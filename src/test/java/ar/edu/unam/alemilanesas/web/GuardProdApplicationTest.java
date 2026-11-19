package ar.edu.unam.alemilanesas.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Aplicación completa, perfil prod, servlet MOCK sin puerto HTTP y PostgreSQL efímero. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={
    "ale.web.allowed-origin=https://ale.example.invalid","server.address=127.0.0.1",
    "server.forward-headers-strategy=native","ale.security.singleton-db-guard=true"})
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class GuardProdApplicationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired ApplicationContext ctx;
    @Test void contextoProdRealSuperaPrecondicionesSinDesplegarServidor() {
        assertEquals(1,ctx.getBeansOfType(PrecondicionesDespliegue.class).size());
        assertTrue(ctx.getBean(UnicaInstanciaPostgres.class).operativo());
    }
}
