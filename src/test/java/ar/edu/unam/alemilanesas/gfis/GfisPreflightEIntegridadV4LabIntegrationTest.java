package ar.edu.unam.alemilanesas.gfis;

import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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
 * G-FIS (Fase A + Fase B — Laboratorio aislado):
 * Verifica preflight V1-V3/RCG-06, PE-1, IMP-01 (IMP-B/IMP-A/IMP-C),
 * T1 + K2, conservacion A_e + V_e^{vig} = V_e^0, D1 (solo efectivo),
 * D7-A, DC05-07-A, RBAC ADMINISTRADOR, custodia segregada y escenarios
 * de frontera abiertos sin resolverlos silenciosamente.
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

    private static final AtomicBoolean V4_APLICADA = new AtomicBoolean(false);

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired CobroManualService cobros;

    @BeforeEach
    void prepararLaboratorio() {
        if (V4_APLICADA.compareAndSet(false, true)) {
            GfisLabSupport.aplicarMigracionCandidataV4EnLaboratorio(dataSource);
        }
    }

    @Test
    void preflightVerificaV1V3IntactasSinBackfillHeuristicoDeCobroIdPedido() {
        List<String> versionesFlyway = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL AND success = true ORDER BY installed_rank",
                String.class);
        assertEquals(List.of("1", "2", "3"), versionesFlyway,
                "Flyway de aplicacion debe ejecutar exclusivamente V1-V3");

        Integer nuevasTablas = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'RECONOCIMIENTO_ENTREGA_PE1','RECORRIDO_REPARTO','RECORRIDO_ENTREGA',
                       'CIERRE_RECORRIDO_PE2','COBRO_RENDICION_K2','APLICACION_RENDICION_K2',
                       'CUSTODIA_DIFERENCIA_RENDICION','EVENTO_IMPUTACION_FINANCIERA')
                """, Integer.class);
        assertEquals(8, nuevasTablas, "Las 8 tablas candidatas de laboratorio deben existir");

        // Verificar que un cobro manual historico con idPedido=NULL no recibe backfill heuristico
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
        long idAdmin = f.base + 1;
        long idRepartidor = f.base + 3;
        long idCliente = f.base;

        long p1 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long p2 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long p3 = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRepartidor, "REC-PE1");

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

        // Reserva 800 -> Consolida 500 -> Libera 300 (caso de aceptacion RCG-01)
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

        // Disponible libre debe ser 1000 - 500 = 500; intentar sobreuso de 500.01 debe fallar
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
                new BigDecimal("500.00"), new BigDecimal("500.00"), new BigDecimal("0.00"),
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

        // Intento directo de violar IMP-C con V_e^0 > 0 debe ser rechazado por CHECK
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

    @Test
    void cierreT1K2MultiClienteMultiEntregaConservaInvariantesYRollbackEsAtomico() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
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

        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRepartidor, "REC-T1-K2");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA1, clienteA, idRepartidor, recorrido,
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA2, clienteA, idRepartidor, recorrido,
                new BigDecimal("150.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("80.00"), "IMP_B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedB1, clienteB, idRepartidor, recorrido,
                new BigDecimal("300.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("250.00"), "IMP_B");

        // 1) Rollback atomico T1: si una aplicacion excede V_e^0 (ej. 105 > 100), nada queda persistido
        int cobrosAntes = jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class);
        int cajaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class);
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status ->
                GfisLabSupport.ejecutarCierreT1K2EnTx(
                        jdbc, recorrido, idAdmin, UUID.randomUUID(), "v1", "h-fail", "CONFIRMADO",
                        new BigDecimal("430.00"), new BigDecimal("435.00"),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        null, null,
                        List.of(
                                new GfisLabSupport.AplicacionPlan(clienteA, pedA1, new BigDecimal("105.00")),
                                new GfisLabSupport.AplicacionPlan(clienteA, pedA2, new BigDecimal("80.00")),
                                new GfisLabSupport.AplicacionPlan(clienteB, pedB1, new BigDecimal("250.00"))),
                        true, false)));
        assertEquals(cobrosAntes, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));
        assertEquals(cajaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"CIERRE_RECORRIDO_PE2\" WHERE \"idRecorrido\"=?",
                Integer.class, recorrido));

        // 2) Cierre T1 + K2 valido con diferencia parcial en pedA2 (10 de faltante) y 10 de custodia:
        //    Cliente A: pedA1=100 + pedA2=70 => 1 solo COBRO de 170.00 (V_e^vig remanente pedA2 = 10.00)
        //    Cliente B: pedB1=250 => 1 solo COBRO de 250.00 (V_e^vig = 0.00)
        //    Efectivo recibido = 430.00 (420 imputado + 10 custodia), faltante = 10.00
        var res = tx.execute(status -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                jdbc, recorrido, idAdmin, UUID.randomUUID(), "v1", "h-ok-k2", "CONFIRMADO",
                new BigDecimal("430.00"), new BigDecimal("430.00"),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                "Faltante 10 en pedA2 y custodia 10 no imputada",
                "Administrador valida diferencia y custodia sin segundo Cobro",
                List.of(
                        new GfisLabSupport.AplicacionPlan(clienteA, pedA1, new BigDecimal("100.00")),
                        new GfisLabSupport.AplicacionPlan(clienteA, pedA2, new BigDecimal("70.00")),
                        new GfisLabSupport.AplicacionPlan(clienteB, pedB1, new BigDecimal("250.00"))),
                true, false));
        assertNotNull(res);
        assertEquals("CONFIRMADO", res.estadoCierre());

        // K2: exactamente 2 Cobros REPARTO (1 por Cliente), no 3
        Integer cobrosK2 = jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, res.idCierre());
        assertEquals(2, cobrosK2);
        BigDecimal cobroClienteA = jdbc.queryForObject("""
                SELECT c."importe" FROM "COBRO_RENDICION_K2" k JOIN "COBRO" c ON c."idCobro"=k."idCobro"
                 WHERE k."idCierreRecorrido"=? AND k."idCliente"=?
                """, BigDecimal.class, res.idCierre(), clienteA);
        assertEquals(0, new BigDecimal("170.00").compareTo(cobroClienteA));

        // Segregacion custodia/Caja: solo 2 movimientos de Caja por 170 + 250 = 420 (NUNCA 430)
        BigDecimal sumaCajaCierre = jdbc.queryForObject("""
                SELECT COALESCE(SUM(mc."importe"), 0)
                  FROM "MOVIMIENTO_CAJA" mc
                  JOIN "COBRO_RENDICION_K2" k ON k."idCobro" = mc."idCobro"
                 WHERE k."idCierreRecorrido" = ?
                """, BigDecimal.class, res.idCierre());
        assertEquals(0, new BigDecimal("420.00").compareTo(sumaCajaCierre),
                "S08: custodia de 10 no debe generar ingreso de venta duplicado en MOVIMIENTO_CAJA");

        // Conservacion exacta A_e + V_e^{vig} = V_e^0 en pedA2 (70 + 10 = 80)
        BigDecimal vvigA2 = jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, pedA2);
        assertEquals(0, new BigDecimal("10.00").compareTo(vvigA2));

        // D7-A: prohibido anular Cobro REPARTO incluso invocando fn_mr3_anular_cobro_manual
        Long idCobroA = jdbc.queryForObject(
                "SELECT \"idCobro\" FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=? AND \"idCliente\"=?",
                Long.class, res.idCierre(), clienteA);
        DataAccessException exD7a = assertThrows(DataAccessException.class, () -> jdbc.execute(
                "SELECT \"fn_mr3_anular_cobro_manual\"(" + idCobroA + "," + idAdmin + ",9999001,9999002,'Intento ilegal')"));
        assertTrue(exD7a.getMessage().contains("D7-A"), "Debe disparar guard SQL D7-A: " + exD7a.getMessage());

        // Cuestion abierta documentada: NO permitir segundo cierre confirmado sobre mismo recorrido
        // aunque exista V_e^{vig} = 10.00 remanente (sin inventar recupero post-cierre E)
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status ->
                GfisLabSupport.ejecutarCierreT1K2EnTx(
                        jdbc, recorrido, idAdmin, UUID.randomUUID(), "v1", "h-segundo-cierre", "CONFIRMADO",
                        new BigDecimal("10.00"), new BigDecimal("10.00"),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        null, null,
                        List.of(new GfisLabSupport.AplicacionPlan(clienteA, pedA2, new BigDecimal("10.00"))),
                        true, false)),
                "C1-a / E: prohibido segundo cierre confirmado sobre el mismo Recorrido");
    }

    @Test
    void seguridadRbacSoloAdminD1SoloEfectivoDc0507AYCierreSinAplicacionesPositivas() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idAdmin = f.base + 1;
        long idRepartidor = f.base + 3;
        long clienteA = f.base;

        long ped = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, idRepartidor, "REC-FRONTERAS");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, ped, clienteA, idRepartidor, recorrido,
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("200.00"), "IMP_B");

        // 1) RBAC: REPARTIDOR no puede registrar cierre PE-2
        DataAccessException exRbac = assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "CIERRE_RECORRIDO_PE2" (
                    "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                    "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoTotal",
                    "importeDiferenciaFaltante","importeCustodiaNoImputada","motivoDiferencia","reciboHistoricoJson")
                VALUES (?,?, 'v1','h-rep',?,clock_timestamp(),'PENDIENTE_CONCILIACION','EFECTIVO',
                        200,0,0,200,0,'Motivo prueba RBAC','{}'::jsonb)
                """, recorrido, UUID.randomUUID(), idRepartidor));
        assertTrue(exRbac.getMessage().contains("PE-2/RBAC"),
                "Debe rechazar por RBAC ADMINISTRADOR: " + exRbac.getMessage());

        // 2) D1: rechazo de medio distinto a EFECTIVO en cierre PE-2
        DataAccessException exD1 = assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO "CIERRE_RECORRIDO_PE2" (
                    "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                    "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoTotal",
                    "importeDiferenciaFaltante","importeCustodiaNoImputada","motivoDiferencia","reciboHistoricoJson")
                VALUES (?,?, 'v1','h-d1',?,clock_timestamp(),'PENDIENTE_CONCILIACION','TRANSFERENCIA',
                        200,0,0,200,0,'Motivo prueba D1','{}'::jsonb)
                """, recorrido, UUID.randomUUID(), idAdmin));
        assertTrue(exD1.getMessage().contains("ck_gfis_cierre_d1_solo_efectivo"),
                "Debe rechazar por D1 solo efectivo: " + exD1.getMessage());

        // 3) Cuestion abierta probada explicitamente: cierre con 0 aplicaciones positivas (todo faltante = 200)
        //    Queda PENDIENTE_CONCILIACION, 0 COBRO ficticio, 0 MOVIMIENTO_CAJA, V_e^{vig} intacto en 200
        var resCeroApps = tx.execute(status -> GfisLabSupport.ejecutarCierreT1K2EnTx(
                jdbc, recorrido, idAdmin, UUID.randomUUID(), "v1", "h-cero-apps", "PENDIENTE_CONCILIACION",
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2),
                "Repartidor sin efectivo al cierre; pendiente de conciliacion", null,
                List.of(), true, false));
        assertNotNull(resCeroApps);
        assertEquals("PENDIENTE_CONCILIACION", resCeroApps.estadoCierre());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Integer.class, resCeroApps.idCierre()));
        assertEquals(0, new BigDecimal("200.00").compareTo(jdbc.queryForObject(
                "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?",
                BigDecimal.class, ped)),
                "Efectivo no recibido no debe extinguir V_e^{vig} ni generar Cobro ficticio");

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
        assertTrue(exDc05.getMessage().contains("DC05-07-A"),
                "Debe bloquear anulacion con reserva activa: " + exDc05.getMessage());

        // Al liberar la reserva completa (ej. cancelacion UC-06), la anulacion vuelve a ser valida
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
        jdbc.execute("SELECT setval('\"seq_gfis_cobro_id\"', GREATEST((SELECT COALESCE(MAX(\"idCobro\"),0) FROM \"COBRO\"), 1), true)");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedA, clienteA, idRepartidor, recorrido,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedB, clienteB, idRepartidor, recorrido,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");

        // 1) Cobro K2 con importe 100 pero aplicacion de solo 80 => rechazado al COMMIT por ct_gfis_k2_cobro_suma
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> {
            Long idCierre = jdbc.queryForObject("""
                    INSERT INTO "CIERRE_RECORRIDO_PE2" (
                        "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                        "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                        "importeEsperado","importeEfectivoRecibido","importeImputadoTotal",
                        "importeDiferenciaFaltante","importeCustodiaNoImputada","reciboHistoricoJson")
                    VALUES (?,?, 'v1','h-desaj',?,clock_timestamp(),'CONFIRMADO','EFECTIVO',
                            100,100,100,0,0,'{}'::jsonb)
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
        }), "K2 debe rechazar desajuste entre COBRO.importe y suma de aplicaciones");

        // 2) Cobro K2 de Cliente A intentando aplicarse a Entrega de Cliente B (multicliente) => FK rechaza
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> {
            Long idCierre = jdbc.queryForObject("""
                    INSERT INTO "CIERRE_RECORRIDO_PE2" (
                        "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                        "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                        "importeEsperado","importeEfectivoRecibido","importeImputadoTotal",
                        "importeDiferenciaFaltante","importeCustodiaNoImputada","reciboHistoricoJson")
                    VALUES (?,?, 'v1','h-multi',?,clock_timestamp(),'CONFIRMADO','EFECTIVO',
                            100,100,100,0,0,'{}'::jsonb)
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
        }), "K2 debe impedir aplicar Cobro de Cliente A sobre Entrega de Cliente B");

        // 3) Cobro TRANSFERENCIA vinculado a COBRO_RENDICION_K2 => trigger D1 rechaza
        DataAccessException exMedio = assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> {
            Long idCierre = jdbc.queryForObject("""
                    INSERT INTO "CIERRE_RECORRIDO_PE2" (
                        "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                        "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                        "importeEsperado","importeEfectivoRecibido","importeImputadoTotal",
                        "importeDiferenciaFaltante","importeCustodiaNoImputada","reciboHistoricoJson")
                    VALUES (?,?, 'v1','h-transf',?,clock_timestamp(),'CONFIRMADO','EFECTIVO',
                            100,100,100,0,0,'{}'::jsonb)
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
        assertTrue(exMedio.getMessage().contains("D1:"),
                "Debe rechazar medioPago distinto de EFECTIVO en COBRO_RENDICION_K2: " + exMedio.getMessage());
    }
}
