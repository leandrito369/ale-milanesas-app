package ar.edu.unam.alemilanesas.produccioninventario;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC10: identidad estable de receta, versiones y detalles atómicos, RD-I301-001 §2. */
@Service
public class ConfigurarRecetaService {
    private static final Set<String> ADMIN = Set.of("ADMINISTRADOR");
    private static final String INVALIDA = "SOLICITUD_INVALIDA";
    private static final String UNIDAD = "UNIDAD_NO_COINCIDE";
    private static final String SOLAPE = "SOLAPE_VERSION_FUTURA";
    private static final String NUMERO = "numero";
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public ConfigurarRecetaService(NamedParameterJdbcTemplate sql, AutorizacionSesionInterna autorizacion) {
        this.sql = sql;
        this.autorizacion = autorizacion;
    }
    public record ItemReceta(long idInsumo, BigDecimal cantidadNecesaria, String codigoUnidadCantidad) {}
    public record DatosReceta(long idProducto, BigDecimal cantidadRendimientoBase, String codigoUnidadRendimiento,
            OffsetDateTime vigenteDesde, OffsetDateTime vigenteHasta, List<ItemReceta> itemsInsumo) {}
    public record RecetaRegistrada(long idReceta, int numeroVersion, OffsetDateTime vigenteDesde,
            OffsetDateTime vigenteHasta, BigDecimal cantidadRendimientoBase, String codigoUnidadRendimiento,
            int cantidadItems, Integer versionCerrada) {}
    private record Periodo(int numero, OffsetDateTime desde, OffsetDateTime hasta) {}

    @Transactional
    public RecetaRegistrada configurarRecetaProducto(UUID sesion, DatosReceta datos) {
        autorizacion.exigirRoles(sesion, ADMIN);
        DatosReceta d = validar(datos);
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(808, 0)");
        validarProducto(d);
        // Orden estable de locks compartidos; protege activo/unidad frente a cambios concurrentes.
        d.itemsInsumo().stream().sorted(Comparator.comparingLong(ItemReceta::idInsumo)).forEach(this::validarInsumo);
        autorizacion.exigirRoles(sesion, ADMIN);
        OffsetDateTime ahora = sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (d.vigenteDesde().isBefore(ahora)) throw new IllegalArgumentException("VIGENCIA_RETROACTIVA");
        long receta = obtenerOCrearReceta(d.idProducto());
        Integer cerrada = acotarVigencia(receta, d, ahora);
        Integer numero = sql.queryForObject("""
                SELECT COALESCE(MAX("numeroVersion"),0)+1 FROM "VERSION_RECETA" WHERE "idReceta"=:id
                """, Map.of("id", receta), Integer.class);
        var p = new MapSqlParameterSource().addValue("id", receta).addValue(NUMERO, numero)
                .addValue("desde", d.vigenteDesde()).addValue("hasta", d.vigenteHasta())
                .addValue("cantidad", d.cantidadRendimientoBase()).addValue("unidad", d.codigoUnidadRendimiento());
        sql.update("""
                INSERT INTO "VERSION_RECETA" ("idReceta","numeroVersion","vigenteDesde","vigenteHasta",
                    "cantidadRendimientoBase","codigoUnidadRendimiento")
                VALUES (:id,:numero,:desde,:hasta,:cantidad,:unidad)
                """, p);
        for (var item : d.itemsInsumo()) {
            sql.update("""
                    INSERT INTO "DETALLE_VERSION_RECETA" ("idReceta","numeroVersion","idInsumo",
                        "cantidadNecesaria","codigoUnidadCantidad") VALUES (:id,:numero,:insumo,:cantidad,:unidad)
                    """, new MapSqlParameterSource().addValue("id", receta).addValue(NUMERO, numero)
                    .addValue("insumo", item.idInsumo()).addValue("cantidad", item.cantidadNecesaria())
                    .addValue("unidad", item.codigoUnidadCantidad()));
        }
        return new RecetaRegistrada(receta, numero, d.vigenteDesde(), d.vigenteHasta(),
                d.cantidadRendimientoBase(), d.codigoUnidadRendimiento(), d.itemsInsumo().size(), cerrada);
    }
    private static DatosReceta validar(DatosReceta d) {
        if (d == null || d.idProducto() <= 0 || !cantidadValida(d.cantidadRendimientoBase())
                || !codigoValido(d.codigoUnidadRendimiento()) || d.vigenteDesde() == null
                || d.itemsInsumo() == null || d.itemsInsumo().isEmpty()) throw new IllegalArgumentException(INVALIDA);
        OffsetDateTime desde = d.vigenteDesde().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime hasta = d.vigenteHasta() == null ? null : d.vigenteHasta().truncatedTo(ChronoUnit.MICROS);
        if (hasta != null && !hasta.isAfter(desde)) throw new IllegalArgumentException(INVALIDA);
        var ids = new HashSet<Long>();
        for (var item : d.itemsInsumo()) {
            if (item == null || item.idInsumo() <= 0 || !cantidadValida(item.cantidadNecesaria())
                    || !codigoValido(item.codigoUnidadCantidad()) || !ids.add(item.idInsumo()))
                throw new IllegalArgumentException(INVALIDA);
        }
        return new DatosReceta(d.idProducto(), d.cantidadRendimientoBase(), d.codigoUnidadRendimiento(),
                desde, hasta, List.copyOf(d.itemsInsumo()));
    }
    private static boolean cantidadValida(BigDecimal n) {
        return n != null && n.signum() > 0 && n.scale() <= 3 && n.precision() - n.scale() <= 15;
    }
    private static boolean codigoValido(String s) {
        return s != null && !s.isBlank() && s.length() <= 20 && s.indexOf('\0') < 0;
    }
    private void validarProducto(DatosReceta d) {
        var filas = sql.queryForList("""
                SELECT "tipoProducto","activo","codigoUnidadVenta" FROM "PRODUCTO" WHERE "idProducto"=:id FOR UPDATE
                """, Map.of("id", d.idProducto()));
        if (filas.isEmpty()) throw new IllegalArgumentException("PRODUCTO_INEXISTENTE");
        var producto = filas.getFirst();
        if (!"ELABORADO".equals(producto.get("tipoProducto"))) throw new IllegalArgumentException("PRODUCTO_NO_ES_ELABORADO");
        if (!Boolean.TRUE.equals(producto.get("activo"))) throw new IllegalArgumentException("PRODUCTO_INACTIVO");
        if (!d.codigoUnidadRendimiento().equals(producto.get("codigoUnidadVenta"))) throw new IllegalArgumentException(UNIDAD);
    }
    private void validarInsumo(ItemReceta item) {
        var filas = sql.queryForList("""
                SELECT "activo","codigoUnidadMedida" FROM "INSUMO" WHERE "idInsumo"=:id FOR SHARE
                """, Map.of("id", item.idInsumo()));
        if (filas.isEmpty()) throw new IllegalArgumentException("INSUMO_INEXISTENTE");
        var insumo = filas.getFirst();
        if (!Boolean.TRUE.equals(insumo.get("activo"))) throw new IllegalArgumentException("INSUMO_INACTIVO");
        if (!item.codigoUnidadCantidad().equals(insumo.get("codigoUnidadMedida"))) throw new IllegalArgumentException(UNIDAD);
    }
    private long obtenerOCrearReceta(long producto) {
        var ids = sql.queryForList("SELECT \"idReceta\" FROM \"RECETA\" WHERE \"idProducto\"=:id", Map.of("id", producto), Long.class);
        if (!ids.isEmpty()) return ids.getFirst();
        Long id = sql.getJdbcTemplate().queryForObject("SELECT COALESCE(MAX(\"idReceta\"),0)+1 FROM \"RECETA\"", Long.class);
        sql.update("INSERT INTO \"RECETA\" (\"idReceta\",\"idProducto\") VALUES (:id,:producto)", Map.of("id", id, "producto", producto));
        return id;
    }
    private Integer acotarVigencia(long receta, DatosReceta d, OffsetDateTime ahora) {
        var periodos = sql.query("""
                SELECT "numeroVersion","vigenteDesde","vigenteHasta" FROM "VERSION_RECETA"
                WHERE "idReceta"=:id ORDER BY "vigenteDesde","numeroVersion" FOR UPDATE
                """, Map.of("id", receta), (rs, n) -> new Periodo(rs.getInt("numeroVersion"),
                rs.getObject("vigenteDesde", OffsetDateTime.class), rs.getObject("vigenteHasta", OffsetDateTime.class)));
        Integer cerrada = null;
        for (var p : periodos) {
            if (!solapa(p, d)) continue;
            // RD-I301-001 §2: no truncar versiones aún futuras, aunque comiencen antes de la nueva.
            if (p.desde().isAfter(ahora) || !p.desde().isBefore(d.vigenteDesde()))
                throw new IllegalArgumentException(SOLAPE);
            cerrada = p.numero();
        }
        if (cerrada != null) sql.update("""
                UPDATE "VERSION_RECETA" SET "vigenteHasta"=:hasta WHERE "idReceta"=:id AND "numeroVersion"=:numero
                """, Map.of("hasta", d.vigenteDesde(), "id", receta, NUMERO, cerrada));
        return cerrada;
    }
    private static boolean solapa(Periodo p, DatosReceta d) {
        return (d.vigenteHasta() == null || p.desde().isBefore(d.vigenteHasta()))
                && (p.hasta() == null || d.vigenteDesde().isBefore(p.hasta()));
    }
}
