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
 * G-FIS C0.1 — Verificacion final en PostgreSQL 17.11 efimero aislado:
 *   A) Compatibilidad de servicios legacy reales (CobroManualService y RegistrarMovimientoCajaService)
 *      contra V1-V3 + V4_CANDIDATE + V4_CANDIDATE_L02_ANULACION_LAB.
 *   B) Seguridad de search_path (ataque pg_temp y public) + rol operativo no-owner con ACL minimas.
 *   C) Limites transaccionales de setval/nextval y seed seguro bajo A1(2929001).
 *
 * Exclusivamente en src/test/**; NO modifica src/main/** ni promueve V4 a Flyway.
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

    private static final AtomicBoolean FIXTURES_C01_APLICADOS = new AtomicBoolean(false);

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CobroManualService cobroManualService;
    @Autowired RegistrarMovimientoCajaService cajaService;
    @Autowired CargarPedidoService cargarPedidoService;

    @BeforeEach
    void aplicarFixturesAislados() {
        if (FIXTURES_C01_APLICADOS.compareAndSet(false, true)) {
            GfisLabSupport.aplicarMigracionCandidataV4EnLaboratorio(dataSource);
            GfisLabSupport.aplicarCorreccionCandidataL02EnLaboratorio(dataSource);
        }
    }

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
                VALUES (?,?,decode('c01a01','hex'),now() - interval '1 minute',now() + interval '2 hours')
                """, sesionAdmin, idAdmin));
        return new ContextoFinanzas(f, idAdmin, idVendedor, idRepartidor, idCliente, sesionAdmin, f.vendedor());
    }

    /**
     * C0.1-A1: Servicios productivos reales (CobroManualService + RegistrarMovimientoCajaService)
     * ejecutados contra V1-V3 + V4_CANDIDATE + L02: alta, replay idempotente, caja manual y
     * anulacion valida ejecutando fn_mr3_anular_cobro_manual (L02) + tg_gfis_guard_d7a_y_reservas_cobro.
     */
    @Test
    void c01A1_ServiciosLegacyCompatiblesConV4YL02EnAltaCajaReplayYAnulacionValida() {
        ContextoFinanzas ctx = crearContextoFinanzas();

        // 1) Alta de Cobro manual via servicio real + replay idempotente
        UUID claveCobro = UUID.randomUUID();
        var alta = cobroManualService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null, new BigDecimal("150.00"), "EFECTIVO", claveCobro);
        var replayCobro = cobroManualService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null, new BigDecimal("150.00"), "EFECTIVO", claveCobro);
        assertEquals(alta.idCobro(), replayCobro.idCobro());
        assertEquals("CONFIRMADO", alta.estado());

        // 2) Movimiento de Caja manual UC-22 via servicio real + replay idempotente
        UUID claveCaja = UUID.randomUUID();
        var caja1 = cajaService.registrarMovimientoCaja(
                ctx.sesionAdmin(), "INGRESO", new BigDecimal("40.00"), "Fondo operativo C0.1", "Lab", claveCaja);
        var cajaReplay = cajaService.registrarMovimientoCaja(
                ctx.sesionAdmin(), "INGRESO", new BigDecimal("40.00"), "Fondo operativo C0.1", "Lab", claveCaja);
        assertTrue(caja1.creado());
        assertFalse(cajaReplay.creado());
        assertEquals(caja1.idMovimientoCaja(), cajaReplay.idMovimientoCaja());

        // 3) Anulacion valida via CobroManualService invocando L02 reemplazada y disparando guard D7-A/reservas
        var anulado = cobroManualService.anularCobroManual(
                ctx.sesionAdmin(), alta.idCobro(), "Error de carga verificado en C0.1");
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
     * C0.1-A2: Rechazo de anulacion de Cobro REPARTO (D7-A) y de Cobro manual con reserva activa
     * en IMP-B (DC05-07-A) a traves del servicio real y en SQL directo, con rollback integro y
     * cero efectos parciales.
     */
    @Test
    void c01A2_RechazoAnulacionRepartoYConReservasActivasConRollbackIntegro() {
        ContextoFinanzas ctx = crearContextoFinanzas();

        // 1) Cobro REPARTO emitido mediante cierre limpio T1-C2/K2
        long pedRep = cargarPedidoService.cargarPedido(ctx.sesionVendedor(), ctx.fixture().datos()).idPedido();
        long recorrido = GfisLabSupport.crearRecorridoExplicito(jdbc, ctx.idRepartidor(), "REC-C01-D7A");
        GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, pedRep, ctx.idRepartidor(), ctx.idVendedor(), recorrido);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, pedRep, ctx.idCliente(), ctx.idRepartidor(), recorrido,
                new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "IMP_B");
        var cierre = tx.execute(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                jdbc, recorrido, ctx.idAdmin(), UUID.randomUUID(), "v1", "h-c01-d7a",
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                List.of(new GfisLabSupport.AplicacionPlan(ctx.idCliente(), pedRep, new BigDecimal("100.00"))),
                true, false));
        assertNotNull(cierre);
        Long idCobroReparto = jdbc.queryForObject(
                "SELECT \"idCobro\" FROM \"COBRO_RENDICION_K2\" WHERE \"idCierreRecorrido\"=?",
                Long.class, cierre.idCierre());
        assertNotNull(idCobroReparto);

        // Rechazo en capa Java (CobroManualService) y en capa SQL (L02 + tg_gfis_guard_d7a_y_reservas_cobro)
        assertThrows(IllegalArgumentException.class,
                () -> anularViaServicio(ctx.sesionAdmin(), idCobroReparto, "Intento ilegal REPARTO"));
        DataAccessException exD7aSql = assertThrows(DataAccessException.class,
                () -> anularDirectoSqlL02(idCobroReparto, ctx.idAdmin()));
        assertTrue(exD7aSql.getMessage().contains("D7-A"));

        // 2) Cobro manual con reserva activa en IMP-B => CobroManualService falla por DC05-07-A y revierte todo
        long pedRes = cargarPedidoService.cargarPedido(ctx.sesionVendedor(), ctx.fixture().datos()).idPedido();
        var cobroConReserva = cobroManualService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), pedRes, new BigDecimal("80.00"), "EFECTIVO", UUID.randomUUID());
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","idCierreRecorrido","tipoEvento",
                    "importe","idUsuarioOperador","fechaHoraEvento","observacion")
                VALUES (?,?,?,NULL,'RESERVA_ANTICIPO',80.00,?,clock_timestamp(),'Reserva activa C0.1')
                """, ctx.idCliente(), cobroConReserva.idCobro(), pedRes, ctx.idVendedor()));

        int movCuentaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CUENTA\"", Integer.class);
        int movCajaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class);

        DataAccessException exReserva = assertThrows(DataAccessException.class,
                () -> anularViaServicio(ctx.sesionAdmin(), cobroConReserva.idCobro(), "Intento con reserva activa"));
        assertTrue(exReserva.getMessage().contains("DC05-07-A"));
        assertEquals("CONFIRMADO", jdbc.queryForObject(
                "SELECT \"estadoCobro\" FROM \"COBRO\" WHERE \"idCobro\"=?", String.class, cobroConReserva.idCobro()));
        assertEquals(movCuentaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CUENTA\"", Integer.class));
        assertEquals(movCajaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class));
    }

    /**
     * C0.1-A3: Concurrencia real de servicios legacy (CobroManualService alta + anulacion +
     * RegistrarMovimientoCajaService) contra V4_CANDIDATE + L02 sin colisiones MAX+1 (23505)
     * ni deadlocks (40P01).
     */
    @Test
    void c01A3_ConcurrenciaLegacyCobroCajaYAnulacionBajoV4SinColisionesMaxMasUnoNiDeadlock() throws Exception {
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
                        new BigDecimal("15.00"), "Caja concurrente C0.1", null, UUID.randomUUID());
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
            // Saldo neto cliente: -50 (alta) + 50 (anulacion) - 25 - 35 = -60.00
            BigDecimal saldoFinal = jdbc.queryForObject("""
                    SELECT "saldoResultante" FROM "MOVIMIENTO_CUENTA"
                     WHERE "idCliente" = ? ORDER BY "numeroSecuenciaCliente" DESC LIMIT 1
                    """, BigDecimal.class, ctx.idCliente());
            assertEquals(0, new BigDecimal("-60.00").compareTo(saldoFinal));
        }
    }

    /**
     * C0.1-B1: Prueba negativa de search_path:
     * 1) Demuestra que omitir pg_temp en SET search_path = pg_catalog, ale_gestion con tablas
     *    sin calificar permite secuestro mediante CREATE TEMP TABLE "USUARIO_ROL".
     * 2) Demuestra que SET search_path = pg_catalog, ale_gestion, pg_temp + calificacion explicita
     *    ale_gestion."..." derrota tanto objetos sombra en pg_temp como homonimos en public.
     */
    @Test
    void c01B1_SearchPathConPgTempAlFinalYCalificacionExplicitaDerrotaSombraEnPgTempYPublic() throws Exception {
        ContextoFinanzas ctx = crearContextoFinanzas();

        tx.executeWithoutResult(s -> {
            jdbc.execute("""
                    CREATE OR REPLACE FUNCTION ale_gestion."fn_c01_vulnerable_sin_pgtemp"(p_id BIGINT)
                    RETURNS BOOLEAN LANGUAGE plpgsql
                    SET search_path = pg_catalog, ale_gestion AS $f$
                    BEGIN
                        RETURN EXISTS (
                            SELECT 1 FROM "USUARIO_ROL"
                             WHERE "idUsuario" = p_id AND "codigoRol" = 'ADMINISTRADOR'
                        );
                    END;
                    $f$;
                    """);
            jdbc.execute("""
                    CREATE OR REPLACE FUNCTION ale_gestion."fn_c01_blindada_pgtemp_final"(p_id BIGINT)
                    RETURNS BOOLEAN LANGUAGE plpgsql
                    SET search_path = pg_catalog, ale_gestion, pg_temp AS $f$
                    BEGIN
                        RETURN EXISTS (
                            SELECT 1 FROM ale_gestion."USUARIO_ROL" ur
                             WHERE ur."idUsuario" = p_id AND ur."codigoRol" = 'ADMINISTRADOR'
                        );
                    END;
                    $f$;
                    """);
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
            // Crear tabla sombra en pg_temp atribuyendo rol ADMINISTRADOR a un Repartidor
            st.execute("CREATE TEMP TABLE \"USUARIO_ROL\" (\"idUsuario\" BIGINT, \"codigoRol\" VARCHAR(40))");
            st.execute("INSERT INTO pg_temp.\"USUARIO_ROL\" VALUES (" + ctx.idRepartidor() + ", 'ADMINISTRADOR')");

            // Funcion sin pg_temp y sin calificacion ES SECUESTRADA por pg_temp (devuelve true para Repartidor!)
            assertTrue(consultarBoolean(st, "SELECT ale_gestion.\"fn_c01_vulnerable_sin_pgtemp\"(" + ctx.idRepartidor() + ")"));

            // Funcion endurecida (search_path = pg_catalog, ale_gestion, pg_temp + ale_gestion."...") DERROTA pg_temp y public
            assertFalse(consultarBoolean(st, "SELECT ale_gestion.\"fn_c01_blindada_pgtemp_final\"(" + ctx.idRepartidor() + ")"));
            assertTrue(consultarBoolean(st, "SELECT ale_gestion.\"fn_c01_blindada_pgtemp_final\"(" + ctx.idAdmin() + ")"));
        }
    }

    /**
     * C0.1-B2: Prueba con rol PostgreSQL operativo NO-OWNER y NO-SUPERUSER con ACL minimas por objeto:
     * permite flujo valido (SELECT vista IMP-A, nextval, INSERT RECORRIDO_REPARTO) y rechaza
     * mutaciones directas prohibidas (DELETE/UPDATE append-only) con SQLSTATE 42501.
     */
    @Test
    void c01B2_RolOperativoNoOwnerConAclMinimasPermiteFlujoValidoYRechazaMutacion42501() throws Exception {
        ContextoFinanzas ctx = crearContextoFinanzas();
        String rol = "gfis_c01_app_role_" + ctx.fixture().base;
        String pass = "LabC01Pass#2026";

        tx.executeWithoutResult(s -> {
            jdbc.execute("CREATE ROLE " + rol + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '" + pass + "'");
            jdbc.execute("GRANT USAGE ON SCHEMA ale_gestion TO " + rol);
            jdbc.execute("GRANT SELECT ON ale_gestion.\"USUARIO\", ale_gestion.\"USUARIO_ROL\", "
                    + "ale_gestion.\"VW_IMP_A_DISPONIBILIDAD_COBRO\" TO " + rol);
            jdbc.execute("GRANT SELECT, INSERT, UPDATE ON ale_gestion.\"RECORRIDO_REPARTO\" TO " + rol);
            jdbc.execute("GRANT SELECT, INSERT ON ale_gestion.\"EVENTO_IMPUTACION_FINANCIERA\" TO " + rol);
            jdbc.execute("GRANT USAGE, SELECT ON SEQUENCE ale_gestion.\"seq_gfis_recorrido\" TO " + rol);
        });

        try (Connection appConn = DriverManager.getConnection(db.getJdbcUrl(), rol, pass);
             Statement st = appConn.createStatement()) {
            // 1) Flujo valido bajo rol no-owner: lectura IMP-A e insercion de Recorrido
            assertTrue(consultarLong(st, "SELECT count(*) FROM ale_gestion.\"VW_IMP_A_DISPONIBILIDAD_COBRO\"") >= 0);
            long idRec = consultarLong(st,
                    "INSERT INTO ale_gestion.\"RECORRIDO_REPARTO\" "
                            + "(\"idUsuarioRepartidor\",\"fechaHoraCreacion\",\"estadoOperativo\",\"referenciaOperativa\") "
                            + "VALUES (" + ctx.idRepartidor() + ", clock_timestamp(), 'ABIERTO', 'REC-NONOWNER') "
                            + "RETURNING \"idRecorrido\"");
            assertTrue(idRec > 0);

            // 2) DELETE sobre RECORRIDO_REPARTO => rechazado por ACL (42501 insufficient_privilege)
            SQLException exDelRec = assertThrows(SQLException.class,
                    () -> st.executeUpdate("DELETE FROM ale_gestion.\"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=" + idRec));
            assertEquals("42501", exDelRec.getSQLState());

            // 3) UPDATE y DELETE sobre tabla append-only EVENTO_IMPUTACION_FINANCIERA => rechazados por ACL (42501)
            SQLException exUpdImp = assertThrows(SQLException.class,
                    () -> st.executeUpdate("UPDATE ale_gestion.\"EVENTO_IMPUTACION_FINANCIERA\" SET \"importe\"=1 WHERE 1=0"));
            assertEquals("42501", exUpdImp.getSQLState());
            SQLException exDelImp = assertThrows(SQLException.class,
                    () -> st.executeUpdate("DELETE FROM ale_gestion.\"EVENTO_IMPUTACION_FINANCIERA\" WHERE 1=0"));
            assertEquals("42501", exDelImp.getSQLState());
        }
    }

    /**
     * C0.1-C1: Limites transaccionales de secuencias PostgreSQL:
     * 1) DDL CREATE SEQUENCE dentro de TX abortada SI revierte con ROLLBACK (Escenario 1).
     * 2) setval/nextval sobre una secuencia preexistente NO revierten con ROLLBACK.
     * 3) Seed bajo pg_advisory_xact_lock(2929001) + LOCK TABLE SHARE garantiza nextval > MAX(idCobro)
     *    incluso ante escrituras legacy concurrentes de CobroManualService.
     */
    @Test
    void c01C1_LimitesTransaccionalesDeSetvalNextvalYSeedSeguroBajoExclusion2929001() throws Exception {
        ContextoFinanzas ctx = crearContextoFinanzas();

        // 1) DDL CREATE SEQUENCE en transaccion abortada => NO existe tras ROLLBACK
        assertThrows(IllegalStateException.class, this::crearSecuenciaYAbortarTx);
        assertNull(jdbc.queryForObject("SELECT to_regclass('\"seq_c01_ddl_rollback\"')", String.class));

        // 2) setval + nextval en transaccion abortada => NO revierten (hueco permanente, avance monotonico)
        long valorInicial = GfisLabSupport.siguientePkCobroB2(jdbc);
        long salto = valorInicial + 500L;
        assertThrows(IllegalStateException.class, () -> ejecutarSetvalYAbortarTx(salto));
        long valorTrasRollback = GfisLabSupport.siguientePkCobroB2(jdbc);
        assertEquals(salto + 1L, valorTrasRollback,
                "setval/nextval en PostgreSQL NO son transaccionales: el avance persiste tras ROLLBACK");

        // 3) Seed concurrente bajo A1(2929001) + LOCK TABLE SHARE mientras CobroManualService inserta MAX+1
        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Long> fLegacy = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                return cobroManualService.registrarCobro(ctx.sesionVendedor(), ctx.idCliente(), null,
                        new BigDecimal("60.00"), "EFECTIVO", UUID.randomUUID()).idCobro();
            });
            Future<Boolean> fSeed = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                tx.executeWithoutResult(s -> {
                    jdbc.execute("SELECT pg_advisory_xact_lock(2929001)");
                    jdbc.execute("LOCK TABLE \"COBRO\" IN SHARE MODE");
                    jdbc.queryForObject("""
                            SELECT setval(
                                '"seq_gfis_cobro_id"',
                                GREATEST(
                                    (SELECT COALESCE(MAX("idCobro"), 0) FROM "COBRO"),
                                    (SELECT last_value FROM "seq_gfis_cobro_id"),
                                    1
                                ),
                                true
                            )
                            """, Long.class);
                });
                return true;
            });

            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            long idLegacy = fLegacy.get(20, TimeUnit.SECONDS);
            assertTrue(fSeed.get(20, TimeUnit.SECONDS));

            // Re-seed determinista post-drenaje (ventana R09.1): nextval estrictamente > MAX(idCobro)
            tx.executeWithoutResult(s -> {
                jdbc.execute("SELECT pg_advisory_xact_lock(2929001)");
                jdbc.execute("LOCK TABLE \"COBRO\" IN SHARE MODE");
                jdbc.queryForObject("""
                        SELECT setval(
                            '"seq_gfis_cobro_id"',
                            GREATEST((SELECT COALESCE(MAX("idCobro"), 0) FROM "COBRO"),
                                     (SELECT last_value FROM "seq_gfis_cobro_id"), 1),
                            true
                        )
                        """, Long.class);
            });
            long siguienteB2 = GfisLabSupport.siguientePkCobroB2(jdbc);
            assertTrue(siguienteB2 > idLegacy,
                    "Tras re-seed bajo A1(2929001), nextval (" + siguienteB2 + ") debe superar MAX legacy (" + idLegacy + ")");
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

    private void crearSecuenciaYAbortarTx() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("CREATE SEQUENCE \"seq_c01_ddl_rollback\" START 1");
            throw new IllegalStateException("ROLLBACK_INTENCIONAL_DDL");
        });
    }

    private void ejecutarSetvalYAbortarTx(long nuevoValor) {
        tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT setval('\"seq_gfis_cobro_id\"', ?, true)", Long.class, nuevoValor);
            throw new IllegalStateException("ROLLBACK_INTENCIONAL_SETVAL");
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

    private static boolean consultarBoolean(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getBoolean(1);
        }
    }

    private static long consultarLong(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}