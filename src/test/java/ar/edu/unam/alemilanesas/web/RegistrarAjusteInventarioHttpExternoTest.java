package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.AleMilanesasApplication;
import ar.edu.unam.alemilanesas.abastecimiento.AjusteInventarioFixture;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="ale.http.writes.compras-uc42-enabled=true")
@Testcontainers
class RegistrarAjusteInventarioHttpExternoTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    AjusteInventarioFixture f;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() {f=new AjusteInventarioFixture(j,tx);}
    String produccion() {return f.compra.tokens.get("RESPONSABLE_PRODUCCION");}
    HttpResponse<String> texto(int puerto,String token,String body,String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+puerto+"/api/v1/inventario/ajustes"))
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
    @Test void postValidoRetorna201() throws Exception {
        var r=enviar(produccion(),f.body());respuesta(201,null,r);var d=json.readTree(r.body());
        assertEquals(4,d.size());assertEquals("SALDO_INICIAL",d.get("tipoMovimiento").asString());
        assertEquals(2,d.get("cantidadPartidas").asInt());assertEquals(10,d.get("nuevoStockActual").asDouble());
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_INSUMO\" WHERE \"idMovimientoInsumo\"=? AND \"idInsumo\"=?",Integer.class,d.get("idMovimientoInsumo").asLong(),f.insumo));
    }
    @Test void postSaldoInicialDuplicadoRetorna409() throws Exception {
        respuesta(201,null,enviar(produccion(),f.body()));
        respuesta(409,"SALDO_INICIAL_DUPLICADO",enviar(produccion(),f.body()));
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_INSUMO\" WHERE \"idInsumo\"=?",Integer.class,f.insumo));
    }
    @Test void postConBodyInvalidoRetorna400() throws Exception {
        for(String campo:f.body().keySet()) {
            var d=new HashMap<>(f.body());d.remove(campo);
            respuesta(400,campo.equals("costoValuacion")?"COSTO_VALUACION_REQUERIDO":"SOLICITUD_INVALIDA",enviar(produccion(),d));
        }
        for(var cambio:List.<Map.Entry<String,Object>>of(Map.entry("intruso",1),Map.entry("cantidad","10"),
                Map.entry("cantidad",0),Map.entry("cantidad",1.0001),Map.entry("idElemento",1.5),
                Map.entry("motivo","\0"),Map.entry("tipoElemento","REVENTA"),Map.entry("partidas",List.of()),
                Map.entry("partidas",List.of(Map.of("cantidad",10,"intruso",1))),
                Map.entry("partidas",List.of(Map.of("cantidad",10,"marcarApertura","true"))),
                Map.entry("partidas",List.of(Map.of("cantidad",10,"fechaVencimiento","2026-02-30"))),
                Map.entry("partidas",List.of(Map.of("cantidad",9))))) {
            var d=new HashMap<>(f.body());d.put(cambio.getKey(),cambio.getValue());respuesta(400,"SOLICITUD_INVALIDA",enviar(produccion(),d));
        }
        String valido=json.writeValueAsString(f.body());
        for(String raw:List.of("{","null","[]",valido+" true",valido.replace("\"cantidad\":10","\"cantidad\":10,\"cantidad\":10")))
            respuesta(400,"SOLICITUD_INVALIDA",texto(port,produccion(),raw,null));
    }
    @Test void postSinTokenRetorna401() throws Exception {respuesta(401,"SESION_INVALIDA",enviar(null,f.body()));}
    @Test void postConRolNoAutorizadoRetorna403() throws Exception {
        for(String rol:List.of("VENDEDOR","ADMINISTRADOR","REPARTIDOR","PROVEEDOR"))
            respuesta(403,"OPERACION_PROHIBIDA",enviar(f.compra.tokens.get(rol),f.body()));
    }
    @Test void postConCookieRetorna401() throws Exception {
        respuesta(401,"SESION_INVALIDA",texto(port,null,json.writeValueAsString(f.body()),"JSESSIONID="+produccion()));
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        respuesta(201,null,enviar(produccion(),f.body()));
        respuesta(409,"SALDO_INICIAL_DUPLICADO",enviar(produccion(),f.body()));
        respuesta(401,"SESION_INVALIDA",enviar(null,f.body()));
        respuesta(403,"OPERACION_PROHIBIDA",enviar(f.compra.tokens.get("ADMINISTRADOR"),f.body()));
        var d=new HashMap<>(f.body());d.put("idElemento",Long.MAX_VALUE);
        respuesta(404,"INSUMO_NO_ENCONTRADO",enviar(produccion(),d));
        j.execute("ALTER TABLE \"INSUMO\" RENAME TO \"INSUMO_TEST_OFFLINE\"");
        try {respuesta(503,"PERSISTENCIA_NO_DISPONIBLE",enviar(produccion(),f.body()));}
        finally {j.execute("ALTER TABLE \"INSUMO_TEST_OFFLINE\" RENAME TO \"INSUMO\"");}
    }
    @Test void gateDesactivadoRetorna403() throws Exception {
        try(var ctx=new SpringApplicationBuilder(AleMilanesasApplication.class).run("--server.port=0",
                "--spring.datasource.url="+db.getJdbcUrl(),"--spring.datasource.username="+db.getUsername(),
                "--spring.datasource.password="+db.getPassword(),"--ale.http.writes.compras-uc42-enabled=false")) {
            assertTrue(ctx.getBeansOfType(RegistrarAjusteInventarioHttpController.class).isEmpty());
            int puerto=((WebServerApplicationContext)ctx).getWebServer().getPort();
            respuesta(403,"OPERACION_PROHIBIDA",texto(puerto,produccion(),json.writeValueAsString(f.body()),null));
        }
        try(var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("uc42-test",
                    Map.of("ale.http.writes.compras-uc42-enabled","true")));
            ctx.register(RegistrarAjusteInventarioHttpController.class);ctx.refresh();
            assertTrue(ctx.getBeansOfType(RegistrarAjusteInventarioHttpController.class).isEmpty());
        }
    }
}
