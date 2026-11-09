package ar.edu.unam.alemilanesas.produccioninventario;

import ar.edu.unam.alemilanesas.abastecimiento.IngresoReventaFixture;
import ar.edu.unam.alemilanesas.produccioninventario.ConfigurarRecetaService.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Datos confirmados en el contenedor dedicado; ninguna preparación ni movimiento de inventario. */
public final class RecetaFixture {
    public final IngresoReventaFixture actores;
    public final long producto;
    public final long insumo;
    public final JdbcTemplate j;
    public RecetaFixture(JdbcTemplate j, TransactionTemplate tx) {
        this.j = j;
        actores = new IngresoReventaFixture(j, tx);
        producto = actores.base + 1;
        insumo = actores.base;
        tx.executeWithoutResult(s -> {
            j.update("INSERT INTO \"UNIDAD_MEDIDA\" VALUES ('KG','Kilogramo') ON CONFLICT DO NOTHING");
            for (int n = 0; n < 2; n++) j.update("""
                    INSERT INTO "INSUMO" ("idInsumo","nombre","codigoUnidadMedida","stockActual",
                        "umbralMinimo","nivelObjetivoReposicion","automatizacionCompraActiva","activo")
                    VALUES (?,?,'KG',0,1,10,false,true)
                    """, insumo + n, "Receta insumo " + (insumo + n));
        });
    }
    public UUID admin() { return actores.sesiones.get("ADMINISTRADOR"); }
    public OffsetDateTime fecha(int dias) {
        return j.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).plusDays(dias).withNano(0);
    }
    public DatosReceta datos(OffsetDateTime desde, OffsetDateTime hasta) {
        return new DatosReceta(producto, new BigDecimal("1.000"), "UN", desde, hasta,
                List.of(new ItemReceta(insumo, new BigDecimal("0.200"), "KG"),
                        new ItemReceta(insumo + 1, new BigDecimal("0.500"), "KG")));
    }
    public long sembrarVigente() {
        long id = actores.base + 50;
        j.update("INSERT INTO \"RECETA\" VALUES (?,?)", id, producto);
        j.update("""
                INSERT INTO "VERSION_RECETA" ("idReceta","numeroVersion","vigenteDesde","vigenteHasta",
                    "cantidadRendimientoBase","codigoUnidadRendimiento") VALUES (?,1,?,NULL,1,'UN')
                """, id, fecha(-1));
        j.update("INSERT INTO \"DETALLE_VERSION_RECETA\" VALUES (?,1,?,0.2,'KG')", id, insumo);
        return id;
    }
    public int versiones() {
        return j.queryForObject("""
                SELECT count(*) FROM "VERSION_RECETA" v JOIN "RECETA" r USING ("idReceta") WHERE r."idProducto"=?
                """, Integer.class, producto);
    }
    public Map<String, Object> body(OffsetDateTime desde, OffsetDateTime hasta) {
        var b = new HashMap<String, Object>();
        b.put("cantidadRendimientoBase", new BigDecimal("1.000")); b.put("codigoUnidadRendimiento", "UN");
        b.put("vigenteDesde", desde.toString()); b.put("vigenteHasta", hasta == null ? null : hasta.toString());
        b.put("itemsInsumo", List.of(Map.of("idInsumo", insumo, "cantidadNecesaria", new BigDecimal("0.200"), "codigoUnidadCantidad", "KG"),
                Map.of("idInsumo", insumo + 1, "cantidadNecesaria", new BigDecimal("0.500"), "codigoUnidadCantidad", "KG")));
        return b;
    }
}
