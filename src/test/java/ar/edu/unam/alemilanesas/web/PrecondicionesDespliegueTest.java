package ar.edu.unam.alemilanesas.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** No confundir configuracion declarada con TLS realmente desplegado. */
class PrecondicionesDespliegueTest {
    private MockEnvironment base() {
        return new MockEnvironment()
                .withProperty("server.address","127.0.0.1")
                .withProperty("server.forward-headers-strategy","native")
                .withProperty("ale.security.singleton-db-guard","true")
                .withProperty("ale.web.allowed-origin","https://ale.example.invalid");
    }

    @Test void configuracionBienFormadaPermiteInicioDeEtapaOperativa() {
        assertDoesNotThrow(()->new PrecondicionesDespliegue(base()).postProcessBeanFactory(null));
    }

    @Test void noAdmiteHttpNiOrigenMalFormado() {
        for (String origen : new String[]{
                "","http://ale.example.invalid","https://ale.example.invalid/carpeta",
                "https://user@ale.example.invalid","https://ale.example.invalid?x=1"}) {
            var env=base().withProperty("ale.web.allowed-origin",origen);
            var validador=new PrecondicionesDespliegue(env);
            assertThrows(IllegalStateException.class,()->validador.postProcessBeanFactory(null));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"ale.http.writes.compras-uc08-insumo-enabled",
            "ale.http.writes.clientes-uc02-enabled",
            "ale.http.writes.clientes-uc32-enabled",
            "ale.http.writes.clientes-uc38-enabled",
            "ale.http.writes.productos-uc30-enabled",
            "ale.http.writes.productos-uc47-enabled",
            "ale.http.writes.productos-uc48-enabled",
            "ale.http.writes.usuarios-uc17-enabled",
            "ale.http.writes.usuarios-uc18-enabled",
            "ale.http.writes.usuarios-uc55-enabled"})
    void rechazaCualquierEscrituraExperimentalEnProduccion(String propiedad) {
        var validador=new PrecondicionesDespliegue(base().withProperty(propiedad,"true"));
        assertThrows(IllegalStateException.class,
                ()->validador.postProcessBeanFactory(null));
    }

    @Test void noAdmiteBackendPublicoNiEscalarSinLock() {
        var backendPublico=new PrecondicionesDespliegue(
                base().withProperty("server.address","0.0.0.0"));
        var sinLock=new PrecondicionesDespliegue(
                base().withProperty("ale.security.singleton-db-guard","false"));
        var proxyNoConfiable=new PrecondicionesDespliegue(
                base().withProperty("server.forward-headers-strategy","framework"));
        assertThrows(IllegalStateException.class,()->backendPublico.postProcessBeanFactory(null));
        assertThrows(IllegalStateException.class,()->sinLock.postProcessBeanFactory(null));
        assertThrows(IllegalStateException.class,()->proxyNoConfiable.postProcessBeanFactory(null));
    }

    @Test void controladorUc30NoExisteEnProdAunqueSeSoliciteSuBandera() {
        try(var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("flag-test",
                        java.util.Map.of("ale.http.writes.productos-uc30-enabled","true")));
            ctx.register(RegistroProductoHttpController.class);
            ctx.refresh();
            assertTrue(ctx.getBeansOfType(RegistroProductoHttpController.class).isEmpty());
        }
    }

    @Test void controladorUc17NoExisteEnProdAunqueSeSoliciteSuBandera() {
        try(var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("flag-uc17",
                        java.util.Map.of("ale.http.writes.usuarios-uc17-enabled","true")));
            ctx.register(RegistroUsuarioHttpController.class);
            ctx.refresh();
            assertTrue(ctx.getBeansOfType(RegistroUsuarioHttpController.class).isEmpty());
        }
    }

    @Test void controladorUc18NoExisteEnProdAunqueSeSoliciteSuBandera() {
        try(var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("flag-uc18",
                        java.util.Map.of("ale.http.writes.usuarios-uc18-enabled","true")));
            ctx.register(GestionRolesHttpController.class);
            ctx.refresh();
            assertTrue(ctx.getBeansOfType(GestionRolesHttpController.class).isEmpty());
        }
    }

    @Test void controladorUc55NoExisteEnProdAunqueSeSoliciteSuBandera() {
        try(var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("prod");
            ctx.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("flag-uc55",
                        java.util.Map.of("ale.http.writes.usuarios-uc55-enabled","true")));
            ctx.register(EstadoUsuarioHttpController.class);
            ctx.refresh();
            assertTrue(ctx.getBeansOfType(EstadoUsuarioHttpController.class).isEmpty());
        }
    }
}
