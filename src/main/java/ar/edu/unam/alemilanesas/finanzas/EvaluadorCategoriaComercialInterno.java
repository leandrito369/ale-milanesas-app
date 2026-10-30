package ar.edu.unam.alemilanesas.finanzas;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
        // Un unico reloj de PostgreSQL para vigencia y antiguedad:
        // no mezclar clock_timestamp() con el reloj de la JVM.
        OffsetDateTime instanteDb = sql.getJdbcTemplate().queryForObject(
                "SELECT clock_timestamp()",
                (rs, rowNum) -> rs.getObject(1, OffsetDateTime.class));
        if (instanteDb == null) {
            throw new IllegalStateException("No se pudo obtener reloj PostgreSQL");
        }
        Instant ahora = instanteDb.toInstant();
        List<Integer> tolerancias = sql.queryForList("""
                SELECT "diasToleranciaMora" FROM "POLITICA_CATEGORIA_COMERCIAL"
                 WHERE "vigenteDesde" <= :ahora
                   AND ("vigenteHasta" IS NULL OR "vigenteHasta" > :ahora)
                """, Map.of("ahora", instanteDb), Integer.class);
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
                && superaToleranciaDiasCompletos(primeraImpaga, ahora, tolerancias.getFirst());
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

    /**
     * Antiguedad = dias COMPLETOS transcurridos (bloques de 24 h).
     * Igualdad con tolerancia no genera mora; 0 dias tolerados requiere
     * al menos un dia completo antes de pasar a EN_MORA.
     * No es una regla de "cambio de fecha calendario" y se reserva esa
     * diferencia para ratificacion normativa si la catedra la exige.
     */
    static boolean superaToleranciaDiasCompletos(
            Instant primeraImpaga, Instant instanteDb, int diasTolerancia) {
        if (primeraImpaga == null || instanteDb == null || diasTolerancia < 0) {
            throw new IllegalArgumentException("Fechas y tolerancia validas requeridas");
        }
        return ChronoUnit.DAYS.between(primeraImpaga, instanteDb) > diasTolerancia;
    }

    private record Movimiento(String tipo, BigDecimal importe, Instant fecha) {}
}
