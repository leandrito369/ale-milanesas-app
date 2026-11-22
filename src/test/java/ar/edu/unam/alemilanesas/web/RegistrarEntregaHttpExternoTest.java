package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import ar.edu.unam.alemilanesas.produccioninventario.PreparacionFixture;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService;
import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.GestionarRecorridoRepartoService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** UC-05 HTTP externo: BEARER auth, RBAC REPARTIDOR, proyeccion BC-07, idempotencia, concurrencia y gate flag. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ale.http.writes.entregas-uc05-enabled=true")
@Testcontainers(disabledWithoutDocker = true)
class RegistrarEntregaHttpExternoTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}") int puerto;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired RegistrarPreparacionService preparaciones;
    @Autowired GestionarRecorridoRepartoService recorridos;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private HttpResponse<String> post(long idPedido, String token, String body) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + puerto + "/api/v1/pedidos/" + idPedido + "/entrega"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void autorizacionRbacProyeccionBc07IdempotenciaYErroresHttpReales() throws Exception {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long rep1 = f.actores.base + 3;
        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("4"));
        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), rep1, "REC-HTTP");
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(f.pedido, rep1, null), rec.idRecorrido());

        // Otro REPARTIDOR no asignado al Pedido
        String tokRep2 = tx.execute(s -> {
            long r2 = f.actores.base + 88;
            UUID ses = UUID.randomUUID();
            String tok = TokenSesionInterna.nuevo();
            jdbc.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                        "estado","cambioCredencialPendiente","fechaHoraAlta")
                    VALUES (?,?,'HASH_TEST','HABILITADO',false,now())
                    """, r2, "rep_http2_" + r2);
            jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'REPARTIDOR',now())", r2);
            jdbc.update("""
                    INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                    VALUES (?,?,?,now()-interval '1 minute',now()+interval '1 hour')
                    """, ses, r2, TokenSesionInterna.huella(tok).orElseThrow());
            return tok;
        });

        UUID clave = UUID.randomUUID();
        String body = """
                {
                  "detallesEntrega": [{"idProducto": %d, "cantidadEntregada": 3}],
                  "efectivoDeclaradoRecibido": 150.00,
                  "claveIdempotencia": "%s"
                }
                """.formatted(f.producto, clave);

        // 401 sin token
        assertEquals(401, post(f.pedido, null, body).statusCode());

        // 403 roles ajenos y REPARTIDOR no asignado
        for (String rol : List.of("ADMINISTRADOR", "VENDEDOR", "RESPONSABLE_PRODUCCION", "PROVEEDOR")) {
            assertEquals(403, post(f.pedido, f.actores.tokens.get(rol), body).statusCode());
        }
        assertEquals(403, post(f.pedido, tokRep2, body).statusCode());

        // 400 campo desconocido
        String repToken = f.actores.tokens.get("REPARTIDOR");
        assertEquals(400, post(f.pedido, repToken, """
                {"detallesEntrega":[{"idProducto":%d,"cantidadEntregada":1}],"claveIdempotencia":"%s","extra":"no"}
                """.formatted(f.producto, UUID.randomUUID())).statusCode());

        // 404 pedido inexistente
        assertEquals(404, post(999999999L, repToken, body).statusCode());

        // 201 Created en primera entrega + verificacion strict proyeccion BC-07 (sin costos ni saldo/categoria)
        HttpResponse<String> created = post(f.pedido, repToken, body);
        assertEquals(201, created.statusCode());
        assertTrue(created.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode json = JSON.readTree(created.body());
        assertTrue(json.path("creado").asBoolean());
        assertEquals("ENTREGADO", json.path("estadoPedido").asString());
        assertEquals(0, new BigDecimal("270.00").compareTo(new BigDecimal(json.path("cargoRealCe").asString())));
        assertEquals(1, json.path("excedentesGenerados").size());
        for (String prohibido : List.of(
                "costoHistorico", "costoTotalHistoricoVenta", "costoPromedio",
                "saldoConfirmado", "saldoResultante", "categoriaComercial", "margen")) {
            assertFalse(created.body().contains(prohibido), "Respuesta BC-07 no debe exponer: " + prohibido);
        }

        // 200 OK en replay idempotente
        HttpResponse<String> replay = post(f.pedido, repToken, body);
        assertEquals(200, replay.statusCode());
        assertFalse(JSON.readTree(replay.body()).path("creado").asBoolean());

        // 409 conflicto de contenido con misma clave
        assertEquals(409, post(f.pedido, repToken, """
                {"detallesEntrega":[{"idProducto":%d,"cantidadEntregada":2}],"efectivoDeclaradoRecibido":150.00,"claveIdempotencia":"%s"}
                """.formatted(f.producto, clave)).statusCode());

        // 409 nuevo intento sobre Pedido ya ENTREGADO
        assertEquals(409, post(f.pedido, repToken, """
                {"detallesEntrega":[{"idProducto":%d,"cantidadEntregada":2}],"claveIdempotencia":"%s"}
                """.formatted(f.producto, UUID.randomUUID())).statusCode());
    }

    @Test
    void concurrenciaHttpConMismaClaveSoloCreaUnaEntregaYBanderaDesactivadaDeniega() throws Exception {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long rep1 = f.actores.base + 3;
        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("2"));
        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), rep1, "REC-HTTP-CONC");
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(f.pedido, rep1, null), rec.idRecorrido());

        String repToken = f.actores.tokens.get("REPARTIDOR");
        String body = """
                {"detallesEntrega":[{"idProducto":%d,"cantidadEntregada":2}],"efectivoDeclaradoRecibido":90.00,"claveIdempotencia":"%s"}
                """.formatted(f.producto, UUID.randomUUID());

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> r1 = pool.submit(() -> post(f.pedido, repToken, body));
            Future<HttpResponse<String>> r2 = pool.submit(() -> post(f.pedido, repToken, body));
            List<Integer> codes = List.of(r1.get(15, TimeUnit.SECONDS).statusCode(), r2.get(15, TimeUnit.SECONDS).statusCode());
            assertTrue(codes.contains(201) && codes.contains(200), "Codes=" + codes);
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM \"ENTREGA\" WHERE \"idPedido\"=?", Integer.class, f.pedido));

        // Gate desactivado por defecto => 403 en el endpoint
        try (var ctx = SpringApplication.run(ar.edu.unam.alemilanesas.AleMilanesasApplication.class,
                "--server.port=0",
                "--spring.datasource.url=" + db.getJdbcUrl(),
                "--spring.datasource.username=" + db.getUsername(),
                "--spring.datasource.password=" + db.getPassword(),
                "--ale.http.writes.entregas-uc05-enabled=false")) {
            int p2 = ctx.getEnvironment().getProperty("local.server.port", Integer.class);
            var req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + p2 + "/api/v1/pedidos/" + f.pedido + "/entrega"))
                    .header("Authorization", "Bearer " + repToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            assertEquals(403, http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
