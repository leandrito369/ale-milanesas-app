package ar.edu.unam.alemilanesas.gfis;

import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import ar.edu.unam.alemilanesas.finanzas.RegistrarMovimientoCajaService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * G-FIS Fase C (T3 + T4) sobre la migracion Flyway real V4__gfis_pe1_pe2_imp01.sql:
 *   T3: Compatibilidad real de CobroManualService y RegistrarMovimientoCajaService contra V1..V4
 *       (alta, replay idempotente, caja manual, anulacion L02, rechazos D7-A / DC05-07-A y concurrencia).
 *   T4: Seguridad SQL real directamente contra funciones V4 (ataques search_path con objetos sombra en
 *       pg_temp y public derrotados por ADAPT-02) + rol operativo no-owner/no-superuser (denegacion
 *       42501 por ADAPT-03 antes de GRANT, flujo minimo valido tras GRANT y denegacion 42501 en mutaciones).
 */
@SpringBootTest
@Testcontainers
class GfisC01CompatibilidadLegacyYSeguridadLabIntegrationTest {

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
    @Autowired CobroManualService cobroManualService;
    @Autowired RegistrarMovimientoCajaService cajaService;
    @Autowired CargarPedidoService cargarPedidoService;

    private record ContextoFinanzas(
            PedidoFixture fixture,
            long idAdmin,
            long idVendedor,
            long idRepartidor,
            long idCliente,
            UUID sesionAdmin,
            UUID sesionVendedor) {}

    private ContextoFinanzas crearContextoFinanzas() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idVendedor = f.base;
        long idAdmin = f.base + 1;
        long idRepartidor = f.base + 3;
        long idCliente = f.base;

        UUID sesionAdmin = UUID.randomUUID();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "SESION_USUARIO" (
                    "idSesionUsuario","idUsuario","tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,?,decode(replace(?::text, '-', ''), 'hex'),now() - interval '1 minute',now() + interval '2 hours')
                """, sesionAdmin, idAdmin, sesionAdmin));
        return new ContextoFinanzas(f, idAdmin, idVendedor, idRepartidor, idCliente, sesionAdmin, f.vendedor());
    }

    /**
     * T3-A1: Servicios productivos reales (CobroManualService + RegistrarMovimientoCajaService)
     * ejecutados contra Flyway V1..V4: alta, replay idempotente, caja manual y anulacion L02.
     */
    @Test
    void t3A1_ServiciosLegacyCompatiblesConFlywayV4EnAltaCajaReplayYAnulacionValida() {
        ContextoFinanzas ctx = crearContextoFinanzas();

        UUID claveCobro = UUID.randomUUID();
        var alta = cobroManualService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null, new BigDecimal("150.00"), "EFECTIVO", claveCobro);
        var replayCobro = cobroManualService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null, new BigDecimal("150.00"), "EFECTIVO", claveCobro);
        assertEquals(alta.idCobro(), replayCobro.idCobro());
        assertEquals("CONFIRMADO", alta.estado());

        UUID claveCaja = UUID.randomUUID();
        var caja1 = cajaService.registrarMovimientoCaja(
                ctx.sesionAdmin(), "INGRESO", new BigDecimal("40.00"), "Fondo operativo T3", "FaseC", claveCaja);
        var cajaReplay = cajaService.registrarMovimientoCaja(
                ctx.sesionAdmin(), "INGRESO", new BigDecimal("40.00"), "Fondo operativo T3", "FaseC", claveCaja);
        assertTrue(caja1.creado());
        assertFalse(cajaReplay.creado());
        assertEquals(caja1.idMovimientoCaja(), cajaReplay.idMovimientoCaja());

        var anulado = cobroManualService.anularCobroManual(
                ctx.sesionAdmin(), alta.idCobro(), "Error de carga verificado en T3");
        assertEquals("ANULADO", anulado.estado());
        assertEquals(0, BigDecimal.ZERO.compareTo(anulado.saldoConfirmado()));
        assertEquals("ANULADO", jdbc.queryForObject(
                "SELECT \"estadoCobro\" FROM \"COBRO\" WHERE \"idCobro\"=?", String.class, alta.idCobro()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM \"MOVIMIENTO_CAJA\" WHERE \"idCobroAnulado\"=? AND \"tipoMovimiento\"='EGRESO'",
                Integer.class, alta.idCobro()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM \"ORIGEN_MOVIMIENTO_CUENTA\" WHERE \"idCobroAnulado\"=?",
                Integer.class, alta.idCobro()));
    }

    /**
     * T3-A2: Rechazo de anulacion de Cobro REPARTO (D7-A) y de Cobro manual con reserva activa
     * en IMP-B (DC05-07-A) via CobroManualService y L02 directa, con rollback integro.
     */
    @Test
    void t3A2_RechazoAnulacionRepartoYConReservasActivasConRollbackIntegro() {
        ContextoFinanzas ctx = crearContextoFinanzas();

        long pedRep = cargarPedidoService.cargarPedido(ctx.sesionVendedor(), ctx.fixture().datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, ctx.idRepartidor(), "REC-T3-D7A");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedRep, ctx.idRepartidor(), ctx.idVendedor(), recorrido);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedRep, ctx.idCliente(), ctx.idRepartidor(), recorrido,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");
        var cierre = tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                jdbc, recorrido, ctx.idAdmin(), UUID.randomUUID(), "v1", "h-t3-d7a",
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                List.of(new GfisLabSupport.AplicacionPlan(ctx.idCliente(), pedRep, new BigDecimal("100.00"))),
                true, false));
        assertNotNull(cierre);
        Long idCobroReparto = jdbc.queryForObject(
                "SELECT \"idCobro\" FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Long.class, cierre.idCierre());
        assertNotNull(idCobroReparto);

        UUID sesionAdmin = ctx.sesionAdmin();
        long idAdmin = ctx.idAdmin();
        assertThrows(IllegalArgumentException.class,
                () -> anularViaServicio(sesionAdmin, idCobroReparto, "Intento ilegal REPARTO"));
        DataAccessException exD7aSql = assertThrows(DataAccessException.class,
                () -> anularDirectoSqlL02(idCobroReparto, idAdmin));
        assertTrue(exD7aSql.getMessage().contains("D7-A"));

        long pedRes = cargarPedidoService.cargarPedido(ctx.sesionVendedor(), ctx.fixture().datos()).idPedido();
        var cobroConReserva = cobroManualService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), pedRes, new BigDecimal("80.00"), "EFECTIVO", UUID.randomUUID());
        long idCobroConReserva = cobroConReserva.idCobro();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","idCierreRecorrido","tipoEvento",
                    "importe","idUsuarioOperador","fechaHoraEvento","observacion")
                VALUES (?,?,?,NULL,'RESERVA_ANTICIPO',80.00,?,clock_timestamp(),'Reserva activa T3')
                """, ctx.idCliente(), idCobroConReserva, pedRes, ctx.idVendedor()));

        int movCuentaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CUENTA\"", Integer.class);
        int movCajaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class);

        DataAccessException exReserva = assertThrows(DataAccessException.class,
                () -> anularViaServicio(sesionAdmin, idCobroConReserva, "Intento con reserva activa"));
        assertTrue(exReserva.getMessage().contains("DC05-07-A"));
        assertEquals("CONFIRMADO", jdbc.queryForObject(
                "SELECT \"estadoCobro\" FROM \"COBRO\" WHERE \"idCobro\"=?", String.class, idCobroConReserva));
        assertEquals(movCuentaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CUENTA\"", Integer.class));
        assertEquals(movCajaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class));
    }

    /**
     * T3-A3: Concurrencia real de servicios legacy contra Flyway V4 sin colisiones 23505 ni 40P01.
     */
    @Test
    void t3A3_ConcurrenciaLegacyCobroCajaYAnulacionBajoFlywayV4SinColisionesNiDeadlock() throws Exception {
        ContextoFinanzas ctx = crearContextoFinanzas();
        var cobroAAnular = cobroManualService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null, new BigDecimal("50.00"), "EFECTIVO", UUID.randomUUID());

        int hilos = 4;
        CountDownLatch listos = new CountDownLatch(hilos);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(hilos)) {
            Future<String> fCobro1 = pool.submit(() -> ejecutarConBarrera(listos, largada, () -> {
                cobroManualService.registrarCobro(ctx.sesionVendedor(), ctx.idCliente(), null,
                        new BigDecimal("25.00"), "EFECTIVO", UUID.randomUUID());
                return "OK:COBRO1";
            }));
            Future<String> fCobro2 = pool.submit(() -> ejecutarConBarrera(listos, largada, () -> {
                cobroManualService.registrarCobro(ctx.sesionVendedor(), ctx.idCliente(), null,
                        new BigDecimal("35.00"), "TRANSFERENCIA", UUID.randomUUID());
                return "OK:COBRO2";
            }));
            Future<String> fCaja = pool.submit(() -> ejecutarConBarrera(listos, largada, () -> {
                cajaService.registrarMovimientoCaja(ctx.sesionAdmin(), "INGRESO",
                        new BigDecimal("15.00"), "Caja concurrente T3", null, UUID.randomUUID());
                return "OK:CAJA";
            }));
            Future<String> fAnul = pool.submit(() -> ejecutarConBarrera(listos, largada, () -> {
                cobroManualService.anularCobroManual(ctx.sesionAdmin(), cobroAAnular.idCobro(), "Anulacion concurrente");
                return "OK:ANUL";
            }));

            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            List<String> resultados = List.of(
                    fCobro1.get(20, TimeUnit.SECONDS),
                    fCobro2.get(20, TimeUnit.SECONDS),
                    fCaja.get(20, TimeUnit.SECONDS),
                    fAnul.get(20, TimeUnit.SECONDS));

            assertEquals(List.of("OK:COBRO1", "OK:COBRO2", "OK:CAJA", "OK:ANUL"), resultados);
            assertEquals("ANULADO", jdbc.queryForObject(
                    "SELECT \"estadoCobro\" FROM \"COBRO\" WHERE \"idCobro\"=?", String.class, cobroAAnular.idCobro()));
            BigDecimal saldoFinal = jdbc.queryForObject("""
                    SELECT "saldoResultante" FROM "MOVIMIENTO_CUENTA"
                     WHERE "idCliente" = ? ORDER BY "numeroSecuenciaCliente" DESC LIMIT 1
                    """, BigDecimal.class, ctx.idCliente());
            assertEquals(0, new BigDecimal("-60.00").compareTo(saldoFinal));
        }
    }

    /**
     * T4-B1: Ataque real de secuestro de search_path (tablas sombra USUARIO y USUARIO_ROL en pg_temp
     * y en public atribuyendo rol ADMINISTRADOR a un Repartidor) ejecutado directamente contra las
     * funciones reales de Flyway V4 (fn_mr3_anular_cobro_manual y tg_gfis_validar_admin_y_cierre).
     */
    @Test
    void t4B1_FuncionesRealesV4DerrotanObjetosSombraEnPgTempYPublic() throws Exception {
        ContextoFinanzas ctx = crearContextoFinanzas();
        var cobro = cobroManualService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null, new BigDecimal("70.00"), "EFECTIVO", UUID.randomUUID());
        long idRecorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, ctx.idRepartidor(), "REC-T4-SHADOW");

        tx.executeWithoutResult(s -> {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS public."USUARIO_ROL" (
                        "idUsuario" BIGINT,
                        "codigoRol" VARCHAR(40)
                    )
                    """);
            jdbc.update("INSERT INTO public.\"USUARIO_ROL\" VALUES (?,'ADMINISTRADOR')", ctx.idRepartidor());
        });

        try (Connection conn = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = conn.createStatement()) {
            // Inyectar tablas sombra en pg_temp otorgando ADMINISTRADOR al Repartidor y forzar search_path hostil
            st.execute("CREATE TEMP TABLE \"USUARIO_ROL\" (\"idUsuario\" BIGINT, \"codigoRol\" VARCHAR(40))");
            st.execute("INSERT INTO pg_temp.\"USUARIO_ROL\" VALUES (" + ctx.idRepartidor() + ", 'ADMINISTRADOR')");
            st.execute("SET search_path = pg_temp, public, ale_gestion");

            // 1) fn_mr3_anular_cobro_manual (L02 en V4) DEBE rechazar al Repartidor pese a pg_temp y public
            SQLException exL02 = assertThrows(SQLException.class, () -> st.execute(
                    "SELECT ale_gestion.\"fn_mr3_anular_cobro_manual\"("
                            + cobro.idCobro() + ", " + ctx.idRepartidor()
                            + ", 9900001, 9900002, 'Intento secuestro search_path')"));
            assertTrue(exL02.getMessage().contains("Solo Administrador habilitado"));

            // 2) tg_gfis_validar_admin_y_cierre (en V4) DEBE rechazar al Repartidor pese a pg_temp y public
            String sqlInsertCierre = "INSERT INTO ale_gestion.\"CIERRE_RECORRIDO_PE2\" ("
                    + "\"idRecorrido\",\"claveIdempotenciaCierre\",\"versionHuella\",\"huellaCanonica\","
                    + "\"idUsuarioAdministrador\",\"fechaHoraRegistro\",\"estadoCierre\",\"medioPagoPermitido\","
                    + "\"importeEsperado\",\"importeEfectivoRecibido\",\"importeImputadoPropuesto\","
                    + "\"importeImputadoConfirmado\",\"importeDiferenciaFaltante\",\"importeCustodiaNoImputada\","
                    + "\"reciboHistoricoJson\") VALUES ("
                    + idRecorrido + ",'" + UUID.randomUUID() + "','v1','h-shadow',"
                    + ctx.idRepartidor() + ",clock_timestamp(),'CONFIRMADO','EFECTIVO',0,0,0,0,0,0,'{}'::jsonb)";
            SQLException exCierre = assertThrows(SQLException.class, () -> st.executeUpdate(sqlInsertCierre));
            assertTrue(exCierre.getMessage().contains("PE-2/RBAC"));
        }
    }

    /**
     * T4-B2: Rol PostgreSQL real NO-OWNER y NO-SUPERUSER:
     * 1) Antes de GRANT explicito, ADAPT-03 (REVOKE ALL ... FROM PUBLIC) rechaza SELECT/INSERT/EXECUTE con 42501.
     * 2) Tras GRANT minimo acotado, permite flujo operativo valido y sigue rechazando mutaciones prohibidas con 42501.
     */
    @Test
    void t4B2_RolNoOwnerRechazadoSinGrant42501YOperativoTrasGrantMinimo() throws Exception {
        ContextoFinanzas ctx = crearContextoFinanzas();
        String rol = "gfis_t4_app_role_" + ctx.fixture().base;
        String pass = "FaseC2026#Pass";

        tx.executeWithoutResult(s -> {
            jdbc.execute("CREATE ROLE " + rol + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '" + pass + "'");
            jdbc.execute("GRANT USAGE ON SCHEMA ale_gestion TO " + rol);
        });

        // 1) Sin GRANT sobre objetos V4: ADAPT-03 bloquea acceso por defecto con 42501
        try (Connection sinPermisos = DriverManager.getConnection(db.getJdbcUrl(), rol, pass);
             Statement st = sinPermisos.createStatement()) {
            SQLException exTabla = assertThrows(SQLException.class,
                    () -> st.executeQuery("SELECT count(*) FROM ale_gestion.\"RECORRIDO_REPARTO\""));
            assertEquals("42501", exTabla.getSQLState());

            SQLException exVista = assertThrows(SQLException.class,
                    () -> st.executeQuery("SELECT count(*) FROM ale_gestion.\"VW_IMP_A_DISPONIBILIDAD_COBRO\""));
            assertEquals("42501", exVista.getSQLState());

            SQLException exFunc = assertThrows(SQLException.class,
                    () -> st.executeQuery("SELECT ale_gestion.\"fn_gfis_advisory_lock_key\"(1)"));
            assertEquals("42501", exFunc.getSQLState());
        }

        // 2) Otorgar privilegios minimos operativos
        tx.executeWithoutResult(s -> {
            jdbc.execute("GRANT SELECT ON ale_gestion.\"USUARIO\", ale_gestion.\"USUARIO_ROL\", "
                    + "ale_gestion.\"VW_IMP_A_DISPONIBILIDAD_COBRO\" TO " + rol);
            jdbc.execute("GRANT SELECT, INSERT, UPDATE ON ale_gestion.\"RECORRIDO_REPARTO\" TO " + rol);
            jdbc.execute("GRANT SELECT, INSERT ON ale_gestion.\"EVENTO_IMPUTACION_FINANCIERA\" TO " + rol);
            jdbc.execute("GRANT USAGE, SELECT ON SEQUENCE ale_gestion.\"seq_gfis_recorrido\" TO " + rol);
            jdbc.execute("GRANT EXECUTE ON FUNCTION ale_gestion.\"fn_gfis_advisory_lock_key\"(BIGINT) TO " + rol);
        });

        try (Connection appConn = DriverManager.getConnection(db.getJdbcUrl(), rol, pass);
             Statement st = appConn.createStatement()) {
            assertTrue(consultarLong(st, "SELECT count(*) FROM ale_gestion.\"VW_IMP_A_DISPONIBILIDAD_COBRO\"") >= 0);
            long hashKey = consultarLong(st, "SELECT ale_gestion.\"fn_gfis_advisory_lock_key\"(42)::bigint");
            assertEquals(consultarLong(st, "SELECT pg_catalog.hashtext('42')::bigint"), hashKey);
            long idRec = consultarLong(st,
                    "INSERT INTO ale_gestion.\"RECORRIDO_REPARTO\" "
                            + "(\"idUsuarioRepartidor\",\"fechaHoraCreacion\",\"estadoOperativo\",\"referenciaOperativa\") "
                            + "VALUES (" + ctx.idRepartidor() + ", clock_timestamp(), 'ABIERTO', 'REC-T4-NONOWNER') "
                            + "RETURNING \"idRecorrido\"");
            assertTrue(idRec > 0);

            // Mutaciones prohibidas siguen rechazadas con 42501
            SQLException exDelRec = assertThrows(SQLException.class,
                    () -> st.executeUpdate("DELETE FROM ale_gestion.\"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=" + idRec));
            assertEquals("42501", exDelRec.getSQLState());

            SQLException exUpdImp = assertThrows(SQLException.class,
                    () -> st.executeUpdate("UPDATE ale_gestion.\"EVENTO_IMPUTACION_FINANCIERA\" SET \"importe\"=1 WHERE 1=0"));
            assertEquals("42501", exUpdImp.getSQLState());

            SQLException exDelImp = assertThrows(SQLException.class,
                    () -> st.executeUpdate("DELETE FROM ale_gestion.\"EVENTO_IMPUTACION_FINANCIERA\" WHERE 1=0"));
            assertEquals("42501", exDelImp.getSQLState());
        }
    }

    private void anularViaServicio(UUID sesionAdmin, long idCobro, String motivo) {
        cobroManualService.anularCobroManual(sesionAdmin, idCobro, motivo);
    }

    private void anularDirectoSqlL02(long idCobro, long idAdmin) {
        tx.executeWithoutResult(s -> {
            long idMovCuenta = GfisLabSupport.siguientePkMovCuentaB2(jdbc);
            long idMovCaja = GfisLabSupport.siguientePkMovCajaB2(jdbc);
            jdbc.queryForList("SELECT \"fn_mr3_anular_cobro_manual\"(?,?,?,?,?)",
                    idCobro, idAdmin, idMovCuenta, idMovCaja, "Intento SQL directo D7-A");
        });
    }

    @FunctionalInterface
    private interface AccionLab {
        String run();
    }

    private static String ejecutarConBarrera(CountDownLatch listos, CountDownLatch largada, AccionLab accion)
            throws InterruptedException {
        listos.countDown();
        assertTrue(largada.await(5, TimeUnit.SECONDS));
        try {
            return accion.run();
        } catch (Exception ex) {
            return "ERR:" + ex.getClass().getSimpleName() + ":" + ex.getMessage();
        }
    }

    private static long consultarLong(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
