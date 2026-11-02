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
import java.util.List;
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
    void uc54ConsultaComercialSoloAdminMuestraCuentaYProveedorSinSecretos() throws Exception {
        String tokenAdmin=login(userAdmin,claveAdmin);
        String cuit=jdbc.queryForObject("""
                SELECT "cuit" FROM "PROVEEDOR" WHERE "idProveedor"=?
                """,String.class,prov+1);
        String ruta="/api/v1/usuarios/proveedores?idUsuario="+prov;
        var vista=enviar(ruta,"GET",null,Map.of("Authorization","Bearer "+tokenAdmin));
        assertEquals(200,vista.statusCode(),vista.body());
        assertTrue(vista.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var listado=json.readTree(vista.body());
        assertEquals(1,listado.size());
        var item=listado.get(0);
        assertEquals(prov,item.get("idUsuario").asLong());
        assertEquals(userProv,item.get("nombreUsuario").asText());
        assertEquals("HABILITADO",item.get("estadoUsuario").asText());
        assertTrue(item.get("cambioCredencialPendiente").asBoolean());
        assertEquals(prov+1,item.get("idProveedor").asLong());
        assertEquals(cuit,item.get("cuit").asText());
        assertEquals("Proveedor E2E "+admin,item.get("razonSocial").asText());
        assertEquals("ACTIVO",item.get("estadoProveedor").asText());
        assertFalse(vista.body().contains("credencialProtegida"));
        assertFalse(vista.body().contains("tokenProtegido"));
        assertFalse(vista.body().contains("accessToken"));
        assertFalse(vista.body().contains("nombreComercial"));
        var filtrado=enviar("/api/v1/usuarios/proveedores?cuit="+cuit,"GET",null,
                Map.of("Authorization","Bearer "+tokenAdmin));
        assertEquals(200,filtrado.statusCode(),filtrado.body());
        assertEquals(1,json.readTree(filtrado.body()).size());
        var noExiste=enviar("/api/v1/usuarios/proveedores?idUsuario=999999999","GET",null,
                Map.of("Authorization","Bearer "+tokenAdmin));
        assertEquals(200,noExiste.statusCode(),noExiste.body());
        assertEquals(0,json.readTree(noExiste.body()).size());
    }

    @Test
    void uc54ConsultaComercialNoPermiteCookieNiProveedorNiFiltrosInvalidos()
            throws Exception {
        String ruta="/api/v1/usuarios/proveedores?idUsuario="+prov;
        assertEquals(401,enviar(ruta,"GET",null,Map.of()).statusCode());
        assertEquals(401,enviar(ruta,"GET",null,
                Map.of("Cookie","__Host-ale_session=no-valida")).statusCode());
        String tokenProveedor=login(userProv,claveProv);
        assertEquals(403,enviar(ruta,"GET",null,
                Map.of("Authorization","Bearer "+tokenProveedor)).statusCode());
        String tokenAdmin=login(userAdmin,claveAdmin);
        for(String filtro:List.of("/api/v1/usuarios/proveedores",
                                  "/api/v1/usuarios/proveedores?cuit=ABC",
                                  "/api/v1/usuarios/proveedores?nombreUsuario=ab",
                                  "/api/v1/usuarios/proveedores?idUsuario=-1")) {
            var rechazo=enviar(filtro,"GET",null,
                    Map.of("Authorization","Bearer "+tokenAdmin));
            assertEquals(400,rechazo.statusCode(),rechazo.body());
            assertTrue(rechazo.headers().firstValue("Cache-Control")
                    .orElse("").contains("no-store"));
        }
    }

    /**
     * Recorrido automatizado con identidades sintéticas; NO constituye
     * verificación comercial humana, canal real de entrega ni aceptación E2E.
     */
    @Test
    void uc54SimulacionTecnicaConsultaRestableceRestringeCambiaYReingresa()
            throws Exception {
        String adminToken=login(userAdmin,claveAdmin);
        String previoProveedor=login(userProv,claveProv);
        var identificacion=enviar("/api/v1/usuarios/proveedores?idUsuario="+prov,
                "GET",null,Map.of("Authorization","Bearer "+adminToken));
        assertEquals(200,identificacion.statusCode(),identificacion.body());
        assertEquals(prov,json.readTree(identificacion.body()).get(0)
                .get("idUsuario").asLong());
        String temporal="TemporalVerificada_"+prov;
        assertEquals(204,postJson("/api/v1/usuarios/"+prov+"/restablecer-acceso",
                adminToken,Map.of("identidadComercialVerificada",true,
                                  "credencialTemporal",temporal)).statusCode());
        assertEquals(401,enviar("/api/v1/usuarios/proveedores?idUsuario="+prov,
                "GET",null,Map.of("Authorization","Bearer "+previoProveedor)).statusCode());
        String restringido=login(userProv,temporal);
        assertEquals(403,enviar("/api/v1/usuarios/proveedores?idUsuario="+prov,
                "GET",null,Map.of("Authorization","Bearer "+restringido)).statusCode());
        String definitiva="ClaveFinalSegura_"+prov;
        assertEquals(204,postJson("/api/v1/auth/password/change",restringido,
                Map.of("nuevaCredencial",definitiva,
                       "confirmacionNuevaCredencial",definitiva)).statusCode());
        assertEquals(401,postJson("/api/v1/auth/renew",restringido,Map.of()).statusCode());
        String operativo=login(userProv,definitiva);
        assertEquals(43,operativo.length());
        assertEquals(403,enviar("/api/v1/usuarios/proveedores?idUsuario="+prov,
                "GET",null,Map.of("Authorization","Bearer "+operativo)).statusCode());
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

    /**
     * UC54/A3: un error real durante UPDATE debe provocar ROLLBACK
     * (no confundir con un rechazo de validacion previo a persistencia).
     */
    @Test
    void uc54FalloDePersistenciaHaceRollbackSinRevocarSesiones() throws Exception {
        String adminToken=login(userAdmin,claveAdmin);
        String provToken=login(userProv,claveProv);
        String hashAnterior=jdbc.queryForObject("""
                SELECT "credencialProtegida" FROM "USUARIO" WHERE "idUsuario"=?
                """,String.class,prov);
        jdbc.execute("""
                CREATE FUNCTION "fn_uc54_forzar_error_test"() RETURNS trigger
                LANGUAGE plpgsql AS $test$
                BEGIN
                    RAISE EXCEPTION 'Fallo sintetico controlado de persistencia UC54';
                END
                $test$;
                """);
        try {
            jdbc.execute("""
                    CREATE TRIGGER "tg_uc54_forzar_error_test"
                    BEFORE UPDATE ON "USUARIO"
                    FOR EACH ROW EXECUTE FUNCTION "fn_uc54_forzar_error_test"()
                    """);
            try {
                var fallo=postJson("/api/v1/usuarios/"+prov+"/restablecer-acceso",
                        adminToken,Map.of("identidadComercialVerificada",true,
                                "credencialTemporal","NuevaTemporal_"+prov));
                assertEquals(503,fallo.statusCode(),fallo.body());
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS \"tg_uc54_forzar_error_test\" ON \"USUARIO\"");
            }
        } finally {
            jdbc.execute("DROP FUNCTION IF EXISTS \"fn_uc54_forzar_error_test\"()");
        }
        assertEquals(hashAnterior,jdbc.queryForObject("""
                SELECT "credencialProtegida" FROM "USUARIO" WHERE "idUsuario"=?
                """,String.class,prov));
        assertEquals(0,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                 WHERE "tokenProtegido"=? AND "fechaHoraRevocacion" IS NOT NULL
                """,Integer.class,TokenSesionInterna.huella(provToken).orElseThrow()));
        assertEquals(43,login(userProv,claveProv).length());
    }

    /** RD-UC54-001: restablecimiento no habilita la cuenta deshabilitada. */
    @Test
    void uc54NoReactivaCuentaProveedorDeshabilitada() throws Exception {
        String adminToken=login(userAdmin,claveAdmin);
        jdbc.update("""
                UPDATE "USUARIO"
                   SET "estado"='DESHABILITADO',
                       "fechaHoraDeshabilitacion"=clock_timestamp()
                 WHERE "idUsuario"=?
                """,prov);
        var resp=postJson("/api/v1/usuarios/"+prov+"/restablecer-acceso",
                adminToken,Map.of("identidadComercialVerificada",true,
                        "credencialTemporal","ClaveTrasReset_"+prov));
        assertEquals(204,resp.statusCode(),resp.body());
        assertEquals("DESHABILITADO",jdbc.queryForObject("""
                SELECT "estado" FROM "USUARIO" WHERE "idUsuario"=?
                """,String.class,prov));
        assertEquals(401,postJson("/api/v1/auth/login",null,
                Map.of("nombreUsuario",userProv,"credencial","ClaveTrasReset_"+prov)).statusCode());
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
        var login=enviar("/web/login","POST",datos+"&_csrf="+csrfFormulario,Map.of(
                "Content-Type","application/x-www-form-urlencoded",
                "Cookie",session));
        assertEquals(302,login.statusCode(),login.body());
        assertEquals(403,enviar("/web/login","POST",datos+"&_csrf="+csrfFormulario,
                Map.of("Content-Type","application/x-www-form-urlencoded",
                       "Origin","https://origen-malicioso.example",
                       "Cookie",session)).statusCode());
        var sinCsrf=enviar("/web/login","POST",datos,Map.of(
                "Content-Type","application/x-www-form-urlencoded",
                "Cookie",session));
        assertEquals(403,sinCsrf.statusCode());

        String cookie=login.headers().allValues("Set-Cookie").stream()
                .filter(x->x.startsWith("__Host-ale_session=")).findFirst().orElseThrow();
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Path=/"));
        String bearerCookie=cookie.split(";",2)[0];

        String sessionLogin=login.headers().allValues("Set-Cookie").stream()
                .filter(x->x.startsWith("JSESSIONID=")).findFirst()
                .map(x->x.split(";",2)[0]).orElse(session);
        var panel=enviar("/web/inicio","GET",null,
                Map.of("Cookie",bearerCookie+"; "+sessionLogin));
        assertEquals(200,panel.statusCode());
        Matcher csrfPanel=CSRF.matcher(panel.body());
        assertTrue(csrfPanel.find(),"Panel autenticado debe emitir CSRF nuevo");
        String csrfVigente=URLEncoder.encode(csrfPanel.group(1),StandardCharsets.UTF_8);
        String sessionVigente=panel.headers().allValues("Set-Cookie").stream()
                .filter(x->x.startsWith("JSESSIONID=")).findFirst()
                .map(x->x.split(";",2)[0]).orElse(sessionLogin);
        // La autenticacion rota CSRF: el token anterior NO permite logout.
        assertEquals(403,enviar("/web/logout","POST","_csrf="+csrfFormulario,
                Map.of("Content-Type","application/x-www-form-urlencoded",
                       "Cookie",bearerCookie+"; "+sessionVigente)).statusCode());
        assertEquals(302,enviar("/web/logout","POST","_csrf="+csrfVigente,
                Map.of("Content-Type","application/x-www-form-urlencoded",
                       "Cookie",bearerCookie+"; "+sessionVigente)).statusCode());
        String secreto= bearerCookie.substring("__Host-ale_session=".length());
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                 WHERE "tokenProtegido"=? AND "fechaHoraRevocacion" IS NOT NULL
                """,Integer.class,TokenSesionInterna.huella(secreto).orElseThrow()));
        // El canal web deniega la cookie revocada; según la rama de
        // autorización puede devolver 401 o 403, nunca un documento 200.
        int estadoRevocada=enviar("/web/inicio","GET",null,
                Map.of("Cookie",bearerCookie+"; "+sessionVigente)).statusCode();
        assertTrue(estadoRevocada==401 || estadoRevocada==403);
        // La API no utiliza la cookie web como identidad.
        assertEquals(401,enviar("/api/v1/unidades","GET",null,
                Map.of("Cookie",bearerCookie)).statusCode());
    }
}
