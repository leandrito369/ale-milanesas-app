package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** UC17 B4: alta real en PostgreSQL 17.11, rol ADMIN, transaccion UC18 y salida mínima. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ale.http.writes.usuarios-uc17-enabled=true")
@Testcontainers(disabledWithoutDocker = true)
class RegistroUsuarioHttpExternoTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    private static final String RUTA = "/api/v1/usuarios";
    private static final AtomicLong SECUENCIA = new AtomicLong(980000);
    @Value("${local.server.port}") int puerto;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private long base;
    private UUID sesionAdmin;
    private String admin, vendedor, proveedor, pendiente;

    @BeforeEach
    void preparar() {
        base = SECUENCIA.addAndGet(20);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Administrador UC17 HTTP'),
                    ('VENDEDOR','Vendedor UC17 HTTP'),
                    ('REPARTIDOR','Repartidor UC17 HTTP'),
                    ('PROVEEDOR','Proveedor UC17 HTTP')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            String[] roles = {"ADMINISTRADOR","VENDEDOR","PROVEEDOR","ADMINISTRADOR"};
            for (int i = 0; i < roles.length; i++) {
                jdbc.update("""
                        INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta")
                        VALUES(?,?,'HASH_FICTICIO','HABILITADO',?,clock_timestamp())
                        """, base + i, "actor_uc17_http_" + (base + i), i == 3);
                jdbc.update("""
                        INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                        VALUES(?,?,clock_timestamp())
                        """, base + i, roles[i]);
            }
            jdbc.update("""
                    INSERT INTO "PROVEEDOR"("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                    VALUES(?,?,'Proveedor vinculado UC17','ACTIVO',clock_timestamp()),
                          (?,?,'Proveedor disponible UC17','ACTIVO',clock_timestamp()),
                          (?,?,'Proveedor inactivo UC17','INACTIVO',clock_timestamp())
                    """, base + 50, cuit(base + 50), base + 51, cuit(base + 51),
                    base + 52, cuit(base + 52));
            jdbc.update("""
                    INSERT INTO "USUARIO_PROVEEDOR"("idUsuario","idProveedor","fechaHoraVinculacion")
                    VALUES(?,?,clock_timestamp())
                    """, base + 2, base + 50);
            sesionAdmin = UUID.randomUUID();
            admin = sesion(base, sesionAdmin);
            vendedor = sesion(base + 1, UUID.randomUUID());
            proveedor = sesion(base + 2, UUID.randomUUID());
            pendiente = sesion(base + 3, UUID.randomUUID());
        });
    }

    private static String cuit(long numero) {
        return String.format("%011d", 30000000000L + numero);
    }

    private String sesion(long usuario, UUID id) {
        String token = TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES(?,?,?,clock_timestamp()-interval '1 minute',
                           clock_timestamp()+interval '29 minutes')
                """, id, usuario, TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private Map<String, Object> interno(String nombre) {
        return Map.of("nombreUsuario", nombre, "credencialInicial", "Clave-Sintetica-UC17-1!",
                "rolesIniciales", List.of("VENDEDOR", "REPARTIDOR"));
    }

    private HttpResponse<String> post(String token, Object datos) throws Exception {
        return postTexto(token, json.writeValueAsString(datos));
    }

    private HttpResponse<String> postTexto(String token, String datos) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + RUTA))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.POST(HttpRequest.BodyPublishers.ofString(datos)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int cantidad(String nombre) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO" WHERE lower("nombreUsuario")=lower(?)
                """, Integer.class, nombre);
    }

    @Test
    void altaInternaSoloCuatroCamposHashYEventosDeRoles() throws Exception {
        String nombre = "interno_uc17_http_" + base;
        var respuesta = post(admin, interno(nombre));
        assertEquals(201, respuesta.statusCode(), respuesta.body());
        assertTrue(respuesta.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var salida = json.readTree(respuesta.body());
        assertEquals(4, salida.size(), respuesta.body());
        long usuario = salida.get("idUsuario").asLong();
        assertTrue(usuario > 0);
        assertEquals("HABILITADO", salida.get("estado").asText());
        assertFalse(salida.get("cambioCredencialPendiente").asBoolean());
        Set<String> roles = new java.util.HashSet<>();
        salida.get("roles").forEach(rol -> roles.add(rol.asText()));
        assertEquals(Set.of("VENDEDOR", "REPARTIDOR"), roles);
        assertFalse(respuesta.body().contains(nombre));
        assertFalse(respuesta.body().contains("Clave-Sintetica"));
        assertFalse(respuesta.body().contains("credencialProtegida"));
        String hash = jdbc.queryForObject("""
                SELECT "credencialProtegida" FROM "USUARIO" WHERE "idUsuario"=?
                """, String.class, usuario);
        assertTrue(encoder.matches("Clave-Sintetica-UC17-1!", hash));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM "EVENTO_ASIGNACION_ROL"
                 WHERE "idUsuario"=? AND "tipoEvento"='ASIGNACION'
                   AND "idUsuarioAdministrador"=?
                """, Integer.class, usuario, base));
    }

    @Test
    void altaProveedorTemporalConVinculoYExclusividad() throws Exception {
        var datos = Map.of("nombreUsuario", "proveedor_uc17_http_" + base,
                "credencialInicial", "Temporal-UC17-1!",
                "rolesIniciales", List.of("PROVEEDOR"), "idProveedor", base + 51);
        var respuesta = post(admin, datos);
        assertEquals(201, respuesta.statusCode(), respuesta.body());
        var salida = json.readTree(respuesta.body());
        assertTrue(salida.get("cambioCredencialPendiente").asBoolean());
        assertEquals("PROVEEDOR", salida.get("roles").get(0).asText());
        assertEquals(base + 51, jdbc.queryForObject("""
                SELECT "idProveedor" FROM "USUARIO_PROVEEDOR" WHERE "idUsuario"=?
                """, Long.class, salida.get("idUsuario").asLong()));
        assertEquals(400, post(admin, Map.of(
                "nombreUsuario", "otro_prov_uc17_http_" + base,
                "credencialInicial", "Temporal-UC17-2!",
                "rolesIniciales", List.of("PROVEEDOR"), "idProveedor", base + 51)).statusCode());
        assertEquals(0, cantidad("otro_prov_uc17_http_" + base));
    }

    @Test
    void invalidosYNombreDuplicadoNoCreanFilasParciales() throws Exception {
        String nombre = "invalido_uc17_http_" + base;
        for (Object datos : List.of(
                Map.of(),
                Map.of("nombreUsuario", nombre, "credencialInicial", "Clave-1!"),
                Map.of("nombreUsuario", nombre, "credencialInicial", "Clave-1!", "rolesIniciales", List.of()),
                Map.of("nombreUsuario", nombre, "credencialInicial", "Clave-1!",
                        "rolesIniciales", List.of("VENDEDOR","VENDEDOR")),
                Map.of("nombreUsuario", nombre, "credencialInicial", "Clave-1!",
                        "rolesIniciales", List.of("PROVEEDOR","VENDEDOR"), "idProveedor", base + 51),
                Map.of("nombreUsuario", nombre, "credencialInicial", "Clave-1!",
                        "rolesIniciales", List.of("PROVEEDOR"), "idProveedor", base + 52),
                Map.of("nombreUsuario", nombre, "credencialInicial", " ",
                        "rolesIniciales", List.of("VENDEDOR")),
                Map.of("nombreUsuario", nombre, "credencialInicial", "X".repeat(73),
                        "rolesIniciales", List.of("VENDEDOR")),
                Map.of("nombreUsuario", nombre, "credencialInicial", "Clave-1!",
                        "rolesIniciales", List.of("ROL_INVALIDO")),
                Map.of("nombreUsuario", nombre, "credencialInicial", "Clave-1!",
                        "rolesIniciales", List.of("VENDEDOR"), "estado", "HABILITADO"))) {
            var respuesta = post(admin, datos);
            assertEquals(400, respuesta.statusCode(), String.valueOf(datos) + respuesta.body());
            assertEquals("SOLICITUD_INVALIDA",
                    json.readTree(respuesta.body()).get("codigo").asText());
        }
        for (String malformado : List.of("{", "[]", "null")) {
            assertEquals(400, postTexto(admin, malformado).statusCode(), malformado);
        }
        assertEquals(0, cantidad(nombre));
        String unico = "unico_uc17_http_" + base;
        assertEquals(201, post(admin, interno(unico)).statusCode());
        assertEquals(400, post(admin, interno(unico.toUpperCase(java.util.Locale.ROOT))).statusCode());
        assertEquals(1, cantidad(unico));
    }

    @Test
    void soloAdminYBearerSinCookiesNiSesionRevocada() throws Exception {
        String nombre = "prohibido_uc17_http_" + base;
        var datos = interno(nombre);
        assertEquals(401, post(null, datos).statusCode());
        assertEquals(401, post(sesionAdmin.toString(), datos).statusCode());
        for (String token : List.of(vendedor, proveedor, pendiente)) {
            assertEquals(403, post(token, datos).statusCode());
        }
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + RUTA))
                .header("Cookie", "__Host-ale_session=" + admin)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(datos))).build();
        assertEquals(401, http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
        jdbc.update("""
                UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=clock_timestamp()
                 WHERE "idSesionUsuario"=?
                """, sesionAdmin);
        assertEquals(401, post(admin, datos).statusCode());
        assertEquals(0, cantidad(nombre));
    }

    @Test
    void dosAltasConcurrentesConElMismoNombreNoDuplicanLaCuenta() throws Exception {
        String nombre = "concurrente_uc17_http_" + base;
        try (var ejecutor = Executors.newFixedThreadPool(2)) {
            var inicio = new CountDownLatch(1);
            var tareas = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                tareas.add(ejecutor.submit(() -> {
                    if (!inicio.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("No comenzo la prueba concurrente");
                    }
                    return post(admin, interno(nombre)).statusCode();
                }));
            }
            inicio.countDown();
            var codigos = List.of(tareas.get(0).get(45, TimeUnit.SECONDS),
                    tareas.get(1).get(45, TimeUnit.SECONDS));
            assertEquals(1, codigos.stream().filter(x -> x == 201).count(), codigos.toString());
            assertEquals(1, codigos.stream().filter(x -> x == 400).count(), codigos.toString());
        }
        assertEquals(1, cantidad(nombre));
    }

    @Test
    void errorEnAuditoriaRevierteUsuarioYRolesSinRevelarSql() throws Exception {
        String nombre = "rollback_uc17_http_" + base;
        jdbc.execute("""
                CREATE FUNCTION "fn_uc17_http_error"() RETURNS trigger LANGUAGE plpgsql
                AS $$ BEGIN RAISE EXCEPTION 'Detalle privado UC17'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER "tr_uc17_http_error" BEFORE INSERT
                ON "EVENTO_ASIGNACION_ROL" FOR EACH ROW
                EXECUTE FUNCTION "fn_uc17_http_error"()
                """);
        try {
            var respuesta = post(admin, interno(nombre));
            assertEquals(503, respuesta.statusCode(), respuesta.body());
            assertEquals("PERSISTENCIA_NO_DISPONIBLE",
                    json.readTree(respuesta.body()).get("codigo").asText());
            assertFalse(respuesta.body().contains("Detalle privado"));
            assertEquals(0, cantidad(nombre));
        } finally {
            jdbc.execute("DROP TRIGGER \"tr_uc17_http_error\" ON \"EVENTO_ASIGNACION_ROL\"");
            jdbc.execute("DROP FUNCTION \"fn_uc17_http_error\"()");
        }
    }
}
