package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.web.demo.ConsultasDemoController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Arranque completo demo con configuración deliberadamente no válida para prod. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("demo")
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class GuardDemoApplicationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired ApplicationContext ctx;
    @Test void demoArrancaSinGuardProdYConSusAdaptadores() {
        assertTrue(ctx.getBeansOfType(PrecondicionesDespliegue.class).isEmpty());
        assertNotNull(ctx.getBean(ConsultasDemoController.class));
    }
}
