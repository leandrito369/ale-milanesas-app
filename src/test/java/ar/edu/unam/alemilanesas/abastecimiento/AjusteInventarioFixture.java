package ar.edu.unam.alemilanesas.abastecimiento;

import ar.edu.unam.alemilanesas.abastecimiento.RegistrarAjusteInventarioService.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Insumo sin stock/costo/movimientos, actores y compra sintética para regresión. */
public final class AjusteInventarioFixture {
    public final IngresoStockFixture compra;
    public final long insumo;
    public AjusteInventarioFixture(JdbcTemplate j, TransactionTemplate tx) {
        compra = new IngresoStockFixture(j,tx); insumo = compra.base + 1;
    }
    public UUID produccion() { return compra.produccion(); }
    public static BigDecimal n(String valor) { return new BigDecimal(valor); }
    public static PartidaAjuste nueva(String cantidad) { return new PartidaAjuste(null,n(cantidad),null,null,null); }
    public DatosAjuste inicial() { return datos("SALDO_INICIAL","10",n("2.5000"),List.of(nueva("5"),nueva("5"))); }
    public DatosAjuste datos(String tipo,String cantidad,BigDecimal costo,List<PartidaAjuste> partidas) {
        return new DatosAjuste("INSUMO",insumo,tipo,n(cantidad),"Inventario sintético",costo,partidas);
    }
    public Map<String,Object> body() {
        return Map.of("tipoElemento","INSUMO","idElemento",insumo,"tipoMovimiento","SALDO_INICIAL",
                "cantidad",10,"motivo","Apertura sintética","costoValuacion",2.5,
                "partidas",List.of(Map.of("cantidad",5),Map.of("cantidad",5,"codigoLote","LOTE-42")));
    }
}
