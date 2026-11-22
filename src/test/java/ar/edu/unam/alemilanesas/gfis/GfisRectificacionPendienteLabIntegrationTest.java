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

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;

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

    /**
     * Control final de integridad SQL (ct_gfis_revision_coherencia_commit):
     * Pruebas NEGATIVAS que demuestran que ninguna transicion aislada de REVISION_CONCILIACION_PE2
     * (CONFIRMADA_T1 sin cabecera/Cobros K2, SUPERADA sin sucesora, INVALIDADA sin desvincular cabecera,
     * CONFIRMADA_T1 + cabecera sin Cobros K2, ni nueva VIGENTE sin actualizar cabecera) puede cometerse.
     */
    @Test
    void integridadCommitRechazaTransicionesAisladasDeRevisionConRollbackYSqlstateP0001() {
        EscenarioBase e = crearEscenarioBase("REC-REV-NEG-COMMIT");
        var pend = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("170.00"),
                new BigDecimal("10.00"), BigDecimal.ZERO.setScale(2),
                "Pendiente para pruebas negativas de COMMIT",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(pend);
        Long idRev1 = jdbc.queryForObject(
                "SELECT \"idRevisionVigente\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                Long.class, pend.idCierre());
        assertNotNull(idRev1);

        // 1) Transicion aislada VIGENTE -> CONFIRMADA_T1 sin confirmar cabecera ni emitir Cobros K2
        RuntimeException exConfAislada = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s ->
                jdbc.update("""
                        UPDATE "REVISION_CONCILIACION_PE2"
                           SET "estadoRevision" = 'CONFIRMADA_T1'
                         WHERE "idRevisionConciliacion" = ?
                        """, idRev1)));
        assertEquals("P0001", extraerSqlState(exConfAislada));
        assertTrue(extraerDetalle(exConfAislada).contains("T1-C2/REV-COMMIT"));

        // 2) Transicion aislada VIGENTE -> SUPERADA sin insertar revision sucesora ni actualizar cabecera
        RuntimeException exSupAislada = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s ->
                jdbc.update("""
                        UPDATE "REVISION_CONCILIACION_PE2"
                           SET "estadoRevision" = 'SUPERADA'
                         WHERE "idRevisionConciliacion" = ?
                        """, idRev1)));
        assertEquals("P0001", extraerSqlState(exSupAislada));
        assertTrue(extraerDetalle(exSupAislada).contains("T1-C2/REV-COMMIT"));

        // 3) Transicion aislada VIGENTE -> INVALIDADA sin resolver enlace idRevisionVigente en cabecera
        RuntimeException exInvAislada = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s ->
                jdbc.update("""
                        UPDATE "REVISION_CONCILIACION_PE2"
                           SET "estadoRevision" = 'INVALIDADA',
                               "idUsuarioInvalidacion" = ?,
                               "motivoInvalidacion" = 'Invalidacion aislada sin actualizar cabecera',
                               "fechaHoraInvalidacion" = clock_timestamp()
                         WHERE "idRevisionConciliacion" = ?
                        """, e.idAdmin(), idRev1)));
        assertEquals("P0001", extraerSqlState(exInvAislada));
        assertTrue(extraerDetalle(exInvAislada).contains("T1-C2/REV-COMMIT"));

        // 4) Confirmacion ficticia: REVISION=CONFIRMADA_T1 + CABECERA=CERRADO_CON_DIFERENCIA_RESUELTA
        //    SIN emitir Cobros K2 por 170.00 => rechazada al COMMIT (imposibilidad de cierre sin efectos)
        UUID claveFicticia = UUID.randomUUID();
        RuntimeException exSinCobros = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE "REVISION_CONCILIACION_PE2"
                       SET "estadoRevision" = 'CONFIRMADA_T1'
                     WHERE "idRevisionConciliacion" = ?
                    """, idRev1);
            jdbc.update("""
                    UPDATE "CIERRE_RECORRIDO_PE2"
                       SET "estadoCierre" = 'CERRADO_CON_DIFERENCIA_RESUELTA',
                           "claveIdempotenciaCierre" = ?,
                           "versionHuella" = 'v1',
                           "huellaCanonica" = 'h-ficticia',
                           "importeImputadoConfirmado" = "importeImputadoPropuesto",
                           "idUsuarioResolucionAdmin" = ?,
                           "tipoResolucionAdmin" = 'FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO',
                           "motivoResolucionAdmin" = 'Intento sin Cobros K2',
                           "fechaHoraResolucion" = clock_timestamp(),
                           "reciboHistoricoJson" = '{}'::jsonb
                     WHERE "idCierreRecorrido" = ?
                    """, claveFicticia, e.idAdmin(), pend.idCierre());
            jdbc.update("UPDATE \"RECORRIDO_REPARTO\" SET \"estadoOperativo\"='CERRADO' WHERE \"idRecorrido\"=?",
                    e.recorrido());
        }));
        assertEquals("P0001", extraerSqlState(exSinCobros));

        // 5) Revision 2 huerfana: SUPERADA rev 1 + INSERT rev 2 VIGENTE sin enlazar CIERRE_RECORRIDO_PE2.idRevisionVigente
        UUID claveHuerfana = UUID.randomUUID();
        RuntimeException exHuerfana = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE "REVISION_CONCILIACION_PE2"
                       SET "estadoRevision" = 'SUPERADA'
                     WHERE "idRevisionConciliacion" = ?
                    """, idRev1);
            jdbc.update("""
                    INSERT INTO "REVISION_CONCILIACION_PE2" (
                        "idCierreRecorrido","numeroRevision","idRevisionAnterior","estadoRevision",
                        "claveIdempotenciaRevision","versionHuella","huellaCanonica",
                        "idUsuarioAdmin","fechaHoraRevision",
                        "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                        "importeDiferenciaFaltante","importeCustodiaNoImputada","motivoRevision")
                    VALUES (?,2,?,'VIGENTE',?,'v1','h-huerfana',?,clock_timestamp(),180,170,170,10,0,'Sin enlace cabecera')
                    """, pend.idCierre(), idRev1, claveHuerfana, e.idAdmin());
        }));
        assertEquals("P0001", extraerSqlState(exHuerfana));
        assertTrue(extraerDetalle(exHuerfana).contains("T1-C2/REV-COMMIT"));

        // Verificar rollback completo tras los 5 intentos negativos: estado intacto en Rev 1 VIGENTE y 0 Cobros
        assertEquals("VIGENTE", jdbc.queryForObject(
                "SELECT \"estadoRevision\" FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idRevisionConciliacion\"=?",
                String.class, idRev1));
        assertEquals("PENDIENTE_CONCILIACION", jdbc.queryForObject(
                "SELECT \"estadoCierre\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                String.class, pend.idCierre()));
        assertEquals(idRev1, jdbc.queryForObject(
                "SELECT \"idRevisionVigente\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                Long.class, pend.idCierre()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, pend.idCierre()));
    }

    /**
     * Prueba POSITIVA completa de coherencia al COMMIT:
     * Rev 1 (PENDIENTE) -> Rectificacion Rev 2 (SUPERADA->VIGENTE) -> Invalidacion H
     * (INVALIDADA + idRevisionVigente=NULL) -> Sucesora Rev 3 -> Confirmacion T1-C2 con E-04.
     */
    @Test
    void integridadCommitValidaCadenaCompletaRectificacionInvalidacionSucesoraYConfirmacionT1C2() {
        EscenarioBase e = crearEscenarioBase("REC-REV-POS-CADENA");
        var pend = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("15.00"), new BigDecimal("15.00"),
                "Rev 1 inicial con error de custodia",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("45.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("50.00")))));
        assertNotNull(pend);

        var rev2 = tx.execute(s -> GfisLabSupport.rectificarPendienteConciliacionEnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("12.00"), new BigDecimal("12.00"),
                "Rev 2 rectificacion parcial",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("42.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("56.00")))));
        assertNotNull(rev2);
        assertEquals(2, rev2.numeroRevision());

        tx.executeWithoutResult(s -> GfisLabSupport.invalidarRevisionPendienteEnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(),
                "Rev 2 invalidada para recuento completo"));

        var rev3 = tx.execute(s -> GfisLabSupport.rectificarPendienteConciliacionEnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                "Rev 3 definitiva verificada tras recuento",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(rev3);
        assertEquals(3, rev3.numeroRevision());

        var cierre = tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), 3,
                UUID.randomUUID(), "v1", "h-pos-cadena-rev3",
                "MIXTA_FALTANTE_Y_CUSTODIA",
                "Confirmacion T1-C2 definitiva sobre rev 3",
                new GfisLabSupport.EvidenciaEgresoCustodia(
                        "REC-POS-CAD-" + e.f().base, "Repartidor #" + e.idRepartidor(), "Devolucion 10.00"),
                true, false));
        assertNotNull(cierre);
        assertEquals("CERRADO_CON_DIFERENCIA_RESUELTA", cierre.estadoCierre());

        assertEquals(List.of("SUPERADA", "INVALIDADA", "CONFIRMADA_T1"), jdbc.queryForList("""
                SELECT "estadoRevision" FROM "REVISION_CONCILIACION_PE2"
                 WHERE "idCierreRecorrido" = ?
                 ORDER BY "numeroRevision" ASC
                """, String.class, pend.idCierre()));
        assertEquals("CERRADO", jdbc.queryForObject(
                "SELECT \"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=?",
                String.class, e.recorrido()));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, pend.idCierre()));
    }

    /**
     * B1-R1 (ratificada 21/11/2026): diferencia inicial positiva -> rectificacion acreditada a cero
     * (faltante=0, custodia=0) -> confirmacion T1-C2 en estado CONFIRMADO con historial inmutable y
     * ausencia de doble Cobro.
     */
    @Test
    void b1r1_DiferenciaInicialRectificacionACeroYConfirmacionFinalConfirmadoSinDobleCobro() {
        EscenarioBase e = crearEscenarioBase("REC-B1R1-OK");
        int cobrosAntes = jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class);
        int cajaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class);

        // 1) Diferencia inicial positiva (170 propuesto + 10 custodia + 10 faltante)
        var pend = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                "Error inicial de carga en pedA2: faltaron 10 imputados",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(pend);
        Long idRev1 = jdbc.queryForObject(
                "SELECT \"idRevisionVigente\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                Long.class, pend.idCierre());
        assertNotNull(idRev1);

        // 2) Rectificacion acreditada a cero (faltante=0, custodia=0, imputado=180=esperado) sin efectos financieros
        var rev2 = tx.execute(s -> GfisLabSupport.rectificarPendienteConciliacionEnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                "Recuento acreditado B1-R1: pedA2=50.00 completo, diferencia y custodia en 0.00",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("50.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(rev2);
        assertEquals(2, rev2.numeroRevision());
        assertEquals(cobrosAntes, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));
        assertEquals(cajaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class));

        // 3) Confirmacion final T1-C2 => estado CONFIRMADO preservando historial y sin doble Cobro
        var cierre = tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), 2,
                UUID.randomUUID(), "v1", "h-b1r1-confirmado",
                "RECTIFICACION_ACREDITADA_SIN_DIFERENCIA",
                "Conciliacion resuelta sin diferencias mediante revision 2 acreditada",
                null, true, false));
        assertNotNull(cierre);
        assertEquals("CONFIRMADO", cierre.estadoCierre());
        assertTrue(cierre.reciboJson().contains("\"viaB1R1\": true") || cierre.reciboJson().contains("\"viaB1R1\":true"));

        // 4) Historial inmutable preservado (rev 1 SUPERADA + rev 2 CONFIRMADA_T1 + 6 propuestas + 2 eventos custodia rev 1)
        assertEquals(List.of("SUPERADA", "CONFIRMADA_T1"), jdbc.queryForList("""
                SELECT "estadoRevision" FROM "REVISION_CONCILIACION_PE2"
                 WHERE "idCierreRecorrido" = ? ORDER BY "numeroRevision" ASC
                """, String.class, pend.idCierre()));
        assertEquals(6, jdbc.queryForObject(
                "SELECT count(*) FROM \"PROPUESTA_APLICACION_PENDIENTE\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, pend.idCierre()));
        assertThrows(DataAccessException.class, () -> eliminarRevisionPorId(idRev1));
        assertThrows(DataAccessException.class, () -> mutarMotivoRevision(idRev1));

        // 5) Conservacion K2/PE-1 y ausencia de doble Cobro (exactamente 2 Cobros: A=120, B=60; V_e^vig=0)
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, pend.idCierre()));
        assertEquals(cobrosAntes + 2, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));
        assertEquals(0, new BigDecimal("180.00").compareTo(jdbc.queryForObject("""
                SELECT COALESCE(SUM(mc."importe"), 0) FROM "MOVIMIENTO_CAJA" mc
                  JOIN "COBRO_RENDICION_K2" k ON k."idCobro" = mc."idCobro"
                 WHERE k."idCierreRecorrido" = ?
                """, BigDecimal.class, cierre.idCierre())));
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject("""
                SELECT COALESCE(SUM("reconocimientoVigenteVvig"), 0) FROM "RECONOCIMIENTO_ENTREGA_PE1"
                 WHERE "idPedido" IN (?,?,?)
                """, BigDecimal.class, e.pedA1(), e.pedA2(), e.pedB1())));
        assertEquals("CERRADO", jdbc.queryForObject(
                "SELECT \"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=?",
                String.class, e.recorrido()));
    }

    /**
     * B1-R1: replay idempotente devuelve recibo CONFIRMADO sin duplicar Cobro;
     * misma clave con otra huella => 409; nueva clave sobre cierre ya CONFIRMADO => 409.
     */
    @Test
    void b1r1_ReplayIdempotenteConflicto409YRechazoSegundoCierre() {
        EscenarioBase e = crearEscenarioBase("REC-B1R1-REPLAY");
        var pend = crearPendienteYRectificarACeroB1R1(e);
        UUID claveCierre = UUID.randomUUID();

        var primero = tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), 2,
                claveCierre, "v1", "h-b1r1-replay",
                "RECTIFICACION_ACREDITADA_SIN_DIFERENCIA", "Cierre B1-R1",
                null, true, false));
        assertNotNull(primero);
        assertEquals("CONFIRMADO", primero.estadoCierre());
        assertFalse(primero.replay());

        int cobrosPostPrimero = jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class);

        // Replay exacto => 0 Cobros adicionales, mismo recibo CONFIRMADO
        var replay = tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), 2,
                claveCierre, "v1", "h-b1r1-replay",
                "RECTIFICACION_ACREDITADA_SIN_DIFERENCIA", "Cierre B1-R1",
                null, true, false));
        assertNotNull(replay);
        assertTrue(replay.replay());
        assertEquals("CONFIRMADO", replay.estadoCierre());
        assertEquals(primero.reciboJson(), replay.reciboJson());
        assertEquals(cobrosPostPrimero, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));

        // Misma clave con huella distinta => 409
        IllegalStateException exHuella = assertThrows(IllegalStateException.class,
                () -> confirmarB1R1EnTx(pend.idCierre(), e.recorrido(), e.idAdmin(), claveCierre, "h-b1r1-distinta"));
        assertTrue(exHuella.getMessage().contains("409"));

        // Nueva clave sobre cierre ya CONFIRMADO => 409 y 0 Cobros nuevos
        UUID claveNueva = UUID.randomUUID();
        IllegalStateException exSegundo = assertThrows(IllegalStateException.class,
                () -> confirmarB1R1EnTx(pend.idCierre(), e.recorrido(), e.idAdmin(), claveNueva, "h-b1r1-segundo"));
        assertTrue(exSegundo.getMessage().contains("409"));
        assertEquals(cobrosPostPrimero, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));
    }

    /**
     * B1-R1 pruebas negativas:
     * 1) Prohibido abrir PENDIENTE_CONCILIACION inicial con diferencia 0/0.
     * 2) Prohibido confirmar como CONFIRMADO cuando subsiste diferencia real positiva.
     * 3) Prohibido confirmar como CERRADO_CON_DIFERENCIA_RESUELTA tras rectificar a 0/0.
     */
    @Test
    void b1r1_RechazaConfirmadoConDiferenciaPositivaYCoherenciaBiunivocaAlCommit() {
        // 1) Alta inicial PENDIENTE_CONCILIACION con 0/0 => rechazada por trigger
        EscenarioBase eCero = crearEscenarioBase("REC-B1R1-NEG-INI");
        assertThrows(DataAccessException.class, () -> registrarPendienteInicialCero(eCero));

        // 2) Diferencia real positiva (faltante=10) => prohibido cerrar como CONFIRMADO
        EscenarioBase ePos = crearEscenarioBase("REC-B1R1-NEG-POS");
        var pendPos = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, ePos.recorrido(), ePos.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("170.00"),
                new BigDecimal("10.00"), BigDecimal.ZERO.setScale(2),
                "Faltante real de 10.00",
                List.of(
                        new GfisLabSupport.AplicacionPlan(ePos.clienteA(), ePos.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(ePos.clienteA(), ePos.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(ePos.clienteB(), ePos.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(pendPos);

        DataAccessException exBlanqueo = assertThrows(DataAccessException.class,
                () -> intentarForzarConfirmadoConDiferenciaPositiva(pendPos.idCierre(), ePos.idAdmin()));
        assertTrue(exBlanqueo.getMessage().contains("B1-R1"));

        // 3) Tras rectificar a 0/0: prohibido cerrar como CERRADO_CON_DIFERENCIA_RESUELTA o sin Cobros K2
        EscenarioBase eB1 = crearEscenarioBase("REC-B1R1-NEG-EST");
        var pendB1 = crearPendienteYRectificarACeroB1R1(eB1);
        Long idRev2 = jdbc.queryForObject(
                "SELECT \"idRevisionVigente\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                Long.class, pendB1.idCierre());
        assertNotNull(idRev2);

        IllegalArgumentException exTipoInvalido = assertThrows(IllegalArgumentException.class,
                () -> confirmarConTipoIncorrecto(pendB1.idCierre(), eB1.recorrido(), eB1.idAdmin()));
        assertTrue(exTipoInvalido.getMessage().contains("B1-R1"));

        RuntimeException exSinCobros = assertThrows(RuntimeException.class,
                () -> intentarConfirmarB1R1SinCobrosK2(pendB1.idCierre(), idRev2, eB1.recorrido(), eB1.idAdmin()));
        assertEquals("P0001", extraerSqlState(exSinCobros));
        assertEquals("PENDIENTE_CONCILIACION", jdbc.queryForObject(
                "SELECT \"estadoCierre\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                String.class, pendB1.idCierre()));
    }

    /**
     * B1-R1 concurrencia y rollback:
     * 1) Rollback ante falla inyectada antes del COMMIT preserva PENDIENTE_CONCILIACION, rev 2 VIGENTE y 0 Cobros.
     * 2) Dos ADMIN concurrentes confirmando la misma rev 2 B1-R1 => 0 deadlocks (40P01),
     *    exactamente 1 CONFIRMADO, 1 conflicto 409 y 0 Cobros duplicados.
     */
    @Test
    void b1r1_RollbackAnteFallaYConcurrenciaDosAdminSinDobleCobro() throws Exception {
        // Parte 1: Rollback completo ante excepcion antes del COMMIT
        EscenarioBase eRb = crearEscenarioBase("REC-B1R1-RB");
        var pendRb = crearPendienteYRectificarACeroB1R1(eRb);
        int cobrosAntesRb = jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class);

        assertThrows(IllegalStateException.class,
                () -> ejecutarConfirmacionB1R1ConFallaAntesDeCommit(pendRb.idCierre(), eRb.recorrido(), eRb.idAdmin()));

        assertEquals("PENDIENTE_CONCILIACION", jdbc.queryForObject(
                "SELECT \"estadoCierre\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                String.class, pendRb.idCierre()));
        assertEquals("VIGENTE", jdbc.queryForObject("""
                SELECT "estadoRevision" FROM "REVISION_CONCILIACION_PE2"
                 WHERE "idCierreRecorrido" = ? AND "numeroRevision" = 2
                """, String.class, pendRb.idCierre()));
        assertEquals(cobrosAntesRb, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));

        // Parte 2: Concurrencia real de dos ADMIN confirmando la misma revision 2 B1-R1 con claves distintas
        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> f1 = pool.submit(() -> ejecutarConfirmacionConcurrenteB1R1(
                    listos, largada, pendRb.idCierre(), eRb.recorrido(), eRb.idAdmin(), "h-conc-1"));
            Future<String> f2 = pool.submit(() -> ejecutarConfirmacionConcurrenteB1R1(
                    listos, largada, pendRb.idCierre(), eRb.recorrido(), eRb.idAdmin(), "h-conc-2"));

            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            String r1 = f1.get(20, TimeUnit.SECONDS);
            String r2 = f2.get(20, TimeUnit.SECONDS);

            assertFalse(r1.contains("40P01") || r2.contains("40P01"), "Sin 40P01: r1=" + r1 + ", r2=" + r2);
            long exitos = List.of(r1, r2).stream().filter("OK:CONFIRMADO"::equals).count();
            long conflictos = List.of(r1, r2).stream().filter(s -> s.contains("409")).count();
            assertEquals(1, exitos, "Exactamente un ADMIN debe confirmar: r1=" + r1 + ", r2=" + r2);
            assertEquals(1, conflictos, "El segundo ADMIN debe recibir 409: r1=" + r1 + ", r2=" + r2);
            assertEquals("CONFIRMADO", jdbc.queryForObject(
                    "SELECT \"estadoCierre\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                    String.class, pendRb.idCierre()));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                    Integer.class, pendRb.idCierre()));
        }
    }

    private GfisLabSupport.ResultadoCierreLab crearPendienteYRectificarACeroB1R1(EscenarioBase e) {
        var pend = tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                "Pendiente inicial para rectificacion B1-R1",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        assertNotNull(pend);
        tx.execute(s -> GfisLabSupport.rectificarPendienteConciliacionEnTx(
                jdbc, pend.idCierre(), e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                "Rectificacion acreditada B1-R1 a cero",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("50.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
        return pend;
    }

    private void eliminarRevisionPorId(long idRev) {
        jdbc.update("DELETE FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idRevisionConciliacion\"=?", idRev);
    }

    private void mutarMotivoRevision(long idRev) {
        jdbc.update("UPDATE \"REVISION_CONCILIACION_PE2\" SET \"motivoRevision\"='Alterado' WHERE \"idRevisionConciliacion\"=?", idRev);
    }

    private void confirmarB1R1EnTx(long idCierre, long idRecorrido, long idAdmin, UUID clave, String huella) {
        tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, idCierre, idRecorrido, idAdmin, 2,
                clave, "v1", huella,
                "RECTIFICACION_ACREDITADA_SIN_DIFERENCIA", "Cierre B1-R1",
                null, true, false));
    }

    private void registrarPendienteInicialCero(EscenarioBase e) {
        tx.execute(s -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, e.recorrido(), e.idAdmin(), UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                "Intento ilegal de pendiente inicial con cero",
                List.of(
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA1(), new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteA(), e.pedA2(), new BigDecimal("50.00")),
                        new GfisLabSupport.AplicacionPlan(e.clienteB(), e.pedB1(), new BigDecimal("60.00")))));
    }

    private void intentarForzarConfirmadoConDiferenciaPositiva(long idCierre, long idAdmin) {
        jdbc.update("""
                UPDATE "CIERRE_RECORRIDO_PE2"
                   SET "estadoCierre" = 'CONFIRMADO',
                       "claveIdempotenciaCierre" = ?,
                       "versionHuella" = 'v1',
                       "huellaCanonica" = 'h-blanqueo',
                       "importeImputadoConfirmado" = "importeImputadoPropuesto",
                       "idUsuarioResolucionAdmin" = ?,
                       "tipoResolucionAdmin" = 'RECTIFICACION_ACREDITADA_SIN_DIFERENCIA',
                       "motivoResolucionAdmin" = 'Intento ilegal con faltante=10',
                       "fechaHoraResolucion" = clock_timestamp(),
                       "reciboHistoricoJson" = '{}'::jsonb
                 WHERE "idCierreRecorrido" = ?
                """, UUID.randomUUID(), idAdmin, idCierre);
    }

    private void confirmarConTipoIncorrecto(long idCierre, long idRecorrido, long idAdmin) {
        tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, idCierre, idRecorrido, idAdmin, 2,
                UUID.randomUUID(), "v1", "h-tipo-err",
                "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "Tipo incoherente con 0/0",
                null, true, false));
    }

    private void intentarConfirmarB1R1SinCobrosK2(long idCierre, long idRev2, long idRecorrido, long idAdmin) {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE "REVISION_CONCILIACION_PE2"
                       SET "estadoRevision" = 'CONFIRMADA_T1'
                     WHERE "idRevisionConciliacion" = ?
                    """, idRev2);
            jdbc.update("""
                    UPDATE "CIERRE_RECORRIDO_PE2"
                       SET "estadoCierre" = 'CONFIRMADO',
                           "claveIdempotenciaCierre" = ?,
                           "versionHuella" = 'v1',
                           "huellaCanonica" = 'h-b1r1-sin-cobros',
                           "importeImputadoConfirmado" = "importeImputadoPropuesto",
                           "idUsuarioResolucionAdmin" = ?,
                           "tipoResolucionAdmin" = 'RECTIFICACION_ACREDITADA_SIN_DIFERENCIA',
                           "motivoResolucionAdmin" = 'Sin Cobros K2',
                           "fechaHoraResolucion" = clock_timestamp(),
                           "reciboHistoricoJson" = '{}'::jsonb
                     WHERE "idCierreRecorrido" = ?
                    """, UUID.randomUUID(), idAdmin, idCierre);
            jdbc.update("UPDATE \"RECORRIDO_REPARTO\" SET \"estadoOperativo\"='CERRADO' WHERE \"idRecorrido\"=?",
                    idRecorrido);
        });
    }

    private void ejecutarConfirmacionB1R1ConFallaAntesDeCommit(long idCierre, long idRecorrido, long idAdmin) {
        tx.executeWithoutResult(s -> {
            GfisLabSupport.confirmarResolucionT1K2EnTx(
                    jdbc, idCierre, idRecorrido, idAdmin, 2,
                    UUID.randomUUID(), "v1", "h-b1r1-rb",
                    "RECTIFICACION_ACREDITADA_SIN_DIFERENCIA", "Falla inyectada antes del COMMIT",
                    null, true, false);
            throw new IllegalStateException("FALLA_INYECTADA_PRE_COMMIT_B1R1");
        });
    }

    private String ejecutarConfirmacionConcurrenteB1R1(
            CountDownLatch listos,
            CountDownLatch largada,
            long idCierre,
            long idRecorrido,
            long idAdmin,
            String huella) throws InterruptedException {
        listos.countDown();
        assertTrue(largada.await(5, TimeUnit.SECONDS));
        try {
            var res = tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                    jdbc, idCierre, idRecorrido, idAdmin, 2,
                    UUID.randomUUID(), "v1", huella,
                    "RECTIFICACION_ACREDITADA_SIN_DIFERENCIA", "Confirmacion concurrente B1-R1",
                    null, true, false));
            return "OK:" + (res == null ? "NULL" : res.estadoCierre());
        } catch (Exception ex) {
            return "ERR:" + extraerDetalle(ex);
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
