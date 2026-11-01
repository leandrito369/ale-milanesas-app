package ar.edu.unam.alemilanesas.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
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
                                     SeguridadHttpInterna.VerificadorSesionBearer sesiones,
                                     @Value("${ale.web.allowed-origin:}") String origenPermitido) {
        http.securityMatcher("/web/**");
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        // CSRF habilitado por defecto: incluso login/logout/rotacion/UC53.
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        // HttpSession almacena CSRF, pero NUNCA Authentication. La autoridad
        // solo proviene de SESION_USUARIO revalidada contra PostgreSQL en cada request.
        http.securityContext(c -> c.securityContextRepository(
                new NullSecurityContextRepository()));
        http.authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/web/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/web/login").permitAll()
                .anyRequest().authenticated());
        http.exceptionHandling(e -> e
                .authenticationEntryPoint((req,resp,ex)->resp.sendError(401))
                .accessDeniedHandler((req,resp,ex)->{
                    var actual=SecurityContextHolder.getContext().getAuthentication();
                    if (ex instanceof CsrfException) {
                        resp.sendError(403);
                    } else if (actual==null || actual instanceof AnonymousAuthenticationToken) {
                        resp.sendError(401);
                    } else {
                        resp.sendError(403);
                    }
                }));
        http.addFilterBefore(new FiltroOrigen(origenPermitido),
                UsernamePasswordAuthenticationFilter.class);
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

    /**
     * Rechazo de solicitudes mutantes cross-origin incluso si traen un CSRF válido.
     * Origen autorizado configurable por despliegue; no confiar en X-Forwarded-Host.
     */
    private static final class FiltroOrigen extends OncePerRequestFilter {
        private final String origenConfigurado;

        private FiltroOrigen(String origenConfigurado) {
            this.origenConfigurado = origenConfigurado;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,
                                        FilterChain chain) throws ServletException,IOException {
            if (muta(req.getMethod()) && origenInvalido(req)) {
                res.sendError(403);
                return;
            }
            res.setHeader("Content-Security-Policy",
                    "default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
            res.setHeader("X-Content-Type-Options","nosniff");
            chain.doFilter(req,res);
        }

        private static boolean muta(String metodo) {
            return "POST".equals(metodo) || "PUT".equals(metodo)
                    || "PATCH".equals(metodo) || "DELETE".equals(metodo);
        }

        private boolean origenInvalido(HttpServletRequest req) {
            String origen=req.getHeader("Origin");
            if (origen==null) return false;
            String esperado=origenConfigurado.isBlank()
                    ? req.getScheme()+"://"+req.getServerName()+":"+req.getServerPort()
                    : origenConfigurado;
            try {
                URI recibido=URI.create(origen);
                URI confiable=URI.create(esperado);
                return !mismoOrigen(recibido,confiable);
            } catch (RuntimeException ex) {
                return true;
            }
        }

        private static boolean mismoOrigen(URI recibido,URI esperado) {
            if (recibido.getHost()==null || esperado.getHost()==null) return false;
            if (recibido.getUserInfo()!=null || recibido.getRawQuery()!=null
                    || recibido.getRawFragment()!=null || recibido.getRawPath()!=null
                    && !recibido.getRawPath().isEmpty()) return false;
            return esperado.getScheme().equalsIgnoreCase(recibido.getScheme())
                    && esperado.getHost().equalsIgnoreCase(recibido.getHost())
                    && puerto(esperado)==puerto(recibido);
        }

        private static int puerto(URI uri) {
            if (uri.getPort()!=-1) return uri.getPort();
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }

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
                java.util.Optional<SeguridadHttpInterna.VerificadorSesionBearer.SesionAutorizada> sesion;
                try {
                    sesion=verificador.validar(token);
                } catch (DataAccessException ex) {
                    res.sendError(503);
                    return;
                }
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
