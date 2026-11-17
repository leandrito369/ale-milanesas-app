package ar.edu.unam.alemilanesas.web;

import jakarta.servlet.Filter;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.ViewResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Renderizado Thymeleaf real con las cadenas de seguridad; no arranca el guard prod. */
class AccesoPlantillasMvcTest {
    @Configuration(proxyBeanMethods=false)
    static class Vistas {
        @Bean ViewResolver vistas() {
            var templates=new ClassLoaderTemplateResolver();
            templates.setPrefix("templates/");templates.setSuffix(".html");
            templates.setTemplateMode("HTML");templates.setCharacterEncoding("UTF-8");
            var engine=new SpringTemplateEngine();engine.setTemplateResolver(templates);
            var resolver=new ThymeleafViewResolver();resolver.setTemplateEngine(engine);
            resolver.setCharacterEncoding("UTF-8");return resolver;
        }
    }
    private static void guardarVista(org.springframework.test.web.servlet.MvcResult result,
            String perfiles,String vista)throws Exception {
        String dir=System.getProperty("f02.visual-dir");
        if(dir==null || !perfiles.equals("demo"))return;
        // Evidencia visual opcional sin tokens; no se utiliza para enviar formularios.
        String html=result.getResponse().getContentAsString()
                .replaceAll("name=\"_csrf\" value=\"[^\"]+\"", "name=\"_csrf\" value=\"REDACTED\"")
                .replace("href=\"/demo/demo.css\"", "href=\"demo.css\"");
        java.nio.file.Files.writeString(java.nio.file.Path.of(dir,vista+".html"),html);
    }
    @ParameterizedTest @ValueSource(strings={"demo","test","prod","demo,prod"})
    void cssSegunPerfilYFormulariosRealesConCsrf(String perfiles)throws Exception {
        var f=new InicioModeloMvcTest();
        try {
            f.ctx=new AnnotationConfigWebApplicationContext();
            f.ctx.setServletContext(new MockServletContext());
            f.ctx.getEnvironment().setActiveProfiles(perfiles.split(","));
            f.ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("sintetico",
                    Map.of("ale.web.allowed-origin","https://inicio.test")));
            f.ctx.register(InicioModeloMvcTest.Config.class,Vistas.class);f.ctx.refresh();
            f.verificador=f.ctx.getBean(SeguridadHttpInterna.VerificadorSesionBearer.class);
            f.mvc=MockMvcBuilders.webAppContextSetup(f.ctx)
                    .addFilters(f.ctx.getBean("springSecurityFilterChain",Filter.class)).build();
            boolean demo=!perfiles.contains("prod");
            var login=f.mvc.perform(get("/web/login").secure(true).param("demoDisponible",String.valueOf(!demo)))
                    .andExpect(status().isOk()).andExpect(model().attribute("demoDisponible",demo));
            login.andExpect(content().string(demo?containsString("href=\"/demo/demo.css\""):not(containsString("/demo/demo.css"))))
                    .andExpect(content().string(containsString("action=\"/web/login\" method=\"post\"")))
                    .andExpect(content().string(matchesPattern("(?s).*name=\"_csrf\" value=\"[^\"]+\".*")))
                    .andExpect(content().string(containsString("for=\"credencial\"")));
            guardarVista(login.andReturn(),perfiles,"login");
            for(String role:new String[]{"VENDEDOR",SeguridadHttpInterna.ROL_CAMBIO_PENDIENTE}) {
                var cambio=f.mvc.perform(get("/web/cambiar-clave").secure(true).cookie(f.autorizar(role)))
                    .andExpect(status().isOk()).andExpect(model().attribute("demoDisponible",demo))
                    .andExpect(content().string(demo?containsString("href=\"/demo/demo.css\""):not(containsString("/demo/demo.css"))))
                    .andExpect(content().string(containsString("action=\"/web/cambiar-clave\" method=\"post\"")))
                    .andExpect(content().string(containsString("name=\"credencialActual\"")))
                    .andExpect(content().string(containsString("name=\"nuevaCredencial\"")))
                    .andExpect(content().string(containsString("name=\"confirmacionNuevaCredencial\"")))
                    .andExpect(content().string(matchesPattern("(?s).*name=\"_csrf\" value=\"[^\"]+\".*")));
                guardarVista(cambio.andReturn(),perfiles,"cambiar-clave");
            }
            f.mvc.perform(get("/web/login").param("error","").secure(true))
                    .andExpect(content().string(containsString("role=\"alert\">No se pudo iniciar sesión.</p>")));
            f.mvc.perform(get("/web/login").param("cambiada","").secure(true))
                    .andExpect(content().string(containsString("role=\"status\">Credencial cambiada. Iniciá sesión nuevamente.</p>")));
            f.mvc.perform(post("/web/login").secure(true)).andExpect(status().isForbidden());
            f.mvc.perform(post("/web/cambiar-clave").secure(true).cookie(f.autorizar("VENDEDOR")))
                    .andExpect(status().isForbidden());
        } finally {f.cerrar();}
    }
}
