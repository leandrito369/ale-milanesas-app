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
 * Invocar dentro de transaccion; el evaluador asegura CLIENTE FOR UPDATE.
 * Portado de PR #14 para desarrollo paralelo I1-05; conciliar en merge.
 * No expone endpoint.
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
        return reevaluar(idCliente, instanteDb);
    }

    /**
     * La operacion invocante provee una sola marca temporal PostgreSQL para
     * UC15 y UC16. Se bloquea CLIENTE y la politica escogida FOR SHARE.
     */
    String reevaluar(long idCliente, OffsetDateTime instanteDb) {
        if (idCliente<=0 || instanteDb == null) {
            throw new IllegalArgumentException("Cliente o instante inválido");
        }
        var clientes=sql.queryForList("""
                SELECT "idCliente" FROM "CLIENTE" WHERE "idCliente"=:id FOR UPDATE
                """,Map.of("id",idCliente));
        if (clientes.size()!=1) throw new IllegalArgumentException("Cliente inexistente");
        Instant ahora = instanteDb.toInstant();
        List<Integer> tolerancias = sql.queryForList("""
                SELECT "diasToleranciaMora" FROM "POLITICA_CATEGORIA_COMERCIAL"
                 WHERE "vigenteDesde" <= :ahora
                   AND ("vigenteHasta" IS NULL OR "vigenteHasta" > :ahora)
                 FOR SHARE
                """, Map.of("ahora", instanteDb), Integer.class);
        if (tolerancias.size() != 1) {
            throw new IllegalStateException(
                    "Se requiere una politica comercial vigente e inequivoca");
        }
        var rows = sql.query("""
                SELECT m."tipoMovimiento", m."importe",
                       COALESCE(e."fechaHoraEntrega", m."fechaHoraMovimiento") AS fecha_deuda,
                       o."idPedidoEntrega", o."idReembolso"
                  FROM "MOVIMIENTO_CUENTA" m
                  LEFT JOIN "ORIGEN_MOVIMIENTO_CUENTA" o
                    ON o."idMovimientoCuenta" = m."idMovimientoCuenta"
                  LEFT JOIN "ENTREGA" e ON e."idPedido" = o."idPedidoEntrega"
                 WHERE m."idCliente" = :cliente
                 ORDER BY m."fechaHoraMovimiento", m."numeroSecuenciaCliente"
                """, Map.of("cliente", idCliente), (rs, i) -> {
            Number ped = (Number) rs.getObject("idPedidoEntrega");
            Number reemb = (Number) rs.getObject("idReembolso");
            return new Movimiento(
                    rs.getString("tipoMovimiento"),
                    rs.getBigDecimal("importe"),
                    rs.getTimestamp("fecha_deuda").toInstant(),
                    ped == null ? null : ped.longValue(),
                    reemb != null);
        });

        BigDecimal creditos = BigDecimal.ZERO;
        var deudas = new java.util.ArrayList<DeudaPendiente>();
        for (var m : rows) {
            switch (m.tipo()) {
                case "CARGO_POR_VENTA" -> deudas.add(new DeudaPendiente(m.idPedido(), m.importe(), m.fecha()));
                case "AJUSTE_DEBITO" -> {
                    if (m.esReembolso()) {
                        creditos = creditos.subtract(m.importe());
                    } else {
                        deudas.add(new DeudaPendiente(m.idPedido(), m.importe(), m.fecha()));
                    }
                }
                case "PAGO", "AJUSTE_CREDITO" -> creditos = creditos.add(m.importe());
                case "ANULACION_COBRO" -> creditos = creditos.subtract(m.importe());
                default -> throw new IllegalStateException("Movimiento de Cuenta no reconocido");
            }
        }
        if (creditos.signum() < 0) {
            throw new IllegalStateException("Contraasientos superiores a creditos historicos");
        }

        var porPedidoImp = consultarAplicacionesExplicitaPorPedido(idCliente);
        aplicarExplicitasADeudas(deudas, porPedidoImp);
        BigDecimal totalAplicadoImpB = porPedidoImp.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal comprometidoNoLibre = calcularTotalComprometidoFueraFifo(idCliente, totalAplicadoImpB);
        BigDecimal creditoLibre = creditos.subtract(comprometidoNoLibre).max(BigDecimal.ZERO);

        deudas.sort(java.util.Comparator.comparing(DeudaPendiente::fecha));
        Instant primeraImpaga = null;
        for (var deuda : deudas) {
            if (deuda.pendiente().signum() <= 0) {
                continue;
            }
            if (creditoLibre.compareTo(deuda.pendiente()) >= 0) {
                creditoLibre = creditoLibre.subtract(deuda.pendiente());
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

    private Map<Long, BigDecimal> consultarAplicacionesExplicitaPorPedido(long idCliente) {
        var filas = sql.queryForList("""
                SELECT e."idPedido",
                       COALESCE(SUM(e."importe"), 0)::NUMERIC(18,2) AS aplicado
                  FROM "EVENTO_IMPUTACION_FINANCIERA" e
                  JOIN "COBRO" c ON c."idCobro" = e."idCobro"
                 WHERE e."idCliente" = :cliente
                   AND c."estadoCobro" = 'CONFIRMADO'
                   AND e."tipoEvento" IN ('CONSOLIDACION_ANTICIPO', 'APLICACION_FIFO_LIBRE', 'APLICACION_RENDICION_K2')
                 GROUP BY e."idPedido"
                """, Map.of("cliente", idCliente));
        var mapa = new java.util.HashMap<Long, BigDecimal>();
        for (var row : filas) {
            BigDecimal app = (BigDecimal) row.get("aplicado");
            if (app != null && app.signum() > 0) {
                mapa.put(((Number) row.get("idPedido")).longValue(), app);
            }
        }
        return mapa;
    }

    private static void aplicarExplicitasADeudas(
            List<DeudaPendiente> deudas, Map<Long, BigDecimal> porPedidoImp) {
        if (porPedidoImp.isEmpty()) {
            return;
        }
        for (var deuda : deudas) {
            if (deuda.idPedido() == null) {
                continue;
            }
            BigDecimal disponiblePedido = porPedidoImp.get(deuda.idPedido());
            if (disponiblePedido == null || disponiblePedido.signum() <= 0) {
                continue;
            }
            BigDecimal aplicado = deuda.pendiente().min(disponiblePedido);
            deuda.descontar(aplicado);
        }
    }

    private BigDecimal calcularTotalComprometidoFueraFifo(
            long idCliente, BigDecimal totalAplicadoExistentes) {
        BigDecimal reservasActivas = sql.queryForObject("""
                SELECT COALESCE(SUM(CASE e."tipoEvento"
                    WHEN 'RESERVA_ANTICIPO' THEN e."importe"
                    WHEN 'CONSOLIDACION_ANTICIPO' THEN -e."importe"
                    WHEN 'LIBERACION_ANTICIPO' THEN -e."importe"
                    ELSE 0 END), 0)::NUMERIC(18,2)
                  FROM "EVENTO_IMPUTACION_FINANCIERA" e
                  JOIN "COBRO" c ON c."idCobro" = e."idCobro"
                 WHERE e."idCliente" = :cliente
                   AND c."estadoCobro" = 'CONFIRMADO'
                """, Map.of("cliente", idCliente), BigDecimal.class);
        BigDecimal resNorm = reservasActivas == null ? BigDecimal.ZERO : reservasActivas.max(BigDecimal.ZERO);

        BigDecimal reembolsosPendientes = sql.queryForObject("""
                SELECT COALESCE(SUM(r."importeReembolso"), 0)::NUMERIC(18,2)
                  FROM "REEMBOLSO" r
                  JOIN "COBRO" c ON c."idCobro" = r."idCobro"
                 WHERE c."idCliente" = :cliente
                   AND c."estadoCobro" = 'CONFIRMADO'
                   AND r."estado" = 'PENDIENTE'
                """, Map.of("cliente", idCliente), BigDecimal.class);
        BigDecimal reembNorm = reembolsosPendientes == null ? BigDecimal.ZERO : reembolsosPendientes.max(BigDecimal.ZERO);

        return totalAplicadoExistentes.add(resNorm).add(reembNorm);
    }

    private record Movimiento(String tipo, BigDecimal importe, Instant fecha, Long idPedido, boolean esReembolso) {}

    private static final class DeudaPendiente {
        private final Long idPedido;
        private final Instant fecha;
        private BigDecimal pendiente;

        private DeudaPendiente(Long idPedido, BigDecimal importe, Instant fecha) {
            this.idPedido = idPedido;
            this.pendiente = importe;
            this.fecha = fecha;
        }

        private Long idPedido() { return idPedido; }
        private Instant fecha() { return fecha; }
        private BigDecimal pendiente() { return pendiente; }
        private void descontar(BigDecimal monto) { this.pendiente = this.pendiente.subtract(monto); }
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
}
