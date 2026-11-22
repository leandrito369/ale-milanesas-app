package ar.edu.unam.alemilanesas.gfis;

import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import ar.edu.unam.alemilanesas.finanzas.RegistrarMovimientoCajaService;
import ar.edu.unam.alemilanesas.ventas.AsignacionRepartoFixture;
import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import org.junit.jupiter.api.Test;
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
 * G-FIS (Fase B — Campana C3 ampliada sobre Flyway V4 en laboratorio aislado):
 * Cubre N2-T08 (C3-03 con PE-1), N2-T09 (C3-08 con PE-1/IMP-B y UC-22),
 * N2-T13 (multi-Cliente orden invertido [A,B] vs [B,A]), N2-T16 (recorrido 4x8
 * y recuperacion tras 40P01 forzado), N2-T17 (5 senales literales RCG-06 e IMP-C),
 * N2-T18 (422 COBERTURA_NO_ACREDITADA, rollback y diagnostico separado) y
 * endurecimiento de N2-T05 / N2-T14 (ambito de replay, actor no-ADMIN y D1).
 *
 * Contenedor independiente: NO aplica L02 (reservado a su propia clase aislada).
 * NO modifica src/main/** ni promueve V4.
 */
@SpringBootTest
@Testcontainers
class GfisCampanaC3AmpliadaLabIntegrationTest {
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
    @Autowired AsignarRepartoService asignaciones;
    @Autowired CobroManualService cobros;
    @Autowired RegistrarMovimientoCajaService cajaManual;

    // =========================================================================
    // N2-T08: C3-03 (UC-43 real frente a UC-05/PE-1 sobre Flyway V4)
    // =========================================================================

    @Test
    void n2T08a_C303InversionAsignacionAntesDePedidoConPe1Produce40P01() throws Exception {
        var f = tx.execute(s -> new AsignacionRepartoFixture(jdbc, pedidos));
        assertNotNull(f);
        asignaciones.asignarOReasignar(f.vendedor(), f.datos(f.primero, null));

        CountDownLatch bloqueoAsignacion = new CountDownLatch(1);
        try (Connection connUc05 = dataSource.getConnection();
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            connUc05.setAutoCommit(false);
            int pidUc05 = GfisLabSupport.backendPid(connUc05);

            Future<String> futUc05 = pool.submit(() -> {
                try (PreparedStatement ps1 = connUc05.prepareStatement("""
                        SELECT "numeroAsignacion" FROM "ASIGNACION_REPARTO"
                         WHERE "idPedido"=? AND "fechaHoraFin" IS NULL FOR UPDATE
                        """)) {
                    ps1.setLong(1, f.pendiente);
                    try (ResultSet rs = ps1.executeQuery()) {
                        assertTrue(rs.next());
                    }
                    bloqueoAsignacion.countDown();

                    await().atMost(Duration.ofSeconds(10)).until(() -> {
                        Integer c = jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                Integer.class, pidUc05);
                        return c != null && c >= 1;
                    });

                    try (PreparedStatement ps2 = connUc05.prepareStatement(
                            "SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=? FOR UPDATE")) {
                        ps2.setLong(1, f.pendiente);
                        ps2.executeQuery().close();
                    }
                    connUc05.commit();
                    return "COMMIT";
                } catch (Exception t) {
                    connUc05.rollback();
                    return extraerSqlState(t);
                }
            });

            assertTrue(bloqueoAsignacion.await(10, TimeUnit.SECONDS));
            Future<String> futUc43 = pool.submit(() -> {
                try {
                    asignaciones.asignarOReasignar(f.vendedor(), f.datos(f.segundo, "Reasignacion C3-03"));
                    return "COMMIT";
                } catch (Exception t) {
                    return extraerSqlState(t);
                }
            });

            String rUc05 = futUc05.get(20, TimeUnit.SECONDS);
            String rUc43 = futUc43.get(20, TimeUnit.SECONDS);
            assertTrue("40P01".equals(rUc05) || "40P01".equals(rUc43),
                    "La inversion ASIGNACION->PEDIDO debe reproducir deadlock 40P01: uc05=" + rUc05 + ", uc43=" + rUc43);
        }
    }

    @Test
    void n2T08b_C303OrdenAlineadoPedidoAsignacionConPe1SerializaYRevalidaUc43() throws Exception {
        var f = tx.execute(s -> new AsignacionRepartoFixture(jdbc, pedidos));
        assertNotNull(f);
        var asig = asignaciones.asignarOReasignar(f.vendedor(), f.datos(f.primero, null));
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, f.primero, "REC-T08");
        GfisLabSupport.vincularAsignacionARecorridoEnLab(jdbc, recorrido, f.pendiente, asig.numeroAsignacion());

        CountDownLatch pedidoBloqueado = new CountDownLatch(1);
        CountDownLatch liberarEntrega = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> futUc05 = pool.submit(() -> tx.execute(status -> {
                // Orden alineado: CLIENTE U -> PEDIDO U -> ASIGNACION U -> ENTREGA + PE-1
                jdbc.queryForObject("SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE",
                        Long.class, f.origen.base);
                jdbc.queryForObject("SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=? FOR UPDATE",
                        String.class, f.pendiente);
                pedidoBloqueado.countDown();
                try {
                    assertTrue(liberarEntrega.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                Long repVigente = jdbc.queryForObject("""
                        SELECT "idUsuarioRepartidor" FROM "ASIGNACION_REPARTO"
                         WHERE "idPedido"=? AND "fechaHoraFin" IS NULL FOR UPDATE
                        """, Long.class, f.pendiente);
                assertEquals(f.primero, repVigente);

                GfisLabSupport.registrarEntregaPe1EnLaboratorio(
                        jdbc, f.pendiente, f.origen.base, f.primero, recorrido,
                        new BigDecimal("250.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        new BigDecimal("250.00"), "IMP_B");
                return "ENTREGADO";
            }));

            assertTrue(pedidoBloqueado.await(10, TimeUnit.SECONDS));
            Future<Exception> futUc43 = pool.submit(() -> {
                try {
                    asignaciones.asignarOReasignar(f.vendedor(), f.datos(f.segundo, "Tarde tras entrega"));
                    return null;
                } catch (Exception t) {
                    return t;
                }
            });

            // Esperar a que UC-43 quede bloqueado esperando PEDIDO en pg_locks
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                Integer esperando = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0",
                        Integer.class);
                return esperando != null && esperando >= 1;
            });
            liberarEntrega.countDown();

            assertEquals("ENTREGADO", futUc05.get(20, TimeUnit.SECONDS));
            Exception errUc43 = futUc43.get(20, TimeUnit.SECONDS);
            assertNotNull(errUc43, "UC-43 debe revalidar estado ENTREGADO y rechazar reasignacion");
            assertNotEquals("40P01", extraerSqlState(errUc43), "Orden alineado no debe producir 40P01");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                    Integer.class, f.pendiente));
        }
    }

    // =========================================================================
    // N2-T09: C3-08 (UC-29 real frente a UC-05/PE-1/IMP-B y UC-22)
    // =========================================================================

    @Test
    void n2T09a_C308InversionPedidoAntesDeClienteProduce40P01ConUc29() throws Exception {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idPedido = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long idCliente = f.base;

        CountDownLatch pedidoBloqueado = new CountDownLatch(1);
        try (Connection connUc05 = dataSource.getConnection();
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            connUc05.setAutoCommit(false);
            int pidUc05 = GfisLabSupport.backendPid(connUc05);

            Future<String> futUc05 = pool.submit(() -> {
                try (PreparedStatement ps1 = connUc05.prepareStatement(
                        "SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=? FOR UPDATE")) {
                    ps1.setLong(1, idPedido);
                    ps1.executeQuery().close();
                    pedidoBloqueado.countDown();

                    await().atMost(Duration.ofSeconds(10)).until(() -> {
                        Integer c = jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                Integer.class, pidUc05);
                        return c != null && c >= 1;
                    });

                    try (PreparedStatement ps2 = connUc05.prepareStatement(
                            "SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE")) {
                        ps2.setLong(1, idCliente);
                        ps2.executeQuery().close();
                    }
                    connUc05.commit();
                    return "COMMIT";
                } catch (Exception t) {
                    connUc05.rollback();
                    return extraerSqlState(t);
                }
            });

            assertTrue(pedidoBloqueado.await(10, TimeUnit.SECONDS));
            Future<String> futUc29 = pool.submit(() -> {
                try {
                    cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), idCliente, idPedido,
                            new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());
                    return "COMMIT";
                } catch (Exception t) {
                    return extraerSqlState(t);
                }
            });

            String rUc05 = futUc05.get(20, TimeUnit.SECONDS);
            String rUc29 = futUc29.get(20, TimeUnit.SECONDS);
            assertTrue("40P01".equals(rUc05) || "40P01".equals(rUc29),
                    "Inversion PEDIDO->CLIENTE frente a UC-29 debe producir 40P01: uc05=" + rUc05 + ", uc29=" + rUc29);
        }
    }

    @Test
    void n2T09b_C308OrdenAlineadoConviveConUc29Uc22EImpBSin40P01Ni23505() throws Exception {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idAdmin = f.base + 1;
        long idRepartidor = f.base + 3;
        long idCliente = f.base;

        long ped = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRepartidor, "REC-T09");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, ped, idRepartidor, f.base, recorrido);

        // Cobro anticipo con reserva IMP-B de 120.00 sobre ped
        var anticipo = cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), idCliente, null,
                new BigDecimal("120.00"), "EFECTIVO", UUID.randomUUID());
        jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                VALUES (?,?,?,'RESERVA_ANTICIPO',120.00,?,clock_timestamp())
                """, idCliente, anticipo.idCobro(), ped, idAdmin);

        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger duplicados = new AtomicInteger();
        List<Exception> errores = new ArrayList<>();

        CountDownLatch listos = new CountDownLatch(3);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            Future<?> futUc05Pe1 = pool.submit(() -> {
                listos.countDown();
                try {
                    assertTrue(largada.await(5, TimeUnit.SECONDS));
                    tx.executeWithoutResult(s -> {
                        // Orden alineado: puente 2929001 -> A2(CLIENTE) -> CLIENTE U -> PEDIDO U -> IMP-B -> PE-1
                        GfisLabSupport.bloquearPuenteLegacyFinanciero(jdbc);
                        GfisLabSupport.bloquearA2(jdbc, GfisLabSupport.NS_CLIENTE, idCliente);
                        jdbc.queryForObject("SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE",
                                Long.class, idCliente);
                        jdbc.queryForObject("SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=? FOR UPDATE",
                                String.class, ped);

                        // Consolidar reserva 100.00 y liberar 20.00 (C_e=300, P_e=100, E_e=200, D_e=150 -> V_e^0=150)
                        jdbc.update("""
                                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                                VALUES (?,?,?,'CONSOLIDACION_ANTICIPO',100.00,?,clock_timestamp())
                                """, idCliente, anticipo.idCobro(), ped, idAdmin);
                        jdbc.update("""
                                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                                VALUES (?,?,?,'LIBERACION_ANTICIPO',20.00,?,clock_timestamp())
                                """, idCliente, anticipo.idCobro(), ped, idAdmin);

                        GfisLabSupport.registrarEntregaPe1EnLaboratorio(
                                jdbc, ped, idCliente, idRepartidor, recorrido,
                                new BigDecimal("300.00"), new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2),
                                new BigDecimal("150.00"), "IMP_B");

                        long idMov = jdbc.queryForObject(
                                "SELECT COALESCE(MAX(\"idMovimientoCuenta\"),0)+1 FROM \"MOVIMIENTO_CUENTA\"", Long.class);
                        long seq = jdbc.queryForObject(
                                "SELECT COALESCE(MAX(\"numeroSecuenciaCliente\"),0)+1 FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=?",
                                Long.class, idCliente);
                        BigDecimal saldoPrev = jdbc.queryForObject(
                                "SELECT \"saldoResultante\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY \"numeroSecuenciaCliente\" DESC LIMIT 1",
                                BigDecimal.class, idCliente);
                        jdbc.update("""
                                INSERT INTO "MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCliente","numeroSecuenciaCliente",
                                    "tipoMovimiento","importe","fechaHoraMovimiento","saldoResultante")
                                VALUES (?,?,?,'CARGO_POR_VENTA',300.00,clock_timestamp(),?)
                                """, idMov, idCliente, seq, saldoPrev.add(new BigDecimal("300.00")));
                        jdbc.update("INSERT INTO \"ORIGEN_MOVIMIENTO_CUENTA\" (\"idMovimientoCuenta\",\"idPedidoEntrega\") VALUES (?,?)",
                                idMov, ped);
                    });
                } catch (Exception t) {
                    registrarError(t, deadlocks, duplicados, errores);
                }
            });

            Future<?> futUc29 = pool.submit(() -> {
                listos.countDown();
                try {
                    assertTrue(largada.await(5, TimeUnit.SECONDS));
                    cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), idCliente, null,
                            new BigDecimal("80.00"), "EFECTIVO", UUID.randomUUID());
                } catch (Exception t) {
                    registrarError(t, deadlocks, duplicados, errores);
                }
            });

            Future<?> futUc22 = pool.submit(() -> {
                listos.countDown();
                try {
                    assertTrue(largada.await(5, TimeUnit.SECONDS));
                    cajaManual.registrarMovimientoCaja(f.sesiones.get("ADMINISTRADOR"), "INGRESO",
                            new BigDecimal("50.00"), "Fondo fijo", "N2-T09", UUID.randomUUID());
                } catch (Exception t) {
                    registrarError(t, deadlocks, duplicados, errores);
                }
            });

            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            futUc05Pe1.get(20, TimeUnit.SECONDS);
            futUc29.get(20, TimeUnit.SECONDS);
            futUc22.get(20, TimeUnit.SECONDS);

            assertEquals(0, deadlocks.get(), "0 deadlocks 40P01 en orden alineado N2-T09");
            assertEquals(0, duplicados.get(), "0 colisiones 23505 en N2-T09");
            assertTrue(errores.isEmpty(), "Sin errores en N2-T09: " + errores);

            // Saldo esperado: -120 (anticipo) + 300 (cargo) - 80 (cobro) = +100.00
            BigDecimal saldoFinal = jdbc.queryForObject(
                    "SELECT \"saldoResultante\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY \"numeroSecuenciaCliente\" DESC LIMIT 1",
                    BigDecimal.class, idCliente);
            assertEquals(0, new BigDecimal("100.00").compareTo(saldoFinal));

            // Disponibilidad IMP-A del anticipo tras consolidar 100 y liberar 20 => 20.00 libre
            BigDecimal libre = jdbc.queryForObject(
                    "SELECT \"importeLibreDisponible\" FROM \"VW_IMP_A_DISPONIBILIDAD_COBRO\" WHERE \"idCobro\"=?",
                    BigDecimal.class, anticipo.idCobro());
            assertEquals(0, new BigDecimal("20.00").compareTo(libre));
        }
    }

    // =========================================================================
    // N2-T13: Multi-Cliente con orden invertido [A,B] vs [B,A]
    // =========================================================================

    @Test
    void n2T13_MultiClienteOrdenInvertidoABvsBASinCiclosNiDuplicacion() throws Exception {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idVendedor = f.base;
        long idAdmin = f.base + 1;
        long idRep1 = f.base + 3;
        long idRep2 = tx.execute(s -> GfisLabSupport.crearRepartidorAdicionalEnLab(jdbc, f.base + 52));
        long clienteA = f.base;
        long clienteB = f.base + 1;

        long pedA1 = crearPedidoParaCliente(f, clienteA);
        long pedB1 = crearPedidoParaCliente(f, clienteB);
        long pedA2 = crearPedidoParaCliente(f, clienteA);
        long pedB2 = crearPedidoParaCliente(f, clienteB);

        long rec1 = GfisLabSupport.crearRecorridoExplicito(jdbc, idRep1, "REC-T13-AB");
        long rec2 = GfisLabSupport.crearRecorridoExplicito(jdbc, idRep2, "REC-T13-BA");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedA1, idRep1, idVendedor, rec1);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedB1, idRep1, idVendedor, rec1);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedA2, idRep2, idVendedor, rec2);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedB2, idRep2, idVendedor, rec2);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA1, clienteA, idRep1, rec1,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), new BigDecimal("100.00"), "IMP_B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedB1, clienteB, idRep1, rec1,
                new BigDecimal("150.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), new BigDecimal("150.00"), "IMP_B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA2, clienteA, idRep2, rec2,
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), new BigDecimal("200.00"), "IMP_B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedB2, clienteB, idRep2, rec2,
                new BigDecimal("250.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), new BigDecimal("250.00"), "IMP_B");

        sincronizarSecuenciasB2();

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<GfisLabSupport.ResultadoCierreLab> f1 = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                return tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, rec1, idAdmin, UUID.randomUUID(), "v1", "h-t13-1",
                        new BigDecimal("250.00"), new BigDecimal("250.00"),
                        List.of(
                                new GfisLabSupport.AplicacionPlan(clienteA, pedA1, new BigDecimal("100.00")),
                                new GfisLabSupport.AplicacionPlan(clienteB, pedB1, new BigDecimal("150.00"))),
                        false, true));
            });
            Future<GfisLabSupport.ResultadoCierreLab> f2 = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                return tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, rec2, idAdmin, UUID.randomUUID(), "v1", "h-t13-2",
                        new BigDecimal("450.00"), new BigDecimal("450.00"),
                        List.of(
                                new GfisLabSupport.AplicacionPlan(clienteB, pedB2, new BigDecimal("250.00")),
                                new GfisLabSupport.AplicacionPlan(clienteA, pedA2, new BigDecimal("200.00"))),
                        false, true));
            });
            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();

            assertEquals("CONFIRMADO", f1.get(20, TimeUnit.SECONDS).estadoCierre());
            assertEquals("CONFIRMADO", f2.get(20, TimeUnit.SECONDS).estadoCierre());

            // Verificar consecutividad de ordinales por ambos Clientes
            for (long c : List.of(clienteA, clienteB)) {
                List<Long> seqs = jdbc.queryForList(
                        "SELECT \"numeroSecuenciaCliente\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY 1",
                        Long.class, c);
                for (int i = 0; i < seqs.size(); i++) {
                    assertEquals(i + 1L, seqs.get(i));
                }
            }
            // Conservacion A_e + V_e^{vig} = V_e^0 en las 4 entregas
            Integer pendientes = jdbc.queryForObject(
                    "SELECT count(*) FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\" IN (?,?,?,?) AND \"reconocimientoVigenteVvig\" <> 0",
                    Integer.class, pedA1, pedB1, pedA2, pedB2);
            assertEquals(0, pendientes);
        }
    }

    // =========================================================================
    // N2-T16: Recorrido grande (4 Clientes / 8 Entregas), aborto 40P01 y retry
    // =========================================================================

    @Test
    void n2T16_RecorridoGrande4Clientes8EntregasYRecuperacionTras40P01Forzado() throws Exception {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idAdmin = f.base + 1;
        long idRep = f.base + 3;

        // Crear 2 clientes adicionales para totalizar 4 Clientes activos (8 entregas = 2 c/u)
        long c1 = f.base;
        long c2 = f.base + 1;
        long c3 = f.base + 60;
        long c4 = f.base + 61;
        for (long c : List.of(c3, c4)) {
            jdbc.update("""
                    INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal","estado","categoriaComercial","fechaHoraAlta")
                    VALUES (?,?,'3764999000','ACTIVO','CUMPLIDOR',now())
                    """, c, "Cliente T16 " + c);
        }

        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRep, "REC-T16-4x8");
        List<GfisLabSupport.AplicacionPlan> plan = new ArrayList<>();
        BigDecimal totalEsperado = BigDecimal.ZERO.setScale(2);
        for (long c : List.of(c1, c2, c3, c4)) {
            for (int k = 0; k < 2; k++) {
                long p = crearPedidoParaCliente(f, c);
                BigDecimal monto = new BigDecimal("125.00");
                GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, p, idRep, f.base, recorrido);
                GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, p, c, idRep, recorrido,
                        monto, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), monto, "IMP_B");
                plan.add(new GfisLabSupport.AplicacionPlan(c, p, monto));
                totalEsperado = totalEsperado.add(monto);
            }
        }
        assertEquals(8, plan.size());
        sincronizarSecuenciasB2();

        UUID claveCierre = UUID.randomUUID();

        // Fase 1: forzar un aborto real por deadlock 40P01 (c1 vs c2 en orden cruzado)
        // antes de cometer el cierre con claveCierre, verificando rollback integro
        CountDownLatch t1TomoC1 = new CountDownLatch(1);
        CountDownLatch t2TomoC2 = new CountDownLatch(1);
        try (Connection connInterferencia = dataSource.getConnection();
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            connInterferencia.setAutoCommit(false);

            Future<String> futIntentoFallido = pool.submit(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE", Long.class, c1);
                        t1TomoC1.countDown();
                        try {
                            assertTrue(t2TomoC2.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                        // Intentar tomar c2 mientras connInterferencia tiene c2 y pide c1 => 40P01 real
                        jdbc.queryForObject("SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE", Long.class, c2);
                    });
                    return "COMMIT";
                } catch (Exception t) {
                    return extraerSqlState(t);
                }
            });

            Future<String> futInterferencia = pool.submit(() -> {
                try {
                    try (PreparedStatement ps1 = connInterferencia.prepareStatement(
                            "SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE")) {
                        ps1.setLong(1, c2);
                        ps1.executeQuery().close();
                    }
                    t2TomoC2.countDown();
                    assertTrue(t1TomoC1.await(5, TimeUnit.SECONDS));
                    try (PreparedStatement ps2 = connInterferencia.prepareStatement(
                            "SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE")) {
                        ps2.setLong(1, c1);
                        ps2.executeQuery().close();
                    }
                    connInterferencia.commit();
                    return "COMMIT";
                } catch (Exception t) {
                    connInterferencia.rollback();
                    return extraerSqlState(t);
                }
            });

            String r1 = futIntentoFallido.get(20, TimeUnit.SECONDS);
            String r2 = futInterferencia.get(20, TimeUnit.SECONDS);
            assertTrue("40P01".equals(r1) || "40P01".equals(r2), "Debe reproducirse 40P01 real antes del retry");
        }

        // Confirmar 0 residuos tras el aborto 40P01
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=?", Integer.class, recorrido));

        // Fase 2: reintento en NUEVA transaccion con la misma claveIdempotenciaCierre (4 Clientes / 8 Entregas)
        final BigDecimal esperadoFinal = totalEsperado;
        long inicioNs = System.nanoTime();
        var resRetry = tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                jdbc, recorrido, idAdmin, claveCierre, "v1", "h-t16-4x8",
                esperadoFinal, esperadoFinal,
                plan, false, true));
        long latenciaMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioNs);

        assertNotNull(resRetry);
        assertEquals("CONFIRMADO", resRetry.estadoCierre());
        assertTrue(latenciaMs < 10_000, "Latencia de cierre 4x8 debe mantenerse acotada: " + latenciaMs + "ms");
        assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, resRetry.idCierre()));
        assertEquals(8, jdbc.queryForObject(
                "SELECT count(*) FROM \"APLICACION_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, resRetry.idCierre()));
    }

    // =========================================================================
    // N2-T17: Cinco senales literales RCG-06 y contencion selectiva IMP-C
    // =========================================================================

    @Test
    void n2T17_CincoSenalesExactasRcg06YContencionSelectivaImpCSinBackfill() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idRep = f.base + 3;

        long cA = f.base + 70;
        long cB = f.base + 71;
        long cC = f.base + 72;
        long cD = f.base + 73;
        long cE = f.base + 74;
        for (long c : List.of(cA, cB, cC, cD, cE)) {
            jdbc.update("""
                    INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal","estado","categoriaComercial","fechaHoraAlta")
                    VALUES (?,?,'3764777000','ACTIVO','CUMPLIDOR',now())
                    """, c, "Cliente RCG06 " + c);
        }

        // Senal A: COBRO CONFIRMADO con idPedido hacia Pedido PENDIENTE sin reserva en IMP-B
        long pedA = crearPedidoParaCliente(f, cA);
        var cobroA = cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), cA, pedA,
                new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());
        var resA = GfisLabSupport.evaluarPreflightRcg06Cliente(jdbc, cA);
        assertEquals(1, resA.senalA_cobroIdPedidoPendienteSinReserva());
        assertTrue(resA.requiereContencionSelectivaImpC());

        // Senal B: saldo neto a favor (< 0) y Pedido PENDIENTE
        crearPedidoParaCliente(f, cB);
        cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), cB, null,
                new BigDecimal("200.00"), "EFECTIVO", UUID.randomUUID());
        var resB = GfisLabSupport.evaluarPreflightRcg06Cliente(jdbc, cB);
        assertEquals(1, resB.senalB_saldoAFavorConPedidosPendientes());
        assertTrue(resB.requiereContencionSelectivaImpC());

        // Senal C: REEMBOLSO PENDIENTE sin conciliar
        var cobroC = cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), cC, null,
                new BigDecimal("150.00"), "EFECTIVO", UUID.randomUUID());
        jdbc.update("""
                INSERT INTO "REEMBOLSO" ("idReembolso","idCobro","importeReembolso",
                    "fechaHoraCreacion","motivo","estado")
                VALUES (?,?,?,clock_timestamp(),'Reembolso pendiente RCG-06','PENDIENTE')
                """, f.base + 790, cobroC.idCobro(), new BigDecimal("150.00"));
        var resC = GfisLabSupport.evaluarPreflightRcg06Cliente(jdbc, cC);
        assertEquals(1, resC.senalC_reembolsoPendienteSinConciliar());
        assertTrue(resC.requiereContencionSelectivaImpC());

        // Senal D: credito confirmado sin idPedido potencialmente consumido (sin evento IMP-B)
        cobros.registrarCobro(f.sesiones.get("ADMINISTRADOR"), cD, null,
                new BigDecimal("90.00"), "EFECTIVO", UUID.randomUUID());
        var resD = GfisLabSupport.evaluarPreflightRcg06Cliente(jdbc, cD);
        assertEquals(1, resD.senalD_creditoSinIdPedidoPotencialmenteConsumido());
        assertTrue(resD.requiereContencionSelectivaImpC());

        // Senal E: pago ONLINE confirmado + Entrega pendiente de cierre
        long pedE = crearPedidoParaCliente(f, cE);
        long recE = GfisLabSupport.crearRecorridoExplicito(jdbc, idRep, "REC-RCG06-E");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedE, idRep, f.base, recE);
        sincronizarSecuenciasB2();
        long idCobroOnline = GfisLabSupport.siguientePkCobroB2(jdbc);
        jdbc.update("""
                INSERT INTO "COBRO" ("idCobro","idCliente","idPedido","idUsuarioRegistrador","fechaHoraCobro",
                    "importe","medioPago","origen","claveIdempotenciaOrigen","estadoCobro")
                VALUES (?,?,NULL,NULL,clock_timestamp(),180.00,'MERCADOPAGO','ONLINE',?,'CONFIRMADO')
                """, idCobroOnline, cE, UUID.randomUUID());
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedE, cE, idRep, recE,
                new BigDecimal("180.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("180.00"), "IMP_C");
        var resE = GfisLabSupport.evaluarPreflightRcg06Cliente(jdbc, cE);
        assertEquals(1, resE.senalE_pagosOnlineYEntregasPendientesDeCierre());
        assertTrue(resE.requiereContencionSelectivaImpC());

        // Verificar contencion selectiva IMP-C: V_e^0=0, COBRO historico intacto y sin backfill
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
                "SELECT \"reconocimientoInicialV0\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, pedE)));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"EVENTO_IMPUTACION_FINANCIERA\" WHERE \"idCobro\"=?",
                Integer.class, cobroA.idCobro()),
                "RCG-06: prohibido fabricar backfill heuristico desde COBRO.idPedido");
        assertEquals(pedA, jdbc.queryForObject(
                "SELECT \"idPedido\" FROM \"COBRO\" WHERE \"idCobro\"=?", Long.class, cobroA.idCobro()));
    }

    // =========================================================================
    // N2-T18: Cobertura insuficiente 422, rollback, D_e intacto y diagnostico
    // =========================================================================

    @Test
    void n2T18_CoberturaInsuficiente422RollbackTotalDeIntactoYDiagnosticoSeparado() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idAdmin = f.base + 1;
        long idRep = f.base + 3;
        long cliente = f.base;

        long ped = crearPedidoParaCliente(f, cliente);
        long rec = GfisLabSupport.crearRecorridoExplicito(jdbc, idRep, "REC-T18-422");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, ped, idRep, f.base, rec);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, ped, cliente, idRep, rec,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");

        int cobrosAntes = jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class);
        int cajaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class);
        int cuentaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CUENTA\"", Integer.class);

        // Intento de aplicar 120.00 sobre V_e^{vig}=100.00 => rechazo contractual 422
        GfisLabSupport.CoberturaNoAcreditadaException ex422 = assertThrows(
                GfisLabSupport.CoberturaNoAcreditadaException.class,
                () -> tx.executeWithoutResult(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, rec, idAdmin, UUID.randomUUID(), "v1", "h-t18-422",
                        new BigDecimal("100.00"), new BigDecimal("120.00"),
                        List.of(new GfisLabSupport.AplicacionPlan(cliente, ped, new BigDecimal("120.00"))),
                        true, true)));
        assertTrue(ex422.getMessage().contains("422 COBERTURA_NO_ACREDITADA"));
        assertEquals(ped, ex422.idPedido);

        // Verificar rollback total (0 Cobro/Caja/Cuenta/Cierre)
        assertEquals(cobrosAntes, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));
        assertEquals(cajaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class));
        assertEquals(cuentaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CUENTA\"", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=?", Integer.class, rec));

        // Diagnostico separado post-rollback (A1): D_e y V_e^{vig} permanecen intactos
        var diagPostRollback = tx.execute(s -> jdbc.queryForMap(
                "SELECT \"declaradoRepartidorDe\",\"reconocimientoInicialV0\",\"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                ped));
        assertNotNull(diagPostRollback);
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) diagPostRollback.get("declaradoRepartidorDe")));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) diagPostRollback.get("reconocimientoVigenteVvig")));
    }

    // =========================================================================
    // N2-T05 / 2.3 (ambito de replay + autorizacion) y N2-T14 (dos medios D1)
    // =========================================================================

    @Test
    void n2T05_T14_ReplayOtroRecorridoActorNoAdminYDosMediosMismoClienteRechazados() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idAdmin = f.base + 1;
        long idRep1 = f.base + 3;
        long idRep2 = tx.execute(s -> GfisLabSupport.crearRepartidorAdicionalEnLab(jdbc, f.base + 53));
        long idVendedor = f.base;
        long cliente = f.base;

        long ped1 = crearPedidoParaCliente(f, cliente);
        long ped2 = crearPedidoParaCliente(f, cliente);
        long rec1 = GfisLabSupport.crearRecorridoExplicito(jdbc, idRep1, "REC-T05-1");
        long rec2 = GfisLabSupport.crearRecorridoExplicito(jdbc, idRep2, "REC-T05-2");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, ped1, idRep1, idVendedor, rec1);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, ped2, idRep2, idVendedor, rec2);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, ped1, cliente, idRep1, rec1,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), new BigDecimal("100.00"), "IMP_B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, ped2, cliente, idRep2, rec2,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), new BigDecimal("100.00"), "IMP_B");

        UUID claveRec1 = UUID.randomUUID();
        var ok = tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                jdbc, rec1, idAdmin, claveRec1, "v1", "h-rec1",
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                List.of(new GfisLabSupport.AplicacionPlan(cliente, ped1, new BigDecimal("100.00"))),
                true, true));
        assertNotNull(ok);

        IllegalStateException exAmbito = assertThrows(IllegalStateException.class, () ->
                tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, rec2, idAdmin, claveRec1, "v1", "h-rec1",
                        new BigDecimal("100.00"), new BigDecimal("100.00"),
                        List.of(new GfisLabSupport.AplicacionPlan(cliente, ped2, new BigDecimal("100.00"))),
                        true, true)));
        assertTrue(exAmbito.getMessage().contains("CONFLICTO AMBITO 409"));

        for (long actorNoAdmin : List.of(idRep1, idVendedor)) {
            SecurityException ex403 = assertThrows(SecurityException.class, () ->
                    tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                            jdbc, rec1, actorNoAdmin, claveRec1, "v1", "h-rec1",
                            new BigDecimal("100.00"), new BigDecimal("100.00"),
                            List.of(new GfisLabSupport.AplicacionPlan(cliente, ped1, new BigDecimal("100.00"))),
                            true, true)));
            assertTrue(ex403.getMessage().contains("403"));
        }

        // 3) N2-T14 (alcance D1): un mismo Cliente intentando registrar dos medios (EFECTIVO + TRANSFERENCIA)
        //    en el mismo cierre => rechazado por K2 PK(idCierreRecorrido, idCliente) y por trigger D1 solo efectivo
        sincronizarSecuenciasB2();
        DataAccessException exSegundoMedio = assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s -> {
            long idCobroTransf = GfisLabSupport.siguientePkCobroB2(jdbc);
            jdbc.update("""
                    INSERT INTO "COBRO" ("idCobro","idCliente","idUsuarioRegistrador","fechaHoraCobro",
                        "importe","medioPago","origen","claveIdempotenciaOrigen","estadoCobro")
                    VALUES (?,?,?,clock_timestamp(),50.00,'TRANSFERENCIA','REPARTO',?,'CONFIRMADO')
                    """, idCobroTransf, cliente, idAdmin, UUID.randomUUID());
            jdbc.update("INSERT INTO \"COBRO_RENDICION_K2\" VALUES (?,?,?)", ok.idCierre(), cliente, idCobroTransf);
        }));
        assertNotNull(exSegundoMedio);
    }

    private long crearPedidoParaCliente(PedidoFixture f, long idCliente) {
        return pedidos.cargarPedido(f.vendedor(), new CargarPedidoService.DatosPedido(
                idCliente, f.hoy.plusDays(2),
                List.of(new CargarPedidoService.ItemPedido(f.base, new BigDecimal("1.000"))),
                new CargarPedidoService.DireccionEntrega("Calle Lab", "100", null, null, "Posadas", "Misiones", null, null),
                UUID.randomUUID())).idPedido();
    }

    private void sincronizarSecuenciasB2() {
        jdbc.execute("""
                SELECT setval('"seq_gfis_cobro_id"', GREATEST((SELECT COALESCE(MAX("idCobro"),0) FROM "COBRO"), 1), true);
                SELECT setval('"seq_gfis_mov_cuenta_id"', GREATEST((SELECT COALESCE(MAX("idMovimientoCuenta"),0) FROM "MOVIMIENTO_CUENTA"), 1), true);
                SELECT setval('"seq_gfis_mov_caja_id"', GREATEST((SELECT COALESCE(MAX("idMovimientoCaja"),0) FROM "MOVIMIENTO_CAJA"), 1), true);
                """);
    }

    private static void registrarError(
            Exception t, AtomicInteger deadlocks, AtomicInteger duplicados, List<Exception> errores) {
        String st = extraerSqlState(t);
        if ("40P01".equals(st)) deadlocks.incrementAndGet();
        else if ("23505".equals(st)) duplicados.incrementAndGet();
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
