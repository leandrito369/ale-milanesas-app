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
 * G-FIS (Fase B — Laboratorio de concurrencia, locks A2, generadores B2 e idempotencia C1-a):
 * Verifica dos ADMIN simultaneos, imposibilidad concurrente de coexistencia entre cierre
 * terminal y PENDIENTE_CONCILIACION (Bloqueante 3), replay/conflicto C1-a, aislamiento de
 * namespaces A2(ns,id), generadores B2-HIBRIDO y transicion desde MAX+1, convivencia con
 * servicios reales de main (CobroManualService, UC-22 #91, UC-43) y hallazgo L02 en V1.
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

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired AsignarRepartoService asignaciones;
    @Autowired CobroManualService cobros;
    @Autowired RegistrarMovimientoCajaService cajaManual;

    @Test
    void dosAdministradoresSimultaneosConClavesDistintasSoloUnoConfirmaYSeConservaPe1() throws Exception {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long vendedor = f.base;
        long admin1 = f.base + 1;
        long admin2 = f.base + 50;
        long repartidor = f.base + 3;
        long cliente = f.base;

        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado",
                        "cambioCredencialPendiente","fechaHoraAlta")
                    VALUES (?,?,'HASH_TEST','HABILITADO',false,now())
                    """, admin2, "admin2_gfis_" + admin2);
            jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'ADMINISTRADOR',now())", admin2);
        });

        long pedido = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, repartidor, "REC-2ADMIN");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedido, repartidor, vendedor, recorrido);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedido, cliente, repartidor, recorrido,
                new BigDecimal("300.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("300.00"), "IMP_B");

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<String> tareaAdmin1 = () -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    var r = tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                            jdbc, recorrido, admin1, UUID.randomUUID(), "v1", "h-adm1",
                            new BigDecimal("300.00"), new BigDecimal("300.00"),
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
                    var r = tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                            jdbc, recorrido, admin2, UUID.randomUUID(), "v1", "h-adm2",
                            new BigDecimal("300.00"), new BigDecimal("300.00"),
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

            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=?", Integer.class, recorrido));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"APLICACION_RENDICION_K2\" WHERE \"idPedido\"=?", Integer.class, pedido));
            assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
                    "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                    BigDecimal.class, pedido)));
        }
    }

    /**
     * Bloqueante 3: garantiza que nunca puedan coexistir un cierre terminal (CONFIRMADO) y
     * una conciliacion pendiente (PENDIENTE_CONCILIACION) sobre el mismo Recorrido, tanto
     * bajo carrera concurrente simultanea como bajo ambos ordenes secuenciales.
     */
    @Test
    void concurrenciaCierreTerminalVsPendienteConciliacionMismoRecorridoNuncaCoexisten() throws Exception {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long vendedor = f.base;
        long admin = f.base + 1;
        long rep1 = f.base + 3;
        long rep2 = tx.execute(s -> GfisLabSupport.crearRepartidorAdicionalEnLab(jdbc, f.base + 54));
        long rep3 = tx.execute(s -> GfisLabSupport.crearRepartidorAdicionalEnLab(jdbc, f.base + 55));
        long cliente = f.base;

        // Escenario A: Carrera concurrente real (CONFIRMADO vs PENDIENTE_CONCILIACION sobre mismo Recorrido)
        long pedConc = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recConc = GfisLabSupport.crearRecorridoExplicito(jdbc, rep1, "REC-RACE-TERM-PEND");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedConc, rep1, vendedor, recConc);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedConc, cliente, rep1, recConc,
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("200.00"), "IMP_B");

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> futConfirmado = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                            jdbc, recConc, admin, UUID.randomUUID(), "v1", "h-race-conf",
                            new BigDecimal("200.00"), new BigDecimal("200.00"),
                            List.of(new GfisLabSupport.AplicacionPlan(cliente, pedConc, new BigDecimal("200.00"))),
                            true, false));
                    return "OK:CONFIRMADO";
                } catch (Exception ex) {
                    return "ERR:" + extraerSqlState(ex);
                }
            });
            Future<String> futPendiente = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                            jdbc, recConc, admin, UUID.randomUUID(),
                            new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2),
                            new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2),
                            "Carrera concurrente pendiente vs confirmado", List.of()));
                    return "OK:PENDIENTE";
                } catch (Exception ex) {
                    return "ERR:" + extraerSqlState(ex);
                }
            });

            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            String rConf = futConfirmado.get(20, TimeUnit.SECONDS);
            String rPend = futPendiente.get(20, TimeUnit.SECONDS);

            long exitos = List.of(rConf, rPend).stream().filter(s -> s.startsWith("OK:")).count();
            long fallos23505 = List.of(rConf, rPend).stream().filter(s -> s.equals("ERR:23505")).count();
            assertEquals(1, exitos, "Exactamente uno debe persistir: conf=" + rConf + ", pend=" + rPend);
            assertEquals(1, fallos23505, "El competidor debe recibir 23505 (sin 40P01): conf=" + rConf + ", pend=" + rPend);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=?", Integer.class, recConc));
        }

        // Escenario B: Orden secuencial 1 (PENDIENTE primero -> intento CONFIRMADO rechazado con 23505)
        long pedSeq1 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recSeq1 = GfisLabSupport.crearRecorridoExplicito(jdbc, rep2, "REC-SEQ-PEND-CONF");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedSeq1, rep2, vendedor, recSeq1);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedSeq1, cliente, rep2, recSeq1,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");
        tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, recSeq1, admin, UUID.randomUUID(),
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2),
                "Pendiente primero", List.of()));
        DataAccessException exConfTrasPend = assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s ->
                GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, recSeq1, admin, UUID.randomUUID(), "v1", "h-conf-tras-pend",
                        new BigDecimal("100.00"), new BigDecimal("100.00"),
                        List.of(new GfisLabSupport.AplicacionPlan(cliente, pedSeq1, new BigDecimal("100.00"))),
                        true, false)));
        assertEquals("23505", extraerSqlState(exConfTrasPend));

        // Escenario C: Orden secuencial 2 (CONFIRMADO primero -> intento PENDIENTE rechazado con 23505)
        long pedSeq2 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recSeq2 = GfisLabSupport.crearRecorridoExplicito(jdbc, rep3, "REC-SEQ-CONF-PEND");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedSeq2, rep3, vendedor, recSeq2);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedSeq2, cliente, rep3, recSeq2,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");
        tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                jdbc, recSeq2, admin, UUID.randomUUID(), "v1", "h-conf-primero",
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                List.of(new GfisLabSupport.AplicacionPlan(cliente, pedSeq2, new BigDecimal("100.00"))),
                true, false));
        DataAccessException exPendTrasConf = assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s ->
                GfisLabSupport.registrarPendienteConciliacionEnTx(
                        jdbc, recSeq2, admin, UUID.randomUUID(),
                        new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2),
                        new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2),
                        "Pendiente tras confirmado", List.of())));
        assertEquals("23505", extraerSqlState(exPendTrasConf));
    }

    @Test
    void replayIdempotenteC1aRetornaReciboHistoricoYDetectaConflictoDeHuella() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long vendedor = f.base;
        long admin = f.base + 1;
        long repartidor = f.base + 3;
        long cliente = f.base;

        long pedido = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, repartidor, "REC-C1A");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedido, repartidor, vendedor, recorrido);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedido, cliente, repartidor, recorrido,
                new BigDecimal("180.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("180.00"), "IMP_B");

        UUID clave = UUID.randomUUID();
        var primero = tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                jdbc, recorrido, admin, clave, "v1", "sha256-payload-A",
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                List.of(new GfisLabSupport.AplicacionPlan(cliente, pedido, new BigDecimal("180.00"))),
                true, false));
        assertNotNull(primero);
        assertFalse(primero.replay());

        var replay = tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                jdbc, recorrido, admin, clave, "v1", "sha256-payload-A",
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                List.of(new GfisLabSupport.AplicacionPlan(cliente, pedido, new BigDecimal("180.00"))),
                true, false));
        assertNotNull(replay);
        assertTrue(replay.replay());
        assertEquals(primero.idCierre(), replay.idCierre());
        assertEquals(primero.reciboJson(), replay.reciboJson());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, primero.idCierre()));

        IllegalStateException exConflicto = assertThrows(IllegalStateException.class, () ->
                tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, recorrido, admin, clave, "v1", "sha256-payload-DISTINTO",
                        new BigDecimal("180.00"), new BigDecimal("180.00"),
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
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long vendedor = f.base;
        long admin = f.base + 1;
        long repA = f.base + 3;
        long repB = tx.execute(s -> GfisLabSupport.crearRepartidorAdicionalEnLab(jdbc, f.base + 51));
        long clienteA = f.base;
        long clienteB = f.base + 1;

        long pedA = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long pedB = pedidos.cargarPedido(f.vendedor(), new CargarPedidoService.DatosPedido(
                clienteB, f.hoy.plusDays(2),
                List.of(new CargarPedidoService.ItemPedido(f.base, new BigDecimal("1.000"))),
                new CargarPedidoService.DireccionEntrega("Calle B2", "789", null, null, "Posadas", "Misiones", null, null),
                UUID.randomUUID())).idPedido();

        long recA = GfisLabSupport.crearRecorridoExplicito(jdbc, repA, "REC-B2-A");
        long recB = GfisLabSupport.crearRecorridoExplicito(jdbc, repB, "REC-B2-B");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedA, repA, vendedor, recA);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedB, repB, vendedor, recB);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA, clienteA, repA, recA,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedB, clienteB, repB, recB,
                new BigDecimal("120.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("120.00"), "IMP_B");

        jdbc.execute("""
                SELECT setval('"seq_gfis_cobro_id"', GREATEST((SELECT COALESCE(MAX("idCobro"),0) FROM "COBRO"), 1), true);
                SELECT setval('"seq_gfis_mov_cuenta_id"', GREATEST((SELECT COALESCE(MAX("idMovimientoCuenta"),0) FROM "MOVIMIENTO_CUENTA"), 1), true);
                SELECT setval('"seq_gfis_mov_caja_id"', GREATEST((SELECT COALESCE(MAX("idMovimientoCaja"),0) FROM "MOVIMIENTO_CAJA"), 1), true);
                """);

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<GfisLabSupport.ResultadoCierreLab> fa = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                return tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, recA, admin, UUID.randomUUID(), "v1", "h-b2-a",
                        new BigDecimal("100.00"), new BigDecimal("100.00"),
                        List.of(new GfisLabSupport.AplicacionPlan(clienteA, pedA, new BigDecimal("100.00"))),
                        false, true));
            });
            Future<GfisLabSupport.ResultadoCierreLab> fb = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                return tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, recB, admin, UUID.randomUUID(), "v1", "h-b2-b",
                        new BigDecimal("120.00"), new BigDecimal("120.00"),
                        List.of(new GfisLabSupport.AplicacionPlan(clienteB, pedB, new BigDecimal("120.00"))),
                        false, true));
            });
            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();

            var ra = fa.get(20, TimeUnit.SECONDS);
            var rb = fb.get(20, TimeUnit.SECONDS);
            assertEquals("CONFIRMADO", ra.estadoCierre());
            assertEquals("CONFIRMADO", rb.estadoCierre());

            List<Long> seqB = jdbc.queryForList(
                    "SELECT \"numeroSecuenciaCliente\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY 1",
                    Long.class, clienteB);
            for (int i = 0; i < seqB.size(); i++) {
                assertEquals(i + 1L, seqB.get(i), "Ordinal numeroSecuenciaCliente debe permanecer consecutivo");
            }
        }
    }

    @Test
    void convivenciaConcurrenteConServiciosRealesMainUc29Uc22Uc43Sin40P01Ni23505() throws Exception {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long vendedor = f.base;
        long admin = f.base + 1;
        long repartidor = f.base + 3;
        long cliente = f.base;

        long pedEntrega = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long pedAsignar = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, repartidor, "REC-CONV");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedEntrega, repartidor, vendedor, recorrido);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedEntrega, cliente, repartidor, recorrido,
                new BigDecimal("250.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("250.00"), "IMP_B");

        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger duplicados = new AtomicInteger();
        List<Exception> errores = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(4);
             Connection barrera = dataSource.getConnection()) {
            barrera.setAutoCommit(false);
            int pidBarrera = GfisLabSupport.backendPid(barrera);
            try (PreparedStatement ps = barrera.prepareStatement(
                    "SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE")) {
                ps.setLong(1, cliente);
                ps.executeQuery().close();
            }

            Future<?> futT1 = pool.submit(() -> {
                try {
                    tx.executeWithoutResult(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                            jdbc, recorrido, admin, UUID.randomUUID(), "v1", "h-conv",
                            new BigDecimal("250.00"), new BigDecimal("250.00"),
                            List.of(new GfisLabSupport.AplicacionPlan(cliente, pedEntrega, new BigDecimal("250.00"))),
                            true, false));
                } catch (Exception t) {
                    clasificarError(t, deadlocks, duplicados, errores);
                }
            });

            Future<?> futUc29 = pool.submit(() -> {
                try {
                    cobros.registrarCobro(
                            f.sesiones.get("ADMINISTRADOR"), cliente, null,
                            new BigDecimal("50.00"), "EFECTIVO", UUID.randomUUID());
                } catch (Exception t) {
                    clasificarError(t, deadlocks, duplicados, errores);
                }
            });

            Future<?> futUc22 = pool.submit(() -> {
                try {
                    cajaManual.registrarMovimientoCaja(
                            f.sesiones.get("ADMINISTRADOR"), "INGRESO",
                            new BigDecimal("75.00"), "Reposicion cambio caja", "Prueba convivencia #91",
                            UUID.randomUUID());
                } catch (Exception t) {
                    clasificarError(t, deadlocks, duplicados, errores);
                }
            });

            Future<?> futUc43 = pool.submit(() -> {
                try {
                    asignaciones.asignarOReasignar(
                            f.vendedor(),
                            new AsignarRepartoService.DatosAsignacion(pedAsignar, repartidor, null));
                } catch (Exception t) {
                    clasificarError(t, deadlocks, duplicados, errores);
                }
            });

            await().atMost(Duration.ofSeconds(10)).until(() -> {
                Integer bloqueados = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                        Integer.class, pidBarrera);
                return bloqueados != null && bloqueados >= 1;
            });

            barrera.commit();

            futT1.get(20, TimeUnit.SECONDS);
            futUc29.get(20, TimeUnit.SECONDS);
            futUc22.get(20, TimeUnit.SECONDS);
            futUc43.get(20, TimeUnit.SECONDS);

            assertEquals(0, deadlocks.get(), "No debe ocurrir ningun deadlock 40P01");
            assertEquals(0, duplicados.get(), "No debe ocurrir ninguna colision 23505");
            assertTrue(errores.isEmpty(), "Errores inesperados en convivencia: " + errores);

            BigDecimal saldoFinal = jdbc.queryForObject(
                    "SELECT \"saldoResultante\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY \"numeroSecuenciaCliente\" DESC LIMIT 1",
                    BigDecimal.class, cliente);
            assertEquals(0, new BigDecimal("-300.00").compareTo(saldoFinal));
        }
    }

    @Test
    void flywayV4IntegraL02YSearchPathBlindadoEnFnMr3AnularCobroManual() {
        String prosrc = jdbc.queryForObject(
                "SELECT prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                        + "WHERE n.nspname='ale_gestion' AND p.proname = 'fn_mr3_anular_cobro_manual'",
                String.class);
        String proconfig = jdbc.queryForObject(
                "SELECT array_to_string(proconfig, ',') FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                        + "WHERE n.nspname='ale_gestion' AND p.proname = 'fn_mr3_anular_cobro_manual'",
                String.class);
        assertNotNull(prosrc);
        assertNotNull(proconfig);
        int posClienteForUpdate = prosrc.indexOf("FROM ale_gestion.\"CLIENTE\" WHERE \"idCliente\" = v_id_cliente FOR UPDATE");
        int posCobroForUpdate = prosrc.indexOf("FOR UPDATE", posClienteForUpdate + 1);
        assertTrue(posClienteForUpdate > 0 && posCobroForUpdate > posClienteForUpdate,
                "ADAPT-01: Flyway V4 debe adquirir CLIENTE FOR UPDATE antes que COBRO FOR UPDATE");
        assertTrue(proconfig.contains("search_path=pg_catalog, ale_gestion, pg_temp"),
                "ADAPT-02: fn_mr3_anular_cobro_manual debe fijar search_path seguro con pg_temp al final");
    }

    @Test
    void compatibilidadTransicionLegacyMaxMasUnoVsSecuenciasB2YMitigacionSin23505() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long admin = f.base + 1;

        jdbc.execute("""
                SELECT setval('"seq_gfis_mov_caja_id"', GREATEST((SELECT COALESCE(MAX("idMovimientoCaja"),0) FROM "MOVIMIENTO_CAJA"), 1),
                              (SELECT EXISTS (SELECT 1 FROM "MOVIMIENTO_CAJA")));
                """);
        var legacyCaja = cajaManual.registrarMovimientoCaja(
                f.sesiones.get("ADMINISTRADOR"), "INGRESO",
                new BigDecimal("10.00"), "Escritor legacy MAX+1 (#91)", "Prueba riesgo mezcla",
                UUID.randomUUID());

        long idColisionB2 = GfisLabSupport.siguientePkMovCajaB2(jdbc);
        assertEquals(legacyCaja.idMovimientoCaja(), idColisionB2);
        Exception exColision = assertThrows(Exception.class, () -> jdbc.update("""
                INSERT INTO "MOVIMIENTO_CAJA" (
                    "idMovimientoCaja","tipoMovimiento","importe","fechaHoraMovimiento",
                    "origen","concepto","idUsuarioRegistro")
                VALUES (?,'INGRESO',10.00,clock_timestamp(),'MANUAL','Colision mezcla',?)
                """, idColisionB2, admin));
        assertEquals("23505", extraerSqlState(exColision));

        jdbc.execute("""
                SELECT setval('"seq_gfis_mov_caja_id"', GREATEST((SELECT COALESCE(MAX("idMovimientoCaja"),0) FROM "MOVIMIENTO_CAJA"), 1), true);
                """);
        long idPostCutover1 = GfisLabSupport.siguientePkMovCajaB2(jdbc);
        long idPostCutover2 = GfisLabSupport.siguientePkMovCajaB2(jdbc);
        assertTrue(idPostCutover1 > legacyCaja.idMovimientoCaja());
        assertEquals(idPostCutover1 + 1L, idPostCutover2);
        jdbc.update("""
                INSERT INTO "MOVIMIENTO_CAJA" (
                    "idMovimientoCaja","tipoMovimiento","importe","fechaHoraMovimiento",
                    "origen","concepto","idUsuarioRegistro")
                VALUES (?,'INGRESO',15.00,clock_timestamp(),'MANUAL','Post cutover B2 #1',?)
                """, idPostCutover1, admin);
        jdbc.update("""
                INSERT INTO "MOVIMIENTO_CAJA" (
                    "idMovimientoCaja","tipoMovimiento","importe","fechaHoraMovimiento",
                    "origen","concepto","idUsuarioRegistro")
                VALUES (?,'INGRESO',20.00,clock_timestamp(),'MANUAL','Post cutover B2 #2',?)
                """, idPostCutover2, admin);
    }

    private static void clasificarError(
            Exception t, AtomicInteger deadlocks, AtomicInteger duplicados, List<Exception> errores) {
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
