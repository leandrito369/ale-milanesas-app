package ar.edu.unam.alemilanesas.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Cadenas reales en ambos perfiles, sin registrar el guard prod ni abrir puertos.
 * No acredita arranque productivo. Reutiliza el fixture MVC de Inicio. */
class EstadoSesionWebMvcTest {
 @ParameterizedTest @ValueSource(strings={"demo","prod"})
 void autenticacion401Permisos403YErrorCerradoEnAmbosPerfiles(String profile)throws Exception{
  var f=new InicioModeloMvcTest();
  try{
   f.iniciar(profile,Map.of());
   f.mvc.perform(get("/web/inicio").secure(true)).andExpect(status().isUnauthorized())
       .andExpect(header().string("Cache-Control","no-store"));
   f.mvc.perform(get("/web/inicio").secure(true)
       .cookie(new Cookie(SeguridadWebInterna.COOKIE_SESION,"revocada-no-validada")))
       .andExpect(status().isUnauthorized());
   var admin=f.autorizar("ADMINISTRADOR");
   f.mvc.perform(get("/web/inicio").secure(true).cookie(admin))
       .andExpect(status().isOk()).andExpect(model().attribute("esAdmin",true))
       .andExpect(model().attribute("esVendedor",false));
   f.mvc.perform(get("/web/demo/pedidos/nuevo").secure(true).cookie(admin))
       .andExpect(status().isForbidden());
   f.mvc.perform(post("/web/logout").secure(true).cookie(admin))
       .andExpect(status().isForbidden());
   var login=f.mvc.perform(get("/web/login").secure(true)).andReturn();
   var csrf=(CsrfToken)login.getRequest().getAttribute("_csrf");String token=csrf.getToken();
   var session=(MockHttpSession)login.getRequest().getSession(false);
   f.mvc.perform(post("/web/logout").secure(true).session(session).cookie(admin)
       .param("_csrf",token).header("Origin","https://externo.invalid"))
       .andExpect(status().isForbidden());
   // No se agrega excepción para /error ni se relaja el denyAll del fallback.
   f.mvc.perform(get("/error").secure(true)).andExpect(status().isForbidden());
   f.mvc.perform(get("/error").secure(true).with(req->{req.setDispatcherType(DispatcherType.ERROR);return req;}))
       .andExpect(status().isForbidden());
  }finally{f.cerrar();}
 }
}
