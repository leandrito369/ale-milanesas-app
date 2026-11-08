package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.AleMilanesasApplication;
import ar.edu.unam.alemilanesas.abastecimiento.IngresoReventaFixture;
import java.math.BigDecimal;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="ale.http.writes.compras-uc08-reventa-enabled=true")
@Testcontainers
class RegistrarIngresoStockReventaHttpExternoTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    IngresoReventaFixture f;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() {f=new IngresoReventaFixture(j,tx);}
    String produccion() {return f.tokens.get("RESPONSABLE_PRODUCCION");}
    HttpResponse<String> texto(int puerto,String token,String body,String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+puerto+"/api/v1/compras/recepciones-reventa"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(cookie!=null)b.header("Cookie",cookie);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> enviar(String token,Map<String,Object> body) throws Exception {
        return texto(port,token,json.writeValueAsString(body),null);
    }
    void respuesta(int status,String codigo,HttpResponse<String> r) {
        assertEquals(status,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if(codigo!=null)assertEquals(codigo,json.readTree(r.body()).get("codigo").asString());
    }
    String bodyExacto(String cantidad,String costo,String extra) {
        return """
                {"idProducto":%d,"idProveedorDirecto":%d,"cantidadRecibida":%s,
                "codigoUnidadMedida":"UN","costoUnitarioCompra":%s,"comprobante":"FACT-EXACTA"%s}
                """.formatted(f.base,f.base,cantidad,costo,extra);
    }
    @Test void postValidoRetorna201ConIdsCorrectos() throws Exception {
        var r=enviar(produccion(),f.body());respuesta(201,null,r);
        var d=json.readTree(r.body());assertEquals(3,d.size());assertEquals(new BigDecimal("150.0000"),
                j.queryForObject("SELECT \"costoPromedioResultante\" FROM \"MOVIMIENTO_STOCK_REVENTA\" WHERE \"idMovimientoStockReventa\"=?",BigDecimal.class,d.get("idMovimientoStockReventa").asLong()));
        assertEquals(1,j.queryForObject("""
                SELECT count(*) FROM "RECEPCION_COMPRA" r JOIN "MOVIMIENTO_STOCK_REVENTA" m USING ("idRecepcionCompra")
                WHERE r."idRecepcionCompra"=? AND m."idMovimientoStockReventa"=? AND r."idProductoReventa"=?
                AND r."idOrdenCompra" IS NULL AND r."idProveedorDirecto"=?
                """,Integer.class,d.get("idRecepcionCompra").asLong(),d.get("idMovimientoStockReventa").asLong(),f.base,f.base));
        var exacta=texto(port,produccion(),bodyExacto("1","99999999999999.9999",""),null);respuesta(201,null,exacta);
        long recepcion=json.readTree(exacta.body()).get("idRecepcionCompra").asLong();
        assertEquals(new BigDecimal("99999999999999.9999"),j.queryForObject(
                "SELECT \"costoUnitarioReal\" FROM \"RECEPCION_COMPRA\" WHERE \"idRecepcionCompra\"=?",BigDecimal.class,recepcion));
    }
    @Test void postConBodyInvalidoRetorna400() throws Exception {
        for(String campo:f.body().keySet()) {
            var d=new HashMap<>(f.body());d.remove(campo);
            respuesta(400,campo.equals("idProveedorDirecto")?"PROVEEDOR_DIRECTO_REQUERIDO":"SOLICITUD_INVALIDA",enviar(produccion(),d));
        }
        for(var cambio:List.<Map.Entry<String,Object>>of(Map.entry("intruso",1),Map.entry("cantidadRecibida","1"),
                Map.entry("cantidadRecibida",0),Map.entry("idProducto",1.5),Map.entry("comprobante","\0"),
                Map.entry("codigoUnidadMedida",false),Map.entry("idProveedorDirecto",0))) {
            var d=new HashMap<>(f.body());d.put(cambio.getKey(),cambio.getValue());
            respuesta(400,cambio.getKey().equals("idProveedorDirecto")?"PROVEEDOR_DIRECTO_REQUERIDO":"SOLICITUD_INVALIDA",enviar(produccion(),d));
        }
        for(String raw:List.of("{","null","[]",bodyExacto("1.0000000000000001","100",""),
                bodyExacto("1","12345678901234.56789",""),bodyExacto("1","100",",\"idProducto\":1"),
                bodyExacto("1","100","")+" true"))respuesta(400,"SOLICITUD_INVALIDA",texto(port,produccion(),raw,null));
        j.update("UPDATE \"PRODUCTO\" SET \"costoPromedioActualReventa\"=NULL WHERE \"idProducto\"=?",f.base);
        respuesta(400,"VALUACION_EXTRAORDINARIA_REQUERIDA",enviar(produccion(),f.body()));
        assertEquals(0,j.queryForObject("SELECT count(*) FROM \"RECEPCION_COMPRA\" WHERE \"idProductoReventa\"=?",Integer.class,f.base));
    }
    @Test void postConOrdenCompraRetorna400() throws Exception {
        for(String valor:List.of("null","1","0"))respuesta(400,"ORDEN_NO_APLICA_REVENTA",
                texto(port,produccion(),bodyExacto("1","100",",\"idOrdenCompra\":"+valor),null));
    }
    @Test void postSinTokenRetorna401() throws Exception {respuesta(401,"SESION_INVALIDA",enviar(null,f.body()));}
    @Test void postConRolNoAutorizadoRetorna403() throws Exception {
        for(String rol:List.of("ADMINISTRADOR","VENDEDOR","REPARTIDOR","PROVEEDOR"))
            respuesta(403,"OPERACION_PROHIBIDA",enviar(f.tokens.get(rol),f.body()));
    }
    @Test void postConCookieRetorna401() throws Exception {
        respuesta(401,"SESION_INVALIDA",texto(port,null,json.writeValueAsString(f.body()),"JSESSIONID="+produccion()));
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        respuesta(201,null,enviar(produccion(),f.body()));
        respuesta(401,"SESION_INVALIDA",enviar(null,f.body()));
        respuesta(403,"OPERACION_PROHIBIDA",enviar(f.tokens.get("ADMINISTRADOR"),f.body()));
        var d=new HashMap<>(f.body());d.put("idProducto",Long.MAX_VALUE);
        respuesta(404,"PRODUCTO_NO_ENCONTRADO",enviar(produccion(),d));
        d=new HashMap<>(f.body());d.put("idProveedorDirecto",Long.MAX_VALUE);
        respuesta(404,"PROVEEDOR_NO_ENCONTRADO",enviar(produccion(),d));
        j.execute("ALTER TABLE \"PRODUCTO\" RENAME TO \"PRODUCTO_TEST_OFFLINE\"");
        try {respuesta(503,"PERSISTENCIA_NO_DISPONIBLE",enviar(produccion(),f.body()));}
        finally {j.execute("ALTER TABLE \"PRODUCTO_TEST_OFFLINE\" RENAME TO \"PRODUCTO\"");}
        try(var ctx=new SpringApplicationBuilder(AleMilanesasApplication.class).run("--server.port=0",
                "--spring.datasource.url="+db.getJdbcUrl(),"--spring.datasource.username="+db.getUsername(),
                "--spring.datasource.password="+db.getPassword(),"--ale.http.writes.compras-uc08-reventa-enabled=false")) {
            assertTrue(ctx.getBeansOfType(RegistrarIngresoStockReventaHttpController.class).isEmpty());
            int puerto=((WebServerApplicationContext)ctx).getWebServer().getPort();
            respuesta(403,"OPERACION_PROHIBIDA",texto(puerto,produccion(),json.writeValueAsString(f.body()),null));
        }
        try(var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("reventa-test",
                    Map.of("ale.http.writes.compras-uc08-reventa-enabled","true")));
            ctx.register(RegistrarIngresoStockReventaHttpController.class);ctx.refresh();
            assertTrue(ctx.getBeansOfType(RegistrarIngresoStockReventaHttpController.class).isEmpty());
        }
    }
}
