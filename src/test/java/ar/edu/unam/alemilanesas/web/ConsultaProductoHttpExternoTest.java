package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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

/** B4-03-05 / UC35: consulta HTTP solo lectura en puerto real y PostgreSQL 17.11. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ConsultaProductoHttpExternoTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    private static final AtomicLong SEQ = new AtomicLong(950000);
    private static final String RUTA = "/api/v1/productos";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private long base;
    private long idElaboradoActivo;
    private long idReventaInactivo;
    private UUID sesionAdmin;
    private String admin, vendedor, produccion, repartidor, proveedor, pendiente;

    @BeforeEach
    void fixture() {
        base = SEQ.addAndGet(20);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "UNIDAD_MEDIDA"("codigoUnidad","nombre") VALUES('UN','Unidad'),('KG','Kilogramo')
                    ON CONFLICT("codigoUnidad") DO NOTHING
                    """);
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Admin UC35 HTTP'),('VENDEDOR','Vendedor UC35 HTTP'),
                    ('RESPONSABLE_PRODUCCION','Produccion UC35 HTTP'),
                    ('REPARTIDOR','Repartidor UC35 HTTP'),('PROVEEDOR','Proveedor UC35 HTTP')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            String[] roles = {"ADMINISTRADOR", "VENDEDOR", "RESPONSABLE_PRODUCCION",
                    "REPARTIDOR", "PROVEEDOR", "ADMINISTRADOR"};
            for (int i = 0; i < roles.length; i++) {
                jdbc.update("""
                        INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta")
                        VALUES(?,?,'HASH_SINTETICO','HABILITADO',?,clock_timestamp())
                        """, base + i, "usuario_uc35_http_" + (base + i), i == 5);
                jdbc.update("""
                        INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                        VALUES(?,?,clock_timestamp())
                        """, base + i, roles[i]);
            }
            jdbc.update("""
                    INSERT INTO "PROVEEDOR"("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                    VALUES(?,?,'Proveedor sintetico UC35','ACTIVO',clock_timestamp())
                    """, base + 8, String.format("%011d", 30000000000L + base));
            jdbc.update("""
                    INSERT INTO "USUARIO_PROVEEDOR"("idUsuario","idProveedor","fechaHoraVinculacion")
                    VALUES(?,?,clock_timestamp())
                    """, base + 4, base + 8);

            idElaboradoActivo = base + 10;
            idReventaInactivo = base + 11;
            jdbc.update("""
                    INSERT INTO "PRODUCTO"("idProducto","nombre","nombreNormalizado","descripcion",
                        "tipoProducto","codigoUnidadVenta","stockActualReventa",
                        "costoPromedioActualReventa","activo")
                    VALUES
                    (?, ?, ?, 'Elaborado activo prueba', 'ELABORADO', 'KG', NULL, NULL, true),
                    (?, ?, ?, 'Reventa inactivo con stock y costo internos', 'REVENTA', 'UN', 45.500, 1850.75, false)
                    """,
                    idElaboradoActivo, "Milanesa Suprema " + base, "milanesa suprema " + base,
                    idReventaInactivo, "Pan Rallado Extra " + base, "pan rallado extra " + base);

            sesionAdmin = UUID.randomUUID();
            admin = sesion(base, sesionAdmin);
            vendedor = sesion(base + 1, UUID.randomUUID());
            produccion = sesion(base + 2, UUID.randomUUID());
            repartidor = sesion(base + 3, UUID.randomUUID());
            proveedor = sesion(base + 4, UUID.randomUUID());
            pendiente = sesion(base + 5, UUID.randomUUID());
        });
    }

    private String sesion(long usuario, UUID id) {
        String token = TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES(?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '29 minutes')
                """, id, usuario, TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private HttpResponse<String> get(String token, String query) throws Exception {
        String url = "http://127.0.0.1:" + port + RUTA + (query == null || query.isEmpty() ? "" : "?" + query);
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void adminYVendedorConsultanActivosEInactivosSinExponerStockNiCostos() throws Exception {
        for (String token : List.of(admin, vendedor)) {
            var porId = get(token, "idProducto=" + idReventaInactivo);
            assertEquals(200, porId.statusCode(), porId.body());
            assertTrue(porId.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            var arr = json.readTree(porId.body());
            assertEquals(1, arr.size(), porId.body());
            var item = arr.get(0);
            assertEquals(6, item.size(), porId.body());
            assertEquals(idReventaInactivo, item.get("idProducto").asLong());
            assertEquals("Pan Rallado Extra " + base, item.get("nombre").asText());
            assertEquals("REVENTA", item.get("tipoProducto").asText());
            assertEquals("UN", item.get("codigoUnidadVenta").asText());
            assertFalse(item.get("activo").asBoolean());
            assertFalse(porId.body().contains("stockActualReventa"));
            assertFalse(porId.body().contains("costoPromedioActualReventa"));
            assertFalse(porId.body().contains("nombreNormalizado"));
            assertFalse(porId.body().contains("1850.75"));
        }
    }

    @Test
    void filtraPorNombreNormalizadoTipoYEstadoYDevuelveListaVaciaSinCoincidencias() throws Exception {
        var porNombre = get(vendedor, "nombre=SUPREMA%20" + base + "&tipoProducto=ELABORADO&activo=true");
        assertEquals(200, porNombre.statusCode(), porNombre.body());
        var arr = json.readTree(porNombre.body());
        assertEquals(1, arr.size());
        assertEquals(idElaboradoActivo, arr.get(0).get("idProducto").asLong());

        var sinCoincidencia = get(admin, "nombre=Inexistente_" + base + "&activo=true");
        assertEquals(200, sinCoincidencia.statusCode(), sinCoincidencia.body());
        assertTrue(sinCoincidencia.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(0, json.readTree(sinCoincidencia.body()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "campoExtra=1",
            "idProducto=0",
            "idProducto=-5",
            "idProducto=abc",
            "idProducto=%20",
            "idProducto=1&idProducto=2",
            "activo=si",
            "activo=1",
            "tipoProducto=MIXTO",
            "tipoProducto=elaborado",
            "nombre=%00",
            "nombre=%20%20"
    })
    void rechazaCriteriosYParametrosInvalidosCon400(String queryInvalida) throws Exception {
        var r = get(admin, queryInvalida);
        assertEquals(400, r.statusCode(), queryInvalida + " -> " + r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals("SOLICITUD_INVALIDA", json.readTree(r.body()).get("codigo").asText());
    }

    @Test
    void filtraPorActivoFalseDevuelveSoloInactivos() throws Exception {
        var r = get(vendedor, "nombre=Extra%20" + base + "&activo=false");
        assertEquals(200, r.statusCode(), r.body());
        var arr = json.readTree(r.body());
        assertEquals(1, arr.size(), r.body());
        assertEquals(idReventaInactivo, arr.get(0).get("idProducto").asLong());
        assertFalse(arr.get(0).get("activo").asBoolean());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    @Test
    void rechazaNombreMayorA150CaracteresCon400() throws Exception {
        String nombreLargo = "a".repeat(151);
        var r = get(admin, "nombre=" + nombreLargo);
        assertEquals(400, r.statusCode(), r.body());
        assertEquals("SOLICITUD_INVALIDA", json.readTree(r.body()).get("codigo").asText());
    }

    @Test
    void sesionRevocadaEnCalienteDevuelve401() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE \"SESION_USUARIO\" SET \"fechaHoraRevocacion\"=clock_timestamp() WHERE \"idSesionUsuario\"=?",
                sesionAdmin));
        var r = get(admin, "idProducto=" + idElaboradoActivo);
        assertEquals(401, r.statusCode(), r.body());
    }

    @Test
    void cambioDeRolEnCalienteActualizaAutorizacion() throws Exception {
        // produccion NO puede consultar
        assertEquals(403, get(produccion, "idProducto=" + idElaboradoActivo).statusCode());
        // le asignamos rol VENDEDOR en caliente
        tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO \"USUARIO_ROL\"(\"idUsuario\",\"codigoRol\",\"fechaHoraAsignacion\") VALUES(?,?,clock_timestamp())",
                base + 2, "VENDEDOR"));
        // ahora sí puede
        assertEquals(200, get(produccion, "idProducto=" + idElaboradoActivo).statusCode());
    }

    @Test
    void rdRp007002RechazaProduccionRepartidorProveedorPendienteCookieYSinToken() throws Exception {
        String q = "idProducto=" + idElaboradoActivo;
        assertEquals(401, get(null, q).statusCode());
        assertEquals(401, get(sesionAdmin.toString(), q).statusCode());
        for (String noAutorizado : List.of(produccion, repartidor, proveedor, pendiente)) {
            assertEquals(403, get(noAutorizado, q).statusCode());
        }
        var cookie = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + RUTA + "?" + q))
                .header("Cookie", "__Host-ale_session=" + admin).GET().build();
        assertEquals(401, http.send(cookie, HttpResponse.BodyHandlers.ofString()).statusCode());

        // UC30 POST sigue apagado por defecto en este contexto sin flag.
        var postBloqueado = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + RUTA))
                .header("Authorization", "Bearer " + admin)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"nombre\":\"Bloqueado\",\"tipoProducto\":\"REVENTA\",\"codigoUnidadVenta\":\"UN\"}"))
                .build();
        assertEquals(403, http.send(postBloqueado, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
