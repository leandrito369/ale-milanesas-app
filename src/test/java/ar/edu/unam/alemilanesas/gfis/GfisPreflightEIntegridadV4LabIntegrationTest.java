package ar.edu.unam.alemilanesas.gfis;

import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import ar.edu.unam.alemilanesas.ventas.AsignacionRepartoFixture;
import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
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
 * G-FIS (Fase A + Fase B — Laboratorio aislado):
 * Verifica preflight V1-V3/RCG-06, PE-1, IMP-01 (IMP-B/IMP-A/IMP-C),
 * separacion estricta PENDIENTE_CONCILIACION (0 efectos financieros) vs
 * confirmacion atomica T1-C2 + K2 (G-ARQ-N2-01 s8), egreso auditable de
 * custodia con recibo (E-04), integridad de Recorrido (FA-1/FA-2 y exigencia
 * de pertenencia ACTIVA en ENTREGA), D1, D7-A, DC05-07-A y RBAC ADMINISTRADOR.
 *
 * NO promueve V4 a src/main/resources/db/migration/ ni modifica src/main/**.
 */
@SpringBootTest
@Testcontainers
class GfisPreflightEIntegridadV4LabIntegrationTest {
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
    @Autowired AsignarRepartoService asignaciones;
    @Autowired CobroManualService cobros;

    @Test
    void preflightVerificaV1V3IntactasSinBackfillHeuristicoDeCobroIdPedido() {
        List<String> versionesFlyway = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL AND success = true ORDER BY installed_rank",
                String.class);
        assertEquals(List.of("1", "2", "3", "4"), versionesFlyway,
                "Flyway de aplicacion debe ejecutar V1-V4 en orden");

        Integer nuevasTablas = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = current_schema()
                   AND table_name IN (
                       'RECONOCIMIENTO_ENTREGA_PE1','RECORRIDO_REPARTO','RECORRIDO_ASIGNACION',
                       'RECORRIDO_ENTREGA','CIERRE_RECORRIDO_PE2','REVISION_CONCILIACION_PE2',
                       'PROPUESTA_APLICACION_PENDIENTE','COBRO_RENDICION_K2','APLICACION_RENDICION_K2',
                       'CUSTODIA_DIFERENCIA_RENDICION','EGRESO_CUSTODIA_RENDICION',
                       'EVENTO_IMPUTACION_FINANCIERA','ANULACION_COBRO_IDEMPOTENCIA')
                """, Integer.class);
        assertEquals(13, nuevasTablas, "Las 13 tablas candidatas de laboratorio deben existir");

        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idCliente = f.base;
        var cobro = cobros.registrarCobro(
                f.sesiones.get("ADMINISTRADOR"), idCliente, null,
                new BigDecimal("150.00"), "EFECTIVO", UUID.randomUUID());
        Long idPedidoCobro = jdbc.queryForObject(
                "SELECT \"idPedido\" FROM \"COBRO\" WHERE \"idCobro\"=?", Long.class, cobro.idCobro());
        assertNull(idPedidoCobro, "RCG-06: prohibido backfill heuristico de COBRO.idPedido");
    }

    @Test
    void pe1FormulaR084Ex01ImpCYReembolsoRechazadoVsPendienteEnImpB() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idVendedor = f.base;
        long idAdmin = f.base + 1;
        long idRepartidor = f.base + 3;
        long idCliente = f.base;

        long p1 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long p2 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long p3 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRepartidor, "REC-PE1");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, p1, idRepartidor, idVendedor, recorrido);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, p2, idRepartidor, idVendedor, recorrido);

        // 1) Cobro anticipo confirmado de 1000.00 con REEMBOLSO RECHAZADO (RCG-01: NO veta disponibilidad)
        var cobro1 = cobros.registrarCobro(
                f.sesiones.get("ADMINISTRADOR"), idCliente, null,
                new BigDecimal("1000.00"), "EFECTIVO", UUID.randomUUID());
        jdbc.update("""
                INSERT INTO "REEMBOLSO" ("idReembolso","idCobro","importeReembolso",
                    "fechaHoraCreacion","motivo","estado")
                VALUES (?,?,?,clock_timestamp(),'Rechazado por pasarela','RECHAZADO')
                """, f.base + 901, cobro1.idCobro(), new BigDecimal("1000.00"));

        BigDecimal libreConRechazado = jdbc.queryForObject(
                "SELECT \"importeLibreDisponible\" FROM \"VW_IMP_A_DISPONIBILIDAD_COBRO\" WHERE \"idCobro\"=?",
                BigDecimal.class, cobro1.idCobro());
        assertEquals(0, new BigDecimal("1000.00").compareTo(libreConRechazado),
                "RCG-01: REEMBOLSO RECHAZADO no debe vetar disponibilidad");

        // Reserva 800 -> Consolida 500 -> Libera 300
        jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                VALUES (?,?,?,'RESERVA_ANTICIPO',800.00,?,clock_timestamp())
                """, idCliente, cobro1.idCobro(), p1, idAdmin);
        jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                VALUES (?,?,?,'CONSOLIDACION_ANTICIPO',500.00,?,clock_timestamp())
                """, idCliente, cobro1.idCobro(), p1, idAdmin);
        jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                VALUES (?,?,?,'LIBERACION_ANTICIPO',300.00,?,clock_timestamp())
                """, idCliente, cobro1.idCobro(), p1, idAdmin);

        BigDecimal libreTrasConsolidar = jdbc.queryForObject(
                "SELECT \"importeLibreDisponible\" FROM \"VW_IMP_A_DISPONIBILIDAD_COBRO\" WHERE \"idCobro\"=?",
                BigDecimal.class, cobro1.idCobro());
        assertEquals(0, new BigDecimal("500.00").compareTo(libreTrasConsolidar));

        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                VALUES (?,?,?,'APLICACION_FIFO_LIBRE',500.01,?,clock_timestamp())
                """, idCliente, cobro1.idCobro(), p2, idAdmin),
                "IMP-B debe impedir sobreuso de Cobro");

        // 2) Cobro con REEMBOLSO PENDIENTE si descuenta disponibilidad
        var cobro2 = cobros.registrarCobro(
                f.sesiones.get("ADMINISTRADOR"), idCliente, null,
                new BigDecimal("300.00"), "EFECTIVO", UUID.randomUUID());
        jdbc.update("""
                INSERT INTO "REEMBOLSO" ("idReembolso","idCobro","importeReembolso",
                    "fechaHoraCreacion","motivo","estado")
                VALUES (?,?,?,clock_timestamp(),'Reembolso pendiente','PENDIENTE')
                """, f.base + 902, cobro2.idCobro(), new BigDecimal("300.00"));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                VALUES (?,?,?,'RESERVA_ANTICIPO',10.00,?,clock_timestamp())
                """, idCliente, cobro2.idCobro(), p2, idAdmin),
                "REEMBOLSO PENDIENTE debe bloquear reserva sobre el monto inmovilizado");

        // 3) EX-01: C_e=500, P_e=500 -> E_e=0, D_e=120 -> conserva D_e=120 integro y V_e^0=0
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(
                jdbc, p1, idCliente, idRepartidor, recorrido,
                new BigDecimal("500.00"), new BigDecimal("500.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("120.00"), "IMP_B");
        var pe1Ex01 = jdbc.queryForMap(
                "SELECT \"declaradoRepartidorDe\",\"exigibleEe\",\"reconocimientoInicialV0\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                p1);
        assertEquals(0, new BigDecimal("120.00").compareTo((BigDecimal) pe1Ex01.get("declaradoRepartidorDe")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) pe1Ex01.get("exigibleEe")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) pe1Ex01.get("reconocimientoInicialV0")));

        // 4) IMP-C: modo contencion impide inventar V_e^0 > 0
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(
                jdbc, p2, idCliente, idRepartidor, recorrido,
                new BigDecimal("400.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("400.00"), "IMP_C");
        BigDecimal v0ImpC = jdbc.queryForObject(
                "SELECT \"reconocimientoInicialV0\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, p2);
        assertEquals(0, BigDecimal.ZERO.compareTo(v0ImpC), "IMP-C no puede inventar V_e^0 > 0");

        jdbc.update("UPDATE \"PEDIDO\" SET \"estado\"='ENTREGADO' WHERE \"idPedido\"=?", p3);
        jdbc.update("INSERT INTO \"ENTREGA\" VALUES (?,clock_timestamp(),?)", p3, idRepartidor);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "RECONOCIMIENTO_ENTREGA_PE1" (
                    "idPedido","idCliente","claveIdempotenciaEntrega",
                    "cargoRealCe","anticipoExplicitoPe","fifoVinculanteFe",
                    "coberturaTotalKe","exigibleEe","declaradoRepartidorDe",
                    "reconocimientoInicialV0","reconocimientoVigenteVvig",
                    "modoEvidencia","estadoContencionUc07","fechaHoraRegistro")
                VALUES (?,?,?,400,0,0,0,400,400,400,400,'IMP_C','SOLO_ORIGINALES',clock_timestamp())
                """, p3, idCliente, UUID.randomUUID()));

        // 5) Inmutabilidad append-only de IMP-B
        assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM \"EVENTO_IMPUTACION_FINANCIERA\" WHERE \"idCobro\"=?", cobro1.idCobro()));
    }

    /**
     * Bloqueante 1 (T1-C2 / G-ARQ-N2-01 s8) + Bloqueante 4 (E-04 custodia con recibo y egreso):
     * Reproduce el escenario exacto de G-ARQ-N2-01 s8:
     *   A1 (V_0=70), A2 (V_0=50), B1 (V_0=60) => Sum V_0 = 180.00
     *   Efectivo recibido = 180.00 (170.00 propuesto [A1=70, A2=40, B1=60] + 10.00 custodia)
     *   Faltante en A2 = 10.00.
     * 1) Verifica que PENDIENTE_CONCILIACION produce 0 Cobro, 0 PAGO, 0 Caja, 0 extincion de V_e
     *    y 0 recibo definitivo T1, y que PostgreSQL rechaza cualquier COBRO_RENDICION_K2 en PENDIENTE.
     * 2) Verifica que intentar resolver MIXTA_FALTANTE_Y_CUSTODIA sin EGRESO_CUSTODIA_RENDICION
     *    con recibo falla en COMMIT (E04-CUSTODIA) y revierte al estado PENDIENTE_CONCILIACION.
     * 3) Verifica la confirmacion posterior en UN UNICO COMMIT T1-C2 con EGRESO_CUSTODIA_RENDICION,
     *    emitiendo Cobro A=110, Cobro B=60, Caja=170 (nunca 180), V_{A2}^{vig}=10 intacto,
     *    recibo historico inmutable, replay C1-a, D7-A y rechazo de 2do cierre (23505).
     */
    @Test
    void pendienteConciliacionNoGeneraEfectosFinancierosYConfirmacionT1C2ConCustodiaE04EsAtomica() {
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
                new CargarPedidoService.DireccionEntrega("Calle B", "456", null, null, "Posadas", "Misiones", null, null),
                UUID.randomUUID())).idPedido();

        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRepartidor, "REC-T1C2-S8");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedA1, idRepartidor, idVendedor, recorrido);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedA2, idRepartidor, idVendedor, recorrido);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedB1, idRepartidor, idVendedor, recorrido);

        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA1, clienteA, idRepartidor, recorrido,
                new BigDecimal("120.00"), new BigDecimal("30.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("70.00"), "IMP_B"); // E=90, D=70 -> V_0=70
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA2, clienteA, idRepartidor, recorrido,
                new BigDecimal("80.00"), new BigDecimal("15.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("50.00"), "IMP_B"); // E=65, D=50 -> V_0=50
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedB1, clienteB, idRepartidor, recorrido,
                new BigDecimal("90.00"), new BigDecimal("30.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("70.00"), "IMP_B"); // E=60, D=70 -> V_0=60

        int cobrosAntes = jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class);
        int cuentaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CUENTA\"", Integer.class);
        int cajaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class);

        // 1) Registrar PENDIENTE_CONCILIACION con efectivo parcialmente atribuible (170 propuesto + 10 custodia + 10 faltante)
        var pend = tx.execute(status -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, recorrido, idAdmin, UUID.randomUUID(),
                new BigDecimal("180.00"), new BigDecimal("180.00"),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                "Faltante 10 en A2 y custodia no imputada de 10",
                List.of(
                        new GfisLabSupport.AplicacionPlan(clienteA, pedA1, new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(clienteA, pedA2, new BigDecimal("40.00")),
                        new GfisLabSupport.AplicacionPlan(clienteB, pedB1, new BigDecimal("60.00")))));
        assertNotNull(pend);
        assertEquals("PENDIENTE_CONCILIACION", pend.estadoCierre());
        assertNull(pend.reciboJson(), "PENDIENTE_CONCILIACION no debe emitir recibo definitivo T1");

        // Prueba NEGATIVA Bloqueante 1: 0 Cobro, 0 PAGO, 0 Caja, 0 aplicaciones definitivas, V_e^{vig} intactos
        assertEquals(cobrosAntes, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class),
                "T1-C2: PENDIENTE_CONCILIACION no debe crear ningun COBRO");
        assertEquals(cuentaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CUENTA\"", Integer.class),
                "T1-C2: PENDIENTE_CONCILIACION no debe crear ningun MOVIMIENTO_CUENTA");
        assertEquals(cajaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class),
                "T1-C2: PENDIENTE_CONCILIACION no debe crear ningun MOVIMIENTO_CAJA");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?", Integer.class, pend.idCierre()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"APLICACION_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?", Integer.class, pend.idCierre()));
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM \"PROPUESTA_APLICACION_PENDIENTE\" WHERE \"idCierreRecorrido\"=?", Integer.class, pend.idCierre()));
        assertEquals(0, new BigDecimal("70.00").compareTo(jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?", BigDecimal.class, pedA1)));
        assertEquals(0, new BigDecimal("50.00").compareTo(jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?", BigDecimal.class, pedA2)));
        assertEquals(0, new BigDecimal("60.00").compareTo(jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?", BigDecimal.class, pedB1)));

        // Prueba NEGATIVA SQL: intentar insertar COBRO_RENDICION_K2 mientras PENDIENTE_CONCILIACION => RECHAZADO
        jdbc.execute("SELECT setval('\"seq_gfis_cobro_id\"', GREATEST((SELECT COALESCE(MAX(\"idCobro\"),0) FROM \"COBRO\"), 1), true)");
        DataAccessException exCobroEnPend = assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s -> {
            long idCobroIlegal = GfisLabSupport.siguientePkCobroB2(jdbc);
            jdbc.update("""
                    INSERT INTO "COBRO" ("idCobro","idCliente","idUsuarioRegistrador","fechaHoraCobro",
                        "importe","medioPago","origen","claveIdempotenciaOrigen","estadoCobro")
                    VALUES (?,?,?,clock_timestamp(),110.00,'EFECTIVO','REPARTO',?,'CONFIRMADO')
                    """, idCobroIlegal, clienteA, idAdmin, UUID.randomUUID());
            jdbc.update("INSERT INTO \"COBRO_RENDICION_K2\" VALUES (?,?,?)", pend.idCierre(), clienteA, idCobroIlegal);
        }));
        assertTrue(exCobroEnPend.getMessage().contains("T1-C2:"),
                "Trigger SQL debe prohibir COBRO_RENDICION_K2 en PENDIENTE_CONCILIACION: " + exCobroEnPend.getMessage());

        // Prueba NEGATIVA Bloqueante 4 (E-04): intentar confirmar devolucion fisica de custodia SOLO con
        // tipoResolucionAdmin + nota (sin EGRESO_CUSTODIA_RENDICION con recibo) => RECHAZADO al COMMIT y rollback integro
        RuntimeException exSinEgreso = assertThrows(RuntimeException.class, () -> tx.execute(s ->
                GfisLabSupport.confirmarResolucionT1K2EnTx(
                        jdbc, pend.idCierre(), recorrido, idAdmin, UUID.randomUUID(), "v1", "h-sin-egreso",
                        "MIXTA_FALTANTE_Y_CUSTODIA",
                        "Solo texto sin recibo ni egreso de custodia",
                        null, true, true)));
        assertTrue(mensajeRaiz(exSinEgreso).contains("E04-CUSTODIA"),
                "E-04 debe rechazar devolucion fisica sin EGRESO_CUSTODIA_RENDICION: " + mensajeRaiz(exSinEgreso));
        // Rollback integro verificado: sigue PENDIENTE_CONCILIACION y 0 Cobros
        assertEquals("PENDIENTE_CONCILIACION", jdbc.queryForObject(
                "SELECT \"estadoCierre\" FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idCierreRecorrido\"=?", String.class, pend.idCierre()));
        assertEquals(cobrosAntes, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));

        // Prueba NEGATIVA E-04 adicional: intentar registrar egreso mayor a la custodia (15 > 10) o por no-ADMIN => RECHAZADO
        Long idEvCust = jdbc.queryForObject(
                "SELECT \"idEventoCustodia\" FROM \"CUSTODIA_DIFERENCIA_RENDICION\" WHERE \"idCierreRecorrido\"=? AND \"tipoEvento\"='CUSTODIA_NO_IMPUTADA'",
                Long.class, pend.idCierre());
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "EGRESO_CUSTODIA_RENDICION" (
                    "idEventoCustodia","idCierreRecorrido","tipoEgreso","importeEgreso",
                    "numeroReciboDevolucion","receptorIdentificacion","motivo","idUsuarioAdmin","fechaHoraEgreso")
                VALUES (?,?,'DEVOLUCION_FISICA_CON_RECIBO',15.00,'REC-EXCESO','Repartidor','Excede custodia',?,clock_timestamp())
                """, idEvCust, pend.idCierre(), idAdmin));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "EGRESO_CUSTODIA_RENDICION" (
                    "idEventoCustodia","idCierreRecorrido","tipoEgreso","importeEgreso",
                    "numeroReciboDevolucion","receptorIdentificacion","motivo","idUsuarioAdmin","fechaHoraEgreso")
                VALUES (?,?,'DEVOLUCION_FISICA_CON_RECIBO',10.00,'REC-NOADMIN','Repartidor','No admin',?,clock_timestamp())
                """, idEvCust, pend.idCierre(), idRepartidor));

        // Prueba NEGATIVA Bloqueante 3: prohibido blanquear PENDIENTE -> CONFIRMADO o alterar evidencia historica
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE \"CIERRE_RECORRIDO_PE2\" SET \"estadoCierre\"='CONFIRMADO' WHERE \"idCierreRecorrido\"=?",
                pend.idCierre()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE \"CIERRE_RECORRIDO_PE2\" SET \"motivoDiferencia\"='Reescritura ilegal' WHERE \"idCierreRecorrido\"=?",
                pend.idCierre()));

        // 2) Prueba POSITIVA Bloqueante 1 + Bloqueante 4: confirmacion T1-C2 en UN UNICO COMMIT atomico
        UUID claveCierreDef = UUID.randomUUID();
        String numeroReciboDev = "REC-DEV-" + f.base;
        var confirmado = tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, pend.idCierre(), recorrido, idAdmin, claveCierreDef, "v1", "h-t1c2-ok",
                "MIXTA_FALTANTE_Y_CUSTODIA",
                "Faltante 10 asentado sin recupero automatico; custodia 10 devuelta fisicamente con recibo",
                new GfisLabSupport.EvidenciaEgresoCustodia(
                        numeroReciboDev, "Repartidor #" + idRepartidor, "Devolucion fisica en efectivo verificada"),
                true, true));
        assertNotNull(confirmado);
        assertEquals("CERRADO_CON_DIFERENCIA_RESUELTA", confirmado.estadoCierre());
        assertFalse(confirmado.replay());
        assertNotNull(confirmado.reciboJson());

        // Verificar K2 exacto: 2 Cobros (A=110.00, B=60.00), Caja de ventas=170.00 (NUNCA 180.00)
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?", Integer.class, confirmado.idCierre()));
        assertEquals(0, new BigDecimal("110.00").compareTo(jdbc.queryForObject("""
                SELECT c."importe" FROM "COBRO_RENDICION_K2" k JOIN "COBRO" c ON c."idCobro"=k."idCobro"
                 WHERE k."idCierreRecorrido"=? AND k."idCliente"=?
                """, BigDecimal.class, confirmado.idCierre(), clienteA)));
        assertEquals(0, new BigDecimal("60.00").compareTo(jdbc.queryForObject("""
                SELECT c."importe" FROM "COBRO_RENDICION_K2" k JOIN "COBRO" c ON c."idCobro"=k."idCobro"
                 WHERE k."idCierreRecorrido"=? AND k."idCliente"=?
                """, BigDecimal.class, confirmado.idCierre(), clienteB)));
        assertEquals(0, new BigDecimal("170.00").compareTo(jdbc.queryForObject("""
                SELECT COALESCE(SUM(mc."importe"), 0)
                  FROM "MOVIMIENTO_CAJA" mc
                  JOIN "COBRO_RENDICION_K2" k ON k."idCobro" = mc."idCobro"
                 WHERE k."idCierreRecorrido" = ?
                """, BigDecimal.class, confirmado.idCierre())),
                "S08/E-04: MOVIMIENTO_CAJA de ventas debe registrar estrictamente 170.00 (custodia 10 segregada)");

        // Verificar egreso de custodia auditable persistido y append-only
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM \"EGRESO_CUSTODIA_RENDICION\" WHERE \"idCierreRecorrido\"=? AND \"numeroReciboDevolucion\"=?",
                Integer.class, confirmado.idCierre(), numeroReciboDev));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM \"EGRESO_CUSTODIA_RENDICION\" WHERE \"idCierreRecorrido\"=?", confirmado.idCierre()));

        // Verificar conservacion exacta: V_{A1}^{vig}=0, V_{A2}^{vig}=10.00 intacto (NO extinguido!), V_{B1}^{vig}=0
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?", BigDecimal.class, pedA1)));
        assertEquals(0, new BigDecimal("10.00").compareTo(jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?", BigDecimal.class, pedA2)),
                "G-ARQ-N2-01 s8: remanente V_{A2}^{vig}=10.00 debe conservarse intacto");
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?", BigDecimal.class, pedB1)));

        // Verificar replay C1-a con misma UUID/huella => mismo recibo historico, 0 nuevos Cobros
        var replay = tx.execute(s -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, pend.idCierre(), recorrido, idAdmin, claveCierreDef, "v1", "h-t1c2-ok",
                "MIXTA_FALTANTE_Y_CUSTODIA",
                "Reintento idempotente", null, true, true));
        assertNotNull(replay);
        assertTrue(replay.replay());
        assertEquals(confirmado.reciboJson(), replay.reciboJson());
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?", Integer.class, confirmado.idCierre()));

        // Verificar inmutabilidad de estado terminal y rechazo de segundo cierre (23505)
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE \"CIERRE_RECORRIDO_PE2\" SET \"motivoResolucionAdmin\"='Mutacion ilegal' WHERE \"idCierreRecorrido\"=?",
                confirmado.idCierre()));
        DataAccessException exSegundo = assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s ->
                GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, recorrido, idAdmin, UUID.randomUUID(), "v1", "h-segundo",
                        new BigDecimal("10.00"), new BigDecimal("10.00"),
                        List.of(new GfisLabSupport.AplicacionPlan(clienteA, pedA2, new BigDecimal("10.00"))),
                        true, true)));
        assertEquals("23505", extraerSqlState(exSegundo), "Segundo cierre debe fallar por uq_gfis_unico_cierre_por_recorrido");

        // Verificar D7-A sobre el Cobro REPARTO del Cliente A
        Long idCobroA = jdbc.queryForObject(
                "SELECT \"idCobro\" FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=? AND \"idCliente\"=?",
                Long.class, confirmado.idCierre(), clienteA);
        DataAccessException exD7a = assertThrows(DataAccessException.class, () -> jdbc.execute(
                "SELECT \"fn_mr3_anular_cobro_manual\"(" + idCobroA + "," + idAdmin + ",9999001,9999002,'Intento D7-A')"));
        assertTrue(exD7a.getMessage().contains("D7-A"));
    }

    @Test
    void cierreLimpioRollbackAtomicoT1AnteExcesoDeCobertura() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idVendedor = f.base;
        long idAdmin = f.base + 1;
        long idRepartidor = f.base + 3;
        long clienteA = f.base;

        long ped = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRepartidor, "REC-ROLLBACK-T1");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, ped, idRepartidor, idVendedor, recorrido);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, ped, clienteA, idRepartidor, recorrido,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");

        int cobrosAntes = jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class);
        int cajaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class);

        assertThrows(GfisLabSupport.CoberturaNoAcreditadaException.class, () -> tx.executeWithoutResult(status ->
                GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, recorrido, idAdmin, UUID.randomUUID(), "v1", "h-fail",
                        new BigDecimal("100.00"), new BigDecimal("105.00"),
                        List.of(new GfisLabSupport.AplicacionPlan(clienteA, ped, new BigDecimal("105.00"))),
                        true, true)));
        assertEquals(cobrosAntes, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));
        assertEquals(cajaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=?",
                Integer.class, recorrido));
    }

    @Test
    void seguridadRbacSoloAdminD1SoloEfectivoDc0507AYCierreSinAplicacionesPositivas() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idVendedor = f.base;
        long idAdmin = f.base + 1;
        long idRepartidor = f.base + 3;
        long clienteA = f.base;

        long ped = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRepartidor, "REC-FRONTERAS");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, ped, idRepartidor, idVendedor, recorrido);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, ped, clienteA, idRepartidor, recorrido,
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("200.00"), "IMP_B");

        // 1) RBAC: REPARTIDOR no puede registrar cierre PE-2
        DataAccessException exRbac = assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "CIERRE_RECORRIDO_PE2" (
                    "idRecorrido","claveIdempotenciaPropuesta",
                    "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                    "importeImputadoConfirmado","importeDiferenciaFaltante","importeCustodiaNoImputada",
                    "motivoDiferencia")
                VALUES (?,?,?,clock_timestamp(),'PENDIENTE_CONCILIACION','EFECTIVO',
                        200,0,0,0,200,0,'Motivo prueba RBAC')
                """, recorrido, UUID.randomUUID(), idRepartidor));
        assertTrue(exRbac.getMessage().contains("PE-2/RBAC"));

        // 2) D1: rechazo de medio distinto a EFECTIVO en cierre PE-2
        DataAccessException exD1 = assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "CIERRE_RECORRIDO_PE2" (
                    "idRecorrido","claveIdempotenciaPropuesta",
                    "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                    "importeImputadoConfirmado","importeDiferenciaFaltante","importeCustodiaNoImputada",
                    "motivoDiferencia")
                VALUES (?,?,?,clock_timestamp(),'PENDIENTE_CONCILIACION','TRANSFERENCIA',
                        200,0,0,0,200,0,'Motivo prueba D1')
                """, recorrido, UUID.randomUUID(), idAdmin));
        assertTrue(exD1.getMessage().contains("ck_gfis_cierre_d1_solo_efectivo"));

        // 3) Cierre con 0 aplicaciones positivas (faltante integro = 200): PENDIENTE -> CERRADO_CON_DIFERENCIA_RESUELTA
        var resPend = tx.execute(status -> GfisLabSupport.registrarPendienteConciliacionEnTx(
                jdbc, recorrido, idAdmin, UUID.randomUUID(),
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2),
                "Repartidor sin efectivo al cierre; pendiente de conciliacion",
                List.of()));
        assertNotNull(resPend);
        assertEquals("PENDIENTE_CONCILIACION", resPend.estadoCierre());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?", Integer.class, resPend.idCierre()));

        UUID claveResCero = UUID.randomUUID();
        var resCerrado = tx.execute(status -> GfisLabSupport.confirmarResolucionT1K2EnTx(
                jdbc, resPend.idCierre(), recorrido, idAdmin, claveResCero, "v1", "h-cero-res",
                "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO",
                "Faltante total 200 asentado administrativamente sin recupero automatico ni extincion financiera",
                null, true, true));
        assertNotNull(resCerrado);
        assertEquals("CERRADO_CON_DIFERENCIA_RESUELTA", resCerrado.estadoCierre());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?", Integer.class, resCerrado.idCierre()));
        assertEquals(0, new BigDecimal("200.00").compareTo(jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, ped)),
                "Resolucion administrativa con 0 aplicaciones NO debe extinguir V_e^{vig}");

        // 4) DC05-07-A: un Cobro manual con reserva activa NO puede anularse; tras liberar reserva SI puede
        var cobroManual = cobros.registrarCobro(
                f.sesiones.get("ADMINISTRADOR"), clienteA, null,
                new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());
        long pedNuevo = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                VALUES (?,?,?,'RESERVA_ANTICIPO',60.00,?,clock_timestamp())
                """, clienteA, cobroManual.idCobro(), pedNuevo, idAdmin);

        DataAccessException exDc05 = assertThrows(DataAccessException.class, () ->
                cobros.anularCobroManual(f.sesiones.get("ADMINISTRADOR"), cobroManual.idCobro(), "Intento con reserva"));
        assertTrue(exDc05.getMessage().contains("DC05-07-A"));

        jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","tipoEvento","importe","idUsuarioOperador","fechaHoraEvento")
                VALUES (?,?,?,'LIBERACION_ANTICIPO',60.00,?,clock_timestamp())
                """, clienteA, cobroManual.idCobro(), pedNuevo, idAdmin);
        var anulado = cobros.anularCobroManual(f.sesiones.get("ADMINISTRADOR"), cobroManual.idCobro(), "Reserva liberada");
        assertEquals("ANULADO", anulado.estado());
    }

    @Test
    void controlesNegativosK2RechazanMulticlienteDesajusteDeSumaYMedioDistintoDeEfectivo() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idVendedor = f.base;
        long idAdmin = f.base + 1;
        long idRepartidor = f.base + 3;
        long clienteA = f.base;
        long clienteB = f.base + 1;

        long pedA = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long pedB = pedidos.cargarPedido(f.vendedor(), new CargarPedidoService.DatosPedido(
                clienteB, f.hoy.plusDays(2),
                List.of(new CargarPedidoService.ItemPedido(f.base, new BigDecimal("1.000"))),
                new CargarPedidoService.DireccionEntrega("Calle Neg", "10", null, null, "Posadas", "Misiones", null, null),
                UUID.randomUUID())).idPedido();

        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRepartidor, "REC-NEG-K2");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedA, idRepartidor, idVendedor, recorrido);
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedB, idRepartidor, idVendedor, recorrido);
        jdbc.execute("SELECT setval('\"seq_gfis_cobro_id\"', GREATEST((SELECT COALESCE(MAX(\"idCobro\"),0) FROM \"COBRO\"), 1), true)");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA, clienteA, idRepartidor, recorrido,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedB, clienteB, idRepartidor, recorrido,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");

        // 1) Cobro K2 con importe 100 pero aplicacion de solo 80 => rechazado al COMMIT por ct_gfis_k2_cobro_suma
        RuntimeException exSuma = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            Long idCierre = jdbc.queryForObject("""
                    INSERT INTO "CIERRE_RECORRIDO_PE2" (
                        "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                        "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                        "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                        "importeImputadoConfirmado","importeDiferenciaFaltante","importeCustodiaNoImputada",
                        "reciboHistoricoJson")
                    VALUES (?,?, 'v1','h-desaj',?,clock_timestamp(),'CONFIRMADO','EFECTIVO',
                            100,100,100,100,0,0,'{}'::jsonb)
                    RETURNING "idCierreRecorrido"
                    """, Long.class, recorrido, UUID.randomUUID(), idAdmin);
            long idCobro = GfisLabSupport.siguientePkCobroB2(jdbc);
            jdbc.update("""
                    INSERT INTO "COBRO" ("idCobro","idCliente","idUsuarioRegistrador","fechaHoraCobro",
                        "importe","medioPago","origen","claveIdempotenciaOrigen","estadoCobro")
                    VALUES (?,?,?,clock_timestamp(),100,'EFECTIVO','REPARTO',?,'CONFIRMADO')
                    """, idCobro, clienteA, idAdmin, UUID.randomUUID());
            jdbc.update("INSERT INTO \"COBRO_RENDICION_K2\" VALUES (?,?,?)", idCierre, clienteA, idCobro);
            jdbc.update("""
                    INSERT INTO "APLICACION_RENDICION_K2" ("idCierreRecorrido","idCliente","idPedido","idCobro",
                        "importeAplicado","fechaHoraAplicacion")
                    VALUES (?,?,?,?,80,clock_timestamp())
                    """, idCierre, clienteA, pedA, idCobro);
            jdbc.update("""
                    UPDATE "RECONOCIMIENTO_ENTREGA_PE1" SET "reconocimientoVigenteVvig"=20 WHERE "idPedido"=?
                    """, pedA);
        }));
        assertTrue(mensajeRaiz(exSuma).contains("K2-CONSERVACION"));

        // 2) Cobro K2 de Cliente A intentando aplicarse a Entrega de Cliente B (multicliente) => FK rechaza
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> {
            Long idCierre = jdbc.queryForObject("""
                    INSERT INTO "CIERRE_RECORRIDO_PE2" (
                        "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                        "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                        "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                        "importeImputadoConfirmado","importeDiferenciaFaltante","importeCustodiaNoImputada",
                        "reciboHistoricoJson")
                    VALUES (?,?, 'v1','h-multi',?,clock_timestamp(),'CONFIRMADO','EFECTIVO',
                            100,100,100,100,0,0,'{}'::jsonb)
                    RETURNING "idCierreRecorrido"
                    """, Long.class, recorrido, UUID.randomUUID(), idAdmin);
            long idCobro = GfisLabSupport.siguientePkCobroB2(jdbc);
            jdbc.update("""
                    INSERT INTO "COBRO" ("idCobro","idCliente","idUsuarioRegistrador","fechaHoraCobro",
                        "importe","medioPago","origen","claveIdempotenciaOrigen","estadoCobro")
                    VALUES (?,?,?,clock_timestamp(),100,'EFECTIVO','REPARTO',?,'CONFIRMADO')
                    """, idCobro, clienteA, idAdmin, UUID.randomUUID());
            jdbc.update("INSERT INTO \"COBRO_RENDICION_K2\" VALUES (?,?,?)", idCierre, clienteA, idCobro);
            jdbc.update("""
                    INSERT INTO "APLICACION_RENDICION_K2" ("idCierreRecorrido","idCliente","idPedido","idCobro",
                        "importeAplicado","fechaHoraAplicacion")
                    VALUES (?,?,?,?,100,clock_timestamp())
                    """, idCierre, clienteA, pedB, idCobro);
        }));

        // 3) Cobro TRANSFERENCIA vinculado a COBRO_RENDICION_K2 => trigger D1 rechaza
        DataAccessException exMedio = assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> {
            Long idCierre = jdbc.queryForObject("""
                    INSERT INTO "CIERRE_RECORRIDO_PE2" (
                        "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                        "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                        "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                        "importeImputadoConfirmado","importeDiferenciaFaltante","importeCustodiaNoImputada",
                        "reciboHistoricoJson")
                    VALUES (?,?, 'v1','h-transf',?,clock_timestamp(),'CONFIRMADO','EFECTIVO',
                            100,100,100,100,0,0,'{}'::jsonb)
                    RETURNING "idCierreRecorrido"
                    """, Long.class, recorrido, UUID.randomUUID(), idAdmin);
            long idCobro = GfisLabSupport.siguientePkCobroB2(jdbc);
            jdbc.update("""
                    INSERT INTO "COBRO" ("idCobro","idCliente","idUsuarioRegistrador","fechaHoraCobro",
                        "importe","medioPago","origen","claveIdempotenciaOrigen","estadoCobro")
                    VALUES (?,?,?,clock_timestamp(),100,'TRANSFERENCIA','REPARTO',?,'CONFIRMADO')
                    """, idCobro, clienteA, idAdmin, UUID.randomUUID());
            jdbc.update("INSERT INTO \"COBRO_RENDICION_K2\" VALUES (?,?,?)", idCierre, clienteA, idCobro);
        }));
        assertTrue(exMedio.getMessage().contains("D1:"));
    }

    /**
     * Bloqueante 2 (Integridad de Recorrido, FA-1, FA-2 y exigencia de pertenencia ACTIVA en ENTREGA):
     * 1) RBAC UC-43 exclusivo VENDEDOR (ADMIN rechazado).
     * 2) FA-1: 2do Recorrido ABIERTO para el mismo Repartidor rechazado (23505).
     * 3) fn_gfis_validar_recorrido_entrega RECHAZA Entrega sin pertenencia ACTIVA previa.
     * 4) Reasignacion UC-43 preserva historial ASIGNACION_REPARTO y mueve pertenencia al Recorrido 2.
     * 5) FA-2: intentar cerrar Recorrido con Pedido PENDIENTE ACTIVA => RECHAZADO; tras resolverlo => CIERRA
     *    y habilita abrir un nuevo Recorrido ABIERTO para ese Repartidor.
     */
    @Test
    void fronteraA_IntegridadRecorridoFa1Fa2ReasignacionUc43YEntregaSinPertenenciaRechazada() {
        var f = tx.execute(s -> new AsignacionRepartoFixture(jdbc, pedidos));
        assertNotNull(f);
        long idAdmin = f.origen.base + 1;

        // 1) RBAC real de UC-43: ADMINISTRADOR NO esta autorizado (solo VENDEDOR)
        assertThrows(SecurityException.class, () -> asignaciones.asignarOReasignar(
                f.origen.sesiones.get("ADMINISTRADOR"), f.datos(f.primero, null)));

        // 2) FA-1: un solo Recorrido ABIERTO por Repartidor
        long recRep1 = GfisLabSupport.crearRecorridoExplicito(jdbc, f.primero, "REC-FA-1");
        DataAccessException exFa1 = assertThrows(DataAccessException.class, () ->
                GfisLabSupport.crearRecorridoExplicito(jdbc, f.primero, "REC-FA-1-DUP"));
        assertEquals("23505", extraerSqlState(exFa1),
                "FA-1: uq_gfis_unico_recorrido_abierto_repartidor debe impedir 2 recorridos ABIERTO del mismo Repartidor");

        long recRep2 = GfisLabSupport.crearRecorridoExplicito(jdbc, f.segundo, "REC-FA-2");

        // 3) Bloqueante 2: intentar vincular ENTREGA sin pertenencia ACTIVA previa en RECORRIDO_ASIGNACION => RECHAZADO
        long pedSinVincular = pedidos.cargarPedido(f.vendedor(), f.origen.datos()).idPedido();
        DataAccessException exSinPertenencia = assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s ->
                GfisLabSupport.registrarEntregaPe1EnLaboratorio(
                        jdbc, pedSinVincular, f.origen.base, f.primero, recRep1,
                        new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        new BigDecimal("100.00"), "IMP_B")));
        assertTrue(exSinPertenencia.getMessage().contains("FRONTERA-A:"),
                "fn_gfis_validar_recorrido_entrega debe rechazar Entrega sin pertenencia ACTIVA previa");

        // 4) Asignacion inicial UC-43 (VENDEDOR) y vinculacion pre-ENTREGA
        var asig1 = asignaciones.asignarOReasignar(f.vendedor(), f.datos(f.primero, null));
        assertThrows(DataAccessException.class, () ->
                GfisLabSupport.vincularAsignacionARecorridoEnLab(jdbc, recRep2, f.pendiente, asig1.numeroAsignacion()),
                "No se puede vincular asignacion de Repartidor 1 a Recorrido de Repartidor 2");
        GfisLabSupport.vincularAsignacionARecorridoEnLab(jdbc, recRep1, f.pendiente, asig1.numeroAsignacion());

        // Reasignacion UC-43 a Repartidor 2 preservando historial ASIGNACION_REPARTO
        var asig2 = asignaciones.asignarOReasignar(f.vendedor(), f.datos(f.segundo, "Cambio de turno"));
        GfisLabSupport.desvincularAsignacionEnLab(
                jdbc, f.pendiente, asig1.numeroAsignacion(), "DESVINCULADA_POR_REASIGNACION", "Cambio de turno");
        GfisLabSupport.vincularAsignacionARecorridoEnLab(jdbc, recRep2, f.pendiente, asig2.numeroAsignacion());
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=?", Integer.class, f.pendiente));

        // Entregar f.pendiente en recRep2 (pasa RECORRIDO_ASIGNACION a ENTREGADA)
        tx.executeWithoutResult(s -> GfisLabSupport.registrarEntregaPe1EnLaboratorio(
                jdbc, f.pendiente, f.origen.base, f.segundo, recRep2,
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("200.00"), "IMP_B"));
        assertEquals("ENTREGADA", jdbc.queryForObject(
                "SELECT \"estadoPertenencia\" FROM \"RECORRIDO_ASIGNACION\" WHERE \"idPedido\"=? AND \"numeroAsignacion\"=2",
                String.class, f.pendiente));

        // 5) FA-2: agregar un segundo Pedido PENDIENTE ACTIVO en recRep2 => cerrar recRep2 debe RECHAZARSE
        long pedExtra = pedidos.cargarPedido(f.vendedor(), f.origen.datos()).idPedido();
        var asigExtra = asignaciones.asignarOReasignar(
                f.vendedor(), new AsignarRepartoService.DatosAsignacion(pedExtra, f.segundo, null));
        GfisLabSupport.vincularAsignacionARecorridoEnLab(jdbc, recRep2, pedExtra, asigExtra.numeroAsignacion());

        DataAccessException exFa2 = assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s ->
                GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                        jdbc, recRep2, idAdmin, UUID.randomUUID(), "v1", "h-fa2-bloqueo",
                        new BigDecimal("200.00"), new BigDecimal("200.00"),
                        List.of(new GfisLabSupport.AplicacionPlan(f.origen.base, f.pendiente, new BigDecimal("200.00"))),
                        true, true)));
        assertTrue(exFa2.getMessage().contains("FA-2"),
                "FA-2 debe impedir cerrar Recorrido mientras existan Pedidos PENDIENTE activos: " + exFa2.getMessage());

        // Tras resolver el Pedido pendiente (ej. cancelacion con desvinculacion auditable), el cierre procede
        GfisLabSupport.desvincularAsignacionEnLab(
                jdbc, pedExtra, asigExtra.numeroAsignacion(), "DESVINCULADA_POR_CANCELACION", "Cliente cancelo antes de salir");
        var cierreOk = tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                jdbc, recRep2, idAdmin, UUID.randomUUID(), "v1", "h-fa2-ok",
                new BigDecimal("200.00"), new BigDecimal("200.00"),
                List.of(new GfisLabSupport.AplicacionPlan(f.origen.base, f.pendiente, new BigDecimal("200.00"))),
                true, true));
        assertNotNull(cierreOk);
        assertEquals("CONFIRMADO", cierreOk.estadoCierre());

        // Una vez CERRADO recRep2, Repartidor 2 SI puede abrir su proximo Recorrido ABIERTO (FA-1)
        long recRep2Nuevo = GfisLabSupport.crearRecorridoExplicito(jdbc, f.segundo, "REC-FA-2-SIGUIENTE");
        assertTrue(recRep2Nuevo > recRep2);
    }

    private static String mensajeRaiz(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = ex;
        while (cur != null) {
            if (cur.getMessage() != null) sb.append(cur.getMessage()).append(" | ");
            cur = cur.getCause();
        }
        return sb.toString();
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
