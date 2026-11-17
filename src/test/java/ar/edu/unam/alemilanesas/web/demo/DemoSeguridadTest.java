package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.web.*;
import ar.edu.unam.alemilanesas.acceso.*;
import ar.edu.unam.alemilanesas.maestros.*;
import ar.edu.unam.alemilanesas.ventas.*;
import java.util.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Cadenas reales + MVC; verificador simulado. No es navegador, render Thymeleaf ni prueba DB. */
class DemoSeguridadTest {
    AnnotationConfigWebApplicationContext ctx;
    MockMvc mvc;
    SeguridadHttpInterna.VerificadorSesionBearer verificador;
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SeguridadWebInterna.class,SeguridadHttpInterna.class,
        ar.edu.unam.alemilanesas.bootstrap.SecurityConfiguration.class,
        ConsultasDemoController.class,CargarPedidoDemoController.class,AsignarRepartoDemoController.class,
        MisEntregasDemoController.class,ErroresDemoAdvice.class,AccesoWebController.class})
    static class Config {
        @Bean @Primary SeguridadHttpInterna.VerificadorSesionBearer verificador() {return mock(SeguridadHttpInterna.VerificadorSesionBearer.class);}
        @Bean org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate sql() {
            return mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
        }
        @Bean BuscarClienteService clientes() {return mock(BuscarClienteService.class);}
        @Bean BuscarProductoService productos() {return mock(BuscarProductoService.class);}
        @Bean BuscarPedidoService pedidos() {return mock(BuscarPedidoService.class);}
        @Bean CargarPedidoService cargar() {return mock(CargarPedidoService.class);}
        @Bean AsignarRepartoService reparto() {return mock(AsignarRepartoService.class);}
        @Bean SesionesUsuarioService sesiones() {return mock(SesionesUsuarioService.class);}
        @Bean CambiarContrasenaService claves() {return mock(CambiarContrasenaService.class);}
        @Bean LimiteIntentosLogin limite() {return mock(LimiteIntentosLogin.class);}
        @Bean ViewResolver vistasDePrueba() {
            return (nombre,locale) -> new View() {
                public String getContentType() {return "text/plain";}
                public void render(Map<String,?> model,jakarta.servlet.http.HttpServletRequest req,
                        jakarta.servlet.http.HttpServletResponse res) throws Exception {
                    var csrf=(CsrfToken)req.getAttribute("_csrf");
                    res.getWriter().write(csrf==null?"vista-prueba":csrf.getToken());
                }
            };
        }
    }
    void iniciar(boolean flags,boolean prod) {
        ctx=new AnnotationConfigWebApplicationContext();ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment().setActiveProfiles(prod?"prod":"test");
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("demo-test",Map.of(
                "ale.http.writes.pedidos-uc03-enabled",Boolean.toString(flags),
                "ale.http.writes.pedidos-uc43-enabled",Boolean.toString(flags),
                "ale.web.allowed-origin","https://demo.test")));
        ctx.register(Config.class);ctx.refresh();
        verificador=ctx.getBean(SeguridadHttpInterna.VerificadorSesionBearer.class);
        when(verificador.validar(anyString())).thenReturn(Optional.empty());
        for(String rol:List.of("VENDEDOR","ADMINISTRADOR","REPARTIDOR","RESPONSABLE_PRODUCCION","PROVEEDOR",SeguridadHttpInterna.ROL_CAMBIO_PENDIENTE))
            when(verificador.validar(rol)).thenReturn(Optional.of(new SeguridadHttpInterna.VerificadorSesionBearer.SesionAutorizada(
                    new SeguridadHttpInterna.PrincipalInterno(UUID.randomUUID(),9),Set.of(rol))));
        mvc=MockMvcBuilders.webAppContextSetup(ctx).addFilters(ctx.getBean("springSecurityFilterChain",Filter.class)).build();
    }
    @AfterEach void cerrar() {if(ctx!=null)ctx.close();}
    MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder req,String rol) {
        return req.secure(true).cookie(new jakarta.servlet.http.Cookie("__Host-ale_session",rol));
    }
    @Test void consultasYVistaInformativaTienenRbacIndependiente() throws Exception {
        iniciar(true,false);
        for(String ruta:List.of("clientes","productos","pedidos")) {
            for(String rol:List.of("ADMINISTRADOR","VENDEDOR"))
                mvc.perform(actor(get("/web/demo/"+ruta),rol)).andExpect(status().isOk());
            for(String rol:List.of("REPARTIDOR","RESPONSABLE_PRODUCCION","PROVEEDOR",SeguridadHttpInterna.ROL_CAMBIO_PENDIENTE))
                mvc.perform(actor(get("/web/demo/"+ruta),rol)).andExpect(status().isForbidden());
        }
        mvc.perform(actor(get("/web/demo/mis-entregas"),"REPARTIDOR")).andExpect(status().isOk());
        for(String rol:List.of("ADMINISTRADOR","VENDEDOR","PROVEEDOR","RESPONSABLE_PRODUCCION",SeguridadHttpInterna.ROL_CAMBIO_PENDIENTE))
            mvc.perform(actor(get("/web/demo/mis-entregas"),rol)).andExpect(status().isForbidden());
        verifyNoInteractions(ctx.getBean(AsignarRepartoService.class),ctx.getBean(CargarPedidoService.class));
        mvc.perform(get("/web/demo/mis-entregas")).andExpect(status().isUnauthorized());
        mvc.perform(actor(get("/web/demo/clientes"),"revocada")).andExpect(status().isUnauthorized());
    }
    @Test void banderasApagadasCierranGetYPost() throws Exception {
        iniciar(false,false);
        assertTrue(ctx.getBeansOfType(CargarPedidoDemoController.class).isEmpty());
        assertTrue(ctx.getBeansOfType(AsignarRepartoDemoController.class).isEmpty());
        mvc.perform(actor(get("/web/demo/pedidos/nuevo"),"VENDEDOR")).andExpect(status().isForbidden());
        mvc.perform(actor(get("/web/demo/pedidos/1/asignar"),"VENDEDOR")).andExpect(status().isForbidden());
        for(String ruta:List.of("/web/demo/pedidos","/web/demo/pedidos/1/asignar")) {
            var token=csrf();
            mvc.perform(actor(post(ruta).session(token.session()).param("_csrf",token.valor()),"VENDEDOR"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(ctx.getBean(CargarPedidoService.class),ctx.getBean(AsignarRepartoService.class));
    }
    record Token(MockHttpSession session,String valor) {}
    Token csrf() throws Exception {
        var r=mvc.perform(get("/web/login").secure(true)).andExpect(status().isOk()).andReturn();
        return new Token((MockHttpSession)r.getRequest().getSession(false),r.getResponse().getContentAsString());
    }
    @Test void csrfOrigenYRolSonObligatoriosParaMutar() throws Exception {
        iniciar(true,false);
        for(String ruta:List.of("/web/demo/pedidos","/web/demo/pedidos/1/asignar")) {
            mvc.perform(actor(post(ruta),"VENDEDOR")).andExpect(status().isForbidden());
            for(String origen:List.of("https://externo.test","null")) {
                var t=csrf();
                mvc.perform(actor(post(ruta).session(t.session()).param("_csrf",t.valor()).header("Origin",origen),"VENDEDOR"))
                        .andExpect(status().isForbidden());
            }
            for(String rol:List.of("ADMINISTRADOR","REPARTIDOR",SeguridadHttpInterna.ROL_CAMBIO_PENDIENTE)) {
                var t=csrf();
                mvc.perform(actor(post(ruta).session(t.session()).param("_csrf",t.valor()).header("Origin","https://demo.test"),rol))
                        .andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(ctx.getBean(CargarPedidoService.class),ctx.getBean(AsignarRepartoService.class));
        // Origin ausente sigue permitido con CSRF; no se inventa una exigencia nueva.
        for(boolean origen:List.of(true,false)) {
            var t=csrf();var req=actor(post("/web/demo/pedidos/1/asignar").session(t.session()).param("_csrf",t.valor())
                    .param("idUsuarioRepartidor","9"),"VENDEDOR");
            if(origen)req.header("Origin","https://demo.test");
            mvc.perform(req).andExpect(status().isSeeOther());
        }
        verify(ctx.getBean(AsignarRepartoService.class),times(2)).asignarOReasignar(any(),any());
    }
    @Test void cookieCspCacheYCanalesSeConservan() throws Exception {
        iniciar(true,false);
        mvc.perform(actor(get("/web/demo/mis-entregas"),"REPARTIDOR"))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Content-Security-Policy","default-src 'none'; style-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"));
        mvc.perform(get("/web/demo/clientes").header("Authorization","Bearer VENDEDOR")).andExpect(status().isUnauthorized());
        mvc.perform(actor(get("/api/v1/pedidos"),"VENDEDOR")).andExpect(status().isUnauthorized());
        mvc.perform(get("/demo/demo.css")).andExpect(status().isNotFound());
        mvc.perform(get("/demo/otro.css")).andExpect(status().isForbidden());
        mvc.perform(post("/demo/demo.css")).andExpect(status().isForbidden());
        var t=csrf();
        when(ctx.getBean(SesionesUsuarioService.class).iniciarSesion("sintetico","efimera"))
                .thenReturn(new SesionesUsuarioService.TokenEmitido("token-de-prueba","Bearer",1800,false));
        var login=mvc.perform(post("/web/login").session(t.session()).secure(true).param("_csrf",t.valor())
                .param("nombreUsuario","sintetico").param("credencial","efimera").header("Origin","https://demo.test"))
                .andExpect(view().name("redirect:/web/inicio")).andReturn();
        String cookie=login.getResponse().getHeader("Set-Cookie");
        assertTrue(cookie.contains("__Host-ale_session="));assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));assertTrue(cookie.contains("Path=/"));
    }
    @Test void cssNoDebeInvalidarCsrfDeFormularioAutenticado() throws Exception {
        iniciar(true,false);
        var inicial=csrf();
        var form=mvc.perform(actor(get("/web/demo/pedidos/nuevo").session(inicial.session()),"VENDEDOR")).andReturn();
        String token=form.getResponse().getContentAsString();
        var session=(org.springframework.mock.web.MockHttpSession)form.getRequest().getSession(false);
        var repo=new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository();
        assertNotNull(repo.loadToken(form.getRequest()));
        mvc.perform(actor(get("/demo/demo.css").session(session),"VENDEDOR")).andReturn();
        var probe=new org.springframework.mock.web.MockHttpServletRequest();probe.setSession(session);
        assertNotNull(repo.loadToken(probe),"CSS no debe borrar CSRF de HttpSession");
        mvc.perform(actor(post("/web/demo/pedidos/1/asignar").session(session)
                .param("_csrf",token).param("idUsuarioRepartidor","9"),"VENDEDOR"))
                .andExpect(status().isSeeOther());
    }
    @Test void prodNoRegistraControladoresAunqueFlagsSeanTrue() throws Exception {
        iniciar(true,true);
        for(Class<?> tipo:List.of(ConsultasDemoController.class,CargarPedidoDemoController.class,
                AsignarRepartoDemoController.class,MisEntregasDemoController.class))assertTrue(ctx.getBeansOfType(tipo).isEmpty());
        mvc.perform(actor(get("/web/demo/pedidos"),"VENDEDOR")).andExpect(status().isForbidden());
        mvc.perform(get("/demo/demo.css")).andExpect(status().isUnauthorized());
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("ale.http.writes.pedidos-uc03-enabled","true");
        assertThrows(IllegalStateException.class,()->new PrecondicionesDespliegue(env).postProcessBeanFactory(null));
    }
}
