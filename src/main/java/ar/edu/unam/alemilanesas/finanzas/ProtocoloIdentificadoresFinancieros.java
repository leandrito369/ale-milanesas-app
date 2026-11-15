package ar.edu.unam.alemilanesas.finanzas;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Protocolo cooperativo vigente para los IDs financieros BIGINT definidos por MR5.
 * Todo escritor de MOVIMIENTO_CAJA debe tomar este lock antes de calcular MAX+1.
 */
@Component
public class ProtocoloIdentificadoresFinancieros {
    static final long LOCK_IDS_FINANCIEROS = 2_929_001L;
    private final JdbcTemplate jdbc;

    public ProtocoloIdentificadoresFinancieros(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void bloquear() {
        jdbc.execute("SELECT pg_advisory_xact_lock(2929001)");
    }

    public long siguienteMovimientoCaja() {
        Long siguiente = jdbc.queryForObject("""
                SELECT COALESCE(MAX("idMovimientoCaja"),0)+1 FROM "MOVIMIENTO_CAJA"
                """, Long.class);
        if (siguiente == null || siguiente <= 0) {
            throw new IllegalStateException("Sin ID de Movimiento de Caja");
        }
        return siguiente;
    }
}
