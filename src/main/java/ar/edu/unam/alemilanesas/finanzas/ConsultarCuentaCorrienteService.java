package ar.edu.unam.alemilanesas.finanzas;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** UC-14: consulta financiera de solo lectura, exclusiva de VENDEDOR. */
@Service
public class ConsultarCuentaCorrienteService {
    private static final Set<String> VENDEDORES = Set.of("VENDEDOR");
    private static final ZoneId ZONA_NEGOCIO = ZoneId.of("America/Argentina/Buenos_Aires");

    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public ConsultarCuentaCorrienteService(NamedParameterJdbcTemplate sql,
                                            AutorizacionSesionInterna autorizacion) {
        this.sql = sql;
        this.autorizacion = autorizacion;
    }

    public record OrigenMovimiento(String tipo, Long idReferencia) {}

    public record MovimientoCuenta(long idMovimientoCuenta,
                                   long numeroSecuenciaCliente,
                                   String tipoMovimiento,
                                   BigDecimal importe,
                                   OffsetDateTime fechaHoraMovimiento,
                                   BigDecimal saldoHistorico,
                                   OrigenMovimiento origen) {}

    public record EstadoCuentaCorriente(long idCliente,
                                        OffsetDateTime fechaHoraCorte,
                                        BigDecimal saldoConfirmado,
                                        BigDecimal importeTransitoValidado,
                                        BigDecimal saldoExigibleProvisional,
                                        List<MovimientoCuenta> movimientos) {}

    private record Resumen(OffsetDateTime fechaHoraCorte,
                           BigDecimal saldoConfirmado,
                           BigDecimal importeTransitoValidado) {}

    /**
     * El rango restringe solo el historial. Los tres saldos se calculan sobre
     * el estado actual del mismo snapshot PostgreSQL y nunca desde D_e bruto.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EstadoCuentaCorriente consultarCuentaCorriente(UUID sesion,
                                                           long idCliente,
                                                           LocalDate fechaDesde,
                                                           LocalDate fechaHasta) {
        autorizacion.exigirRoles(sesion, VENDEDORES);
        if (idCliente <= 0 || (fechaDesde != null && fechaHasta != null
                && fechaDesde.isAfter(fechaHasta))) {
            throw new IllegalArgumentException("Cliente o rango de fechas invalido");
        }

        OffsetDateTime inicio = fechaDesde == null
                ? null : fechaDesde.atStartOfDay(ZONA_NEGOCIO).toOffsetDateTime();
        OffsetDateTime finExclusivo;
        try {
            finExclusivo = fechaHasta == null ? null
                    : fechaHasta.plusDays(1).atStartOfDay(ZONA_NEGOCIO).toOffsetDateTime();
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("Rango de fechas invalido", ex);
        }

        List<Resumen> resumenes = sql.query("""
                SELECT transaction_timestamp() AS "fechaHoraCorte",
                       COALESCE((
                           SELECT m."saldoResultante"
                             FROM "MOVIMIENTO_CUENTA" m
                            WHERE m."idCliente" = c."idCliente"
                            ORDER BY m."numeroSecuenciaCliente" DESC
                            LIMIT 1
                       ), 0::NUMERIC(18,2)) AS "saldoConfirmado",
                       COALESCE((
                           SELECT SUM(p."reconocimientoVigenteVvig")
                             FROM "RECONOCIMIENTO_ENTREGA_PE1" p
                            WHERE p."idCliente" = c."idCliente"
                       ), 0::NUMERIC(18,2)) AS "importeTransitoValidado"
                  FROM "CLIENTE" c
                 WHERE c."idCliente" = :cliente
                """, Map.of("cliente", idCliente), (rs, fila) -> new Resumen(
                rs.getObject("fechaHoraCorte", OffsetDateTime.class),
                rs.getBigDecimal("saldoConfirmado"),
                rs.getBigDecimal("importeTransitoValidado")));
        if (resumenes.size() != 1) {
            throw new IllegalArgumentException("Cliente inexistente");
        }

        var parametros = new MapSqlParameterSource().addValue("cliente", idCliente);
        var consulta = new StringBuilder("""
                SELECT m."idMovimientoCuenta", m."numeroSecuenciaCliente",
                       m."tipoMovimiento", m."importe", m."fechaHoraMovimiento",
                       m."saldoResultante", m."idUsuarioAjuste",
                       o."idPedidoEntrega", o."idCobro", o."idReembolso",
                       o."idCobroAnulado"
                  FROM "MOVIMIENTO_CUENTA" m
                  LEFT JOIN "ORIGEN_MOVIMIENTO_CUENTA" o
                    ON o."idMovimientoCuenta" = m."idMovimientoCuenta"
                 WHERE m."idCliente" = :cliente
                """);
        if (inicio != null) {
            consulta.append(" AND m.\"fechaHoraMovimiento\" >= :inicio ");
            parametros.addValue("inicio", inicio);
        }
        if (finExclusivo != null) {
            consulta.append(" AND m.\"fechaHoraMovimiento\" < :finExclusivo ");
            parametros.addValue("finExclusivo", finExclusivo);
        }
        consulta.append(" ORDER BY m.\"numeroSecuenciaCliente\" ASC ");

        List<MovimientoCuenta> movimientos = sql.query(consulta.toString(), parametros,
                (rs, fila) -> new MovimientoCuenta(
                        rs.getLong("idMovimientoCuenta"),
                        rs.getLong("numeroSecuenciaCliente"),
                        rs.getString("tipoMovimiento"),
                        rs.getBigDecimal("importe"),
                        rs.getObject("fechaHoraMovimiento", OffsetDateTime.class),
                        rs.getBigDecimal("saldoResultante"),
                        origen(rs)));

        Resumen resumen = resumenes.getFirst();
        return new EstadoCuentaCorriente(
                idCliente,
                resumen.fechaHoraCorte(),
                resumen.saldoConfirmado(),
                resumen.importeTransitoValidado(),
                resumen.saldoConfirmado().subtract(resumen.importeTransitoValidado()),
                List.copyOf(movimientos));
    }

    private static OrigenMovimiento origen(ResultSet rs) throws SQLException {
        Long idPedido = rs.getObject("idPedidoEntrega", Long.class);
        if (idPedido != null) {
            return new OrigenMovimiento("ENTREGA", idPedido);
        }
        Long idCobro = rs.getObject("idCobro", Long.class);
        if (idCobro != null) {
            return new OrigenMovimiento("COBRO", idCobro);
        }
        Long idReembolso = rs.getObject("idReembolso", Long.class);
        if (idReembolso != null) {
            return new OrigenMovimiento("REEMBOLSO", idReembolso);
        }
        Long idCobroAnulado = rs.getObject("idCobroAnulado", Long.class);
        if (idCobroAnulado != null) {
            return new OrigenMovimiento("COBRO_ANULADO", idCobroAnulado);
        }
        Long idUsuarioAjuste = rs.getObject("idUsuarioAjuste", Long.class);
        return idUsuarioAjuste == null ? null
                : new OrigenMovimiento("AJUSTE_USUARIO", idUsuarioAjuste);
    }
}
