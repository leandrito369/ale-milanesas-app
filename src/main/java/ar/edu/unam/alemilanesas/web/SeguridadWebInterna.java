package ar.edu.unam.alemilanesas.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * B4-02C: canal Thymeleaf cookie HttpOnly de la MISMA sesion MR5.
 * Un HttpSession puede conservar solamente el token CSRF, nunca identidad/roles
 * ni el secreto de acceso. Cada request usa el digest en PostgreSQL.
 */
@Configuration
public class SeguridadWebInterna {
    public static final String COOKIE_SESION = "__Host-ale_session";

    @Bean
    @Order(2)
    SecurityFilterChain seguridadWeb(HttpSecurity http,
                                     SeguridadHttpInterna.VerificadorSesionBearer sesiones)
            throws Exception {
        http.securityMatcher("/web/**");
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        // CSRF habilitado por defecto: incluso login/logout/rotacion/UC53.
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        http.authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/web/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/web/login").permitAll()
                .anyRequest().authenticated());
        http.exceptionHandling(e -> e
                .authenticationEntryPoint((req,resp,ex)->resp.sendError(401))
                .accessDeniedHandler((req,resp,ex)->resp.sendError(403)));
        http.addFilterBefore(new SesionCookieFilter(sesiones),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static String tokenCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        String token = null;
        for (Cookie cookie : cookies) {
            if (COOKIE_SESION.equals(cookie.getName())) {
                if (token != null) return null; // cookie ambigua: fail-closed
                token = cookie.getValue();
            }
        }
        return token;
    }

    private static final class SesionCookieFilter extends OncePerRequestFilter {
        private final SeguridadHttpInterna.VerificadorSesionBearer verificador;

        SesionCookieFilter(SeguridadHttpInterna.VerificadorSesionBearer verificador) {
            this.verificador=verificador;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,
                                        FilterChain chain) throws ServletException, IOException {
            res.setHeader("Cache-Control","no-store");
            res.setHeader("Pragma","no-cache");
            String token=tokenCookie(req);
            if (token!=null) {
                var sesion=verificador.validar(token);
                if (sesion.isPresent()) {
                    var valor=sesion.get();
                    var auth=new UsernamePasswordAuthenticationToken(
                            valor.principal(),null,
                            valor.roles().stream().map(SimpleGrantedAuthority::new).toList());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
            try { chain.doFilter(req,res); }
            finally { SecurityContextHolder.clearContext(); }
        }
    }
}
