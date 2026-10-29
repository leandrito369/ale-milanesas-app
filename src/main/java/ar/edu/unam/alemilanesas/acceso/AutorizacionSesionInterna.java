package ar.edu.unam.alemilanesas.acceso;

import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Control interno transversal: comprueba sesión en DB, rol nominal y clave pendiente.
 * NO sustituye validación criptográfica del token ni autenticación HTTP (B4).
 */
@Component
public class AutorizacionSesionInterna {
    private final NamedParameterJdbcTemplate sql;

    public AutorizacionSesionInterna(NamedParameterJdbcTemplate sql) {
        this.sql = sql;
    }

    public void exigirRoles(UUID idSesionUsuario, Set<String> rolesAdmitidos) {
        if (idSesionUsuario == null || rolesAdmitidos == null || rolesAdmitidos.isEmpty()) {
            throw new SecurityException("Operación no autorizada");
        }
        var params = new MapSqlParameterSource()
                .addValue("sesion", idSesionUsuario)
                .addValue("roles", rolesAdmitidos);
        Integer coincidencias = sql.queryForObject("""
                SELECT count(DISTINCT ur."codigoRol")
                  FROM "SESION_USUARIO" s
                  JOIN "USUARIO" u ON u."idUsuario" = s."idUsuario"
                  JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
                 WHERE s."idSesionUsuario" = :sesion
                   AND s."fechaHoraInicio" <= clock_timestamp()
                   AND s."fechaHoraExpiracion" > clock_timestamp()
                   AND s."fechaHoraRevocacion" IS NULL
                   AND u."estado" = 'HABILITADO'
                   AND u."cambioCredencialPendiente" = false
                   AND ur."codigoRol" IN (:roles)
                """, params, Integer.class);
        if (coincidencias == null || coincidencias == 0) {
            throw new SecurityException("Operación no autorizada");
        }
    }
}
