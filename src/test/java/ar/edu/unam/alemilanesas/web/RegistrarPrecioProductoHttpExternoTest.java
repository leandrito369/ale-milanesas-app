package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.AleMilanesasApplication;
import ar.edu.unam.alemilanesas.abastecimiento.IngresoReventaFixture;
import ar.edu.unam.alemilanesas.maestros.ConsultarHistorialPreciosService;
import java.math.BigDecimal;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="ale.http.writes.precios-uc19-enabled=true")
@Testcontainers
class RegistrarPrecioProductoHttpExternoTest extends PreciosHttpSoporte {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Test void postPrimerPrecioRetorna201ConIdsCorretos() throws Exception {
        var desde=futuro(2);var hasta=futuro(5);
        var r=post(admin(),body("1500.1234",desde,hasta));assertRespuesta(201,null,r);
        var d=json.readTree(r.body());assertEquals(5,d.size());assertTrue(d.get("idPrecioCerrado").isNull());
        assertTrue(OffsetDateTime.parse(d.get("vigenteDesde").asString()).isEqual(desde));
        assertTrue(OffsetDateTime.parse(d.get("vigenteHasta").asString()).isEqual(hasta));
        assertEquals(1,j.queryForObject("""
                SELECT count(*) FROM "PRECIO_PRODUCTO" p JOIN "AUDITORIA_PRECIO" a USING ("idPrecio")
                WHERE p."idPrecio"=? AND a."idAuditoriaPrecio"=? AND p."idProducto"=?
                AND p."precioBase"=1500.1234 AND a."precioAnterior" IS NULL
                AND a."precioNuevo"=1500.1234 AND a."tipoEvento"='MODIFICACION_PROGRAMADA'
                AND a."idUsuario"=?
                """,Integer.class,d.get("idPrecio").asLong(),d.get("idAuditoriaPrecio").asLong(),f.base,f.base));
        var exacto=post(admin(),body("99999999999999.9999",futuro(6),null));assertRespuesta(201,null,exacto);
        assertEquals(new BigDecimal("99999999999999.9999"),j.queryForObject("SELECT \"precioBase\" FROM \"PRECIO_PRODUCTO\" WHERE \"idPrecio\"=?",BigDecimal.class,json.readTree(exacto.body()).get("idPrecio").asLong()));
    }
    @Test void postSegundoPrecioRetorna201YCierraVigenciaPrevia() throws Exception {
        var primero=post(admin(),body("100.0000",futuro(2),null));assertRespuesta(201,null,primero);
        long anterior=json.readTree(primero.body()).get("idPrecio").asLong();
        var desde=futuro(4);var segundo=post(admin(),body("200.0000",desde,null));assertRespuesta(201,null,segundo);
        assertEquals(anterior,json.readTree(segundo.body()).get("idPrecioCerrado").asLong());
        assertTrue(j.queryForObject("SELECT \"vigenteHasta\" FROM \"PRECIO_PRODUCTO\" WHERE \"idPrecio\"=?",OffsetDateTime.class,anterior).isEqual(desde));
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"AUDITORIA_PRECIO\" WHERE \"idPrecio\"=? AND \"tipoEvento\"='CIERRE_VIGENCIA' AND \"vigenteHastaNuevo\"=?",Integer.class,anterior,desde));
    }
    @Test void postRechazaPrecioNoPositivo() throws Exception {
        for(String precio:List.of("0","-1","1.00001","100000000000000","1.0000000000000001"))
            assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),body(precio,futuro(2),null)));
        for(var cambio:List.<Map.Entry<String,Object>>of(Map.entry("nuevoPrecioBase","15"),Map.entry("extra",1),
                Map.entry("vigenteDesde",123),Map.entry("vigenteDesde","sin-fecha"))) {
            var datos=body("15",futuro(2),null);datos.put(cambio.getKey(),cambio.getValue());
            assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),datos));
        }
        String valido=json.writeValueAsString(body("15",futuro(2),null));
        for(String raw:List.of("{","null","[]",valido+" true",valido.replace("\"nuevoPrecioBase\":15","\"nuevoPrecioBase\":15,\"nuevoPrecioBase\":16")))
            assertRespuesta(400,"SOLICITUD_INVALIDA",request(port,"POST",ruta(),admin(),raw,null));
        var sinPrecio=body("15",futuro(2),null);sinPrecio.remove("nuevoPrecioBase");assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),sinPrecio));
        assertEquals(0,j.queryForObject("SELECT count(*) FROM \"PRECIO_PRODUCTO\" WHERE \"idProducto\"=?",Integer.class,f.base));
        j.execute("ALTER TABLE \"PRECIO_PRODUCTO\" RENAME TO \"PRECIO_PRODUCTO_TEST_OFFLINE\"");
        try {assertRespuesta(503,"PERSISTENCIA_NO_DISPONIBLE",post(admin(),body("15",futuro(2),null)));}
        finally {j.execute("ALTER TABLE \"PRECIO_PRODUCTO_TEST_OFFLINE\" RENAME TO \"PRECIO_PRODUCTO\"");}
    }
    @Test void postRechazaVigenciaHastaMenorQueDesde() throws Exception {
        assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),body("15",futuro(3),futuro(2))));
        var desde=futuro(2);assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),body("15",desde,desde)));
        assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),body("15",desde.plusNanos(100),desde.plusNanos(800))));
        assertRespuesta(400,"VIGENCIA_RETROACTIVA",post(admin(),body("15",futuro(-1),null)));
        var sinDesde=body("15",desde,null);sinDesde.remove("vigenteDesde");assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),sinDesde));
        for(String valor:List.of("2026-02-30T12:00:00-03:00","2026-11-10","2026-11-10T12:00:00")) {
            var d=body("15",desde,null);d.put("vigenteDesde",valor);assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),d));
        }
        assertRespuesta(400,"SOLICITUD_INVALIDA",request(port,"POST","/api/v1/productos/no-es-id/precios",admin(),json.writeValueAsString(body("15",desde,null)),null));
    }
    @Test void postRechazaSolape() throws Exception {
        var creado=post(admin(),body("100",futuro(4),null));assertRespuesta(201,null,creado);
        assertRespuesta(409,"SOLAPE_VIGENCIA",post(admin(),body("200",futuro(3),null)));
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"PRECIO_PRODUCTO\" WHERE \"idProducto\"=?",Integer.class,f.base));
        assertNull(j.queryForObject("SELECT \"vigenteHasta\" FROM \"PRECIO_PRODUCTO\" WHERE \"idPrecio\"=?",OffsetDateTime.class,json.readTree(creado.body()).get("idPrecio").asLong()));
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"AUDITORIA_PRECIO\" a JOIN \"PRECIO_PRODUCTO\" p USING (\"idPrecio\") WHERE p.\"idProducto\"=?",Integer.class,f.base));
    }
    @Test void postSinTokenRetorna401() throws Exception {
        var body=json.writeValueAsString(body("15",futuro(2),null));
        assertRespuesta(401,"SESION_INVALIDA",request(port,"POST",ruta(),null,body,null));
        assertRespuesta(401,"SESION_INVALIDA",request(port,"POST",ruta(),null,body,"JSESSIONID="+admin()));
        j.update("UPDATE \"SESION_USUARIO\" SET \"fechaHoraRevocacion\"=clock_timestamp() WHERE \"idSesionUsuario\"=?",f.sesiones.get("ADMINISTRADOR"));
        assertRespuesta(401,"SESION_INVALIDA",request(port,"POST",ruta(),admin(),body,null));
    }
    @Test void postConRolVendedorRetorna403() throws Exception {
        for(String rol:List.of("VENDEDOR","RESPONSABLE_PRODUCCION","REPARTIDOR","PROVEEDOR"))
            assertRespuesta(403,"OPERACION_PROHIBIDA",post(f.tokens.get(rol),body("15",futuro(2),null)));
    }
    @Test void postConGateDesactivadoRetorna403() throws Exception {
        try(var ctx=new SpringApplicationBuilder(AleMilanesasApplication.class).run("--server.port=0",
                "--spring.datasource.url="+db.getJdbcUrl(),"--spring.datasource.username="+db.getUsername(),
                "--spring.datasource.password="+db.getPassword(),"--ale.http.writes.precios-uc19-enabled=false")) {
            assertTrue(ctx.getBeansOfType(RegistrarPrecioProductoHttpController.class).isEmpty());
            assertEquals(1,ctx.getBeansOfType(ConsultarHistorialPreciosHttpController.class).size());
            int puerto=((WebServerApplicationContext)ctx).getWebServer().getPort();
            assertRespuesta(403,"OPERACION_PROHIBIDA",request(puerto,"POST",ruta(),admin(),json.writeValueAsString(body("15",futuro(2),null)),null));
            assertRespuesta(200,null,request(puerto,"GET",ruta(),admin(),null,null));
        }
        try(var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("uc19-prod",
                    Map.of("ale.http.writes.precios-uc19-enabled","true")));
            ctx.registerBean(ConsultarHistorialPreciosService.class,()->org.mockito.Mockito.mock(ConsultarHistorialPreciosService.class));
            ctx.register(RegistrarPrecioProductoHttpController.class,ConsultarHistorialPreciosHttpController.class);ctx.refresh();
            assertTrue(ctx.getBeansOfType(RegistrarPrecioProductoHttpController.class).isEmpty());
            assertEquals(1,ctx.getBeansOfType(ConsultarHistorialPreciosHttpController.class).size());
        }
    }
}

/** Soporte HTTP real compartido por ambas suites; reutiliza fixture sin modificarla. */
abstract class PreciosHttpSoporte {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    IngresoReventaFixture f;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() {f=new IngresoReventaFixture(j,tx);}
    String admin() {return f.tokens.get("ADMINISTRADOR");}
    String ruta() {return "/api/v1/productos/"+f.base+"/precios";}
    OffsetDateTime futuro(int dias) {return OffsetDateTime.now(ZoneOffset.ofHours(-3)).plusDays(dias).withNano(0);}
    Map<String,Object> body(String precio,OffsetDateTime desde,OffsetDateTime hasta) {
        var d=new HashMap<String,Object>();d.put("nuevoPrecioBase",new BigDecimal(precio));d.put("vigenteDesde",desde.toString());
        if(hasta!=null)d.put("vigenteHasta",hasta.toString());return d;
    }
    HttpResponse<String> request(int puerto,String metodo,String ruta,String token,String body,String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+puerto+ruta)).timeout(Duration.ofSeconds(20));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(cookie!=null)b.header("Cookie",cookie);
        if(body!=null)b.header("Content-Type","application/json");
        return http.send(b.method(metodo,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String token,Map<String,Object> body) throws Exception {
        return request(port,"POST",ruta(),token,json.writeValueAsString(body),null);
    }
    HttpResponse<String> get(String token,String query) throws Exception {return request(port,"GET",ruta()+query,token,null,null);}
    void assertRespuesta(int estado,String codigo,HttpResponse<String> r) {
        assertEquals(estado,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if(codigo!=null)assertEquals(codigo,json.readTree(r.body()).get("codigo").asString());
    }
}
