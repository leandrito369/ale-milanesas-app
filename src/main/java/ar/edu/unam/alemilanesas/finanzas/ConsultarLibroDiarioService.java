package ar.edu.unam.alemilanesas.finanzas;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC-23: Libro Diario monetario. No representa arqueo fisico ni utilidad. */
@Service
public class ConsultarLibroDiarioService {
    private static final Set<String> ADMIN = Set.of("ADMINISTRADOR");
    private static final ZoneId ZONA_NEGOCIO = ZoneId.of("America/Argentina/Buenos_Aires");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public ConsultarLibroDiarioService(NamedParameterJdbcTemplate sql,
                                       AutorizacionSesionInterna autorizacion) {
        this.sql = sql;
        this.autorizacion = autorizacion;
    }

    public record Movimiento(long idMovimientoCaja, String tipoMovimiento,
                             BigDecimal importe, OffsetDateTime fechaHoraMovimiento,
                             String origen, String concepto, String referencia) {}

    public record LibroDiario(LocalDate fechaInicial, LocalDate fechaFinal,
                              List<Movimiento> movimientos, BigDecimal totalIngresos,
                              BigDecimal totalEgresos, BigDecimal resultadoMonetario) {}

    @Transactional(readOnly = true)
    public LibroDiario consultarLibroDiario(UUID sesion, LocalDate fechaInicial,
                                            LocalDate fechaFinal) {
        autorizacion.exigirRoles(sesion, ADMIN);
        if (fechaInicial == null || fechaFinal == null || fechaInicial.isAfter(fechaFinal)) {
            throw new IllegalArgumentException("Periodo de Libro Diario invalido");
        }
        var parametros = Map.of(
                "inicio", fechaInicial.atStartOfDay(ZONA_NEGOCIO).toOffsetDateTime(),
                "finExclusivo", fechaFinal.plusDays(1).atStartOfDay(ZONA_NEGOCIO).toOffsetDateTime());
        List<Movimiento> movimientos = sql.query("""
                SELECT "idMovimientoCaja", "tipoMovimiento", "importe",
                       "fechaHoraMovimiento", "origen", "concepto", "referencia"
                  FROM "MOVIMIENTO_CAJA"
                 WHERE "fechaHoraMovimiento" >= :inicio
                   AND "fechaHoraMovimiento" < :finExclusivo
                 ORDER BY "fechaHoraMovimiento", "idMovimientoCaja"
                """, parametros, (rs, fila) -> new Movimiento(
                rs.getLong("idMovimientoCaja"), rs.getString("tipoMovimiento"),
                rs.getBigDecimal("importe"), rs.getObject("fechaHoraMovimiento", OffsetDateTime.class),
                rs.getString("origen"), rs.getString("concepto"), rs.getString("referencia")));

        // Los totales se derivan de la misma lista consultada: no hay segunda lectura
        // que pueda observar un alta concurrente diferente.
        BigDecimal ingresos = movimientos.stream()
                .filter(m -> "INGRESO".equals(m.tipoMovimiento()))
                .map(Movimiento::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal egresos = movimientos.stream()
                .filter(m -> "EGRESO".equals(m.tipoMovimiento()))
                .map(Movimiento::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new LibroDiario(fechaInicial, fechaFinal, List.copyOf(movimientos),
                ingresos, egresos, ingresos.subtract(egresos));
    }
}
