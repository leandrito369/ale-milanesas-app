package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.AleMilanesasApplication;
import ar.edu.unam.alemilanesas.produccioninventario.PreparacionFixture;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
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
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="ale.http.writes.preparaciones-uc28-enabled=true")
@Testcontainers
class RegistrarPreparacionHttpExternoTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl); r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int puerto;
    @Autowired CargarPedidoService pedidos;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    PreparacionFixture f;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() { f = new PreparacionFixture(j,tx,pedidos); }
    String token() { return f.actores.tokens.get("RESPONSABLE_PRODUCCION"); }
    String ruta(long pedido) { return "/api/v1/pedidos/"+pedido+"/preparaciones"; }
    Map<String,Object> body(String cantidad) {
        return new HashMap<>(Map.of("idProducto",f.producto,"cantidadPreparada",new BigDecimal(cantidad)));
    }
    HttpResponse<String> request(int port,String metodo,String ruta,String token,String body,String cookie) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+ruta)).timeout(Duration.ofSeconds(20));
        if (token != null) b.header("Authorization","Bearer "+token);
        if (cookie != null) b.header("Cookie",cookie);
        if (body != null) b.header("Content-Type","application/json");
        return http.send(b.method(metodo,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(Map<String,Object> d) throws Exception { return post(token(),d); }
    HttpResponse<String> post(String token,Map<String,Object> d) throws Exception {
        return request(puerto,"POST",ruta(f.pedido),token,json.writeValueAsString(d),null);
    }
    HttpResponse<String> buscar(int port) throws Exception {
        return request(port,"GET","/api/v1/pedidos?idPedido="+f.pedido,token(),null,null);
    }
    void assertRespuesta(int estado,String codigo,HttpResponse<String> r) {
        assertEquals(estado,r.statusCode(),r.body()); assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if (codigo != null) assertEquals(codigo,json.readTree(r.body()).get("codigo").asString());
    }
    @Test void postPreparacionValidaRetorna201() throws Exception {
        var r = post(body("5.000")); assertRespuesta(201,null,r); var d = json.readTree(r.body());
        assertEquals(7,d.size()); assertEquals(f.pedido,d.get("idPedido").asLong()); assertEquals(f.producto,d.get("idProducto").asLong());
        assertEquals(1,d.get("numeroDetalle").asInt()); assertEquals(2,d.get("cantidadInsumosConsumidos").asInt());
        assertEquals(1,f.preparaciones()); assertEquals(new BigDecimal("9.000"),f.disponible(f.lote));
    }
    @Test void postPreparacionConOverrideRetorna201() throws Exception {
        var d = body("5"); d.put("partidas",f.override().stream().map(p -> Map.of("idInsumo",p.idInsumo(),
                "idPartidaInsumo",p.idPartidaInsumo(),"cantidadAplicada",p.cantidadAplicada())).toList());
        assertRespuesta(201,null,post(d)); assertEquals(new BigDecimal("9.000"),f.disponible(f.lote));
        assertEquals(new BigDecimal("7.500"),f.disponible(f.lote+1));
    }
    @Test void partidasVacioActivaSeleccionAutomatica() throws Exception {
        var d = body("5"); d.put("partidas",List.of());
        assertRespuesta(201,null,post(d));
        assertEquals(new BigDecimal("9.000"),f.disponible(f.lote));
        assertEquals(new BigDecimal("7.500"),f.disponible(f.lote+1));
    }
    @Test void marcarAperturaExplicitoAceptaBooleanosYNulo() throws Exception {
        for (Boolean marcar : Arrays.asList(true,false,null)) {
            var d = body("1");
            d.put("partidas",List.of(partida(f.insumo,f.lote,"0.2",marcar),partida(f.insumo+1,f.lote+1,"0.5",marcar)));
            assertRespuesta(201,null,post(d));
        }
        assertEquals(new BigDecimal("9.400"),f.disponible(f.lote));
    }
    Map<String,Object> partida(long insumo,long lote,String cantidad,Boolean marcar) {
        var p = new HashMap<String,Object>(Map.of("idInsumo",insumo,"idPartidaInsumo",lote,"cantidadAplicada",new BigDecimal(cantidad)));
        p.put("marcarApertura",marcar); return p;
    }
    @Test void postPreparacionSinRecetaRetorna400() throws Exception {
        j.update("UPDATE \"VERSION_RECETA\" SET \"vigenteHasta\"=? WHERE \"idReceta\"=?",f.fecha.minusDays(1),f.receta);
        assertRespuesta(400,"SIN_RECETA_VIGENTE",post(body("5"))); assertEquals(0,f.preparaciones());
    }
    @Test void postPreparacionExcedeCantidadRetorna400() throws Exception {
        assertRespuesta(201,null,post(body("7")));
        assertRespuesta(400,"CANTIDAD_EXCEDE_ORIGINAL",post(body("4"))); assertEquals(1,f.preparaciones());
    }
    @Test void postPreparacionStockInsuficienteRetorna409() throws Exception {
        j.update("UPDATE \"PARTIDA_INSUMO\" SET \"fechaVencimiento\"=CURRENT_DATE-1 WHERE \"idInsumo\"=?",f.insumo);
        var antes = f.snapshot(); assertRespuesta(409,"STOCK_UTILIZABLE_INSUFICIENTE",post(body("5"))); assertEquals(antes,f.snapshot());
    }
    @Test void postSinTokenRetorna401() throws Exception {
        String d = json.writeValueAsString(body("5"));
        assertRespuesta(401,"SESION_INVALIDA",request(puerto,"POST",ruta(f.pedido),null,d,null));
        assertRespuesta(401,"SESION_INVALIDA",request(puerto,"POST",ruta(f.pedido),null,d,"JSESSIONID="+token()));
        j.update("UPDATE \"SESION_USUARIO\" SET \"fechaHoraRevocacion\"=clock_timestamp() WHERE \"idSesionUsuario\"=?",f.produccion());
        assertRespuesta(401,"SESION_INVALIDA",post(body("5")));
    }
    @Test void postConRolAdminRetorna403() throws Exception {
        for (String rol : List.of("ADMINISTRADOR","VENDEDOR","REPARTIDOR","PROVEEDOR"))
            assertRespuesta(403,"OPERACION_PROHIBIDA",post(f.actores.tokens.get(rol),body("5")));
    }
    @Test void postConGateDesactivadoRetorna403() throws Exception {
        try (var ctx = new SpringApplicationBuilder(AleMilanesasApplication.class).run("--server.port=0",
                "--spring.datasource.url="+db.getJdbcUrl(),"--spring.datasource.username="+db.getUsername(),
                "--spring.datasource.password="+db.getPassword(),"--ale.http.writes.preparaciones-uc28-enabled=false")) {
            assertTrue(ctx.getBeansOfType(RegistrarPreparacionHttpController.class).isEmpty());
            int port = ((WebServerApplicationContext)ctx).getWebServer().getPort();
            assertRespuesta(403,"OPERACION_PROHIBIDA",request(port,"POST",ruta(f.pedido),token(),json.writeValueAsString(body("5")),null));
            assertRespuesta(200,null,buscar(port)); // Lectura contextual independiente de la escritura.
        }
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("uc28-prod",
                    Map.of("ale.http.writes.preparaciones-uc28-enabled","true")));
            ctx.register(RegistrarPreparacionHttpController.class); ctx.refresh();
            assertTrue(ctx.getBeansOfType(RegistrarPreparacionHttpController.class).isEmpty());
        }
        var veto = new PrecondicionesDespliegue(new MockEnvironment().withProperty("ale.http.writes.preparaciones-uc28-enabled","true"));
        assertEquals("I3-01c UC-28 (preparaciones) HTTP bloqueado en produccion",
                assertThrows(IllegalStateException.class,() -> veto.postProcessBeanFactory(null)).getMessage());
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        var valido = body("1"); valido.put("partidas",null); assertRespuesta(201,null,post(valido));
        for (var cambio : List.<Map.Entry<String,Object>>of(Map.entry("extra",1),Map.entry("idPedido",f.pedido),
                Map.entry("idProducto","10"),Map.entry("idProducto",new BigDecimal("9223372036854775808")),
                Map.entry("cantidadPreparada","1"),Map.entry("cantidadPreparada",0),Map.entry("cantidadPreparada",new BigDecimal("1.0001")),
                Map.entry("cantidadPreparada",new BigDecimal("1000000000000000")),Map.entry("partidas",1))) {
            var d = body("1"); d.put(cambio.getKey(),cambio.getValue()); assertRespuesta(400,"SOLICITUD_INVALIDA",post(d));
        }
        for (var partida : List.of(Map.of("idInsumo",f.insumo,"idPartidaInsumo",f.lote,"cantidadAplicada",1,"marcarApertura","true"),
                Map.of("idInsumo",f.insumo,"idPartidaInsumo",f.lote,"cantidadAplicada",new BigDecimal("0.0001")),
                Map.of("idInsumo",f.insumo,"idPartidaInsumo",f.lote,"cantidadAplicada",1))) {
            var d = body("1"); d.put("partidas",List.of(partida)); assertRespuesta(400,"SOLICITUD_INVALIDA",post(d));
        }
        String raw = json.writeValueAsString(body("1"));
        for (String invalido : List.of("{","null","[]",raw+" true",raw.replace("\"cantidadPreparada\":1","\"cantidadPreparada\":1,\"cantidadPreparada\":2")))
            assertRespuesta(400,"SOLICITUD_INVALIDA",request(puerto,"POST",ruta(f.pedido),token(),invalido,null));
        assertRespuesta(404,"PEDIDO_NO_ENCONTRADO",request(puerto,"POST",ruta(Long.MAX_VALUE),token(),raw,null));
        j.update("UPDATE \"PEDIDO\" SET \"estado\"='CANCELADO' WHERE \"idPedido\"=?",f.pedido);
        assertRespuesta(409,"PEDIDO_NO_PENDIENTE",post(body("1")));
        j.update("UPDATE \"PEDIDO\" SET \"estado\"='PENDIENTE' WHERE \"idPedido\"=?",f.pedido);
        j.update("UPDATE \"COSTO_INSUMO_HISTORICO\" SET \"vigenteHasta\"=? WHERE \"idInsumo\"=?",f.fecha.minusDays(1),f.insumo);
        assertRespuesta(409,"COSTO_HISTORICO_REQUERIDO",post(body("1")));
        j.update("UPDATE \"COSTO_INSUMO_HISTORICO\" SET \"vigenteHasta\"=NULL WHERE \"idInsumo\"=?",f.insumo);
        var antes = f.snapshot();
        j.execute("ALTER TABLE \"DETALLE_MOVIMIENTO_PARTIDA\" RENAME TO \"DETALLE_MOVIMIENTO_PARTIDA_TEST_OFFLINE\"");
        try { assertRespuesta(503,"PERSISTENCIA_NO_DISPONIBLE",post(body("1"))); }
        finally { j.execute("ALTER TABLE \"DETALLE_MOVIMIENTO_PARTIDA_TEST_OFFLINE\" RENAME TO \"DETALLE_MOVIMIENTO_PARTIDA\""); }
        assertEquals(antes,f.snapshot());
        var contexto = buscar(puerto); assertRespuesta(200,null,contexto);
        assertEquals(Set.of("idPedido","idCliente","fechaReparto","estado","canalOrigen"),json.readValue(contexto.body(),new tools.jackson.core.type.TypeReference<List<Map<String,Object>>>() {}).getFirst().keySet());
        assertRespuesta(201,null,post(body("9"))); assertRespuesta(403,"FUERA_DE_CONTEXTO",buscar(puerto));
    }
}
