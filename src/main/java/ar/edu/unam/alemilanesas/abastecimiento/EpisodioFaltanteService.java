package ar.edu.unam.alemilanesas.abastecimiento;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PRA-R02/R10. Participa de la transacción que bloquea el insumo; no emite compras. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class EpisodioFaltanteService {
    private final NamedParameterJdbcTemplate sql;
    public EpisodioFaltanteService(NamedParameterJdbcTemplate sql) { this.sql = sql; }

    public void evaluarCierre(long idInsumo, BigDecimal stockResultante, BigDecimal umbralMinimo) {
        if (stockResultante.compareTo(umbralMinimo) >= 0) {
            evaluarCierre(idInsumo, sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", OffsetDateTime.class));
        }
    }

    // SQL y timestamp de UC08 conservados: el umbral solo no basta para cerrar.
    public void evaluarCierre(long idInsumo, OffsetDateTime fecha) {
        var p = new MapSqlParameterSource("insumo", idInsumo).addValue("fecha", fecha);
        sql.update("""
                UPDATE "EPISODIO_FALTANTE" e SET "fechaHoraCierre"=:fecha
                WHERE e."idInsumo"=:insumo AND e."fechaHoraCierre" IS NULL
                AND (SELECT "stockActual">="umbralMinimo" FROM "INSUMO" WHERE "idInsumo"=:insumo)
                AND NOT EXISTS (SELECT 1 FROM "SOLICITUD_COTIZACION" s WHERE s."idEpisodio"=e."idEpisodio"
                    AND s."estado" IN ('RECEPCION_ABIERTA','PENDIENTE_DECISION'))
                AND NOT EXISTS (SELECT 1 FROM "SOLICITUD_COTIZACION" s
                    JOIN "DECISION_COMPRA" d USING ("idSolicitud") JOIN "ORDEN_COMPRA" o USING ("idDecisionCompra")
                    WHERE s."idEpisodio"=e."idEpisodio" AND o."estado" IN ('PENDIENTE','PARCIALMENTE_RECIBIDA'))
                """, p);
    }

    public void evaluarApertura(long idInsumo, BigDecimal stockResultante,
            BigDecimal umbralMinimo, long idMovimientoInsumo) {
        if (stockResultante.compareTo(umbralMinimo) >= 0) return;
        var p = new MapSqlParameterSource("insumo", idInsumo).addValue("movimiento", idMovimientoInsumo);
        sql.update("""
                INSERT INTO "EPISODIO_FALTANTE"
                    ("idEpisodio","idInsumo","fechaHoraInicio","idMovimientoApertura")
                SELECT (SELECT COALESCE(MAX("idEpisodio"),0)+1 FROM "EPISODIO_FALTANTE"),
                    :insumo,clock_timestamp(),:movimiento
                WHERE NOT EXISTS (SELECT 1 FROM "EPISODIO_FALTANTE"
                    WHERE "idInsumo"=:insumo AND "fechaHoraCierre" IS NULL)
                """, p);
    }
}
