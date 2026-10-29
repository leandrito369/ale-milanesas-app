package ar.edu.unam.alemilanesas;

import ar.edu.unam.alemilanesas.bootstrap.HomeController;
import ar.edu.unam.alemilanesas.bootstrap.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** B1: prueba HTTP real y PostgreSQL efímero; no constituye prueba de CU. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BootstrapIntegrationTest {

    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;

    @Autowired DataSource dataSource;
    @Autowired HomeController homeController;
    @Autowired SecurityConfiguration securityConfiguration;

    private int status(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @Test
    void applicationStartsAndQueriesPostgresql() throws Exception {
        assertNotNull(homeController);
        assertNotNull(securityConfiguration);
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.isValid(5));
        }
    }

    @Test
    void publicBootstrapAndHealthWork() throws Exception {
        assertEquals(200, status("/"));
        assertEquals(200, status("/actuator/health"));
    }

    @Test
    void undisclosedEndpointsAreDenied() throws Exception {
        assertEquals(403, status("/api/pedidos"));
        assertEquals(403, status("/actuator/env"));
    }
}
