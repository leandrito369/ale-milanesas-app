package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.produccioninventario.PreparacionFixture;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

/** HTTP real + PostgreSQL efímero + B3 real: la confirmación sale de una escritura real. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ale.http.writes.preparaciones-uc28-enabled=true"
})
@Testcontainers
class PrepararPedidoDemoIntegrationTest {
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
    @Autowired CargarPedidoService pedidos;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    PreparacionFixture f;

    @BeforeEach
    void fixture() {
        f = tx.execute(s -> new PreparacionFixture(jdbc, tx, pedidos));
    }

    record Csrf(String cookie, String token) {}

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

    HttpResponse<String> get(String ruta, String rol) throws Exception {
        return get(ruta, rol, null);
    }

    HttpResponse<String> get(String ruta, String rol, String jsession) throws Exception {
        String cookie = "__Host-ale_session=" + f.actores.tokens.get(rol)
                + (jsession == null ? "" : "; " + jsession);
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta))
                .header("Cookie", cookie)
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String ruta, String cantidad, Csrf csrf, String rol) throws Exception {
        String body = "idProducto=" + f.producto + "&cantidadPreparada=" + URLEncoder.encode(cantidad, StandardCharsets.UTF_8);
        if (csrf != null) body += "&_csrf=" + URLEncoder.encode(csrf.token(), StandardCharsets.UTF_8);
        String cookie = "__Host-ale_session=" + f.actores.tokens.get(rol) + (csrf == null ? "" : "; " + csrf.cookie());
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta))
                .header("Cookie", cookie).header("Origin", "http://127.0.0.1:" + port)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void getAutorizadoMuestraFormularioYContexto() throws Exception {
        var r = get("/web/demo/pedidos/" + f.pedido + "/preparar", "RESPONSABLE_PRODUCCION");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("Registrar Preparación"));
        assertTrue(r.body().contains("name=\"idProducto\""));
        assertTrue(r.body().contains("name=\"cantidadPreparada\""));
        assertFalse(r.body().contains("totalOriginal"));
        assertFalse(r.body().contains("precioUnitarioOriginal"));
    }

    @Test
    void postAutorizadoPersisteYConfirmaIdRealConMensajeDeUnSoloUso() throws Exception {
        String ruta = "/web/demo/pedidos/" + f.pedido + "/preparar";
        var csrf = csrf();
        var respuesta = post(ruta, "1.000", csrf, "RESPONSABLE_PRODUCCION");
        assertEquals(303, respuesta.statusCode(), respuesta.body());
        String location = respuesta.headers().firstValue("Location").orElseThrow();
        assertEquals(ruta, location);
        long id = jdbc.queryForObject("SELECT MAX(\"idPreparacion\") FROM \"PREPARACION\"", Long.class);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM \"PREPARACION\" WHERE \"idPreparacion\"=?", Integer.class, id));
        assertEquals(1, f.preparaciones());
        var confirmacion = get(location, "RESPONSABLE_PRODUCCION", csrf.cookie());
        assertEquals(200, confirmacion.statusCode());
        assertTrue(confirmacion.body().contains("Preparación registrada con identificador real"));
        assertTrue(confirmacion.body().contains(Long.toString(id)));
        var reconsulta = get(location, "RESPONSABLE_PRODUCCION", csrf.cookie());
        assertFalse(reconsulta.body().contains("Preparación registrada con identificador real"));
    }

    @Test
    void getConConfirmadaInventadoNoMuestraExito() throws Exception {
        String ruta = "/web/demo/pedidos/" + f.pedido + "/preparar?confirmada=999";
        var respuesta = get(ruta, "RESPONSABLE_PRODUCCION");
        assertEquals(200, respuesta.statusCode(), respuesta.body());
        assertFalse(respuesta.body().contains("Preparación registrada con identificador real"));
        assertFalse(respuesta.body().contains(">999<"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"PREPARACION\" WHERE \"idPreparacion\"=999", Integer.class));
    }

    @Test
    void rechazaRolYcsrfSinPersistir() throws Exception {
        String ruta = "/web/demo/pedidos/" + f.pedido + "/preparar";
        assertEquals(403, get(ruta, "VENDEDOR").statusCode());
        assertEquals(403, get(ruta, "ADMINISTRADOR").statusCode());
        assertEquals(403, post(ruta, "1.000", null, "RESPONSABLE_PRODUCCION").statusCode());
        assertEquals(403, post(ruta, "1.000", csrf(), "VENDEDOR").statusCode());
        assertEquals(0, f.preparaciones());
    }

    @Test
    void cantidadInvalidaYSuperiorAlOriginalNoPersisten() throws Exception {
        String ruta = "/web/demo/pedidos/" + f.pedido + "/preparar";
        assertEquals(400, post(ruta, "0", csrf(), "RESPONSABLE_PRODUCCION").statusCode());
        assertEquals(400, post(ruta, "9999", csrf(), "RESPONSABLE_PRODUCCION").statusCode());
        assertEquals(0, f.preparaciones());
    }

    @Test
    void stockInsuficienteEsConflicto() throws Exception {
        jdbc.update("UPDATE \"PARTIDA_INSUMO\" SET \"cantidadDisponible\"=0 WHERE \"idInsumo\" IN (?,?)", f.insumo, f.insumo + 1);
        var respuesta = post("/web/demo/pedidos/" + f.pedido + "/preparar", "1.000", csrf(), "RESPONSABLE_PRODUCCION");
        assertEquals(409, respuesta.statusCode(), respuesta.body());
        assertTrue(respuesta.body().contains("No hay stock utilizable suficiente en las partidas."), respuesta.body());
        assertEquals(0, f.preparaciones());
    }

    @Test
    void reintentoNoEsIdempotenteSegunContratoVigente() throws Exception {
        String ruta = "/web/demo/pedidos/" + f.pedido + "/preparar";
        var primero = post(ruta, "1.000", csrf(), "RESPONSABLE_PRODUCCION");
        assertEquals(303, primero.statusCode());
        var segundo = post(ruta, "1.000", csrf(), "RESPONSABLE_PRODUCCION");
        assertEquals(303, segundo.statusCode());
        assertEquals(2, f.preparaciones());
        assertEquals(ruta, primero.headers().firstValue("Location").orElseThrow());
        assertEquals(ruta, segundo.headers().firstValue("Location").orElseThrow());
        var ids = jdbc.queryForList("SELECT \"idPreparacion\" FROM \"PREPARACION\" WHERE \"idPedido\"=?", Long.class, f.pedido);
        assertEquals(2, ids.size());
        assertNotEquals(ids.getFirst(), ids.getLast());
    }
}
