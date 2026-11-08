package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.AleMilanesasApplication;
import ar.edu.unam.alemilanesas.abastecimiento.IngresoStockFixture;
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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"ale.http.writes.insumos-uc50-enabled=true","ale.http.writes.insumos-uc51-enabled=true","ale.http.writes.insumos-uc52-enabled=true"})
@Testcontainers
class RegistroInsumoHttpExternoTest extends InsumosHttpSoporte {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Test void postValidoRetorna201() throws Exception {
        var r=enviar("POST",RAIZ,alta());assertRespuesta(201,null,r);var d=json.readTree(r.body());
        assertEquals(6,d.size());assertEquals(0,d.get("stockActual").asDouble());assertTrue(d.get("activo").asBoolean());
        assertTrue(d.get("huboCambio").asBoolean());assertEquals("KG",d.get("codigoUnidadMedida").asString());
        long id=d.get("idInsumo").asLong();
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"INSUMO\" WHERE \"idInsumo\"=? AND NOT \"automatizacionCompraActiva\" AND \"umbralMinimo\"=5 AND \"nivelObjetivoReposicion\"=20",Integer.class,id));
        for(String tabla:List.of("MOVIMIENTO_INSUMO","COSTO_INSUMO_HISTORICO","PARTIDA_INSUMO"))
            assertEquals(0,j.queryForObject("SELECT count(*) FROM \""+tabla+"\" WHERE \"idInsumo\"=?",Integer.class,id));
        assertRespuesta(409,"INSUMO_DUPLICADO",enviar("POST",RAIZ,alta()));
        for(var entrada:List.<Map.Entry<String,Object>>of(Map.entry("umbralMinimo",-1),Map.entry("umbralMinimo","5"),
                Map.entry("nivelObjetivoReposicion",5),Map.entry("umbralMinimo",new BigDecimal("1.0001")),
                Map.entry("nivelObjetivoReposicion",new BigDecimal("1000000000000000")),Map.entry("nombre","\0"),Map.entry("codigoUnidadMedida","\0"))) {
            var b=alta();b.put(entrada.getKey(),entrada.getValue());assertRespuesta(400,"SOLICITUD_INVALIDA",enviar("POST",RAIZ,b));
        }
        for(String campo:alta().keySet()) {
            var b=alta();b.remove(campo);assertRespuesta(400,"SOLICITUD_INVALIDA",enviar("POST",RAIZ,b));
        }
        String valido=json.writeValueAsString(alta());
        for(String raw:List.of("{","null","[]",valido+" true",valido.replace("\"umbralMinimo\":5","\"umbralMinimo\":5,\"umbralMinimo\":6")))
            assertRespuesta(400,"SOLICITUD_INVALIDA",request(port,"POST",RAIZ,admin(),raw,null));
    }
    @Test void postRechazaStockActualEnBody() throws Exception {
        for(String campo:List.of("stockActual","stockInicial")) {
            var b=alta();b.put(campo,0);assertRespuesta(400,"SOLICITUD_INVALIDA",enviar("POST",RAIZ,b));
        }
    }
    @Test void postRechazaCostoInicialEnBody() throws Exception {
        var b=alta();b.put("costoInicial",0);assertRespuesta(400,"SOLICITUD_INVALIDA",enviar("POST",RAIZ,b));
        j.execute("ALTER TABLE \"INSUMO\" RENAME TO \"INSUMO_TEST_OFFLINE\"");
        try {assertRespuesta(503,"PERSISTENCIA_NO_DISPONIBLE",enviar("POST",RAIZ,alta()));}
        finally {j.execute("ALTER TABLE \"INSUMO_TEST_OFFLINE\" RENAME TO \"INSUMO\"");}
    }
    @Test void postRechazaAutomatizacionEnBody() throws Exception {
        var b=alta();b.put("automatizacionCompraActiva",false);assertRespuesta(400,"SOLICITUD_INVALIDA",enviar("POST",RAIZ,b));
    }
    @Test void postSinTokenRetorna401() throws Exception {assertSinToken("POST",RAIZ,alta());}
    @Test void postConRolVendedorRetorna403() throws Exception {assertRoles("POST",RAIZ,alta());}
    @Test void postConGateDesactivadoRetorna403() throws Exception {
        try(var ctx=contexto(Set.of("51","52"))) {
            int p=puerto(ctx);assertTrue(ctx.getBeansOfType(RegistroInsumoHttpController.class).isEmpty());
            assertRespuesta(403,"OPERACION_PROHIBIDA",request(p,"POST",RAIZ,admin(),json.writeValueAsString(alta()),null));
            assertRespuesta(200,null,request(p,"PUT",ruta(),admin(),json.writeValueAsString(modificar("KG")),null));
            assertRespuesta(200,null,request(p,"PATCH",ruta()+"/estado",admin(),"{\"activo\":false}",null));
        }
        try(var ctx=contexto(Set.of())) {
            int p=puerto(ctx);
            assertRespuesta(403,"OPERACION_PROHIBIDA",request(p,"POST",RAIZ,admin(),json.writeValueAsString(alta()),null));
            assertRespuesta(403,"OPERACION_PROHIBIDA",request(p,"PUT",ruta(),admin(),json.writeValueAsString(modificar("KG")),null));
            assertRespuesta(403,"OPERACION_PROHIBIDA",request(p,"PATCH",ruta()+"/estado",admin(),"{\"activo\":true}",null));
            assertRespuesta(200,null,request(p,"GET",RAIZ,admin(),null,null));
            assertRespuesta(200,null,request(p,"GET","/api/v1/unidades",f.tokens.get("RESPONSABLE_PRODUCCION"),null,null));
        }
        try(var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("insumos-prod",
                    Map.of("ale.http.writes.insumos-uc50-enabled","true","ale.http.writes.insumos-uc51-enabled","true","ale.http.writes.insumos-uc52-enabled","true")));
            ctx.register(RegistroInsumoHttpController.class,ModificacionInsumoHttpController.class,EstadoInsumoHttpController.class);ctx.refresh();
            assertTrue(ctx.getBeansOfType(RegistroInsumoHttpController.class).isEmpty());
            assertTrue(ctx.getBeansOfType(ModificacionInsumoHttpController.class).isEmpty());assertTrue(ctx.getBeansOfType(EstadoInsumoHttpController.class).isEmpty());
        }
    }
}

/** Cliente real y datos sintéticos compartidos; no se agregan endpoints de consulta. */
abstract class InsumosHttpSoporte {
    static final String RAIZ="/api/v1/insumos";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Value("${spring.datasource.url}") String dbUrl;
    @Value("${spring.datasource.username}") String dbUser;
    @Value("${spring.datasource.password}") String dbPassword;
    IngresoStockFixture f;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() {
        f=new IngresoStockFixture(j,tx);
        j.update("INSERT INTO \"UNIDAD_MEDIDA\" VALUES ('G','Gramo') ON CONFLICT DO NOTHING");
    }
    String admin() {return f.tokens.get("ADMINISTRADOR");}
    String ruta() {return RAIZ+"/"+(f.base+1);}
    Map<String,Object> alta() {
        return new HashMap<>(Map.of("nombre","Insumo HTTP "+f.base,"codigoUnidadMedida","KG","umbralMinimo",5,"nivelObjetivoReposicion",20));
    }
    Map<String,Object> modificar(String unidad) {return new HashMap<>(Map.of("nombre","Renombrado "+f.base,"codigoUnidadMedida",unidad));}
    HttpResponse<String> request(int p,String metodo,String ruta,String token,String body,String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+p+ruta)).timeout(Duration.ofSeconds(20));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(cookie!=null)b.header("Cookie",cookie);
        if(body!=null)b.header("Content-Type","application/json");
        return http.send(b.method(metodo,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> enviar(String metodo,String ruta,Map<String,Object> body) throws Exception {
        return request(port,metodo,ruta,admin(),json.writeValueAsString(body),null);
    }
    void assertRespuesta(int status,String codigo,HttpResponse<String> r) {
        assertEquals(status,r.statusCode(),r.body());assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if(codigo!=null)assertEquals(codigo,json.readTree(r.body()).get("codigo").asString());
    }
    void assertSinToken(String metodo,String ruta,Map<String,Object> datos) throws Exception {
        String body=json.writeValueAsString(datos);
        assertRespuesta(401,"SESION_INVALIDA",request(port,metodo,ruta,null,body,null));
        assertRespuesta(401,"SESION_INVALIDA",request(port,metodo,ruta,null,body,"JSESSIONID="+admin()));
        j.update("UPDATE \"SESION_USUARIO\" SET \"fechaHoraRevocacion\"=clock_timestamp() WHERE \"idSesionUsuario\"=?",f.sesiones.get("ADMINISTRADOR"));
        assertRespuesta(401,"SESION_INVALIDA",request(port,metodo,ruta,admin(),body,null));
    }
    void assertRoles(String metodo,String ruta,Map<String,Object> body) throws Exception {
        for(String rol:List.of("VENDEDOR","RESPONSABLE_PRODUCCION","REPARTIDOR","PROVEEDOR"))
            assertRespuesta(403,"OPERACION_PROHIBIDA",request(port,metodo,ruta,f.tokens.get(rol),json.writeValueAsString(body),null));
    }
    void partidaAgotada() {
        j.update("""
                INSERT INTO "PARTIDA_INSUMO" ("idPartidaInsumo","idInsumo","fechaHoraRegistro",
                    "cantidadInicial","cantidadDisponible") VALUES (?,?,now()-interval '1 day',1,0)
                """,f.base+1,f.base+1);
    }
    ConfigurableApplicationContext contexto(Set<String> habilitados) {
        return new SpringApplicationBuilder(AleMilanesasApplication.class).run("--server.port=0",
                "--spring.datasource.url="+dbUrl,"--spring.datasource.username="+dbUser,
                "--spring.datasource.password="+dbPassword,
                "--ale.http.writes.insumos-uc50-enabled="+habilitados.contains("50"),
                "--ale.http.writes.insumos-uc51-enabled="+habilitados.contains("51"),
                "--ale.http.writes.insumos-uc52-enabled="+habilitados.contains("52"));
    }
    int puerto(ConfigurableApplicationContext ctx) {return ((WebServerApplicationContext)ctx).getWebServer().getPort();}
}
