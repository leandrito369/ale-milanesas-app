package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Fail-closed: con el flag deshabilitado no existe escritura web de UC-28. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ale.http.writes.preparaciones-uc28-enabled=false"
})
@Testcontainers
class PrepararPedidoFlagDeshabilitadoTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    PedidoFixture f;

    @BeforeEach
    void fixture() { f = tx.execute(s -> new PedidoFixture(jdbc)); }

    Csrf csrf() throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/web/login"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        var matcher = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(r.body());
        assertTrue(matcher.find());
        String cookie = r.headers().allValues("Set-Cookie").stream()
                .filter(x -> x.startsWith("JSESSIONID=")).findFirst().orElseThrow().split(";", 2)[0];
        return new Csrf(cookie, matcher.group(1));
    }
    record Csrf(String cookie, String token) {}

    @Test
    void flagDeshabilitadoNiegaGetYPost() throws Exception {
        String ruta = "/web/demo/pedidos/1/preparar";
        var get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta))
                .header("Cookie", "__Host-ale_session=" + f.tokens.get("RESPONSABLE_PRODUCCION"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, get.statusCode());

        var csrf = csrf();
        String body = "idProducto=1&cantidadPreparada=1&_csrf=" + csrf.token();
        var post = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta))
                .header("Cookie", "__Host-ale_session=" + f.tokens.get("RESPONSABLE_PRODUCCION") + "; " + csrf.cookie())
                .header("Origin", "http://127.0.0.1:" + port)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, post.statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"PREPARACION\"", Integer.class));
    }
}
