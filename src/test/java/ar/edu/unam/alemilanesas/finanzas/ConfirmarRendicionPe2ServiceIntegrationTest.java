package ar.edu.unam.alemilanesas.finanzas;

import ar.edu.unam.alemilanesas.finanzas.ConfirmarRendicionPe2Service.DatosConfirmacionConciliacion;
import ar.edu.unam.alemilanesas.finanzas.ConfirmarRendicionPe2Service.DatosConfirmacionLimpia;
import ar.edu.unam.alemilanesas.finanzas.ConfirmarRendicionPe2Service.EvidenciaEgresoCustodia;
import ar.edu.unam.alemilanesas.finanzas.ConfirmarRendicionPe2Service.RendicionConfirmada;
import ar.edu.unam.alemilanesas.finanzas.RendicionPreliminarPe2Service.CoberturaNoAcreditadaException;
import ar.edu.unam.alemilanesas.finanzas.RendicionPreliminarPe2Service.ConciliacionPreliminarRegistrada;
import ar.edu.unam.alemilanesas.finanzas.RendicionPreliminarPe2Service.DatosConciliacionPreliminar;
import ar.edu.unam.alemilanesas.finanzas.RendicionPreliminarPe2Service.PropuestaAplicacion;
import ar.edu.unam.alemilanesas.produccioninventario.PreparacionFixture;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService;
import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.GestionarRecorridoRepartoService;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
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
 * R09.3b-B — Pruebas reales PostgreSQL 17.11 sobre Flyway V1-V4 para ConfirmarRendicionPe2Service (T1-C2 / K2):
 * cierre limpio multi-Cliente K2 + UC-14 + FA-1 + D7-A, confirmacion de conciliacion resuelta (faltante parcial,
 * faltante total con 0 Cobros/Caja, mixta + egreso custodia E-04 sin segundo ingreso Caja), RBAC/revalidacion en replay,
 * estados invalidos/FA-2/422/anti-blanqueamiento, idempotencia/replay/huella inyectiva/inmutabilidad V4,
 * concurrencia A2(NS_RECORRIDO) sin 40P01 y rollback atomico pre-commit.
 */
@SpringBootTest
@Testcontainers
class ConfirmarRendicionPe2ServiceIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired RegistrarPreparacionService preparaciones;
    @Autowired GestionarRecorridoRepartoService recorridos;
    @Autowired RegistrarEntregaService entregas;
    @Autowired RendicionPreliminarPe2Service preliminar;
    @Autowired ConfirmarRendicionPe2Service confirmacion;
    @Autowired ConsultarCuentaCorrienteService cuentaCorriente;
    @Autowired CobroManualService cobroManual;

    private record EscenarioTresEntregas(
            PreparacionFixture f,
            UUID sesionAdmin,
            UUID sesionVendedor,
            UUID sesionRepartidor,
            UUID sesionProduccion,
            long idAdmin,
            long idRepartidor,
            long clienteA,
            long clienteB,
            long idRecorrido,
            long pedA1,
            long pedA2,
            long pedB1) {}

    private record ContadoresFinancieros(
            int cobros,
            int cobrosK2,
            int appsK2,
            int pagosCuenta,
            int caja,
            int eventosImpK2,
            int egresosCustodia) {}

    private ContadoresFinancieros capturarContadores() {
        return new ContadoresFinancieros(
                count("SELECT count(*) FROM \"COBRO\""),
                count("SELECT count(*) FROM \"COBRO_RENDICION_K2\""),
                count("SELECT count(*) FROM \"APLICACION_RENDICION_K2\""),
                count("SELECT count(*) FROM \"MOVIMIENTO_CUENTA\" WHERE \"tipoMovimiento\"='PAGO'"),
                count("SELECT count(*) FROM \"MOVIMIENTO_CAJA\""),
                count("SELECT count(*) FROM \"EVENTO_IMPUTACION_FINANCIERA\" WHERE \"tipoEvento\"='APLICACION_RENDICION_K2'"),
                count("SELECT count(*) FROM \"EGRESO_CUSTODIA_RENDICION\""));
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private static void assertDec(String esperado, BigDecimal real) {
        assertNotNull(real);
        assertEquals(0, new BigDecimal(esperado).compareTo(real),
                "Esperado=" + esperado + " real=" + real.toPlainString());
    }

    /**
     * Crea un Recorrido con 3 Pedidos: Cliente A (pedA1=270.00, pedA2=180.00 -> 450.00)
     * y Cliente B (pedB1=180.00), total esperado 630.00 cuando todos se entregan.
     */
    private EscenarioTresEntregas crearEscenarioTresEntregas(String ref, boolean entregarB) {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        UUID sesionAdmin = f.actores.sesiones.get("ADMINISTRADOR");
        UUID sesionVendedor = f.actores.vendedor();
        UUID sesionRep = f.actores.sesiones.get("REPARTIDOR");
        UUID sesionProd = f.actores.sesiones.get("RESPONSABLE_PRODUCCION");
        long idAdmin = f.actores.base + 1;
        long idRep = f.actores.base + 3;
        long clienteA = f.actores.base;
        long clienteB = f.actores.base + 12;

        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal","estado","categoriaComercial","fechaHoraAlta")
                VALUES (?,?,'3764000499','ACTIVO','CUMPLIDOR',now())
                """, clienteB, "Cliente B T1C2 " + clienteB));

        long pedA1 = f.pedido; // 3 * 100 - 10% = 270.00
        long pedA2 = pedidos.cargarPedido(sesionVendedor, new CargarPedidoService.DatosPedido(
                clienteA, f.actores.hoy.plusDays(1),
                List.of(new CargarPedidoService.ItemPedido(f.producto, new BigDecimal("2.000"))),
                new CargarPedidoService.DireccionEntrega("Av T1 A2", "102", null, null, "Posadas", "Misiones", null, null),
                UUID.randomUUID())).idPedido(); // 2 * 100 - 10% = 180.00
        long pedB1 = pedidos.cargarPedido(sesionVendedor, new CargarPedidoService.DatosPedido(
                clienteB, f.actores.hoy.plusDays(1),
                List.of(new CargarPedidoService.ItemPedido(f.producto, new BigDecimal("2.000"))),
                new CargarPedidoService.DireccionEntrega("Av T1 B1", "201", null, null, "Posadas", "Misiones", null, null),
                UUID.randomUUID())).idPedido(); // 2 * 100 - 10% = 180.00

        long idRec = recorridos.abrirRecorrido(sesionVendedor, idRep, ref).idRecorrido();
        for (long ped : List.of(pedA1, pedA2, pedB1)) {
            recorridos.asignarYVincularRecorrido(sesionVendedor,
                    new AsignarRepartoService.DatosAsignacion(ped, idRep, null), idRec);
        }
        preparaciones.registrarPreparacionPedido(sesionProd,
                new RegistrarPreparacionService.DatosPreparacion(pedA1, f.producto, new BigDecimal("3.000"), null));
        preparaciones.registrarPreparacionPedido(sesionProd,
                new RegistrarPreparacionService.DatosPreparacion(pedA2, f.producto, new BigDecimal("2.000"), null));
        preparaciones.registrarPreparacionPedido(sesionProd,
                new RegistrarPreparacionService.DatosPreparacion(pedB1, f.producto, new BigDecimal("2.000"), null));

        entregas.registrarEntrega(sesionRep, new RegistrarEntregaService.DatosEntrega(
                pedA1, List.of(new RegistrarEntregaService.ItemEntrega(f.producto, new BigDecimal("3.000"))),
                new BigDecimal("270.00"), UUID.randomUUID()));
        entregas.registrarEntrega(sesionRep, new RegistrarEntregaService.DatosEntrega(
                pedA2, List.of(new RegistrarEntregaService.ItemEntrega(f.producto, new BigDecimal("2.000"))),
                new BigDecimal("180.00"), UUID.randomUUID()));
        if (entregarB) {
            entregas.registrarEntrega(sesionRep, new RegistrarEntregaService.DatosEntrega(
                    pedB1, List.of(new RegistrarEntregaService.ItemEntrega(f.producto, new BigDecimal("2.000"))),
                    new BigDecimal("180.00"), UUID.randomUUID()));
        }

        return new EscenarioTresEntregas(
                f, sesionAdmin, sesionVendedor, sesionRep, sesionProd,
                idAdmin, idRep, clienteA, clienteB, idRec, pedA1, pedA2, pedB1);
    }

    @Test
    void cierreLimpioDirectoMultiClienteK2ConservacionCuentaCajaFa1YD7A() {
        var e = crearEscenarioTresEntregas("T1C2-LIMPIO", true);
        var antes = capturarContadores();

        RendicionConfirmada res = confirmacion.confirmarCierreLimpio(e.sesionAdmin(),
                new DatosConfirmacionLimpia(
                        e.idRecorrido(), UUID.randomUUID(), new BigDecimal("630.00"),
                        List.of(
                                new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                                new PropuestaAplicacion(e.clienteA(), e.pedA2(), new BigDecimal("180.00")),
                                new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("180.00")))));

        assertTrue(res.creado());
        assertEquals("CONFIRMADO", res.estadoCierre());
        assertEquals("CERRADO", res.estadoRecorrido());
        assertNull(res.idRevisionConfirmada());
        assertDec("630.00", res.importeImputadoConfirmado());
        assertEquals(2, res.cobros().size()); // K2: exactamente 1 Cobro por Cliente (A=450, B=180)
        assertEquals(3, res.aplicaciones().size());
        assertNotNull(res.reciboHistoricoJson());

        var despues = capturarContadores();
        assertEquals(antes.cobros() + 2, despues.cobros());
        assertEquals(antes.cobrosK2() + 2, despues.cobrosK2());
        assertEquals(antes.appsK2() + 3, despues.appsK2());
        assertEquals(antes.pagosCuenta() + 2, despues.pagosCuenta());
        assertEquals(antes.caja() + 2, despues.caja());
        assertEquals(antes.eventosImpK2() + 3, despues.eventosImpK2());
        assertEquals(antes.egresosCustodia(), despues.egresosCustodia());

        // Conservacion PE-1 y UC-14 sin doble descuento
        assertDec("0.00", jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, e.pedA1()));
        var ccA = cuentaCorriente.consultarCuentaCorriente(e.sesionVendedor(), e.clienteA(), null, null);
        assertDec("0.00", ccA.saldoConfirmado());
        assertDec("0.00", ccA.importeTransitoValidado());
        assertDec("0.00", ccA.saldoExigibleProvisional());

        // FA-1 liberado tras cierre terminal y D7-A bloquea anulacion manual del Cobro REPARTO
        assertTrue(recorridos.abrirRecorrido(e.sesionVendedor(), e.idRepartidor(), "T1C2-SIGUIENTE").idRecorrido() > e.idRecorrido());
        long idCobroReparto = res.cobros().getFirst().idCobro();
        UUID sesionAdmin = e.sesionAdmin();
        assertThrows(IllegalArgumentException.class,
                () -> cobroManual.anularCobroManual(sesionAdmin, idCobroReparto, "Intento ilegal D7-A"));
    }

    @Test
    void confirmacionConciliacionFaltanteParcialConservaTransitoYNoRestauraDeudaCliente() {
        var e = crearEscenarioTresEntregas("T1C2-FALT-PARC", true);
        preliminar.registrarConciliacionPreliminar(e.sesionAdmin(), new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("590.00"), "Faltante de 40 en cliente B",
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e.clienteA(), e.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("140.00")))));

        var antes = capturarContadores();
        RendicionConfirmada res = confirmacion.confirmarConciliacionResuelta(e.sesionAdmin(),
                new DatosConfirmacionConciliacion(
                        e.idRecorrido(), 1, UUID.randomUUID(),
                        "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "Asentado por administracion", null));

        assertTrue(res.creado());
        assertEquals("CERRADO_CON_DIFERENCIA_RESUELTA", res.estadoCierre());
        assertEquals("CERRADO", res.estadoRecorrido());
        assertEquals(1, res.numeroRevisionConfirmada());
        assertDec("590.00", res.importeImputadoConfirmado());
        assertDec("40.00", res.importeDiferenciaFaltante());
        assertNull(res.egresoCustodia());
        assertEquals("CONFIRMADA_T1", jdbc.queryForObject(
                "SELECT \"estadoRevision\" FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idRevisionConciliacion\"=?",
                String.class, res.idRevisionConfirmada()));

        // Solo 590.00 en Cobros/Caja (0 Cobro por faltante de 40.00)
        var despues = capturarContadores();
        assertEquals(antes.cobros() + 2, despues.cobros());
        assertEquals(antes.caja() + 2, despues.caja());
        assertDec("40.00", jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, e.pedB1()));

        // RD-F04-005 / UC-14: faltante no restaura exigibilidad al Cliente B (confirmado=40, transito=40, exigible=0)
        var ccB = cuentaCorriente.consultarCuentaCorriente(e.sesionVendedor(), e.clienteB(), null, null);
        assertDec("40.00", ccB.saldoConfirmado());
        assertDec("40.00", ccB.importeTransitoValidado());
        assertDec("0.00", ccB.saldoExigibleProvisional());
    }

    @Test
    void confirmacionConciliacionFaltanteTotalProduceCeroEfectosFinancierosYCierraRecorrido() {
        var e = crearEscenarioTresEntregas("T1C2-FALT-TOTAL", true);
        preliminar.registrarConciliacionPreliminar(e.sesionAdmin(), new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), BigDecimal.ZERO, "Faltante total del recorrido", List.of()));

        var antes = capturarContadores();
        RendicionConfirmada res = confirmacion.confirmarConciliacionResuelta(e.sesionAdmin(),
                new DatosConfirmacionConciliacion(
                        e.idRecorrido(), 1, UUID.randomUUID(),
                        "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "Faltante total documentado", null));

        assertTrue(res.creado());
        assertEquals("CERRADO_CON_DIFERENCIA_RESUELTA", res.estadoCierre());
        assertEquals("CERRADO", res.estadoRecorrido());
        assertDec("0.00", res.importeImputadoConfirmado());
        assertDec("630.00", res.importeDiferenciaFaltante());
        assertTrue(res.cobros().isEmpty());
        assertTrue(res.aplicaciones().isEmpty());
        assertEquals(antes, capturarContadores());
        assertDec("270.00", jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, e.pedA1()));
    }

    @Test
    void confirmacionMixtaRegistraEgresoCustodiaE04SinSegundoIngresoCajaYBloqueaBlanqueamiento() {
        var e = crearEscenarioTresEntregas("T1C2-MIXTA-E04", true);
        UUID sesionAdmin = e.sesionAdmin();
        // esperado=630, recibido=615, propuesto=590 -> faltante=40, custodia=25
        preliminar.registrarConciliacionPreliminar(sesionAdmin, new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("615.00"), "Faltante 40 y custodia 25",
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e.clienteA(), e.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("140.00")))));

        // Anti-blanqueamiento y resoluciones incoherentes rechazadas
        var cmdBlanqueo = new DatosConfirmacionConciliacion(
                e.idRecorrido(), 1, UUID.randomUUID(),
                "RECTIFICACION_ACREDITADA_SIN_DIFERENCIA", "Intento blanqueo", null);
        assertEquals("BLANQUEAMIENTO_NO_AUTORIZADO", assertThrows(IllegalStateException.class,
                () -> confirmacion.confirmarConciliacionResuelta(sesionAdmin, cmdBlanqueo)).getMessage());

        var cmdSinEgreso = new DatosConfirmacionConciliacion(
                e.idRecorrido(), 1, UUID.randomUUID(),
                "MIXTA_FALTANTE_Y_CUSTODIA", "Falta recibo custodia", null);
        assertEquals("TIPO_RESOLUCION_INCOHERENTE", assertThrows(IllegalArgumentException.class,
                () -> confirmacion.confirmarConciliacionResuelta(sesionAdmin, cmdSinEgreso)).getMessage());

        BigDecimal cajaAntes = jdbc.queryForObject(
                "SELECT COALESCE(SUM(\"importe\"),0) FROM \"MOVIMIENTO_CAJA\"", BigDecimal.class);
        var contAntes = capturarContadores();

        RendicionConfirmada res = confirmacion.confirmarConciliacionResuelta(sesionAdmin,
                new DatosConfirmacionConciliacion(
                        e.idRecorrido(), 1, UUID.randomUUID(),
                        "MIXTA_FALTANTE_Y_CUSTODIA", "Devolucion fisica acreditada",
                        new EvidenciaEgresoCustodia("REC-DEV-E04-" + e.idRecorrido(), "Repartidor titular", "Sobrante devuelto")));

        assertTrue(res.creado());
        assertEquals("CERRADO_CON_DIFERENCIA_RESUELTA", res.estadoCierre());
        assertNotNull(res.egresoCustodia());
        assertDec("25.00", res.egresoCustodia().importeEgreso());
        assertEquals(contAntes.egresosCustodia() + 1, capturarContadores().egresosCustodia());

        // Caja suma exactamente 590.00 (NUNCA 615.00 ni egreso de caja ventas por la custodia de 25.00)
        BigDecimal cajaDespues = jdbc.queryForObject(
                "SELECT COALESCE(SUM(\"importe\"),0) FROM \"MOVIMIENTO_CAJA\"", BigDecimal.class);
        assertDec("590.00", cajaDespues.subtract(cajaAntes));
    }

    @Test
    void rbacNegativoYRevalidacionDeAdminDeshabilitadoInclusoEnReplay() {
        var e = crearEscenarioTresEntregas("T1C2-RBAC", true);
        var antes = capturarContadores();
        var cmdLimpio = new DatosConfirmacionLimpia(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("630.00"),
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e.clienteA(), e.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("180.00"))));
        var cmdConc = new DatosConfirmacionConciliacion(
                e.idRecorrido(), 1, UUID.randomUUID(),
                "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "No autorizado", null);

        for (UUID s : List.of(e.sesionVendedor(), e.sesionRepartidor(), e.sesionProduccion(), UUID.randomUUID())) {
            assertThrows(SecurityException.class, () -> confirmacion.confirmarCierreLimpio(s, cmdLimpio));
            assertThrows(SecurityException.class, () -> confirmacion.confirmarConciliacionResuelta(s, cmdConc));
        }
        assertThrows(SecurityException.class, () -> confirmacion.confirmarCierreLimpio(null, cmdLimpio));
        assertEquals(antes, capturarContadores());

        // Confirmar con ADMIN habilitado y luego deshabilitarlo -> replay rechazado (C1-a)
        assertTrue(confirmacion.confirmarCierreLimpio(e.sesionAdmin(), cmdLimpio).creado());
        long adminRespaldo = e.f().actores.base + 54;
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado","cambioCredencialPendiente","fechaHoraAlta")
                    VALUES (?,?,'HASH','HABILITADO',false,clock_timestamp())
                    """, adminRespaldo, "admin_resp_t1c2_" + adminRespaldo);
            jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'ADMINISTRADOR',clock_timestamp())", adminRespaldo);
            jdbc.update("""
                    UPDATE "USUARIO" SET "estado"='DESHABILITADO', "fechaHoraDeshabilitacion"=clock_timestamp()
                     WHERE "idUsuario"=?
                    """, e.idAdmin());
        });
        UUID sesionDeshabilitada = e.sesionAdmin();
        assertThrows(SecurityException.class, () -> confirmacion.confirmarCierreLimpio(sesionDeshabilitada, cmdLimpio));
    }

    @Test
    void estadosInvalidosFa2Cobertura422DiferenciaEnLimpioYRevisionObsoleta() {
        // 1) FA-2 con pedido pendiente
        var eFa2 = crearEscenarioTresEntregas("T1C2-FA2", false);
        UUID adminFa2 = eFa2.sesionAdmin();
        var cmdFa2 = new DatosConfirmacionLimpia(
                eFa2.idRecorrido(), UUID.randomUUID(), new BigDecimal("450.00"),
                List.of(
                        new PropuestaAplicacion(eFa2.clienteA(), eFa2.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(eFa2.clienteA(), eFa2.pedA2(), new BigDecimal("180.00"))));
        assertEquals("FA2_PEDIDOS_PENDIENTES_ACTIVOS", assertThrows(IllegalStateException.class,
                () -> confirmacion.confirmarCierreLimpio(adminFa2, cmdFa2)).getMessage());

        // 2) Recorrido inexistente y cierre limpio con diferencia / cobertura 422 / sin conciliacion
        var e = crearEscenarioTresEntregas("T1C2-ERR", true);
        UUID admin = e.sesionAdmin();
        var antes = capturarContadores();

        var cmdNoExiste = new DatosConfirmacionLimpia(999_999_998L, UUID.randomUUID(), BigDecimal.TEN, List.of());
        assertEquals("RECORRIDO_NO_ENCONTRADO", assertThrows(IllegalArgumentException.class,
                () -> confirmacion.confirmarCierreLimpio(admin, cmdNoExiste)).getMessage());

        var cmdDif = new DatosConfirmacionLimpia(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("600.00"),
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e.clienteA(), e.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("150.00"))));
        assertEquals("DIFERENCIA_REQUIERE_CONCILIACION_PRELIMINAR", assertThrows(IllegalStateException.class,
                () -> confirmacion.confirmarCierreLimpio(admin, cmdDif)).getMessage());

        var cmd422 = new DatosConfirmacionLimpia(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("640.00"),
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("280.00")),
                        new PropuestaAplicacion(e.clienteA(), e.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("180.00"))));
        assertThrows(CoberturaNoAcreditadaException.class, () -> confirmacion.confirmarCierreLimpio(admin, cmd422));

        var cmdSinConc = new DatosConfirmacionConciliacion(
                e.idRecorrido(), 1, UUID.randomUUID(),
                "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "No hay pendiente", null);
        assertEquals("CONCILIACION_NO_ENCONTRADA", assertThrows(IllegalStateException.class,
                () -> confirmacion.confirmarConciliacionResuelta(admin, cmdSinConc)).getMessage());

        // Registrar pendiente -> bloquear cierre limpio directo y rechazar revision obsoleta
        preliminar.registrarConciliacionPreliminar(admin, new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("600.00"), "Faltante 30",
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e.clienteA(), e.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("150.00")))));

        var cmdLimpioSobrePend = new DatosConfirmacionLimpia(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("630.00"),
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e.clienteA(), e.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("180.00"))));
        assertEquals("CONCILIACION_PENDIENTE_EXISTENTE", assertThrows(IllegalStateException.class,
                () -> confirmacion.confirmarCierreLimpio(admin, cmdLimpioSobrePend)).getMessage());

        var cmdRevObsoleta = new DatosConfirmacionConciliacion(
                e.idRecorrido(), 2, UUID.randomUUID(),
                "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "Rev incorrecta", null);
        assertEquals("REVISION_OBSOLETA", assertThrows(IllegalStateException.class,
                () -> confirmacion.confirmarConciliacionResuelta(admin, cmdRevObsoleta)).getMessage());

        assertEquals(antes, capturarContadores());
    }

    @Test
    void idempotenciaReplayHuellaInequivocaConflictosEInmutabilidadTerminal() {
        var e1 = crearEscenarioTresEntregas("T1C2-IDEMP-1", true);
        var e2 = crearEscenarioTresEntregas("T1C2-IDEMP-2", true);
        UUID admin1 = e1.sesionAdmin();
        UUID admin2 = e2.sesionAdmin();

        // Conciliacion mixta en e1 para probar huella inyectiva sobre motivo + recibo/receptor/motivoEgreso
        preliminar.registrarConciliacionPreliminar(admin1, new DatosConciliacionPreliminar(
                e1.idRecorrido(), UUID.randomUUID(), new BigDecimal("615.00"), "Mixta para huella",
                List.of(
                        new PropuestaAplicacion(e1.clienteA(), e1.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e1.clienteA(), e1.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e1.clienteB(), e1.pedB1(), new BigDecimal("140.00")))));

        UUID clave = UUID.randomUUID();
        RendicionConfirmada primera = confirmacion.confirmarConciliacionResuelta(admin1,
                new DatosConfirmacionConciliacion(
                        e1.idRecorrido(), 1, clave, "MIXTA_FALTANTE_Y_CUSTODIA", "a|b",
                        new EvidenciaEgresoCustodia("c", "rec|1", "m")));
        assertTrue(primera.creado());
        var contTrasAlta = capturarContadores();

        // 1) Replay con espacios normalizados -> mismo recibo historico, 0 escrituras
        RendicionConfirmada replay = confirmacion.confirmarConciliacionResuelta(admin1,
                new DatosConfirmacionConciliacion(
                        e1.idRecorrido(), 1, clave, "MIXTA_FALTANTE_Y_CUSTODIA", "  a|b  ",
                        new EvidenciaEgresoCustodia(" c ", "rec|1", " m ")));
        assertFalse(replay.creado());
        assertEquals(primera.reciboHistoricoJson(), replay.reciboHistoricoJson());
        assertEquals(primera.cobros(), replay.cobros());
        assertEquals(primera.egresoCustodia(), replay.egresoCustodia());

        // 2) Payload ambiguo por delimitador '|' (motivo="a", recibo="b|c") -> CLAVE_IDEMPOTENCIA_CONFLICTO_PAYLOAD
        var cmdAmbiguo = new DatosConfirmacionConciliacion(
                e1.idRecorrido(), 1, clave, "MIXTA_FALTANTE_Y_CUSTODIA", "a",
                new EvidenciaEgresoCustodia("b|c", "rec|1", "m"));
        assertEquals("CLAVE_IDEMPOTENCIA_CONFLICTO_PAYLOAD", assertThrows(IllegalStateException.class,
                () -> confirmacion.confirmarConciliacionResuelta(admin1, cmdAmbiguo)).getMessage());

        // 3) Misma clave sobre otro Recorrido -> CLAVE_IDEMPOTENCIA_CONFLICTO_AMBITO
        var cmdOtroRec = new DatosConfirmacionLimpia(
                e2.idRecorrido(), clave, new BigDecimal("630.00"),
                List.of(
                        new PropuestaAplicacion(e2.clienteA(), e2.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e2.clienteA(), e2.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e2.clienteB(), e2.pedB1(), new BigDecimal("180.00"))));
        assertEquals("CLAVE_IDEMPOTENCIA_CONFLICTO_AMBITO", assertThrows(IllegalStateException.class,
                () -> confirmacion.confirmarCierreLimpio(admin2, cmdOtroRec)).getMessage());

        // 4) Nueva clave sobre cierre ya terminal -> RECORRIDO_YA_CERRADO
        var cmdNuevaClave = new DatosConfirmacionConciliacion(
                e1.idRecorrido(), 1, UUID.randomUUID(), "MIXTA_FALTANTE_Y_CUSTODIA", "Otro intento",
                new EvidenciaEgresoCustodia("REC-OTRO", "Rep", "m"));
        assertEquals("RECORRIDO_YA_CERRADO", assertThrows(IllegalStateException.class,
                () -> confirmacion.confirmarConciliacionResuelta(admin1, cmdNuevaClave)).getMessage());

        // 5) Inmutabilidad terminal respaldada por triggers V4
        long idCierre = primera.idCierreRecorrido();
        assertThrows(DataAccessException.class, () ->
                jdbc.update("UPDATE \"CIERRE_RECORRIDO_PE2\" SET \"motivoResolucionAdmin\"='Editado' WHERE \"idCierreRecorrido\"=?", idCierre));
        assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?", idCierre));
        assertEquals(contTrasAlta, capturarContadores());
    }

    @Test
    void concurrenciaA2DobleConfirmacionYCarreraConPreliminarSinDeadlock() throws Exception {
        // 1) Doble confirmacion limpia concurrente con MISMA clave -> 1 creado=true, 1 replay=false, 0 errores
        var e1 = crearEscenarioTresEntregas("T1C2-CONC-SAME", true);
        UUID claveMisma = UUID.randomUUID();
        var cmdMisma = new DatosConfirmacionLimpia(
                e1.idRecorrido(), claveMisma, new BigDecimal("630.00"),
                List.of(
                        new PropuestaAplicacion(e1.clienteA(), e1.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e1.clienteA(), e1.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e1.clienteB(), e1.pedB1(), new BigDecimal("180.00"))));

        List<Object> resMisma = ejecutarEnParalelo(
                () -> confirmacion.confirmarCierreLimpio(e1.sesionAdmin(), cmdMisma),
                () -> confirmacion.confirmarCierreLimpio(e1.sesionAdmin(), cmdMisma));

        int creados = 0;
        int replays = 0;
        for (Object r : resMisma) {
            if (r instanceof RendicionConfirmada rc) {
                if (rc.creado()) creados++; else replays++;
            } else {
                fail("Error inesperado en carrera misma clave: " + r);
            }
        }
        assertEquals(1, creados);
        assertEquals(1, replays);
        assertEquals(2, count("SELECT count(*) FROM \"COBRO_RENDICION_K2\" ck JOIN \"CIERRE_RECORRIDO_PE2\" c ON c.\"idCierreRecorrido\"=ck.\"idCierreRecorrido\" WHERE c.\"idRecorrido\"=" + e1.idRecorrido()));

        // 2) Doble confirmacion de conciliacion pendiente con CLAVES DISTINTAS -> 1 OK + 1 RECORRIDO_YA_CERRADO
        var e2 = crearEscenarioTresEntregas("T1C2-CONC-DIFF", true);
        preliminar.registrarConciliacionPreliminar(e2.sesionAdmin(), new DatosConciliacionPreliminar(
                e2.idRecorrido(), UUID.randomUUID(), new BigDecimal("590.00"), "Faltante 40",
                List.of(
                        new PropuestaAplicacion(e2.clienteA(), e2.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e2.clienteA(), e2.pedA2(), new BigDecimal("180.00")),
                        new PropuestaAplicacion(e2.clienteB(), e2.pedB1(), new BigDecimal("140.00")))));

        List<Object> resDiff = ejecutarEnParalelo(
                () -> confirmacion.confirmarConciliacionResuelta(e2.sesionAdmin(), new DatosConfirmacionConciliacion(
                        e2.idRecorrido(), 1, UUID.randomUUID(), "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "Hilo 1", null)),
                () -> confirmacion.confirmarConciliacionResuelta(e2.sesionAdmin(), new DatosConfirmacionConciliacion(
                        e2.idRecorrido(), 1, UUID.randomUUID(), "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO", "Hilo 2", null)));

        int okDiff = 0;
        int cerrDiff = 0;
        for (Object r : resDiff) {
            if (r instanceof RendicionConfirmada rc && rc.creado()) {
                okDiff++;
            } else if (r instanceof Throwable t) {
                assertNotEquals("40P01", extraerSqlState(t), "Sin deadlock 40P01");
                assertEquals("RECORRIDO_YA_CERRADO", t.getMessage());
                cerrDiff++;
            } else {
                fail("Resultado inesperado: " + r);
            }
        }
        assertEquals(1, okDiff);
        assertEquals(1, cerrDiff);

        // 3) Carrera confirmacion limpia vs conciliacion preliminar R09.3b-A sobre el mismo Recorrido
        var e3 = crearEscenarioTresEntregas("T1C2-VS-PRELIM", true);
        List<Object> resCruce = ejecutarEnParalelo(
                () -> confirmacion.confirmarCierreLimpio(e3.sesionAdmin(), new DatosConfirmacionLimpia(
                        e3.idRecorrido(), UUID.randomUUID(), new BigDecimal("630.00"),
                        List.of(
                                new PropuestaAplicacion(e3.clienteA(), e3.pedA1(), new BigDecimal("270.00")),
                                new PropuestaAplicacion(e3.clienteA(), e3.pedA2(), new BigDecimal("180.00")),
                                new PropuestaAplicacion(e3.clienteB(), e3.pedB1(), new BigDecimal("180.00"))))),
                () -> preliminar.registrarConciliacionPreliminar(e3.sesionAdmin(), new DatosConciliacionPreliminar(
                        e3.idRecorrido(), UUID.randomUUID(), new BigDecimal("590.00"), "Carrera preliminar",
                        List.of(
                                new PropuestaAplicacion(e3.clienteA(), e3.pedA1(), new BigDecimal("270.00")),
                                new PropuestaAplicacion(e3.clienteA(), e3.pedA2(), new BigDecimal("180.00")),
                                new PropuestaAplicacion(e3.clienteB(), e3.pedB1(), new BigDecimal("140.00"))))));

        int ganadores = 0;
        for (Object r : resCruce) {
            if (r instanceof RendicionConfirmada || r instanceof ConciliacionPreliminarRegistrada) {
                ganadores++;
            } else if (r instanceof Throwable t) {
                assertNotEquals("40P01", extraerSqlState(t), "Sin deadlock 40P01");
                assertTrue("CONCILIACION_PENDIENTE_EXISTENTE".equals(t.getMessage())
                        || "RECORRIDO_YA_CERRADO".equals(t.getMessage()),
                        "Mensaje inesperado: " + t.getMessage());
            }
        }
        assertEquals(1, ganadores);
        assertEquals(1, count("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=" + e3.idRecorrido()));
    }

    @Test
    void rollbackAtomicoPreCommitRevierteCierreCobrosCajaPe1EgresoYNoConsumeClave() {
        var e = crearEscenarioTresEntregas("T1C2-ROLLBACK", true);
        UUID sesionAdmin = e.sesionAdmin();
        ConciliacionPreliminarRegistrada prelim = preliminar.registrarConciliacionPreliminar(
                sesionAdmin, new DatosConciliacionPreliminar(
                        e.idRecorrido(), UUID.randomUUID(), new BigDecimal("615.00"), "Mixta rollback",
                        List.of(
                                new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                                new PropuestaAplicacion(e.clienteA(), e.pedA2(), new BigDecimal("180.00")),
                                new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("140.00")))));

        var antes = capturarContadores();
        UUID clave = UUID.randomUUID();
        var cmd = new DatosConfirmacionConciliacion(
                e.idRecorrido(), 1, clave, "MIXTA_FALTANTE_Y_CUSTODIA", "Falla inyectada pre-commit",
                new EvidenciaEgresoCustodia("REC-RB-" + e.idRecorrido(), "Repartidor", "Devolucion"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            assertTrue(confirmacion.confirmarConciliacionResuelta(sesionAdmin, cmd).creado());
            throw new IllegalStateException("FALLA_INYECTADA_T1C2");
        }));
        assertEquals("FALLA_INYECTADA_T1C2", ex.getMessage());

        // Reversion integra verificada
        assertEquals("PENDIENTE_CONCILIACION", jdbc.queryForObject(
                "SELECT \"estadoCierre\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                String.class, prelim.idCierreRecorrido()));
        assertEquals("VIGENTE", jdbc.queryForObject(
                "SELECT \"estadoRevision\" FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idRevisionConciliacion\"=?",
                String.class, prelim.idRevisionConciliacion()));
        assertEquals("PENDIENTE_CONCILIACION", jdbc.queryForObject(
                "SELECT \"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=?",
                String.class, e.idRecorrido()));
        assertDec("270.00", jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, e.pedA1()));
        assertEquals(antes, capturarContadores());

        // Reintento posterior con MISMA clave y recibo confirma limpiamente
        RendicionConfirmada ok = confirmacion.confirmarConciliacionResuelta(sesionAdmin, cmd);
        assertTrue(ok.creado());
        assertEquals("CERRADO_CON_DIFERENCIA_RESUELTA", ok.estadoCierre());
        assertEquals("CERRADO", ok.estadoRecorrido());
    }

    @FunctionalInterface
    private interface Tarea {
        Object ejecutar() throws Exception;
    }

    private List<Object> ejecutarEnParalelo(Tarea t1, Tarea t2) throws Exception {
        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> f1 = pool.submit(() -> correrBarrera(listos, largada, t1));
            Future<Object> f2 = pool.submit(() -> correrBarrera(listos, largada, t2));
            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            List<Object> out = new ArrayList<>(2);
            out.add(f1.get(20, TimeUnit.SECONDS));
            out.add(f2.get(20, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object correrBarrera(CountDownLatch listos, CountDownLatch largada, Tarea tarea) {
        listos.countDown();
        try {
            if (!largada.await(5, TimeUnit.SECONDS)) {
                return new IllegalStateException("Timeout barrera");
            }
            return tarea.ejecutar();
        } catch (Throwable t) {
            return t;
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
        return "";
    }
}

