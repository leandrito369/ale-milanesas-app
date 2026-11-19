package ar.edu.unam.alemilanesas.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP servlet real + PG aislado; Cookie explícita solo en loopback.
 * El navegador HTTPS se verifica aparte. El diagnóstico no cambia respuestas. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "server.address=127.0.0.1","ale.http.writes.pedidos-uc03-enabled=true","ale.http.writes.pedidos-uc43-enabled=true"})
@ActiveProfiles("demo") @Import(EstadoSesionWebHttpTest.TraceConfig.class)
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EstadoSesionWebHttpTest {
 @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
 @DynamicPropertySource static void dbprops(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);
 }
 record Event(String trace,String method,String uri,DispatcherType dispatch,String securityChain,
              String phase,Integer errorStatus,int status,boolean cookiePresent,boolean sameOldCookie){}
 static final Queue<Event> EVENTS=new ConcurrentLinkedQueue<>();
 static final AtomicReference<String> OLD_COOKIE=new AtomicReference<>();
 @TestConfiguration(proxyBeanMethods=false)
 static class TraceConfig {
  @Bean FilterRegistrationBean<Filter> trace(ApplicationContext ctx){
   Filter f=(request,response,chain)->{
    var req=(HttpServletRequest)request;var res=(HttpServletResponse)response;String trace=req.getHeader("X-G4-Trace");
    if(trace==null){chain.doFilter(request,response);return;}
    var proxy=ctx.getBean("springSecurityFilterChain",FilterChainProxy.class);
    var selected=proxy.getFilterChains().stream().filter(c->c.matches(req)).findFirst().orElseThrow();
    String name=ctx.getBeansOfType(SecurityFilterChain.class).entrySet().stream()
     .filter(e->e.getValue()==selected).map(Map.Entry::getKey).findFirst().orElse("unknown");
    String cookie=SeguridadWebInterna.tokenCookie(req);boolean present=cookie!=null,same=present&&cookie.equals(OLD_COOKIE.get());
    var error=(Integer)req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    EVENTS.add(new Event(trace,req.getMethod(),req.getRequestURI(),req.getDispatcherType(),name,"enter",error,res.getStatus(),present,same));
    var wrapper=new HttpServletResponseWrapper(res){
     @Override public void sendError(int status)throws IOException{
      EVENTS.add(new Event(trace,req.getMethod(),req.getRequestURI(),req.getDispatcherType(),name,"sendError",error,status,present,same));super.sendError(status);
     }
     @Override public void sendError(int status,String message)throws IOException{
      EVENTS.add(new Event(trace,req.getMethod(),req.getRequestURI(),req.getDispatcherType(),name,"sendError",error,status,present,same));
      super.sendError(status,message);
     }
    };
    try{chain.doFilter(req,wrapper);}finally{EVENTS.add(new Event(trace,req.getMethod(),req.getRequestURI(),req.getDispatcherType(),name,"exit",error,res.getStatus(),present,same));}
   };
   var reg=new FilterRegistrationBean<Filter>(f);reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
   reg.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST,DispatcherType.ERROR));return reg;
  }
 }
 @Value("${local.server.port}") int port;
 @Autowired JdbcTemplate jdbc;@Autowired TransactionTemplate tx;@Autowired PasswordEncoder encoder;
 static final AtomicLong IDS=new AtomicLong(970000);
 long id;String user,password;
 final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
 @BeforeEach void fixture(){
  EVENTS.clear();OLD_COOKIE.set(null);id=IDS.incrementAndGet();user="r01_"+id;password=UUID.randomUUID().toString();
  tx.executeWithoutResult(s->{
   jdbc.update("INSERT INTO \"ROL\" VALUES ('ADMINISTRADOR','Admin R01') ON CONFLICT DO NOTHING");
   jdbc.update("INSERT INTO \"USUARIO\" (\"idUsuario\",\"nombreUsuario\",\"credencialProtegida\",\"estado\",\"cambioCredencialPendiente\",\"fechaHoraAlta\") VALUES (?,?,?,'HABILITADO',false,clock_timestamp())",id,user,encoder.encode(password));
   jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'ADMINISTRADOR',clock_timestamp())",id);
  });
 }
 @AfterEach void clearSecret(){OLD_COOKIE.set(null);}
 String origin(){return "http://127.0.0.1:"+port;}
 HttpResponse<String> request(String method,String path,String cookie,Map<String,String> form,String source,String trace)throws Exception{
  var b=HttpRequest.newBuilder(URI.create(origin()+path)).header("Accept","text/html");
  if(cookie!=null)b.header("Cookie",cookie);if(source!=null)b.header("Origin",source);if(trace!=null)b.header("X-G4-Trace",trace);
  if(method.equals("GET"))b.GET();else{
   String data=form.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
   b.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(data));
  }
  var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());
  if(trace!=null){System.out.println("R01_HTTP "+trace+" "+method+" "+path+" final="+r.statusCode());EVENTS.stream().filter(e->e.trace().equals(trace)).forEach(e->System.out.println("R01_TRACE "+e));}
  return r;
 }
 HttpResponse<String> get(String path,String cookie,String trace)throws Exception{return request("GET",path,cookie,Map.of(),null,trace);}
 HttpResponse<String> post(String path,String cookie,Map<String,String> form,String source,String trace)throws Exception{return request("POST",path,cookie,form,source,trace);}
 static String csrf(HttpResponse<String> r){var m=Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(r.body());assertTrue(m.find(),"CSRF renderizado");return m.group(1);}
 static String setCookie(HttpResponse<String> r,String name){return r.headers().allValues("Set-Cookie").stream().filter(s->s.startsWith(name+"=")).map(s->s.split(";",2)[0]).findFirst().orElseThrow();}
 record Login(String cookies,String token){}
 Login login(String cred,String target)throws Exception{
  var form=get("/web/login",null,null);String js=setCookie(form,"JSESSIONID");
  var r=post("/web/login",js,Map.of("nombreUsuario",user,"credencial",cred,"_csrf",csrf(form)),origin(),null);
  assertEquals(302,r.statusCode());assertEquals(target,URI.create(r.headers().firstValue("Location").orElseThrow()).getPath());
  String newJs=r.headers().allValues("Set-Cookie").stream().filter(s->s.startsWith("JSESSIONID=")).map(s->s.split(";",2)[0]).findFirst().orElse(js);
  String auth=setCookie(r,SeguridadWebInterna.COOKIE_SESION);return new Login(auth+"; "+newJs,auth.substring(auth.indexOf('=')+1));
 }
 void assert401(HttpResponse<String> r,String trace){
  assertEquals(401,r.statusCode(),"Rechazo de autenticación debe conservar 401");
  var ev=EVENTS.stream().filter(e->e.trace().equals(trace)).toList();
  assertTrue(ev.stream().anyMatch(e->e.dispatch()==DispatcherType.REQUEST&&e.securityChain().equals("seguridadWeb")));
  assertTrue(ev.stream().noneMatch(e->e.dispatch()==DispatcherType.ERROR),"401 sin despacho a cadena global");
  assertEquals("no-store",r.headers().firstValue("Cache-Control").orElseThrow());
  assertTrue(r.headers().firstValue("Content-Security-Policy").orElseThrow().contains("default-src 'none'"));
 }
 @Test void logoutCookieAnteriorYGetSinCookie()throws Exception{
  var l=login(password,"/web/inicio");var home=get("/web/inicio",l.cookies(),null);assertEquals(200,home.statusCode());
  assertEquals(302,post("/web/logout",l.cookies(),Map.of("_csrf",csrf(home)),origin(),null).statusCode());
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM \"SESION_USUARIO\" WHERE \"idUsuario\"=? AND \"fechaHoraRevocacion\" IS NOT NULL",Integer.class,id));
  OLD_COOKIE.set(l.token());var revoked=get("/web/demo/clientes",l.cookies(),"logout-reused");
  assertTrue(EVENTS.stream().anyMatch(e->e.trace().equals("logout-reused")&&e.sameOldCookie()),"Cookie anterior transmitida");
  var anonymous=get("/web/inicio",null,"no-cookie");assert401(revoked,"logout-reused");assert401(anonymous,"no-cookie");
 }
 @Test void uc53RevocaCookieAnteriorYPermiteNuevoLogin()throws Exception{
  jdbc.update("UPDATE \"USUARIO\" SET \"cambioCredencialPendiente\"=true WHERE \"idUsuario\"=?",id);
  var l=login(password,"/web/cambiar-clave");assertEquals(302,get("/web/inicio",l.cookies(),null).statusCode());
  var form=get("/web/cambiar-clave",l.cookies(),null);String next=UUID.randomUUID().toString();
  assertEquals(302,post("/web/cambiar-clave",l.cookies(),Map.of("_csrf",csrf(form),"nuevaCredencial",next,"confirmacionNuevaCredencial",next),origin(),null).statusCode());
  OLD_COOKIE.set(l.token());var reused=get("/web/inicio",l.cookies(),"uc53-reused");
  assertTrue(EVENTS.stream().anyMatch(e->e.trace().equals("uc53-reused")&&e.sameOldCookie()));
  assert401(reused,"uc53-reused");var nextLogin=login(next,"/web/inicio");assertEquals(200,get("/web/inicio",nextLogin.cookies(),null).statusCode());
 }
 @Test void loginAnonimoCssYCredencialIncorrecta()throws Exception{
  var form=get("/web/login",null,null);assertEquals(200,form.statusCode());
  assertTrue(form.body().contains("href=\"/demo/demo.css\""));
  var css=get("/demo/demo.css",null,null);assertEquals(200,css.statusCode());
  assertTrue(css.body().contains("input[type=\"password\"]"));
  var rejected=post("/web/login",setCookie(form,"JSESSIONID"),Map.of("nombreUsuario",user,"credencial","incorrecta","_csrf",csrf(form)),origin(),null);
  assertEquals(302,rejected.statusCode());
  assertTrue(rejected.headers().firstValue("Location").orElseThrow().endsWith("/web/login?error"));
  assertTrue(get("/web/login?error",null,null).body().contains("No se pudo iniciar sesión."));
 }
 @Test void cambioVoluntarioConservaContratoYRevocaSesion()throws Exception{
  var l=login(password,"/web/inicio");var form=get("/web/cambiar-clave",l.cookies(),null);
  assertEquals(200,form.statusCode());String next=UUID.randomUUID().toString();
  var changed=post("/web/cambiar-clave",l.cookies(),Map.of("_csrf",csrf(form),"credencialActual",password,"nuevaCredencial",next,"confirmacionNuevaCredencial",next),origin(),null);
  assertEquals(302,changed.statusCode());
  assertTrue(changed.headers().firstValue("Location").orElseThrow().endsWith("/web/login?cambiada"));
  assertEquals(401,get("/web/inicio",l.cookies(),null).statusCode());
  assertEquals(200,get("/web/inicio",login(next,"/web/inicio").cookies(),null).statusCode());
 }
 @Test void csrfOrigenRolInsuficienteYErrorDirectoConservan403()throws Exception{
  var l=login(password,"/web/inicio");var home=get("/web/inicio",l.cookies(),null);
  assertEquals(403,post("/web/logout",l.cookies(),Map.of(),origin(),"csrf-missing").statusCode());
  assertEquals(403,post("/web/logout",l.cookies(),Map.of("_csrf",csrf(home)),"https://externo.invalid","origin-external").statusCode());
  assertEquals(403,get("/web/demo/pedidos/nuevo",l.cookies(),"rbac-admin").statusCode());
  assertEquals(403,get("/error",null,"direct-error").statusCode());
  assertEquals(200,get("/web/inicio",l.cookies(),null).statusCode());
 }
}
