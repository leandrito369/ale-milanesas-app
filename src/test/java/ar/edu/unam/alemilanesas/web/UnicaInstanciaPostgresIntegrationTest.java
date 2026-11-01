package ar.edu.unam.alemilanesas.web;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/** B4-02D: dos instancias sobre PostgreSQL real no pueden iniciar juntas. */
@SpringBootTest
@Testcontainers
class UnicaInstanciaPostgresIntegrationTest {
    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17.11-alpine");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",db::getJdbcUrl);
        props.add("spring.datasource.username",db::getUsername);
        props.add("spring.datasource.password",db::getPassword);
    }

    @Autowired DataSource ds;

    @Test
    void soloUnaInstanciaYDespuesDelCierreOtraPuedeAsumir() throws Exception {
        var primera=new UnicaInstanciaPostgres(ds);
        var segunda=new UnicaInstanciaPostgres(ds);
        var tercera=new UnicaInstanciaPostgres(ds);
        assertFalse(primera.operativo()); // no admite peticiones antes del startup
        try {
            primera.run(null);
            assertTrue(primera.operativo());
            assertThrows(IllegalStateException.class, ()->segunda.run(null));
            assertFalse(segunda.operativo());
            assertTrue(primera.operativo());
        } finally {
            primera.close();
            segunda.close();
        }
        assertFalse(primera.operativo()); // lock perdido: nunca reactivar
        try {
            tercera.run(null);
            assertTrue(tercera.operativo());
        } finally {
            tercera.close();
        }
    }
}
