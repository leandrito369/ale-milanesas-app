package ar.edu.unam.alemilanesas.gfis;

import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * G-FIS (Fase B — Alternativa A CANDIDATA, NO RATIFICADA):
 * Verifica la rectificacion versionada append-only (REVISION_CONCILIACION_PE2)
 * sobre una unica cabecera CIERRE_RECORRIDO_PE2 cubriendo los escenarios A-H:
 *   A: importe propuesto erroneo
 *   B: propuesta vinculada al Pedido equivocado
 *   C: custodia/diferencia incorrecta y control E-04 sobre la revision confirmada
 *   D: propuesta sin cobertura acreditada (422) o Pedido ajeno + rollback
 *   E: carrera concurrente rectificacion vs confirmacion T1-C2
 *   F: perdida de ACK (replay idempotente), conflicto 409 y RBAC 403
 *   G: rechazo de rectificacion posterior a cierre definitivo
 *   H: invalidacion sin borrado historico, bloqueo de T1 y nueva revision sucesora.
 *
 * NO modifica src/main/** ni promueve V4.
 */
@SpringBootTest
@Testcontainers
class GfisRectificacionPendienteLabIntegrationTest {
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

    @BeforeEach
    void prepararLaboratorio() {
        if (V4_APLICADA.compareAndSet(false, true)) {
            GfisLabSupport.aplicarMigracionCandidataV4EnLaboratorio(dataSource);
        }
    }

    private record EscenarioBase(
            PedidoFixture f,
            long idVendedor,
            long idAdmin,
            long idRepartidor,
            long clienteA,
            long clienteB,
            long pedA1,
            long pedA2,
            long pedB1,
            long recorrido) {}

    private EscenarioBase crearEscenarioBase(String refRecorrido) {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idVendedor = f.base;
        long idAdmin = f.base + 1;
        long idRepartidor = f.base + 3;
        long clienteA = f.base;
        long clienteB = f.base + 1;

        long pedA1 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long pedA2 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long pedB1 = pedidos.cargarPedido(f.vendedor(), new CargarPedidoService.DatosPedido(
                clienteB, f.hoy.plusDays(2),
                List.of(new CargarPedidoService.ItemPedido(f.base, new BigDecimal("2.000"))),
                new CargarPedidoService.DireccionEntrega("Calle Rev", "200", null, null, "Posadas", "Misiones", null, null),
                UUID.randomUUID())).idPedido();

        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRepartidor, refRecorrido);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedA1, idRepartidor, idVendedor, recorrido);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedA2, idRepartidor, idVendedor, recorrido);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedB1, idRepartidor, idVendedor, recorrido);

        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA1, clienteA, idRepartidor, recorrido,
                new BigDecimal("120.00"), new BigDecimal("30.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("70.00"), "IMP_B"); // V_0 = 70
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA2, clienteA, idRepartidor, recorrido,
                new BigDecimal("80.00"), new BigDecimal("15.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("50.00"), "IMP_B"); // V_0 = 50
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedB1, clienteB, idRepartidor, recorrido,
                new BigDecimal("90.00"), new BigDecimal("30.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("70.00"), "IMP_B"); // V_0 = 60

        return new EscenarioBase(f, idVendedor, idAdmin, idRepartidor, clienteA, clienteB, pedA1, pedA2, pedB1, recorrido);
    }

    @Test
    void escenariosABC_RectificarImportePedidoYCustodiaSinEfectosFinancierosYConfirmarT1C2ConE04() {
        EscenarioBase e = crearEscenarioBase("REC-REV-ABC");
        int cobrosInicial = jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class);
        int cajaInicial = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class);

        // Rev 1 (erronea): atribuyo mal a pedA2=45 (A), omitio pedB1 (B), y registro custodia=15 (C)
        var pend = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("15.00"), new BigDecimal("15.00"),
                "Carga inicial erronea en monto, pedido y custodia",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("120.00").min(new BigDecimal("70.00"))),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("45.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("50.00")))));
        assertNotNull(pend);
        Long idRev1 = jdbc.queryForObject(
                "SELECT \"idRevisionVigente\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                Long.class, pend.idCierre());
        assertNotNull(idRev1);

        // Rev 2 (corrige A importe, B atribucion por Pedido y C custodia: 170 propuesto + 10 custodia + 10 faltante)
        var rev2 = tx.execute(s -> GfisLabSupport.rectificarPendienteConciliacionEnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                "Rectificacion auditada: A2 corregido a 40, B1 a 60 y custodia real a 10",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(rev2);
        assertEquals(2, rev2.numeroRevision());
        assertEquals("VIGENTE", rev2.estadoRevision());
        assertFalse(rev2.replay());

        // Cero efectos financieros anticipados tras rectificar y preservacion de historial completo
        assertEquals(cobrosInicial, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));
        assertEquals(cajaInicial, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class));
        assertEquals("SUPERADA", jdbc.queryForObject(
                "SELECT \"estadoRevision\" FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idRevisionConciliacion\"=?",
                String.class, idRev1));
        assertEquals(6, jdbc.queryForObject(
                "SELECT count(*) FROM \"PROPUESTA_APLICACION_PENDIENTE\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, pend.idCierre()),
                "Ambas revisiones deben conservar intactas sus 3 propuestas (3 + 3 = 6)");

        // Prohibido registrar egreso de custodia contra el evento de la revision 1 SUPERADA (E-04)
        Long idCustRev1 = jdbc.queryForObject("""
                SELECT "idEventoCustodia" FROM "CUSTODIA_DIFERENCIA_RENDICION"
                 WHERE "idRevisionConciliacion"=? AND "tipoEvento"='CUSTODIA_NO_IMPUTADA'
                """, Long.class, idRev1);
        DataAccessException exCustSuperada = assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "EGRESO_CUSTODIA_RENDICION" (
                    "idEventoCustodia","idCierreRecorrido","tipoEgreso","importeEgreso",
                    "numeroReciboDevolucion","receptorIdentificacion","motivo","idUsuarioAdmin","fechaHoraEgreso")
                VALUES (?,?,'DEVOLUCION_FISICA_CON_RECIBO',10.00,'REC-REV1-OBS','Repartidor','Sobre rev superada',?,clock_timestamp())
                """, idCustRev1, pend.idCierre(), e.idAdmin()));
        assertTrue(exCustSuperada.getMessage().contains("E04-CUSTODIA/REV"));

        // Confirmacion T1-C2 unica sobre rev 2 con egreso E-04 sobre custodia vigente de 10.00
        String reciboDev = "REC-DEV-ABC-" + e.f().base;
        var cierre = tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), 2,
                UUID.randomUUID(), "v1", "h-abc-rev2",
                "MIXTA_FALTANTE_Y_CUSTODIA",
                "Resolucion definitiva sobre revision 2 rectificada",
                new GfisLabSupport.EvidenciaEgresoCustodia(reciboDev, "Repartidor #" + e.idRepartidor(), "Devolucion 10.00"),
                true, false));
        assertNotNull(cierre);
        assertEquals("CERRADO_CON_DIFERENCIA_RESUELTA", cierre.estadoCierre());
        assertEquals("CONFIRMADA_T1", jdbc.queryForObject(
                "SELECT \"estadoRevision\" FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idRevisionConciliacion\"=?",
                String.class, rev2.idRevision()));
        assertEquals(0, new BigDecimal("170.00").compareTo(jdbc.queryForObject("""
                SELECT COALESCE(SUM(mc."importe"), 0) FROM "MOVIMIENTO_CAJA" mc
                  JOIN "COBRO_RENDICION_K2" k ON k."idCobro" = mc."idCobro"
                 WHERE k."idCierreRecorrido" = ?
                """, BigDecimal.class, cierre.idCierre())));
        assertEquals(0, new BigDecimal("10.00").compareTo(jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, e.pedA2())));
    }

    @Test
    void escenarioD_RectificacionConCoberturaInsuficiente422OPedidoAjenoRevierteIntegramente() {
        EscenarioBase e = crearEscenarioBase("REC-REV-D");
        long pedAjeno = pedidos.cargarPedido(e.f().vendedor(), e.f().datos()).idPedido();

        var pend = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                "Pendiente inicial escenario D",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(pend);

        // 1) Cobertura insuficiente (pedA2 solo tiene V_e^{vig}=50, se intenta proponer 55) => 422 y rollback
        UUID clave422 = UUID.randomUUID();
        List<GfisLabSupport.AplicacionPlan> planExcedido = List.of(
                new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("65.00")),
                new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("55.00")),
                new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("50.00")));
        assertThrows(GfisLabSupport.CoberturaNoAcreditadaException.class, () -> tx.execute(s ->
                GfisLabSupport.rectificarPendienteConciliacionEnTx(
                        jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), clave422,
                        new BigDecimal("180.00"), new BigDecimal("180.00"),
                        new BigDecimal("10.00"), new BigDecimal("10.00"),
                        "Intento con cobertura no acreditada", planExcedido)));

        // 2) Pedido ajeno al Recorrido => rechazado y rollback
        UUID claveAjeno = UUID.randomUUID();
        List<GfisLabSupport.AplicacionPlan> planAjeno = List.of(
                new GfisLabSupport.AplicacionPlan(e.clienteA(), pedAjeno, new BigDecimal("70.00")));
        assertThrows(IllegalArgumentException.class, () -> tx.execute(s ->
                GfisLabSupport.rectificarPendienteConciliacionEnTx(
                        jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), claveAjeno,
                        new BigDecimal("180.00"), new BigDecimal("80.00"),
                        new BigDecimal("100.00"), new BigDecimal("10.00"),
                        "Intento con pedido fuera del recorrido", planAjeno)));

        // Rev 1 permanece unica y VIGENTE; ninguna clave fallida fue consumida
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, pend.idCierre()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"REVISION_CONCILIACION_PE2\" WHERE \"claveIdempotenciaRevision\" IN (?,?)",
                Integer.class, clave422, claveAjeno));
    }

    @Test
    void escenarioE_CarreraConcurrenteRectificacionVsConfirmacionT1C2SinEstadosInconsistentes() throws Exception {
        EscenarioBase e = crearEscenarioBase("REC-REV-E");
        var pend = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                "Pendiente carrera concurrente E",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(pend);

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> futRect = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    tx.execute(s -> GfisLabSupport.rectificarPendienteConciliacionEnTx(
                            jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                            new BigDecimal("180.00"), new BigDecimal("180.00"),
                            new BigDecimal("20.00"), new BigDecimal("20.00"),
                            "Rectificacion concurrente a rev 2",
                            List.of(
                                    new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                                    new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("30.00")),
                                    new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
                    return "OK:RECT";
                } catch (Exception ex) {
                    return "ERR:" + extraerDetalle(ex);
                }
            });

            Future<String> futConf = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                            jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), 1,
                            UUID.randomUUID(), "v1", "h-race-e",
                            "MIXTA_FALTANTE_Y_CUSTODIA", "Confirmacion concurrente sobre rev 1",
                            new GfisLabSupport.EvidenciaEgresoCustodia(
                                    "REC-RACE-E-" + e.f().base, "Repartidor", "Devolucion 10.00"),
                            true, false));
                    return "OK:CONF";
                } catch (Exception ex) {
                    return "ERR:" + extraerDetalle(ex);
                }
            });

            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            String rRect = futRect.get(20, TimeUnit.SECONDS);
            String rConf = futConf.get(20, TimeUnit.SECONDS);

            assertFalse(rRect.contains("40P01") || rConf.contains("40P01"),
                    "A2(NS_RECORRIDO) debe evitar 40P01: rect=" + rRect + ", conf=" + rConf);
            long exitos = List.of(rRect, rConf).stream().filter(s -> s.startsWith("OK:")).count();
            long conflictos409 = List.of(rRect, rConf).stream().filter(s -> s.contains("409")).count();
            assertEquals(1, exitos, "Exactamente una operacion debe ganar: rect=" + rRect + ", conf=" + rConf);
            assertEquals(1, conflictos409, "La perdedora debe recibir 409 determinista: rect=" + rRect + ", conf=" + rConf);

            // Invariante: si gano rectificacion, 0 Cobros y 1 rev VIGENTE (=2); si gano confirmacion, cierre terminal y 0 rev VIGENTE
            String estadoFinal = jdbc.queryForObject(
                    "SELECT \"estadoCierre\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                    String.class, pend.idCierre());
            if ("OK:RECT".equals(rRect)) {
                assertEquals("PENDIENTE_CONCILIACION", estadoFinal);
                assertEquals(0, jdbc.queryForObject(
                        "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                        Integer.class, pend.idCierre()));
            } else {
                assertEquals("CERRADO_CON_DIFERENCIA_RESUELTA", estadoFinal);
                assertEquals(2, jdbc.queryForObject(
                        "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                        Integer.class, pend.idCierre()));
            }
        }
    }

    @Test
    void escenarioF_ReplayIdempotenteDeRectificacionPorPerdidaDeAckConflicto409YRbac403() {
        EscenarioBase e = crearEscenarioBase("REC-REV-F");
        var pend = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                "Pendiente escenario F",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(pend);

        UUID claveRev2 = UUID.randomUUID();
        List<GfisLabSupport.AplicacionPlan> planRev2 = List.of(
                new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("35.00")),
                new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")));

        var r1 = tx.execute(s -> GfisLabSupport.rectificarPendienteConciliacionEnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), claveRev2,
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("15.00"), new BigDecimal("15.00"),
                "Ajuste por recuento", planRev2));
        assertNotNull(r1);
        assertFalse(r1.replay());

        // Reenvio por perdida de ACK con misma UUID y mismo payload => replay idempotente, 0 nuevas revisiones
        var replay = tx.execute(s -> GfisLabSupport.rectificarPendienteConciliacionEnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), claveRev2,
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("15.00"), new BigDecimal("15.00"),
                "  Ajuste   por recuento ", planRev2));
        assertNotNull(replay);
        assertTrue(replay.replay());
        assertEquals(r1.idRevision(), replay.idRevision());
        assertEquals(2, replay.numeroRevision());
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, pend.idCierre()));

        // Misma UUID con distinto payload => 409
        IllegalStateException ex409 = assertThrows(IllegalStateException.class, () -> tx.execute(s ->
                GfisLabSupport.rectificarPendienteConciliacionEnTx(
                        jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), claveRev2,
                        new BigDecimal("180.00"), new BigDecimal("180.00"),
                        new BigDecimal("15.00"), new BigDecimal("15.00"),
                        "Motivo alterado", planRev2)));
        assertTrue(ex409.getMessage().contains("409"));

        // Actor no-ADMIN (VENDEDOR / REPARTIDOR) => 403 incluso en replay con UUID valida
        assertThrows(SecurityException.class, () -> tx.execute(s ->
                GfisLabSupport.rectificarPendienteConciliacionEnTx(
                        jdbc, pend.idCierre(), e.recorrido(), e.idVendedor(), claveRev2,
                        new BigDecimal("180.00"), new BigDecimal("180.00"),
                        new BigDecimal("15.00"), new BigDecimal("15.00"),
                        "Ajuste por recuento", planRev2)));
        assertThrows(SecurityException.class, () -> tx.execute(s ->
                GfisLabSupport.rectificarPendienteConciliacionEnTx(
                        jdbc, pend.idCierre(), e.recorrido(), e.idRepartidor(), claveRev2,
                        new BigDecimal("180.00"), new BigDecimal("180.00"),
                        new BigDecimal("15.00"), new BigDecimal("15.00"),
                        "Ajuste por recuento", planRev2)));
    }

    @Test
    void escenarioG_ProhibidoRectificarOAlterarRevisionesTrasCierreTerminal() {
        EscenarioBase e = crearEscenarioBase("REC-REV-G");
        var pend = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("170.00"),
                new BigDecimal("10.00"), BigDecimal.ZERO.setScale(2),
                "Faltante 10 sin custodia",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(pend);

        tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), 1,
                UUID.randomUUID(), "v1", "h-g-cerrado",
                "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "Cerrado definitivamente",
                null, true, false));

        // Intento de rectificacion por servicio => 409 terminal
        List<GfisLabSupport.AplicacionPlan> planTardio = List.of(
                new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")));
        UUID claveTardia = UUID.randomUUID();
        IllegalStateException exTerm = assertThrows(IllegalStateException.class, () -> tx.execute(s ->
                GfisLabSupport.rectificarPendienteConciliacionEnTx(
                        jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), claveTardia,
                        new BigDecimal("180.00"), new BigDecimal("170.00"),
                        new BigDecimal("10.00"), BigDecimal.ZERO.setScale(2),
                        "Intento tardio post cierre", planTardio)));
        assertTrue(exTerm.getMessage().contains("409"));

        // Intento directo SQL de insertar revision o borrar historial tras cierre terminal => RECHAZADO
        Long idRev1 = jdbc.queryForObject(
                "SELECT \"idRevisionVigente\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                Long.class, pend.idCierre());
        UUID claveDirecta = UUID.randomUUID();
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "REVISION_CONCILIACION_PE2" (
                    "idCierreRecorrido","numeroRevision","idRevisionAnterior","estadoRevision",
                    "claveIdempotenciaRevision","versionHuella","huellaCanonica",
                    "idUsuarioAdmin","fechaHoraRevision",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                    "importeDiferenciaFaltante","importeCustodiaNoImputada","motivoRevision")
                VALUES (?,2,?,'VIGENTE',?,'v1','h-sql-ilegal',?,clock_timestamp(),180,170,170,10,0,'Ilegal')
                """, pend.idCierre(), idRev1, claveDirecta, e.idAdmin()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idRevisionConciliacion\"=?", idRev1));
    }

    @Test
    void escenarioH_InvalidacionSinBorradoBloqueaT1YPermiteNuevaRevisionSucesora() {
        EscenarioBase e = crearEscenarioBase("REC-REV-H");
        var pend = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("170.00"),
                new BigDecimal("10.00"), BigDecimal.ZERO.setScale(2),
                "Propuesta inicial a invalidar",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(pend);

        // 1) Invalidar formalmente rev 1 sin borrar evidencia historica
        tx.executeWithoutResult(s -> GfisLabSupport.invalidarRevisionPendienteEnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(),
                "Planilla cargada con comprobantes incompletos; se invalida esperando recuento"));

        assertEquals("INVALIDADA", jdbc.queryForObject(
                "SELECT \"estadoRevision\" FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idCierreRecorrido\"=? AND \"numeroRevision\"=1",
                String.class, pend.idCierre()));
        assertNull(jdbc.queryForObject(
                "SELECT \"idRevisionVigente\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                Long.class, pend.idCierre()));
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM \"PROPUESTA_APLICACION_PENDIENTE\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, pend.idCierre()),
                "Invalidar no borra las propuestas historicas de rev 1");

        // 2) Intentar confirmar T1-C2 sin revision vigente => BLOQUEADO (409)
        UUID claveBloq = UUID.randomUUID();
        IllegalStateException exSinVig = assertThrows(IllegalStateException.class, () -> tx.execute(s ->
                GfisLabSupport.confirmarResolucionT1K2EnTx(
                        jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), 1,
                        claveBloq, "v1", "h-h-bloq",
                        "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "Intento sobre invalidada",
                        null, true, false)));
        assertTrue(exSinVig.getMessage().contains("409"));

        // 3) Emitir nueva revision sucesora (rev 2 encadenada a rev 1 INVALIDADA) y confirmar en T1-C2
        var rev2 = tx.execute(s -> GfisLabSupport.rectificarPendienteConciliacionEnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("170.00"),
                new BigDecimal("10.00"), BigDecimal.ZERO.setScale(2),
                "Nueva propuesta verificada tras recuento fisico",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(rev2);
        assertEquals(2, rev2.numeroRevision());

        var cierreOk = tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), 2,
                UUID.randomUUID(), "v1", "h-h-ok",
                "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "Cierre T1-C2 sobre rev 2 sucesora",
                null, true, false));
        assertNotNull(cierreOk);
        assertEquals("CERRADO_CON_DIFERENCIA_RESUELTA", cierreOk.estadoCierre());
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, pend.idCierre()));
    }

    private static String extraerDetalle(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx && sqlEx.getSQLState() != null) {
                return sqlEx.getSQLState() + ":" + sqlEx.getMessage();
            }
            if (cur.getMessage() != null && cur.getMessage().contains("409")) {
                return cur.getMessage();
            }
            cur = cur.getCause();
        }
        return t.getClass().getSimpleName() + ":" + t.getMessage();
    }
}
