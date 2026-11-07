package ar.edu.unam.alemilanesas.abastecimiento;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC08 INSUMO, REV03B §7 / H01-H02 / RD-RP022-001 y MR-6. Sin REVENTA. */
@Service
public class RegistrarIngresoStockInsumoService {
    private static final Set<String> PRODUCCION = Set.of("RESPONSABLE_PRODUCCION");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public RegistrarIngresoStockInsumoService(NamedParameterJdbcTemplate sql,
            AutorizacionSesionInterna autorizacion) {
        this.sql = sql;
        this.autorizacion = autorizacion;
    }

    public record DatosIngreso(long idInsumo, long idOrdenCompra, BigDecimal cantidadRecibida,
            String codigoUnidadMedidaRecibida, BigDecimal costoUnitarioCompra, String comprobante) {}
    public record IngresoRegistrado(long idRecepcionCompra, long idMovimientoInsumo,
            long idPartidaInsumo, String estadoOrdenCompra) {}

    @Transactional
    public IngresoRegistrado registrarIngresoStockPorCompra(UUID sesion, DatosIngreso datos) {
        autorizacion.exigirRoles(sesion, PRODUCCION);
        validar(datos);
        // MAX+1 global: serializar también ingresos simultáneos de insumos diferentes.
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(808, 0)");
        sql.getJdbcTemplate().queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, datos.idInsumo());
        autorizacion.exigirRoles(sesion, PRODUCCION);
        var p = new MapSqlParameterSource("insumo", datos.idInsumo())
                .addValue("orden", datos.idOrdenCompra()).addValue("cantidad", datos.cantidadRecibida())
                .addValue("unidad", datos.codigoUnidadMedidaRecibida())
                .addValue("costo", datos.costoUnitarioCompra()).addValue("comprobante", datos.comprobante());
        var insumos = sql.queryForList("""
                SELECT "codigoUnidadMedida","stockActual","activo" FROM "INSUMO"
                WHERE "idInsumo"=:insumo FOR UPDATE
                """, p);
        if (insumos.isEmpty()) throw new IllegalArgumentException("Insumo inexistente");
        var insumo = insumos.getFirst();
        if (!Boolean.TRUE.equals(insumo.get("activo"))) throw new IllegalArgumentException("Insumo inactivo");
        if (!datos.codigoUnidadMedidaRecibida().equals(insumo.get("codigoUnidadMedida")))
            throw new IllegalArgumentException("Unidad no coincide");
        var ordenes = sql.queryForList("""
                SELECT o."cantidadOrdenada",o."estado",e."idInsumo"
                FROM "ORDEN_COMPRA" o JOIN "DECISION_COMPRA" d USING ("idDecisionCompra")
                JOIN "SOLICITUD_COTIZACION" s USING ("idSolicitud")
                JOIN "EPISODIO_FALTANTE" e USING ("idEpisodio")
                WHERE o."idOrdenCompra"=:orden FOR UPDATE OF o
                """, p);
        if (ordenes.isEmpty()) throw new IllegalArgumentException("Orden de compra inexistente");
        var orden = ordenes.getFirst();
        if ("CANCELADA".equals(orden.get("estado"))) throw new IllegalArgumentException("Orden cancelada");
        if ("RECIBIDA".equals(orden.get("estado"))) throw new IllegalArgumentException("Orden ya recibida");
        if (((Number) orden.get("idInsumo")).longValue() != datos.idInsumo())
            throw new IllegalArgumentException("Orden de otro insumo");
        BigDecimal recibido = sql.queryForObject("""
                SELECT COALESCE(SUM("cantidadRecibida"),0) FROM "RECEPCION_COMPRA"
                WHERE "idOrdenCompra"=:orden
                """, p, BigDecimal.class);
        BigDecimal total = recibido.add(datos.cantidadRecibida());
        BigDecimal ordenado = (BigDecimal) orden.get("cantidadOrdenada");
        if (total.compareTo(ordenado) > 0) throw new IllegalArgumentException("Cantidad excede lo pendiente");
        BigDecimal stock = ((BigDecimal) insumo.get("stockActual")).add(datos.cantidadRecibida());
        if (stock.precision() - stock.scale() > 15) throw new IllegalArgumentException("Solicitud invalida");
        Long usuario = sql.queryForObject("""
                SELECT "idUsuario" FROM "SESION_USUARIO" WHERE "idSesionUsuario"=:sesion
                """, Map.of("sesion", sesion), Long.class);
        // Reloj DB posterior al lock; un único valor con precisión PostgreSQL para H01.
        OffsetDateTime fecha = sql.queryForObject("""
                SELECT GREATEST(clock_timestamp(), COALESCE(MAX("vigenteDesde") + interval '1 microsecond',
                    clock_timestamp())) FROM "COSTO_INSUMO_HISTORICO" WHERE "idInsumo"=:insumo
                """, p, OffsetDateTime.class);
        long recepcion = siguiente("RECEPCION_COMPRA", "idRecepcionCompra");
        long movimiento = siguiente("MOVIMIENTO_INSUMO", "idMovimientoInsumo");
        long partida = siguiente("PARTIDA_INSUMO", "idPartidaInsumo");
        Long secuencia = sql.queryForObject("""
                SELECT COALESCE(MAX("numeroSecuenciaInsumo"),0)+1 FROM "MOVIMIENTO_INSUMO"
                WHERE "idInsumo"=:insumo
                """, p, Long.class);
        p.addValue("usuario", usuario).addValue("fecha", fecha).addValue("recepcion", recepcion)
                .addValue("movimiento", movimiento).addValue("partida", partida)
                .addValue("secuencia", secuencia).addValue("stock", stock);
        sql.update("""
                INSERT INTO "RECEPCION_COMPRA" ("idRecepcionCompra","idOrdenCompra","idProveedorDirecto",
                    "idInsumoRecibido","idProductoReventa","idUsuarioResponsable","fechaHoraRecepcion",
                    "cantidadRecibida","codigoUnidadMedida","costoUnitarioReal","factorConversionAplicado",
                    "referenciaComercial")
                VALUES (:recepcion,:orden,NULL,:insumo,NULL,:usuario,:fecha,:cantidad,:unidad,:costo,1,:comprobante)
                """, p);
        sql.update("""
                UPDATE "COSTO_INSUMO_HISTORICO" SET "vigenteHasta"=:fecha
                WHERE "idInsumo"=:insumo AND "vigenteHasta" IS NULL
                """, p);
        // H01: costo ANTES del movimiento que referencia su clave temporal exacta.
        sql.update("""
                INSERT INTO "COSTO_INSUMO_HISTORICO" ("idInsumo","vigenteDesde","vigenteHasta",
                    "costoUnitario","fuenteCosto","referenciaOrigen","idRecepcionCompra")
                VALUES (:insumo,:fecha,NULL,:costo,'COMPRA',:comprobante,:recepcion)
                """, p);
        sql.update("""
                INSERT INTO "MOVIMIENTO_INSUMO" ("idMovimientoInsumo","idInsumo","numeroSecuenciaInsumo",
                    "idPreparacion","idRecepcionCompra","vigenteDesdeCosto","idUsuarioResponsable",
                    "tipoMovimiento","cantidad","fechaHora","stockResultante","origen",
                    "referenciaOrigen","motivo","observacion")
                VALUES (:movimiento,:insumo,:secuencia,NULL,:recepcion,:fecha,:usuario,
                    'INGRESO_COMPRA',:cantidad,:fecha,:stock,'COMPRA',:comprobante,NULL,NULL)
                """, p);
        sql.update("""
                INSERT INTO "PARTIDA_INSUMO" ("idPartidaInsumo","idInsumo","idRecepcionCompra","codigoLote",
                    "fechaFabricacion","fechaVencimiento","fechaHoraRegistro","fechaHoraApertura",
                    "cantidadInicial","cantidadDisponible")
                VALUES (:partida,:insumo,:recepcion,'LOTE_' || CAST(:recepcion AS text),NULL,NULL,:fecha,NULL,:cantidad,0)
                """, p);
        // V3 acredita el ingreso: no actualizar cantidadDisponible desde Java.
        sql.update("""
                INSERT INTO "DETALLE_MOVIMIENTO_PARTIDA"
                    ("idMovimientoInsumo","idPartidaInsumo","idInsumo","cantidadAplicada")
                VALUES (:movimiento,:partida,:insumo,:cantidad)
                """, p);
        BigDecimal aplicado = sql.queryForObject("""
                SELECT SUM("cantidadAplicada") FROM "DETALLE_MOVIMIENTO_PARTIDA"
                WHERE "idMovimientoInsumo"=:movimiento
                """, p, BigDecimal.class);
        if (aplicado == null || aplicado.compareTo(datos.cantidadRecibida()) != 0)
            throw new IllegalStateException("Desglose de movimiento inconsistente");
        sql.update("UPDATE \"INSUMO\" SET \"stockActual\"=:stock WHERE \"idInsumo\"=:insumo", p);
        String estado = total.compareTo(ordenado) == 0 ? "RECIBIDA" : "PARCIALMENTE_RECIBIDA";
        p.addValue("estado", estado);
        sql.update("UPDATE \"ORDEN_COMPRA\" SET \"estado\"=:estado WHERE \"idOrdenCompra\"=:orden", p);
        cerrarEpisodios(p);
        return new IngresoRegistrado(recepcion, movimiento, partida, estado);
    }

    private void cerrarEpisodios(MapSqlParameterSource p) {
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

    private long siguiente(String tabla, String columna) {
        // Identificadores exclusivamente constantes del servicio, nunca datos de entrada.
        Long id = sql.getJdbcTemplate().queryForObject(
                "SELECT COALESCE(MAX(\"" + columna + "\"),0)+1 FROM \"" + tabla + "\"", Long.class);
        if (id == null || id <= 0) throw new IllegalStateException("Sin identificador");
        return id;
    }

    private static boolean positivo(BigDecimal n, int escala, int enteros) {
        return n != null && n.signum() > 0 && n.scale() <= escala && n.precision() - n.scale() <= enteros;
    }
    private static void validar(DatosIngreso d) {
        if (d == null) throw new IllegalArgumentException("Solicitud invalida");
        if (d.idOrdenCompra() <= 0) throw new IllegalArgumentException("Orden de compra requerida");
        if (d.idInsumo() <= 0 || !positivo(d.cantidadRecibida(),3,15) || !positivo(d.costoUnitarioCompra(),4,14)
                || d.codigoUnidadMedidaRecibida() == null || d.codigoUnidadMedidaRecibida().isBlank()
                || d.codigoUnidadMedidaRecibida().length() > 20 || d.codigoUnidadMedidaRecibida().indexOf('\0') >= 0
                || d.comprobante() == null || d.comprobante().isBlank() || d.comprobante().length() > 100
                || d.comprobante().indexOf('\0') >= 0) throw new IllegalArgumentException("Solicitud invalida");
    }
}
