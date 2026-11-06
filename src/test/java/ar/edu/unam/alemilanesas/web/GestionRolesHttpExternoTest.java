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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** UC18 B4: RBAC, F07, delta de Rol, eventos y sesiones en HTTP/PostgreSQL real. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="ale.http.writes.usuarios-uc18-enabled=true")
@Testcontainers(disabledWithoutDocker=true)
class GestionRolesHttpExternoTest {
    @Container
    static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url",db::getJdbcUrl);
        registro.add("spring.datasource.username",db::getUsername);
        registro.add("spring.datasource.password",db::getPassword);
    }

    private static final AtomicLong SECUENCIA=new AtomicLong(997000);
    private static final String RUTA="/api/v1/usuarios";
    private static final String QUITAR_ADMIN="{\"rolesQuitar\":[\"ADMINISTRADOR\"]}";

    @Value("${local.server.port}") int puerto;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private long base;
    private UUID sesionDestino;
    private String adminA,adminB,vendedor,proveedor,pendiente;

    @BeforeEach
    void preparar() {
        base=SECUENCIA.addAndGet(30);
        // Cada prueba usa su propia base efímera Testcontainers: eliminar
        // usuarios de pruebas previas para evaluar F07 sin otros Admin residuales.
        jdbc.execute("TRUNCATE TABLE \"USUARIO\" CASCADE");
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Administrador UC18 HTTP'),
                    ('VENDEDOR','Vendedor UC18 HTTP'),
                    ('REPARTIDOR','Repartidor UC18 HTTP'),
                    ('PROVEEDOR','Proveedor UC18 HTTP')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            String[] roles={"ADMINISTRADOR","ADMINISTRADOR","VENDEDOR",
                    "VENDEDOR","PROVEEDOR","VENDEDOR"};
            for (int i=0;i<roles.length;i++) {
                jdbc.update("""
                        INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta")
                        VALUES(?,?,'HASH_SINTETICO','HABILITADO',?,clock_timestamp())
                        """,base+i,"usuario_uc18_http_"+(base+i),i==5);
                jdbc.update("""
                        INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                        VALUES(?,?,clock_timestamp())
                        """,base+i,roles[i]);
            }
            // Ambos administradores no pendientes tienen VENDEDOR para probar F07
            // incluso cuando retirar ADMIN no deja al usuario sin Roles.
            for (int i=0;i<2;i++) {
                jdbc.update("""
                        INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                        VALUES(?,'VENDEDOR',clock_timestamp())
                        """,base+i);
            }
            jdbc.update("""
                    INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                    VALUES(?,'REPARTIDOR',clock_timestamp())
                    """,base+3);
            jdbc.update("""
                    INSERT INTO "PROVEEDOR"("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                    VALUES(?,?,'Proveedor sintetico UC18','ACTIVO',clock_timestamp())
                    """,base+15,String.format("%011d",32000000000L+base));
            jdbc.update("""
                    INSERT INTO "USUARIO_PROVEEDOR"("idUsuario","idProveedor","fechaHoraVinculacion")
                    VALUES(?,?,clock_timestamp())
                    """,base+4,base+15);
            adminA=sesion(base,UUID.randomUUID());
            adminB=sesion(base+1,UUID.randomUUID());
            vendedor=sesion(base+2,UUID.randomUUID());
            sesionDestino=UUID.randomUUID();
            sesion(base+3,sesionDestino);
            proveedor=sesion(base+4,UUID.randomUUID());
            pendiente=sesion(base+5,UUID.randomUUID());
        });
    }

    private String sesion(long usuario,UUID id) {
        String token=TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES(?,?,?,clock_timestamp()-interval '1 minute',
                           clock_timestamp()+interval '29 minutes')
                """,id,usuario,TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private HttpResponse<String> patch(String token,long usuario,String cuerpo) throws Exception {
        var pedido=HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:"+puerto+RUTA+"/"+usuario+"/roles"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString(cuerpo));
        if (token!=null) pedido.header("Authorization","Bearer "+token);
        return http.send(pedido.build(),HttpResponse.BodyHandlers.ofString());
    }

    private int contar(String tabla,long usuario) {
        return jdbc.queryForObject("SELECT count(*) FROM \""+tabla+"\" WHERE \"idUsuario\"=?",
                Integer.class,usuario);
    }

    private int eventos(long usuario,String tipo) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM "EVENTO_ASIGNACION_ROL"
                 WHERE "idUsuario"=? AND "tipoEvento"=?
                """,Integer.class,usuario,tipo);
    }

    private Set<String> roles(long usuario) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT "codigoRol" FROM "USUARIO_ROL" WHERE "idUsuario"=?
                """,String.class,usuario));
    }

    @Test
    void deltaMixtoMantieneInvariantesAuditaYRevocaSesiones() throws Exception {
        var r=patch(adminA,base+3,
                "{\"rolesAgregar\":[\"ADMINISTRADOR\"],\"rolesQuitar\":[\"VENDEDOR\"]}");
        assertEquals(200,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var salida=json.readTree(r.body());
        assertEquals(3,salida.size(),r.body());
        assertEquals(base+3,salida.get("idUsuario").asLong());
        assertTrue(salida.get("huboCambio").asBoolean());
        Set<String> devueltos=new java.util.HashSet<>();
        salida.get("rolesVigentes").forEach(rol->devueltos.add(rol.asText()));
        assertEquals(Set.of("ADMINISTRADOR","REPARTIDOR"),devueltos);
        assertEquals(devueltos,roles(base+3));
        assertEquals(1,eventos(base+3,"ASIGNACION"));
        assertEquals(1,eventos(base+3,"REVOCACION"));
        assertEquals(2,jdbc.queryForObject("""
                SELECT count(*) FROM "EVENTO_ASIGNACION_ROL"
                 WHERE "idUsuario"=? AND "idUsuarioAdministrador"=?
                """,Integer.class,base+3,base));
        assertNotNull(jdbc.queryForObject("""
                SELECT "fechaHoraRevocacion" FROM "SESION_USUARIO"
                 WHERE "idSesionUsuario"=?
                """,Object.class,sesionDestino));
        assertFalse(r.body().contains("tokenProtegido"));
        assertFalse(r.body().contains("credencialProtegida"));
    }

    @Test
    void a3NoOpNoGeneraEventosNiRevocaSesiones() throws Exception {
        var r=patch(adminA,base+3,
                "{\"rolesAgregar\":[\"VENDEDOR\"],\"rolesQuitar\":[\"ADMINISTRADOR\"]}");
        assertEquals(200,r.statusCode(),r.body());
        assertFalse(json.readTree(r.body()).get("huboCambio").asBoolean());
        assertEquals(Set.of("VENDEDOR","REPARTIDOR"),roles(base+3));
        assertEquals(0,eventos(base+3,"ASIGNACION"));
        assertEquals(0,eventos(base+3,"REVOCACION"));
        assertNull(jdbc.queryForObject("""
                SELECT "fechaHoraRevocacion" FROM "SESION_USUARIO"
                 WHERE "idSesionUsuario"=?
                """,Object.class,sesionDestino));
    }

    @Test
    void f07ImpideRevocarUltimoAdminAunqueLeQuedeOtroRol() throws Exception {
        assertEquals(200,patch(adminA,base+1,QUITAR_ADMIN).statusCode());
        var r=patch(adminA,base,QUITAR_ADMIN);
        assertEquals(400,r.statusCode(),r.body());
        assertEquals(Set.of("ADMINISTRADOR","VENDEDOR"),roles(base));
        assertEquals(0,eventos(base,"REVOCACION"));
        assertEquals(1,eventos(base+1,"REVOCACION"));
    }

    @Test
    void a2NoPermiteDejarSinRoles() throws Exception {
        assertEquals(400,patch(adminA,base+2,
                "{\"rolesQuitar\":[\"VENDEDOR\"]}").statusCode());
        assertEquals(Set.of("VENDEDOR"),roles(base+2));
        assertEquals(0,eventos(base+2,"REVOCACION"));
    }

    @Test
    void proveedorEsExclusivoYSuVinculoNoSeInventa() throws Exception {
        assertEquals(400,patch(adminA,base+4,
                "{\"rolesAgregar\":[\"VENDEDOR\"]}").statusCode());
        assertEquals(400,patch(adminA,base+4,
                "{\"rolesAgregar\":[\"VENDEDOR\"],\"rolesQuitar\":[\"PROVEEDOR\"]}")
                .statusCode());
        assertEquals(400,patch(adminA,base+3,
                "{\"rolesAgregar\":[\"PROVEEDOR\"]}").statusCode());
        assertEquals(Set.of("PROVEEDOR"),roles(base+4));
        assertEquals(1,contar("USUARIO_PROVEEDOR",base+4));
        assertEquals(0,eventos(base+4,"REVOCACION"));
    }

    @Test
    void cuerpoDesconocidoMalTipadoOSolapadoNoModificaNada() throws Exception {
        for (String invalido:List.of(
                "{\"rolesAgregar\":\"VENDEDOR\"}",
                "{\"rolesAgregar\":[\"VENDEDOR\",\"VENDEDOR\"]}",
                "{\"rolesAgregar\":[\"NO_EXISTE\"]}",
                "{\"rolesAgregar\":[\"REPARTIDOR\"],\"rolesQuitar\":[\"REPARTIDOR\"]}",
                "{\"rolesAgregar\":null}",
                "{\"rolesQuitar\":[null]}",
                "{\"rolesAgregar\":[\"\"]}",
                "{\"idProveedor\":3}",
                "[]","null","{")) {
            var r=patch(adminA,base+3,invalido);
            assertEquals(400,r.statusCode(),invalido+" / "+r.body());
            assertEquals("SOLICITUD_INVALIDA",
                    json.readTree(r.body()).get("codigo").asText(),invalido);
        }
        assertEquals(400,patch(adminA,-1,"{}").statusCode());
        assertEquals(400,patch(adminA,base+1000,"{}").statusCode());
        assertEquals(Set.of("VENDEDOR","REPARTIDOR"),roles(base+3));
        assertEquals(0,eventos(base+3,"ASIGNACION"));
    }

    @Test
    void accesoSoloAdminBearerSinCookieNiSesionRestringida() throws Exception {
        String comando="{\"rolesAgregar\":[\"REPARTIDOR\"]}";
        assertEquals(401,patch(null,base+2,comando).statusCode());
        assertEquals(401,patch(UUID.randomUUID().toString(),base+2,comando).statusCode());
        for (String token:List.of(vendedor,proveedor,pendiente)) {
            assertEquals(403,patch(token,base+2,comando).statusCode());
        }
        var pedido=HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:"+puerto+RUTA+"/"+(base+2)+"/roles"))
                .header("Cookie","__Host-ale_session="+adminA)
                .header("Content-Type","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString(comando)).build();
        assertEquals(401,http.send(pedido,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0,eventos(base+2,"ASIGNACION"));
        assertEquals(Set.of("VENDEDOR"),roles(base+2));
    }

    @Test
    void errorDeAuditoriaRevierteDeltaYNoRevelaSql() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION "fn_uc18_http_error"() RETURNS trigger LANGUAGE plpgsql
                AS $$ BEGIN RAISE EXCEPTION 'Detalle privado UC18'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER "tg_uc18_http_error"
                BEFORE INSERT ON "EVENTO_ASIGNACION_ROL"
                FOR EACH ROW EXECUTE FUNCTION "fn_uc18_http_error"()
                """);
        try {
            var r=patch(adminA,base+3,"{\"rolesAgregar\":[\"ADMINISTRADOR\"]}");
            assertEquals(503,r.statusCode(),r.body());
            assertFalse(r.body().contains("Detalle privado"),r.body());
            assertEquals(Set.of("VENDEDOR","REPARTIDOR"),roles(base+3));
            assertEquals(0,eventos(base+3,"ASIGNACION"));
            assertNull(jdbc.queryForObject("""
                    SELECT "fechaHoraRevocacion" FROM "SESION_USUARIO"
                     WHERE "idSesionUsuario"=?
                    """,Object.class,sesionDestino));
        } finally {
            jdbc.execute("DROP TRIGGER \"tg_uc18_http_error\" ON \"EVENTO_ASIGNACION_ROL\"");
            jdbc.execute("DROP FUNCTION \"fn_uc18_http_error\"()");
        }
    }

    @Test
    void revocacionesConcurrentesMantienenAdministradorHabilitado() throws Exception {
        try (var ejecutor=Executors.newFixedThreadPool(2)) {
            var inicio=new CountDownLatch(1);
            var uno=ejecutor.submit(()->{
                if (!inicio.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Inicio A");
                return patch(adminA,base,QUITAR_ADMIN).statusCode();
            });
            var dos=ejecutor.submit(()->{
                if (!inicio.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Inicio B");
                return patch(adminB,base+1,QUITAR_ADMIN).statusCode();
            });
            inicio.countDown();
            int a=uno.get(45,TimeUnit.SECONDS);
            int b=dos.get(45,TimeUnit.SECONDS);
            assertEquals(1,List.of(a,b).stream().filter(x->x==200).count(),
                    a+" / "+b);
            assertTrue(a==200 || a==400 || a==401,a+" / "+b);
            assertTrue(b==200 || b==400 || b==401,a+" / "+b);
        }
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO_ROL" ur JOIN "USUARIO" u
                  ON u."idUsuario"=ur."idUsuario"
                 WHERE ur."codigoRol"='ADMINISTRADOR'
                   AND u."estado"='HABILITADO'
                   AND u."idUsuario" IN (?,?)
                """,Integer.class,base,base+1));
        assertEquals(1,eventos(base,"REVOCACION")+eventos(base+1,"REVOCACION"));
    }
}
