package ar.edu.unam.alemilanesas.acceso;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B3 / I1-01, primera operación de acceso: UC-56 buscarUsuario.
 *
 * RESTRICCIÓN: solo invocación interna desde una capa B4 autenticada y
 * validada; no existe todavía endpoint ni tokens de autenticación operativos.
 * Revalida sesión interna y rol ADMINISTRADOR en la base al consultar.
 */
@Service
public class BuscarUsuarioService {
    private static final Set<String> ESTADOS = Set.of("HABILITADO", "DESHABILITADO");
    private static final Set<String> ROLES = Set.of(
            "ADMINISTRADOR", "VENDEDOR", "RESPONSABLE_PRODUCCION", "REPARTIDOR", "PROVEEDOR");

    private final NamedParameterJdbcTemplate sql;

    public BuscarUsuarioService(NamedParameterJdbcTemplate sql) {
        this.sql = sql;
    }

    @Transactional(readOnly = true)
    public List<UsuarioConsulta> buscarUsuario(UUID idSesionUsuario, CriterioBusquedaUsuario criterios) {
        if (idSesionUsuario == null || criterios == null) {
            throw new IllegalArgumentException("Sesión y criterios requeridos");
        }
        comprobarAdministradorVigente(idSesionUsuario);

        var parametros = new MapSqlParameterSource();
        var consulta = new StringBuilder("""
                SELECT u."idUsuario", u."nombreUsuario", u."estado",
                       u."cambioCredencialPendiente", up."idProveedor",
                       (SELECT COALESCE(string_agg(ur."codigoRol", ',' ORDER BY ur."codigoRol"), '')
                        FROM "USUARIO_ROL" ur WHERE ur."idUsuario" = u."idUsuario") roles,
                       (SELECT count(*) FROM "ASIGNACION_REPARTO" ar
                          JOIN "PEDIDO" p ON p."idPedido" = ar."idPedido"
                        WHERE ar."idUsuarioRepartidor" = u."idUsuario"
                          AND ar."fechaHoraFin" IS NULL
                          AND p."estado" = 'PENDIENTE') pedidosPendientes
                  FROM "USUARIO" u
                  LEFT JOIN "USUARIO_PROVEEDOR" up ON up."idUsuario" = u."idUsuario"
                 WHERE 1=1
                """);
        if (criterios.nombreUsuario() != null) {
            String nombre = criterios.nombreUsuario().trim();
            if (nombre.isEmpty() || nombre.length() > 80) {
                throw new IllegalArgumentException("Nombre de usuario inválido");
            }
            // Escapar metacaracteres de LIKE; siempre se usa parámetro, nunca SQL concatenado.
            String escaped = nombre.toLowerCase(java.util.Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            consulta.append(" AND lower(u.\"nombreUsuario\") LIKE :nombre ESCAPE '\\' ");
            parametros.addValue("nombre", "%" + escaped + "%");
        }
        if (criterios.estado() != null) {
            if (!ESTADOS.contains(criterios.estado())) {
                throw new IllegalArgumentException("Estado de usuario inválido");
            }
            consulta.append(" AND u.\"estado\" = :estado ");
            parametros.addValue("estado", criterios.estado());
        }
        if (criterios.codigoRol() != null) {
            if (!ROLES.contains(criterios.codigoRol())) {
                throw new IllegalArgumentException("Rol inválido");
            }
            consulta.append("""
                     AND EXISTS (SELECT 1 FROM "USUARIO_ROL" ur
                         WHERE ur."idUsuario" = u."idUsuario"
                           AND ur."codigoRol" = :codigoRol)
                    """);
            parametros.addValue("codigoRol", criterios.codigoRol());
        }
        consulta.append(" ORDER BY lower(u.\"nombreUsuario\"), u.\"idUsuario\" ");
        return sql.query(consulta.toString(), parametros, (rs, rowNum) ->
                new UsuarioConsulta(
                        rs.getLong("idUsuario"),
                        rs.getString("nombreUsuario"),
                        rs.getString("estado"),
                        rs.getBoolean("cambioCredencialPendiente"),
                        roles(rs.getString("roles")),
                        (Long) rs.getObject("idProveedor"),
                        rs.getLong("pedidosPendientes")));
    }

    private void comprobarAdministradorVigente(UUID idSesionUsuario) {
        Integer permitido = sql.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO" s
                  JOIN "USUARIO" u ON u."idUsuario" = s."idUsuario"
                  JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
                 WHERE s."idSesionUsuario" = :sesion
                   AND s."fechaHoraInicio" <= clock_timestamp()
                   AND s."fechaHoraExpiracion" > clock_timestamp()
                   AND s."fechaHoraRevocacion" IS NULL
                   AND u."estado" = 'HABILITADO'
                   AND u."cambioCredencialPendiente" = false
                   AND ur."codigoRol" = 'ADMINISTRADOR'
                """, Map.of("sesion", idSesionUsuario), Integer.class);
        if (permitido == null || permitido != 1) {
            // No se revela si el actor, la sesión o el rol existe.
            throw new SecurityException("Operación no autorizada");
        }
    }

    private static List<String> roles(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }
}
