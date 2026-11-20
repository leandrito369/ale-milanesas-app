package ar.edu.unam.alemilanesas.gfis;

import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G-FIS (Fase B — N2-T10 en contenedor PostgreSQL 17.11 completamente aislado):
 * 1) Acredita el comportamiento original L02 de V1 (COBRO FOR UPDATE antes que CLIENTE FOR UPDATE).
 * 2) Aplica la correccion SQL candidata V4_CANDIDATE_L02_ANULACION_LAB.sql exclusivamente
 *    en este contenedor (sin editar V1 ni contaminar otros tests) y valida:
 *    - Orden CLIENTE FOR UPDATE -> COBRO FOR UPDATE con revalidacion post-espera.
 *    - Preservacion de D7-A, DC05-07-A, triggers V1/V2/MR-5 y contraasientos completos.
 *    - Regresiones de CobroManualService.anularCobroManual.
 *    - Intercalado concurrente real entre anulacion corregida L02 y cierre T1/K2 sobre
 *      el mismo Cliente sin 40P01 ni 23505.
 */
@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GfisL02AnulacionCandidataLabIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", db::getJdbcUrl);
        registro.add("spring.datasource.username", db::getUsername);
        registro.add("spring.datasource.password", db::getPassword);
    }

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired CobroManualService cobros;

    @Test
    @Order(1)
    void n2T10a_LineaBaseV1EvidenciaInversionL02CobroAntesDeCliente() throws Exception {
        // Antes de aplicar V4_CANDIDATE_L02: V1 intacta adquiere COBRO U antes que CLIENTE U
        String prosrcV1 = jdbc.queryForObject(
                "SELECT prosrc FROM pg_proc WHERE proname = 'fn_mr3_anular_cobro_manual'",
                String.class);
        assertNotNull(prosrcV1);
        int posCobro = prosrcV1.indexOf("FROM \"COBRO\" WHERE \"idCobro\"=p_id_cobro FOR UPDATE");
        int posCliente = prosrcV1.indexOf("FROM \"CLIENTE\" WHERE \"idCliente\"=c.\"idCliente\" FOR UPDATE");
        assertTrue(posCobro > 0 && posCliente > 0 && posCobro < posCliente,
                "Linea base V1 debe reflejar inversion original L02 (COBRO antes de CLIENTE)");

        // Reproducir deadlock 40P01 real sobre fn_mr3_anular_cobro_manual de V1
        // cuando una transaccion alineada tiene CLIENTE U y solicita COBRO U mientras fn_mr3 tiene COBRO U y pide CLIENTE U
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long admin = f.base + 1;
        long cliente = f.base;
        var cobro = cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), cliente, null,
                new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());

        CountDownLatch clienteBloqueado = new CountDownLatch(1);
        try (Connection connAlineada = dataSource.getConnection();
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            connAlineada.setAutoCommit(false);
            int pidAlineada = GfisLabSupport.backendPid(connAlineada);

            Future<String> futAlineada = pool.submit(() -> {
                try (PreparedStatement ps1 = connAlineada.prepareStatement(
                        "SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE")) {
                    ps1.setLong(1, cliente);
                    ps1.executeQuery().close();
                    clienteBloqueado.countDown();

                    await().atMost(Duration.ofSeconds(10)).until(() -> {
                        Integer c = jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                Integer.class, pidAlineada);
                        return c != null && c >= 1;
                    });

                    try (PreparedStatement ps2 = connAlineada.prepareStatement(
                            "SELECT \"idCobro\" FROM \"COBRO\" WHERE \"idCobro\"=? FOR UPDATE")) {
                        ps2.setLong(1, cobro.idCobro());
                        ps2.executeQuery().close();
                    }
                    connAlineada.commit();
                    return "COMMIT";
                } catch (Throwable t) {
                    connAlineada.rollback();
                    return extraerSqlState(t);
                }
            });

            assertTrue(clienteBloqueado.await(10, TimeUnit.SECONDS));
            Future<String> futFnV1 = pool.submit(() -> {
                try {
                    jdbc.execute("SELECT \"fn_mr3_anular_cobro_manual\"("
                            + cobro.idCobro() + "," + admin + ",9810001,9810002,'Prueba inversion L02 V1')");
                    return "COMMIT";
                } catch (Throwable t) {
                    return extraerSqlState(t);
                }
            });

            String rAlin = futAlineada.get(20, TimeUnit.SECONDS);
            String rV1 = futFnV1.get(20, TimeUnit.SECONDS);
            assertTrue("40P01".equals(rAlin) || "40P01".equals(rV1),
                    "V1 fn_mr3_anular_cobro_manual debe reproducir 40P01 frente a CLIENTE->COBRO: alin=" + rAlin + ", v1=" + rV1);
        }
    }

    @Test
    @Order(2)
    void n2T10b_CorreccionCandidataL02AlineaClienteAntesDeCobroPreservaD7ADc0507AYConviveConT1K2() throws Exception {
        // Aplicar V4_CANDIDATE + correccion candidata L02 EXCLUSIVAMENTE en este contenedor aislado
        GfisLabSupport.aplicarMigracionCandidataV4EnLaboratorio(dataSource);
        GfisLabSupport.aplicarCorreccionCandidataL02EnLaboratorio(dataSource);

        String prosrcL02 = jdbc.queryForObject(
                "SELECT prosrc FROM pg_proc WHERE proname = 'fn_mr3_anular_cobro_manual'",
                String.class);
        assertNotNull(prosrcL02);
        int posCliente = prosrcL02.indexOf("FROM \"CLIENTE\" WHERE \"idCliente\" = v_id_cliente FOR UPDATE");
        int posCobroForUpdate = prosrcL02.indexOf("FOR UPDATE", posCliente + 1);
        assertTrue(posCliente > 0 && posCobroForUpdate > posCliente,
                "Correccion L02 debe adquirir CLIENTE FOR UPDATE antes de COBRO FOR UPDATE");

        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long admin = f.base + 1;
        long rep = f.base + 3;
        long cliente = f.base;

        // 1) Regresion: anulacion manual valida + contraasientos completos + triggers MR-5/F01
        var cobroLibre = cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), cliente, null,
                new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());
        var anulado = cobros.anularCobroManual(f.sesiones.get("ADMINISTRADOR"), cobroLibre.idCobro(), "Regresion L02 OK");
        assertEquals("ANULADO", anulado.estado());
        assertEquals(0, BigDecimal.ZERO.compareTo(anulado.saldoConfirmado()));

        // 2) Preservacion DC05-07-A: cobro con reserva activa bloqueado; tras liberacion, anula limpio
        var cobroRes = cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), cliente, null,
                new BigDecimal("80.00"), "EFECTIVO", UUID.randomUUID());
        long pedRes = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                VALUES (?,?,?,'RESERVA_ANTICIPO',50.00,?,clock_timestamp())
                """, cliente, cobroRes.idCobro(), pedRes, admin);
        DataAccessException exDc05 = assertThrows(DataAccessException.class, () ->
                cobros.anularCobroManual(f.sesiones.get("ADMINISTRADOR"), cobroRes.idCobro(), "Bloqueo DC05-07-A"));
        assertTrue(exDc05.getMessage().contains("DC05-07-A"));
        jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                VALUES (?,?,?,'LIBERACION_ANTICIPO',50.00,?,clock_timestamp())
                """, cliente, cobroRes.idCobro(), pedRes, admin);
        assertEquals("ANULADO", cobros.anularCobroManual(
                f.sesiones.get("ADMINISTRADOR"), cobroRes.idCobro(), "Tras liberacion").estado());

        // 3) Intercalado concurrente real: anulacion L02 de cobro manual + cierre T1/K2 sobre MISMO Cliente
        var cobroAAnular = cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), cliente, null,
                new BigDecimal("60.00"), "EFECTIVO", UUID.randomUUID());
        long pedK2 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long rec = GfisLabSupport.crearRecorridoExplicito(jdbc, rep, "REC-L02-CONV");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedK2, cliente, rep, rec,
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("200.00"), "IMP_B");

        jdbc.execute("""
                SELECT setval('"seq_gfis_cobro_id"', GREATEST((SELECT COALESCE(MAX("idCobro"),0) FROM "COBRO"), 1), true);
                SELECT setval('"seq_gfis_mov_cuenta_id"', GREATEST((SELECT COALESCE(MAX("idMovimientoCuenta"),0) FROM "MOVIMIENTO_CUENTA"), 1), true);
                SELECT setval('"seq_gfis_mov_caja_id"', GREATEST((SELECT COALESCE(MAX("idMovimientoCaja"),0) FROM "MOVIMIENTO_CAJA"), 1), true);
                """);

        AtomicInteger deadlocks = new AtomicInteger();
        List<Throwable> errores = new ArrayList<>();
        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> futAnulL02 = pool.submit(() -> {
                listos.countDown();
                try {
                    assertTrue(largada.await(5, TimeUnit.SECONDS));
                    tx.executeWithoutResult(s -> {
                        long idMc = GfisLabSupport.siguientePkMovCuentaB2(jdbc);
                        long idCj = GfisLabSupport.siguientePkMovCajaB2(jdbc);
                        jdbc.execute("SELECT \"fn_mr3_anular_cobro_manual\"("
                                + cobroAAnular.idCobro() + "," + admin + "," + idMc + "," + idCj + ",'Anulacion L02 concurrente')");
                    });
                } catch (Throwable t) {
                    if ("40P01".equals(extraerSqlState(t))) deadlocks.incrementAndGet();
                    errores.add(t);
                }
            });

            Future<GfisLabSupport.ResultadoCierreLab> futT1 = pool.submit(() -> {
                listos.countDown();
                try {
                    assertTrue(largada.await(5, TimeUnit.SECONDS));
                    return tx.execute(s -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                            jdbc, rec, admin, UUID.randomUUID(), "v1", "h-l02-t1", "CONFIRMADO",
                            new BigDecimal("200.00"), new BigDecimal("200.00"),
                            BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), null, null,
                            List.of(new GfisLabSupport.AplicacionPlan(cliente, pedK2, new BigDecimal("200.00"))),
                            false, true));
                } catch (Throwable t) {
                    if ("40P01".equals(extraerSqlState(t))) deadlocks.incrementAndGet();
                    errores.add(t);
                    return null;
                }
            });

            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            futAnulL02.get(20, TimeUnit.SECONDS);
            var resCierre = futT1.get(20, TimeUnit.SECONDS);

            assertEquals(0, deadlocks.get(), "Correccion L02 debe eliminar 40P01 frente a cierre T1/K2");
            assertTrue(errores.isEmpty(), "Errores inesperados en L02 concurrente: " + errores);
            assertNotNull(resCierre);
            assertEquals("CONFIRMADO", resCierre.estadoCierre());

            // Verificar D7-A preservado sobre el Cobro REPARTO recien creado por T1/K2
            Long idCobroReparto = jdbc.queryForObject(
                    "SELECT \"idCobro\" FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                    Long.class, resCierre.idCierre());
            DataAccessException exD7a = assertThrows(DataAccessException.class, () ->
                    jdbc.execute("SELECT \"fn_mr3_anular_cobro_manual\"("
                            + idCobroReparto + "," + admin + ",9820001,9820002,'D7-A tras L02')"));
            assertTrue(exD7a.getMessage().contains("D7-A"));

            // Saldo final exacto: -60 (cobro) + 60 (anulacion L02) - 200 (K2) = -200.00
            BigDecimal saldoFinal = jdbc.queryForObject(
                    "SELECT \"saldoResultante\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY \"numeroSecuenciaCliente\" DESC LIMIT 1",
                    BigDecimal.class, cliente);
            assertEquals(0, new BigDecimal("-200.00").compareTo(saldoFinal));
        }
    }

    private static String extraerSqlState(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx && sqlEx.getSQLState() != null) {
                return sqlEx.getSQLState();
            }
            cur = cur.getCause();
        }
        return t.getClass().getSimpleName();
    }
}
