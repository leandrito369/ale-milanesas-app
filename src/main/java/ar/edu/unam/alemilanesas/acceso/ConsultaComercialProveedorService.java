package ar.edu.unam.alemilanesas.acceso;

import java.sql.Types;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B4-02E / UC56 contextual con UC37: lectura para que un Administrador
 * identifique comercialmente al Proveedor ANTES de UC54.
 * La respuesta no equivale a verificar identidad humana ni concede UC54.
 */
@Service
public class ConsultaComercialProveedorService {
    private static final Set<String> ADMIN=Set.of("ADMINISTRADOR");
    private static final int MAX_RESULTADOS=25;
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public ConsultaComercialProveedorService(NamedParameterJdbcTemplate sql,
                                             AutorizacionSesionInterna autorizacion) {
        this.sql=sql;
        this.autorizacion=autorizacion;
    }

    /** Datos físicos MR5 + vínculo UC56. No existe PROVEEDOR.nombreComercial en MR5. */
    public record IdentidadComercial(long idUsuario,String nombreUsuario,
            String estadoUsuario,boolean cambioCredencialPendiente,
            long idProveedor,String cuit,String razonSocial,String telefonoContacto,
            String emailContacto,String estadoProveedor) {}

    @Transactional(readOnly=true)
    public List<IdentidadComercial> buscar(UUID sesionAdministrador,Long idUsuario,
                                          String nombreUsuario,String cuit) {
        autorizacion.exigirRoles(sesionAdministrador,ADMIN);
        if (idUsuario!=null && idUsuario<=0) {
            throw new IllegalArgumentException("idUsuario inválido");
        }
        String nombre=nombreUsuario==null?null:nombreUsuario.strip();
        if (nombre!=null && (nombre.length()<3 || nombre.length()>80
                || nombre.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Filtro de cuenta inválido");
        }
        if (cuit!=null && !cuit.matches("\\d{11}")) {
            throw new IllegalArgumentException("CUIT inválido");
        }
        if (idUsuario==null && nombre==null && cuit==null) {
            throw new IllegalArgumentException("Se exige criterio de búsqueda");
        }
        var params=new MapSqlParameterSource()
                .addValue("idUsuario",idUsuario,Types.BIGINT)
                .addValue("nombre",nombre,Types.VARCHAR)
                .addValue("cuit",cuit,Types.VARCHAR)
                .addValue("limite",MAX_RESULTADOS,Types.INTEGER);
        List<IdentidadComercial> resultado=sql.query("""
                SELECT u."idUsuario",u."nombreUsuario",
                       u."estado" AS "estadoUsuario",u."cambioCredencialPendiente",
                       p."idProveedor",p."cuit",p."razonSocial",
                       p."telefonoContacto",p."emailContacto",
                       p."estado" AS "estadoProveedor"
                  FROM "USUARIO" u
                  JOIN "USUARIO_PROVEEDOR" up ON up."idUsuario"=u."idUsuario"
                  JOIN "PROVEEDOR" p ON p."idProveedor"=up."idProveedor"
                 WHERE EXISTS (SELECT 1 FROM "USUARIO_ROL" ur
                               WHERE ur."idUsuario"=u."idUsuario"
                                 AND ur."codigoRol"='PROVEEDOR')
                   AND (CAST(:idUsuario AS BIGINT) IS NULL
                        OR u."idUsuario"=:idUsuario)
                   AND (CAST(:nombre AS VARCHAR(80)) IS NULL
                        OR position(lower(:nombre) IN lower(u."nombreUsuario"))>0)
                   AND (CAST(:cuit AS VARCHAR(11)) IS NULL OR p."cuit"=:cuit)
                 ORDER BY u."idUsuario"
                 LIMIT :limite
                """,params,(rs,n)->new IdentidadComercial(
                rs.getLong("idUsuario"),rs.getString("nombreUsuario"),
                rs.getString("estadoUsuario"),rs.getBoolean("cambioCredencialPendiente"),
                rs.getLong("idProveedor"),rs.getString("cuit"),
                rs.getString("razonSocial"),rs.getString("telefonoContacto"),
                rs.getString("emailContacto"),rs.getString("estadoProveedor")));
        autorizacion.exigirRoles(sesionAdministrador,ADMIN);
        return List.copyOf(resultado);
    }
}
