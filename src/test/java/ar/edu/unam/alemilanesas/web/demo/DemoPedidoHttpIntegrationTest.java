package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP real + PostgreSQL efímero + B3 real. No es navegador ni vistas de negocio integradas. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "ale.http.writes.pedidos-uc03-enabled=true","ale.http.writes.pedidos-uc43-enabled=true"})
@Testcontainers
class DemoPedidoHttpIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    PedidoFixture f;
    @BeforeEach void fixture() {f=tx.execute(s->new PedidoFixture(jdbc));}
    record Csrf(String cookie,String token) {}
    Csrf csrf() throws Exception {
        var r=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/web/login")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,r.statusCode());
        var matcher=Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(r.body());
        assertTrue(matcher.find());
        String cookie=r.headers().allValues("Set-Cookie").stream().filter(x->x.startsWith("JSESSIONID=")).findFirst().orElseThrow().split(";",2)[0];
        return new Csrf(cookie,matcher.group(1));
    }
    HttpResponse<String> post(String ruta,Map<String,String> datos,Csrf csrf,String rol) throws Exception {
        String body=datos.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+
                URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        if(csrf!=null)body+="&_csrf="+URLEncoder.encode(csrf.token(),StandardCharsets.UTF_8);
        String cookie="__Host-ale_session="+f.tokens.get(rol)+(csrf==null?"":"; "+csrf.cookie());
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+ruta))
                .header("Cookie",cookie).header("Origin","http://127.0.0.1:"+port)
                .header("Content-Type","application/x-www-form-urlencoded").timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    Map<String,String> datos() {
        return Map.of("idCliente",Long.toString(f.base),"fechaReparto",f.hoy.plusDays(2).toString(),
                "claveIdempotencia",UUID.randomUUID().toString(),"items[0].idProducto",Long.toString(f.base),
                "items[0].cantidadSolicitada","2.000","calle","Calle sintética","localidad","Posadas");
    }
    @Test void creaReintentaAsignaYPreservaSnapshotConB3Real() throws Exception {
        var csrf=csrf();var datos=datos();
        var primero=post("/web/demo/pedidos",datos,csrf,"VENDEDOR");
        assertEquals(303,primero.statusCode(),primero.body());
        String location=primero.headers().firstValue("Location").orElseThrow();
        long id=Long.parseLong(location.substring(location.indexOf('=')+1));
        // Cada formulario obtiene CSRF vigente; la autenticación invalida el anterior.
        var reintento=post("/web/demo/pedidos",datos,csrf(),"VENDEDOR");
        assertEquals(303,reintento.statusCode(),reintento.body());
        assertEquals(location,reintento.headers().firstValue("Location").orElseThrow());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM \"PEDIDO\" WHERE \"idOperacionOrigen\"=?",Integer.class,UUID.fromString(datos.get("claveIdempotencia"))));
        var antes=jdbc.queryForMap("SELECT \"fechaReparto\",\"categoriaComercialAplicada\",\"totalOriginal\" FROM \"PEDIDO\" WHERE \"idPedido\"=?",id);
        var detalle=jdbc.queryForMap("SELECT \"precioUnitarioOriginal\",\"cantidadOriginal\" FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=?",id);
        var asignado=post("/web/demo/pedidos/"+id+"/asignar",Map.of("idUsuarioRepartidor",Long.toString(f.base+3)),csrf(),"VENDEDOR");
        assertEquals(303,asignado.statusCode(),asignado.body());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? AND \"fechaHoraFin\" IS NULL",Integer.class,id));
        assertEquals(antes,jdbc.queryForMap("SELECT \"fechaReparto\",\"categoriaComercialAplicada\",\"totalOriginal\" FROM \"PEDIDO\" WHERE \"idPedido\"=?",id));
        assertEquals(detalle,jdbc.queryForMap("SELECT \"precioUnitarioOriginal\",\"cantidadOriginal\" FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=?",id));
    }
    @Test void rechazoCsrfYRolNoPersisten() throws Exception {
        var datos=datos();
        assertEquals(403,post("/web/demo/pedidos",datos,null,"VENDEDOR").statusCode());
        assertEquals(403,post("/web/demo/pedidos",datos,csrf(),"ADMINISTRADOR").statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM \"PEDIDO\" WHERE \"idOperacionOrigen\"=?",Integer.class,UUID.fromString(datos.get("claveIdempotencia"))));
    }
}
