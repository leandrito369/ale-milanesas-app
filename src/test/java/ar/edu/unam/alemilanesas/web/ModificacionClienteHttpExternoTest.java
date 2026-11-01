package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import ar.edu.unam.alemilanesas.maestros.GestionClientesService;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B4-03-02: red HTTP real con DB PostgreSQL; flag exclusivamente del test.
 * No se usan transacciones abiertas en JUnit que oculten datos al servidor.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="ale.http.writes.clientes-uc32-enabled=true")
@Testcontainers
class ModificacionClienteHttpExternoTest {
    @Container
    static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void dbprops(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    private static final AtomicLong SEQ=new AtomicLong(420000);
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired GestionClientesService service;
    @Autowired ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private String vendedor,admin,repartidor,pendiente;
    private UUID vendedorId;
    private long clienteA,clienteB,base;

    @BeforeEach
    void preparar() {
        base=SEQ.addAndGet(10);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "ROL" ("codigoRol","nombreMostrado")
                    VALUES ('VENDEDOR','Vendedor B40302'),
                           ('ADMINISTRADOR','Admin B40302'),
                           ('REPARTIDOR','Repartidor B40302')
                    ON CONFLICT ("codigoRol") DO NOTHING
                    """);
            jdbc.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                        "estado","cambioCredencialPendiente","fechaHoraAlta")
                    VALUES (? ,?,'HASH_TEST','HABILITADO',false,clock_timestamp()),
                           (? ,?,'HASH_TEST','HABILITADO',false,clock_timestamp()),
                           (? ,?,'HASH_TEST','HABILITADO',false,clock_timestamp()),
                           (? ,?,'HASH_TEST','HABILITADO',true,clock_timestamp())
                    """,base,"vendedor_uc32_"+base,base+1,"admin_uc32_"+base,
                    base+2,"repartidor_uc32_"+base,base+3,"pendiente_uc32_"+base);
            jdbc.update("""
                    INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                    VALUES (?,'VENDEDOR',clock_timestamp()),
                           (?,'ADMINISTRADOR',clock_timestamp()),
                           (?,'REPARTIDOR',clock_timestamp()),
                           (?,'VENDEDOR',clock_timestamp())
                    """,base,base+1,base+2,base+3);
            vendedor=sesion(base);
            admin=sesion(base+1);
            repartidor=sesion(base+2);
            pendiente=sesion(base+3);
        });
        vendedorId=jdbc.queryForObject("""
                SELECT "idSesionUsuario" FROM "SESION_USUARIO"
                 WHERE "tokenProtegido"=?
                """,UUID.class,TokenSesionInterna.huella(vendedor).orElseThrow());
        clienteA=service.registrarCliente(vendedorId,dto("Original",telefono(1))).idCliente();
        clienteB=service.registrarCliente(vendedorId,dto("Distinto",telefono(2))).idCliente();
    }

    private String sesion(long usuario) {
        String token=TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                        "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,?,?,clock_timestamp()-interval '1 minute',
                       clock_timestamp()+interval '29 minutes')
                """,UUID.randomUUID(),usuario,TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private String telefono(int sufijo) {
        return String.format("376%07d",base+sufijo);
    }

    private GestionClientesService.DatosCliente dto(String nombre,String tel) {
        return new GestionClientesService.DatosCliente(
                "B4032_"+base+"_"+nombre,"Gomez",tel,"San Martin",
                "123",null,null,"Apostoles","Misiones",null,"Centro",(short)3);
    }

    private Map<String,Object> datos(String nombre,String tel) {
        var d=new HashMap<String,Object>();
        d.put("nombres","B4032_"+base+"_"+nombre);
        d.put("apellidos","Gomez");
        d.put("telefonoPrincipal",tel);
        d.put("calle","San Martin");
        d.put("numeroPuerta","123");
        d.put("localidad","Apostoles");
        d.put("provincia","Misiones");
        d.put("zona","Centro");
        d.put("diaHabitualReparto",3);
        return d;
    }

    private HttpResponse<String> enviar(long id,String token,Map<String,Object> body)
            throws Exception {
        var builder=HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:"+port+"/api/v1/clientes/"+id))
                .timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return http.send(builder.PUT(HttpRequest.BodyPublishers.ofString(
                json.writeValueAsString(body),StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String nombreCliente(long id) {
        return jdbc.queryForObject("""
                SELECT "nombres" FROM "CLIENTE" WHERE "idCliente"=?
                """,String.class,id);
    }

    @Test
    void vendedorModificaSoloDatosPermitidosYRetornaProyeccionMinima() throws Exception {
        var r=enviar(clienteA,vendedor,datos("Actualizado",telefono(1)));
        assertEquals(200,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var salida=json.readTree(r.body());
        assertEquals(clienteA,salida.get("idCliente").asLong());
        assertTrue(salida.get("huboCambio").asBoolean());
        assertEquals("ACTIVO",salida.get("estado").asText());
        assertEquals("CUMPLIDOR",salida.get("categoriaComercial").asText());
        assertFalse(r.body().contains("telefonoPrincipal"));
        assertEquals("B4032_"+base+"_Actualizado",nombreCliente(clienteA));
        assertEquals(0,jdbc.queryForObject("""
                SELECT count(*) FROM "HISTORIAL_ESTADO_CLIENTE"
                 WHERE "idCliente"=?
                """,Integer.class,clienteA));
    }

    @Test
    void adminNoHeredaPermisoUC32YNingunaSesionIncorrectaEscribe() throws Exception {
        var datos=datos("Prohibido",telefono(1));
        assertEquals(401,enviar(clienteA,null,datos).statusCode());
        assertEquals(403,enviar(clienteA,admin,datos).statusCode());
        assertEquals(403,enviar(clienteA,repartidor,datos).statusCode());
        assertEquals(403,enviar(clienteA,pendiente,datos).statusCode());
        assertEquals("B4032_"+base+"_Original",nombreCliente(clienteA));
        var post=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/clientes"))
                .header("Authorization","Bearer "+vendedor)
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(datos))).build();
        assertEquals(403,http.send(post,HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void camposAutoridadYDatosInvalidosNoSeAplican() throws Exception {
        var d=datos("Invalido",telefono(1));
        d.put("estado","INACTIVO");
        assertEquals(400,enviar(clienteA,vendedor,d).statusCode());
        d.remove("estado");
        d.put("categoriaComercial","EN_MORA");
        assertEquals(400,enviar(clienteA,vendedor,d).statusCode());
        d.remove("categoriaComercial");
        d.put("diaHabitualReparto",2.5);
        assertEquals(400,enviar(clienteA,vendedor,d).statusCode());
        d.put("diaHabitualReparto",3);
        d.put("telefonoPrincipal","ABC");
        assertEquals(400,enviar(clienteA,vendedor,d).statusCode());
        assertEquals(404,enviar(clienteB+10000,vendedor,datos("Invalido",telefono(9))).statusCode());
        assertEquals("B4032_"+base+"_Original",nombreCliente(clienteA));
    }

    @Test
    void cambioSinDiferenciasNoInvalidaOtp() throws Exception {
        crearOtp(clienteA);
        var r=enviar(clienteA,vendedor,datos("Original",telefono(1)));
        assertEquals(200,r.statusCode(),r.body());
        assertFalse(json.readTree(r.body()).get("huboCambio").asBoolean());
        assertEquals(0,otpBloqueados(clienteA));
        assertEquals(0,sesionesOnlineRevocadas(clienteA));
    }

    @Test
    void cambioTelefonoBloqueaSoloOtpPendienteNoSesionOnline() throws Exception {
        crearOtp(clienteA);
        var r=enviar(clienteA,vendedor,datos("Original",telefono(3)));
        assertEquals(200,r.statusCode(),r.body());
        assertEquals(1,otpBloqueados(clienteA));
        assertEquals(0,sesionesOnlineRevocadas(clienteA));
        assertEquals("ACTIVO",jdbc.queryForObject("""
                SELECT "estado" FROM "CLIENTE" WHERE "idCliente"=?
                """,String.class,clienteA));
    }

    @Test
    void duplicadoExactoDeOtroClienteNoEscribe() throws Exception {
        var resp=enviar(clienteA,vendedor,datos("Distinto",telefono(2)));
        assertEquals(409,resp.statusCode(),resp.body());
        assertTrue(resp.body().contains("CLIENTE_DUPLICADO"));
        assertFalse(resp.body().contains("telefonoPrincipal"));
        assertEquals("B4032_"+base+"_Original",nombreCliente(clienteA));
    }

    @Test
    void coincidenciaParcialPermiteModificarSinExponerOtrosIds() throws Exception {
        var resp=enviar(clienteA,vendedor,datos("Original",telefono(2)));
        assertEquals(200,resp.statusCode(),resp.body());
        assertTrue(json.readTree(resp.body()).get("advertenciaCoincidenciaParcial").asBoolean());
        assertFalse(resp.body().contains("advertenciasParciales"));
        assertFalse(resp.body().contains("telefonoPrincipal"));
        assertEquals("B4032_"+base+"_Original",nombreCliente(clienteA));
    }

    @Test
    void falloDePersistenciaRevierteCambioYNoBloqueaOtp() throws Exception {
        crearOtp(clienteA);
        jdbc.execute("""
                CREATE FUNCTION "fn_uc32_error_http"() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Fallo sintetico UC32';
                END; $$
                """);
        try {
            jdbc.execute("""
                    CREATE TRIGGER "tg_uc32_error_http" BEFORE UPDATE ON "CLIENTE"
                    FOR EACH ROW EXECUTE FUNCTION "fn_uc32_error_http"()
                    """);
            try {
                var r=enviar(clienteA,vendedor,datos("Erroneo",telefono(3)));
                assertEquals(503,r.statusCode(),r.body());
                assertFalse(r.body().contains("Fallo sintetico"));
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS \"tg_uc32_error_http\" ON \"CLIENTE\"");
            }
        } finally {
            jdbc.execute("DROP FUNCTION IF EXISTS \"fn_uc32_error_http\"()");
        }
        assertEquals("B4032_"+base+"_Original",nombreCliente(clienteA));
        assertEquals(0,otpBloqueados(clienteA));
        assertEquals(0,sesionesOnlineRevocadas(clienteA));
    }

    @Test
    void dosModificacionesHaciaMismaIdentidadSoloUnaConfirma() throws Exception {
        var d=datos("Objetivo",telefono(7));
        var inicio=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var uno=pool.submit(()->{inicio.await(10,TimeUnit.SECONDS);
                return enviar(clienteA,vendedor,d);});
            var dos=pool.submit(()->{inicio.await(10,TimeUnit.SECONDS);
                return enviar(clienteB,vendedor,d);});
            inicio.countDown();
            var a=uno.get(30,TimeUnit.SECONDS);
            var b=dos.get(30,TimeUnit.SECONDS);
            assertEquals(1,(a.statusCode()==200?1:0)+(b.statusCode()==200?1:0));
            assertEquals(1,(a.statusCode()==409?1:0)+(b.statusCode()==409?1:0));
            assertEquals(1,jdbc.queryForObject("""
                    SELECT count(*) FROM "CLIENTE" WHERE "nombres"=?
                     AND "telefonoPrincipal"=?
                    """,Integer.class,"B4032_"+base+"_Objetivo",telefono(7)));
        }
    }

    private void crearOtp(long id) {
        UUID desafio=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO "DESAFIO_AUTENTICACION_CLIENTE"
                    ("idDesafio","idCliente","telefonoDestino","codigoProtegido",
                     "fechaHoraCreacion","fechaHoraExpiracion","intentosFallidos")
                VALUES (?,?,?,decode('020004','hex'),
                        clock_timestamp()-interval '1 minute',
                        clock_timestamp()+interval '1 hour',0)
                """,desafio,id,telefono(1));
        jdbc.update("""
                INSERT INTO "SESION_CLIENTE_ONLINE"
                    ("idSesionOnline","idDesafio","tokenProtegido",
                     "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,?,?,clock_timestamp()-interval '1 minute',
                       clock_timestamp()+interval '1 hour')
                """,UUID.randomUUID(),desafio,UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    private int otpBloqueados(long id) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM "DESAFIO_AUTENTICACION_CLIENTE"
                 WHERE "idCliente"=? AND "fechaHoraBloqueo" IS NOT NULL
                """,Integer.class,id);
    }

    private int sesionesOnlineRevocadas(long id) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_CLIENTE_ONLINE" s
                 JOIN "DESAFIO_AUTENTICACION_CLIENTE" d ON d."idDesafio"=s."idDesafio"
                 WHERE d."idCliente"=? AND s."fechaHoraRevocacion" IS NOT NULL
                """,Integer.class,id);
    }
}
