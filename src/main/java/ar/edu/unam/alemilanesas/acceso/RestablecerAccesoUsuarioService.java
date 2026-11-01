package ar.edu.unam.alemilanesas.acceso;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC54 + UC56: un Administrador restablece unicamente cuenta PROVEEDOR
 * tras confirmar presencialmente/verificar identidad comercial.
 * No es el login UC01 ni un servicio de entrega automatica de credenciales.
 */
@Service
public class RestablecerAccesoUsuarioService {
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna acceso;
    private final PasswordEncoder encoder;

    public RestablecerAccesoUsuarioService(NamedParameterJdbcTemplate sql,
                                          AutorizacionSesionInterna acceso,
                                          PasswordEncoder encoder) {
        this.sql=sql;
        this.acceso=acceso;
        this.encoder=encoder;
    }

    @Transactional
    public void restablecerAccesoUsuario(UUID sesionAdministrador, long idUsuario,
                                          Boolean identidadComercialVerificada,
                                          String credencialTemporal) {
        acceso.exigirRoles(sesionAdministrador, Set.of("ADMINISTRADOR"));
        if (idUsuario <= 0 || !Boolean.TRUE.equals(identidadComercialVerificada))
            throw new IllegalArgumentException("Verificacion comercial requerida");
        if (credencialTemporal == null || credencialTemporal.isBlank()
                || credencialTemporal.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Credencial temporal invalida");

        // Mismo orden de locks que UC17/UC18 y controles F07.
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(9172001)");
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(60505,7)");
        acceso.exigirRoles(sesionAdministrador, Set.of("ADMINISTRADOR"));

        var usuarios = sql.queryForList("""
                SELECT u."idUsuario",u."estado",u."credencialProtegida",
                       up."idProveedor",p."estado" AS "estadoProveedor"
                  FROM "USUARIO" u
                  LEFT JOIN "USUARIO_PROVEEDOR" up ON up."idUsuario"=u."idUsuario"
                  LEFT JOIN "PROVEEDOR" p ON p."idProveedor"=up."idProveedor"
                 WHERE u."idUsuario"=:id FOR UPDATE OF u
                """, Map.of("id",idUsuario));
        if (usuarios.size()!=1) throw new IllegalStateException("Cuenta Proveedor no elegible");
        var usuario=usuarios.getFirst();
        if (!(usuario.get("idProveedor") instanceof Number)
                || !"ACTIVO".equals(usuario.get("estadoProveedor")))
            throw new IllegalStateException("Cuenta Proveedor no elegible");
        Long proveedor=((Number)usuario.get("idProveedor")).longValue();
        var roles = sql.queryForList("""
                SELECT "codigoRol" FROM "USUARIO_ROL" WHERE "idUsuario"=:id
                """, Map.of("id",idUsuario));
        if (roles.size()!=1 || !"PROVEEDOR".equals(roles.getFirst().get("codigoRol")))
            throw new IllegalStateException("Cuenta Proveedor no elegible");
        Integer habilitados = sql.queryForObject("""
                SELECT count(*) FROM "USUARIO_PROVEEDOR" up
                  JOIN "USUARIO" u ON u."idUsuario"=up."idUsuario"
                 WHERE up."idProveedor"=:p AND u."estado"='HABILITADO'
                """, Map.of("p",proveedor),Integer.class);
        if (habilitados==null || habilitados>1
                || (habilitados==1 && !"HABILITADO".equals(usuario.get("estado"))))
            throw new IllegalStateException("Cuenta Proveedor no elegible");

        // No reusar la credencial actual ni reactivar cuentas DESHABILITADO.
        if (encoder.matches(credencialTemporal,(String)usuario.get("credencialProtegida")))
            throw new IllegalArgumentException("Credencial temporal debe ser distinta");
        int actualizadas=sql.update("""
                UPDATE "USUARIO"
                   SET "credencialProtegida"=:hash,
                       "cambioCredencialPendiente"=true
                 WHERE "idUsuario"=:id
                """,new MapSqlParameterSource().addValue("id",idUsuario)
                    .addValue("hash",encoder.encode(credencialTemporal)));
        if (actualizadas!=1) throw new IllegalStateException("Restablecimiento no confirmado");
        // MR5 revoca todas las sesiones al cambiar credencialProtegida.
        // No modificar estado, roles, proveedor ni generar Bearer/otro Usuario.
    }
}
