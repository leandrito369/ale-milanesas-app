package ar.edu.unam.alemilanesas.finanzas;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Protocolo cooperativo para los IDs financieros globales BIGINT (B2-HIBRIDO) y locks A2/puente A1.
 * Centraliza los generadores V4 (seq_gfis_cobro_id, seq_gfis_mov_cuenta_id, seq_gfis_mov_caja_id)
 * y conserva temporalmente el lock puente A1(2929001) mientras coexistan flujos futuros o la
 * serialización de idempotencia sobre MOVIMIENTO_CAJA.referencia.
 */
@Component
public class ProtocoloIdentificadoresFinancieros {
    static final long LOCK_IDS_FINANCIEROS = 2_929_001L;
    static final int NS_CLIENTE = 3202;
    private final JdbcTemplate jdbc;

    public ProtocoloIdentificadoresFinancieros(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void bloquear() {
        jdbc.execute("SELECT pg_catalog.pg_advisory_xact_lock(2929001)");
    }

    public void bloquearClienteA2(long idCliente) {
        if (idCliente <= 0) {
            throw new IllegalArgumentException("Cliente invalido para lock A2");
        }
        jdbc.queryForObject(
                "SELECT pg_catalog.pg_advisory_xact_lock(?, pg_catalog.hashtext(?::text))",
                Object.class,
                NS_CLIENTE,
                Long.toString(idCliente));
    }

    public long siguienteCobro() {
        return validarSiguiente(jdbc.queryForObject(
                "SELECT pg_catalog.nextval('ale_gestion.\"seq_gfis_cobro_id\"'::regclass)",
                Long.class), "Sin ID de Cobro");
    }

    public long siguienteMovimientoCuenta() {
        return validarSiguiente(jdbc.queryForObject(
                "SELECT pg_catalog.nextval('ale_gestion.\"seq_gfis_mov_cuenta_id\"'::regclass)",
                Long.class), "Sin ID de Movimiento de Cuenta");
    }

    public long siguienteMovimientoCaja() {
        return validarSiguiente(jdbc.queryForObject(
                "SELECT pg_catalog.nextval('ale_gestion.\"seq_gfis_mov_caja_id\"'::regclass)",
                Long.class), "Sin ID de Movimiento de Caja");
    }

    private static long validarSiguiente(Long siguiente, String mensajeError) {
        if (siguiente == null || siguiente <= 0) {
            throw new IllegalStateException(mensajeError);
        }
        return siguiente;
    }
}
