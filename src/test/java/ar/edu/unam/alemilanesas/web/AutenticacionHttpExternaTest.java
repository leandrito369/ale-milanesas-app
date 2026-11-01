package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

/**
 * B4-02C: HTTP real contra servidor en puerto efimero + PostgreSQL 17.11.
 * Se ejecuta sin @Transactional en el test: cada request servidor usa otra tx.
 * No acredita TLS de despliegue/ingress o uso externo de red publica.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AutenticacionHttpExternaTest {
    @Container
    static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void dbprops(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }

    private static final AtomicLong SEQ=new AtomicLong(85000);
    private static final Pattern CSRF=Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper json;

    private final HttpClient client=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private long admin,prov;
    private String userAdmin,userProv,claveAdmin,claveProv;

    @BeforeEach
    void fixture() {
        long base=SEQ.addAndGet(3);
        admin=base;prov=base+1;
        userAdmin="admin_http_"+base;
        userProv="proveedor_http_"+base;
        claveAdmin="Admin_"+base+"_privada";
        claveProv="Temporal_"+base+"_privada";
        transactions.executeWithoutResult(tx -> {
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Administrador E2E'),('PROVEEDOR','Proveedor E2E')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            jdbc.update("""
                    INSERT INTO "PROVEEDOR"("idProveedor","cuit","razonSocial",
                            "estado","fechaHoraAlta")
                    VALUES(?,?,?,'ACTIVO',clock_timestamp())
                    """,base+2,String.format("%011d",30000000000L+base),"Proveedor E2E "+base);
            jdbc.update("""
                    INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta")
                    VALUES(?,?,?,'HABILITADO',false,clock_timestamp()),
                          (?,?,?,'HABILITADO',true,clock_timestamp())
                    """,admin,userAdmin,encoder.encode(claveAdmin),
                    prov,userProv,encoder.encode(claveProv));
            jdbc.update("""
                    INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                    VALUES(?,'ADMINISTRADOR',clock_timestamp()),
                          (?,'PROVEEDOR',clock_timestamp())
                    """,admin,prov);
            jdbc.update("""
                    INSERT INTO "USUARIO_PROVEEDOR"("idUsuario","idProveedor","fechaHoraVinculacion")
                    VALUES(?,?,clock_timestamp())
                    """,prov,base+2);
        });
    }

    private URI uri(String ruta) {return URI.create("http://127.0.0.1:"+port+ruta);}

    private HttpResponse<String> enviar(String ruta,String metodo,String cuerpo,
                                         Map<String,String> headers) throws Exception {
        var b=HttpRequest.newBuilder(uri(ruta)).timeout(Duration.ofSeconds(15));
        headers.forEach(b::header);
        b.method(metodo,metodo.equals("GET")?HttpRequest.BodyPublishers.noBody():
                HttpRequest.BodyPublishers.ofString(cuerpo==null?"":cuerpo,StandardCharsets.UTF_8));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postJson(String ruta,String token,Object payload) throws Exception {
        var h=new java.util.HashMap<String,String>();
        h.put("Content-Type","application/json");
        if(token!=null) h.put("Authorization","Bearer "+token);
        return enviar(ruta,"POST",json.writeValueAsString(payload),h);
    }

    private String login(String nombre,String password) throws Exception {
        var resp=postJson("/api/v1/auth/login",null,
                Map.of("nombreUsuario",nombre,"credencial",password));
        assertEquals(200,resp.statusCode(),resp.body());
        assertTrue(resp.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return json.readTree(resp.body()).get("accessToken").asText();
    }

    private int sesiones(long usuario) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO" WHERE "idUsuario"=?
                """,Integer.class,usuario);
    }

    @Test
    void emisionHashNoPersistirSecretoYDenegarEscrituraComercial() throws Exception {
        String token=login(userAdmin,claveAdmin);
        assertEquals(43,token.length());
        assertEquals(1,sesiones(admin));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                 WHERE "idUsuario"=? AND "tokenProtegido"=?
                   AND "fechaHoraExpiracion" <= "fechaHoraInicio"+interval '30 minutes 1 second'
                """,Integer.class,admin,TokenSesionInterna.huella(token).orElseThrow()));
        assertEquals(403,postJson("/api/v1/clientes",token,Map.of()).statusCode());
        assertEquals(200,enviar("/api/v1/unidades","GET",null,
                Map.of("Authorization","Bearer "+token)).statusCode());
    }

    @Test
    void dosRenovacionesConcurrenSoloUnaValida() throws Exception {
        String anterior=login(userAdmin,claveAdmin);
        UUID id=jdbc.queryForObject("""
                SELECT "idSesionUsuario" FROM "SESION_USUARIO"
                 WHERE "tokenProtegido"=?
                """,UUID.class,TokenSesionInterna.huella(anterior).orElseThrow());
        CountDownLatch inicio=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> trabajo=()->{
                inicio.await(10,TimeUnit.SECONDS);
                return postJson("/api/v1/auth/renew",anterior,Map.of());
            };
            Future<HttpResponse<String>> uno=pool.submit(trabajo);
            Future<HttpResponse<String>> dos=pool.submit(trabajo);
            inicio.countDown();
            var a=uno.get(20,TimeUnit.SECONDS);
            var b=dos.get(20,TimeUnit.SECONDS);
            assertEquals(1,(a.statusCode()==200?1:0)+(b.statusCode()==200?1:0));
            assertEquals(1,(a.statusCode()==401?1:0)+(b.statusCode()==401?1:0));
            String nuevo=json.readTree(a.statusCode()==200?a.body():b.body())
                    .get("accessToken").asText();
            assertNotEquals(anterior,nuevo);
            assertEquals(1,sesiones(admin)); // rotacion en MISMA fila
            assertEquals(id,jdbc.queryForObject("""
                    SELECT "idSesionUsuario" FROM "SESION_USUARIO" WHERE "tokenProtegido"=?
                    """,UUID.class,TokenSesionInterna.huella(nuevo).orElseThrow()));
            assertEquals(401,enviar("/api/v1/unidades","GET",null,
                    Map.of("Authorization","Bearer "+anterior)).statusCode());
            assertEquals(200,enviar("/api/v1/unidades","GET",null,
                    Map.of("Authorization","Bearer "+nuevo)).statusCode());
        }
    }

    @Test
    void vencimientoYRevocacionExigenNuevoLogin() throws Exception {
        String token=login(userAdmin,claveAdmin);
        jdbc.update("""
                UPDATE "SESION_USUARIO"
                   SET "fechaHoraInicio"=clock_timestamp()-interval '8 hours 1 minute'
                 WHERE "tokenProtegido"=?
                """,TokenSesionInterna.huella(token).orElseThrow());
        assertEquals(401,postJson("/api/v1/auth/renew",token,Map.of()).statusCode());
        String nuevo=login(userAdmin,claveAdmin);
        assertEquals(204,postJson("/api/v1/auth/logout",nuevo,Map.of()).statusCode());
        assertEquals(401,postJson("/api/v1/auth/renew",nuevo,Map.of()).statusCode());
    }

    @Test
    void sesionRestringidaPermiteUC53YRevocaTodas() throws Exception {
        String token=login(userProv,claveProv);
        assertEquals(403,enviar("/api/v1/unidades","GET",null,
                Map.of("Authorization","Bearer "+token)).statusCode());
        assertEquals(403,postJson("/api/v1/auth/renew",token,Map.of()).statusCode());
        String claveNueva="Nueva_"+prov+"_secreta";
        assertEquals(204,postJson("/api/v1/auth/password/change",token,
                Map.of("nuevaCredencial",claveNueva,
                       "confirmacionNuevaCredencial",claveNueva)).statusCode());
        assertEquals(401,postJson("/api/v1/auth/renew",token,Map.of()).statusCode());
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                 WHERE "idUsuario"=? AND "fechaHoraRevocacion" IS NOT NULL
                """,Integer.class,prov));
        assertEquals(401,postJson("/api/v1/auth/login",null,
                Map.of("nombreUsuario",userProv,"credencial",claveProv)).statusCode());
        String nuevo=login(userProv,claveNueva);
        assertNotEquals(token,nuevo);
    }

    @Test
    void uc54NoRestableceOtrosRolesNiSinVerificacion() throws Exception {
        String adminToken=login(userAdmin,claveAdmin);
        var falso=postJson("/api/v1/usuarios/"+prov+"/restablecer-acceso",
                adminToken,Map.of("identidadComercialVerificada",false,
                        "credencialTemporal","Temporal_"+prov+"_nuevo"));
        assertEquals(400,falso.statusCode());
        var noProveedor=postJson("/api/v1/usuarios/"+admin+"/restablecer-acceso",
                adminToken,Map.of("identidadComercialVerificada",true,
                        "credencialTemporal","Temporal_"+admin+"_nuevo"));
        assertEquals(409,noProveedor.statusCode());
        assertEquals(204,postJson("/api/v1/usuarios/"+prov+"/restablecer-acceso",
                adminToken,Map.of("identidadComercialVerificada",true,
                        "credencialTemporal","Temporal_"+prov+"_nuevo")).statusCode());
        assertEquals(401,postJson("/api/v1/auth/login",null,
                Map.of("nombreUsuario",userProv,"credencial",claveProv)).statusCode());
        assertEquals(43,login(userProv,"Temporal_"+prov+"_nuevo").length());
    }

    @Test
    void formularioWebExigeCsrfYCookieProtegida() throws Exception {
        var entrada=enviar("/web/login","GET",null,Map.of());
        assertEquals(200,entrada.statusCode(),entrada.body());
        Matcher m=CSRF.matcher(entrada.body());
        assertTrue(m.find(),"La plantilla debe entregar token CSRF independiente");
        String csrf=m.group(1);
        String csrfFormulario=URLEncoder.encode(csrf,StandardCharsets.UTF_8);
        String session=entrada.headers().allValues("Set-Cookie").stream()
                .filter(x->x.startsWith("JSESSIONID="))
                .findFirst().orElseThrow().split(";",2)[0];

        String datos="nombreUsuario="+userAdmin+"&credencial="+claveAdmin;
        assertEquals(403,enviar("/web/login","POST",datos+"&_csrf="+csrfFormulario,
                Map.of("Content-Type","application/x-www-form-urlencoded",
                       "Origin","https://origen-malicioso.example",
                       "Cookie",session)).statusCode());
        var sinCsrf=enviar("/web/login","POST",datos,Map.of(
                "Content-Type","application/x-www-form-urlencoded",
                "Cookie",session));
        assertEquals(403,sinCsrf.statusCode());

        var login=enviar("/web/login","POST",datos+"&_csrf="+csrfFormulario,Map.of(
                "Content-Type","application/x-www-form-urlencoded",
                "Cookie",session));
        assertEquals(302,login.statusCode(),login.body());
        String cookie=login.headers().allValues("Set-Cookie").stream()
                .filter(x->x.startsWith("__Host-ale_session=")).findFirst().orElseThrow();
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Path=/"));
        String bearerCookie=cookie.split(";",2)[0];

        assertEquals(200,enviar("/web/inicio","GET",null,
                Map.of("Cookie",bearerCookie+"; "+session)).statusCode());
        assertEquals(403,enviar("/web/logout","POST","",Map.of(
                "Cookie",bearerCookie+"; "+session)).statusCode());
        assertEquals(302,enviar("/web/logout","POST","_csrf="+csrfFormulario,
                Map.of("Content-Type","application/x-www-form-urlencoded",
                       "Cookie",bearerCookie+"; "+session)).statusCode());
        assertEquals(401,enviar("/web/inicio","GET",null,
                Map.of("Cookie",bearerCookie+"; "+session)).statusCode());
        // La API no utiliza la cookie web como identidad.
        assertEquals(401,enviar("/api/v1/unidades","GET",null,
                Map.of("Cookie",bearerCookie)).statusCode());
    }
}
