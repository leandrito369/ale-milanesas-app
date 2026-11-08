package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.AleMilanesasApplication;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="ale.http.writes.pedidos-uc03-enabled=true")
@Testcontainers
class CargarPedidoHttpExternoTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    PedidoFixture f;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() {f=tx.execute(status->new PedidoFixture(j));}
    String vendedor() {return f.tokens.get("VENDEDOR");}
    HttpResponse<String> texto(int puerto,String token,String body,String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+puerto+"/api/v1/pedidos")).timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(cookie!=null)b.header("Cookie",cookie);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String token,Map<String,Object> body) throws Exception {
        return texto(port,token,json.writeValueAsString(body),null);
    }
    void assertRespuesta(int estado,String codigo,HttpResponse<String> r) {
        assertEquals(estado,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if(codigo!=null)assertEquals(codigo,json.readTree(r.body()).get("codigo").asString());
    }
    @Test void postValidoRetorna201ConIdPedido() throws Exception {
        var r=post(vendedor(),f.body());assertRespuesta(201,null,r);
        var d=json.readTree(r.body());assertEquals(7,d.size());assertTrue(d.get("idPedido").asLong()>0);
        assertEquals("PENDIENTE",d.get("estado").asString());assertEquals("INTERNO",d.get("canalOrigen").asString());
        assertEquals("CUMPLIDOR",d.get("categoriaComercialAplicada").asString());assertEquals(2,d.get("cantidadItems").asInt());
        assertEquals(f.hoy.plusDays(2).toString(),d.get("fechaReparto").asString());
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"PEDIDO\" WHERE \"idPedido\"=? AND \"idCliente\"=?",Integer.class,d.get("idPedido").asLong(),f.base));
        var mora=f.body();mora.put("idCliente",f.base+1);var enMora=post(vendedor(),mora);
        assertRespuesta(201,null,enMora);assertEquals("EN_MORA",json.readTree(enMora.body()).get("categoriaComercialAplicada").asString());
    }
    @Test void postIdempotenteRetorna200ConMismoPedido() throws Exception {
        var body=f.body();var primero=post(vendedor(),body);assertRespuesta(201,null,primero);
        // Un reintento reconoce lo congelado incluso si el catálogo cambió desde la primera aceptación.
        j.update("UPDATE \"PRODUCTO\" SET \"activo\"=false WHERE \"idProducto\"=?",f.base);
        var segundo=post(vendedor(),body);assertRespuesta(200,null,segundo);
        assertEquals(json.readTree(primero.body()),json.readTree(segundo.body()));
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"PEDIDO\" WHERE \"idOperacionOrigen\"=?::uuid",Integer.class,body.get("claveIdempotencia")));
        assertEquals(2,j.queryForObject("SELECT count(*) FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=?",Integer.class,json.readTree(primero.body()).get("idPedido").asLong()));
    }
    @Test void postRechazaBodyInvalido() throws Exception {
        for(String campo:List.of("idCliente","fechaReparto","items","direccionEntrega","claveIdempotencia")) {
            var d=f.body();d.remove(campo);assertRespuesta(400,"SOLICITUD_INVALIDA",post(vendedor(),d));
        }
        for(String campo:List.of("idUsuarioCreador","estado","totalOriginal","tipoAjuste","idSesionOnline")) {
            var d=f.body();d.put(campo,1);assertRespuesta(400,"SOLICITUD_INVALIDA",post(vendedor(),d));
        }
        for(Object items:List.of(List.of(),"items",List.of(Map.of("idProducto",f.base,"cantidadSolicitada",1,"precioUnitarioOriginal",1)),
                List.of(Map.of("idProducto",f.base,"cantidadSolicitada","1")),List.of(Map.of("idProducto",1.5,"cantidadSolicitada",1)))) {
            var d=f.body();d.put("items",items);assertRespuesta(400,"SOLICITUD_INVALIDA",post(vendedor(),d));
        }
        for(Object direccion:List.of("direccion",Map.of("calle","C","localidad","L","extra",1),Map.of("calle",1,"localidad","L"),
                Map.of("calle","C\0","localidad","L"),Map.of("calle","C","localidad","L","numero",1))) {
            var d=f.body();d.put("direccionEntrega",direccion);assertRespuesta(400,"SOLICITUD_INVALIDA",post(vendedor(),d));
        }
        for(String clave:List.of("no-uuid","1-1-1-1-1","")) {
            var d=f.body();d.put("claveIdempotencia",clave);assertRespuesta(400,"SOLICITUD_INVALIDA",post(vendedor(),d));
        }
        for(String fecha:List.of("2026-02-30","2026-11-15T00:00:00Z","")) {
            var d=f.body();d.put("fechaReparto",fecha);assertRespuesta(400,"SOLICITUD_INVALIDA",post(vendedor(),d));
        }
        String valido=json.writeValueAsString(f.body());
        for(String raw:List.of("{","null","[]",valido+" true",valido.substring(0,valido.length()-1)+",\"idCliente\":1}"))
            assertRespuesta(400,"SOLICITUD_INVALIDA",texto(port,vendedor(),raw,null));
        assertEquals(0,j.queryForObject("SELECT count(*) FROM \"PEDIDO\" WHERE \"idCliente\"=?",Integer.class,f.base));
    }
    @Test void postRechazaCantidadNegativa() throws Exception {
        for(Object cantidad:List.of(-1,0,1.0001,"1",1e20)) {
            var d=f.body();d.put("items",List.of(Map.of("idProducto",f.base,"cantidadSolicitada",cantidad)));
            assertRespuesta(400,"SOLICITUD_INVALIDA",post(vendedor(),d));
        }
    }
    @Test void postRechazaFechaRepartoRetroactiva() throws Exception {
        var d=f.body();d.put("fechaReparto",f.hoy.minusDays(1).toString());
        assertRespuesta(400,"SOLICITUD_INVALIDA",post(vendedor(),d));
    }
    @Test void postSinTokenRetorna401() throws Exception {
        assertRespuesta(401,"SESION_INVALIDA",post(null,f.body()));
        assertRespuesta(401,"SESION_INVALIDA",texto(port,null,json.writeValueAsString(f.body()),"JSESSIONID="+vendedor()));
    }
    @Test void postConRolAdminRetorna403() throws Exception {
        for(String rol:List.of("ADMINISTRADOR","RESPONSABLE_PRODUCCION","REPARTIDOR","PROVEEDOR"))
            assertRespuesta(403,"OPERACION_PROHIBIDA",post(f.tokens.get(rol),f.body()));
    }
    @Test void postConGateDesactivadoRetorna403() throws Exception {
        try(var ctx=new SpringApplicationBuilder(AleMilanesasApplication.class).run(
                "--server.port=0","--spring.datasource.url="+db.getJdbcUrl(),"--spring.datasource.username="+db.getUsername(),
                "--spring.datasource.password="+db.getPassword(),"--ale.http.writes.pedidos-uc03-enabled=false")) {
            int otro=((WebServerApplicationContext)ctx).getWebServer().getPort();
            assertTrue(ctx.getBeansOfType(CargarPedidoHttpController.class).isEmpty());
            assertRespuesta(403,"OPERACION_PROHIBIDA",texto(otro,vendedor(),json.writeValueAsString(f.body()),null));
        }
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        var body=f.body();assertRespuesta(201,null,post(vendedor(),body));assertRespuesta(200,null,post(vendedor(),body));
        assertRespuesta(400,"SOLICITUD_INVALIDA",texto(port,vendedor(),"{}",null));
        assertRespuesta(401,"SESION_INVALIDA",post(null,f.body()));
        assertRespuesta(403,"OPERACION_PROHIBIDA",post(f.tokens.get("ADMINISTRADOR"),f.body()));
        j.execute("CREATE FUNCTION fallo_pedido_http_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fallo controlado'; END; $$");
        j.execute("CREATE TRIGGER fallo_pedido_http_test BEFORE INSERT ON \"DETALLE_PEDIDO\" FOR EACH ROW EXECUTE FUNCTION fallo_pedido_http_test()");
        try {assertRespuesta(503,"PERSISTENCIA_NO_DISPONIBLE",post(vendedor(),f.body()));}
        finally {
            j.execute("DROP TRIGGER fallo_pedido_http_test ON \"DETALLE_PEDIDO\"");j.execute("DROP FUNCTION fallo_pedido_http_test()");
        }
    }
}
