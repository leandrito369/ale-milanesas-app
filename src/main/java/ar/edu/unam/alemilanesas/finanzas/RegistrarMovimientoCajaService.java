package ar.edu.unam.alemilanesas.finanzas;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-22: asiento exclusivamente manual, sin efectos en Cobro, Cuenta o Reembolso.
 * Usa seq_gfis_mov_caja_id (V4) vía ProtocoloIdentificadoresFinancieros y conserva el lock
 * puente A1(2929001) para serializar la idempotencia sobre MOVIMIENTO_CAJA.referencia.
 */
@Service
public class RegistrarMovimientoCajaService {
    private static final Set<String> ADMIN = Set.of("ADMINISTRADOR");
    private static final Set<String> TIPOS = Set.of("INGRESO", "EGRESO");
    private static final String PREFIJO_REFERENCIA = "UC22:";
    private static final String IMPORTE_INVALIDO = "Importe invalido";
    private static final BigDecimal LIMITE_EXCLUSIVO_IMPORTE = new BigDecimal("10000000000000000");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    private final ProtocoloIdentificadoresFinancieros idsFinancieros;

    public RegistrarMovimientoCajaService(NamedParameterJdbcTemplate sql,
            AutorizacionSesionInterna autorizacion,
            ProtocoloIdentificadoresFinancieros idsFinancieros) {
        this.sql = sql;
        this.autorizacion = autorizacion;
        this.idsFinancieros = idsFinancieros;
    }

    public record Resultado(long idMovimientoCaja, String tipoMovimiento,
                            BigDecimal importe, String concepto, String observacion,
                            boolean creado) {}

    public static final class ClaveCajaManualEnConflictoException extends RuntimeException {
        public ClaveCajaManualEnConflictoException() {
            super("Clave de operacion reutilizada con otros datos");
        }
    }

    @Transactional
    public Resultado registrarMovimientoCaja(UUID sesion, String tipoMovimiento,
            BigDecimal importe, String concepto, String observacion, UUID claveOperacion) {
        autorizacion.exigirRoles(sesion, ADMIN);
        String tipo = validarTipo(tipoMovimiento);
        BigDecimal monto = validarImporte(importe);
        String detalle = validarConcepto(concepto);
        String nota = normalizarObservacion(observacion);
        if (claveOperacion == null) throw new IllegalArgumentException("Clave tecnica obligatoria");

        idsFinancieros.bloquear();
        // El rol puede haber cambiado mientras la transaccion esperaba el lock global.
        autorizacion.exigirRoles(sesion, ADMIN);
        long actor = idUsuario(sesion);
        String referencia = PREFIJO_REFERENCIA + claveOperacion;
        var previos = sql.queryForList("""
                SELECT "idMovimientoCaja","tipoMovimiento","importe","concepto","observacion",
                       "idUsuarioRegistro"
                  FROM "MOVIMIENTO_CAJA"
                 WHERE "origen"='MANUAL' AND "referencia"=:referencia
                """, Map.of("referencia", referencia));
        if (!previos.isEmpty()) {
            var previo = previos.getFirst();
            if (previos.size() != 1
                    || !tipo.equals(previo.get("tipoMovimiento"))
                    || ((BigDecimal) previo.get("importe")).compareTo(monto) != 0
                    || !detalle.equals(previo.get("concepto"))
                    || !Objects.equals(nota, previo.get("observacion"))
                    || ((Number) previo.get("idUsuarioRegistro")).longValue() != actor) {
                throw new ClaveCajaManualEnConflictoException();
            }
            return new Resultado(((Number) previo.get("idMovimientoCaja")).longValue(),
                    tipo, monto, detalle, nota, false);
        }

        long id = idsFinancieros.siguienteMovimientoCaja();
        sql.update("""
                INSERT INTO "MOVIMIENTO_CAJA"("idMovimientoCaja","tipoMovimiento","importe",
                    "fechaHoraMovimiento","origen","concepto","observacion","referencia",
                    "idUsuarioRegistro")
                VALUES(:id,:tipo,:importe,clock_timestamp(),'MANUAL',:concepto,:observacion,
                    :referencia,:actor)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("tipo", tipo).addValue("importe", monto)
                .addValue("concepto", detalle).addValue("observacion", nota)
                .addValue("referencia", referencia).addValue("actor", actor));
        return new Resultado(id, tipo, monto, detalle, nota, true);
    }

    private long idUsuario(UUID sesion) {
        Long id = sql.queryForObject("""
                SELECT "idUsuario" FROM "SESION_USUARIO"
                 WHERE "idSesionUsuario"=:sesion AND "fechaHoraRevocacion" IS NULL
                   AND "fechaHoraInicio"<=clock_timestamp()
                   AND "fechaHoraExpiracion">clock_timestamp()
                """, Map.of("sesion", sesion), Long.class);
        if (id == null) throw new SecurityException("Sesion no autorizada");
        return id;
    }

    private static String validarTipo(String valor) {
        if (valor == null || !TIPOS.contains(valor)) throw new IllegalArgumentException("Tipo invalido");
        return valor;
    }

    private static BigDecimal validarImporte(BigDecimal valor) {
        if (valor == null
                || valor.signum() <= 0
                || valor.compareTo(LIMITE_EXCLUSIVO_IMPORTE) >= 0
                || valor.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(IMPORTE_INVALIDO);
        }
        try {
            return valor.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(IMPORTE_INVALIDO, ex);
        }
    }

    private static String validarConcepto(String valor) {
        if (valor == null || valor.isBlank()) throw new IllegalArgumentException("Concepto obligatorio");
        String normalizado = valor.strip();
        if (normalizado.length() > 200) throw new IllegalArgumentException("Concepto demasiado largo");
        return normalizado;
    }

    private static String normalizarObservacion(String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }
}
