package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.AleMilanesasApplication;
import ar.edu.unam.alemilanesas.abastecimiento.IngresoStockFixture;
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
        properties="ale.http.writes.compras-uc08-insumo-enabled=true")
@Testcontainers
class RegistrarIngresoStockInsumoHttpExternoTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    IngresoStockFixture f;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() { f=new IngresoStockFixture(j,tx); }
    String produccion() { return f.tokens.get("RESPONSABLE_PRODUCCION"); }
    HttpResponse<String> enviar(int puerto,String token,String body,String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+puerto+"/api/v1/compras/recepciones-insumo"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(cookie!=null)b.header("Cookie",cookie);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> enviar(String token,Map<String,Object> body) throws Exception {
        return enviar(port,token,json.writeValueAsString(body),null);
    }
    void respuesta(int status,String codigo,HttpResponse<String> r) {
        assertEquals(status,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if(codigo!=null)assertEquals(codigo,json.readTree(r.body()).get("codigo").asString());
    }
    @Test void postValidoRetorna201ConIdsCorrectos() throws Exception {
        var r=enviar(produccion(),f.body());respuesta(201,null,r);
        var datos=json.readTree(r.body());assertEquals(4,datos.size());
        assertEquals("PARCIALMENTE_RECIBIDA",datos.get("estadoOrdenCompra").asString());
        assertEquals(1,j.queryForObject("""
                SELECT count(*) FROM "RECEPCION_COMPRA" r JOIN "MOVIMIENTO_INSUMO" m USING ("idRecepcionCompra")
                JOIN "PARTIDA_INSUMO" p USING ("idRecepcionCompra")
                WHERE r."idRecepcionCompra"=? AND m."idMovimientoInsumo"=? AND p."idPartidaInsumo"=?
                AND r."idOrdenCompra"=? AND p."cantidadDisponible"=5
                """,Integer.class,datos.get("idRecepcionCompra").asLong(),datos.get("idMovimientoInsumo").asLong(),
                datos.get("idPartidaInsumo").asLong(),f.base));
    }
    @Test void postConBodyInvalidoRetorna400() throws Exception {
        for (String campo:f.body().keySet()) {
            var d=new HashMap<>(f.body()); d.remove(campo);
            respuesta(400,campo.equals("idOrdenCompra")?"ORDEN_COMPRA_REQUERIDA":"SOLICITUD_INVALIDA",enviar(produccion(),d));
        }
        for (var cambio:List.<Map.Entry<String,Object>>of(Map.entry("intruso",1),Map.entry("cantidadRecibida","5"),
                Map.entry("cantidadRecibida",0),Map.entry("cantidadRecibida",-1),Map.entry("cantidadRecibida",0.0001),
                Map.entry("idInsumo",1.5),Map.entry("idOrdenCompra",0),Map.entry("codigoUnidadMedida",true),
                Map.entry("comprobante","FACT\0NUL"),Map.entry("comprobante"," "),Map.entry("costoUnitarioCompra",false))) {
            var d=new HashMap<>(f.body());d.put(cambio.getKey(),cambio.getValue());
            respuesta(400,cambio.getKey().equals("idOrdenCompra")?"ORDEN_COMPRA_REQUERIDA":"SOLICITUD_INVALIDA",enviar(produccion(),d));
        }
        respuesta(400,"SOLICITUD_INVALIDA",enviar(port,produccion(),"{",null));
        var d=new HashMap<>(f.body());d.put("codigoUnidadMedida","G");
        respuesta(400,"UNIDAD_NO_COINCIDE",enviar(produccion(),d));
        assertEquals(0,j.queryForObject("SELECT count(*) FROM \"RECEPCION_COMPRA\" WHERE \"idOrdenCompra\"=?",Integer.class,f.base));
    }
    @Test void postSinTokenRetorna401() throws Exception { respuesta(401,"SESION_INVALIDA",enviar(null,f.body())); }
    @Test void postConRolNoAutorizadoRetorna403() throws Exception {
        for(String rol:List.of("VENDEDOR","ADMINISTRADOR","REPARTIDOR","PROVEEDOR"))
            respuesta(403,"OPERACION_PROHIBIDA",enviar(f.tokens.get(rol),f.body()));
    }
    @Test void postConCookieRetorna401() throws Exception {
        respuesta(401,"SESION_INVALIDA",enviar(port,null,json.writeValueAsString(f.body()),"JSESSIONID="+produccion()));
    }
    @Test void postConOrdenYaRecibidaRetorna409() throws Exception {
        j.update("UPDATE \"ORDEN_COMPRA\" SET \"estado\"='RECIBIDA' WHERE \"idOrdenCompra\"=?",f.base);
        respuesta(409,"ORDEN_YA_RECIBIDA",enviar(produccion(),f.body()));
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        respuesta(201,null,enviar(produccion(),f.body()));
        respuesta(401,"SESION_INVALIDA",enviar(null,f.body()));
        respuesta(403,"OPERACION_PROHIBIDA",enviar(f.tokens.get("ADMINISTRADOR"),f.body()));
        var d=new HashMap<>(f.body());d.put("idInsumo",Long.MAX_VALUE);
        respuesta(404,"INSUMO_NO_ENCONTRADO",enviar(produccion(),d));
        d=new HashMap<>(f.body());d.put("idOrdenCompra",Long.MAX_VALUE);
        respuesta(404,"ORDEN_COMPRA_NO_ENCONTRADA",enviar(produccion(),d));
        d=new HashMap<>(f.body());d.put("cantidadRecibida",21);
        respuesta(409,"CANTIDAD_EXCEDE_PENDIENTE",enviar(produccion(),d));
        // Forzar falla de persistencia posterior a la autenticación, sin mocks.
        j.execute("ALTER TABLE \"ORDEN_COMPRA\" RENAME TO \"ORDEN_COMPRA_TEST_OFFLINE\"");
        try { respuesta(503,"PERSISTENCIA_NO_DISPONIBLE",enviar(produccion(),f.body())); }
        finally { j.execute("ALTER TABLE \"ORDEN_COMPRA_TEST_OFFLINE\" RENAME TO \"ORDEN_COMPRA\""); }
    }
    @Test void gateDesactivadoRetorna403() throws Exception {
        try(var ctx=new SpringApplicationBuilder(AleMilanesasApplication.class).run(
                "--server.port=0","--spring.datasource.url="+db.getJdbcUrl(),
                "--spring.datasource.username="+db.getUsername(),"--spring.datasource.password="+db.getPassword(),
                "--ale.http.writes.compras-uc08-insumo-enabled=false")) {
            int puerto=((WebServerApplicationContext)ctx).getWebServer().getPort();
            assertTrue(ctx.getBeansOfType(RegistrarIngresoStockInsumoHttpController.class).isEmpty());
            respuesta(403,"OPERACION_PROHIBIDA",enviar(puerto,produccion(),json.writeValueAsString(f.body()),null));
        }
        try(var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "uc08-test",Map.of("ale.http.writes.compras-uc08-insumo-enabled","true")));
            ctx.register(RegistrarIngresoStockInsumoHttpController.class);ctx.refresh();
            assertTrue(ctx.getBeansOfType(RegistrarIngresoStockInsumoHttpController.class).isEmpty());
        }
    }
}
