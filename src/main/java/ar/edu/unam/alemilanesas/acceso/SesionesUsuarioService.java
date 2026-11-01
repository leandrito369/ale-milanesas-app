package ar.edu.unam.alemilanesas.acceso;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B4-02B: UC-01 + ciclo tecnico de SESION_USUARIO (MR5).
 * Sin JWT, refresh token persistido, migraciones ni privilegios por parametros HTTP.
 */
@Service
public class SesionesUsuarioService {
    private static final String PARAM_DIGEST = "digest";
    private final NamedParameterJdbcTemplate sql;
    private final PasswordEncoder encoder;
    private final String hashFicticio;

    public record TokenEmitido(String accessToken, String tokenType,
                               int expiresIn, boolean requiereCambioContrasena) {}

    public SesionesUsuarioService(NamedParameterJdbcTemplate sql, PasswordEncoder encoder) {
        this.sql = sql;
        this.encoder = encoder;
        this.hashFicticio = encoder.encode(TokenSesionInterna.nuevo());
    }

    /** Auth01: fila de USUARIO bloqueada para no emitir despues de deshabilitar/cambiar clave. */
    @Transactional
    public TokenEmitido iniciarSesion(String nombreUsuario, String credencial) {
        boolean formatoValido = nombreUsuario != null && !nombreUsuario.isBlank()
                && nombreUsuario.length() <= 80 && credencial != null
                && !credencial.isEmpty()
                && credencial.getBytes(StandardCharsets.UTF_8).length <= 72;
        if (!formatoValido) throw new TokenSesionInterna.AccesoRechazado();

        var filas = sql.queryForList("""
                SELECT "idUsuario","credencialProtegida","estado","cambioCredencialPendiente"
                  FROM "USUARIO"
                 WHERE lower(btrim("nombreUsuario")) = lower(btrim(:nombre))
                 FOR UPDATE
                """, Map.of("nombre", nombreUsuario));
        if (filas.size() != 1) {
            encoder.matches(credencial, hashFicticio);
            throw new TokenSesionInterna.AccesoRechazado();
        }
        var usuario = filas.getFirst();
        long id = ((Number) usuario.get("idUsuario")).longValue();
        boolean coincide = encoder.matches(credencial, (String) usuario.get("credencialProtegida"));
        if (!coincide || !"HABILITADO".equals(usuario.get("estado"))
                || !tieneRolesCompatibles(id)) throw new TokenSesionInterna.AccesoRechazado();

        String token = TokenSesionInterna.nuevo();
        var params = new MapSqlParameterSource()
                .addValue("idSesion", UUID.randomUUID())
                .addValue("usuario", id)
                .addValue(PARAM_DIGEST, TokenSesionInterna.huella(token).orElseThrow());
        int insertadas = sql.update("""
                INSERT INTO "SESION_USUARIO"
                    ("idSesionUsuario","idUsuario","tokenProtegido",
                     "fechaHoraInicio","fechaHoraExpiracion","fechaHoraRevocacion")
                VALUES (:idSesion,:usuario,:digest,
                        clock_timestamp(),clock_timestamp()+interval '30 minutes',NULL)
                """, params);
        if (insertadas != 1) throw new IllegalStateException("No se creo sesion");
        return new TokenEmitido(token, "Bearer", 1800,
                Boolean.TRUE.equals(usuario.get("cambioCredencialPendiente")));
    }

    /** PROVEEDOR debe ser unico y con vinculo ACTIVO. Sin herencia de otros roles. */
    private boolean tieneRolesCompatibles(long idUsuario) {
        var datos = sql.queryForMap("""
                SELECT count(*)::integer AS total,
                       count(*) FILTER (WHERE ur."codigoRol"='PROVEEDOR')::integer AS proveedor
                  FROM "USUARIO_ROL" ur WHERE ur."idUsuario"=:id
                """, Map.of("id", idUsuario));
        int total = ((Number) datos.get("total")).intValue();
        int proveedor = ((Number) datos.get("proveedor")).intValue();
        if (total < 1 || (proveedor > 0 && (total != 1 || proveedor != 1))) return false;
        if (proveedor == 0) return true;
        Integer habilitados = sql.queryForObject("""
                SELECT count(*) FROM "USUARIO_PROVEEDOR" up
                  JOIN "PROVEEDOR" p ON p."idProveedor"=up."idProveedor"
                 WHERE up."idUsuario"=:id AND p."estado"='ACTIVO'
                """, Map.of("id", idUsuario), Integer.class);
        return habilitados != null && habilitados == 1;
    }

    /**
     * Una rotacion vigente y exclusiva. Orden de locks: USUARIO -> SESION
     * como UC53, para no interbloquear con el trigger que revoca sesiones.
     */
    @Transactional
    public TokenEmitido renovar(UUID idSesion, String tokenAnterior) {
        byte[] digest = TokenSesionInterna.huella(tokenAnterior)
                .orElseThrow(TokenSesionInterna.AccesoRechazado::new);
        Long idUsuario = sql.query("""
                SELECT "idUsuario" FROM "SESION_USUARIO"
                 WHERE "idSesionUsuario"=:id AND "tokenProtegido"=:digest
                """, new MapSqlParameterSource().addValue("id", idSesion)
                        .addValue(PARAM_DIGEST, digest),
                rs -> rs.next() ? rs.getLong(1) : null);
        if (idUsuario == null) throw new TokenSesionInterna.AccesoRechazado();

        var usuarios = sql.queryForList("""
                SELECT "estado","cambioCredencialPendiente" FROM "USUARIO"
                 WHERE "idUsuario"=:id FOR UPDATE
                """, Map.of("id", idUsuario));
        if (usuarios.size()!=1 || !"HABILITADO".equals(usuarios.getFirst().get("estado"))
                || Boolean.TRUE.equals(usuarios.getFirst().get("cambioCredencialPendiente"))
                || !tieneRolesCompatibles(idUsuario)) {
            throw new TokenSesionInterna.AccesoRechazado();
        }
        var sesiones = sql.queryForList("""
                SELECT "idSesionUsuario" FROM "SESION_USUARIO"
                 WHERE "idSesionUsuario"=:id AND "idUsuario"=:usuario
                   AND "tokenProtegido"=:digest
                   AND "fechaHoraRevocacion" IS NULL
                   AND "fechaHoraInicio" <= clock_timestamp()
                   AND "fechaHoraExpiracion" > clock_timestamp()
                   AND "fechaHoraInicio" + interval '8 hours' > clock_timestamp()
                 FOR UPDATE
                """, new MapSqlParameterSource()
                    .addValue("id",idSesion).addValue("usuario",idUsuario).addValue(PARAM_DIGEST,digest));
        if (sesiones.size()!=1) throw new TokenSesionInterna.AccesoRechazado();

        String nuevo = TokenSesionInterna.nuevo();
        Integer segundos = sql.query("""
                UPDATE "SESION_USUARIO"
                   SET "tokenProtegido"=:nuevo,
                       "fechaHoraExpiracion"=LEAST(
                           clock_timestamp()+interval '30 minutes',
                           "fechaHoraInicio"+interval '8 hours')
                 WHERE "idSesionUsuario"=:id AND "tokenProtegido"=:anterior
                   AND "fechaHoraRevocacion" IS NULL
                   AND "fechaHoraExpiracion" > clock_timestamp()
                   AND "fechaHoraInicio"+interval '8 hours'>clock_timestamp()
                RETURNING GREATEST(1, FLOOR(EXTRACT(EPOCH FROM
                    ("fechaHoraExpiracion"-clock_timestamp()))))::integer
                """, new MapSqlParameterSource()
                    .addValue("id",idSesion).addValue("anterior",digest)
                    .addValue("nuevo",TokenSesionInterna.huella(nuevo).orElseThrow()),
                    rs -> rs.next() ? rs.getInt(1) : null);
        if (segundos==null || segundos<1) throw new TokenSesionInterna.AccesoRechazado();
        return new TokenEmitido(nuevo,"Bearer",Math.min(segundos,1800),false);
    }

    @Transactional
    public void cerrarSesion(UUID idSesion) {
        int filas = sql.update("""
                UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=clock_timestamp()
                 WHERE "idSesionUsuario"=:id AND "fechaHoraRevocacion" IS NULL
                   AND "fechaHoraExpiracion">clock_timestamp()
                """, Map.of("id",idSesion));
        if (filas != 1) throw new TokenSesionInterna.AccesoRechazado();
    }
}
