package ar.edu.unam.alemilanesas.web;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
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

/**
 * B4-01: sesión interna vinculada a token secreto de 256 bits.
 * La API NUNCA admite UUID de sesión, idUsuario ni roles desde parámetros HTTP.
 * tokenProtegido = SHA-256(bytes originales del token Base64URL canónico).
 * El alta/entrega/rotación del token se reserva para UC54; no hay login nuevo.
 */
@Configuration
public class SeguridadHttpInterna {
    @Bean
    @Order(1)
    SecurityFilterChain seguridad(HttpSecurity http, VerificadorSesionBearer sesiones) {
        // La aplicación ya posee un SecurityFilterChain fail-closed para las
        // demás rutas en bootstrap.SecurityConfiguration. Esta cadena solo
        // cubre la API interna y se evalúa ANTES de la cadena global.
        http.securityMatcher("/api/v1/**");
        // B4-01 habilita solo GET: mantener CSRF activo por defecto.
        // B4-02B introducirá POST Bearer sin cookies en una cadena separada.
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(a->a
                .requestMatchers(HttpMethod.GET, "/api/v1/clientes")
                    .hasAnyAuthority("ADMINISTRADOR","VENDEDOR")
                .requestMatchers(HttpMethod.GET,"/api/v1/insumos","/api/v1/unidades")
                    .hasAnyAuthority("ADMINISTRADOR","RESPONSABLE_PRODUCCION")
                .anyRequest().denyAll());
        http.exceptionHandling(e->e
                .authenticationEntryPoint((req,res,ex)->rechazar(res))
                .accessDeniedHandler((req,res,ex)->res.sendError(HttpServletResponse.SC_FORBIDDEN)));
        // No es un Filter @Component registrado por el contenedor: se ejecuta
        // una única vez dentro del SecurityFilterChain, nunca como servlet filter.
        http.addFilterBefore(new FiltroSesionBearer(sesiones), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void rechazar(HttpServletResponse res) throws IOException {
        res.setHeader("WWW-Authenticate","Bearer realm=\"ale-interno\"");
        res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    public record PrincipalInterno(UUID idSesion, long idUsuario) {}

    private static final class FiltroSesionBearer extends OncePerRequestFilter {
        private final VerificadorSesionBearer sesiones;
        private FiltroSesionBearer(VerificadorSesionBearer sesiones) {this.sesiones=sesiones;}

        @Override
        protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
                                        FilterChain chain) throws ServletException,IOException {
            String header=request.getHeader("Authorization");
            if(header!=null) {
                if(!header.startsWith("Bearer ") || header.length()<=7) {
                    rechazar(response);return;
                }
                var autenticado=sesiones.validar(header.substring(7));
                if(autenticado.isEmpty()) {
                    rechazar(response);return;
                }
                var sesion=autenticado.get();
                var auth=new UsernamePasswordAuthenticationToken(sesion.principal(),null,
                        sesion.roles().stream().map(SimpleGrantedAuthority::new).toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            try {chain.doFilter(request,response);}
            finally {SecurityContextHolder.clearContext();}
        }
    }

    @Component
    public static class VerificadorSesionBearer {
        private final NamedParameterJdbcTemplate sql;
        public VerificadorSesionBearer(NamedParameterJdbcTemplate sql) {this.sql=sql;}
        public record SesionAutorizada(PrincipalInterno principal,Set<String> roles) {}

        public Optional<SesionAutorizada> validar(String token) {
            if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))return Optional.empty();
            byte[] secreto;
            try {secreto=Base64.getUrlDecoder().decode(token);}
            catch(IllegalArgumentException ex) {return Optional.empty();}
            if(secreto.length!=32 ||
               !Base64.getUrlEncoder().withoutPadding().encodeToString(secreto).equals(token))
                return Optional.empty();
            byte[] huella;
            try {huella=MessageDigest.getInstance("SHA-256").digest(secreto);}
            catch(NoSuchAlgorithmException e) {throw new IllegalStateException("SHA-256 no disponible",e);}
            // Una validación completa por request: revocación, vencimiento,
            // usuario habilitado, credencial no pendiente y roles vigentes.
            List<Map<String,Object>> filas=sql.queryForList("""
                    SELECT s."idSesionUsuario",u."idUsuario",ur."codigoRol"
                      FROM "SESION_USUARIO" s
                      JOIN "USUARIO" u ON u."idUsuario"=s."idUsuario"
                      JOIN "USUARIO_ROL" ur ON ur."idUsuario"=u."idUsuario"
                     WHERE s."tokenProtegido"=:huella
                       AND s."fechaHoraInicio"<=clock_timestamp()
                       AND s."fechaHoraExpiracion">clock_timestamp()
                       AND s."fechaHoraRevocacion" IS NULL
                       AND u."estado"='HABILITADO'
                       AND u."cambioCredencialPendiente"=false
                    """,new MapSqlParameterSource("huella",huella));
            if(filas.isEmpty())return Optional.empty();
            Map<String,Object> primera=filas.getFirst();
            UUID id=(UUID)primera.get("idSesionUsuario");
            long actor=((Number)primera.get("idUsuario")).longValue();
            Set<String> roles=filas.stream().map(f->(String)f.get("codigoRol"))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return Optional.of(new SesionAutorizada(new PrincipalInterno(id,actor),roles));
        }
    }
}
