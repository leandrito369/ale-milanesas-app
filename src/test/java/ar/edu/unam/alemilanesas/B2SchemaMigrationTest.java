package ar.edu.unam.alemilanesas;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** B2: estructura MR-5/F01 real en PostgreSQL 17; no certifica operaciones de negocio. */
@Testcontainers
@SpringBootTest
class B2SchemaMigrationTest {

    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    int query(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        assertNotNull(value);
        return value;
    }

    @Test
    void exactMr5CatalogAndF01Constraints() {
        assertEquals(60, query("""
            SELECT count(*) FROM information_schema.tables
            WHERE table_schema='ale_gestion' AND table_type='BASE TABLE'
              AND table_name <> 'flyway_schema_history'
            """));
        assertEquals(453, query("""
            SELECT count(*) FROM information_schema.columns
            WHERE table_schema='ale_gestion' AND table_name <> 'flyway_schema_history'
            """));
        assertEquals(125, query("""
            SELECT count(*) FROM pg_constraint c
            JOIN pg_namespace n ON n.oid = c.connamespace
            WHERE n.nspname='ale_gestion' AND c.contype='f'
            """));
        assertEquals(1, query("""
            SELECT count(*) FROM pg_constraint c
            JOIN pg_namespace n ON n.oid=c.connamespace
            WHERE n.nspname='ale_gestion' AND c.conname='ck_f01_cobro_estado'
            """));
        assertEquals(1, query("""
            SELECT count(*) FROM pg_trigger t
            JOIN pg_class c ON c.oid=t.tgrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='ale_gestion' AND t.tgname='tg_f01_transicion_cobro'
              AND NOT t.tgisinternal
            """));
    }

    @Test
    void twoMigrationsCompleteAndRerunDoesNotRepeat() {
        assertEquals(2, query("""
            SELECT count(*) FROM ale_gestion.flyway_schema_history
            WHERE success = true AND type = 'SQL'
            """));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(2, query("""
            SELECT count(*) FROM ale_gestion.flyway_schema_history
            WHERE success = true AND type = 'SQL'
            """));
    }
}
