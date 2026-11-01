package ar.edu.unam.alemilanesas.web;

import org.junit.jupiter.api.Test;
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
        assertDoesNotThrow(()->new PrecondicionesDespliegue(base()).run(null));
    }

    @Test void noAdmiteHttpNiOrigenMalFormado() {
        for (String origen : new String[]{
                "","http://ale.example.invalid","https://ale.example.invalid/carpeta",
                "https://user@ale.example.invalid","https://ale.example.invalid?x=1"}) {
            var env=base().withProperty("ale.web.allowed-origin",origen);
            var validador=new PrecondicionesDespliegue(env);
            assertThrows(IllegalStateException.class,()->validador.run(null));
        }
    }

    @Test void noAdmiteBackendPublicoNiEscalarSinLock() {
        var backendPublico=new PrecondicionesDespliegue(
                base().withProperty("server.address","0.0.0.0"));
        var sinLock=new PrecondicionesDespliegue(
                base().withProperty("ale.security.singleton-db-guard","false"));
        var proxyNoConfiable=new PrecondicionesDespliegue(
                base().withProperty("server.forward-headers-strategy","framework"));
        assertThrows(IllegalStateException.class,()->backendPublico.run(null));
        assertThrows(IllegalStateException.class,()->sinLock.run(null));
        assertThrows(IllegalStateException.class,()->proxyNoConfiable.run(null));
    }
}
