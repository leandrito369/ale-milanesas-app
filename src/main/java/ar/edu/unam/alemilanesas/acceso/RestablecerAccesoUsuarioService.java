package ar.edu.unam.alemilanesas.acceso;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-54 restablecerAccesoUsuario — RD-UC54-001, incluye localización UC-56.
 *
 * B3: invocación solo desde backend interno tras identificación comercial
 * realizada por el Administrador; no se expone HTTP. Un UUID NO autentica al
 * cliente remoto, ni un booleano es evidencia automatizada de identidad.
 */
@Service
public class RestablecerAccesoUsuarioService {
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    private final PasswordEncoder encoder;

    public RestablecerAccesoUsuarioService(NamedParameterJdbcTemplate sql,
                                          AutorizacionSesionInterna autorizacion,
                                          PasswordEncoder encoder) {
        this.sql = sql;
        this.autorizacion = autorizacion;
        this.encoder = encoder;
    }

    /** Contexto interno sesionAdministrador, no forma parte de firma funcional UC-54. */
    @Transactional
    public void restablecerAccesoUsuario(UUID sesionAdministrador, long idUsuario,
                                        Boolean identidadComercialVerificada,
                                        String credencialTemporal) {
        // No revelar si la cuenta o Proveedor existen a un actor no autorizado.
        autorizacion.exigirRoles(sesionAdministrador, Set.of("ADMINISTRADOR"));
        if (idUsuario <= 0 || !Boolean.TRUE.equals(identidadComercialVerificada)) {
            throw new IllegalArgumentException("Cuenta o verificación comercial inválidas");
        }
        if (credencialTemporal == null || credencialTemporal.isBlank()
                || credencialTemporal.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Credencial temporal inválida");
        }

        // Compartir lock de serialización I1 con UC17/18, que mantienen las
        // asociaciones y claves BIGINT sin generadores físicos MR5.
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(9172001)");
        autorizacion.exigirRoles(sesionAdministrador, Set.of("ADMINISTRADOR"));

        var usuarios = sql.queryForList("""
                SELECT "idUsuario","estado","credencialProtegida","cambioCredencialPendiente"
                  FROM "USUARIO" WHERE "idUsuario"=:id FOR UPDATE
                """, Map.of("id", idUsuario));
        if (usuarios.size() != 1) {
            throw new IllegalArgumentException("Cuenta Proveedor no identificable");
        }
        var usuario=usuarios.getFirst();

        var roles = sql.queryForList("""
                SELECT "codigoRol" FROM "USUARIO_ROL"
                 WHERE "idUsuario"=:id
                """, Map.of("id", idUsuario), String.class);
        boolean exclusivoProveedor = roles.size()==1 && "PROVEEDOR".equals(roles.getFirst());

        var vinculos=sql.queryForList("""
                SELECT up."idProveedor", p."idProveedor" AS "existeProveedor"
                  FROM "USUARIO_PROVEEDOR" up
                  JOIN "PROVEEDOR" p ON p."idProveedor"=up."idProveedor"
                 WHERE up."idUsuario"=:id
                """, Map.of("id", idUsuario));
        boolean vinculoConsistente=vinculos.size()==1;
        boolean otraCuentaHabilitada=false;
        if (vinculoConsistente) {
            Long proveedor=((Number)vinculos.getFirst().get("idProveedor")).longValue();
            Integer duplicadas=sql.queryForObject("""
                    SELECT count(*) FROM "USUARIO_PROVEEDOR" up
                      JOIN "USUARIO" u ON u."idUsuario"=up."idUsuario"
                     WHERE up."idProveedor"=:proveedor AND up."idUsuario"<>:id
                       AND u."estado"='HABILITADO'
                    """, new MapSqlParameterSource()
                    .addValue("id", idUsuario).addValue("proveedor", proveedor),
                    Integer.class);
            otraCuentaHabilitada=duplicadas != null && duplicadas > 0;
        }
        PoliticaRestablecimientoProveedor.validar(
                true, credencialTemporal, exclusivoProveedor,
                vinculoConsistente, otraCuentaHabilitada);
        if (encoder.matches(credencialTemporal,(String)usuario.get("credencialProtegida"))) {
            throw new IllegalArgumentException("La nueva credencial temporal debe ser distinta");
        }

        int cambiados=sql.update("""
                UPDATE "USUARIO" SET
                     "credencialProtegida"=:hash,
                     "cambioCredencialPendiente"=true
                 WHERE "idUsuario"=:id
                """, new MapSqlParameterSource()
                .addValue("id",idUsuario).addValue("hash",encoder.encode(credencialTemporal)));
        if (cambiados!=1) {
            throw new IllegalStateException("No se pudo confirmar el restablecimiento");
        }
        // El trigger MR5 revoca todas las sesiones del Usuario por cambio de
        // hash. No se habilita automáticamente DESHABILITADO ni se tocan Roles.
    }
}
