package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.produccioninventario.StockFixture;
import ar.edu.unam.alemilanesas.produccioninventario.ConsultarStockService.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConsultarStockHttpExternoTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    StockFixture f;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() {f=tx.execute(s->{StockFixture.limpiar(j);return new StockFixture(j,tx);});}
    HttpResponse<String> get(String query,String token,String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/stock"+query)).timeout(Duration.ofSeconds(20));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(cookie!=null)b.header("Cookie",cookie);
        return http.send(b.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String query) throws Exception {return get(query,f.actores.tokens.get("RESPONSABLE_PRODUCCION"),null);}
    ResultadoStock resultado(HttpResponse<String> r) throws Exception {return json.readValue(r.body(),ResultadoStock.class);}
    void assertRespuesta(int estado,String codigo,HttpResponse<String> r) {
        assertEquals(estado,r.statusCode(),r.body());assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if(codigo!=null)assertEquals(codigo,json.readTree(r.body()).get("codigo").asString());
    }
    @Test void getInsumosRetorna200() throws Exception {
        var r=get("?tipoInventario=INSUMO");assertRespuesta(200,null,r);var d=resultado(r);
        assertEquals(3,d.insumos().size());assertTrue(d.reventa().isEmpty());var a=d.insumos().getFirst();
        assertEquals(f.base,a.idInsumo());assertEquals("KG",a.codigoUnidadMedida());
        assertEquals(0,new BigDecimal("10").compareTo(a.stockActual()));assertEquals(0,new BigDecimal("8").compareTo(a.stockUtilizable()));
        assertEquals(3,a.partidas().size());assertEquals(1,a.partidas().stream().filter(PartidaResumen::vencida).count());
        var b=d.insumos().get(1);assertEquals(0,b.stockActual().compareTo(BigDecimal.ZERO));assertTrue(b.partidas().isEmpty());
        assertTrue(b.tieneEpisodioAbierto());assertFalse(a.tieneEpisodioAbierto());
        var todos=get("");assertRespuesta(200,null,todos);assertEquals(3,resultado(todos).reventa().size());
        var id=get("?tipoInventario=INSUMO&idElemento="+f.base);assertRespuesta(200,null,id);assertEquals(1,resultado(id).insumos().size());
        var vacio=get("?idElemento="+Long.MAX_VALUE);assertRespuesta(200,null,vacio);
        assertTrue(resultado(vacio).insumos().isEmpty());assertTrue(resultado(vacio).reventa().isEmpty());
    }
    @Test void getReventaRetorna200() throws Exception {
        var r=get("?tipoInventario=REVENTA");assertRespuesta(200,null,r);var d=resultado(r);
        assertTrue(d.insumos().isEmpty());assertEquals(3,d.reventa().size());
        assertEquals(0,new BigDecimal("10").compareTo(d.reventa().getFirst().stockActualReventa()));
        assertEquals(0,d.reventa().get(1).stockActualReventa().compareTo(BigDecimal.ZERO));assertNull(d.reventa().getLast().stockActualReventa());
        for(var nodo:json.readTree(r.body()).get("reventa")) {
            assertEquals(4,nodo.size());assertTrue(nodo.has("idProducto"));assertTrue(nodo.has("nombre"));
            assertTrue(nodo.has("codigoUnidadVenta"));assertTrue(nodo.has("stockActualReventa"));
        }
    }
    @Test void getSoloBajoUmbralFiltra() throws Exception {
        var r=get("?tipoInventario=INSUMO&soloBajoUmbral=true");assertRespuesta(200,null,r);
        assertEquals(List.of(f.base+1),resultado(r).insumos().stream().map(StockInsumo::idInsumo).toList());
        var ambos=get("?soloBajoUmbral=true");assertRespuesta(200,null,ambos);assertEquals(1,resultado(ambos).insumos().size());
        assertEquals(3,resultado(ambos).reventa().size());
        var sin=get("?tipoInventario=INSUMO&soloBajoUmbral=false");assertRespuesta(200,null,sin);assertEquals(3,resultado(sin).insumos().size());
    }
    @Test void getParametroInvalidoRetorna400() throws Exception {
        for(String q:List.of("?tipoInventario=OTRO","?idElemento=0","?idElemento=-1","?idElemento=1.5","?idElemento=9223372036854775808",
                "?soloBajoUmbral=yes","?soloBajoUmbral=TRUE","?tipoInventario=","?idElemento=","?soloBajoUmbral=",
                "?tipoInventario=INSUMO&tipoInventario=REVENTA","?soloBajoUmbral=false&soloBajoUmbral=false"))
            assertRespuesta(400,"SOLICITUD_INVALIDA",get(q));
        for(String extra:List.of("activo","costo","idUsuario","rol","limit"))
            assertRespuesta(400,"SOLICITUD_INVALIDA",get("?"+extra+"=1"));
        assertRespuesta(400,"SOLO_BAJO_UMBRAL_NO_APLICA_REVENTA",get("?tipoInventario=REVENTA&soloBajoUmbral=true"));
        assertRespuesta(200,null,get("?tipoInventario=REVENTA&soloBajoUmbral=false"));
    }
    @Test void getSinTokenRetorna401() throws Exception {
        assertRespuesta(401,"SESION_INVALIDA",get("",null,null));
        assertRespuesta(401,"SESION_INVALIDA",get("",null,"JSESSIONID="+f.actores.tokens.get("RESPONSABLE_PRODUCCION")));
    }
    @Test void getConRolAdminRetorna403() throws Exception {
        for(String rol:List.of("ADMINISTRADOR","VENDEDOR","REPARTIDOR","PROVEEDOR"))
            assertRespuesta(403,"OPERACION_PROHIBIDA",get("",f.actores.tokens.get(rol),null));
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        assertRespuesta(200,null,get(""));assertRespuesta(400,"SOLICITUD_INVALIDA",get("?extra=1"));
        assertRespuesta(401,"SESION_INVALIDA",get("",null,null));
        assertRespuesta(403,"OPERACION_PROHIBIDA",get("",f.actores.tokens.get("ADMINISTRADOR"),null));
        j.execute("ALTER TABLE \"PARTIDA_INSUMO\" RENAME TO \"PARTIDA_INSUMO_NO_DISPONIBLE_TEST\"");
        try {assertRespuesta(503,"PERSISTENCIA_NO_DISPONIBLE",get(""));}
        finally {j.execute("ALTER TABLE \"PARTIDA_INSUMO_NO_DISPONIBLE_TEST\" RENAME TO \"PARTIDA_INSUMO\"");}
    }
}
