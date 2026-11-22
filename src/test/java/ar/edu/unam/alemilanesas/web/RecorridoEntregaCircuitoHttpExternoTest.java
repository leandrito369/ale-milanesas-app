package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.produccioninventario.PreparacionFixture;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H1 E2E HTTP real sin llamadas directas a GestionarRecorridoRepartoService ni RegistrarPreparacionService:
 * login VENDEDOR -> abrir Recorrido -> asignar+vincular (primera y reasignacion) ->
 * login PRODUCCION -> preparar UC-28 -> login REPARTIDOR asignado -> POST Entrega UC-05 -> cierre FA-2,
 * mas pruebas negativas 401/403, cross-repartidor, Recorrido cerrado, asignacion invalidada, idempotencia y 0 escrituras tras fallos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ale.http.writes.pedidos-uc03-enabled=true",
                "ale.http.writes.pedidos-uc43-enabled=true",
                "ale.http.writes.recorridos-uc43-enabled=true",
                "ale.http.writes.preparaciones-uc28-enabled=true",
                "ale.http.writes.entregas-uc05-enabled=true"
        })
@Testcontainers(disabledWithoutDocker = true)
class RecorridoEntregaCircuitoHttpExternoTest {
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
    @Autowired PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> post(String ruta, String token, String body) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + puerto + ruta))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String loginHttp(String nombreUsuario, String clavePlana) throws Exception {
        HttpResponse<String> res = post("/api/v1/auth/login", null,
                "{\"nombreUsuario\":\"" + nombreUsuario + "\",\"credencial\":\"" + clavePlana + "\"}");
        assertEquals(200, res.statusCode(), res.body());
        return JSON.readTree(res.body()).path("accessToken").asString();
    }

    private void asignarClaveReal(long idUsuario, String clavePlana) {
        String hash = passwordEncoder.encode(clavePlana);
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE \"USUARIO\" SET \"credencialProtegida\"=? WHERE \"idUsuario\"=?",
                hash, idUsuario));
    }

    @Test
    void circuitoCompletoPorHttpDesdeLoginHastaEntregaYCierreSinBypassJava() throws Exception {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idVendedor = f.actores.base;
        long idAdmin = f.actores.base + 1;
        long idProduccion = f.actores.base + 2;
        long rep1 = f.actores.base + 3;
        long rep2 = f.actores.base + 77;
        long rep3 = f.actores.base + 78;

        // Habilitar credenciales reales para login HTTP UC-01 y crear 2 repartidores adicionales
        String claveComun = "ClaveSegura#2026!";
        asignarClaveReal(idVendedor, claveComun);
        asignarClaveReal(idAdmin, claveComun);
        asignarClaveReal(idProduccion, claveComun);
        asignarClaveReal(rep1, claveComun);
        String hashComun = passwordEncoder.encode(claveComun);
        tx.executeWithoutResult(s -> {
            for (long r : new long[]{rep2, rep3}) {
                jdbc.update("""
                        INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta")
                        VALUES (?,?,?,'HABILITADO',false,now())
                        """, r, "uc03_" + r, hashComun);
                jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'REPARTIDOR',now())", r);
            }
        });

        String tokVendedor = loginHttp("uc03_" + idVendedor, claveComun);
        String tokAdmin = loginHttp("uc03_" + idAdmin, claveComun);
        String tokProd = loginHttp("uc03_" + idProduccion, claveComun);
        String tokRep1 = loginHttp("uc03_" + rep1, claveComun);
        String tokRep2 = loginHttp("uc03_" + rep2, claveComun);

        // 1) Seguridad de apertura Recorrido: 401 sin token y 403 para roles distintos de VENDEDOR
        assertEquals(401, post("/api/v1/recorridos", null,
                "{\"idUsuarioRepartidor\":" + rep1 + "}").statusCode());
        for (String otro : List.of(tokAdmin, tokProd, tokRep1)) {
            assertEquals(403, post("/api/v1/recorridos", otro,
                    "{\"idUsuarioRepartidor\":" + rep1 + "}").statusCode());
        }

        // 2) VENDEDOR abre Recorrido 1 para rep1 y Recorrido 2 para rep2 por HTTP
        HttpResponse<String> rAbrir1 = post("/api/v1/recorridos", tokVendedor,
                "{\"idUsuarioRepartidor\":" + rep1 + ",\"referenciaOperativa\":\"CIRCUITO-R1\"}");
        assertEquals(201, rAbrir1.statusCode(), rAbrir1.body());
        assertTrue(rAbrir1.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        long idRec1 = JSON.readTree(rAbrir1.body()).path("idRecorrido").asLong();

        // FA-1 por HTTP: segundo Recorrido ABIERTO para rep1 rechazado 409
        assertEquals(409, post("/api/v1/recorridos", tokVendedor,
                "{\"idUsuarioRepartidor\":" + rep1 + "}").statusCode());

        HttpResponse<String> rAbrir2 = post("/api/v1/recorridos", tokVendedor,
                "{\"idUsuarioRepartidor\":" + rep2 + ",\"referenciaOperativa\":\"CIRCUITO-R2\"}");
        assertEquals(201, rAbrir2.statusCode(), rAbrir2.body());
        long idRec2 = JSON.readTree(rAbrir2.body()).path("idRecorrido").asLong();

        // 3) Asignar+vincular Pedido a R1 por HTTP (primera asignacion)
        HttpResponse<String> rAsig1 = post("/api/v1/recorridos/" + idRec1 + "/asignaciones", tokVendedor,
                "{\"idPedido\":" + f.pedido + ",\"idUsuarioRepartidor\":" + rep1 + "}");
        assertEquals(201, rAsig1.statusCode(), rAsig1.body());
        assertEquals(1, JSON.readTree(rAsig1.body()).path("numeroAsignacion").asInt());

        // Cross-repartidor: intentar asignar rep2 sobre Recorrido de rep1 rechazado 409 con 0 escrituras
        assertEquals(409, post("/api/v1/recorridos/" + idRec1 + "/asignaciones", tokVendedor,
                "{\"idPedido\":" + f.pedido + ",\"idUsuarioRepartidor\":" + rep2 + ",\"motivoReasignacion\":\"Cross\"}").statusCode());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=?", Integer.class, f.pedido));
        assertEquals("ACTIVA", jdbc.queryForObject(
                "SELECT \"estadoPertenencia\" FROM \"RECORRIDO_ASIGNACION\" WHERE \"idPedido\"=? AND \"numeroAsignacion\"=1",
                String.class, f.pedido));

        // 4) Reasignar Pedido de R1 (rep1) hacia R2 (rep2) por HTTP
        HttpResponse<String> rAsig2 = post("/api/v1/recorridos/" + idRec2 + "/asignaciones", tokVendedor,
                "{\"idPedido\":" + f.pedido + ",\"idUsuarioRepartidor\":" + rep2 + ",\"motivoReasignacion\":\"Cambio de ruta HTTP\"}");
        assertEquals(201, rAsig2.statusCode(), rAsig2.body());
        assertEquals(2, JSON.readTree(rAsig2.body()).path("numeroAsignacion").asInt());
        assertEquals("DESVINCULADA_POR_REASIGNACION", jdbc.queryForObject(
                "SELECT \"estadoPertenencia\" FROM \"RECORRIDO_ASIGNACION\" WHERE \"idPedido\"=? AND \"numeroAsignacion\"=1",
                String.class, f.pedido));

        // Cerrar R1 (ya sin pendientes por reasignacion) por HTTP y comprobar que R1 cerrado rechaza nuevas asignaciones con 0 escrituras
        assertEquals(200, post("/api/v1/recorridos/" + idRec1 + "/cierre", tokVendedor, "{}").statusCode());
        HttpResponse<String> rPedExtra = post("/api/v1/pedidos", tokVendedor,
                JSON.writeValueAsString(f.actores.body()));
        assertEquals(201, rPedExtra.statusCode(), rPedExtra.body());
        long pedExtra = JSON.readTree(rPedExtra.body()).path("idPedido").asLong();
        assertEquals(409, post("/api/v1/recorridos/" + idRec1 + "/asignaciones", tokVendedor,
                "{\"idPedido\":" + pedExtra + ",\"idUsuarioRepartidor\":" + rep1 + "}").statusCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=?", Integer.class, pedExtra));

        // 5) PRODUCCION prepara UC-28 por HTTP
        HttpResponse<String> rPrep = post("/api/v1/pedidos/" + f.pedido + "/preparaciones", tokProd,
                "{\"idProducto\":" + f.producto + ",\"cantidadPreparada\":4}");
        assertEquals(201, rPrep.statusCode(), rPrep.body());

        // 6) REPARTIDOR 1 (asignacion invalidada por reasignacion) intenta entregar por HTTP => 403 con 0 escrituras
        UUID clave = UUID.randomUUID();
        String bodyEntrega = """
                {"detallesEntrega":[{"idProducto":%d,"cantidadEntregada":3}],"efectivoDeclaradoRecibido":120.00,"claveIdempotencia":"%s"}
                """.formatted(f.producto, clave);
        assertEquals(403, post("/api/v1/pedidos/" + f.pedido + "/entrega", tokRep1, bodyEntrega).statusCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"ENTREGA\" WHERE \"idPedido\"=?", Integer.class, f.pedido));

        // 7) FA-2 impide cerrar R2 mientras Pedido este PENDIENTE
        assertEquals(409, post("/api/v1/recorridos/" + idRec2 + "/cierre", tokVendedor, "{}").statusCode());

        // 8) REPARTIDOR 2 (asignado vigente) entrega UC-05 por HTTP => 201 Created + replay idempotente 200 OK
        HttpResponse<String> rEntrega = post("/api/v1/pedidos/" + f.pedido + "/entrega", tokRep2, bodyEntrega);
        assertEquals(201, rEntrega.statusCode(), rEntrega.body());
        JsonNode jsonEnt = JSON.readTree(rEntrega.body());
        assertTrue(jsonEnt.path("creado").asBoolean());
        assertEquals(idRec2, jsonEnt.path("idRecorrido").asLong());
        assertEquals("ENTREGADO", jsonEnt.path("estadoPedido").asString());
        assertEquals(0, new BigDecimal("270.00").compareTo(new BigDecimal(jsonEnt.path("cargoRealCe").asString())));
        assertEquals(1, jsonEnt.path("excedentesGenerados").size());

        HttpResponse<String> rReplay = post("/api/v1/pedidos/" + f.pedido + "/entrega", tokRep2, bodyEntrega);
        assertEquals(200, rReplay.statusCode(), rReplay.body());
        assertFalse(JSON.readTree(rReplay.body()).path("creado").asBoolean());

        // Conflicto con distinta cantidad y misma clave => 409 sin mutar entrega
        assertEquals(409, post("/api/v1/pedidos/" + f.pedido + "/entrega", tokRep2, """
                {"detallesEntrega":[{"idProducto":%d,"cantidadEntregada":2}],"efectivoDeclaradoRecibido":120.00,"claveIdempotencia":"%s"}
                """.formatted(f.producto, clave)).statusCode());

        // 9) Tras entregar, VENDEDOR cierra R2 por HTTP y habilita siguiente Recorrido para rep2
        assertEquals(200, post("/api/v1/recorridos/" + idRec2 + "/cierre", tokVendedor, "{}").statusCode());
        HttpResponse<String> rAbrir2Sig = post("/api/v1/recorridos", tokVendedor,
                "{\"idUsuarioRepartidor\":" + rep2 + ",\"referenciaOperativa\":\"CIRCUITO-R2-SIG\"}");
        assertEquals(201, rAbrir2Sig.statusCode(), rAbrir2Sig.body());

        // 10) Tambien compatibilidad UC-43 clasico -> vinculacion explicita -> fallo en asignacion UC-43 posterior sobre cerrado
        long idRec3 = JSON.readTree(post("/api/v1/recorridos", tokVendedor,
                "{\"idUsuarioRepartidor\":" + rep3 + "}").body()).path("idRecorrido").asLong();
        assertEquals(201, post("/api/v1/pedidos/" + pedExtra + "/asignacion-reparto", tokVendedor,
                "{\"idUsuarioRepartidor\":" + rep3 + "}").statusCode());
        assertEquals(201, post("/api/v1/recorridos/" + idRec3 + "/vinculaciones", tokVendedor,
                "{\"idPedido\":" + pedExtra + "}").statusCode());
        assertEquals("ACTIVA", jdbc.queryForObject(
                "SELECT \"estadoPertenencia\" FROM \"RECORRIDO_ASIGNACION\" WHERE \"idPedido\"=?",
                String.class, pedExtra));
    }
}
