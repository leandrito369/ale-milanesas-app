package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.ventas.*;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BuscarPedidoHttpExternoTest {
    private static final Set<String> COMUNES=Set.of("idPedido","idCliente","fechaReparto","estado","canalOrigen");
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired AsignarRepartoService reparto;
    @Autowired ObjectMapper json;
    BusquedaPedidoFixture f;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void fixture() {f=tx.execute(s->new BusquedaPedidoFixture(j,pedidos,reparto));}
    HttpResponse<String> get(String query,String token,String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/pedidos"+query)).timeout(Duration.ofSeconds(20));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(cookie!=null)b.header("Cookie",cookie);
        return http.send(b.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String query,String rol) throws Exception {return get(query,f.token(rol),null);}
    List<Map<String,Object>> contenido(HttpResponse<String> r) throws Exception {
        return json.readValue(r.body(),new TypeReference<List<Map<String,Object>>>() {});
    }
    List<Long> ids(List<Map<String,Object>> lista) {return lista.stream().map(x->((Number)x.get("idPedido")).longValue()).toList();}
    void assertRespuesta(int estado,String codigo,HttpResponse<String> r) {
        assertEquals(estado,r.statusCode(),r.body());assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if(codigo!=null)assertEquals(codigo,json.readTree(r.body()).get("codigo").asString());
    }
    void assertProyeccion(Set<String> campos,List<Map<String,Object>> lista) {
        assertFalse(lista.isEmpty());for(var p:lista)assertEquals(campos,p.keySet());
    }
    @Test void getSinFiltrosRetornaTodosLosAccesibles() throws Exception {
        var admin=get("","ADMINISTRADOR");assertRespuesta(200,null,admin);var a=contenido(admin);
        assertEquals(j.queryForObject("SELECT count(*) FROM \"PEDIDO\"",Integer.class),a.size());
        assertEquals(f.ids(),ids(a).subList(0,4));
        var campos=new HashSet<>(COMUNES);campos.add("fechaHoraCarga");assertProyeccion(campos,a);
        var vendedor=get("","VENDEDOR");assertRespuesta(200,null,vendedor);var v=contenido(vendedor);
        assertEquals(ids(a),ids(v));campos.add("categoriaComercialAplicada");campos.add("totalOriginal");assertProyeccion(campos,v);
        // Todas las banderas de escritura conservan su valor false: este GET no depende de ellas.
    }
    @Test void getConFiltroIdPedidoRetornaUno() throws Exception {
        var r=get("?idPedido="+f.online,"VENDEDOR");assertRespuesta(200,null,r);assertEquals(List.of(f.online),ids(contenido(r)));
    }
    @Test void getConFiltroEstadoRetornaFiltrados() throws Exception {
        var r=get("?estado=ENTREGADO","ADMINISTRADOR");assertRespuesta(200,null,r);
        assertTrue(ids(contenido(r)).contains(f.asignacion.entregado));
        for(var p:contenido(r))assertEquals("ENTREGADO",p.get("estado"));
    }
    @Test void getConMultiplesFiltrosRetornaCombinados() throws Exception {
        String query="?idPedido="+f.online+"&idCliente="+(f.origen.base+1)+"&fechaReparto="+f.origen.hoy.plusDays(3)+"&estado=PENDIENTE&canalOrigen=ONLINE";
        var r=get(query,"VENDEDOR");assertRespuesta(200,null,r);assertEquals(List.of(f.online),ids(contenido(r)));
        var cliente=get("?idCliente="+(f.origen.base+1),"VENDEDOR");assertRespuesta(200,null,cliente);assertEquals(List.of(f.online),ids(contenido(cliente)));
        var fecha=get("?fechaReparto="+f.origen.hoy.plusDays(3),"ADMINISTRADOR");assertRespuesta(200,null,fecha);
        assertTrue(ids(contenido(fecha)).contains(f.online));for(var p:contenido(fecha))assertEquals(f.origen.hoy.plusDays(3).toString(),p.get("fechaReparto"));
        var canal=get("?canalOrigen=ONLINE","VENDEDOR");assertRespuesta(200,null,canal);
        assertTrue(ids(contenido(canal)).contains(f.online));for(var p:contenido(canal))assertEquals("ONLINE",p.get("canalOrigen"));
    }
    @Test void getSinCoincidenciasRetornaArrayVacio() throws Exception {
        var r=get("?idPedido="+Long.MAX_VALUE,"VENDEDOR");assertRespuesta(200,null,r);assertEquals(List.of(),contenido(r));
    }
    @Test void getSinTokenRetorna401() throws Exception {
        assertRespuesta(401,"SESION_INVALIDA",get("",null,null));
        assertRespuesta(401,"SESION_INVALIDA",get("",null,"JSESSIONID="+f.token("VENDEDOR")));
    }
    @Test void getConRolRepartidorRetornaSoloSusPedidos() throws Exception {
        var r=get("","REPARTIDOR");assertRespuesta(200,null,r);var lista=contenido(r);
        assertEquals(List.of(f.asignacion.pendiente),ids(lista));assertProyeccion(COMUNES,lista);
        for(long id:new long[]{f.asignacion.cancelado,f.asignacion.entregado,f.online,Long.MAX_VALUE})
            assertRespuesta(403,"OPERACION_PROHIBIDA",get("?idPedido="+id,"REPARTIDOR"));
        var propioSinCoincidencia=get("?idPedido="+f.asignacion.pendiente+"&estado=CANCELADO","REPARTIDOR");
        assertRespuesta(200,null,propioSinCoincidencia);assertTrue(contenido(propioSinCoincidencia).isEmpty());
        reparto.asignarOReasignar(f.origen.vendedor(),f.asignacion.datos(f.asignacion.segundo,"Cambio de ámbito"));
        var posterior=get("","REPARTIDOR");assertRespuesta(200,null,posterior);assertTrue(contenido(posterior).isEmpty());
    }
    @Test void getConRolProduccionRetornaContextoPreparacion() throws Exception {
        var r=get("?idCliente="+f.origen.base,"RESPONSABLE_PRODUCCION");
        assertRespuesta(200,null,r); assertEquals(List.of(f.asignacion.pendiente),ids(contenido(r)));
        assertProyeccion(COMUNES,contenido(r));
        for(long id:new long[]{f.asignacion.entregado,f.asignacion.cancelado,Long.MAX_VALUE})
            assertRespuesta(403,"FUERA_DE_CONTEXTO",get("?idPedido="+id,"RESPONSABLE_PRODUCCION"));
        var vacio=get("?idCliente="+Long.MAX_VALUE,"RESPONSABLE_PRODUCCION");
        assertRespuesta(200,null,vacio); assertTrue(contenido(vacio).isEmpty());
        assertRespuesta(403,"OPERACION_PROHIBIDA",get("","PROVEEDOR"));
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        assertRespuesta(200,null,get("","VENDEDOR"));
        assertRespuesta(400,"SOLICITUD_INVALIDA",get("?intruso=1","VENDEDOR"));
        assertRespuesta(401,"SESION_INVALIDA",get("",null,null));
        assertRespuesta(403,"FUERA_DE_CONTEXTO",get("?idPedido="+f.asignacion.cancelado,"RESPONSABLE_PRODUCCION"));
        // Un fallo SQL real y reversible en el contenedor aislado verifica 503 sin exponer el mensaje SQL.
        j.execute("ALTER TABLE \"PEDIDO\" RENAME TO \"PEDIDO_NO_DISPONIBLE_TEST\"");
        try {assertRespuesta(503,"PERSISTENCIA_NO_DISPONIBLE",get("","VENDEDOR"));}
        finally {j.execute("ALTER TABLE \"PEDIDO_NO_DISPONIBLE_TEST\" RENAME TO \"PEDIDO\"");}
    }
    @Test void getConParametroDesconocidoRetorna400() throws Exception {
        for(String campo:List.of("idUsuarioRepartidor","rol","sesion","limit","desde","totalOriginal"))
            assertRespuesta(400,"SOLICITUD_INVALIDA",get("?"+campo+"=1","VENDEDOR"));
        for(String query:List.of("?idPedido=0","?idPedido=-1","?idCliente=0","?idPedido=1.5","?idPedido=9223372036854775808",
                "?estado=INVALIDO","?canalOrigen=INVALIDO","?fechaReparto=2026-02-30","?idPedido=","?estado=",
                "?estado=PENDIENTE&estado=PENDIENTE","?idCliente=1&idCliente=2","?estado=%27OR%201%3D1"))
            assertRespuesta(400,"SOLICITUD_INVALIDA",get(query,"VENDEDOR"));
    }
}
