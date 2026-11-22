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
    public static final int NS_RECORRIDO = 4100;
    public static final int NS_REPARTIDOR = 4101;
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

    public void bloquearRecorridoA2(long idRecorrido) {
        if (idRecorrido <= 0) {
            throw new IllegalArgumentException("Recorrido invalido para lock A2");
        }
        jdbc.queryForObject(
                "SELECT pg_catalog.pg_advisory_xact_lock(?, pg_catalog.hashtext(?::text))",
                Object.class,
                NS_RECORRIDO,
                Long.toString(idRecorrido));
    }

    public void bloquearRepartidorA2(long idUsuarioRepartidor) {
        if (idUsuarioRepartidor <= 0) {
            throw new IllegalArgumentException("Repartidor invalido para lock A2");
        }
        jdbc.queryForObject(
                "SELECT pg_catalog.pg_advisory_xact_lock(?, pg_catalog.hashtext(?::text))",
                Object.class,
                NS_REPARTIDOR,
                Long.toString(idUsuarioRepartidor));
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

    public long siguienteRecorrido() {
        return validarSiguiente(jdbc.queryForObject(
                "SELECT pg_catalog.nextval('ale_gestion.\"seq_gfis_recorrido\"'::regclass)",
                Long.class), "Sin ID de Recorrido");
    }

    public long siguienteRecorridoAsignacion() {
        return validarSiguiente(jdbc.queryForObject(
                "SELECT pg_catalog.nextval('ale_gestion.\"seq_gfis_recorrido_asignacion\"'::regclass)",
                Long.class), "Sin ID de RecorridoAsignacion");
    }

    public long siguienteEventoImp() {
        return validarSiguiente(jdbc.queryForObject(
                "SELECT pg_catalog.nextval('ale_gestion.\"seq_gfis_evento_imp\"'::regclass)",
                Long.class), "Sin ID de EventoImputacion");
    }

    private static long validarSiguiente(Long siguiente, String mensajeError) {
        if (siguiente == null || siguiente <= 0) {
            throw new IllegalStateException(mensajeError);
        }
        return siguiente;
    }
}
