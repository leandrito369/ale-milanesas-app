package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import ar.edu.unam.alemilanesas.maestros.GestionClientesService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

/** UC38 B4-03-03: peticiones por puerto real y transacciones PostgreSQL efectivas. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="ale.http.writes.clientes-uc38-enabled=true")
@Testcontainers
class EstadoClienteHttpExternoTest {
    @Container
    static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void dbprops(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }

    private static final AtomicLong SEQ=new AtomicLong(770000);
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired GestionClientesService servicio;
    @Autowired ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private String admin,vendedor,repartidor,pendiente;
    private long base,cliente;

    @BeforeEach
    void preparar() {
        base=SEQ.addAndGet(10);
        UUID adminId=UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "ROL" ("codigoRol","nombreMostrado")
                    VALUES ('ADMINISTRADOR','Admin UC38'),('VENDEDOR','Vendedor UC38'),
                           ('REPARTIDOR','Repartidor UC38')
                    ON CONFLICT ("codigoRol") DO NOTHING
                    """);
            jdbc.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                        "estado","cambioCredencialPendiente","fechaHoraAlta")
                    VALUES (?,?,'HASH_TEST','HABILITADO',false,clock_timestamp()),
                           (?,?,'HASH_TEST','HABILITADO',false,clock_timestamp()),
                           (?,?,'HASH_TEST','HABILITADO',false,clock_timestamp()),
                           (?,?,'HASH_TEST','HABILITADO',true,clock_timestamp())
                    """,base,"admin_uc38_"+base,base+1,"vendedor_uc38_"+base,
                    base+2,"repartidor_uc38_"+base,base+3,"pendiente_uc38_"+base);
            jdbc.update("""
                    INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                    VALUES (?,'ADMINISTRADOR',clock_timestamp()),
                           (?,'VENDEDOR',clock_timestamp()),
                           (?,'REPARTIDOR',clock_timestamp()),
                           (?,'ADMINISTRADOR',clock_timestamp())
                    """,base,base+1,base+2,base+3);
            admin=sesion(base,adminId);
            vendedor=sesion(base+1,UUID.randomUUID());
            repartidor=sesion(base+2,UUID.randomUUID());
            pendiente=sesion(base+3,UUID.randomUUID());
        });
        cliente=servicio.registrarCliente(adminId,new GestionClientesService.DatosCliente(
                "Cliente UC38 "+base,"Prueba",telefono(),"San Martin","123",null,null,
                "Posadas","Misiones",null,"Centro",(short)3)).idCliente();
    }

    private String telefono() {return String.format("376%07d",base);}

    private String sesion(long idUsuario,UUID idSesion) {
        String token=TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,?,?,clock_timestamp()-interval '1 minute',
                       clock_timestamp()+interval '29 minutes')
                """,idSesion,idUsuario,TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private URI endpoint(long id) {
        return URI.create("http://127.0.0.1:"+port+"/api/v1/clientes/"+id+"/estado");
    }

    private HttpResponse<String> enviar(long id,String token,Object body) throws Exception {
        return enviarTexto(id,token,json.writeValueAsString(body));
    }

    private HttpResponse<String> enviarTexto(long id,String token,String body) throws Exception {
        var request=HttpRequest.newBuilder(endpoint(id)).timeout(Duration.ofSeconds(25))
                .header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.method("PATCH",HttpRequest.BodyPublishers.ofString(
                    body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
    }

    private Map<String,Object> estado(String nuevo) {
        return Map.of("nuevoEstado",nuevo);
    }

    private String estadoCliente() {
        return jdbc.queryForObject("SELECT \"estado\" FROM \"CLIENTE\" WHERE \"idCliente\"=?",
                String.class,cliente);
    }

    private int cambios() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM "HISTORIAL_ESTADO_CLIENTE" WHERE "idCliente"=?
                """,Integer.class,cliente);
    }

    @Test
    void administradorInactivaConHistorialMotivoYActorSinExponerDatos() throws Exception {
        var resp=enviar(cliente,admin,Map.of("nuevoEstado","INACTIVO","motivo","Sin contacto"));
        assertEquals(200,resp.statusCode(),resp.body());
        assertTrue(resp.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var r=json.readTree(resp.body());
        assertEquals(cliente,r.get("idCliente").asLong());
        assertEquals("INACTIVO",r.get("estado").asText());
        assertTrue(r.get("huboCambio").asBoolean());
        assertEquals(3,r.size(),resp.body());
        assertEquals("INACTIVO",estadoCliente());
        assertEquals(1,cambios());
        assertEquals(base,jdbc.queryForObject("""
                SELECT "idUsuarioAdministrador" FROM "HISTORIAL_ESTADO_CLIENTE"
                 WHERE "idCliente"=? AND "numeroCambio"=1
                """,Long.class,cliente));
        assertEquals("ACTIVO",jdbc.queryForObject("""
                SELECT "estadoAnterior" FROM "HISTORIAL_ESTADO_CLIENTE"
                 WHERE "idCliente"=? AND "numeroCambio"=1
                """,String.class,cliente));
        assertEquals("Sin contacto",jdbc.queryForObject("""
                SELECT "motivo" FROM "HISTORIAL_ESTADO_CLIENTE"
                 WHERE "idCliente"=? AND "numeroCambio"=1
                """,String.class,cliente));
        assertFalse(resp.body().contains(telefono()));
    }

    @Test
    void soloAdminPuedeEscribirYNingunOtroMetodoSeHabilita() throws Exception {
        assertEquals(401,enviar(cliente,null,estado("INACTIVO")).statusCode());
        assertEquals(403,enviar(cliente,vendedor,estado("INACTIVO")).statusCode());
        assertEquals(403,enviar(cliente,repartidor,estado("INACTIVO")).statusCode());
        assertEquals(403,enviar(cliente,pendiente,estado("INACTIVO")).statusCode());
        var cookie=HttpRequest.newBuilder(endpoint(cliente))
                .header("Cookie","JSESSIONID=usuario-administrador-falso")
                .header("Content-Type","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"nuevoEstado\":\"INACTIVO\"}"))
                .build();
        assertEquals(401,http.send(cookie,HttpResponse.BodyHandlers.ofString()).statusCode());
        var put=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+
                "/api/v1/clientes/"+cliente)).header("Authorization","Bearer "+admin)
                .header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        assertEquals(403,http.send(put,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("ACTIVO",estadoCliente());
        assertEquals(0,cambios());
    }

    @Test
    void validaPayloadEstrictoYSeleccionesAntesDeModificar() throws Exception {
        for(Object payload : new Object[]{
                Map.of("nuevoEstado","SUSPENDIDO"),
                Map.of("nuevoEstado","activo"),
                Map.of("estado","INACTIVO"),
                Map.of("nuevoEstado","INACTIVO","idUsuario",base),
                Map.of("nuevoEstado","INACTIVO","idCliente",cliente),
                Map.of("nuevoEstado","INACTIVO","motivo",23),
                Map.of("nuevoEstado","INACTIVO","motivo","X".repeat(2001))}) {
            assertEquals(400,enviar(cliente,admin,payload).statusCode(),payload.toString());
        }
        assertEquals(400,enviarTexto(cliente,admin,"{JSON").statusCode());
        assertEquals(400,enviar(0,admin,estado("INACTIVO")).statusCode());
        assertEquals(404,enviar(cliente+999999,admin,estado("INACTIVO")).statusCode());
        assertEquals("ACTIVO",estadoCliente());
        assertEquals(0,cambios());
    }

    @Test
    void noopNoCreaHistorialYReactivacionValidaCreaSegundoEvento() throws Exception {
        var sinCambio=enviar(cliente,admin,estado("ACTIVO"));
        assertEquals(200,sinCambio.statusCode());
        assertFalse(json.readTree(sinCambio.body()).get("huboCambio").asBoolean());
        assertEquals(0,cambios());
        assertEquals(200,enviar(cliente,admin,estado("INACTIVO")).statusCode());
        var otroNoop=enviar(cliente,admin,estado("INACTIVO"));
        assertEquals(200,otroNoop.statusCode());
        assertFalse(json.readTree(otroNoop.body()).get("huboCambio").asBoolean());
        jdbc.update("UPDATE \"CLIENTE\" SET \"telefonoPrincipal\"='X' WHERE \"idCliente\"=?",cliente);
        assertEquals(400,enviar(cliente,admin,estado("ACTIVO")).statusCode());
        assertEquals("INACTIVO",estadoCliente());
        assertEquals(1,cambios());
        jdbc.update("UPDATE \"CLIENTE\" SET \"telefonoPrincipal\"=? WHERE \"idCliente\"=?",
                telefono(),cliente);
        var reactivar=enviar(cliente,admin,estado("ACTIVO"));
        assertEquals(200,reactivar.statusCode(),reactivar.body());
        assertEquals("ACTIVO",estadoCliente());
        assertEquals(2,cambios());
        assertEquals("INACTIVO",jdbc.queryForObject("""
                SELECT "estadoAnterior" FROM "HISTORIAL_ESTADO_CLIENTE"
                 WHERE "idCliente"=? AND "numeroCambio"=2
                """,String.class,cliente));
    }

    @Test
    void inactivacionRevocaOtpYSesionesOnlinePeroConservaPedidoYCuentasF09() throws Exception {
        UUID desafio=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO "DESAFIO_AUTENTICACION_CLIENTE"
                    ("idDesafio","idCliente","telefonoDestino","codigoProtegido",
                     "fechaHoraCreacion","fechaHoraExpiracion","intentosFallidos")
                VALUES (?,?,?,decode('010203','hex'),clock_timestamp()-interval '1 minute',
                        clock_timestamp()+interval '1 hour',0)
                """,desafio,cliente,telefono());
        jdbc.update("""
                INSERT INTO "SESION_CLIENTE_ONLINE"
                    ("idSesionOnline","idDesafio","tokenProtegido",
                     "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,?,decode('aabbcc','hex'),clock_timestamp()-interval '1 minute',
                       clock_timestamp()+interval '1 hour')
                """,UUID.randomUUID(),desafio);
        jdbc.update("""
                INSERT INTO "POLITICA_CATEGORIA_COMERCIAL"
                    ("idPoliticaComercial","diasToleranciaMora","vigenteDesde",
                     "fechaHoraRegistro","idUsuarioRegistro")
                VALUES (?,7,clock_timestamp()-interval '1 day',clock_timestamp(),?)
                """,base+100,base);
        jdbc.update("""
                INSERT INTO "PEDIDO"
                    ("idPedido","idCliente","idUsuarioCreador","fechaHoraCarga",
                     "fechaReparto","canalOrigen","estado","categoriaComercialAplicada",
                     "idPoliticaComercialAplicada","tipoAjuste","porcentajeAjuste",
                     "totalOriginal","direccionEntregaCalle","direccionEntregaLocalidad")
                VALUES (?,?,?,clock_timestamp(),current_date,'INTERNO','PENDIENTE',
                        'CUMPLIDOR',?,'SIN_AJUSTE',0,100,'Calle histórica','Posadas')
                """,base+200,cliente,base+1,base+100);
        jdbc.update("""
                INSERT INTO "MOVIMIENTO_CUENTA"
                    ("idMovimientoCuenta","idCliente","numeroSecuenciaCliente",
                     "tipoMovimiento","importe","fechaHoraMovimiento","saldoResultante")
                VALUES (?,?,1,'CARGO_POR_VENTA',125,clock_timestamp(),125)
                """,base+300,cliente);
        assertEquals(200,enviar(cliente,admin,estado("INACTIVO")).statusCode());
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "DESAFIO_AUTENTICACION_CLIENTE"
                 WHERE "idCliente"=? AND "fechaHoraBloqueo" IS NOT NULL
                """,Integer.class,cliente));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_CLIENTE_ONLINE"
                 WHERE "idDesafio"=? AND "fechaHoraRevocacion" IS NOT NULL
                """,Integer.class,desafio));
        assertEquals("PENDIENTE",jdbc.queryForObject("""
                SELECT "estado" FROM "PEDIDO" WHERE "idPedido"=?
                """,String.class,base+200));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "MOVIMIENTO_CUENTA" WHERE "idCliente"=?
                """,Integer.class,cliente));
    }

    @Test
    void fallaDeHistorialRevierteCambioYRevocaciones() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION "fn_uc38_error_http"() RETURNS trigger
                LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Detalle privado UC38'; END; $$
                """);
        try {
            jdbc.execute("""
                    CREATE TRIGGER "tg_uc38_error_http"
                    BEFORE INSERT ON "HISTORIAL_ESTADO_CLIENTE"
                    FOR EACH ROW EXECUTE FUNCTION "fn_uc38_error_http"()
                    """);
            try {
                var r=enviar(cliente,admin,estado("INACTIVO"));
                assertEquals(503,r.statusCode(),r.body());
                assertFalse(r.body().contains("Detalle privado"));
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS \"tg_uc38_error_http\" ON \"HISTORIAL_ESTADO_CLIENTE\"");
            }
        } finally {
            jdbc.execute("DROP FUNCTION IF EXISTS \"fn_uc38_error_http\"()");
        }
        assertEquals("ACTIVO",estadoCliente());
        assertEquals(0,cambios());
    }

    @Test
    void dosCambiosConcurrentesMismoDestinoGeneranUnaSolaFila() throws Exception {
        var inicio=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{inicio.await(10,TimeUnit.SECONDS);
                return enviar(cliente,admin,estado("INACTIVO"));});
            var b=pool.submit(()->{inicio.await(10,TimeUnit.SECONDS);
                return enviar(cliente,admin,estado("INACTIVO"));});
            inicio.countDown();
            var ra=a.get(30,TimeUnit.SECONDS);
            var rb=b.get(30,TimeUnit.SECONDS);
            assertEquals(200,ra.statusCode(),ra.body());
            assertEquals(200,rb.statusCode(),rb.body());
            assertEquals(1,(json.readTree(ra.body()).get("huboCambio").asBoolean()?1:0)+
                    (json.readTree(rb.body()).get("huboCambio").asBoolean()?1:0));
            assertEquals(1,cambios());
            assertEquals("INACTIVO",estadoCliente());
        }
    }
}
