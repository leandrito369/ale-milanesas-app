package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.*;
import ar.edu.unam.alemilanesas.maestros.*;
import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService;
import ar.edu.unam.alemilanesas.web.demo.*;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC y cadenas reales; solo el verificador y B3 son mocks. Sin DB, navegador ni despliegue. */
class InicioModeloMvcTest {
    static final List<String> ATRIBUTOS=List.of("esAdmin","esVendedor","esRepartidor",
            "demoDisponible","uc03Disponible","uc43Disponible");
    AnnotationConfigWebApplicationContext ctx;
    MockMvc mvc;
    SeguridadHttpInterna.VerificadorSesionBearer verificador;

    @TestConfiguration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity
    @Import({AccesoWebController.class,SeguridadWebInterna.class,SeguridadHttpInterna.class,
        ar.edu.unam.alemilanesas.bootstrap.SecurityConfiguration.class,
        ConsultasDemoController.class,MisEntregasDemoController.class,ErroresDemoAdvice.class})
    static class Config {
        @Bean @Primary SeguridadHttpInterna.VerificadorSesionBearer verificador() {
            return mock(SeguridadHttpInterna.VerificadorSesionBearer.class);
        }
        @Bean org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate sql() {
            return mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
        }
        @Bean SesionesUsuarioService sesiones() {return mock(SesionesUsuarioService.class);}
        @Bean CambiarContrasenaService claves() {return mock(CambiarContrasenaService.class);}
        @Bean LimiteIntentosLogin limite() {return mock(LimiteIntentosLogin.class);}
        @Bean BuscarClienteService clientes() {return mock(BuscarClienteService.class);}
        @Bean BuscarProductoService productos() {return mock(BuscarProductoService.class);}
        @Bean BuscarPedidoService pedidos() {return mock(BuscarPedidoService.class);}
        @Bean ViewResolver vistas() {
            return (nombre,locale)->{
                if(nombre.startsWith("redirect:"))
                    return new org.springframework.web.servlet.view.RedirectView(nombre.substring(9));
                return new View() {
                public String getContentType() {return "text/plain";}
                public void render(Map<String,?> model,jakarta.servlet.http.HttpServletRequest req,
                        jakarta.servlet.http.HttpServletResponse res) { /* Se verifica ModelAndView. */ }
            };
            };
        }
    }
    void iniciar(String perfiles,Map<String,Object> flags) {
        ctx=new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment().setActiveProfiles(perfiles.split(","));
        ctx.getEnvironment().getPropertySources().remove("systemProperties");
        ctx.getEnvironment().getPropertySources().remove("systemEnvironment");
        var props=new HashMap<>(flags);
        props.put("ale.web.allowed-origin","https://inicio.test");
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("sintetico",props));
        ctx.register(Config.class);ctx.refresh();
        verificador=ctx.getBean(SeguridadHttpInterna.VerificadorSesionBearer.class);
        when(verificador.validar(anyString())).thenReturn(Optional.empty());
        mvc=MockMvcBuilders.webAppContextSetup(ctx)
                .addFilters(ctx.getBean("springSecurityFilterChain",Filter.class)).build();
    }
    static Map<String,Object> flags(boolean uc03,boolean uc43) {
        return Map.of("ale.http.writes.pedidos-uc03-enabled",uc03,
                "ale.http.writes.pedidos-uc43-enabled",uc43);
    }
    Cookie autorizar(String... roles) {
        var token="fixture-opaca";
        when(verificador.validar(token)).thenReturn(Optional.of(
            new SeguridadHttpInterna.VerificadorSesionBearer.SesionAutorizada(
                new SeguridadHttpInterna.PrincipalInterno(UUID.randomUUID(),9),Set.of(roles))));
        return new Cookie(SeguridadWebInterna.COOKIE_SESION,token);
    }
    ResultActions inicio(Cookie cookie) throws Exception {
        return mvc.perform(get("/web/inicio").secure(true).cookie(cookie))
                .andExpect(status().isOk()).andExpect(view().name("web-inicio"));
    }
    static void modelo(ResultActions result,boolean admin,boolean vendedor,boolean repartidor,
            boolean demo,boolean uc03,boolean uc43) throws Exception {
        boolean[] esperado={admin,vendedor,repartidor,demo,uc03,uc43};
        for(int i=0;i<ATRIBUTOS.size();i++)
            result.andExpect(model().attribute(ATRIBUTOS.get(i),esperado[i]));
    }
    @AfterEach void cerrar() {if(ctx!=null)ctx.close();}

    @ParameterizedTest @CsvSource({"false,false","true,false","false,true","true,true"})
    void vendedorRespetaCadaFlagIndependiente(boolean uc03,boolean uc43) throws Exception {
        iniciar("demo",flags(uc03,uc43));
        modelo(inicio(autorizar("VENDEDOR")),false,true,false,true,uc03,uc43);
    }
    @ParameterizedTest @CsvSource({
        "ADMINISTRADOR,true,false,false","REPARTIDOR,false,false,true",
        "RESPONSABLE_PRODUCCION,false,false,false","PROVEEDOR,false,false,false"})
    void otrosRolesNoHeredanVendedorNiEscrituras(String rol,boolean admin,boolean vendedor,
            boolean repartidor) throws Exception {
        iniciar("demo",flags(true,true));
        modelo(inicio(autorizar(rol)),admin,vendedor,repartidor,true,false,false);
    }
    @Test void autoridadesMultiplesSeConservanSinJerarquiaInventada() throws Exception {
        iniciar("demo",flags(true,true));
        modelo(inicio(autorizar("ADMINISTRADOR","VENDEDOR","REPARTIDOR")),true,true,true,true,true,true);
    }
    @Test void flagsAusentesSonFalseYNonProdCoincideConRutasActuales() throws Exception {
        iniciar("test",Map.of());
        modelo(inicio(autorizar("VENDEDOR")),false,true,false,true,false,false);
    }
    @ParameterizedTest @ValueSource(strings={"prod","demo,prod"})
    void prodDominaAunqueFlagsEstenTrue(String perfiles) throws Exception {
        // Slice MVC: no registra ni modifica el guard de arranque productivo.
        iniciar(perfiles,flags(true,true));
        modelo(inicio(autorizar("VENDEDOR")),false,true,false,false,false,false);
    }
    @Test void cambioPendienteNoRecibeNavegacionAunqueTengaRolesOperativos() throws Exception {
        iniciar("demo",flags(true,true));
        mvc.perform(get("/web/inicio").secure(true)
                .cookie(autorizar("VENDEDOR",SeguridadHttpInterna.ROL_CAMBIO_PENDIENTE)))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/web/cambiar-clave"))
            .andExpect(model().attributeDoesNotExist(ATRIBUTOS.toArray(String[]::new)));
    }
    @Test void parametrosYCookiesDePresentacionNoOtorganAutoridades() throws Exception {
        iniciar("demo",flags(true,true));
        var r=mvc.perform(get("/web/inicio").secure(true).cookie(autorizar("ADMINISTRADOR"),
                new Cookie("esVendedor","true")).param("esVendedor","true")
                .param("uc03Disponible","true").param("demoDisponible","true"))
            .andExpect(status().isOk());
        modelo(r,true,false,false,true,false,false);
    }
    @Test void anonimoCookieInvalidaYBearerNoSatisfacenAutenticacionWeb() throws Exception {
        iniciar("demo",flags(true,true));
        mvc.perform(get("/web/inicio").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/web/inicio").secure(true)
                .cookie(new Cookie(SeguridadWebInterna.COOKIE_SESION,"no-validada")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/web/inicio").secure(true).header("Authorization","Bearer fixture-opaca"))
                .andExpect(status().isUnauthorized());
    }
    @Test void repartidorNoObtieneBusquedaGeneralNiConsultasB3EnVistaIlustrativa() throws Exception {
        iniciar("demo",flags(true,true));
        var cookie=autorizar("REPARTIDOR");
        inicio(cookie);
        mvc.perform(get("/web/demo/pedidos").secure(true).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/web/demo/mis-entregas").secure(true).cookie(cookie))
                .andExpect(status().isOk()).andExpect(view().name("demo/mis-entregas"));
        verifyNoInteractions(ctx.getBean(BuscarPedidoService.class));
    }
    @Test void renovacionWebConservaCookieCsrfYControlDeOrigen() throws Exception {
        iniciar("demo",flags(true,true));
        var cookie=autorizar("VENDEDOR");
        var login=mvc.perform(get("/web/login").secure(true)).andReturn();
        var token=(org.springframework.security.web.csrf.CsrfToken)login.getRequest().getAttribute("_csrf");
        String csrf=token.getToken();
        var session=(org.springframework.mock.web.MockHttpSession)login.getRequest().getSession(false);
        when(ctx.getBean(SesionesUsuarioService.class).renovar(any(),eq(cookie.getValue())))
                .thenReturn(new SesionesUsuarioService.TokenEmitido("fixture-renovada","Bearer",1800,false));
        mvc.perform(post("/web/renew").secure(true).cookie(cookie).session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/web/renew").secure(true).cookie(cookie).session(session)
                .param("_csrf",csrf).header("Origin","https://externo.invalid"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ctx.getBean(SesionesUsuarioService.class));
        mvc.perform(post("/web/renew").secure(true).cookie(cookie).session(session)
                .param("_csrf",csrf).header("Origin","https://inicio.test"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/web/inicio"))
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("Secure")))
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("SameSite=Strict")));
        verify(ctx.getBean(SesionesUsuarioService.class)).renovar(any(),eq(cookie.getValue()));
    }
    @Test void sesionRevocadaSeRevalidaEnCadaSolicitud() throws Exception {
        iniciar("demo",flags(true,true));
        var cookie=autorizar("VENDEDOR");
        inicio(cookie);
        when(verificador.validar(cookie.getValue())).thenReturn(Optional.empty());
        mvc.perform(get("/web/inicio").secure(true).cookie(cookie)).andExpect(status().isUnauthorized());
    }
}
