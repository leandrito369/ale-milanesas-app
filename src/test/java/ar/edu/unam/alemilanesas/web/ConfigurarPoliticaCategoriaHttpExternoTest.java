package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.AleMilanesasApplication;
import ar.edu.unam.alemilanesas.abastecimiento.IngresoReventaFixture;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="ale.http.writes.categorias-uc57-enabled=true")
@Testcontainers
class ConfigurarPoliticaCategoriaHttpExternoTest {
    private static final String RUTA = "/api/v1/categorias-comerciales/politicas";
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    IngresoReventaFixture f;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() {
        // La política es global: limpiar exclusivamente el contenedor aislado de esta suite.
        j.update("DELETE FROM \"POLITICA_CATEGORIA_COMERCIAL\"");
        f=new IngresoReventaFixture(j,tx);
    }
    String admin() {return f.tokens.get("ADMINISTRADOR");}
    OffsetDateTime futuro(int dias) {return OffsetDateTime.now(ZoneOffset.ofHours(-3)).plusDays(dias).withNano(0);}
    Map<String,Object> ajuste(String tipo,String porcentaje) {
        return Map.of("tipoAjuste",tipo,"porcentajeAjuste",new BigDecimal(porcentaje));
    }
    Map<String,Object> body(OffsetDateTime desde,OffsetDateTime hasta) {
        var d=new HashMap<String,Object>();d.put("diasToleranciaMora",5);d.put("vigenteDesde",desde.toString());
        d.put("vigenteHasta",hasta==null?null:hasta.toString());
        d.put("ajusteCumplidor",ajuste("DESCUENTO","5.00"));d.put("ajusteEnMora",ajuste("RECARGO","10.00"));return d;
    }
    HttpResponse<String> texto(int puerto,String token,String body,String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+puerto+RUTA)).timeout(Duration.ofSeconds(20))
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
    int contar(String tabla) {return j.queryForObject("SELECT count(*) FROM \""+tabla+"\"",Integer.class);}
    @Test void postPoliticaValidaRetorna201ConDatosDelB3() throws Exception {
        var desde=futuro(2);var hasta=futuro(5);
        var r=post(admin(),body(desde,hasta));assertRespuesta(201,null,r);var d=json.readTree(r.body());
        assertEquals(5,d.size());assertEquals(5,d.get("diasToleranciaMora").asInt());assertTrue(d.get("politicaCerrada").isNull());
        assertTrue(OffsetDateTime.parse(d.get("vigenteDesde").asString()).isEqual(desde));
        assertTrue(OffsetDateTime.parse(d.get("vigenteHasta").asString()).isEqual(hasta));
        assertEquals(1,contar("POLITICA_CATEGORIA_COMERCIAL"));assertEquals(2,contar("AJUSTE_POLITICA_CATEGORIA"));
        long id=d.get("idPoliticaComercial").asLong();
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"POLITICA_CATEGORIA_COMERCIAL\" WHERE \"idPoliticaComercial\"=? AND \"idUsuarioRegistro\"=? AND \"diasToleranciaMora\"=5",Integer.class,id,f.base));
        assertEquals(List.of("CUMPLIDOR:DESCUENTO:5.00","EN_MORA:RECARGO:10.00"),j.queryForList("""
                SELECT "categoriaComercial" || ':' || "tipoAjuste" || ':' || "porcentajeAjuste"::text
                FROM "AJUSTE_POLITICA_CATEGORIA" WHERE "idPoliticaComercial"=? ORDER BY "categoriaComercial"
                """,String.class,id));
        var sin=body(futuro(6),null);sin.remove("vigenteHasta");sin.put("diasToleranciaMora",0);
        sin.put("ajusteCumplidor",ajuste("SIN_AJUSTE","0"));sin.put("ajusteEnMora",ajuste("SIN_AJUSTE","0"));
        assertRespuesta(201,null,post(admin(),sin));
    }
    @Test void postConPoliticaPreviaVigenteRetorna201YDevuelvePoliticaCerrada() throws Exception {
        j.update("""
                INSERT INTO "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial","diasToleranciaMora",
                    "vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                VALUES (?,7,clock_timestamp()-interval '1 day',clock_timestamp()-interval '2 days',?)
                """,f.base,f.base);
        j.update("""
                INSERT INTO "AJUSTE_POLITICA_CATEGORIA" VALUES
                (?,'CUMPLIDOR','DESCUENTO',1),(?,'EN_MORA','RECARGO',2)
                """,f.base,f.base);
        var desde=futuro(2);var r=post(admin(),body(desde,null));assertRespuesta(201,null,r);
        assertEquals(f.base,json.readTree(r.body()).get("politicaCerrada").asLong());
        assertTrue(j.queryForObject("SELECT \"vigenteHasta\" FROM \"POLITICA_CATEGORIA_COMERCIAL\" WHERE \"idPoliticaComercial\"=?",OffsetDateTime.class,f.base).isEqual(desde));
        assertEquals(2,contar("POLITICA_CATEGORIA_COMERCIAL"));assertEquals(4,contar("AJUSTE_POLITICA_CATEGORIA"));
        assertEquals(new BigDecimal("1.00"),j.queryForObject("SELECT \"porcentajeAjuste\" FROM \"AJUSTE_POLITICA_CATEGORIA\" WHERE \"idPoliticaComercial\"=? AND \"categoriaComercial\"='CUMPLIDOR'",BigDecimal.class,f.base));
    }
    @Test void postRechazaDiasToleranciaNegativos() throws Exception {
        for(Object dias:List.of(-1,"5",false,1.5,2147483648L)) {
            var d=body(futuro(2),null);d.put("diasToleranciaMora",dias);assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),d));
        }
        for(String campo:List.of("diasToleranciaMora","vigenteDesde","ajusteCumplidor","ajusteEnMora")) {
            var d=body(futuro(2),null);d.remove(campo);assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),d));
        }
        var extra=body(futuro(2),null);extra.put("intruso",1);assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),extra));
        String valido=json.writeValueAsString(body(futuro(2),null));
        for(String raw:List.of("{","null","[]",valido+" true",valido.replace("\"diasToleranciaMora\":5","\"diasToleranciaMora\":5,\"diasToleranciaMora\":6")))
            assertRespuesta(400,"SOLICITUD_INVALIDA",texto(port,admin(),raw,null));
        assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),body(futuro(-1),null)));
        assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),body(futuro(3),futuro(2))));
        var desde=futuro(2);assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),body(desde,desde)));
        for(Object fecha:List.of(123,"2026-02-30T00:00:00-03:00","2026-11-10T00:00:00","\0")) {
            var d=body(futuro(2),null);d.put("vigenteDesde",fecha);assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),d));
        }
        assertEquals(0,contar("POLITICA_CATEGORIA_COMERCIAL"));assertEquals(0,contar("AJUSTE_POLITICA_CATEGORIA"));
    }
    @Test void postRechazaTipoAjusteInvalido() throws Exception {
        for(String campo:List.of("ajusteCumplidor","ajusteEnMora")) {
            for(Object valor:List.of(ajuste("INVENTADO","5"),Map.of("tipoAjuste",1,"porcentajeAjuste",5),
                    Map.of("tipoAjuste","SIN_AJUSTE","porcentajeAjuste",0,"extra",1),Map.of("tipoAjuste","SIN_AJUSTE"),
                    Map.of("porcentajeAjuste",5),"ajuste")) {
                var d=body(futuro(2),null);d.put(campo,valor);assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),d));
            }
        }
        var d=body(futuro(2),null);d.put("ajusteCumplidor",ajuste("RECARGO","5"));assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),d));
        d=body(futuro(2),null);d.put("ajusteEnMora",ajuste("DESCUENTO","5"));assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),d));
    }
    @Test void postRechazaPorcentajeFueraDeRango() throws Exception {
        for(String campo:List.of("ajusteCumplidor","ajusteEnMora")) {
            String tipo=campo.equals("ajusteCumplidor")?"DESCUENTO":"RECARGO";
            for(var valor:List.of(ajuste(tipo,"-1"),ajuste(tipo,"100.01"),ajuste(tipo,"1.001"),ajuste(tipo,"0"),ajuste("SIN_AJUSTE","1"),
                    Map.of("tipoAjuste",tipo,"porcentajeAjuste","5"))) {
                var d=body(futuro(2),null);d.put(campo,valor);assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),d));
            }
        }
        var d=body(futuro(2),null);d.put("ajusteCumplidor",ajuste("DESCUENTO","100"));d.put("ajusteEnMora",ajuste("RECARGO","100"));
        assertRespuesta(201,null,post(admin(),d)); // Límite 100 incluido por contrato/V1/B3.
    }
    @Test void postRechazaSolape() throws Exception {
        var primero=post(admin(),body(futuro(4),null));assertRespuesta(201,null,primero);
        assertRespuesta(409,"SOLAPE_VIGENCIA",post(admin(),body(futuro(3),null)));
        assertRespuesta(409,"VIGENCIA_FUTURA_NO_TRUNCADA",post(admin(),body(futuro(5),null)));
        assertEquals(1,contar("POLITICA_CATEGORIA_COMERCIAL"));assertEquals(2,contar("AJUSTE_POLITICA_CATEGORIA"));
        assertNull(j.queryForObject("SELECT \"vigenteHasta\" FROM \"POLITICA_CATEGORIA_COMERCIAL\" WHERE \"idPoliticaComercial\"=?",OffsetDateTime.class,json.readTree(primero.body()).get("idPoliticaComercial").asLong()));
    }
    @Test void postSinTokenRetorna401() throws Exception {
        var body=json.writeValueAsString(body(futuro(2),null));
        assertRespuesta(401,"SESION_INVALIDA",texto(port,null,body,null));
        assertRespuesta(401,"SESION_INVALIDA",texto(port,null,body,"JSESSIONID="+admin()));
        j.update("UPDATE \"SESION_USUARIO\" SET \"fechaHoraRevocacion\"=clock_timestamp() WHERE \"idSesionUsuario\"=?",f.sesiones.get("ADMINISTRADOR"));
        assertRespuesta(401,"SESION_INVALIDA",texto(port,admin(),body,null));
    }
    @Test void postConRolVendedorRetorna403() throws Exception {
        for(String rol:List.of("VENDEDOR","RESPONSABLE_PRODUCCION","REPARTIDOR","PROVEEDOR"))
            assertRespuesta(403,"OPERACION_PROHIBIDA",post(f.tokens.get(rol),body(futuro(2),null)));
    }
    @Test void postConGateDesactivadoRetorna403() throws Exception {
        try(var ctx=new SpringApplicationBuilder(AleMilanesasApplication.class).run("--server.port=0",
                "--spring.datasource.url="+db.getJdbcUrl(),"--spring.datasource.username="+db.getUsername(),
                "--spring.datasource.password="+db.getPassword(),"--ale.http.writes.categorias-uc57-enabled=false")) {
            assertTrue(ctx.getBeansOfType(ConfigurarPoliticaCategoriaHttpController.class).isEmpty());
            int puerto=((WebServerApplicationContext)ctx).getWebServer().getPort();
            assertRespuesta(403,"OPERACION_PROHIBIDA",texto(puerto,admin(),json.writeValueAsString(body(futuro(2),null)),null));
        }
        try(var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("uc57-prod",
                    Map.of("ale.http.writes.categorias-uc57-enabled","true")));
            ctx.register(ConfigurarPoliticaCategoriaHttpController.class);ctx.refresh();
            assertTrue(ctx.getBeansOfType(ConfigurarPoliticaCategoriaHttpController.class).isEmpty());
        }
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        assertRespuesta(201,null,post(admin(),body(futuro(2),futuro(3))));
        var invalida=body(futuro(4),null);invalida.put("diasToleranciaMora",-1);assertRespuesta(400,"SOLICITUD_INVALIDA",post(admin(),invalida));
        assertRespuesta(409,"SOLAPE_VIGENCIA",post(admin(),body(futuro(1),null)));
        assertRespuesta(401,"SESION_INVALIDA",post(null,body(futuro(4),null)));
        assertRespuesta(403,"OPERACION_PROHIBIDA",post(f.tokens.get("VENDEDOR"),body(futuro(4),null)));
        j.execute("""
                CREATE FUNCTION uc57_test_fallo() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                IF NEW."categoriaComercial"='EN_MORA' THEN RAISE EXCEPTION 'Fallo sintetico segundo ajuste'; END IF;
                RETURN NEW; END $$
                """);
        j.execute("CREATE TRIGGER uc57_test_fallo BEFORE INSERT ON \"AJUSTE_POLITICA_CATEGORIA\" FOR EACH ROW EXECUTE FUNCTION uc57_test_fallo()");
        try {
            assertRespuesta(503,"PERSISTENCIA_NO_DISPONIBLE",post(admin(),body(futuro(4),futuro(5))));
            assertEquals(1,contar("POLITICA_CATEGORIA_COMERCIAL"));assertEquals(2,contar("AJUSTE_POLITICA_CATEGORIA"));
        } finally {
            j.execute("DROP TRIGGER uc57_test_fallo ON \"AJUSTE_POLITICA_CATEGORIA\"");j.execute("DROP FUNCTION uc57_test_fallo()");
        }
        var rutas=mappings.getHandlerMethods().entrySet().stream()
                .filter(e->e.getKey().getPatternValues().stream().anyMatch(p->p.startsWith("/api/v1/categorias-comerciales"))).toList();
        assertEquals(1,rutas.size());assertEquals(Set.of(RUTA),rutas.getFirst().getKey().getPatternValues());
        assertEquals(Set.of(RequestMethod.POST),rutas.getFirst().getKey().getMethodsCondition().getMethods());
        assertEquals(ConfigurarPoliticaCategoriaHttpController.class,rutas.getFirst().getValue().getBeanType());
        assertFalse(mappings.getHandlerMethods().values().stream().anyMatch(h->Set.of("evaluarYActualizarCategoriaComercial",
                "aplicarAjusteComercialSegunCategoria").contains(h.getMethod().getName())));
    }
}
