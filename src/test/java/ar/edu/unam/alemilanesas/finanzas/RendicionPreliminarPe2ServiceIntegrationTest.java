package ar.edu.unam.alemilanesas.finanzas;

import ar.edu.unam.alemilanesas.finanzas.RendicionPreliminarPe2Service.CoberturaNoAcreditadaException;
import ar.edu.unam.alemilanesas.finanzas.RendicionPreliminarPe2Service.ConciliacionPreliminarRegistrada;
import ar.edu.unam.alemilanesas.finanzas.RendicionPreliminarPe2Service.DatosConciliacionPreliminar;
import ar.edu.unam.alemilanesas.finanzas.RendicionPreliminarPe2Service.ExposicionRecorrido;
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
 * R09.3b-A — Pruebas reales PostgreSQL 17.11 sobre Flyway V1-V4 para RendicionPreliminarPe2Service:
 * autorizacion, revalidacion post-espera, FA-2, coherencia de importes, cobertura 422, EX-01, IMP-C,
 * prohibicion de 0/0 inicial, idempotencia/replay/conflictos, unicidad e inmutabilidad historica,
 * concurrencia A2(NS_RECORRIDO), rollback atomico y cero efectos financieros definitivos.
 */
@SpringBootTest
@Testcontainers
class RendicionPreliminarPe2ServiceIntegrationTest {
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
    @Autowired RendicionPreliminarPe2Service servicio;

    private record EscenarioRecorrido(
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
            long pedB1) {}

    private record ContadoresFinancieros(
            int cobros,
            int cobrosK2,
            int appsK2,
            int pagosCuenta,
            int caja,
            int eventosImpK2,
            int egresosCustodia) {}

    private ContadoresFinancieros capturarContadoresFinancieros() {
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
     * Crea un Recorrido real con 2 Pedidos (Cliente A: C_e=270.00, Cliente B: C_e=180.00),
     * preparados via UC-28 y entregados via UC-05 con declaraciones dadas.
     */
    private EscenarioRecorrido crearRecorridoConDosEntregas(
            String ref, BigDecimal declaradoA, BigDecimal declaradoB, boolean entregarB) {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        UUID sesionAdmin = f.actores.sesiones.get("ADMINISTRADOR");
        UUID sesionVendedor = f.actores.vendedor();
        UUID sesionRep = f.actores.sesiones.get("REPARTIDOR");
        UUID sesionProd = f.actores.sesiones.get("RESPONSABLE_PRODUCCION");
        long idAdmin = f.actores.base + 1;
        long idRep = f.actores.base + 3;
        long clienteA = f.actores.base;
        long clienteB = f.actores.base + 11;

        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal","estado","categoriaComercial","fechaHoraAlta")
                VALUES (?,?,'3764000399','ACTIVO','CUMPLIDOR',now())
                """, clienteB, "Cliente B PE2 " + clienteB));

        long pedA1 = f.pedido; // 3 entregadas * 100 - 10% = 270.00
        long pedB1 = pedidos.cargarPedido(sesionVendedor, new CargarPedidoService.DatosPedido(
                clienteB, f.actores.hoy.plusDays(1),
                List.of(new CargarPedidoService.ItemPedido(f.producto, new BigDecimal("2.000"))),
                new CargarPedidoService.DireccionEntrega("Av PE2", "500", null, null, "Posadas", "Misiones", null, null),
                UUID.randomUUID())).idPedido(); // 2 * 100 - 10% = 180.00

        long idRec = recorridos.abrirRecorrido(sesionVendedor, idRep, ref).idRecorrido();
        recorridos.asignarYVincularRecorrido(sesionVendedor,
                new AsignarRepartoService.DatosAsignacion(pedA1, idRep, null), idRec);
        recorridos.asignarYVincularRecorrido(sesionVendedor,
                new AsignarRepartoService.DatosAsignacion(pedB1, idRep, null), idRec);

        preparaciones.registrarPreparacionPedido(sesionProd,
                new RegistrarPreparacionService.DatosPreparacion(pedA1, f.producto, new BigDecimal("3.000"), null));
        preparaciones.registrarPreparacionPedido(sesionProd,
                new RegistrarPreparacionService.DatosPreparacion(pedB1, f.producto, new BigDecimal("2.000"), null));

        entregas.registrarEntrega(sesionRep, new RegistrarEntregaService.DatosEntrega(
                pedA1,
                List.of(new RegistrarEntregaService.ItemEntrega(f.producto, new BigDecimal("3.000"))),
                declaradoA,
                UUID.randomUUID()));

        if (entregarB) {
            entregas.registrarEntrega(sesionRep, new RegistrarEntregaService.DatosEntrega(
                    pedB1,
                    List.of(new RegistrarEntregaService.ItemEntrega(f.producto, new BigDecimal("2.000"))),
                    declaradoB,
                    UUID.randomUUID()));
        }

        return new EscenarioRecorrido(
                f, sesionAdmin, sesionVendedor, sesionRep, sesionProd,
                idAdmin, idRep, clienteA, clienteB, idRec, pedA1, pedB1);
    }

    @Test
    void rbacYRevalidacionExclusivoAdministradorHabilitadoInclusoEnReplay() {
        var e = crearRecorridoConDosEntregas("PE2-RBAC", new BigDecimal("270.00"), new BigDecimal("180.00"), true);
        var antes = capturarContadoresFinancieros();
        long idRec = e.idRecorrido();
        UUID sesionAdmin = e.sesionAdmin();
        var cmd = new DatosConciliacionPreliminar(
                idRec, UUID.randomUUID(), new BigDecimal("400.00"), "Faltante de 50 en efectivo",
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("130.00"))));

        // Roles no autorizados y sesiones invalidas -> SecurityException y 0 escrituras
        for (UUID s : List.of(e.sesionVendedor(), e.sesionRepartidor(), e.sesionProduccion(), UUID.randomUUID())) {
            assertThrows(SecurityException.class, () -> servicio.consultarExposicionRecorrido(s, idRec));
            assertThrows(SecurityException.class, () -> servicio.registrarConciliacionPreliminar(s, cmd));
        }
        assertThrows(SecurityException.class, () -> servicio.registrarConciliacionPreliminar(null, cmd));
        assertEquals(0, count("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=" + idRec));
        assertEquals(antes, capturarContadoresFinancieros());

        // Proyeccion contextual BC-07 valida para ADMINISTRADOR
        ExposicionRecorrido exp = servicio.consultarExposicionRecorrido(sesionAdmin, idRec);
        assertEquals(idRec, exp.idRecorrido());
        assertEquals(e.idRepartidor(), exp.idUsuarioRepartidor());
        assertEquals("ABIERTO", exp.estadoOperativo());
        assertEquals(0, exp.pedidosPendientesActivos());
        assertDec("450.00", exp.importeEsperado());
        assertDec("450.00", exp.importeDeclaradoTotal());
        assertDec("0.00", exp.sobranteDeclaradoTotalEx01());
        assertNull(exp.idCierreExistente());
        assertEquals(2, exp.entregas().size());

        // Alta valida por ADMIN habilitado
        ConciliacionPreliminarRegistrada res = servicio.registrarConciliacionPreliminar(sesionAdmin, cmd);
        assertTrue(res.creado());
        assertEquals("PENDIENTE_CONCILIACION", res.estadoCierre());

        // Deshabilitar ADMIN (conservando otro ADMINISTRADOR habilitado por invariante MR5)
        // -> incluso el replay idempotente debe rechazarse (C1-a / RCG-05)
        long adminRespaldo = e.f().actores.base + 54;
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado","cambioCredencialPendiente","fechaHoraAlta")
                    VALUES (?,?,'HASH','HABILITADO',false,clock_timestamp())
                    """, adminRespaldo, "admin_respaldo_pe2_" + adminRespaldo);
            jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'ADMINISTRADOR',clock_timestamp())", adminRespaldo);
            jdbc.update("""
                    UPDATE "USUARIO"
                       SET "estado" = 'DESHABILITADO',
                           "fechaHoraDeshabilitacion" = clock_timestamp()
                     WHERE "idUsuario" = ?
                    """, e.idAdmin());
        });
        assertThrows(SecurityException.class, () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmd));
        assertThrows(SecurityException.class, () -> servicio.consultarExposicionRecorrido(sesionAdmin, idRec));
        assertEquals(antes, capturarContadoresFinancieros());
    }

    @Test
    void fa2YPrecondicionesDeRecorridoVaciosOInconsistentes() {
        // Pedido B sigue PENDIENTE -> FA-2 debe rechazar
        var e = crearRecorridoConDosEntregas("PE2-FA2", new BigDecimal("270.00"), BigDecimal.ZERO, false);
        var antes = capturarContadoresFinancieros();
        UUID sesionAdmin = e.sesionAdmin();

        ExposicionRecorrido exp = servicio.consultarExposicionRecorrido(sesionAdmin, e.idRecorrido());
        assertEquals(1, exp.pedidosPendientesActivos());

        var cmdFa2 = new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("200.00"), "Intento con pendiente",
                List.of(new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("200.00"))));
        IllegalStateException exFa2 = assertThrows(IllegalStateException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdFa2));
        assertEquals("FA2_PEDIDOS_PENDIENTES_ACTIVOS", exFa2.getMessage());
        assertEquals("ABIERTO", jdbc.queryForObject(
                "SELECT \"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=?",
                String.class, e.idRecorrido()));
        assertEquals(0, count("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=" + e.idRecorrido()));

        // Recorrido inexistente -> RECORRIDO_NO_ENCONTRADO
        var cmdNoExiste = new DatosConciliacionPreliminar(
                999_999_999L, UUID.randomUUID(), BigDecimal.ZERO, "No existe", List.of());
        IllegalArgumentException exNoExiste = assertThrows(IllegalArgumentException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdNoExiste));
        assertEquals("RECORRIDO_NO_ENCONTRADO", exNoExiste.getMessage());

        // Recorrido vacio (sin Entregas) -> RECORRIDO_SIN_ENTREGAS
        long repLibre = e.f().actores.base + 55;
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado","cambioCredencialPendiente","fechaHoraAlta")
                    VALUES (?,?,'HASH','HABILITADO',false,clock_timestamp())
                    """, repLibre, "rep_vacio_pe2_" + repLibre);
            jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'REPARTIDOR',clock_timestamp())", repLibre);
        });
        long recVacio = recorridos.abrirRecorrido(e.sesionVendedor(), repLibre, "PE2-VACIO").idRecorrido();
        var cmdVacio = new DatosConciliacionPreliminar(
                recVacio, UUID.randomUUID(), new BigDecimal("10.00"), "Sin entregas", List.of());
        IllegalStateException exVacio = assertThrows(IllegalStateException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdVacio));
        assertEquals("RECORRIDO_SIN_ENTREGAS", exVacio.getMessage());
        assertEquals("ABIERTO", jdbc.queryForObject(
                "SELECT \"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=?",
                String.class, recVacio));
        assertEquals(antes, capturarContadoresFinancieros());
    }

    @Test
    void rechazaDiferenciaInicialCeroCobertura422YPropuestasIncoherentesSinEscribir() {
        var e = crearRecorridoConDosEntregas("PE2-VALID", new BigDecimal("270.00"), new BigDecimal("180.00"), true);
        var eOtro = crearRecorridoConDosEntregas("PE2-OTRO", new BigDecimal("100.00"), new BigDecimal("100.00"), true);
        var antes = capturarContadoresFinancieros();
        UUID sesionAdmin = e.sesionAdmin();

        // 1) Prohibido iniciar PENDIENTE_CONCILIACION con diferencia 0/0 (B1-R1)
        var cmdCero = new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("450.00"), "Intento ilegal 0/0",
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("180.00"))));
        IllegalStateException exCero = assertThrows(IllegalStateException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdCero));
        assertEquals("DIFERENCIA_POSITIVA_REQUERIDA", exCero.getMessage());

        // 2) Cobertura excedida (> V_e^vig) -> CoberturaNoAcreditadaException (422)
        var cmd422 = new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("460.00"), "Excede V_vig",
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("280.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("180.00"))));
        CoberturaNoAcreditadaException ex422 = assertThrows(CoberturaNoAcreditadaException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmd422));
        assertEquals("COBERTURA_NO_ACREDITADA", ex422.getMessage());
        assertEquals(e.pedA1(), ex422.getIdPedido());
        assertDec("280.00", ex422.getSolicitado());
        assertDec("270.00", ex422.getDisponible());

        // 3) Propuesta sobre Pedido de otro Recorrido
        var cmdAjeno = new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("100.00"), "Pedido ajeno",
                List.of(new PropuestaAplicacion(eOtro.clienteA(), eOtro.pedA1(), new BigDecimal("100.00"))));
        IllegalArgumentException exAjeno = assertThrows(IllegalArgumentException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdAjeno));
        assertEquals("ENTREGA_NO_PERTENECE_A_RECORRIDO", exAjeno.getMessage());

        // 4) Cliente no coincide con PE-1
        var cmdCliente = new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("200.00"), "Cliente cruzado",
                List.of(new PropuestaAplicacion(e.clienteB(), e.pedA1(), new BigDecimal("200.00"))));
        IllegalArgumentException exCliente = assertThrows(IllegalArgumentException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdCliente));
        assertEquals("CLIENTE_NO_COINCIDE_CON_ENTREGA", exCliente.getMessage());

        // 5) Suma propuesta > efectivo recibido
        var cmdExcedeEf = new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("200.00"), "Excede efectivo",
                List.of(new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("250.00"))));
        IllegalArgumentException exExcedeEf = assertThrows(IllegalArgumentException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdExcedeEf));
        assertEquals("IMPUTACION_EXCEDE_EFECTIVO_RECIBIDO", exExcedeEf.getMessage());

        // 6) Pedido duplicado en propuestas
        var cmdDup = new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("200.00"), "Duplicado",
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("100.00")),
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("50.00"))));
        IllegalArgumentException exDup = assertThrows(IllegalArgumentException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdDup));
        assertEquals("PROPUESTA_PEDIDO_DUPLICADO", exDup.getMessage());

        // 7) Entradas sintacticamente invalidas (motivo blanco, negativo, >2 decimales)
        var cmdBlanco = new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("100.00"), "   ", List.of());
        var cmdNeg = new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("-1.00"), "Negativo", List.of());
        var cmdEscala = new DatosConciliacionPreliminar(
                e.idRecorrido(), UUID.randomUUID(), new BigDecimal("100.005"), "Tres decimales", List.of());
        assertThrows(IllegalArgumentException.class, () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdBlanco));
        assertThrows(IllegalArgumentException.class, () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdNeg));
        assertThrows(IllegalArgumentException.class, () -> servicio.registrarConciliacionPreliminar(sesionAdmin, cmdEscala));

        assertEquals("ABIERTO", jdbc.queryForObject(
                "SELECT \"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=?",
                String.class, e.idRecorrido()));
        assertEquals(0, count("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=" + e.idRecorrido()));
        assertEquals(antes, capturarContadoresFinancieros());
    }

    @Test
    void registraMixtoFaltanteYCustodiaConCeroEfectosFinancieros() {
        var e1 = crearRecorridoConDosEntregas("PE2-MIXTO", new BigDecimal("270.00"), new BigDecimal("180.00"), true);
        var finAntes1 = capturarContadoresFinancieros();

        ConciliacionPreliminarRegistrada r1 = servicio.registrarConciliacionPreliminar(e1.sesionAdmin(),
                new DatosConciliacionPreliminar(
                        e1.idRecorrido(), UUID.randomUUID(), new BigDecimal("425.00"),
                        "  Faltante parcial   y billetes sin identificar ", "CAJA_F_ADMIN",
                        List.of(
                                new PropuestaAplicacion(e1.clienteB(), e1.pedB1(), new BigDecimal("140.00")),
                                new PropuestaAplicacion(e1.clienteA(), e1.pedA1(), new BigDecimal("270.00")))));

        assertTrue(r1.creado());
        assertEquals(1, r1.numeroRevision());
        assertEquals("PENDIENTE_CONCILIACION", r1.estadoCierre());
        assertEquals("VIGENTE", r1.estadoRevision());
        assertEquals("PENDIENTE_CONCILIACION", r1.estadoRecorrido());
        assertEquals("v1", r1.versionHuella());
        assertEquals(64, r1.huellaCanonica().length());
        assertDec("450.00", r1.importeEsperado());
        assertDec("425.00", r1.importeEfectivoRecibido());
        assertDec("410.00", r1.importeImputadoPropuesto());
        assertDec("0.00", r1.importeImputadoConfirmado());
        assertDec("40.00", r1.importeDiferenciaFaltante());
        assertDec("15.00", r1.importeCustodiaNoImputada());
        assertEquals("Faltante parcial y billetes sin identificar", r1.motivoDiferencia());
        assertEquals(2, r1.propuestas().size());
        assertEquals(2, r1.eventosCustodia().size());

        var cab = jdbc.queryForMap("SELECT * FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?",
                r1.idCierreRecorrido());
        assertEquals(r1.idRevisionConciliacion(), ((Number) cab.get("idRevisionVigente")).longValue());
        assertNull(cab.get("claveIdempotenciaCierre"));
        assertNull(cab.get("reciboHistoricoJson"));
        assertDec("270.00", jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, e1.pedA1()));
        assertDec("180.00", jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, e1.pedB1()));
        assertEquals(finAntes1, capturarContadoresFinancieros());
    }

    @Test
    void registraFaltanteTotalConPropuestasVaciasYSobranteEx01ConCustodia() {
        // Faltante total con efectivo recibido 0 y propuestas vacias
        var e2 = crearRecorridoConDosEntregas("PE2-TOTAL-FALT", new BigDecimal("270.00"), new BigDecimal("180.00"), true);
        var finAntes2 = capturarContadoresFinancieros();
        ConciliacionPreliminarRegistrada r2 = servicio.registrarConciliacionPreliminar(e2.sesionAdmin(),
                new DatosConciliacionPreliminar(
                        e2.idRecorrido(), UUID.randomUUID(), BigDecimal.ZERO,
                        "Repartidor no entrego efectivo", List.of()));
        assertDec("450.00", r2.importeEsperado());
        assertDec("0.00", r2.importeImputadoPropuesto());
        assertDec("450.00", r2.importeDiferenciaFaltante());
        assertDec("0.00", r2.importeCustodiaNoImputada());
        assertTrue(r2.propuestas().isEmpty());
        assertEquals(1, r2.eventosCustodia().size());
        assertEquals("FALTANTE_EFECTIVO", r2.eventosCustodia().getFirst().tipoEvento());
        assertEquals(finAntes2, capturarContadoresFinancieros());

        // Sobrante declarado EX-01 (D_e=300 sobre E_e=270 => V_0=270, EX-01=30) + custodia pura 30
        var e3 = crearRecorridoConDosEntregas("PE2-EX01", new BigDecimal("300.00"), new BigDecimal("180.00"), true);
        var finAntes3 = capturarContadoresFinancieros();
        ExposicionRecorrido exp3 = servicio.consultarExposicionRecorrido(e3.sesionAdmin(), e3.idRecorrido());
        assertDec("450.00", exp3.importeEsperado());
        assertDec("480.00", exp3.importeDeclaradoTotal());
        assertDec("30.00", exp3.sobranteDeclaradoTotalEx01());

        ConciliacionPreliminarRegistrada r3 = servicio.registrarConciliacionPreliminar(e3.sesionAdmin(),
                new DatosConciliacionPreliminar(
                        e3.idRecorrido(), UUID.randomUUID(), new BigDecimal("480.00"),
                        "Sobrante declarado en entrega A bajo custodia",
                        List.of(
                                new PropuestaAplicacion(e3.clienteA(), e3.pedA1(), new BigDecimal("270.00")),
                                new PropuestaAplicacion(e3.clienteB(), e3.pedB1(), new BigDecimal("180.00")))));
        assertDec("0.00", r3.importeDiferenciaFaltante());
        assertDec("30.00", r3.importeCustodiaNoImputada());
        assertEquals(2, r3.eventosCustodia().size());
        assertTrue(r3.eventosCustodia().stream().anyMatch(ev -> "CUSTODIA_NO_IMPUTADA".equals(ev.tipoEvento())
                && new BigDecimal("30.00").compareTo(ev.importe()) == 0));
        assertTrue(r3.eventosCustodia().stream().anyMatch(ev -> "SOBRANTE_DECLARADO_EX01".equals(ev.tipoEvento())
                && Long.valueOf(e3.pedA1()).equals(ev.idPedido())
                && Long.valueOf(e3.clienteA()).equals(ev.idCliente())
                && new BigDecimal("30.00").compareTo(ev.importe()) == 0));
        assertEquals(finAntes3, capturarContadoresFinancieros());
    }

    @Test
    void casoContencionImpCEfectivoVaACustodiaSinImputacionYConCeroEfectos() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        UUID sesionAdmin = f.actores.sesiones.get("ADMINISTRADOR");
        UUID sesionVendedor = f.actores.vendedor();
        UUID sesionRep = f.actores.sesiones.get("REPARTIDOR");
        UUID sesionProd = f.actores.sesiones.get("RESPONSABLE_PRODUCCION");
        long idAdmin = f.actores.base + 1;
        long idRep = f.actores.base + 3;
        long clienteA = f.actores.base;

        // Inducir senal A de RCG-06 (cobro confirmado con idPedido pendiente sin IMP-B) -> UC-05 registra PE-1 en IMP_C (V0=0)
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "COBRO" ("idCobro","idCliente","idPedido","fechaHoraCobro","importe",
                    "medioPago","origen","estadoCobro","referenciaExterna","idUsuarioRegistrador")
                VALUES (nextval('ale_gestion."seq_gfis_cobro_id"'::regclass),?,?,clock_timestamp(),50.00,
                    'EFECTIVO','ADMINISTRACION','CONFIRMADO',?,?)
                """, clienteA, f.pedido, "LEGACY-IMPC-PE2-" + f.pedido, idAdmin));

        long idRec = recorridos.abrirRecorrido(sesionVendedor, idRep, "PE2-IMPC-OK").idRecorrido();
        recorridos.asignarYVincularRecorrido(sesionVendedor,
                new AsignarRepartoService.DatosAsignacion(f.pedido, idRep, null), idRec);
        preparaciones.registrarPreparacionPedido(sesionProd,
                new RegistrarPreparacionService.DatosPreparacion(f.pedido, f.producto, new BigDecimal("3.000"), null));
        var ent = entregas.registrarEntrega(sesionRep, new RegistrarEntregaService.DatosEntrega(
                f.pedido,
                List.of(new RegistrarEntregaService.ItemEntrega(f.producto, new BigDecimal("3.000"))),
                new BigDecimal("60.00"),
                UUID.randomUUID()));
        assertEquals("IMP_C", ent.modoEvidencia());
        assertDec("0.00", ent.reconocimientoInicialV0());

        var finAntes = capturarContadoresFinancieros();
        ConciliacionPreliminarRegistrada res = servicio.registrarConciliacionPreliminar(sesionAdmin,
                new DatosConciliacionPreliminar(
                        idRec, UUID.randomUUID(), new BigDecimal("60.00"),
                        "Efectivo en custodia bajo contencion IMP_C", List.of()));
        assertTrue(res.creado());
        assertDec("0.00", res.importeEsperado());
        assertDec("60.00", res.importeDeclaradoTotal());
        assertDec("60.00", res.importeEfectivoRecibido());
        assertDec("0.00", res.importeImputadoPropuesto());
        assertDec("0.00", res.importeDiferenciaFaltante());
        assertDec("60.00", res.importeCustodiaNoImputada());
        assertEquals(1, res.eventosCustodia().size());
        assertEquals("CUSTODIA_NO_IMPUTADA", res.eventosCustodia().getFirst().tipoEvento());
        assertEquals(finAntes, capturarContadoresFinancieros());
    }

    @Test
    void idempotenciaReplayConflictosUnicidadEInmutabilidadHistorica() {
        var e1 = crearRecorridoConDosEntregas("PE2-IDEMP-1", new BigDecimal("270.00"), new BigDecimal("180.00"), true);
        var e2 = crearRecorridoConDosEntregas("PE2-IDEMP-2", new BigDecimal("270.00"), new BigDecimal("180.00"), true);
        UUID clave = UUID.randomUUID();
        UUID sesionAdmin1 = e1.sesionAdmin();
        UUID sesionAdmin2 = e2.sesionAdmin();
        var finAntes = capturarContadoresFinancieros();

        ConciliacionPreliminarRegistrada primera = servicio.registrarConciliacionPreliminar(sesionAdmin1,
                new DatosConciliacionPreliminar(
                        e1.idRecorrido(), clave, new BigDecimal("400.00"), "Diferencia de 50 pesos",
                        List.of(
                                new PropuestaAplicacion(e1.clienteA(), e1.pedA1(), new BigDecimal("270.00")),
                                new PropuestaAplicacion(e1.clienteB(), e1.pedB1(), new BigDecimal("130.00")))));
        assertTrue(primera.creado());

        int cierresAntes = count("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\"");
        int revsAntes = count("SELECT count(*) FROM \"REVISION_CONCILIACION_PE2\"");
        int propsAntes = count("SELECT count(*) FROM \"PROPUESTA_APLICACION_PENDIENTE\"");
        int custAntes = count("SELECT count(*) FROM \"CUSTODIA_DIFERENCIA_RENDICION\"");

        // 1) Replay semanticamente identico (orden inverso de propuestas, decimales 400.0 y espacios extra en motivo)
        ConciliacionPreliminarRegistrada replay = servicio.registrarConciliacionPreliminar(sesionAdmin1,
                new DatosConciliacionPreliminar(
                        e1.idRecorrido(), clave, new BigDecimal("400.0"), "  Diferencia   de 50 pesos  ",
                        List.of(
                                new PropuestaAplicacion(e1.clienteB(), e1.pedB1(), new BigDecimal("130.0")),
                                new PropuestaAplicacion(e1.clienteA(), e1.pedA1(), new BigDecimal("270")))));
        assertFalse(replay.creado());
        assertEquals(primera.idCierreRecorrido(), replay.idCierreRecorrido());
        assertEquals(primera.idRevisionConciliacion(), replay.idRevisionConciliacion());
        assertEquals(primera.huellaCanonica(), replay.huellaCanonica());
        assertEquals(primera.fechaHoraRegistro().toInstant(), replay.fechaHoraRegistro().toInstant());
        assertEquals(primera.propuestas(), replay.propuestas());
        assertEquals(primera.eventosCustodia(), replay.eventosCustodia());

        // 2) Misma clave con payload distinto -> CLAVE_IDEMPOTENCIA_CONFLICTO_PAYLOAD
        var cmdPayloadDistinto = new DatosConciliacionPreliminar(
                e1.idRecorrido(), clave, new BigDecimal("390.00"), "Diferencia de 50 pesos",
                List.of(
                        new PropuestaAplicacion(e1.clienteA(), e1.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e1.clienteB(), e1.pedB1(), new BigDecimal("120.00"))));
        IllegalStateException exPayload = assertThrows(IllegalStateException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin1, cmdPayloadDistinto));
        assertEquals("CLAVE_IDEMPOTENCIA_CONFLICTO_PAYLOAD", exPayload.getMessage());

        // 3) Misma clave sobre otro Recorrido -> CLAVE_IDEMPOTENCIA_CONFLICTO_AMBITO
        var cmdAmbito = new DatosConciliacionPreliminar(
                e2.idRecorrido(), clave, new BigDecimal("400.00"), "Diferencia de 50 pesos",
                List.of(
                        new PropuestaAplicacion(e2.clienteA(), e2.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e2.clienteB(), e2.pedB1(), new BigDecimal("130.00"))));
        IllegalStateException exAmbito = assertThrows(IllegalStateException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin2, cmdAmbito));
        assertEquals("CLAVE_IDEMPOTENCIA_CONFLICTO_AMBITO", exAmbito.getMessage());

        // 4) Nueva clave sobre el mismo Recorrido -> CONCILIACION_YA_EXISTENTE (unicidad por Recorrido)
        var cmdDuplicado = new DatosConciliacionPreliminar(
                e1.idRecorrido(), UUID.randomUUID(), new BigDecimal("400.00"), "Otra clave",
                List.of(
                        new PropuestaAplicacion(e1.clienteA(), e1.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e1.clienteB(), e1.pedB1(), new BigDecimal("130.00"))));
        IllegalStateException exDup = assertThrows(IllegalStateException.class,
                () -> servicio.registrarConciliacionPreliminar(sesionAdmin1, cmdDuplicado));
        assertEquals("CONCILIACION_YA_EXISTENTE", exDup.getMessage());

        // 5) Inmutabilidad historica respaldada por triggers V4
        long idRev = primera.idRevisionConciliacion();
        assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM \"REVISION_CONCILIACION_PE2\" WHERE \"idRevisionConciliacion\"=?", idRev));
        assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM \"PROPUESTA_APLICACION_PENDIENTE\" WHERE \"idRevisionConciliacion\"=?", idRev));
        assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM \"CUSTODIA_DIFERENCIA_RENDICION\" WHERE \"idRevisionConciliacion\"=?", idRev));

        assertEquals(cierresAntes, count("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\""));
        assertEquals(revsAntes, count("SELECT count(*) FROM \"REVISION_CONCILIACION_PE2\""));
        assertEquals(propsAntes, count("SELECT count(*) FROM \"PROPUESTA_APLICACION_PENDIENTE\""));
        assertEquals(custAntes, count("SELECT count(*) FROM \"CUSTODIA_DIFERENCIA_RENDICION\""));
        assertEquals(finAntes, capturarContadoresFinancieros());
    }

    @Test
    void concurrenciaA2PorRecorridoMismaClaveClavesDistintasEIntercaladoUc05SinDeadlock() throws Exception {
        // 1) Carrera misma clave: 2 hilos concurrentes -> 1 creado=true, 1 replay creado=false, 0 errores
        var e1 = crearRecorridoConDosEntregas("PE2-CONC-SAME", new BigDecimal("270.00"), new BigDecimal("180.00"), true);
        UUID claveCompartida = UUID.randomUUID();
        var cmdMisma = new DatosConciliacionPreliminar(
                e1.idRecorrido(), claveCompartida, new BigDecimal("410.00"), "Carrera misma clave",
                List.of(
                        new PropuestaAplicacion(e1.clienteA(), e1.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e1.clienteB(), e1.pedB1(), new BigDecimal("140.00"))));

        List<Object> resMisma = ejecutarEnParalelo(
                () -> servicio.registrarConciliacionPreliminar(e1.sesionAdmin(), cmdMisma),
                () -> servicio.registrarConciliacionPreliminar(e1.sesionAdmin(), cmdMisma));

        int creadosMisma = 0;
        int replaysMisma = 0;
        long idCierreUnico = -1;
        for (Object r : resMisma) {
            if (r instanceof ConciliacionPreliminarRegistrada c) {
                if (c.creado()) creadosMisma++; else replaysMisma++;
                if (idCierreUnico == -1) idCierreUnico = c.idCierreRecorrido();
                else assertEquals(idCierreUnico, c.idCierreRecorrido());
            } else {
                fail("Error inesperado en carrera misma clave: " + r);
            }
        }
        assertEquals(1, creadosMisma);
        assertEquals(1, replaysMisma);
        assertEquals(1, count("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=" + e1.idRecorrido()));

        // 2) Carrera claves distintas: 1 creado=true y 1 CONCILIACION_YA_EXISTENTE, 0 deadlocks
        var e2 = crearRecorridoConDosEntregas("PE2-CONC-DIFF", new BigDecimal("270.00"), new BigDecimal("180.00"), true);
        List<Object> resDiff = ejecutarEnParalelo(
                () -> servicio.registrarConciliacionPreliminar(e2.sesionAdmin(), new DatosConciliacionPreliminar(
                        e2.idRecorrido(), UUID.randomUUID(), new BigDecimal("400.00"), "Hilo A",
                        List.of(new PropuestaAplicacion(e2.clienteA(), e2.pedA1(), new BigDecimal("270.00"))))),
                () -> servicio.registrarConciliacionPreliminar(e2.sesionAdmin(), new DatosConciliacionPreliminar(
                        e2.idRecorrido(), UUID.randomUUID(), new BigDecimal("350.00"), "Hilo B",
                        List.of(new PropuestaAplicacion(e2.clienteA(), e2.pedA1(), new BigDecimal("250.00"))))));

        int okDiff = 0;
        int conflictoDiff = 0;
        for (Object r : resDiff) {
            if (r instanceof ConciliacionPreliminarRegistrada c && c.creado()) {
                okDiff++;
            } else if (r instanceof Throwable t) {
                assertNotEquals("40P01", extraerSqlState(t), "No debe ocurrir deadlock 40P01");
                assertEquals("CONCILIACION_YA_EXISTENTE", t.getMessage());
                conflictoDiff++;
            } else {
                fail("Resultado inesperado: " + r);
            }
        }
        assertEquals(1, okDiff);
        assertEquals(1, conflictoDiff);
        assertEquals(1, count("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=" + e2.idRecorrido()));
        assertEquals(1, count("SELECT count(*) FROM \"REVISION_CONCILIACION_PE2\" r JOIN \"CIERRE_RECORRIDO_PE2\" c ON c.\"idCierreRecorrido\"=r.\"idCierreRecorrido\" WHERE c.\"idRecorrido\"=" + e2.idRecorrido()));

        // 3) Intercalado concurrente UC-05 registrarEntrega (pedB1) vs registrarConciliacionPreliminar sobre mismo Recorrido
        var e3 = crearRecorridoConDosEntregas("PE2-CONC-UC05", new BigDecimal("270.00"), BigDecimal.ZERO, false);
        List<Object> resInter = ejecutarEnParalelo(
                () -> entregas.registrarEntrega(e3.sesionRepartidor(), new RegistrarEntregaService.DatosEntrega(
                        e3.pedB1(),
                        List.of(new RegistrarEntregaService.ItemEntrega(e3.f().producto, new BigDecimal("2.000"))),
                        new BigDecimal("180.00"),
                        UUID.randomUUID())),
                () -> servicio.registrarConciliacionPreliminar(e3.sesionAdmin(), new DatosConciliacionPreliminar(
                        e3.idRecorrido(), UUID.randomUUID(), new BigDecimal("270.00"), "Conciliacion concurrente UC-05",
                        List.of(new PropuestaAplicacion(e3.clienteA(), e3.pedA1(), new BigDecimal("270.00"))))));

        assertInstanceOf(RegistrarEntregaService.EntregaRegistrada.class, resInter.get(0));
        Object rPe2 = resInter.get(1);
        if (rPe2 instanceof Throwable t) {
            assertNotEquals("40P01", extraerSqlState(t), "Intercalado UC-05/PE-2 sin deadlock 40P01");
            assertEquals("FA2_PEDIDOS_PENDIENTES_ACTIVOS", t.getMessage());
        } else {
            assertInstanceOf(ConciliacionPreliminarRegistrada.class, rPe2);
            assertDec("180.00", ((ConciliacionPreliminarRegistrada) rPe2).importeDiferenciaFaltante());
        }
    }

    @Test
    void rollbackAtomicoAnteFallaPreCommitRevierteTodoYNoConsumeClave() {
        var e = crearRecorridoConDosEntregas("PE2-ROLLBACK", new BigDecimal("270.00"), new BigDecimal("180.00"), true);
        var finAntes = capturarContadoresFinancieros();
        UUID clave = UUID.randomUUID();
        var cmd = new DatosConciliacionPreliminar(
                e.idRecorrido(), clave, new BigDecimal("400.00"), "Falla pre-commit",
                List.of(
                        new PropuestaAplicacion(e.clienteA(), e.pedA1(), new BigDecimal("270.00")),
                        new PropuestaAplicacion(e.clienteB(), e.pedB1(), new BigDecimal("130.00"))));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            ConciliacionPreliminarRegistrada parcial = servicio.registrarConciliacionPreliminar(e.sesionAdmin(), cmd);
            assertTrue(parcial.creado());
            throw new IllegalStateException("FALLA_INYECTADA_PRE_COMMIT");
        }));
        assertEquals("FALLA_INYECTADA_PRE_COMMIT", ex.getMessage());

        // Cero residuos tras rollback y Recorrido intacto en ABIERTO
        assertEquals("ABIERTO", jdbc.queryForObject(
                "SELECT \"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=?",
                String.class, e.idRecorrido()));
        assertEquals(0, count("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=" + e.idRecorrido()));
        assertEquals(0, count("SELECT count(*) FROM \"REVISION_CONCILIACION_PE2\" WHERE \"claveIdempotenciaRevision\"='" + clave + "'"));
        assertEquals(finAntes, capturarContadoresFinancieros());

        // Reintento posterior con la MISMA clave debe crear normalmente (clave no consumida)
        ConciliacionPreliminarRegistrada ok = servicio.registrarConciliacionPreliminar(e.sesionAdmin(), cmd);
        assertTrue(ok.creado());
        assertEquals("PENDIENTE_CONCILIACION", ok.estadoCierre());
        assertEquals(finAntes, capturarContadoresFinancieros());
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
