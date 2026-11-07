package ar.edu.unam.alemilanesas.web;

import java.net.URI;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * El perfil prod no puede operar sin origen publico HTTPS y aislamiento
 * del puerto backend o sin lock singleton. Se valida antes de arrancar Tomcat.
 * No sustituye evidencia de red.
 */
@Component
@Profile("prod")
public class PrecondicionesDespliegue implements BeanFactoryPostProcessor {
    private final Environment env;

    public PrecondicionesDespliegue(Environment env) {this.env=env;}

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Ningún perfil prod puede habilitar una escritura comercial experimental,
        // aunque la variable de entorno de feature flag haya sido configurada.
        if (Boolean.TRUE.equals(env.getProperty(
                "ale.http.writes.clientes-uc02-enabled",Boolean.class))) {
            throw new IllegalStateException("UC02 HTTP bloqueado en producción");
        }
        if (Boolean.TRUE.equals(env.getProperty(
                "ale.http.writes.clientes-uc32-enabled",Boolean.class))) {
            throw new IllegalStateException("UC32 HTTP bloqueado en producción");
        }
        if (Boolean.TRUE.equals(env.getProperty(
                "ale.http.writes.clientes-uc38-enabled",Boolean.class))) {
            throw new IllegalStateException("UC38 HTTP bloqueado en producción");
        }
        if (Boolean.TRUE.equals(env.getProperty(
                "ale.http.writes.productos-uc30-enabled",Boolean.class))) {
            throw new IllegalStateException("UC30 HTTP bloqueado en produccion");
        }
        if (Boolean.TRUE.equals(env.getProperty(
                "ale.http.writes.productos-uc47-enabled",Boolean.class))) {
            throw new IllegalStateException("UC47 HTTP bloqueado en produccion");
        }
        if (Boolean.TRUE.equals(env.getProperty(
                "ale.http.writes.productos-uc48-enabled",Boolean.class))) {
            throw new IllegalStateException("UC48 HTTP bloqueado en produccion");
        }
        if (Boolean.TRUE.equals(env.getProperty(
                "ale.http.writes.usuarios-uc17-enabled",Boolean.class))) {
            throw new IllegalStateException("UC17 HTTP bloqueado en produccion");
        }
        if (Boolean.TRUE.equals(env.getProperty(
                "ale.http.writes.usuarios-uc18-enabled",Boolean.class))) {
            throw new IllegalStateException("UC18 HTTP bloqueado en producción");
        }
        if (Boolean.TRUE.equals(env.getProperty(
                "ale.http.writes.usuarios-uc55-enabled",Boolean.class))) {
            throw new IllegalStateException("UC55 HTTP bloqueado en producción");
        }
        if (Boolean.TRUE.equals(env.getProperty(
                "ale.http.writes.compras-uc08-insumo-enabled",Boolean.class))) {
            throw new IllegalStateException("UC08 INSUMO HTTP bloqueado en produccion");
        }
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
