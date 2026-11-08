package ar.edu.unam.alemilanesas.abastecimiento;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC42 INSUMO, RD-UC42-001 G1/G2/G3/G5. G4 permanece pendiente. */
@Service
public class RegistrarAjusteInventarioService {
    private static final String INICIAL = "SALDO_INICIAL";
    private static final String POSITIVO = "AJUSTE_POSITIVO";
    private static final String NEGATIVO = "AJUSTE_NEGATIVO";
    private static final String INVALIDA = "Solicitud invalida";
    private static final String INSUMO = "insumo";
    private static final String PARTIDA = "partida";
    private static final String FECHA = "fecha";
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    private final EpisodioFaltanteService episodios;

    public RegistrarAjusteInventarioService(NamedParameterJdbcTemplate sql,
            AutorizacionSesionInterna autorizacion, EpisodioFaltanteService episodios) {
        this.sql = sql; this.autorizacion = autorizacion; this.episodios = episodios;
    }
    public record DatosAjuste(String tipoElemento, long idElemento, String tipoMovimiento,
            BigDecimal cantidad, String motivo, BigDecimal costoValuacion, List<PartidaAjuste> partidas) {}
    public record PartidaAjuste(Long idPartidaInsumo, BigDecimal cantidad, String codigoLote,
            LocalDate fechaVencimiento, Boolean marcarApertura) {}
    public record AjusteRegistrado(long idMovimientoInsumo, String tipoMovimiento,
            int cantidadPartidas, BigDecimal nuevoStockActual) {}

    @Transactional
    public AjusteRegistrado registrarAjusteInventario(UUID sesion, DatosAjuste datos) {
        autorizacion.exigirRoles(sesion, Set.of("RESPONSABLE_PRODUCCION"));
        validar(datos);
        // Mismo orden de locks que UC08: MAX+1 global y luego insumo.
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(808, 0)");
        sql.getJdbcTemplate().queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, datos.idElemento());
        autorizacion.exigirRoles(sesion, Set.of("RESPONSABLE_PRODUCCION"));
        var p = new MapSqlParameterSource(INSUMO, datos.idElemento());
        var filas = sql.queryForList("""
                SELECT "stockActual","umbralMinimo","activo" FROM "INSUMO"
                WHERE "idInsumo"=:insumo FOR UPDATE
                """, p);
        if (filas.isEmpty()) throw new IllegalArgumentException("Insumo inexistente");
        var insumo = filas.getFirst();
        if (!Boolean.TRUE.equals(insumo.get("activo"))) throw new IllegalArgumentException("Insumo inactivo");
        BigDecimal stockAnterior = (BigDecimal) insumo.get("stockActual");
        Long secuencia = sql.queryForObject("""
                SELECT COALESCE(MAX("numeroSecuenciaInsumo"),0)+1 FROM "MOVIMIENTO_INSUMO"
                WHERE "idInsumo"=:insumo
                """, p, Long.class);
        validarApertura(datos, stockAnterior, secuencia, p);
        if (NEGATIVO.equals(datos.tipoMovimiento())) validarDisponibilidad(datos, p);
        OffsetDateTime fecha = sql.queryForObject("""
                SELECT GREATEST(clock_timestamp(), COALESCE(MAX("vigenteDesde") + interval '1 microsecond',
                    clock_timestamp())) FROM "COSTO_INSUMO_HISTORICO" WHERE "idInsumo"=:insumo
                """, p, OffsetDateTime.class);
        p.addValue(FECHA, fecha);
        OffsetDateTime costo = determinarCosto(datos, p);
        long movimiento = siguiente("SELECT COALESCE(MAX(\"idMovimientoInsumo\"),0)+1 FROM \"MOVIMIENTO_INSUMO\"");
        BigDecimal stock = NEGATIVO.equals(datos.tipoMovimiento())
                ? stockAnterior.subtract(datos.cantidad()) : stockAnterior.add(datos.cantidad());
        if (stock.precision() - stock.scale() > 15) throw new IllegalArgumentException(INVALIDA);
        Long usuario = sql.queryForObject("SELECT \"idUsuario\" FROM \"SESION_USUARIO\" WHERE \"idSesionUsuario\"=:sesion",
                Map.of("sesion", sesion), Long.class);
        p.addValue("movimiento", movimiento).addValue("secuencia", secuencia).addValue("costoDesde", costo)
                .addValue("usuario", usuario).addValue("tipo", datos.tipoMovimiento()).addValue("cantidad", datos.cantidad())
                .addValue("stock", stock).addValue("motivo", datos.motivo())
                .addValue("origen", INICIAL.equals(datos.tipoMovimiento()) ? "APERTURA_INVENTARIO" : "AJUSTE");
        // El CHECK de INSUMO es la última defensa y revierte también el costo nuevo.
        sql.update("UPDATE \"INSUMO\" SET \"stockActual\"=:stock WHERE \"idInsumo\"=:insumo", p);
        sql.update("""
                INSERT INTO "MOVIMIENTO_INSUMO" ("idMovimientoInsumo","idInsumo","numeroSecuenciaInsumo",
                    "idPreparacion","idRecepcionCompra","vigenteDesdeCosto","idUsuarioResponsable","tipoMovimiento",
                    "cantidad","fechaHora","stockResultante","origen","referenciaOrigen","motivo","observacion")
                VALUES (:movimiento,:insumo,:secuencia,NULL,NULL,:costoDesde,:usuario,:tipo,
                    :cantidad,:fecha,:stock,:origen,NULL,:motivo,NULL)
                """, p);
        for (PartidaAjuste partida : datos.partidas()) registrarPartida(datos.tipoMovimiento(), partida, p);
        BigDecimal aplicado = sql.queryForObject("""
                SELECT SUM("cantidadAplicada") FROM "DETALLE_MOVIMIENTO_PARTIDA"
                WHERE "idMovimientoInsumo"=:movimiento
                """, p, BigDecimal.class);
        if (aplicado == null || aplicado.compareTo(datos.cantidad()) != 0)
            throw new IllegalStateException("Desglose de movimiento inconsistente");
        BigDecimal umbral = (BigDecimal) insumo.get("umbralMinimo");
        if (NEGATIVO.equals(datos.tipoMovimiento())) episodios.evaluarApertura(datos.idElemento(), stock, umbral, movimiento);
        else episodios.evaluarCierre(datos.idElemento(), stock, umbral);
        return new AjusteRegistrado(movimiento, datos.tipoMovimiento(), datos.partidas().size(), stock);
    }

    private void validarApertura(DatosAjuste d, BigDecimal stock, long secuencia, MapSqlParameterSource p) {
        if (!INICIAL.equals(d.tipoMovimiento())) return;
        Integer previos = sql.queryForObject("""
                SELECT COUNT(*) FROM "MOVIMIENTO_INSUMO" WHERE "idInsumo"=:insumo AND "tipoMovimiento"='SALDO_INICIAL'
                """, p, Integer.class);
        if (previos != null && previos > 0) throw new IllegalArgumentException("SALDO_INICIAL_DUPLICADO");
        if (secuencia != 1 || stock.signum() != 0)
            throw new IllegalArgumentException("Saldo inicial requiere insumo sin movimientos ni stock");
    }

    private void validarDisponibilidad(DatosAjuste d, MapSqlParameterSource p) {
        for (PartidaAjuste partida : d.partidas()) {
            p.addValue(PARTIDA, partida.idPartidaInsumo());
            var disponibles = sql.queryForList("""
                    SELECT "cantidadDisponible" FROM "PARTIDA_INSUMO"
                    WHERE "idPartidaInsumo"=:partida AND "idInsumo"=:insumo FOR UPDATE
                    """, p);
            if (disponibles.isEmpty()) throw new IllegalArgumentException("Partida no pertenece al insumo");
            if (((BigDecimal) disponibles.getFirst().get("cantidadDisponible")).compareTo(partida.cantidad()) < 0)
                throw new IllegalArgumentException("Cantidad excede disponible en partida");
        }
    }

    private OffsetDateTime determinarCosto(DatosAjuste d, MapSqlParameterSource p) {
        if (!NEGATIVO.equals(d.tipoMovimiento()) && d.costoValuacion() != null) {
            p.addValue("costo", d.costoValuacion()).addValue("fuente", d.tipoMovimiento());
            sql.update("""
                    UPDATE "COSTO_INSUMO_HISTORICO" SET "vigenteHasta"=:fecha
                    WHERE "idInsumo"=:insumo AND "vigenteHasta" IS NULL
                    """, p);
            sql.update("""
                    INSERT INTO "COSTO_INSUMO_HISTORICO" ("idInsumo","vigenteDesde","vigenteHasta",
                        "costoUnitario","fuenteCosto","referenciaOrigen","idRecepcionCompra")
                    VALUES (:insumo,:fecha,NULL,:costo,:fuente,NULL,NULL)
                    """, p);
            return (OffsetDateTime) p.getValue(FECHA);
        }
        var costos = sql.query("""
                SELECT "vigenteDesde" FROM "COSTO_INSUMO_HISTORICO"
                WHERE "idInsumo"=:insumo AND "vigenteHasta" IS NULL AND "costoUnitario">0
                ORDER BY "vigenteDesde" DESC LIMIT 1
                """, p, (rs, n) -> rs.getObject(1, OffsetDateTime.class));
        if (costos.isEmpty()) throw new IllegalArgumentException("COSTO_HISTORICO_REQUERIDO");
        return costos.getFirst();
    }

    private void registrarPartida(String tipo, PartidaAjuste partida, MapSqlParameterSource p) {
        p.addValue("aplicada", partida.cantidad());
        if (NEGATIVO.equals(tipo)) {
            p.addValue(PARTIDA, partida.idPartidaInsumo());
            if (Boolean.TRUE.equals(partida.marcarApertura())) sql.update("""
                    UPDATE "PARTIDA_INSUMO" SET "fechaHoraApertura"=COALESCE("fechaHoraApertura",:fecha)
                    WHERE "idPartidaInsumo"=:partida AND "idInsumo"=:insumo
                    """, p);
        } else {
            p.addValue(PARTIDA, siguiente("SELECT COALESCE(MAX(\"idPartidaInsumo\"),0)+1 FROM \"PARTIDA_INSUMO\""))
                    .addValue("lote", partida.codigoLote()).addValue("vencimiento", partida.fechaVencimiento());
            sql.update("""
                    INSERT INTO "PARTIDA_INSUMO" ("idPartidaInsumo","idInsumo","idRecepcionCompra","codigoLote",
                        "fechaFabricacion","fechaVencimiento","fechaHoraRegistro","fechaHoraApertura",
                        "cantidadInicial","cantidadDisponible")
                    VALUES (:partida,:insumo,NULL,:lote,NULL,:vencimiento,:fecha,NULL,:aplicada,0)
                    """, p);
        }
        sql.update("""
                INSERT INTO "DETALLE_MOVIMIENTO_PARTIDA"
                    ("idMovimientoInsumo","idPartidaInsumo","idInsumo","cantidadAplicada")
                VALUES (:movimiento,:partida,:insumo,:aplicada)
                """, p);
    }
    private long siguiente(String consulta) {
        Long id = sql.getJdbcTemplate().queryForObject(consulta, Long.class);
        if (id == null || id <= 0) throw new IllegalStateException("Sin identificador");
        return id;
    }
    private static boolean positivo(BigDecimal n, int escala, int enteros) {
        return n != null && n.signum() > 0 && n.scale() <= escala && n.precision() - n.scale() <= enteros;
    }
    private static void validar(DatosAjuste d) {
        if (d == null) throw new IllegalArgumentException(INVALIDA);
        if (!"INSUMO".equals(d.tipoElemento())) throw new IllegalArgumentException("Tipo de elemento no soportado en este corte");
        if (d.idElemento() <= 0 || d.tipoMovimiento() == null || !Set.of(INICIAL, POSITIVO, NEGATIVO).contains(d.tipoMovimiento())
                || !positivo(d.cantidad(),3,15) || d.motivo() == null || d.motivo().isBlank() || d.motivo().indexOf('\0') >= 0
                || d.partidas() == null || d.partidas().isEmpty()) throw new IllegalArgumentException(INVALIDA);
        validarCosto(d);
        var ids = new HashSet<Long>();
        BigDecimal suma = BigDecimal.ZERO;
        for (PartidaAjuste partida : d.partidas()) {
            validarPartida(d.tipoMovimiento(), partida, ids);
            suma = suma.add(partida.cantidad());
        }
        if (suma.compareTo(d.cantidad()) != 0) throw new IllegalArgumentException(INVALIDA);
    }
    private static void validarCosto(DatosAjuste d) {
        if (INICIAL.equals(d.tipoMovimiento()) && !positivo(d.costoValuacion(),4,14))
            throw new IllegalArgumentException("COSTO_VALUACION_REQUERIDO");
        if (POSITIVO.equals(d.tipoMovimiento()) && d.costoValuacion() != null && !positivo(d.costoValuacion(),4,14))
            throw new IllegalArgumentException(INVALIDA);
    }
    private static void validarPartida(String tipo, PartidaAjuste p, Set<Long> ids) {
        if (p == null || !positivo(p.cantidad(),3,15)) throw new IllegalArgumentException(INVALIDA);
        if (NEGATIVO.equals(tipo)) {
            if (p.idPartidaInsumo() == null || p.idPartidaInsumo() <= 0 || !ids.add(p.idPartidaInsumo()))
                throw new IllegalArgumentException(INVALIDA);
        } else if (p.idPartidaInsumo() != null || Boolean.TRUE.equals(p.marcarApertura())) {
            throw new IllegalArgumentException(INVALIDA);
        }
        if (p.codigoLote() != null && (p.codigoLote().length() > 80 || p.codigoLote().indexOf('\0') >= 0))
            throw new IllegalArgumentException(INVALIDA);
    }
}
