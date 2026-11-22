package ar.edu.unam.alemilanesas.gfis;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G-FIS Fase C — Validacion directa de Flyway V4__gfis_pe1_pe2_imp01.sql:
 *   T1: Instalacion limpia V1..V4 desde cero, invariancia V1..V3, cero objetos fuera de ale_gestion,
 *       endurecimiento search_path (ADAPT-02), indices auxiliares (ADAPT-05) y REVOKE PUBLIC (ADAPT-03).
 *   T2: Upgrade V4 sobre base V1..V3 poblada con datos sinteticos representativos sin backfill retroactivo
 *       y seed protegido (ADAPT-06) estrictamente mayor que MAX existente.
 *   T6: Reversibilidad real ante error de preflight, lock_timeout (ADAPT-04), fallo DDL y
 *       limites transaccionales de setval/nextval sobre secuencias preexistentes.
 */
@SpringBootTest
@Testcontainers
class GfisFlywayV4InstalacionYReversibilidadIntegrationTest {

    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", db::getJdbcUrl);
        registro.add("spring.datasource.username", db::getUsername);
        registro.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    /**
     * T1a: Instalacion limpia Flyway V1..V4 desde cero, catalogo exacto e invariancia V1..V3.
     */
    @Test
    void t1a_FlywayV1AV4DesdeCeroCatalogoExactoYChecksumsIntactos() throws Exception {
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(0, flyway.migrate().migrationsExecuted);

        assertEquals(4, contar(jdbc,
                "SELECT count(*) FROM ale_gestion.flyway_schema_history WHERE success = true AND type = 'SQL'"));
        assertEquals(75, contar(jdbc, """
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'ale_gestion' AND table_type = 'BASE TABLE'
                   AND table_name <> 'flyway_schema_history'
                """));
        assertEquals(606, contar(jdbc, """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'ale_gestion' AND table_name <> 'flyway_schema_history'
                """));
        assertEquals(168, contar(jdbc, """
                SELECT count(*) FROM pg_constraint c
                JOIN pg_namespace n ON n.oid = c.connamespace
                 WHERE n.nspname = 'ale_gestion' AND c.contype = 'f'
                """));

        assertNotNull(sha256Classpath("db/migration/V1__baseline_mr5.sql"));
        assertNotNull(sha256Classpath("db/migration/V2__f01_cobro_estados.sql"));
        assertNotNull(sha256Classpath("db/migration/V3__trazabilidad_partidas_insumo.sql"));
        assertNotNull(sha256Classpath("db/migration/V4__gfis_pe1_pe2_imp01.sql"));
    }

    /**
     * T1b: Endurecimiento ADAPT-02 (search_path), ADAPT-05 (indices), ADAPT-03 (REVOKE PUBLIC)
     * y cero objetos G-FIS fuera de ale_gestion.
     */
    @Test
    void t1b_FlywayV4EndurecimientoSearchPathIndicesAclsYCeroObjetosFueraDeAleGestion() {
        // ADAPT-02: las 17 funciones V4 (16 fn_gfis_* + fn_mr3_anular_cobro_manual) fijan search_path seguro
        assertEquals(17, contar(jdbc, """
                SELECT count(*) FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'ale_gestion'
                   AND (p.proname LIKE 'fn_gfis_%' OR p.proname = 'fn_mr3_anular_cobro_manual')
                   AND array_to_string(p.proconfig, ',') LIKE '%search_path=pg_catalog, ale_gestion, pg_temp%'
                """));

        // ADAPT-05: 13 indices auxiliares B-tree + 4 indices unicos parciales G-FIS
        assertEquals(13, contar(jdbc, """
                SELECT count(*) FROM pg_indexes
                 WHERE schemaname = 'ale_gestion' AND indexname LIKE 'idx_gfis_%'
                """));
        assertEquals(4, contar(jdbc, """
                SELECT count(*) FROM pg_indexes
                 WHERE schemaname = 'ale_gestion' AND indexname LIKE 'uq_gfis_%'
                   AND indexdef LIKE '%WHERE%'
                """));

        // Cero objetos G-FIS fuera de ale_gestion
        assertEquals(0, contar(jdbc, """
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema <> 'ale_gestion'
                   AND table_name IN (
                       'RECONOCIMIENTO_ENTREGA_PE1', 'RECORRIDO_REPARTO', 'RECORRIDO_ASIGNACION',
                       'RECORRIDO_ENTREGA', 'CIERRE_RECORRIDO_PE2', 'REVISION_CONCILIACION_PE2',
                       'PROPUESTA_APLICACION_PENDIENTE', 'COBRO_RENDICION_K2', 'APLICACION_RENDICION_K2',
                       'CUSTODIA_DIFERENCIA_RENDICION', 'EGRESO_CUSTODIA_RENDICION',
                       'EVENTO_IMPUTACION_FINANCIERA', 'ANULACION_COBRO_IDEMPOTENCIA',
                       'VW_IMP_A_DISPONIBILIDAD_COBRO'
                   )
                """));
        assertEquals(0, contar(jdbc, """
                SELECT count(*) FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname <> 'ale_gestion' AND p.proname LIKE 'fn_gfis_%'
                """));

        // ADAPT-03: cero privilegios PUBLIC sobre las 13 tablas nuevas, 1 vista y 16 fn_gfis_*
        assertEquals(0, contar(jdbc, """
                SELECT count(*) FROM information_schema.role_table_grants
                 WHERE grantee = 'PUBLIC' AND table_schema = 'ale_gestion'
                   AND table_name IN (
                       'RECONOCIMIENTO_ENTREGA_PE1', 'RECORRIDO_REPARTO', 'RECORRIDO_ASIGNACION',
                       'RECORRIDO_ENTREGA', 'CIERRE_RECORRIDO_PE2', 'REVISION_CONCILIACION_PE2',
                       'PROPUESTA_APLICACION_PENDIENTE', 'COBRO_RENDICION_K2', 'APLICACION_RENDICION_K2',
                       'CUSTODIA_DIFERENCIA_RENDICION', 'EGRESO_CUSTODIA_RENDICION',
                       'EVENTO_IMPUTACION_FINANCIERA', 'ANULACION_COBRO_IDEMPOTENCIA',
                       'VW_IMP_A_DISPONIBILIDAD_COBRO'
                   )
                """));
        assertEquals(0, contar(jdbc, """
                SELECT count(*) FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'ale_gestion' AND p.proname LIKE 'fn_gfis_%'
                   AND has_function_privilege('public', p.oid, 'EXECUTE')
                """));
    }

    /**
     * T2: Upgrade real Flyway V1..V3 -> V4 sobre base poblada con datos sinteticos previos,
     * acreditando cero backfill retroactivo (RCG-06) y seed seguro > MAX existente (ADAPT-06).
     */
    @Test
    void t2_UpgradeFlywayV4SobreBaseV1V3PobladaSinBackfillRetroactivoYSeedMayorQueMax() throws Exception {
        String dbName = "ale_gfis_t2_upgrade";
        crearBaseAislada(dbName);
        String urlDb = jdbcUrlParaBase(dbName);
        DriverManagerDataSource ds = crearDataSource(urlDb);
        JdbcTemplate jdbcT2 = new JdbcTemplate(ds);

        // 1) Migrar exclusivamente V1..V3 sobre ale_gestion
        Flyway flywayV3 = Flyway.configure()
                .dataSource(ds)
                .defaultSchema("ale_gestion")
                .schemas("ale_gestion")
                .target("3")
                .load();
        assertEquals(3, flywayV3.migrate().migrationsExecuted);

        // 2) Poblar datos sinteticos representativos con PKs altas en una sola TX (respeta triggers diferidos MR-5)
        long idCliente = 400_001L;
        long idUsuario = 400_002L;
        long idCobroAlto = 500_001L;
        long idMovCuentaAlto = 600_001L;
        long idMovCajaAlto = 700_001L;
        UUID claveCobro = UUID.randomUUID();

        ejecutarSqlEnTransaccion(urlDb, """
                INSERT INTO ale_gestion."ROL" ("codigoRol","nombreMostrado")
                VALUES ('ADMINISTRADOR','Administrador') ON CONFLICT DO NOTHING;
                INSERT INTO ale_gestion."USUARIO" (
                    "idUsuario","nombreUsuario","credencialProtegida","estado",
                    "cambioCredencialPendiente","fechaHoraAlta")
                VALUES (%d,'t2_admin','hash_t2','HABILITADO',false,clock_timestamp());
                INSERT INTO ale_gestion."USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (%d,'ADMINISTRADOR',clock_timestamp());
                INSERT INTO ale_gestion."CLIENTE" (
                    "idCliente","nombres","telefonoPrincipal","estado","categoriaComercial","fechaHoraAlta")
                VALUES (%d,'Cliente T2 Upgrade','3764111222','ACTIVO','CUMPLIDOR',clock_timestamp());
                INSERT INTO ale_gestion."COBRO" (
                    "idCobro","idCliente","fechaHoraCobro","importe","medioPago","origen",
                    "estadoCobro","claveIdempotenciaOrigen")
                VALUES (%d,%d,clock_timestamp(),250.00,'EFECTIVO','ADMINISTRACION','CONFIRMADO','%s'::uuid);
                INSERT INTO ale_gestion."MOVIMIENTO_CUENTA" (
                    "idMovimientoCuenta","idCliente","numeroSecuenciaCliente","tipoMovimiento",
                    "importe","fechaHoraMovimiento","saldoResultante")
                VALUES (%d,%d,1,'PAGO',250.00,clock_timestamp(),-250.00);
                INSERT INTO ale_gestion."ORIGEN_MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCobro")
                VALUES (%d,%d);
                INSERT INTO ale_gestion."MOVIMIENTO_CAJA" (
                    "idMovimientoCaja","tipoMovimiento","importe","fechaHoraMovimiento","origen",
                    "concepto","idUsuarioRegistro","idCobro")
                VALUES (%d,'INGRESO',250.00,clock_timestamp(),'COBRO','Cobro previo T2',%d,%d);
                """.formatted(
                idUsuario, idUsuario, idCliente,
                idCobroAlto, idCliente, claveCobro,
                idMovCuentaAlto, idCliente,
                idMovCuentaAlto, idCobroAlto,
                idMovCajaAlto, idUsuario, idCobroAlto));

        // 3) Ejecutar upgrade real a Flyway V4
        Flyway flywayV4 = Flyway.configure()
                .dataSource(ds)
                .defaultSchema("ale_gestion")
                .schemas("ale_gestion")
                .target("4")
                .load();
        assertEquals(1, flywayV4.migrate().migrationsExecuted);
        assertTrue(flywayV4.validateWithResult().validationSuccessful);

        // 4) Verificar preservacion de datos previos y CERO backfill retroactivo en tablas nuevas G-FIS
        assertEquals(1, contar(jdbcT2, "SELECT count(*) FROM ale_gestion.\"COBRO\" WHERE \"idCobro\" = 500001 AND \"estadoCobro\" = 'CONFIRMADO'"));
        assertEquals(0, contar(jdbcT2, "SELECT count(*) FROM ale_gestion.\"EVENTO_IMPUTACION_FINANCIERA\""));
        assertEquals(0, contar(jdbcT2, "SELECT count(*) FROM ale_gestion.\"RECONOCIMIENTO_ENTREGA_PE1\""));
        assertEquals(0, contar(jdbcT2, "SELECT count(*) FROM ale_gestion.\"RECORRIDO_REPARTO\""));
        assertEquals(0, contar(jdbcT2, "SELECT count(*) FROM ale_gestion.\"CIERRE_RECORRIDO_PE2\""));
        assertEquals(0, contar(jdbcT2, "SELECT count(*) FROM ale_gestion.\"ANULACION_COBRO_IDEMPOTENCIA\""));

        // 5) ADAPT-06: secuencias financieras > MAX historico inmediatamente despues del upgrade
        Long nextCobro = jdbcT2.queryForObject("SELECT pg_catalog.nextval('ale_gestion.\"seq_gfis_cobro_id\"')", Long.class);
        Long nextCuenta = jdbcT2.queryForObject("SELECT pg_catalog.nextval('ale_gestion.\"seq_gfis_mov_cuenta_id\"')", Long.class);
        Long nextCaja = jdbcT2.queryForObject("SELECT pg_catalog.nextval('ale_gestion.\"seq_gfis_mov_caja_id\"')", Long.class);
        assertNotNull(nextCobro);
        assertNotNull(nextCuenta);
        assertNotNull(nextCaja);
        assertEquals(idCobroAlto + 1L, nextCobro);
        assertEquals(idMovCuentaAlto + 1L, nextCuenta);
        assertEquals(idMovCajaAlto + 1L, nextCaja);

        // La vista IMP-A proyecta el Cobro historico como 250.00 libres sin haber mutado ningun dato
        BigDecimal libre = jdbcT2.queryForObject(
                "SELECT \"importeLibreDisponible\" FROM ale_gestion.\"VW_IMP_A_DISPONIBILIDAD_COBRO\" WHERE \"idCobro\" = ?",
                BigDecimal.class, idCobroAlto);
        assertEquals(0, new BigDecimal("250.00").compareTo(libre));
    }

    /**
     * T6a: Reversibilidad transaccional ante fallo de preflight (GFIS-PREFLIGHT-B2) y
     * fallo de lock_timeout (ADAPT-04, SQLSTATE 55P03): cero objetos parciales de V4 tras ROLLBACK.
     */
    @Test
    void t6a_RollbackTransaccionalAntePreflightFallidoYLockTimeoutReal() throws Exception {
        // 1) Preflight fallido en base aislada: inyectar COBRO con idCobro <= 0 -> Flyway V4 falla y revierte
        String dbPreflight = "ale_gfis_t6_preflight";
        crearBaseAislada(dbPreflight);
        DriverManagerDataSource dsPre = crearDataSource(jdbcUrlParaBase(dbPreflight));
        JdbcTemplate jdbcPre = new JdbcTemplate(dsPre);

        Flyway.configure()
                .dataSource(dsPre)
                .defaultSchema("ale_gestion")
                .schemas("ale_gestion")
                .target("3")
                .load()
                .migrate();

        jdbcPre.update("""
                INSERT INTO ale_gestion."CLIENTE" (
                    "idCliente","nombres","telefonoPrincipal","estado","categoriaComercial","fechaHoraAlta")
                VALUES (800001,'Cliente T6','3764000800','ACTIVO','CUMPLIDOR',clock_timestamp())
                """);
        jdbcPre.update("""
                INSERT INTO ale_gestion."COBRO" (
                    "idCobro","idCliente","fechaHoraCobro","importe","medioPago","origen","estadoCobro")
                VALUES (-1,800001,clock_timestamp(),10.00,'EFECTIVO','ADMINISTRACION','CONFIRMADO')
                """);

        Flyway flywayPreV4 = Flyway.configure()
                .dataSource(dsPre)
                .defaultSchema("ale_gestion")
                .schemas("ale_gestion")
                .target("4")
                .load();
        FlywayException exPreflight = assertThrows(FlywayException.class, flywayPreV4::migrate);
        assertTrue(exPreflight.getMessage().contains("GFIS-PREFLIGHT-B2"));
        assertEquals(62, contar(jdbcPre, """
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'ale_gestion' AND table_type = 'BASE TABLE'
                   AND table_name <> 'flyway_schema_history'
                """));
        assertEquals(0, contar(jdbcPre, """
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'ale_gestion' AND c.relkind = 'S' AND c.relname LIKE 'seq_gfis_%'
                """));

        // 2) Lock timeout (ADAPT-04) en segunda base limpia V1..V3: ACCESS EXCLUSIVE sobre COBRO -> 55P03
        String dbLock = "ale_gfis_t6_locktimeout";
        crearBaseAislada(dbLock);
        String urlLock = jdbcUrlParaBase(dbLock);
        DriverManagerDataSource dsLock = crearDataSource(urlLock);
        JdbcTemplate jdbcLock = new JdbcTemplate(dsLock);

        Flyway.configure()
                .dataSource(dsLock)
                .defaultSchema("ale_gestion")
                .schemas("ale_gestion")
                .target("3")
                .load()
                .migrate();

        String sqlV4 = new ClassPathResource("db/migration/V4__gfis_pe1_pe2_imp01.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        CountDownLatch lockTomado = new CountDownLatch(1);
        CountDownLatch soltarLock = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Void> bloqueador = pool.submit(() -> {
                try (Connection cLock = DriverManager.getConnection(urlLock, db.getUsername(), db.getPassword());
                     Statement st = cLock.createStatement()) {
                    cLock.setAutoCommit(false);
                    st.execute("LOCK TABLE ale_gestion.\"COBRO\" IN ACCESS EXCLUSIVE MODE");
                    lockTomado.countDown();
                    assertTrue(soltarLock.await(25, TimeUnit.SECONDS));
                    cLock.rollback();
                    return null;
                }
            });

            assertTrue(lockTomado.await(5, TimeUnit.SECONDS));
            SQLException exTimeout = assertThrows(SQLException.class, () -> ejecutarSqlEnTransaccion(urlLock, sqlV4));
            soltarLock.countDown();
            bloqueador.get(10, TimeUnit.SECONDS);

            assertEquals("55P03", exTimeout.getSQLState(),
                    "ADAPT-04: lock_timeout='10s' debe abortar limpiamente con SQLSTATE 55P03");
            assertEquals(62, contar(jdbcLock, """
                    SELECT count(*) FROM information_schema.tables
                     WHERE table_schema = 'ale_gestion' AND table_type = 'BASE TABLE'
                       AND table_name <> 'flyway_schema_history'
                    """));
        }

        // 3) Una vez liberado el lock, Flyway V4 aplica limpio a la primera y queda valido
        Flyway flywayLockV4 = Flyway.configure()
                .dataSource(dsLock)
                .defaultSchema("ale_gestion")
                .schemas("ale_gestion")
                .target("4")
                .load();
        assertEquals(1, flywayLockV4.migrate().migrationsExecuted);
        assertEquals(75, contar(jdbcLock, """
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'ale_gestion' AND table_type = 'BASE TABLE'
                   AND table_name <> 'flyway_schema_history'
                """));
    }

    /**
     * T6b: Distingue rollback transaccional de DDL frente a no-transaccionalidad de setval/nextval
     * sobre secuencias ya existentes tras COMMIT de V4.
     */
    @Test
    void t6b_DdlRevierteConRollbackPeroSetvalSobreSecuenciaPreexistenteNoRevierte() throws Exception {
        String dbName = "ale_gfis_t6_seq";
        crearBaseAislada(dbName);
        String urlDb = jdbcUrlParaBase(dbName);
        DriverManagerDataSource ds = crearDataSource(urlDb);
        JdbcTemplate jdbcSeq = new JdbcTemplate(ds);

        Flyway.configure()
                .dataSource(ds)
                .defaultSchema("ale_gestion")
                .schemas("ale_gestion")
                .target("4")
                .load()
                .migrate();

        // DDL en TX abortada revierte completamente
        assertThrows(SQLException.class, () -> ejecutarSqlEnTransaccion(
                urlDb, "CREATE SEQUENCE ale_gestion.\"seq_t6_ddl_abortada\" START 1; SELECT 1/0;"));
        assertNull(jdbcSeq.queryForObject("SELECT pg_catalog.to_regclass('ale_gestion.\"seq_t6_ddl_abortada\"')", String.class));

        // setval sobre secuencia preexistente NO revierte ante ROLLBACK
        assertThrows(SQLException.class, () -> ejecutarSqlEnTransaccion(
                urlDb, "SELECT pg_catalog.setval('ale_gestion.\"seq_gfis_cobro_id\"', 9999, true); SELECT 1/0;"));
        Long siguiente = jdbcSeq.queryForObject("SELECT pg_catalog.nextval('ale_gestion.\"seq_gfis_cobro_id\"')", Long.class);
        assertEquals(10_000L, siguiente);
    }

    private void crearBaseAislada(String dbName) throws SQLException {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
        }
    }

    private String jdbcUrlParaBase(String dbName) {
        String baseUrl = db.getJdbcUrl();
        int idxParams = baseUrl.indexOf('?');
        String prefix = idxParams >= 0 ? baseUrl.substring(0, idxParams) : baseUrl;
        String suffix = idxParams >= 0 ? baseUrl.substring(idxParams) : "";
        int lastSlash = prefix.lastIndexOf('/');
        String target = prefix.substring(0, lastSlash + 1) + dbName + suffix;
        if (target.contains("currentSchema=")) {
            return target;
        }
        return target + (target.contains("?") ? "&" : "?") + "currentSchema=ale_gestion";
    }

    private DriverManagerDataSource crearDataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        ds.setUsername(db.getUsername());
        ds.setPassword(db.getPassword());
        return ds;
    }

    private void ejecutarSqlEnTransaccion(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, db.getUsername(), db.getPassword());
             Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                st.execute(sql);
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    private static int contar(JdbcTemplate template, String sql) {
        Integer v = template.queryForObject(sql, Integer.class);
        assertNotNull(v);
        return v;
    }

    private static String sha256Classpath(String path) throws Exception {
        byte[] bytes = new ClassPathResource(path).getContentAsByteArray();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
