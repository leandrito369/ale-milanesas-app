package ar.edu.unam.alemilanesas.maestros;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-33 buscarCliente — I1-02/B3. Solo consulta con roles nominales
 * ADMINISTRADOR/VENDEDOR ratificados (D/C). Sin endpoint HTTP B4.
 */
@Service
public class BuscarClienteService {
    private static final Set<String> ROLES = Set.of("ADMINISTRADOR", "VENDEDOR");
    private static final Set<String> ESTADOS = Set.of("ACTIVO", "INACTIVO");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public BuscarClienteService(NamedParameterJdbcTemplate sql,
                                AutorizacionSesionInterna autorizacion) {
        this.sql = sql;
        this.autorizacion = autorizacion;
    }

    @Transactional(readOnly = true)
    public List<ClienteConsulta> buscarCliente(UUID idSesionUsuario,
                                               CriterioBusquedaCliente criterio) {
        if (criterio == null) {
            throw new IllegalArgumentException("Criterios de búsqueda requeridos");
        }
        // Nunca admitir consultas directas por el rol Producción, Repartidor o Proveedor.
        autorizacion.exigirRoles(idSesionUsuario, ROLES);
        var params = new MapSqlParameterSource();
        var consulta = new StringBuilder("""
                SELECT c."idCliente",
                       btrim(concat_ws(' ', c."nombres", c."apellidos")) AS nombre,
                       c."telefonoPrincipal",
                       btrim(concat_ws(' ', c."calle", c."numeroPuerta",
                          c."piso", c."unidadFuncional", c."localidad",
                          c."provincia", c."referenciaDomicilio")) AS direccion,
                       c."zona", c."estado", c."categoriaComercial"
                  FROM "CLIENTE" c
                 WHERE 1=1
                """);
        if (criterio.idCliente() != null) {
            if (criterio.idCliente() <= 0) {
                throw new IllegalArgumentException("Identificador inválido");
            }
            consulta.append(" AND c.\"idCliente\" = :idCliente ");
            params.addValue("idCliente", criterio.idCliente());
        }
        if (criterio.nombre() != null) {
            String nombre = validar(criterio.nombre(), 241, "Nombre");
            consulta.append(" AND lower(btrim(concat_ws(' ',c.\"nombres\",c.\"apellidos\"))) LIKE :nombre ESCAPE '\\' ");
            params.addValue("nombre", "%" + escapeLike(nombre.toLowerCase(Locale.ROOT)) + "%");
        }
        if (criterio.telefonoPrincipal() != null) {
            String telefono = validar(criterio.telefonoPrincipal(), 20, "Teléfono");
            consulta.append(" AND c.\"telefonoPrincipal\" = :telefono ");
            params.addValue("telefono", telefono);
        }
        if (criterio.zona() != null) {
            String zona = validar(criterio.zona(), 100, "Zona");
            consulta.append(" AND lower(c.\"zona\") LIKE :zona ESCAPE '\\' ");
            params.addValue("zona", "%" + escapeLike(zona.toLowerCase(Locale.ROOT)) + "%");
        }
        if (criterio.estado() != null) {
            if (!ESTADOS.contains(criterio.estado())) {
                throw new IllegalArgumentException("Estado inválido");
            }
            consulta.append(" AND c.\"estado\" = :estado ");
            params.addValue("estado", criterio.estado());
        }
        consulta.append(" ORDER BY lower(c.\"nombres\"), c.\"idCliente\" ");
        return sql.query(consulta.toString(), params, (rs, rowNum) ->
                new ClienteConsulta(
                    rs.getLong("idCliente"),
                    rs.getString("nombre"),
                    rs.getString("telefonoPrincipal"),
                    rs.getString("direccion"),
                    rs.getString("zona"),
                    rs.getString("estado"),
                    rs.getString("categoriaComercial")));
    }

    private static String validar(String texto, int longitudMaxima, String campo) {
        String normalizado = texto.trim();
        if (normalizado.isEmpty() || normalizado.length() > longitudMaxima) {
            throw new IllegalArgumentException(campo + " inválido");
        }
        return normalizado;
    }

    private static String escapeLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
