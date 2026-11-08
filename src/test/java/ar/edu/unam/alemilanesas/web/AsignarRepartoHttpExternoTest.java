package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.AleMilanesasApplication;
import ar.edu.unam.alemilanesas.ventas.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="ale.http.writes.pedidos-uc43-enabled=true")
@Testcontainers(disabledWithoutDocker=true)
class AsignarRepartoHttpExternoTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired ObjectMapper json;
    AsignacionRepartoFixture f;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() {f=tx.execute(s->new AsignacionRepartoFixture(j,pedidos));}
    String vendedor() {return f.origen.tokens.get("VENDEDOR");}
    HttpResponse<String> texto(int puerto,String id,String token,String body,String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+puerto+"/api/v1/pedidos/"+id+"/asignacion-reparto"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(cookie!=null)b.header("Cookie",cookie);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(long pedido,String token,Map<String,Object> body) throws Exception {
        return texto(port,Long.toString(pedido),token,json.writeValueAsString(body),null);
    }
    HttpResponse<String> primera() throws Exception {return post(f.pendiente,vendedor(),f.body(f.primero));}
    Map<String,Object> cambio(String motivo) {
        var d=new HashMap<>(f.body(f.segundo));d.put("motivoReasignacion",motivo);return d;
    }
    void assertRespuesta(int estado,String codigo,HttpResponse<String> r) {
        assertEquals(estado,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if(codigo!=null)assertEquals(codigo,json.readTree(r.body()).get("codigo").asString());
    }
    @Test void postPrimeraAsignacionRetorna201() throws Exception {
        var r=primera();assertRespuesta(201,null,r);var d=json.readTree(r.body());assertEquals(5,d.size());
        assertEquals(f.pendiente,d.get("idPedido").asLong());assertEquals(1,d.get("numeroAsignacion").asInt());
        assertEquals(f.primero,d.get("idUsuarioRepartidor").asLong());assertFalse(d.get("fueReasignacion").asBoolean());
        var inicio=j.queryForObject("SELECT \"fechaHoraInicio\" FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? AND \"numeroAsignacion\"=1",OffsetDateTime.class,f.pendiente);
        assertTrue(OffsetDateTime.parse(d.get("fechaHoraInicio").asString()).isEqual(inicio));
        assertEquals(f.origen.base,j.queryForObject("SELECT \"idUsuarioVendedor\" FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? AND \"numeroAsignacion\"=1",Long.class,f.pendiente));
    }
    @Test void postReasignacionRetorna201() throws Exception {
        assertRespuesta(201,null,primera());var r=post(f.pendiente,vendedor(),cambio("  Vacaciones  "));assertRespuesta(201,null,r);
        var d=json.readTree(r.body());assertEquals(2,d.get("numeroAsignacion").asInt());assertTrue(d.get("fueReasignacion").asBoolean());
        assertEquals("Vacaciones",j.queryForObject("SELECT \"motivoReasignacion\" FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? AND \"numeroAsignacion\"=2",String.class,f.pendiente));
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? AND \"fechaHoraFin\" IS NOT NULL",Integer.class,f.pendiente));
    }
    @Test void postMismoRepartidorRetorna409() throws Exception {
        assertRespuesta(201,null,primera());assertRespuesta(409,"SIN_CAMBIO",primera());
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=?",Integer.class,f.pendiente));
    }
    @Test void postPedidoNoPendienteRetorna409() throws Exception {
        for(long id:new long[]{f.entregado,f.cancelado})
            assertRespuesta(409,"PEDIDO_NO_PENDIENTE",post(id,vendedor(),f.body(f.primero)));
    }
    @Test void postRepartidorInvalidoRetorna400() throws Exception {
        for(long id:new long[]{f.deshabilitado,f.origen.base+2,Long.MAX_VALUE})
            assertRespuesta(400,"REPARTIDOR_NO_ELEGIBLE",post(f.pendiente,vendedor(),f.body(id)));
        for(Object id:List.of(0,-1,"1",1.5,true,new java.math.BigInteger("9223372036854775808")))
            assertRespuesta(400,"SOLICITUD_INVALIDA",post(f.pendiente,vendedor(),Map.of("idUsuarioRepartidor",id)));
        for(String campo:List.of("idUsuarioVendedor","idPedido","estado","offline","numeroAsignacion")) {
            var d=new HashMap<>(f.body(f.primero));d.put(campo,1);assertRespuesta(400,"SOLICITUD_INVALIDA",post(f.pendiente,vendedor(),d));
        }
        for(String raw:List.of("{}","null","[]","{",json.writeValueAsString(f.body(f.primero))+" true",
                "{\"idUsuarioRepartidor\":1,\"idUsuarioRepartidor\":2}"))
            assertRespuesta(400,"SOLICITUD_INVALIDA",texto(port,Long.toString(f.pendiente),vendedor(),raw,null));
        for(Object motivo:List.of(1,true,"x".repeat(251),"a\0")) {
            var d=new HashMap<>(f.body(f.primero));d.put("motivoReasignacion",motivo);
            assertRespuesta(400,"SOLICITUD_INVALIDA",post(f.pendiente,vendedor(),d));
        }
        assertRespuesta(400,"SOLICITUD_INVALIDA",texto(port,"abc",vendedor(),json.writeValueAsString(f.body(f.primero)),null));
        assertRespuesta(400,"SOLICITUD_INVALIDA",post(0,vendedor(),f.body(f.primero)));
    }
    @Test void postSinMotivoEnReasignacionRetorna400() throws Exception {
        assertRespuesta(400,"MOTIVO_NO_APLICA",post(f.pendiente,vendedor(),Map.of("idUsuarioRepartidor",f.primero,"motivoReasignacion","Cambio")));
        assertRespuesta(201,null,primera());
        assertRespuesta(400,"MOTIVO_REQUERIDO",post(f.pendiente,vendedor(),f.body(f.segundo)));
        assertRespuesta(400,"MOTIVO_REQUERIDO",post(f.pendiente,vendedor(),cambio("   ")));
        assertRespuesta(400,"MOTIVO_REQUERIDO",post(f.pendiente,vendedor(),cambio(null)));
    }
    @Test void postSinTokenRetorna401() throws Exception {
        assertRespuesta(401,"SESION_INVALIDA",post(f.pendiente,null,f.body(f.primero)));
        assertRespuesta(401,"SESION_INVALIDA",texto(port,Long.toString(f.pendiente),null,json.writeValueAsString(f.body(f.primero)),"JSESSIONID="+vendedor()));
    }
    @Test void postConRolAdminRetorna403() throws Exception {
        for(String rol:List.of("ADMINISTRADOR","RESPONSABLE_PRODUCCION","REPARTIDOR","PROVEEDOR"))
            assertRespuesta(403,"OPERACION_PROHIBIDA",post(f.pendiente,f.origen.tokens.get(rol),f.body(f.primero)));
    }
    @Test void postConGateDesactivadoRetorna403() throws Exception {
        try(var ctx=new SpringApplicationBuilder(AleMilanesasApplication.class).run("--server.port=0",
                "--spring.datasource.url="+db.getJdbcUrl(),"--spring.datasource.username="+db.getUsername(),
                "--spring.datasource.password="+db.getPassword(),"--ale.http.writes.pedidos-uc43-enabled=false",
                "--ale.http.writes.pedidos-uc03-enabled=true")) {
            assertTrue(ctx.getBeansOfType(AsignarRepartoHttpController.class).isEmpty());
            int otro=((WebServerApplicationContext)ctx).getWebServer().getPort();
            assertRespuesta(403,"OPERACION_PROHIBIDA",texto(otro,Long.toString(f.pendiente),vendedor(),json.writeValueAsString(f.body(f.primero)),null));
        }
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        assertRespuesta(201,null,primera());assertRespuesta(409,"SIN_CAMBIO",primera());
        assertRespuesta(400,"MOTIVO_REQUERIDO",post(f.pendiente,vendedor(),f.body(f.segundo)));
        assertRespuesta(404,"PEDIDO_NO_ENCONTRADO",post(Long.MAX_VALUE,vendedor(),f.body(f.primero)));
        assertRespuesta(401,"SESION_INVALIDA",post(f.pendiente,null,f.body(f.primero)));
        assertRespuesta(403,"OPERACION_PROHIBIDA",post(f.pendiente,f.origen.tokens.get("ADMINISTRADOR"),f.body(f.primero)));
        j.execute("CREATE FUNCTION fallo_reparto_http_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fallo controlado'; END; $$");
        j.execute("CREATE TRIGGER fallo_reparto_http_test BEFORE INSERT ON \"ASIGNACION_REPARTO\" FOR EACH ROW EXECUTE FUNCTION fallo_reparto_http_test()");
        try {assertRespuesta(503,"PERSISTENCIA_NO_DISPONIBLE",post(f.pendiente,vendedor(),cambio("Reemplazo")));}
        finally {j.execute("DROP TRIGGER fallo_reparto_http_test ON \"ASIGNACION_REPARTO\"");j.execute("DROP FUNCTION fallo_reparto_http_test()");}
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? AND \"fechaHoraFin\" IS NULL",Integer.class,f.pendiente));
    }
}
