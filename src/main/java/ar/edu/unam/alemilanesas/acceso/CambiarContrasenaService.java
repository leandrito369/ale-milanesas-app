package ar.edu.unam.alemilanesas.acceso;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-53 — cambiarContrasena con RD-UC53-001.
 *
 * Servicio B3 interno: recibe contexto de sesión RESUELTO por el servidor.
 * No se publica un endpoint HTTP ni se acepta UUID como prueba criptográfica.
 * Soporta tanto sesión restringida PROVEEDOR como interna.
 */
@Service
public class CambiarContrasenaService {
    private final NamedParameterJdbcTemplate sql;
    private final PasswordEncoder encoder;

    public CambiarContrasenaService(NamedParameterJdbcTemplate sql,
                                   PasswordEncoder encoder) {
        this.sql = sql;
        this.encoder = encoder;
    }

    /**
     * Firma funcional: cambiarContrasena(nueva, confirmacion, actual[0..1]).
     * El UUID es un atributo de contexto INTERNO, no parámetro público UC.
     */
    @Transactional
    public void cambiarContrasena(UUID sesionVigente, String nuevaCredencial,
                                 String confirmacionNuevaCredencial,
                                 String credencialActual) {
        if (sesionVigente == null) {
            throw new SecurityException("Sesión no autorizada");
        }

        // Primero resolver identidad sin conceder permisos generales. El
        // caso UC-53 es justamente el permitido con credencial pendiente.
        Long idUsuario = sql.query("""
                SELECT s."idUsuario" FROM "SESION_USUARIO" s
                 WHERE s."idSesionUsuario"=:sesion
                   AND s."fechaHoraInicio"<=clock_timestamp()
                   AND s."fechaHoraExpiracion">clock_timestamp()
                   AND s."fechaHoraRevocacion" IS NULL
                """, Map.of("sesion", sesionVigente),
                rs -> rs.next() ? rs.getLong(1) : null);
        if (idUsuario == null) {
            throw new SecurityException("Sesión no autorizada");
        }

        // Se bloquea el Usuario antes de leer hash/indicador para evitar
        // perder actualizaciones simultáneas de contraseñas.
        var usuarios = sql.queryForList("""
                SELECT "idUsuario", "estado", "credencialProtegida",
                       "cambioCredencialPendiente"
                  FROM "USUARIO" WHERE "idUsuario"=:id FOR UPDATE
                """, Map.of("id", idUsuario));
        if (usuarios.size() != 1 || !"HABILITADO".equals(usuarios.getFirst().get("estado"))) {
            throw new SecurityException("Sesión no autorizada");
        }

        // Revalidar la sesión después de obtener el lock de Usuario;
        // otros comandos pueden revocarla mientras se aguardaba el lock.
        Integer sesiones = sql.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                 WHERE "idSesionUsuario"=:sesion
                   AND "idUsuario"=:usuario
                   AND "fechaHoraInicio"<=clock_timestamp()
                   AND "fechaHoraExpiracion">clock_timestamp()
                   AND "fechaHoraRevocacion" IS NULL
                """, new MapSqlParameterSource()
                    .addValue("sesion", sesionVigente).addValue("usuario", idUsuario),
                Integer.class);
        if (sesiones == null || sesiones != 1) {
            throw new SecurityException("Sesión no autorizada");
        }
        var usuario = usuarios.getFirst();
        String hashVigente = (String) usuario.get("credencialProtegida");
        boolean pendiente = (Boolean) usuario.get("cambioCredencialPendiente");
        PoliticaCambioCredencial.validar(
                nuevaCredencial, confirmacionNuevaCredencial, credencialActual,
                pendiente, hashVigente, encoder);

        String nuevoHash = encoder.encode(nuevaCredencial);
        int modificados = sql.update("""
                UPDATE "USUARIO"
                   SET "credencialProtegida"=:nuevoHash,
                       "cambioCredencialPendiente"=false
                 WHERE "idUsuario"=:id AND "estado"='HABILITADO'
                """, new MapSqlParameterSource()
                .addValue("nuevoHash", nuevoHash).addValue("id", idUsuario));
        if (modificados != 1) {
            throw new IllegalStateException("No se pudo confirmar el cambio de credencial");
        }
        // MR5 (tg_mr5_revocar_sesiones_usuario) revoca todas las sesiones
        // cuando el hash cambia, incluida la sesión de origen. NO se
        // reactiva la misma sesión. IC-UC53-MR5-002 pendiente de RD.
    }
}
