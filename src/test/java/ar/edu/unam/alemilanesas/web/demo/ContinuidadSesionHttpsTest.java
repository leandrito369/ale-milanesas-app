package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.regex.Pattern;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** HTTPS real, una CookieJar, HTML Thymeleaf y CSS. Fixture de vista SOLO para CI independiente de PR93. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "ale.http.writes.pedidos-uc03-enabled=true","ale.http.writes.pedidos-uc43-enabled=true"})
@Testcontainers
class ContinuidadSesionHttpsTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    static final String PASSWORD=UUID.randomUUID().toString();
    static final Path DIR=preparar();
    static Path preparar() {
        try {
            Path d=Files.createTempDirectory("ale-https-test-");
            var command=List.of(System.getProperty("java.home")+"/bin/keytool","-genkeypair","-alias","demo",
                "-keyalg","RSA","-keystore",d.resolve("tls.p12").toString(),"-storetype","PKCS12",
                "-storepass",PASSWORD,"-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1");
            var proc=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(proc.waitFor()!=0)throw new IllegalStateException("TLS fixture failed");
            for(String view:List.of("web-login","web-inicio")) {
                try(var in=ContinuidadSesionHttpsTest.class.getResourceAsStream("/templates/"+view+".html")) {
                    Files.copy(Objects.requireNonNull(in),d.resolve(view+".html"));
                }
            }
            Files.createDirectories(d.resolve("demo"));
            Files.writeString(d.resolve("demo/pedido-nuevo.html"),"""
                <html xmlns:th="http://www.thymeleaf.org"><head><link rel="stylesheet" href="/demo/demo.css"></head>
                <body><form th:action="@{/web/demo/pedidos}" method="post">
                <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
                <input name="claveIdempotencia" th:value="${form.claveIdempotencia}">
                </form></body></html>
                """);
            Files.writeString(d.resolve("demo/demo.css"),"body { color: #252A31; }");
            return d;
        } catch(Exception e) {throw new IllegalStateException("Cannot prepare HTTPS fixture",e);}
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
        r.add("server.ssl.enabled",()->true);r.add("server.ssl.key-store",()->DIR.resolve("tls.p12").toUri().toString());
        r.add("server.ssl.key-store-password",()->PASSWORD);r.add("server.ssl.key-store-type",()->"PKCS12");
        r.add("spring.thymeleaf.prefix",()->DIR.toUri().toString());
        r.add("spring.web.resources.static-locations",()->DIR.toUri().toString());
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder encoder;
    String token(String html,String name) {
        var matcher=Pattern.compile("name=\""+name+"\"\\s+value=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(),"Rendered field missing");return matcher.group(1);
    }
    @Test void htmlCssPostIdempotenciaAsignacionYLogout() throws Exception {
        PedidoFixture f=tx.execute(s->new PedidoFixture(j));
        String clave=UUID.randomUUID().toString();
        j.update("UPDATE \"USUARIO\" SET \"credencialProtegida\"=? WHERE \"idUsuario\"=?",encoder.encode(clave),f.base);
        KeyStore ks=KeyStore.getInstance("PKCS12");
        try(var in=Files.newInputStream(DIR.resolve("tls.p12"))){ks.load(in,PASSWORD.toCharArray());}
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(ks);
        var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);
        var jar=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        var http=HttpClient.newBuilder().sslContext(ssl).cookieHandler(jar).build();
        String origin="https://localhost:"+port;
        java.util.function.Function<String,HttpResponse<String>> get=path->{
            try{return http.send(HttpRequest.newBuilder(URI.create(origin+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
            catch(Exception e){throw new IllegalStateException(e);}
        };
        java.util.function.BiFunction<String,Map<String,String>,HttpResponse<String>> post=(path,data)->{
            String body=data.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
            if(data.containsKey("_csrf")) body+="&_csrf="+URLEncoder.encode(data.get("_csrf"),StandardCharsets.UTF_8);
            try{return http.send(HttpRequest.newBuilder(URI.create(origin+path)).header("Origin",origin)
                .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
            catch(Exception e){throw new IllegalStateException(e);}
        };
        var login=get.apply("/web/login");
        String before=jar.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        assertEquals(302,post.apply("/web/login",Map.of("nombreUsuario","uc03_"+f.base,"credencial",clave,"_csrf",token(login.body(),"_csrf"))).statusCode());
        String after=jar.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        assertNotEquals(before,after,"Login must protect against session fixation");
        var form=get.apply("/web/demo/pedidos/nuevo");
        String csrf=token(form.body(),"_csrf"),uuid=token(form.body(),"claveIdempotencia");
        assertEquals(2,Pattern.compile("name=\"_csrf\"").matcher(form.body()).results().count(),"Explicit + automatic Thymeleaf CSRF");
        assertEquals(200,get.apply("/demo/demo.css").statusCode());
        assertEquals(after,jar.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue());
        var data=new HashMap<>(Map.of("idCliente",Long.toString(f.base),"fechaReparto",f.hoy.plusDays(2).toString(),
            "claveIdempotencia",uuid,"items[0].idProducto",Long.toString(f.base),"items[0].cantidadSolicitada","2.000",
            "calle","Calle sintetica","localidad","Posadas","_csrf",csrf));
        var oldToken=new HashMap<>(data);oldToken.put("_csrf",token(login.body(),"_csrf"));
        assertEquals(403,post.apply("/web/demo/pedidos",oldToken).statusCode(),"Anonymous CSRF must expire on login");
        var created=post.apply("/web/demo/pedidos",data);assertEquals(303,created.statusCode());
        String location=created.headers().firstValue("Location").orElseThrow();
        assertEquals(location,post.apply("/web/demo/pedidos",data).headers().firstValue("Location").orElseThrow());
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"PEDIDO\" WHERE \"idOperacionOrigen\"=?",Integer.class,UUID.fromString(uuid)));
        long id=Long.parseLong(location.split("=")[1]);
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=?",Integer.class,id));
        assertEquals(200,get.apply("/demo/demo.css").statusCode());
        assertEquals(303,post.apply("/web/demo/pedidos/"+id+"/asignar",Map.of("idUsuarioRepartidor",Long.toString(f.base+3),"_csrf",csrf)).statusCode());
        var noCsrf=new HashMap<>(data);noCsrf.remove("_csrf");assertEquals(403,post.apply("/web/demo/pedidos",noCsrf).statusCode());
        var foreign=http.send(HttpRequest.newBuilder(URI.create(origin+"/web/demo/pedidos/"+id+"/asignar"))
            .header("Origin","https://externo.invalid").header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("idUsuarioRepartidor="+(f.base+3)+"&_csrf="+URLEncoder.encode(csrf,StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(403,foreign.statusCode());
        assertEquals(302,post.apply("/web/logout",Map.of("_csrf",csrf)).statusCode());
        assertTrue(Set.of(401,403).contains(get.apply("/web/inicio").statusCode()));
    }
    @org.junit.jupiter.api.AfterAll static void limpiar() throws Exception {
        try(var paths=Files.walk(DIR)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
}
