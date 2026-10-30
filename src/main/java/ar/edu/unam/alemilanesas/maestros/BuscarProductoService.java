package ar.edu.unam.alemilanesas.maestros;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC35: catálogo mínimo por rol, sin campos de costo/stock o precios no autorizados. */
@Service
public class BuscarProductoService {
    private static final Set<String> CONSULTA = Set.of("ADMINISTRADOR","VENDEDOR");
    private static final Set<String> TIPOS = Set.of("ELABORADO","REVENTA");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public BuscarProductoService(NamedParameterJdbcTemplate sql,
                                 AutorizacionSesionInterna autorizacion) {
        this.sql = sql;
        this.autorizacion=autorizacion;
    }

    public record CriterioProducto(Long idProducto, String nombre, String tipoProducto, Boolean activo) {}
    public record ProductoConsulta(long idProducto, String nombre, String descripcion,
                                   String tipoProducto, String codigoUnidadVenta, boolean activo) {}

    @Transactional(readOnly = true)
    public List<ProductoConsulta> buscarProducto(UUID sesion, CriterioProducto criterio) {
        autorizacion.exigirRoles(sesion, CONSULTA);
        if (criterio == null) throw new IllegalArgumentException("Criterios requeridos");
        var params = new MapSqlParameterSource();
        var q = new StringBuilder("""
            SELECT p."idProducto",p."nombre",p."descripcion",p."tipoProducto",
                   p."codigoUnidadVenta",p."activo"
              FROM "PRODUCTO" p WHERE 1=1
            """);
        if (criterio.idProducto()!=null) {
            if (criterio.idProducto() <= 0) throw new IllegalArgumentException("ID inválido");
            q.append(" AND p.\"idProducto\"=:id ");
            params.addValue("id",criterio.idProducto());
        }
        if (criterio.nombre()!=null) {
            String nombre=criterio.nombre().strip();
            if (nombre.isBlank() || nombre.length()>150)
                throw new IllegalArgumentException("Nombre filtro inválido");
            // POSITION literal, no interpretar % o _ del usuario como wildcards.
            q.append(" AND position(:nombre IN lower(p.\"nombre\"))>0 ");
            params.addValue("nombre",nombre.toLowerCase(Locale.ROOT));
        }
        if (criterio.tipoProducto()!=null) {
            if (!TIPOS.contains(criterio.tipoProducto()))
                throw new IllegalArgumentException("Tipo de Producto inválido");
            q.append(" AND p.\"tipoProducto\"=:tipo ");
            params.addValue("tipo",criterio.tipoProducto());
        }
        if (criterio.activo()!=null) {
            q.append(" AND p.\"activo\"=:activo ");
            params.addValue("activo",criterio.activo());
        }
        q.append(" ORDER BY p.\"nombreNormalizado\",p.\"idProducto\" ");
        return sql.query(q.toString(),params,(rs,i)->new ProductoConsulta(
            rs.getLong("idProducto"),rs.getString("nombre"),rs.getString("descripcion"),
            rs.getString("tipoProducto"),rs.getString("codigoUnidadVenta"),rs.getBoolean("activo")));
    }
}
