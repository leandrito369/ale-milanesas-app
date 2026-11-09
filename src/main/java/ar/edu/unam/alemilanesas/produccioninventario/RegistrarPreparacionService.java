package ar.edu.unam.alemilanesas.produccioninventario;

import ar.edu.unam.alemilanesas.abastecimiento.EpisodioFaltanteService;
import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC28 / RD-I301-001: preparación propia y consumos físicos en una única transacción. */
@Service
public class RegistrarPreparacionService {
    private static final Set<String> PRODUCCION = Set.of("RESPONSABLE_PRODUCCION");
    private static final String INVALIDA = "SOLICITUD_INVALIDA";
    private static final String UNIDAD = "UNIDAD_NO_COINCIDE";
    private static final String INSUFICIENTE = "STOCK_UTILIZABLE_INSUFICIENTE";
    private static final String INSUMO = "insumo";
    private static final String FECHA = "fecha";
    private static final String MOVIMIENTO = "movimiento";
    private static final String PARTIDA = "partida";
    private static final String PEDIDO = "pedido";
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    private final EpisodioFaltanteService episodios;
    public RegistrarPreparacionService(NamedParameterJdbcTemplate sql, AutorizacionSesionInterna autorizacion,
            EpisodioFaltanteService episodios) {
        this.sql = sql; this.autorizacion = autorizacion; this.episodios = episodios;
    }
    public record PartidaAplicada(long idInsumo, long idPartidaInsumo, BigDecimal cantidadAplicada, Boolean marcarApertura) {
        public PartidaAplicada(long idInsumo, long idPartidaInsumo, BigDecimal cantidadAplicada) {
            this(idInsumo, idPartidaInsumo, cantidadAplicada, null);
        }
    }
    public record DatosPreparacion(long idPedido, long idProducto, BigDecimal cantidadPreparada, List<PartidaAplicada> partidas) {}
    public record PreparacionRegistrada(long idPreparacion, long idPedido, long idProducto, int numeroDetalle,
            BigDecimal cantidadPreparada, OffsetDateTime fechaHoraPreparacion, int cantidadInsumosConsumidos) {}
    private record Detalle(int numero, BigDecimal original) {}
    private record Version(long receta, int numero, BigDecimal rendimiento, String unidad) {}
    private record Ingrediente(long id, BigDecimal cantidad, String unidad) {}
    private record Insumo(BigDecimal stock, BigDecimal umbral, String unidad) {}
    private record Lote(long id, BigDecimal disponible) {}
    private record Consumo(long insumo, BigDecimal cantidad, BigDecimal stock, BigDecimal umbral,
            OffsetDateTime costo, List<PartidaAplicada> partidas) {}

    @Transactional
    public PreparacionRegistrada registrarPreparacionPedido(UUID sesion, DatosPreparacion datos) {
        autorizacion.exigirRoles(sesion, PRODUCCION);
        DatosPreparacion d = validar(datos);
        Detalle detalle = bloquearPedido(d);
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(808, 0)");
        autorizacion.exigirRoles(sesion, PRODUCCION);
        validarAcumulada(d, detalle);
        OffsetDateTime ahora = sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        Version version = versionAplicable(d.idProducto(), ahora);
        List<Ingrediente> ingredientes = ingredientes(version);
        validarCobertura(d.partidas(), ingredientes);
        var consumos = new ArrayList<Consumo>();
        for (var ingrediente : ingredientes) consumos.add(planificar(ingrediente, version, d, ahora));
        autorizacion.exigirRoles(sesion, PRODUCCION);
        long preparacion = siguiente("SELECT COALESCE(MAX(\"idPreparacion\"),0)+1 FROM \"PREPARACION\"");
        sql.update("""
                INSERT INTO "PREPARACION" ("idPreparacion","idPedido","numeroDetalle","idReceta","numeroVersion",
                    "fechaHoraPreparacion","cantidadPreparada") VALUES (:id,:pedido,:detalle,:receta,:version,:fecha,:cantidad)
                """, new MapSqlParameterSource("id", preparacion).addValue(PEDIDO, d.idPedido())
                .addValue("detalle", detalle.numero()).addValue("receta", version.receta()).addValue("version", version.numero())
                .addValue(FECHA, ahora).addValue("cantidad", d.cantidadPreparada()));
        Long usuario = sql.queryForObject("SELECT \"idUsuario\" FROM \"SESION_USUARIO\" WHERE \"idSesionUsuario\"=:sesion",
                Map.of("sesion", sesion), Long.class);
        for (var consumo : consumos) registrarConsumo(consumo, preparacion, usuario, ahora);
        return new PreparacionRegistrada(preparacion, d.idPedido(), d.idProducto(), detalle.numero(),
                d.cantidadPreparada(), ahora, consumos.size());
    }
    private static DatosPreparacion validar(DatosPreparacion d) {
        if (d == null || d.idPedido() <= 0 || d.idProducto() <= 0 || !cantidadValida(d.cantidadPreparada()))
            throw new IllegalArgumentException(INVALIDA);
        if (d.partidas() == null || d.partidas().isEmpty())
            return new DatosPreparacion(d.idPedido(), d.idProducto(), d.cantidadPreparada(), null);
        var ids = new HashSet<Long>();
        for (var p : d.partidas()) {
            if (p == null || p.idInsumo() <= 0 || p.idPartidaInsumo() <= 0
                    || !cantidadValida(p.cantidadAplicada()) || !ids.add(p.idPartidaInsumo()))
                throw new IllegalArgumentException(INVALIDA);
        }
        return new DatosPreparacion(d.idPedido(), d.idProducto(), d.cantidadPreparada(), List.copyOf(d.partidas()));
    }
    private static boolean cantidadValida(BigDecimal n) {
        return n != null && n.signum() > 0 && n.scale() <= 3 && n.precision() - n.scale() <= 15;
    }
    private Detalle bloquearPedido(DatosPreparacion d) {
        var p = new MapSqlParameterSource(PEDIDO, d.idPedido()).addValue("producto", d.idProducto());
        var estados = sql.queryForList("SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=:pedido FOR UPDATE", p, String.class);
        if (estados.isEmpty()) throw new IllegalArgumentException("PEDIDO_NO_ENCONTRADO");
        if (!"PENDIENTE".equals(estados.getFirst())) throw new IllegalArgumentException("PEDIDO_NO_PENDIENTE");
        var detalles = sql.query("""
                SELECT "numeroDetalle","cantidadOriginal" FROM "DETALLE_PEDIDO"
                WHERE "idPedido"=:pedido AND "idProducto"=:producto FOR UPDATE
                """, p, (rs, n) -> new Detalle(rs.getInt("numeroDetalle"), rs.getBigDecimal("cantidadOriginal")));
        if (detalles.isEmpty()) throw new IllegalArgumentException("PRODUCTO_NO_EN_PEDIDO");
        return detalles.getFirst();
    }
    private void validarAcumulada(DatosPreparacion d, Detalle detalle) {
        BigDecimal acumulada = sql.queryForObject("""
                SELECT COALESCE(SUM("cantidadPreparada"),0) FROM "PREPARACION"
                WHERE "idPedido"=:pedido AND "numeroDetalle"=:detalle
                """, Map.of(PEDIDO, d.idPedido(), "detalle", detalle.numero()), BigDecimal.class);
        if (acumulada.add(d.cantidadPreparada()).compareTo(detalle.original()) > 0)
            throw new IllegalArgumentException("CANTIDAD_EXCEDE_ORIGINAL");
    }
    private Version versionAplicable(long producto, OffsetDateTime ahora) {
        var p = new MapSqlParameterSource("producto", producto).addValue(FECHA, ahora);
        // Después del lock (808,0), igual que UC10: estabilizar tipo y unidad del Producto.
        var productos = sql.queryForList("""
                SELECT "tipoProducto","codigoUnidadVenta" FROM "PRODUCTO" WHERE "idProducto"=:producto FOR SHARE
                """, p);
        if (productos.isEmpty() || !"ELABORADO".equals(productos.getFirst().get("tipoProducto")))
            throw new IllegalArgumentException("SIN_RECETA_VIGENTE");
        var versiones = sql.query("""
                SELECT v."idReceta",v."numeroVersion",v."cantidadRendimientoBase",v."codigoUnidadRendimiento"
                FROM "VERSION_RECETA" v JOIN "RECETA" r ON r."idReceta"=v."idReceta"
                WHERE r."idProducto"=:producto AND v."vigenteDesde"<=:fecha
                    AND (v."vigenteHasta" IS NULL OR v."vigenteHasta">:fecha)
                ORDER BY v."vigenteDesde" DESC LIMIT 1 FOR SHARE OF v,r
                """, p, (rs, n) -> new Version(rs.getLong("idReceta"), rs.getInt("numeroVersion"),
                rs.getBigDecimal("cantidadRendimientoBase"), rs.getString("codigoUnidadRendimiento")));
        if (versiones.isEmpty()) throw new IllegalArgumentException("SIN_RECETA_VIGENTE");
        Version v = versiones.getFirst();
        if (!v.unidad().equals(productos.getFirst().get("codigoUnidadVenta"))) throw new IllegalArgumentException(UNIDAD);
        return v;
    }
    private List<Ingrediente> ingredientes(Version v) {
        var filas = sql.query("""
                SELECT "idInsumo","cantidadNecesaria","codigoUnidadCantidad" FROM "DETALLE_VERSION_RECETA"
                WHERE "idReceta"=:receta AND "numeroVersion"=:version ORDER BY "idInsumo" ASC FOR SHARE
                """, Map.of("receta", v.receta(), "version", v.numero()),
                (rs, n) -> new Ingrediente(rs.getLong("idInsumo"), rs.getBigDecimal("cantidadNecesaria"), rs.getString("codigoUnidadCantidad")));
        if (filas.isEmpty()) throw new IllegalArgumentException("RECETA_SIN_INSUMOS");
        return filas;
    }
    private static void validarCobertura(List<PartidaAplicada> partidas, List<Ingrediente> ingredientes) {
        if (partidas == null) return;
        var requeridos = new HashSet<Long>();
        for (var ingrediente : ingredientes) requeridos.add(ingrediente.id());
        var indicados = new HashSet<Long>();
        for (var p : partidas) indicados.add(p.idInsumo());
        if (!requeridos.equals(indicados)) throw new IllegalArgumentException(INVALIDA);
    }
    private Consumo planificar(Ingrediente ingrediente, Version v, DatosPreparacion d, OffsetDateTime ahora) {
        long id = ingrediente.id();
        sql.getJdbcTemplate().queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, id);
        var p = new MapSqlParameterSource(INSUMO, id).addValue(FECHA, ahora);
        var insumos = sql.query("""
                SELECT "stockActual","umbralMinimo","codigoUnidadMedida","activo" FROM "INSUMO"
                WHERE "idInsumo"=:insumo FOR UPDATE
                """, p, (rs, n) -> rs.getBoolean("activo") ? new Insumo(rs.getBigDecimal("stockActual"),
                        rs.getBigDecimal("umbralMinimo"), rs.getString("codigoUnidadMedida")) : null);
        if (insumos.isEmpty() || insumos.getFirst() == null) throw new IllegalArgumentException("INSUMO_INACTIVO");
        Insumo insumo = insumos.getFirst();
        if (!ingrediente.unidad().equals(insumo.unidad())) throw new IllegalArgumentException(UNIDAD);
        BigDecimal cantidad = ingrediente.cantidad().multiply(d.cantidadPreparada()).divide(v.rendimiento(), 3, RoundingMode.HALF_UP);
        if (cantidad.signum() <= 0) throw new IllegalArgumentException("CANTIDAD_CONSUMIDA_INSUFICIENTE");
        if (!cantidadValida(cantidad)) throw new IllegalArgumentException(INVALIDA);
        // SUM y selección usan las mismas filas ya bloqueadas: no hay ventana entre validar e imputar.
        List<Lote> lotes = sql.query("""
                SELECT "idPartidaInsumo","cantidadDisponible" FROM "PARTIDA_INSUMO"
                WHERE "idInsumo"=:insumo AND "cantidadDisponible">0
                    AND ("fechaVencimiento" IS NULL OR "fechaVencimiento">=CURRENT_DATE)
                ORDER BY "fechaHoraApertura" ASC NULLS LAST,"fechaVencimiento" ASC NULLS LAST,
                    "fechaHoraRegistro" ASC,"idPartidaInsumo" ASC FOR UPDATE
                """, p, (rs, n) -> new Lote(rs.getLong("idPartidaInsumo"), rs.getBigDecimal("cantidadDisponible")));
        BigDecimal utilizable = lotes.stream().map(Lote::disponible).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (utilizable.compareTo(cantidad) < 0) throw new IllegalArgumentException(INSUFICIENTE);
        List<PartidaAplicada> aplicadas = d.partidas() == null ? seleccionar(id, lotes, cantidad)
                : seleccionarManual(id, lotes, cantidad, d.partidas());
        var costos = sql.queryForList("""
                SELECT "vigenteDesde" FROM "COSTO_INSUMO_HISTORICO" WHERE "idInsumo"=:insumo
                    AND "vigenteDesde"<=:fecha AND ("vigenteHasta" IS NULL OR "vigenteHasta">:fecha)
                ORDER BY "vigenteDesde" DESC LIMIT 1
                """, p, OffsetDateTime.class);
        if (costos.isEmpty()) throw new IllegalArgumentException("COSTO_HISTORICO_REQUERIDO");
        return new Consumo(id, cantidad, insumo.stock().subtract(cantidad), insumo.umbral(), costos.getFirst(), aplicadas);
    }
    private static List<PartidaAplicada> seleccionar(long insumo, List<Lote> lotes, BigDecimal cantidad) {
        var resultado = new ArrayList<PartidaAplicada>();
        BigDecimal pendiente = cantidad;
        for (var lote : lotes) {
            if (pendiente.signum() == 0) break;
            BigDecimal aplicada = lote.disponible().min(pendiente);
            resultado.add(new PartidaAplicada(insumo, lote.id(), aplicada));
            pendiente = pendiente.subtract(aplicada);
        }
        return List.copyOf(resultado);
    }
    private static List<PartidaAplicada> seleccionarManual(long insumo, List<Lote> lotes, BigDecimal cantidad, List<PartidaAplicada> manual) {
        var disponibles = new HashMap<Long, BigDecimal>();
        for (var lote : lotes) disponibles.put(lote.id(), lote.disponible());
        var seleccion = manual.stream().filter(p -> p.idInsumo() == insumo).toList();
        BigDecimal suma = BigDecimal.ZERO;
        for (var p : seleccion) {
            BigDecimal saldo = disponibles.get(p.idPartidaInsumo());
            if (saldo == null || saldo.compareTo(p.cantidadAplicada()) < 0) throw new IllegalArgumentException(INVALIDA);
            suma = suma.add(p.cantidadAplicada());
        }
        if (suma.compareTo(cantidad) != 0) throw new IllegalArgumentException(INVALIDA);
        return seleccion;
    }
    private void registrarConsumo(Consumo c, long preparacion, Long usuario, OffsetDateTime ahora) {
        long movimiento = siguiente("SELECT COALESCE(MAX(\"idMovimientoInsumo\"),0)+1 FROM \"MOVIMIENTO_INSUMO\"");
        Long secuencia = sql.queryForObject("""
                SELECT COALESCE(MAX("numeroSecuenciaInsumo"),0)+1 FROM "MOVIMIENTO_INSUMO" WHERE "idInsumo"=:insumo
                """, Map.of(INSUMO, c.insumo()), Long.class);
        var p = new MapSqlParameterSource(INSUMO, c.insumo()).addValue("stock", c.stock()).addValue(MOVIMIENTO, movimiento)
                .addValue("secuencia", secuencia).addValue("preparacion", preparacion).addValue("costo", c.costo())
                .addValue("usuario", usuario).addValue("cantidad", c.cantidad()).addValue(FECHA, ahora);
        // Igual que UC42: ck_insumo_0 falla antes de insertar el movimiento y revierte toda la preparación.
        sql.update("UPDATE \"INSUMO\" SET \"stockActual\"=:stock WHERE \"idInsumo\"=:insumo", p);
        sql.update("""
                INSERT INTO "MOVIMIENTO_INSUMO" ("idMovimientoInsumo","idInsumo","numeroSecuenciaInsumo","idPreparacion",
                    "idRecepcionCompra","vigenteDesdeCosto","idUsuarioResponsable","tipoMovimiento","cantidad","fechaHora",
                    "stockResultante","origen","referenciaOrigen","motivo","observacion")
                VALUES (:movimiento,:insumo,:secuencia,:preparacion,NULL,:costo,:usuario,'CONSUMO_PRODUCCION',
                    :cantidad,:fecha,:stock,'PRODUCCION',NULL,NULL,NULL)
                """, p);
        for (var partida : c.partidas()) {
            p.addValue(PARTIDA, partida.idPartidaInsumo()).addValue("aplicada", partida.cantidadAplicada())
                    .addValue("abrir", Boolean.TRUE.equals(partida.marcarApertura()));
            // Solo el trigger V3 modifica cantidadDisponible; este UPDATE abre el envase, sin otro descuento.
            sql.update("""
                    INSERT INTO "DETALLE_MOVIMIENTO_PARTIDA" ("idMovimientoInsumo","idPartidaInsumo","idInsumo","cantidadAplicada")
                    VALUES (:movimiento,:partida,:insumo,:aplicada)
                    """, p);
            sql.update("""
                    UPDATE "PARTIDA_INSUMO" SET "fechaHoraApertura"=GREATEST(:fecha,"fechaHoraRegistro")
                    WHERE "idPartidaInsumo"=:partida AND "idInsumo"=:insumo AND (:abrir OR "fechaHoraApertura" IS NULL)
                    """, p);
        }
        episodios.evaluarApertura(c.insumo(), c.stock(), c.umbral(), movimiento);
    }
    private long siguiente(String consulta) {
        Long id = sql.getJdbcTemplate().queryForObject(consulta, Long.class);
        if (id == null || id <= 0) throw new IllegalStateException("Sin identificador");
        return id;
    }
}
