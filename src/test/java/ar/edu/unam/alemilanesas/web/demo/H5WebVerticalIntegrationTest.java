package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.produccioninventario.PreparacionFixture;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "ale.http.writes.recorridos-uc43-enabled=true",
        "ale.http.writes.entregas-uc05-enabled=true",
        "ale.http.writes.preparaciones-uc28-enabled=true"
})
@Testcontainers
class H5WebVerticalIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    PreparacionFixture f;
    @BeforeEach void fixture() { f=tx.execute(s -> new PreparacionFixture(jdbc,tx,pedidos)); }
    record Csrf(String cookie,String token) {}
    String base() {return "http://127.0.0.1:"+port;}
    String cookie(String rol,Csrf csrf) {
        return "__Host-ale_session="+f.actores.tokens.get(rol)+(csrf==null?"":"; "+csrf.cookie());
    }
    Csrf csrf() throws Exception {
        var r=http.send(HttpRequest.newBuilder(URI.create(base()+"/web/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200,r.statusCode());
        var m=Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(r.body());
        assertTrue(m.find());
        return new Csrf(r.headers().allValues("Set-Cookie").stream()
                .filter(x->x.startsWith("JSESSIONID=")).findFirst().orElseThrow().split(";",2)[0],m.group(1));
    }
    HttpResponse<String> get(String path,String rol,Csrf csrf) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base()+path)).header("Cookie",cookie(rol,csrf))
                .timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path,String rol,Csrf csrf,Map<String,String> campos) throws Exception {
        var d=new LinkedHashMap<>(campos);
        if(csrf!=null)d.put("_csrf",csrf.token());
        String body=d.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)
                +"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        return http.send(HttpRequest.newBuilder(URI.create(base()+path)).header("Cookie",cookie(rol,csrf))
                .header("Origin",base()).header("Content-Type","application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    int count(String tabla) {return jdbc.queryForObject("SELECT count(*) FROM \""+tabla+"\"",Integer.class);}
    @Test void recorridoPreparacionEntregaCuentaConPersistenciaReal() throws Exception {
        long rep=f.actores.base+3;
        String idPed=Long.toString(f.pedido);
        String idCli=Long.toString(f.actores.datos().idCliente());
        var v=csrf();var prod=csrf();var r=csrf();
        assertEquals(200,get("/web/demo/recorridos","VENDEDOR",v).statusCode());
        var abrir=post("/web/demo/recorridos/abrir","VENDEDOR",v,Map.of("idUsuarioRepartidor",Long.toString(rep)));
        assertEquals(303,abrir.statusCode(),abrir.body());
        long rec=jdbc.queryForObject("SELECT \"idRecorrido\" FROM \"RECORRIDO_REPARTO\" WHERE \"idUsuarioRepartidor\"=?",
                Long.class,rep);
        var apertura=get("/web/demo/recorridos","VENDEDOR",v);
        assertTrue(apertura.body().contains("Recorrido abierto: #"+rec));
        assertFalse(get("/web/demo/recorridos","VENDEDOR",v).body().contains("Recorrido abierto: #"+rec));
        var asig=post("/web/demo/recorridos/asignar","VENDEDOR",v,Map.of("idRecorrido",Long.toString(rec),
                "idPedido",idPed,"idUsuarioRepartidor",Long.toString(rep)));
        assertEquals(303,asig.statusCode(),asig.body());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM \"RECORRIDO_ASIGNACION\" WHERE \"idPedido\"=? AND \"estadoPertenencia\"='ACTIVA'",Integer.class,f.pedido));
        assertEquals(302,get("/web/demo/preparaciones?idPedido="+idPed,"RESPONSABLE_PRODUCCION",prod).statusCode());
        var prep=post("/web/demo/pedidos/"+idPed+"/preparar","RESPONSABLE_PRODUCCION",prod,
                Map.of("idProducto",Long.toString(f.producto),"cantidadPreparada","2.000"));
        assertEquals(303,prep.statusCode(),prep.body());
        var form=get("/web/demo/entregar?idPedido="+idPed,"REPARTIDOR",r);
        assertEquals(200,form.statusCode(),form.body());
        assertFalse(form.body().contains("saldoConfirmado"));
        int cobros=count("COBRO"),caja=count("MOVIMIENTO_CAJA");
        String clave=UUID.randomUUID().toString();
        var d=Map.of("idPedido",idPed,"items[0].idProducto",Long.toString(f.producto),
                "items[0].cantidadEntregada","1.000","efectivoDeclaradoRecibido","1.00","claveIdempotencia",clave);
        var entrega=post("/web/demo/entregar","REPARTIDOR",r,d);
        assertEquals(303,entrega.statusCode(),entrega.body());
        assertEquals("ENTREGADO",jdbc.queryForObject("SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=?",String.class,f.pedido));
        assertTrue(get("/web/demo/entregar","REPARTIDOR",r).body().contains("Entrega del Pedido #"+idPed+" registrada."));
        assertFalse(get("/web/demo/entregar","REPARTIDOR",r).body().contains("Entrega del Pedido #"+idPed+" registrada."));
        assertEquals(303,post("/web/demo/entregar","REPARTIDOR",r,d).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM \"ENTREGA\" WHERE \"idPedido\"=?",Integer.class,f.pedido));
        assertEquals(cobros,count("COBRO"));assertEquals(caja,count("MOVIMIENTO_CAJA"));
        var cuenta=get("/web/demo/cuenta-corriente?idCliente="+idCli,"VENDEDOR",v);
        assertEquals(200,cuenta.statusCode(),cuenta.body());
        assertTrue(cuenta.body().contains("Saldo confirmado"));
        assertTrue(cuenta.body().contains("Tránsito validado vigente"));
        assertTrue(cuenta.body().contains("Saldo exigible provisional"));
        assertTrue(cuenta.body().contains("CARGO_POR_VENTA"));
    }
    @Test void permisosCsrfParametrosYExitoNoFalsificable() throws Exception {
        var c=csrf();
        for(String rol:List.of("ADMINISTRADOR","RESPONSABLE_PRODUCCION","REPARTIDOR")) {
            assertEquals(403,get("/web/demo/recorridos",rol,c).statusCode());
            assertEquals(403,get("/web/demo/cuenta-corriente",rol,c).statusCode());
        }
        assertEquals(403,get("/web/demo/entregar","VENDEDOR",c).statusCode());
        assertEquals(403,post("/web/demo/recorridos/abrir","VENDEDOR",null,
                Map.of("idUsuarioRepartidor",Long.toString(f.actores.base+3))).statusCode());
        assertEquals(400,get("/web/demo/entregar?confirmada=123","REPARTIDOR",c).statusCode());
        assertEquals(400,get("/web/demo/cuenta-corriente?idSesion=123","VENDEDOR",c).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM \"ENTREGA\" WHERE \"idPedido\"=?",Integer.class,f.pedido));
    }
}
