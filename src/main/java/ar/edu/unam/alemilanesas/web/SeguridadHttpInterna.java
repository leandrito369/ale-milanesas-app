package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** B4-02B: API SOLO Bearer. UC01 emite, UC53 permite sesion restringida. */
@Configuration
public class SeguridadHttpInterna {
    public static final String ROL_CAMBIO_PENDIENTE = "UC53_RESTRINGIDO";
    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";

    @Bean
    @Order(1)
    // S4502 validado: API no acepta cookie, solo Authorization Bearer.
    // Cadena /web/** conserva CSRF y prueba negativa de cookies en API.
    @SuppressWarnings("java:S4502")
    SecurityFilterChain seguridad(HttpSecurity http, VerificadorSesionBearer sesiones,
            @Value("${ale.http.writes.clientes-uc02-enabled:false}") boolean habilitarAltaCliente) {
        http.securityMatcher("/api/v1/**");
        // La API no autentica cookies. El sitio web (otra cadena) conserva CSRF.
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/**"));
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(a->a
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/password/change").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/renew",
                        "/api/v1/auth/logout").access((auth,context) ->
                            new org.springframework.security.authorization.AuthorizationDecision(
                                auth.get().isAuthenticated() &&
                                auth.get().getAuthorities().stream().noneMatch(
                                    x->ROL_CAMBIO_PENDIENTE.equals(x.getAuthority()))))
                .requestMatchers(HttpMethod.POST, "/api/v1/usuarios/*/restablecer-acceso")
                    .hasAuthority(ROL_ADMINISTRADOR)
                // B4-03-01: flag falso por defecto; no hay autorizaciones
                // implícitas para ningún otro POST comercial.
                .requestMatchers(HttpMethod.POST, "/api/v1/clientes")
                    .access((auth,context) ->
                        new org.springframework.security.authorization.AuthorizationDecision(
                            habilitarAltaCliente && auth.get().isAuthenticated() &&
                            auth.get().getAuthorities().stream().anyMatch(
                                rol -> ROL_ADMINISTRADOR.equals(rol.getAuthority()) ||
                                       "VENDEDOR".equals(rol.getAuthority()))))
                .requestMatchers(HttpMethod.GET, "/api/v1/clientes")
                    .hasAnyAuthority(ROL_ADMINISTRADOR,"VENDEDOR")
                .requestMatchers(HttpMethod.GET,"/api/v1/insumos","/api/v1/unidades")
                    .hasAnyAuthority(ROL_ADMINISTRADOR,"RESPONSABLE_PRODUCCION")
                .anyRequest().denyAll());
        http.exceptionHandling(e->e
                .authenticationEntryPoint((req,res,ex)->rechazar(res))
                .accessDeniedHandler((req,res,ex)->error(res,403,"OPERACION_PROHIBIDA")));
        http.addFilterBefore(new FiltroSesionBearer(sesiones),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static void rechazar(HttpServletResponse res) throws IOException {
        res.setHeader("WWW-Authenticate","Bearer realm=\"ale-interno\"");
        error(res,401,"SESION_INVALIDA");
    }

    static void error(HttpServletResponse res,int status,String codigo) throws IOException {
        res.setStatus(status);
        res.setHeader("Cache-Control","no-store");
        res.setHeader("Pragma","no-cache");
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json");
        res.getWriter().write("{\"codigo\":\""+codigo+"\"}");
    }

    public record PrincipalInterno(UUID idSesion, long idUsuario) {}

    private static final class FiltroSesionBearer extends OncePerRequestFilter {
        private final VerificadorSesionBearer sesiones;
        private FiltroSesionBearer(VerificadorSesionBearer sesiones){this.sesiones=sesiones;}

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                        FilterChain chain) throws ServletException,IOException {
            res.setHeader("Cache-Control","no-store");
            res.setHeader("Pragma","no-cache");
            String header=req.getHeader("Authorization");
            if (header!=null) {
                if (!header.startsWith("Bearer ") || header.length()>1024
                        || header.length()<=7) {
                    rechazar(res); return;
                }
                Optional<VerificadorSesionBearer.SesionAutorizada> sesion;
                try {
                    sesion=sesiones.validar(header.substring(7));
                } catch (DataAccessException ex) {
                    error(res,503,"AUTENTICACION_NO_DISPONIBLE");
                    return;
                }
                if (sesion.isEmpty()) { rechazar(res); return; }
                var s=sesion.get();
                var auth=new UsernamePasswordAuthenticationToken(s.principal(),null,
                    s.roles().stream().map(SimpleGrantedAuthority::new).toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            try { chain.doFilter(req,res); }
            finally { SecurityContextHolder.clearContext(); }
        }
    }

    @Component
    public static class VerificadorSesionBearer {
        private final NamedParameterJdbcTemplate sql;
        public VerificadorSesionBearer(NamedParameterJdbcTemplate sql){this.sql=sql;}
        public record SesionAutorizada(PrincipalInterno principal,Set<String> roles) {}

        public Optional<SesionAutorizada> validar(String token) {
            var huella=TokenSesionInterna.huella(token);
            if (huella.isEmpty()) return Optional.empty();
            List<Map<String,Object>> filas=sql.queryForList("""
                    SELECT s."idSesionUsuario",u."idUsuario",
                           u."cambioCredencialPendiente",ur."codigoRol"
                      FROM "SESION_USUARIO" s
                      JOIN "USUARIO" u ON u."idUsuario"=s."idUsuario"
                      JOIN "USUARIO_ROL" ur ON ur."idUsuario"=u."idUsuario"
                     WHERE s."tokenProtegido"=:huella
                       AND s."fechaHoraInicio"<=clock_timestamp()
                       AND s."fechaHoraExpiracion">clock_timestamp()
                       AND s."fechaHoraInicio"+interval '8 hours'>clock_timestamp()
                       AND s."fechaHoraRevocacion" IS NULL
                       AND u."estado"='HABILITADO'
                       AND (
                         NOT EXISTS (SELECT 1 FROM "USUARIO_ROL" r
                                      WHERE r."idUsuario"=u."idUsuario"
                                        AND r."codigoRol"='PROVEEDOR')
                         OR EXISTS (SELECT 1 FROM "USUARIO_PROVEEDOR" up
                                      JOIN "PROVEEDOR" p ON p."idProveedor"=up."idProveedor"
                                     WHERE up."idUsuario"=u."idUsuario"
                                       AND p."estado"='ACTIVO')
                       )
                    """,new MapSqlParameterSource("huella",huella.get()));
            if (filas.isEmpty()) return Optional.empty();
            var primero=filas.getFirst();
            var id=(UUID)primero.get("idSesionUsuario");
            var actor=((Number)primero.get("idUsuario")).longValue();
            boolean pendiente=Boolean.TRUE.equals(primero.get("cambioCredencialPendiente"));
            Set<String> roles=pendiente ? Set.of(ROL_CAMBIO_PENDIENTE) :
                filas.stream().map(f->(String)f.get("codigoRol"))
                     .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return Optional.of(new SesionAutorizada(new PrincipalInterno(id,actor),roles));
        }
    }
}
