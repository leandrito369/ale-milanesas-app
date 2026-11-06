package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** I1-01 / UC-56: consulta ADMIN sobre HTTP real y PostgreSQL 17.11. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ConsultaUsuarioHttpExternoTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }

    private static final String RUTA = "/api/v1/usuarios";
    private static final AtomicLong SECUENCIA = new AtomicLong(960000);
    private static final Set<String> CAMPOS_AUTORIZADOS = Set.of(
            "idUsuario", "nombreUsuario", "estado", "cambioCredencialPendiente",
            "roles", "idProveedor", "pedidosPendientesAsignados");

    @Value("${local.server.port}") int puerto;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private long base;
    private UUID sesionAdmin;
    private String admin, vendedor, produccion, repartidor, proveedor, pendiente;

    @BeforeEach
    void datosFicticios() {
        base = SECUENCIA.addAndGet(20);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Administrador UC56 HTTP'),
                    ('VENDEDOR','Vendedor UC56 HTTP'),
                    ('RESPONSABLE_PRODUCCION','Produccion UC56 HTTP'),
                    ('REPARTIDOR','Repartidor UC56 HTTP'),
                    ('PROVEEDOR','Proveedor UC56 HTTP')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            String[] roles = {"ADMINISTRADOR", "VENDEDOR", "VENDEDOR", "RESPONSABLE_PRODUCCION",
                    "REPARTIDOR", "PROVEEDOR", "ADMINISTRADOR"};
            for (int i = 0; i < roles.length; i++) {
                jdbc.update("""
                        INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta")
                        VALUES(?,?,'HASH_SINTETICO',?,?,clock_timestamp())
                        """, base + i, "usuario_uc56_http_" + (base + i),
                        i == 2 ? "DESHABILITADO" : "HABILITADO", i == 6);
                jdbc.update("""
                        INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                        VALUES(?,?,clock_timestamp())
                        """, base + i, roles[i]);
            }
            jdbc.update("""
                    INSERT INTO "PROVEEDOR"("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                    VALUES(?,?,'Proveedor ficticio UC56','ACTIVO',clock_timestamp())
                    """, base + 50, String.format("%011d", 30000000000L + base));
            jdbc.update("""
                    INSERT INTO "USUARIO_PROVEEDOR"("idUsuario","idProveedor","fechaHoraVinculacion")
                    VALUES(?,?,clock_timestamp())
                    """, base + 5, base + 50);

            sesionAdmin = UUID.randomUUID();
            admin = nuevaSesion(base, sesionAdmin);
            vendedor = nuevaSesion(base + 1, UUID.randomUUID());
            produccion = nuevaSesion(base + 3, UUID.randomUUID());
            repartidor = nuevaSesion(base + 4, UUID.randomUUID());
            proveedor = nuevaSesion(base + 5, UUID.randomUUID());
            pendiente = nuevaSesion(base + 6, UUID.randomUUID());
        });
    }

    private String nuevaSesion(long idUsuario, UUID idSesion) {
        String token = TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES(?,?,?,clock_timestamp()-interval '1 minute',
                           clock_timestamp()+interval '29 minutes')
                """, idSesion, idUsuario, TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private HttpResponse<String> get(String token, String query) throws Exception {
        String destino = "http://127.0.0.1:" + puerto + RUTA
                + (query == null || query.isEmpty() ? "" : "?" + query);
        var req = HttpRequest.newBuilder(URI.create(destino))
                .timeout(Duration.ofSeconds(20)).GET();
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void administradorFiltraPorNombreEstadoYRolConProyeccionExacta() throws Exception {
        var respuesta = get(admin, "nombreUsuario=uc56_http_" + (base + 1)
                + "&estado=HABILITADO&codigoRol=VENDEDOR");
        assertEquals(200, respuesta.statusCode(), respuesta.body());
        assertTrue(respuesta.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var filas = json.readTree(respuesta.body());
        assertEquals(1, filas.size(), respuesta.body());
        var usuario = filas.get(0);
        assertEquals(base + 1, usuario.get("idUsuario").asLong());
        assertEquals("HABILITADO", usuario.get("estado").asText());
        assertEquals("VENDEDOR", usuario.get("roles").get(0).asText());
        assertFalse(usuario.get("cambioCredencialPendiente").asBoolean());
        assertTrue(usuario.get("idProveedor").isNull());
        assertEquals(0, usuario.get("pedidosPendientesAsignados").asLong());
        assertEquals(7, usuario.size());
        CAMPOS_AUTORIZADOS.forEach(campo -> assertTrue(usuario.has(campo), campo));
        assertFalse(respuesta.body().contains("credencialProtegida"));
        assertFalse(respuesta.body().contains("tokenProtegido"));
        assertFalse(respuesta.body().contains("idSesionUsuario"));
        assertFalse(respuesta.body().contains("HASH_SINTETICO"));
    }

    @Test
    void admiteProveedorYDeshabilitadoSinOcultarSuIdentidadAdministrativa() throws Exception {
        var proveedorEncontrado = get(admin, "codigoRol=PROVEEDOR&nombreUsuario=" + (base + 5));
        assertEquals(200, proveedorEncontrado.statusCode(), proveedorEncontrado.body());
        var cuenta = json.readTree(proveedorEncontrado.body()).get(0);
        assertEquals(base + 5, cuenta.get("idUsuario").asLong());
        assertEquals(base + 50, cuenta.get("idProveedor").asLong());
        assertFalse(proveedorEncontrado.body().contains("razonSocial"));
        assertFalse(proveedorEncontrado.body().contains("cuit"));

        var deshabilitado = get(admin, "estado=DESHABILITADO&nombreUsuario=" + (base + 2));
        assertEquals(200, deshabilitado.statusCode(), deshabilitado.body());
        assertEquals(base + 2, json.readTree(deshabilitado.body()).get(0).get("idUsuario").asLong());
    }

    @Test
    void ausenciasYComodinesSonLiteralesSinEnumerarCuentas() throws Exception {
        var sinCoincidencia = get(admin, "nombreUsuario=cuenta_inexistente_" + base);
        assertEquals(200, sinCoincidencia.statusCode());
        assertEquals(0, json.readTree(sinCoincidencia.body()).size());
        assertEquals(0, json.readTree(get(admin, "nombreUsuario=%25").body()).size());
        assertEquals(0, json.readTree(get(admin, "nombreUsuario=%5F%5F%5F").body()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "nombreUsuario=%20", "nombreUsuario=%00", "nombreUsuario=a&nombreUsuario=b",
            "codigoRol=SUPERADMIN", "codigoRol=admin", "estado=PENDIENTE",
            "estado=habilitado", "campoNoAdmitido=1", "codigoRol=VENDEDOR&estado=HABILITADO&estado=DESHABILITADO"
    })
    void rechazaFiltrosInvalidosConRespuestaNeutra(String filtro) throws Exception {
        var respuesta = get(admin, filtro);
        assertEquals(400, respuesta.statusCode(), filtro + " -> " + respuesta.body());
        assertEquals("SOLICITUD_INVALIDA", json.readTree(respuesta.body()).get("codigo").asText());
        assertTrue(respuesta.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    @Test
    void sinNingunCriterioRechazaEnumeracionGeneral() throws Exception {
        var respuesta = get(admin, null);
        assertEquals(400, respuesta.statusCode(), respuesta.body());
        assertEquals("SOLICITUD_INVALIDA", json.readTree(respuesta.body()).get("codigo").asText());
    }

    @Test
    void soloAdminPuedeBuscar() throws Exception {
        String filtro = "nombreUsuario=" + base;
        for (String token : List.of(vendedor, produccion, repartidor, proveedor, pendiente)) {
            assertEquals(403, get(token, filtro).statusCode());
        }
        assertEquals(401, get(null, filtro).statusCode());
        assertEquals(401, get(sesionAdmin.toString(), filtro).statusCode());
    }

    @Test
    void noAceptaCookieComoIdentidad() throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + RUTA
                + "?nombreUsuario=" + base))
                .header("Cookie", "__Host-ale_session=" + admin).GET().build();
        assertEquals(401, http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void revocacionDeSesionCambiaElSiguienteRequestA401() throws Exception {
        assertEquals(200, get(admin, "nombreUsuario=" + base).statusCode());
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=clock_timestamp()
                 WHERE "idSesionUsuario"=?
                """, sesionAdmin));
        assertEquals(401, get(admin, "nombreUsuario=" + base).statusCode());
    }

    @Test
    void noAbreMutacionesNiAlteraConsultaComercialExistente() throws Exception {
        var post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + RUTA))
                .header("Authorization", "Bearer " + admin)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        assertEquals(403, http.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());

        var comercial = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto
                + RUTA + "/proveedores?idUsuario=" + (base + 5)))
                .header("Authorization", "Bearer " + admin).GET().build();
        var respuesta = http.send(comercial, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respuesta.statusCode(), respuesta.body());
        assertEquals(base + 5, json.readTree(respuesta.body()).get(0).get("idUsuario").asLong());
    }
}
