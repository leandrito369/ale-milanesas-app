package ar.edu.unam.alemilanesas.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cierre por defecto (fail-closed) hasta implementar la autenticación y RBAC
 * por operación de G0. No se crea usuario de prueba ni API de negocio pública.
 */
@Configuration
public class SecurityConfiguration {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/", "/actuator/health").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
