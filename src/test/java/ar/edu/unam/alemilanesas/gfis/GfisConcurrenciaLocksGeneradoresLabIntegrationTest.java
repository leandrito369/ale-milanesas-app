package ar.edu.unam.alemilanesas.gfis;

import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import ar.edu.unam.alemilanesas.finanzas.RegistrarMovimientoCajaService;
import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * G-FIS (Fase B — Laboratorio de concurrencia, locks A2, generadores B2 e idempotencia C1-a):
 * Verifica dos ADMIN simultaneos, replay/conflicto C1-a, aislamiento de namespaces A2(ns,id),
 * generadores B2-HIBRIDO sin 23505, convivencia con servicios reales de main (CobroManualService,
 * RegistrarMovimientoCajaService #91 y AsignarRepartoService) y hallazgo L02 en V1.
 *
 * NO certifica aciclicidad global de servicios aun no implementados (UC-05/UC-07) ni promueve V4.
 */
@SpringBootTest
@Testcontainers
class GfisConcurrenciaLocksGeneradoresLabIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", db::getJdbcUrl);
        registro.add("spring.datasource.username", db::getUsername);
        registro.add("spring.datasource.password", db::getPassword);
    }

    private static final AtomicBoolean V4_APLICADA = new AtomicBoolean(false);

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired AsignarRepartoService asignaciones;
    @Autowired CobroManualService cobros;
    @Autowired RegistrarMovimientoCajaService cajaManual;

    @BeforeEach
    void prepararLaboratorio() {
        if (V4_APLICADA.compareAndSet(false, true)) {
            GfisLabSupport.aplicarMigracionCandidataV4EnLaboratorio(dataSource);
        }
    }

    @Test
    void dosAdministradoresSimultaneosConClavesDistintasSoloUnoConfirmaYSeConservaPe1() throws Exception {
        PedidoFixture f = new PedidoFixture(jdbc);
        long admin1 = f.base + 1;
        long admin2 = f.base + 50;
        long repartidor = f.base + 3;
        long cliente = f.base;

        jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado",
                    "cambioCredencialPendiente","fechaHoraAlta")
                VALUES (?,?,'HASH_TEST','HABILITADO',false,now())
                """, admin2, "admin2_gfis_" + admin2);
        jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'ADMINISTRADOR',now())", admin2);

        long pedido = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, repartidor, "REC-2ADMIN");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedido, cliente, repartidor, recorrido,
                new BigDecimal("300.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("300.00"), "IMP_B");

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> tareaAdmin1 = () -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    var r = tx.execute(s -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                            jdbc, recorrido, admin1, UUID.randomUUID(), "v1", "h-adm1", "CONFIRMADO",
                            new BigDecimal("300.00"), new BigDecimal("300.00"),
                            BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                            null, null,
                            List.of(new GfisLabSupport.AplicacionPlan(cliente, pedido, new BigDecimal("300.00"))),
                            true, false));
                    return "OK:" + r.idCierre();
                } catch (Exception ex) {
                    return "ERR:" + extraerSqlState(ex);
                }
            };
            Callable<String> tareaAdmin2 = () -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    var r = tx.execute(s -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                            jdbc, recorrido, admin2, UUID.randomUUID(), "v1", "h-adm2", "CONFIRMADO",
                            new BigDecimal("300.00"), new BigDecimal("300.00"),
                            BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                            null, null,
                            List.of(new GfisLabSupport.AplicacionPlan(cliente, pedido, new BigDecimal("300.00"))),
                            true, false));
                    return "OK:" + r.idCierre();
                } catch (Exception ex) {
                    return "ERR:" + extraerSqlState(ex);
                }
            };

            Future<String> f1 = pool.submit(tareaAdmin1);
            Future<String> f2 = pool.submit(tareaAdmin2);
            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();

            String r1 = f1.get(20, TimeUnit.SECONDS);
            String r2 = f2.get(20, TimeUnit.SECONDS);

            long exitos = List.of(r1, r2).stream().filter(s -> s.startsWith("OK:")).count();
            long fallosUnicidad = List.of(r1, r2).stream().filter(s -> s.equals("ERR:23505")).count();
            assertEquals(1, exitos, "Exactamente 1 ADMIN debe confirmar cierre: " + r1 + " / " + r2);
            assertEquals(1, fallosUnicidad, "El segundo ADMIN debe fallar con 23505 (sin 40P01): " + r1 + " / " + r2);

            // Verificacion de invariantes: exactamente 1 cierre, 1 Cobro, 1 Caja, V_e^{vig}=0
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=? AND \"estadoCierre\"='CONFIRMADO'",
                    Integer.class, recorrido));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"APLICACION_RENDICION_K2\" WHERE \"idPedido\"=?",
                    Integer.class, pedido));
            assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
                    "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                    BigDecimal.class, pedido)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replayIdempotenteC1aRetornaReciboHistoricoYDetectaConflictoDeHuella() {
        PedidoFixture f = new PedidoFixture(jdbc);
        long admin = f.base + 1;
        long repartidor = f.base + 3;
        long cliente = f.base;

        long pedido = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, repartidor, "REC-C1A");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedido, cliente, repartidor, recorrido,
                new BigDecimal("180.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("180.00"), "IMP_B");

        UUID clave = UUID.randomUUID();
        var primero = tx.execute(s -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                jdbc, recorrido, admin, clave, "v1", "sha256-payload-A", "CONFIRMADO",
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                null, null,
                List.of(new GfisLabSupport.AplicacionPlan(cliente, pedido, new BigDecimal("180.00"))),
                true, false));
        assertNotNull(primero);
        assertFalse(primero.replay());

        // Misma clave y misma huella => replay historico idempotente sin nuevo Cobro
        var replay = tx.execute(s -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                jdbc, recorrido, admin, clave, "v1", "sha256-payload-A", "CONFIRMADO",
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                null, null,
                List.of(new GfisLabSupport.AplicacionPlan(cliente, pedido, new BigDecimal("180.00"))),
                true, false));
        assertNotNull(replay);
        assertTrue(replay.replay());
        assertEquals(primero.idCierre(), replay.idCierre());
        assertEquals(primero.reciboJson(), replay.reciboJson());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, primero.idCierre()));

        // Misma clave y distinta huella => conflicto 409
        IllegalStateException exConflicto = assertThrows(IllegalStateException.class, () ->
                tx.execute(s -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                        jdbc, recorrido, admin, clave, "v1", "sha256-payload-DISTINTO", "CONFIRMADO",
                        new BigDecimal("180.00"), new BigDecimal("180.00"),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        null, null,
                        List.of(new GfisLabSupport.AplicacionPlan(cliente, pedido, new BigDecimal("180.00"))),
                        true, false)));
        assertTrue(exConflicto.getMessage().contains("409"));
    }

    @Test
    void namespacesA2EliminanColisionCruzadaClienteVsInsumoObservadaEnA1() throws Exception {
        int idCompartido = 424242;
        try (Connection c1 = dataSource.getConnection();
             Connection c2 = dataSource.getConnection()) {
            c1.setAutoCommit(false);
            c2.setAutoCommit(false);

            // Con A2(ns, id): NS_CLIENTE y NS_INSUMO con mismo id NO colisionan
            try (PreparedStatement ps1 = c1.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)");
                 PreparedStatement ps2 = c2.prepareStatement("SELECT pg_try_advisory_xact_lock(?, ?)")) {
                ps1.setInt(1, GfisLabSupport.NS_CLIENTE);
                ps1.setInt(2, idCompartido);
                ps1.execute();

                ps2.setInt(1, GfisLabSupport.NS_INSUMO);
                ps2.setInt(2, idCompartido);
                try (var rs = ps2.executeQuery()) {
                    assertTrue(rs.next() && rs.getBoolean(1),
                            "L08: A2(NS_CLIENTE, id) y A2(NS_INSUMO, id) no deben bloquearse entre si");
                }
            }

            // Contraste: mismo namespace + mismo id SI serializa deterministicamente
            try (PreparedStatement psSame = c2.prepareStatement("SELECT pg_try_advisory_xact_lock(?, ?)")) {
                psSame.setInt(1, GfisLabSupport.NS_CLIENTE);
                psSame.setInt(2, idCompartido);
                try (var rs = psSame.executeQuery()) {
                    assertTrue(rs.next() && !rs.getBoolean(1),
                            "A2(NS_CLIENTE, id) repetido debe excluir mutuamente");
                }
            }
            c1.rollback();
            c2.rollback();
        }
    }

    @Test
    void generadoresB2HibridoPermitenConcurrenciaMultiClienteSinLockGlobal2929001Ni23505() throws Exception {
        PedidoFixture f = new PedidoFixture(jdbc);
        long admin = f.base + 1;
        long repartidor = f.base + 3;
        long clienteA = f.base;
        long clienteB = f.base + 1;

        long pedA = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long pedB = pedidos.cargarPedido(f.vendedor(), new CargarPedidoService.DatosPedido(
                clienteB, f.hoy.plusDays(2),
                List.of(new CargarPedidoService.ItemPedido(f.base, new BigDecimal("1.000"))),
                new CargarPedidoService.DireccionEntrega("Calle B2", "789", null, null, "Posadas", "Misiones", null, null),
                UUID.randomUUID())).idPedido();

        long recA = GfisLabSupport.crearRecorridoExplicito(jdbc, repartidor, "REC-B2-A");
        long recB = GfisLabSupport.crearRecorridoExplicito(jdbc, repartidor, "REC-B2-B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA, clienteA, repartidor, recA,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedB, clienteB, repartidor, recB,
                new BigDecimal("120.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("120.00"), "IMP_B");

        // Re-sincronizar secuencias B2 > MAX(id) actual antes de la prueba concurrente pura B2
        jdbc.execute("""
                SELECT setval('"seq_gfis_cobro_id"', GREATEST((SELECT COALESCE(MAX("idCobro"),0) FROM "COBRO"), 1), true);
                SELECT setval('"seq_gfis_mov_cuenta_id"', GREATEST((SELECT COALESCE(MAX("idMovimientoCuenta"),0) FROM "MOVIMIENTO_CUENTA"), 1), true);
                SELECT setval('"seq_gfis_mov_caja_id"', GREATEST((SELECT COALESCE(MAX("idMovimientoCaja"),0) FROM "MOVIMIENTO_CAJA"), 1), true);
                """);

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // usarPuenteLegacy2929001 = false, usarSecuenciasB2 = true
            Future<GfisLabSupport.ResultadoCierreLab> fa = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                return tx.execute(s -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                        jdbc, recA, admin, UUID.randomUUID(), "v1", "h-b2-a", "CONFIRMADO",
                        new BigDecimal("100.00"), new BigDecimal("100.00"),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        null, null,
                        List.of(new GfisLabSupport.AplicacionPlan(clienteA, pedA, new BigDecimal("100.00"))),
                        false, true));
            });
            Future<GfisLabSupport.ResultadoCierreLab> fb = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                return tx.execute(s -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                        jdbc, recB, admin, UUID.randomUUID(), "v1", "h-b2-b", "CONFIRMADO",
                        new BigDecimal("120.00"), new BigDecimal("120.00"),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        null, null,
                        List.of(new GfisLabSupport.AplicacionPlan(clienteB, pedB, new BigDecimal("120.00"))),
                        false, true));
            });
            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();

            var ra = fa.get(20, TimeUnit.SECONDS);
            var rb = fb.get(20, TimeUnit.SECONDS);
            assertEquals("CONFIRMADO", ra.estadoCierre());
            assertEquals("CONFIRMADO", rb.estadoCierre());

            // Verificar consecutividad de ordinales de negocio por Cliente (sin huecos)
            List<Long> seqB = jdbc.queryForList(
                    "SELECT \"numeroSecuenciaCliente\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY 1",
                    Long.class, clienteB);
            for (int i = 0; i < seqB.size(); i++) {
                assertEquals(i + 1L, seqB.get(i), "Ordinal numeroSecuenciaCliente debe permanecer consecutivo");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void convivenciaConcurrenteConServiciosRealesMainUc29Uc22Uc43Sin40P01Ni23505() throws Exception {
        PedidoFixture f = new PedidoFixture(jdbc);
        long admin = f.base + 1;
        long repartidor = f.base + 3;
        long cliente = f.base;

        long pedEntrega = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long pedAsignar = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, repartidor, "REC-CONV");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedEntrega, cliente, repartidor, recorrido,
                new BigDecimal("250.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("250.00"), "IMP_B");

        // Barrera determinista: una transaccion externa retiene temporalmente CLIENTE para forzar
        // espera real simultanea de CobroManualService, UC-22 (PR #91), UC-43 y cierre T1+K2
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger duplicados = new AtomicInteger();
        List<Throwable> errores = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try (Connection barrera = dataSource.getConnection()) {
            barrera.setAutoCommit(false);
            int pidBarrera = GfisLabSupport.backendPid(barrera);
            try (PreparedStatement ps = barrera.prepareStatement(
                    "SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE")) {
                ps.setLong(1, cliente);
                ps.executeQuery().close();
            }

            Future<?> futT1 = pool.submit(() -> {
                try {
                    tx.executeWithoutResult(s -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                            jdbc, recorrido, admin, UUID.randomUUID(), "v1", "h-conv", "CONFIRMADO",
                            new BigDecimal("250.00"), new BigDecimal("250.00"),
                            BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                            null, null,
                            List.of(new GfisLabSupport.AplicacionPlan(cliente, pedEntrega, new BigDecimal("250.00"))),
                            true, false));
                } catch (Throwable t) {
                    clasificarError(t, deadlocks, duplicados, errores);
                }
            });

            Future<?> futUc29 = pool.submit(() -> {
                try {
                    cobros.registrarCobro(
                            f.sesiones.get("ADMINISTRADOR"), cliente, null,
                            new BigDecimal("50.00"), "EFECTIVO", UUID.randomUUID());
                } catch (Throwable t) {
                    clasificarError(t, deadlocks, duplicados, errores);
                }
            });

            Future<?> futUc22 = pool.submit(() -> {
                try {
                    cajaManual.registrarMovimientoCaja(
                            f.sesiones.get("ADMINISTRADOR"), "INGRESO",
                            new BigDecimal("75.00"), "Reposicion cambio caja", "Prueba convivencia #91",
                            UUID.randomUUID());
                } catch (Throwable t) {
                    clasificarError(t, deadlocks, duplicados, errores);
                }
            });

            Future<?> futUc43 = pool.submit(() -> {
                try {
                    asignaciones.asignarOReasignar(
                            f.vendedor(),
                            new AsignarRepartoService.DatosAsignacion(pedAsignar, repartidor, null));
                } catch (Throwable t) {
                    clasificarError(t, deadlocks, duplicados, errores);
                }
            });

            // Esperar a que al menos un escritor financiero real quede bloqueado por pidBarrera en pg_locks
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                Integer bloqueados = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                        Integer.class, pidBarrera);
                return bloqueados != null && bloqueados >= 1;
            });

            // Soltar barrera: todos deben completar limpiamente sin 40P01 ni 23505
            barrera.commit();

            futT1.get(20, TimeUnit.SECONDS);
            futUc29.get(20, TimeUnit.SECONDS);
            futUc22.get(20, TimeUnit.SECONDS);
            futUc43.get(20, TimeUnit.SECONDS);

            assertEquals(0, deadlocks.get(), "No debe ocurrir ningun deadlock 40P01");
            assertEquals(0, duplicados.get(), "No debe ocurrir ninguna colision 23505");
            assertTrue(errores.isEmpty(), "Errores inesperados en convivencia: " + errores);

            // Verificar conservacion de saldos: -250 (K2) - 50 (UC-29 manual) = -300.00
            BigDecimal saldoFinal = jdbc.queryForObject(
                    "SELECT \"saldoResultante\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY \"numeroSecuenciaCliente\" DESC LIMIT 1",
                    BigDecimal.class, cliente);
            assertEquals(0, new BigDecimal("-300.00").compareTo(saldoFinal));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void preflightAcreditaHallazgoL02EnFnMr3AnularCobroManualDeV1SinAlterarV1() {
        String prosrc = jdbc.queryForObject(
                "SELECT prosrc FROM pg_proc WHERE proname = 'fn_mr3_anular_cobro_manual'",
                String.class);
        assertNotNull(prosrc);
        int posCobroForUpdate = prosrc.indexOf("FROM \"COBRO\" WHERE \"idCobro\"=p_id_cobro FOR UPDATE");
        int posClienteForUpdate = prosrc.indexOf("FROM \"CLIENTE\" WHERE \"idCliente\"=c.\"idCliente\" FOR UPDATE");
        assertTrue(posCobroForUpdate > 0 && posClienteForUpdate > 0,
                "La funcion V1 debe existir intacta en el esquema base");
        assertTrue(posCobroForUpdate < posClienteForUpdate,
                "Hallazgo L02 acreditado: V1 bloquea COBRO antes que CLIENTE; requiere reordenamiento controlado en Fase C/R09.1");
    }

    private static void clasificarError(
            Throwable t, AtomicInteger deadlocks, AtomicInteger duplicados, List<Throwable> errores) {
        String state = extraerSqlState(t);
        if ("40P01".equals(state)) {
            deadlocks.incrementAndGet();
        } else if ("23505".equals(state)) {
            duplicados.incrementAndGet();
        }
        errores.add(t);
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
