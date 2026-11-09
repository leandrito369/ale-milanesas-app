package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.AleMilanesasApplication;
import ar.edu.unam.alemilanesas.produccioninventario.RecetaFixture;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="ale.http.writes.recetas-uc10-enabled=true")
@Testcontainers
class ConfigurarRecetaHttpExternoTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl); r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }
    @Value("${local.server.port}") int puerto;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    RecetaFixture f;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() { f = new RecetaFixture(j, tx); }
    String admin() { return f.actores.tokens.get("ADMINISTRADOR"); }
    String ruta(long producto) { return "/api/v1/productos/" + producto + "/receta"; }
    HttpResponse<String> request(int port, String ruta, String token, String body, String cookie) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (cookie != null) b.header("Cookie", cookie);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(Map<String, Object> body) throws Exception { return post(admin(), body); }
    HttpResponse<String> post(String token, Map<String, Object> body) throws Exception {
        return request(puerto, ruta(f.producto), token, json.writeValueAsString(body), null);
    }
    Map<String, Object> body() { return f.body(f.fecha(2), null); }
    void assertRespuesta(int estado, String codigo, HttpResponse<String> r) {
        assertEquals(estado, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if (codigo != null) assertEquals(codigo, json.readTree(r.body()).get("codigo").asString());
    }
    @Test void postPrimeraVersionRetorna201() throws Exception {
        var desde = f.fecha(2); var hasta = f.fecha(3);
        var r = post(f.body(desde, hasta)); assertRespuesta(201, null, r);
        var d = json.readTree(r.body()); assertEquals(8, d.size()); assertEquals(1, d.get("numeroVersion").asInt());
        assertEquals(2, d.get("cantidadItems").asInt()); assertTrue(d.get("versionCerrada").isNull());
        assertEquals("UN", d.get("codigoUnidadRendimiento").asString());
        assertTrue(OffsetDateTime.parse(d.get("vigenteDesde").asString()).isEqual(desde));
        assertTrue(OffsetDateTime.parse(d.get("vigenteHasta").asString()).isEqual(hasta));
        assertEquals(2, j.queryForObject("SELECT count(*) FROM \"DETALLE_VERSION_RECETA\" WHERE \"idReceta\"=?", Integer.class, d.get("idReceta").asLong()));
    }
    @Test void postSegundaVersionRetorna201ConVersionCerrada() throws Exception {
        long id = f.sembrarVigente(); var desde = f.fecha(2);
        var r = post(f.body(desde, null)); assertRespuesta(201, null, r);
        var d = json.readTree(r.body()); assertEquals(id, d.get("idReceta").asLong());
        assertEquals(2, d.get("numeroVersion").asInt()); assertEquals(1, d.get("versionCerrada").asInt());
        assertTrue(j.queryForObject("SELECT \"vigenteHasta\" FROM \"VERSION_RECETA\" WHERE \"idReceta\"=? AND \"numeroVersion\"=1", OffsetDateTime.class, id).isEqual(desde));
        assertEquals(1, j.queryForObject("SELECT count(*) FROM \"DETALLE_VERSION_RECETA\" WHERE \"idReceta\"=? AND \"numeroVersion\"=1", Integer.class, id));
    }
    @Test void postSolapeFuturoRetorna409() throws Exception {
        assertRespuesta(201, null, post(f.body(f.fecha(3), null)));
        assertRespuesta(409, "SOLAPE_VERSION_FUTURA", post(f.body(f.fecha(2), null)));
        assertRespuesta(409, "SOLAPE_VERSION_FUTURA", post(f.body(f.fecha(4), null)));
        assertEquals(1, f.versiones());
    }
    @Test void postBodyInvalidoRetorna400() throws Exception {
        for (var cambio : List.<Map.Entry<String, Object>>of(Map.entry("extra", 1), Map.entry("idProducto", f.producto),
                Map.entry("cantidadRendimientoBase", "1"), Map.entry("cantidadRendimientoBase", 0),
                Map.entry("cantidadRendimientoBase", new java.math.BigDecimal("1.0001")),
                Map.entry("cantidadRendimientoBase", new java.math.BigDecimal("1000000000000000")),
                Map.entry("vigenteDesde", "2026-02-30T12:00:00-03:00"), Map.entry("vigenteDesde", "2026-11-15"),
                Map.entry("vigenteDesde", 123), Map.entry("itemsInsumo", List.of()))) {
            var d = body(); d.put(cambio.getKey(), cambio.getValue()); assertRespuesta(400, "SOLICITUD_INVALIDA", post(d));
        }
        var sinCampo = body(); sinCampo.remove("codigoUnidadRendimiento"); assertRespuesta(400, "SOLICITUD_INVALIDA", post(sinCampo));
        for (Object item : List.of(Map.of("idInsumo", f.insumo, "cantidadNecesaria", 1, "codigoUnidadCantidad", "KG", "extra", 1),
                Map.of("idInsumo", "1", "cantidadNecesaria", 1, "codigoUnidadCantidad", "KG"),
                Map.of("idInsumo", f.insumo, "cantidadNecesaria", new java.math.BigDecimal("0.0001"), "codigoUnidadCantidad", "KG"),
                Map.of("idInsumo", new java.math.BigDecimal("9223372036854775808"), "cantidadNecesaria", 1, "codigoUnidadCantidad", "KG"))) {
            var d = body(); d.put("itemsInsumo", List.of(item)); assertRespuesta(400, "SOLICITUD_INVALIDA", post(d));
        }
        String valido = json.writeValueAsString(body());
        for (String raw : List.of("{", "null", "[]", valido + " true", valido.replace("\"cantidadNecesaria\":0.200", "\"cantidadNecesaria\":0.200,\"cantidadNecesaria\":1")))
            assertRespuesta(400, "SOLICITUD_INVALIDA", request(puerto, ruta(f.producto), admin(), raw, null));
        var desde = f.fecha(2);
        assertRespuesta(400, "SOLICITUD_INVALIDA", post(f.body(desde, desde)));
        assertRespuesta(400, "VIGENCIA_RETROACTIVA", post(f.body(f.fecha(-1), null)));
        assertEquals(0, f.versiones());
    }
    @Test void postSinTokenRetorna401() throws Exception {
        String d = json.writeValueAsString(body());
        assertRespuesta(401, "SESION_INVALIDA", request(puerto, ruta(f.producto), null, d, null));
        assertRespuesta(401, "SESION_INVALIDA", request(puerto, ruta(f.producto), null, d, "JSESSIONID=" + admin()));
        j.update("UPDATE \"SESION_USUARIO\" SET \"fechaHoraRevocacion\"=clock_timestamp() WHERE \"idSesionUsuario\"=?", f.admin());
        assertRespuesta(401, "SESION_INVALIDA", post(body()));
    }
    @Test void postRolVendedorRetorna403() throws Exception {
        for (String rol : List.of("VENDEDOR", "RESPONSABLE_PRODUCCION", "REPARTIDOR", "PROVEEDOR"))
            assertRespuesta(403, "OPERACION_PROHIBIDA", post(f.actores.tokens.get(rol), body()));
    }
    @Test void postGateDesactivadoRetorna403() throws Exception {
        try (var ctx = new SpringApplicationBuilder(AleMilanesasApplication.class).run("--server.port=0",
                "--spring.datasource.url=" + db.getJdbcUrl(), "--spring.datasource.username=" + db.getUsername(),
                "--spring.datasource.password=" + db.getPassword(), "--ale.http.writes.recetas-uc10-enabled=false")) {
            assertTrue(ctx.getBeansOfType(ConfigurarRecetaHttpController.class).isEmpty());
            int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
            assertRespuesta(403, "OPERACION_PROHIBIDA", request(port, ruta(f.producto), admin(), json.writeValueAsString(body()), null));
        }
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("uc10-prod",
                    Map.of("ale.http.writes.recetas-uc10-enabled", "true")));
            ctx.register(ConfigurarRecetaHttpController.class); ctx.refresh();
            assertTrue(ctx.getBeansOfType(ConfigurarRecetaHttpController.class).isEmpty());
        }
        var veto = new PrecondicionesDespliegue(new MockEnvironment().withProperty("ale.http.writes.recetas-uc10-enabled", "true"));
        assertEquals("I3-01b UC-10 (recetas) HTTP bloqueado en produccion",
                assertThrows(IllegalStateException.class, () -> veto.postProcessBeanFactory(null)).getMessage());
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        assertRespuesta(201, null, post(f.body(f.fecha(2), f.fecha(3))));
        var unidad = body(); unidad.put("codigoUnidadRendimiento", "KG"); assertRespuesta(400, "UNIDAD_NO_COINCIDE", post(unidad));
        var item = body(); item.put("itemsInsumo", List.of(Map.of("idInsumo", f.insumo, "cantidadNecesaria", 1, "codigoUnidadCantidad", "UN")));
        assertRespuesta(400, "UNIDAD_NO_COINCIDE", post(item));
        assertRespuesta(404, "PRODUCTO_INEXISTENTE", request(puerto, ruta(Long.MAX_VALUE), admin(), json.writeValueAsString(body()), null));
        assertRespuesta(400, "PRODUCTO_NO_ES_ELABORADO", request(puerto, ruta(f.actores.base), admin(), json.writeValueAsString(body()), null));
        var inexistente = body(); inexistente.put("itemsInsumo", List.of(Map.of("idInsumo", Long.MAX_VALUE, "cantidadNecesaria", 1, "codigoUnidadCantidad", "KG")));
        assertRespuesta(404, "INSUMO_INEXISTENTE", post(inexistente));
        j.update("UPDATE \"INSUMO\" SET \"activo\"=false WHERE \"idInsumo\"=?", f.insumo);
        assertRespuesta(400, "INSUMO_INACTIVO", post(body()));
        j.update("UPDATE \"INSUMO\" SET \"activo\"=true WHERE \"idInsumo\"=?", f.insumo);
        j.update("UPDATE \"PRODUCTO\" SET \"activo\"=false WHERE \"idProducto\"=?", f.producto);
        assertRespuesta(400, "PRODUCTO_INACTIVO", post(body()));
        j.update("UPDATE \"PRODUCTO\" SET \"activo\"=true WHERE \"idProducto\"=?", f.producto);
        j.execute("ALTER TABLE \"DETALLE_VERSION_RECETA\" RENAME TO \"DETALLE_VERSION_RECETA_TEST_OFFLINE\"");
        try { assertRespuesta(503, "PERSISTENCIA_NO_DISPONIBLE", post(f.body(f.fecha(4), f.fecha(5)))); }
        finally { j.execute("ALTER TABLE \"DETALLE_VERSION_RECETA_TEST_OFFLINE\" RENAME TO \"DETALLE_VERSION_RECETA\""); }
        assertEquals(1, f.versiones());
    }
}
