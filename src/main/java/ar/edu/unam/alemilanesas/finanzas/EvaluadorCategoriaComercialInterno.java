package ar.edu.unam.alemilanesas.finanzas;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * UC15 interno: reevalua categoria desde deuda confirmada y politica vigente.
 * Debe invocarse dentro de la transaccion que modifica Cuenta Corriente y
 * con CLIENTE bloqueado por el servicio invocante. No expone endpoint.
 *
 * Imputacion FIFO: los creditos definitivos extinguen los debitos mas antiguos.
 * ANULACION_COBRO restaura credito previamente aplicado, no crea nueva venta.
 */
@Component
public class EvaluadorCategoriaComercialInterno {
    private final NamedParameterJdbcTemplate sql;

    public EvaluadorCategoriaComercialInterno(NamedParameterJdbcTemplate sql) {
        this.sql = sql;
    }

    public String reevaluar(long idCliente) {
        List<Integer> tolerancias = sql.queryForList("""
                SELECT "diasToleranciaMora" FROM "POLITICA_CATEGORIA_COMERCIAL"
                 WHERE "vigenteDesde" <= clock_timestamp()
                   AND ("vigenteHasta" IS NULL OR "vigenteHasta" > clock_timestamp())
                """, Map.of(), Integer.class);
        if (tolerancias.size() != 1) {
            throw new IllegalStateException(
                    "Se requiere una politica comercial vigente e inequivoca");
        }
        var rows = sql.query("""
                SELECT m."tipoMovimiento", m."importe",
                       COALESCE(e."fechaHoraEntrega",m."fechaHoraMovimiento") AS fecha_deuda
                  FROM "MOVIMIENTO_CUENTA" m
                  LEFT JOIN "ORIGEN_MOVIMIENTO_CUENTA" o
                    ON o."idMovimientoCuenta"=m."idMovimientoCuenta"
                  LEFT JOIN "ENTREGA" e ON e."idPedido"=o."idPedidoEntrega"
                 WHERE m."idCliente"=:cliente
                 ORDER BY m."fechaHoraMovimiento",m."numeroSecuenciaCliente"
                """, Map.of("cliente", idCliente), (rs, i) ->
                new Movimiento(rs.getString("tipoMovimiento"),rs.getBigDecimal("importe"),
                        rs.getTimestamp("fecha_deuda").toInstant()));
        BigDecimal creditos = BigDecimal.ZERO;
        var deudas = new java.util.ArrayList<Movimiento>();
        for (var m : rows) {
            switch (m.tipo()) {
                case "CARGO_POR_VENTA", "AJUSTE_DEBITO" -> deudas.add(m);
                case "PAGO", "AJUSTE_CREDITO" -> creditos = creditos.add(m.importe());
                case "ANULACION_COBRO" -> creditos = creditos.subtract(m.importe());
                default -> throw new IllegalStateException("Movimiento de Cuenta no reconocido");
            }
        }
        if (creditos.signum() < 0) {
            throw new IllegalStateException("Contraasientos superiores a creditos historicos");
        }
        deudas.sort(java.util.Comparator.comparing(Movimiento::fecha));
        Instant primeraImpaga = null;
        for (var deuda : deudas) {
            if (creditos.compareTo(deuda.importe()) >= 0) {
                creditos = creditos.subtract(deuda.importe());
            } else {
                primeraImpaga = deuda.fecha();
                break;
            }
        }
        boolean mora = primeraImpaga != null
                && Instant.now().isAfter(primeraImpaga.plus(
                        tolerancias.getFirst().longValue(), ChronoUnit.DAYS));
        String categoria = mora ? "EN_MORA" : "CUMPLIDOR";
        int cambiados = sql.update("""
                UPDATE "CLIENTE" SET "categoriaComercial"=:categoria
                 WHERE "idCliente"=:id AND "categoriaComercial"<>:categoria
                """, Map.of("categoria", categoria, "id", idCliente));
        if (cambiados > 1) {
            throw new IllegalStateException("Categoria ambigua");
        }
        return categoria;
    }

    private record Movimiento(String tipo, BigDecimal importe, Instant fecha) {}
}
