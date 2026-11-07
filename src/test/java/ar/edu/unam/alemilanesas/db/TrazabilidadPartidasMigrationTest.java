package ar.edu.unam.alemilanesas.db;

import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MR-6 / RD-MR-PARTIDAS-001: verificación de migración V3
 * (PARTIDA_INSUMO, DETALLE_MOVIMIENTO_PARTIDA, UNIQUE compuesto, backfill y trigger).
 */
@Testcontainers
@SpringBootTest
@Transactional
class TrazabilidadPartidasMigrationTest {

    private static final String V1_SHA256 =
            "35cf5adfb115b3662cbd661686953844c47b7b50900b7accfad6d075b4590eb1";
    private static final String V2_SHA256 =
            "dab4b273fc170527e9a8184298ae323ba6a1b5429cc685e56d3262e27c60956d";

    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @BeforeEach
    void sembrarCatalogoMinimo() {
        jdbc.update("""
            INSERT INTO "UNIDAD_MEDIDA" ("codigoUnidad", "nombre")
            VALUES ('KG', 'Kilogramo')
            ON CONFLICT ("codigoUnidad") DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol", "nombreMostrado")
            VALUES ('ADMINISTRADOR', 'Administrador MR6')
            ON CONFLICT ("codigoRol") DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" (
                "idUsuario", "nombreUsuario", "credencialProtegida",
                "estado", "cambioCredencialPendiente", "fechaHoraAlta"
            )
            VALUES (90001, 'admin_mr6_v3', 'HASH_MR6', 'HABILITADO', false, now())
            ON CONFLICT ("idUsuario") DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario", "codigoRol", "fechaHoraAsignacion")
            VALUES (90001, 'ADMINISTRADOR', now())
            ON CONFLICT ("idUsuario", "codigoRol") DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO "INSUMO" (
                "idInsumo", "nombre", "codigoUnidadMedida",
                "stockActual", "umbralMinimo", "nivelObjetivoReposicion",
                "automatizacionCompraActiva", "activo"
            )
            VALUES
                (9001, 'Pan rallado MR6 A', 'KG', 100.000, 10.000, 50.000, false, true),
                (9002, 'Huevo liquido MR6 B', 'KG', 50.000, 5.000, 20.000, false, true)
            ON CONFLICT ("idInsumo") DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO "COSTO_INSUMO_HISTORICO" (
                "idInsumo", "vigenteDesde", "costoUnitario", "fuenteCosto"
            )
            VALUES
                (9001, '2026-11-01T00:00:00Z'::timestamptz, 1200.0000, 'AJUSTE'),
                (9002, '2026-11-01T00:00:00Z'::timestamptz, 2500.0000, 'AJUSTE')
            ON CONFLICT ("idInsumo", "vigenteDesde") DO NOTHING
            """);
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        assertNotNull(value);
        return value;
    }

    private void rechaza(String sqlState, Runnable operation) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try {
                var error = assertThrows(DataIntegrityViolationException.class, operation::run);
                var cause = assertInstanceOf(java.sql.SQLException.class, error.getMostSpecificCause());
                assertEquals(sqlState, cause.getSQLState());
            } finally {
                // Cada rechazo esperado aborta la transacción hasta recuperar el savepoint.
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            }
            return null;
        });
    }

    private static String sha256Classpath(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return HexFormat.of().formatHex(digest.digest(in.readAllBytes()));
        }
    }

    @Test
    void lasDosTablasNuevasExisten() {
        assertEquals(1, queryInt("""
            SELECT count(*) FROM information_schema.tables
            WHERE table_schema = 'ale_gestion'
              AND table_type = 'BASE TABLE'
              AND table_name = 'PARTIDA_INSUMO'
            """));
        assertEquals(1, queryInt("""
            SELECT count(*) FROM information_schema.tables
            WHERE table_schema = 'ale_gestion'
              AND table_type = 'BASE TABLE'
              AND table_name = 'DETALLE_MOVIMIENTO_PARTIDA'
            """));
        assertEquals(1, queryInt("""
            SELECT count(*) FROM pg_constraint c
            JOIN pg_namespace n ON n.oid = c.connamespace
            WHERE n.nspname = 'ale_gestion'
              AND c.conname = 'uq_movimiento_insumo_insumo'
            """));
    }

    @Test
    void checksFuncionanEnPartidaInsumo() {
        rechaza("23514", () -> jdbc.update("""
            INSERT INTO "PARTIDA_INSUMO" (
                "idInsumo", "codigoLote", "fechaHoraRegistro",
                "cantidadInicial", "cantidadDisponible"
            )
            VALUES (9001, 'LOTE-CERO', now(), 0.000, 0.000)
            """));

        rechaza("23514", () -> jdbc.update("""
            INSERT INTO "PARTIDA_INSUMO" (
                "idInsumo", "codigoLote", "fechaHoraRegistro",
                "cantidadInicial", "cantidadDisponible"
            )
            VALUES (9001, 'LOTE-EXCESO', now(), 10.000, 10.001)
            """));

        rechaza("23514", () -> jdbc.update("""
            INSERT INTO "PARTIDA_INSUMO" (
                "idInsumo", "codigoLote", "fechaFabricacion", "fechaVencimiento",
                "fechaHoraRegistro", "cantidadInicial", "cantidadDisponible"
            )
            VALUES (9001, 'LOTE-FECHAS', DATE '2026-12-10', DATE '2026-12-01', now(), 10.000, 10.000)
            """));
    }

    @Test
    void fksCompuestasFuncionan() {
        jdbc.update("""
            INSERT INTO "PARTIDA_INSUMO" (
                "idPartidaInsumo", "idInsumo", "codigoLote",
                "fechaHoraRegistro", "cantidadInicial", "cantidadDisponible"
            )
            VALUES
                (9101, 9001, 'LOTE-A', now(), 50.000, 50.000),
                (9102, 9002, 'LOTE-B', now(), 50.000, 50.000)
            """);
        jdbc.update("""
            INSERT INTO "MOVIMIENTO_INSUMO" (
                "idMovimientoInsumo", "idInsumo", "numeroSecuenciaInsumo",
                "vigenteDesdeCosto", "idUsuarioResponsable", "tipoMovimiento",
                "cantidad", "fechaHora", "stockResultante", "origen"
            )
            VALUES (
                9201, 9001, 1,
                '2026-11-01T00:00:00Z'::timestamptz, 90001, 'CONSUMO_PRODUCCION',
                10.000, now(), 90.000, 'PRODUCCION'
            )
            """);

        // Rechaza partida de insumo 9002 imputada a movimiento del insumo 9001
        rechaza("23503", () -> jdbc.update("""
            INSERT INTO "DETALLE_MOVIMIENTO_PARTIDA" (
                "idMovimientoInsumo", "idPartidaInsumo", "idInsumo", "cantidadAplicada"
            )
            VALUES (9201, 9102, 9001, 10.000)
            """));

        // Rechaza declarar insumo 9002 cuando el movimiento 9201 pertenece a 9001
        rechaza("23503", () -> jdbc.update("""
            INSERT INTO "DETALLE_MOVIMIENTO_PARTIDA" (
                "idMovimientoInsumo", "idPartidaInsumo", "idInsumo", "cantidadAplicada"
            )
            VALUES (9201, 9102, 9002, 10.000)
            """));

        // Acepta cuando movimiento y partida pertenecen al mismo insumo 9001
        int inserted = jdbc.update("""
            INSERT INTO "DETALLE_MOVIMIENTO_PARTIDA" (
                "idMovimientoInsumo", "idPartidaInsumo", "idInsumo", "cantidadAplicada"
            )
            VALUES (9201, 9101, 9001, 10.000)
            """);
        assertEquals(1, inserted);
    }

    @Test
    void backfillCreoPartidasBackfillV2ParaInsumosConStockPositivo() {
        String schemaBackfill = "ale_v3_backfill_test";
        // Confirmar la fixture antes del DDL: la transacción del test no debe
        // retener bloqueos sobre INSUMO mientras Flyway agrega su FK desde otra conexión.
        JdbcTemplate backfill = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        Flyway.configure()
                .dataSource(dataSource)
                .createSchemas(true)
                .defaultSchema(schemaBackfill)
                .schemas(schemaBackfill)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();

        backfill.update("""
            INSERT INTO ale_v3_backfill_test."UNIDAD_MEDIDA" ("codigoUnidad", "nombre")
            VALUES ('KG', 'Kilogramo')
            """);
        backfill.update("""
            INSERT INTO ale_v3_backfill_test."INSUMO" (
                "idInsumo", "nombre", "codigoUnidadMedida",
                "stockActual", "umbralMinimo", "nivelObjetivoReposicion",
                "automatizacionCompraActiva", "activo"
            )
            VALUES
                (9301, 'Insumo Con Stock', 'KG', 25.500, 5.000, 30.000, false, true),
                (9302, 'Insumo Sin Stock', 'KG', 0.000, 5.000, 30.000, false, true)
            """);

        Flyway.configure()
                .dataSource(dataSource)
                .createSchemas(true)
                .defaultSchema(schemaBackfill)
                .schemas(schemaBackfill)
                .locations("classpath:db/migration")
                .target("3")
                .load()
                .migrate();

        assertEquals(1, queryInt("""
            SELECT count(*) FROM ale_v3_backfill_test."PARTIDA_INSUMO"
            WHERE "idInsumo" = 9301
              AND "codigoLote" = 'BACKFILL_V2'
              AND "idRecepcionCompra" IS NULL
              AND "cantidadInicial" = 25.500
              AND "cantidadDisponible" = 25.500
            """));
        assertEquals(0, queryInt("""
            SELECT count(*) FROM ale_v3_backfill_test."PARTIDA_INSUMO"
            WHERE "idInsumo" = 9302
            """));
    }

    @Test
    void triggerActualizaCantidadDisponibleEnInsert() {
        jdbc.update("""
            INSERT INTO "PARTIDA_INSUMO" (
                "idPartidaInsumo", "idInsumo", "codigoLote",
                "fechaHoraRegistro", "cantidadInicial", "cantidadDisponible"
            )
            VALUES (9501, 9001, 'LOTE-TRIGGER', now(), 100.000, 100.000)
            """);
        jdbc.update("""
            INSERT INTO "MOVIMIENTO_INSUMO" (
                "idMovimientoInsumo", "idInsumo", "numeroSecuenciaInsumo",
                "vigenteDesdeCosto", "idUsuarioResponsable", "tipoMovimiento",
                "cantidad", "fechaHora", "stockResultante", "origen"
            )
            VALUES (
                9511, 9001, 1,
                '2026-11-01T00:00:00Z'::timestamptz, 90001, 'CONSUMO_PRODUCCION',
                35.250, now(), 64.750, 'PRODUCCION'
            )
            """);
        jdbc.update("""
            INSERT INTO "DETALLE_MOVIMIENTO_PARTIDA" (
                "idMovimientoInsumo", "idPartidaInsumo", "idInsumo", "cantidadAplicada"
            )
            VALUES (9511, 9501, 9001, 35.250)
            """);

        BigDecimal luegoDeConsumo = jdbc.queryForObject("""
            SELECT "cantidadDisponible" FROM "PARTIDA_INSUMO" WHERE "idPartidaInsumo" = 9501
            """, BigDecimal.class);
        assertEquals(new BigDecimal("64.750"), luegoDeConsumo);

        jdbc.update("""
            INSERT INTO "MOVIMIENTO_INSUMO" (
                "idMovimientoInsumo", "idInsumo", "numeroSecuenciaInsumo",
                "vigenteDesdeCosto", "idUsuarioResponsable", "tipoMovimiento",
                "cantidad", "fechaHora", "stockResultante", "origen"
            )
            VALUES (
                9512, 9001, 2,
                '2026-11-01T00:00:00Z'::timestamptz, 90001, 'AJUSTE_POSITIVO',
                15.250, now(), 80.000, 'AJUSTE_INVENTARIO'
            )
            """);
        jdbc.update("""
            INSERT INTO "DETALLE_MOVIMIENTO_PARTIDA" (
                "idMovimientoInsumo", "idPartidaInsumo", "idInsumo", "cantidadAplicada"
            )
            VALUES (9512, 9501, 9001, 15.250)
            """);

        BigDecimal luegoDeAjustePositivo = jdbc.queryForObject("""
            SELECT "cantidadDisponible" FROM "PARTIDA_INSUMO" WHERE "idPartidaInsumo" = 9501
            """, BigDecimal.class);
        assertEquals(new BigDecimal("80.000"), luegoDeAjustePositivo);
    }

    @Test
    void triggerRechazaConsumoMayorACantidadDisponible() {
        jdbc.update("""
            INSERT INTO "PARTIDA_INSUMO" (
                "idPartidaInsumo", "idInsumo", "codigoLote",
                "fechaHoraRegistro", "cantidadInicial", "cantidadDisponible"
            )
            VALUES (9601, 9001, 'LOTE-LIMITE', now(), 20.000, 20.000)
            """);
        jdbc.update("""
            INSERT INTO "MOVIMIENTO_INSUMO" (
                "idMovimientoInsumo", "idInsumo", "numeroSecuenciaInsumo",
                "vigenteDesdeCosto", "idUsuarioResponsable", "tipoMovimiento",
                "cantidad", "fechaHora", "stockResultante", "origen"
            )
            VALUES (
                9611, 9001, 1,
                '2026-11-01T00:00:00Z'::timestamptz, 90001, 'CONSUMO_PRODUCCION',
                20.001, now(), 79.999, 'PRODUCCION'
            )
            """);

        rechaza("23514", () -> jdbc.update("""
            INSERT INTO "DETALLE_MOVIMIENTO_PARTIDA" (
                "idMovimientoInsumo", "idPartidaInsumo", "idInsumo", "cantidadAplicada"
            )
            VALUES (9611, 9601, 9001, 20.001)
            """));
    }

    @Test
    void v1YV2NoFueronModificadas() throws Exception {
        assertEquals(V1_SHA256, sha256Classpath("db/migration/V1__baseline_mr5.sql"));
        assertEquals(V2_SHA256, sha256Classpath("db/migration/V2__f01_cobro_estados.sql"));
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(3, queryInt("""
            SELECT count(*) FROM ale_gestion.flyway_schema_history
            WHERE success = true AND type = 'SQL' AND version IN ('1', '2', '3')
            """));
    }
}

