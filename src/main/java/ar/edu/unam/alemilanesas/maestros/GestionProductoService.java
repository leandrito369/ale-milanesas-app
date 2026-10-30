package ar.edu.unam.alemilanesas.maestros;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I1-03 / B3: UC30, UC47 y UC48. Sin endpoints, precios, recetas ni stock
 * ficticio. El lock de Producto también coordina el trigger F09 de DETALLE_PEDIDO.
 */
@Service
public class GestionProductoService {
    private static final Set<String> ADMIN = Set.of("ADMINISTRADOR");
    private static final Set<String> TIPOS = Set.of("ELABORADO", "REVENTA");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public GestionProductoService(NamedParameterJdbcTemplate sql,
                                  AutorizacionSesionInterna autorizacion) {
        this.sql = sql;
        this.autorizacion = autorizacion;
    }

    public record ProductoGuardado(long idProducto, boolean activo, boolean huboCambio) {}
    public record DatosProducto(String nombre, String descripcion, String tipoProducto,
                                String codigoUnidadVenta) {}

    @Transactional
    public ProductoGuardado gestionarProducto(UUID sesion, String nombre, String descripcion,
                                               String tipoProducto, String codigoUnidadVenta) {
        autorizacion.exigirRoles(sesion, ADMIN);
        DatosProducto d = validar(new DatosProducto(nombre, descripcion, tipoProducto, codigoUnidadVenta));
        bloquearEscritores();
        autorizacion.exigirRoles(sesion, ADMIN);
        exigirUnidad(d.codigoUnidadVenta());
        asegurarNombreUnico(normalizarNombre(d.nombre()), null);
        Long id = sql.getJdbcTemplate().queryForObject(
                "SELECT COALESCE(MAX(\"idProducto\"),0)+1 FROM \"PRODUCTO\"", Long.class);
        if (id == null || id <= 0) throw new IllegalStateException("Sin ID Producto");
        sql.update("""
                INSERT INTO "PRODUCTO" ("idProducto","nombre","nombreNormalizado",
                    "descripcion","tipoProducto","codigoUnidadVenta","activo",
                    "stockActualReventa","costoPromedioActualReventa")
                VALUES (:id,:nombre,:norm,:descripcion,:tipo,:unidad,true,NULL,NULL)
                """, parametros(d).addValue("id", id).addValue("norm", normalizarNombre(d.nombre())));
        return new ProductoGuardado(id, true, true);
    }

    @Transactional
    public ProductoGuardado modificarProducto(UUID sesion, long idProducto, String nuevoNombre,
                                               String descripcion, String tipoProductoPropuesto,
                                               String codigoUnidadVentaPropuesto) {
        autorizacion.exigirRoles(sesion, ADMIN);
        if (idProducto <= 0) throw new IllegalArgumentException("Producto inválido");
        DatosProducto d = validar(new DatosProducto(nuevoNombre, descripcion,
                                                    tipoProductoPropuesto,codigoUnidadVentaPropuesto));
        bloquearEscritores();
        autorizacion.exigirRoles(sesion, ADMIN);
        var filas = seleccionarBloqueado(idProducto);
        var p = filas.getFirst();
        String nombreNorm = normalizarNombre(d.nombre());
        exigirUnidad(d.codigoUnidadVenta());
        asegurarNombreUnico(nombreNorm, idProducto);
        boolean cambiaTipoUnidad = !Objects.equals(p.get("tipoProducto"), d.tipoProducto())
                || !Objects.equals(p.get("codigoUnidadVenta"), d.codigoUnidadVenta());
        if (cambiaTipoUnidad && tieneHistoria(idProducto)) {
            throw new IllegalArgumentException(
                    "Producto con historia: registrar otro mediante UC-30; no cambiar tipo/unidad");
        }
        boolean distinto = !Objects.equals(p.get("nombre"), d.nombre())
                || !Objects.equals(p.get("nombreNormalizado"), nombreNorm)
                || !Objects.equals(p.get("descripcion"), d.descripcion())
                || cambiaTipoUnidad;
        if (!distinto) {
            return new ProductoGuardado(idProducto, (Boolean)p.get("activo"), false);
        }
        sql.update("""
                UPDATE "PRODUCTO" SET "nombre"=:nombre,"nombreNormalizado"=:norm,
                    "descripcion"=:descripcion,"tipoProducto"=:tipo,"codigoUnidadVenta"=:unidad
                WHERE "idProducto"=:id
                """, parametros(d).addValue("norm", nombreNorm).addValue("id",idProducto));
        return new ProductoGuardado(idProducto, (Boolean)p.get("activo"), true);
    }

    @Transactional
    public ProductoGuardado cambiarEstadoProducto(UUID sesion, long idProducto, Boolean nuevoActivo) {
        autorizacion.exigirRoles(sesion, ADMIN);
        if (idProducto <= 0 || nuevoActivo == null)
            throw new IllegalArgumentException("Producto y estado obligatorios");
        bloquearEscritores();
        autorizacion.exigirRoles(sesion, ADMIN);
        var p = seleccionarBloqueado(idProducto).getFirst();
        if (Objects.equals(p.get("activo"), nuevoActivo)) {
            return new ProductoGuardado(idProducto, nuevoActivo, false);
        }
        if (nuevoActivo) {
            // A3 UC48: corregir por UC47 antes de reactivar una ficha inválida.
            DatosProducto actual=validar(new DatosProducto((String)p.get("nombre"),
                (String)p.get("descripcion"),(String)p.get("tipoProducto"),
                (String)p.get("codigoUnidadVenta")));
            exigirUnidad(actual.codigoUnidadVenta());
            if (!normalizarNombre(actual.nombre()).equals(p.get("nombreNormalizado"))) {
                throw new IllegalArgumentException("Ficha inconsistente: corregir mediante UC-47");
            }
        }
        sql.update("""
                UPDATE "PRODUCTO" SET "activo"=:activo WHERE "idProducto"=:id
                """, Map.of("activo",nuevoActivo,"id",idProducto));
        return new ProductoGuardado(idProducto,nuevoActivo,true);
    }

    private List<Map<String,Object>> seleccionarBloqueado(long id) {
        var filas = sql.queryForList("""
                SELECT "idProducto","nombre","nombreNormalizado","descripcion",
                       "tipoProducto","codigoUnidadVenta","activo"
                  FROM "PRODUCTO" WHERE "idProducto"=:id FOR UPDATE
                """, Map.of("id",id));
        if (filas.size()!=1) throw new IllegalArgumentException("Producto inexistente");
        return filas;
    }

    /** Dependencias históricas existentes en MR5 (no excluir las inactivas). */
    private boolean tieneHistoria(long id) {
        for (String tabla : List.of("DETALLE_PEDIDO","PRECIO_PRODUCTO","RECETA",
                "PROMOCION_PRODUCTO","RECEPCION_COMPRA","MOVIMIENTO_STOCK_REVENTA",
                "VALORACION_EXTRAORDINARIA_REVENTA")) {
            // Sólo identificadores literales internos, jamás proporcionados por el actor.
            String columna = tabla.equals("RECEPCION_COMPRA") ? "idProductoReventa" : "idProducto";
            Integer n = sql.getJdbcTemplate().queryForObject(
                    "SELECT count(*) FROM \""+tabla+"\" WHERE \""+columna+"\"=?",
                    Integer.class,id);
            if (n!=null && n>0) return true;
        }
        return false;
    }

    private void bloquearEscritores() {
        // Protege MAX(id)+1 y el chequeo de nombre ante servicios cooperantes.
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(3030047)");
    }
    private void exigirUnidad(String unidad) {
        Integer n = sql.queryForObject("""
                SELECT count(*) FROM "UNIDAD_MEDIDA" WHERE "codigoUnidad"=:unidad
                """,Map.of("unidad",unidad),Integer.class);
        if (n==null || n!=1) throw new IllegalArgumentException("Unidad de venta inexistente");
    }
    private void asegurarNombreUnico(String norm, Long excluir) {
        Long id = sql.query("""
                SELECT "idProducto" FROM "PRODUCTO"
                 WHERE "nombreNormalizado"=:norm AND "idProducto"<>:excluir
                 LIMIT 1
                """, Map.of("norm",norm,"excluir",excluir==null?-1L:excluir),
                rs -> rs.next()?rs.getLong(1):null);
        if (id!=null) throw new IllegalArgumentException(
                "Nombre normalizado duplicado incluso si el Producto está inactivo: "+id);
    }

    static String normalizarNombre(String nombre) {
        return nombre.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }
    static DatosProducto validar(DatosProducto d) {
        if (d==null || d.nombre()==null || d.nombre().isBlank()
                || d.nombre().length()>150
                || d.nombre().chars().anyMatch(Character::isISOControl)
                || !TIPOS.contains(d.tipoProducto())
                || d.codigoUnidadVenta()==null || d.codigoUnidadVenta().isBlank()
                || d.codigoUnidadVenta().length()>20
                || (d.descripcion()!=null && d.descripcion().length()>10000))
            throw new IllegalArgumentException("Datos obligatorios/tipo/unidad inválidos");
        String nombre = d.nombre().strip().replaceAll("\\s+"," ");
        return new DatosProducto(nombre,d.descripcion()==null?null:d.descripcion().strip(),
                d.tipoProducto(),d.codigoUnidadVenta().strip());
    }
    private static MapSqlParameterSource parametros(DatosProducto d) {
        return new MapSqlParameterSource()
                .addValue("nombre",d.nombre()).addValue("descripcion",d.descripcion())
                .addValue("tipo",d.tipoProducto()).addValue("unidad",d.codigoUnidadVenta());
    }
}
