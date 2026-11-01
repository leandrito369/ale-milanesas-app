package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Puerto HTTP real + PostgreSQL, bandera UC02 exclusiva del ambiente de test.
 * Sin @Transactional en pruebas: el servidor usa conexiones distintas.
 * Esta clase NO ejecuta TLS público ni activa escritura en producción.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="ale.http.writes.clientes-uc02-enabled=true")
@Testcontainers
class RegistroClienteHttpExternoTest {
    @Container
    static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void dbprops(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",db::getJdbcUrl);
        props.add("spring.datasource.username",db::getUsername);
        props.add("spring.datasource.password",db::getPassword);
    }

    private static final AtomicLong SEQ=new AtomicLong(320000);
    private static final String RUTA="/api/v1/clientes";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final HttpClient cliente=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private long base;
    private String admin,vendedor,repartidor,pendiente;

    @BeforeEach
    void fixture() {
        base=SEQ.addAndGet(10);
        jdbc.update("""
                INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                ('ADMINISTRADOR','Administrador B403'),
                ('VENDEDOR','Vendedor B403'),('REPARTIDOR','Repartidor B403')
                ON CONFLICT("codigoRol") DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                        "estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (? ,?,'HASH_B403','HABILITADO',false,clock_timestamp()),
                       (? ,?,'HASH_B403','HABILITADO',false,clock_timestamp()),
                       (? ,?,'HASH_B403','HABILITADO',false,clock_timestamp()),
                       (? ,?,'HASH_B403','HABILITADO',true,clock_timestamp())
                """,
                base,"admin_uc02_"+base,base+1,"vendedor_uc02_"+base,
                base+2,"repartidor_uc02_"+base,base+3,"pendiente_uc02_"+base);
        jdbc.update("""
                INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (?,'ADMINISTRADOR',clock_timestamp()),
                       (?,'VENDEDOR',clock_timestamp()),
                       (?,'REPARTIDOR',clock_timestamp()),
                       (?,'VENDEDOR',clock_timestamp())
                """,base,base+1,base+2,base+3);
        admin=sesion(base);
        vendedor=sesion(base+1);
        repartidor=sesion(base+2);
        pendiente=sesion(base+3);
    }

    private String sesion(long actor) {
        String token=TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                        "fechaHoraInicio","fechaHoraExpiracion")
                VALUES(?,?,?,clock_timestamp()-interval '1 minute',
                          clock_timestamp()+interval '29 minutes')
                """,UUID.randomUUID(),actor,TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private HttpResponse<String> enviar(String ruta,String token,Object body,
                                         Map<String,String> extra) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+ruta))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json");
        if(token!=null) req.header("Authorization","Bearer "+token);
        extra.forEach(req::header);
        req.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),
                StandardCharsets.UTF_8));
        return cliente.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String token,Object body) throws Exception {
        return enviar(RUTA,token,body,Map.of());
    }

    private Map<String,Object> datos(String nombre,String tel) {
        var payload=new HashMap<String,Object>();
        payload.put("nombres",nombre);
        payload.put("apellidos","Gómez");
        payload.put("telefonoPrincipal",tel);
        payload.put("calle","San Martín");
        payload.put("localidad","Apóstoles");
        payload.put("diaHabitualReparto",3);
        return payload;
    }

    private int contar() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM "CLIENTE" WHERE "nombres" LIKE ?
                """,Integer.class,"B403_"+base+"%");
    }

    @Test
    void adminPuedeRegistrarYNoSeExponeProyeccionDeOtrosClientes() throws Exception {
        var resp=post(admin,datos("B403_"+base+"_Ana","3764000001"));
        assertEquals(201,resp.statusCode(),resp.body());
        assertTrue(resp.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var body=json.readTree(resp.body());
        assertEquals("ACTIVO",body.get("estado").asText());
        assertEquals("CUMPLIDOR",body.get("categoriaComercial").asText());
        assertFalse(body.get("advertenciaCoincidenciaParcial").asBoolean());
        assertTrue(body.get("idCliente").asLong()>0);
        assertFalse(resp.body().contains("telefonoPrincipal"));
        assertFalse(resp.body().contains("advertenciasParciales"));
        assertTrue(resp.headers().firstValue("Location").isEmpty());
        assertEquals(1,contar());
    }

    @Test
    void vendedorPuedeRegistrar() throws Exception {
        var resp=post(vendedor,datos("B403_"+base+"_Bea","3764000002"));
        assertEquals(201,resp.statusCode(),resp.body());
        assertEquals(1,contar());
    }

    @Test
    void requiereBearerRolYCredencialSinCambioPendiente() throws Exception {
        var payload=datos("B403_"+base+"_Bloqueado","3764000003");
        assertEquals(401,post(null,payload).statusCode());
        assertEquals(403,post(repartidor,payload).statusCode());
        assertEquals(403,post(pendiente,payload).statusCode());
        assertEquals(401,enviar(RUTA,null,payload,
                Map.of("Cookie","__Host-ale_session=token_sintetico")).statusCode());
        assertEquals(403,enviar("/api/v1/insumos",admin,payload,Map.of()).statusCode());
        assertEquals(0,contar());
    }

    @Test
    void noAdmiteCamposDeAutoridadNiTiposInvalidos() throws Exception {
        var payload=datos("B403_"+base+"_Invalido","3764000004");
        payload.put("idUsuario",base);
        assertEquals(400,post(admin,payload).statusCode());
        payload.remove("idUsuario");
        payload.put("categoriaComercial","EN_MORA");
        assertEquals(400,post(admin,payload).statusCode());
        payload.remove("categoriaComercial");
        payload.put("diaHabitualReparto",3.5);
        assertEquals(400,post(admin,payload).statusCode());
        payload.put("diaHabitualReparto",3);
        payload.put("telefonoPrincipal","abcdef");
        assertEquals(400,post(admin,payload).statusCode());
        assertEquals(0,contar());
    }

    @Test
    void duplicadoExactoActivoEInactivoEsConflictoYNoDuplica() throws Exception {
        var payload=datos("B403_"+base+"_Duplicado","3764000005");
        var primero=post(vendedor,payload);
        assertEquals(201,primero.statusCode());
        var segundo=post(admin,payload);
        assertEquals(409,segundo.statusCode(),segundo.body());
        assertTrue(segundo.body().contains("CLIENTE_DUPLICADO"));
        assertFalse(segundo.body().contains("3764000005"));
        assertEquals(1,contar());
        long id=json.readTree(primero.body()).get("idCliente").asLong();
        jdbc.update("UPDATE \"CLIENTE\" SET \"estado\"='INACTIVO' WHERE \"idCliente\"=?",id);
        assertEquals(409,post(admin,payload).statusCode());
        assertEquals(1,contar());
    }

    @Test
    void telefonoCompartidoSoloAdvierteSinExponerIdsDeTerceros() throws Exception {
        assertEquals(201,post(admin,datos("B403_"+base+"_Original","3764000006"))
                .statusCode());
        var resp=post(vendedor,datos("B403_"+base+"_Diferente","3764000006"));
        assertEquals(201,resp.statusCode(),resp.body());
        var salida=json.readTree(resp.body());
        assertTrue(salida.get("advertenciaCoincidenciaParcial").asBoolean());
        assertFalse(resp.body().contains("advertenciasParciales"));
        assertEquals(2,contar());
    }

    @Test
    void altaSimultaneaIdenticaConfirmaSoloUna() throws Exception {
        var payload=datos("B403_"+base+"_Concurrente","3764000007");
        var inicio=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var uno=pool.submit(()->{inicio.await(10,TimeUnit.SECONDS);return post(admin,payload);});
            var dos=pool.submit(()->{inicio.await(10,TimeUnit.SECONDS);return post(vendedor,payload);});
            inicio.countDown();
            var a=uno.get(30,TimeUnit.SECONDS);
            var b=dos.get(30,TimeUnit.SECONDS);
            assertEquals(1,(a.statusCode()==201?1:0)+(b.statusCode()==201?1:0));
            assertEquals(1,(a.statusCode()==409?1:0)+(b.statusCode()==409?1:0));
            assertEquals(1,contar());
        }
    }

    @Test
    void errorRealDePersistenciaRevierteOperacion() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION "fn_uc02_fallo_b403"() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Fallo sintético B403';
                END;
                $$
                """);
        try {
            jdbc.execute("""
                    CREATE TRIGGER "tg_uc02_fallo_b403" BEFORE INSERT ON "CLIENTE"
                    FOR EACH ROW EXECUTE FUNCTION "fn_uc02_fallo_b403"()
                    """);
            try {
                var resp=post(admin,datos("B403_"+base+"_Rollback","3764000008"));
                assertEquals(503,resp.statusCode(),resp.body());
                assertFalse(resp.body().contains("sintético"));
                assertEquals(0,contar());
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS \"tg_uc02_fallo_b403\" ON \"CLIENTE\"");
            }
        } finally {
            jdbc.execute("DROP FUNCTION IF EXISTS \"fn_uc02_fallo_b403\"()");
        }
    }
}
