package ar.edu.unam.alemilanesas.web;

import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Registro real por component scan y refresh, sin servidor, DB ni servicios externos. */
class PrecondicionesDespliegueContextTest {
    @Configuration(proxyBeanMethods=false)
    @ComponentScan(basePackageClasses=PrecondicionesDespliegue.class,useDefaultFilters=false,
        includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes=PrecondicionesDespliegue.class))
    static class GuardConfig {}
    static Map<String,Object> base() {
        return new HashMap<>(Map.of("server.address","127.0.0.1",
            "server.forward-headers-strategy","native","ale.security.singleton-db-guard","true",
            "ale.web.allowed-origin","https://ale.example.invalid"));
    }
    static AnnotationConfigApplicationContext context(String profile,Map<String,Object> props) {
        var ctx=new AnnotationConfigApplicationContext();
        ctx.getEnvironment().setActiveProfiles(profile);
        // Evitar que propiedades del host suministren silenciosamente las claves de estos casos.
        ctx.getEnvironment().getPropertySources().remove("systemProperties");
        ctx.getEnvironment().getPropertySources().remove("systemEnvironment");
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic",props));
        ctx.register(GuardConfig.class);
        return ctx;
    }
    static void rejects(Map<String,Object> props) {
        try(var ctx=context("prod",props)) {
            Throwable error=assertThrows(RuntimeException.class,ctx::refresh);
            assertInstanceOf(IllegalStateException.class,error,
                "Debe rechazar por precondición, no por defecto de instanciación");
            assertFalse(ctx.isActive());
        }
    }
    @Test void configuracionValidaInstanciaGuardYEjecutaRefresh() {
        try(var ctx=context("prod",base())) {
            assertDoesNotThrow(ctx::refresh);
            assertTrue(ctx.isActive());
            assertEquals(1,ctx.getBeansOfType(PrecondicionesDespliegue.class).size());
        }
    }
    @Test void demoNoRegistraGuardAunqueConfiguracionSeaInvalidaParaProd() {
        try(var ctx=context("demo",Map.of("ale.http.writes.pedidos-uc03-enabled","true"))) {
            assertDoesNotThrow(ctx::refresh);
            assertTrue(ctx.getBeansOfType(PrecondicionesDespliegue.class).isEmpty());
        }
    }
    @ParameterizedTest
    @ValueSource(strings={"clientes-uc02","clientes-uc32","clientes-uc38","productos-uc30",
        "productos-uc47","productos-uc48","usuarios-uc17","usuarios-uc18","usuarios-uc55",
        "compras-uc08-insumo","compras-uc08-reventa","compras-uc42","precios-uc19",
        "categorias-uc57","insumos-uc50","insumos-uc51","insumos-uc52",
        "pedidos-uc03","pedidos-uc43","recetas-uc10","preparaciones-uc28","entregas-uc05"})
    void cadaBanderaProhibidaAbortaContexto(String flag) {
        var props=base();props.put("ale.http.writes."+flag+"-enabled","true");rejects(props);
    }
    @ParameterizedTest
    @ValueSource(strings={"http://ale.example.invalid","https://ale.example.invalid/ruta",
        "https://usuario@ale.example.invalid","https://ale.example.invalid?x=1","https://",
        "https://ale.example.invalid#fragmento",""})
    void origenInvalidoAbortaContexto(String origen) {
        var props=base();props.put("ale.web.allowed-origin",origen);rejects(props);
    }
    static Stream<Arguments> aislamiento() {
        return Stream.of(Arguments.of("server.address","0.0.0.0"),
            Arguments.of("ale.security.singleton-db-guard","false"),
            Arguments.of("server.forward-headers-strategy","framework"));
    }
    @ParameterizedTest @MethodSource("aislamiento")
    void aislamientoInvalidoAbortaContexto(String key,String value) {
        var props=base();props.put(key,value);rejects(props);
    }
    @ParameterizedTest
    @ValueSource(strings={"server.address","server.forward-headers-strategy",
        "ale.security.singleton-db-guard","ale.web.allowed-origin"})
    void propiedadObligatoriaAusenteAbortaContexto(String key) {
        var props=base();props.remove(key);rejects(props);
    }
}
