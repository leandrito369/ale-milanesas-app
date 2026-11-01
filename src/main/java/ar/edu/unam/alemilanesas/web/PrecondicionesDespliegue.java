package ar.edu.unam.alemilanesas.web;

import java.net.URI;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * El perfil prod no puede operar sin origen publico HTTPS y aislamiento
 * del puerto backend o sin lock singleton. No sustituye evidencia de red.
 */
@Component
@Profile("prod")
public class PrecondicionesDespliegue implements ApplicationRunner {
    private final Environment env;

    public PrecondicionesDespliegue(Environment env) {this.env=env;}

    @Override
    public void run(ApplicationArguments args) {
        String origen=env.getProperty("ale.web.allowed-origin","");
        URI parsed;
        try {parsed=URI.create(origen);}
        catch (IllegalArgumentException ex) {
            throw new IllegalStateException("ALE_PUBLIC_ORIGIN debe ser HTTPS",ex);
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.getHost()==null
                || parsed.getUserInfo()!=null
                || parsed.getRawQuery()!=null
                || parsed.getRawFragment()!=null
                || parsed.getRawPath()!=null && !parsed.getRawPath().isEmpty()) {
            throw new IllegalStateException("ALE_PUBLIC_ORIGIN debe ser origen HTTPS sin ruta");
        }
        if (!"127.0.0.1".equals(env.getProperty("server.address",""))
                || !Boolean.TRUE.equals(env.getProperty("ale.security.singleton-db-guard",Boolean.class))
                || !"native".equalsIgnoreCase(env.getProperty("server.forward-headers-strategy",""))) {
            throw new IllegalStateException("Aislamiento del backend y singleton requeridos");
        }
    }
}
