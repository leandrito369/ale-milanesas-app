package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import ar.edu.unam.alemilanesas.finanzas.EvaluadorCategoriaComercialInterno;
import ar.edu.unam.alemilanesas.finanzas.ImputacionFinancieraService;
import ar.edu.unam.alemilanesas.finanzas.ImputacionFinancieraService.CoberturaEntrega;
import ar.edu.unam.alemilanesas.finanzas.ProtocoloIdentificadoresFinancieros;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-05 registrarEntrega (primer vertical online, exclusivamente cantidades originales):
 * - C1-EXPLICITO (Recorrido explicito via RECORRIDO_ASIGNACION)
 * - PE-1 + IMP-B/IMP-C (sin Cobro/PAGO/Caja; declaracion D_e != Cobro)
 * - Contencion UC-07 fail-closed (SOLO_ORIGINALES)
 * - Costos historicos ELABORADO/REVENTA y Excedentes trazables
 * - Idempotencia verificada (replay autorizado / conflicto sin escrituras)
 */
@Service
public class RegistrarEntregaService {
    private static final String REPARTIDOR = "REPARTIDOR";
    private static final String INVALIDA = "SOLICITUD_INVALIDA";
    private static final String PROHIBIDA = "OPERACION_PROHIBIDA";
    private static final BigDecimal CIEN = new BigDecimal("100");

    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    private final ProtocoloIdentificadoresFinancieros identificadores;
    private final ImputacionFinancieraService imputacion;
    private final EvaluadorCategoriaComercialInterno evaluadorCategoria;

    public RegistrarEntregaService(
            NamedParameterJdbcTemplate sql,
            AutorizacionSesionInterna autorizacion,
            ProtocoloIdentificadoresFinancieros identificadores,
            ImputacionFinancieraService imputacion,
            EvaluadorCategoriaComercialInterno evaluadorCategoria) {
        this.sql = sql;
        this.autorizacion = autorizacion;
        this.identificadores = identificadores;
        this.imputacion = imputacion;
        this.evaluadorCategoria = evaluadorCategoria;
    }

    public record ItemEntrega(long idProducto, BigDecimal cantidadEntregada) {}

    public record DatosEntrega(
            long idPedido,
            List<ItemEntrega> detallesEntrega,
            BigDecimal efectivoDeclaradoRecibido,
            UUID claveIdempotencia) {}

    public record ExcedenteGenerado(
            long idExcedente,
            long idProducto,
            int numeroDetalle,
            BigDecimal cantidadInicial) {}

    public record EntregaRegistrada(
            long idPedido,
            long idRecorrido,
            OffsetDateTime fechaHoraEntrega,
            String estadoPedido,
            BigDecimal cargoRealCe,
            BigDecimal anticipoExplicitoPe,
            BigDecimal fifoVinculanteFe,
            BigDecimal coberturaTotalKe,
            BigDecimal exigibleEe,
            BigDecimal declaradoRepartidorDe,
            BigDecimal reconocimientoInicialV0,
            String modoEvidencia,
            List<ExcedenteGenerado> excedentesGenerados,
            boolean creado) {}

    @Transactional
    public EntregaRegistrada registrarEntrega(UUID sesion, DatosEntrega datos) {
        autorizacion.exigirRoles(sesion, Set.of(REPARTIDOR));
        validarDatos(datos);
        long idRepartidor = Objects.requireNonNull(sql.queryForObject(
                "SELECT \"idUsuario\" FROM \"SESION_USUARIO\" WHERE \"idSesionUsuario\" = :s",
                Map.of("s", sesion), Long.class));

        // Paso 0: lectura sin lock exclusivo para alinear el grafo de locks
        // A2(RECORRIDO) -> A1(2929001) -> A2(CLIENTE) -> CLIENTE U -> PEDIDO U -> ASIGNACION U -> A2(808,0) -> PRODUCTO U
        var pedPrevia = sql.queryForList("""
                SELECT "idCliente" FROM "PEDIDO" WHERE "idPedido" = :p
                """, Map.of("p", datos.idPedido()));
        if (pedPrevia.isEmpty()) {
            throw new IllegalArgumentException("PEDIDO_NO_ENCONTRADO");
        }
        long idCliente = ((Number) pedPrevia.getFirst().get("idCliente")).longValue();

        var recPrevio = sql.queryForList("""
                SELECT "idRecorrido"
                  FROM "RECORRIDO_ASIGNACION"
                 WHERE "idPedido" = :p AND "estadoPertenencia" = 'ACTIVA'
                """, Map.of("p", datos.idPedido()));
        Long idRecorridoPre = recPrevio.isEmpty() ? null : ((Number) recPrevio.getFirst().get("idRecorrido")).longValue();

        if (idRecorridoPre != null) {
            identificadores.bloquearRecorridoA2(idRecorridoPre);
        }
        identificadores.bloquear();
        identificadores.bloquearClienteA2(idCliente);
        autorizacion.exigirRoles(sesion, Set.of(REPARTIDOR));

        // Paso 1: revalidar idempotencia bajo locks (maneja retries concurrentes sin 23505)
        EntregaRegistrada replay = verificarIdempotenciaExistente(datos, idRepartidor);
        if (replay != null) {
            return replay;
        }

        // Paso 2: bloquear CLIENTE U -> PEDIDO U -> ASIGNACION U
        sql.queryForObject("SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\" = :c FOR UPDATE",
                Map.of("c", idCliente), Long.class);

        var pedidoRow = sql.queryForMap("""
                SELECT "estado","tipoAjuste","porcentajeAjuste"
                  FROM "PEDIDO"
                 WHERE "idPedido" = :p
                 FOR UPDATE
                """, Map.of("p", datos.idPedido()));

        var asigRows = sql.queryForList("""
                SELECT "numeroAsignacion","idUsuarioRepartidor"
                  FROM "ASIGNACION_REPARTO"
                 WHERE "idPedido" = :p AND "fechaHoraFin" IS NULL
                 FOR UPDATE
                """, Map.of("p", datos.idPedido()));
        if (asigRows.isEmpty() || ((Number) asigRows.getFirst().get("idUsuarioRepartidor")).longValue() != idRepartidor) {
            throw new SecurityException(PROHIBIDA);
        }
        int numeroAsignacion = ((Number) asigRows.getFirst().get("numeroAsignacion")).intValue();

        if (!"PENDIENTE".equals(pedidoRow.get("estado"))) {
            throw new IllegalStateException("PEDIDO_NO_PENDIENTE");
        }

        // Paso 3: validar Recorrido explicito y pertenencia ACTIVA en RECORRIDO_ASIGNACION
        var raRows = sql.queryForList("""
                SELECT "idRecorridoAsignacion","idRecorrido"
                  FROM "RECORRIDO_ASIGNACION"
                 WHERE "idPedido" = :p
                   AND "numeroAsignacion" = :num
                   AND "estadoPertenencia" = 'ACTIVA'
                 FOR UPDATE
                """, Map.of("p", datos.idPedido(), "num", numeroAsignacion));
        if (raRows.isEmpty()) {
            throw new IllegalStateException("RECORRIDO_ACTIVO_REQUERIDO");
        }
        long idRecorridoAsignacion = ((Number) raRows.getFirst().get("idRecorridoAsignacion")).longValue();
        long idRecorrido = ((Number) raRows.getFirst().get("idRecorrido")).longValue();
        if (idRecorridoPre == null || idRecorridoPre.longValue() != idRecorrido) {
            throw new IllegalStateException("ASIGNACION_RECORRIDO_CONFLICTO");
        }

        var recRows = sql.queryForList("""
                SELECT "idUsuarioRepartidor","estadoOperativo"
                  FROM "RECORRIDO_REPARTO"
                 WHERE "idRecorrido" = :r
                 FOR SHARE
                """, Map.of("r", idRecorrido));
        if (recRows.isEmpty()
                || !"ABIERTO".equals(recRows.getFirst().get("estadoOperativo"))
                || ((Number) recRows.getFirst().get("idUsuarioRepartidor")).longValue() != idRepartidor) {
            throw new IllegalStateException("RECORRIDO_NO_ABIERTO");
        }

        // Paso 4: guardia fail-closed UC-07 (cualquier adicional aceptado o linea tecnica bloquea este corte)
        verificarAusenciaAdicionalesUc07(datos.idPedido());

        // Paso 5: bloquear DETALLE_PEDIDO originales
        var detalles = sql.query("""
                SELECT "numeroDetalle","idProducto","cantidadOriginal",
                       "precioUnitarioOriginal","porcentajePromocionOriginal"
                  FROM "DETALLE_PEDIDO"
                 WHERE "idPedido" = :p AND "cantidadOriginal" > 0
                 ORDER BY "numeroDetalle" ASC
                 FOR UPDATE
                """, Map.of("p", datos.idPedido()),
                (rs, n) -> new DetalleOriginal(
                        rs.getInt(1),
                        rs.getLong(2),
                        rs.getBigDecimal(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5)));
        if (detalles.isEmpty()) {
            throw new IllegalStateException("PEDIDO_SIN_DETALLES_ORIGINALES");
        }

        Map<Long, DetalleOriginal> porProducto = new LinkedHashMap<>();
        for (DetalleOriginal d : detalles) {
            porProducto.put(d.idProducto(), d);
        }

        Map<Long, BigDecimal> entregadas = new HashMap<>();
        BigDecimal sumaEntregada = BigDecimal.ZERO;
        for (ItemEntrega item : datos.detallesEntrega()) {
            DetalleOriginal orig = porProducto.get(item.idProducto());
            if (orig == null) {
                throw new IllegalArgumentException("PRODUCTO_NO_EN_PEDIDO");
            }
            BigDecimal cant = item.cantidadEntregada().setScale(3, RoundingMode.UNNECESSARY);
            if (cant.compareTo(orig.cantidadOriginal()) > 0) {
                throw new IllegalArgumentException("CANTIDAD_EXCEDE_ORIGINAL");
            }
            entregadas.put(item.idProducto(), cant);
            sumaEntregada = sumaEntregada.add(cant);
        }
        if (sumaEntregada.signum() <= 0) {
            throw new IllegalArgumentException("CANTIDADES_EN_CERO");
        }
        for (DetalleOriginal orig : detalles) {
            entregadas.putIfAbsent(orig.idProducto(), BigDecimal.ZERO.setScale(3, RoundingMode.UNNECESSARY));
        }

        // Paso 6: bloquear A2(808, 0) y PRODUCTO FOR UPDATE ordenados por idProducto
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(808, 0)");
        List<Long> idsOrdenados = detalles.stream().map(DetalleOriginal::idProducto).distinct().sorted().toList();
        var prodList = sql.query("""
                SELECT "idProducto","tipoProducto","stockActualReventa","costoPromedioActualReventa"
                  FROM "PRODUCTO"
                 WHERE "idProducto" IN (:ids)
                 ORDER BY "idProducto" ASC
                 FOR UPDATE
                """, Map.of("ids", idsOrdenados),
                (rs, n) -> new ProductoEstado(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getBigDecimal(3),
                        rs.getBigDecimal(4)));
        Map<Long, ProductoEstado> productos = new HashMap<>();
        for (ProductoEstado p : prodList) {
            productos.put(p.idProducto(), p);
        }

        // Paso 7: validar disponibilidad fisica y costos historicos completos antes de escribir
        List<LineaPreparadaParaEscribir> planLineas = new ArrayList<>();
        BigDecimal baseMonetariaSinRedondeo = BigDecimal.ZERO;

        for (DetalleOriginal orig : detalles) {
            ProductoEstado prod = Objects.requireNonNull(productos.get(orig.idProducto()));
            BigDecimal cantEntregada = entregadas.get(orig.idProducto());

            if (cantEntregada.signum() > 0) {
                BigDecimal netoFactor = CIEN.subtract(orig.porcentajePromocionOriginal()).movePointLeft(2);
                BigDecimal subtotalLinea = orig.precioUnitarioOriginal().multiply(cantEntregada).multiply(netoFactor);
                baseMonetariaSinRedondeo = baseMonetariaSinRedondeo.add(subtotalLinea);
            }

            if ("ELABORADO".equals(prod.tipoProducto())) {
                BigDecimal totalPrep = Objects.requireNonNull(sql.queryForObject("""
                        SELECT COALESCE(SUM("cantidadPreparada"), 0)::NUMERIC(18,3)
                          FROM "PREPARACION"
                         WHERE "idPedido" = :p AND "numeroDetalle" = :det
                        """, Map.of("p", datos.idPedido(), "det", orig.numeroDetalle()), BigDecimal.class));
                if (cantEntregada.compareTo(totalPrep) > 0) {
                    throw new IllegalStateException("DISPONIBILIDAD_ELABORADO_INSUFICIENTE");
                }
                BigDecimal costoElaboradoVendido = null;
                BigDecimal excedenteCant = totalPrep.subtract(cantEntregada);
                BigDecimal excedenteCosto = null;

                if (totalPrep.signum() > 0) {
                    Integer prepsSinMovimiento = sql.queryForObject("""
                            SELECT count(*)
                              FROM "PREPARACION" p
                             WHERE p."idPedido" = :p AND p."numeroDetalle" = :det
                               AND NOT EXISTS (
                                   SELECT 1 FROM "MOVIMIENTO_INSUMO" m
                                   JOIN "COSTO_INSUMO_HISTORICO" c
                                     ON c."idInsumo" = m."idInsumo" AND c."vigenteDesde" = m."vigenteDesdeCosto"
                                   WHERE m."idPreparacion" = p."idPreparacion"
                                     AND m."tipoMovimiento" = 'CONSUMO_PRODUCCION'
                                     AND c."costoUnitario" > 0
                               )
                            """, Map.of("p", datos.idPedido(), "det", orig.numeroDetalle()), Integer.class);
                    if (prepsSinMovimiento != null && prepsSinMovimiento > 0) {
                        throw new IllegalStateException("COSTO_HISTORICO_REQUERIDO");
                    }

                    BigDecimal costoPrepTotal = sql.queryForObject("""
                            SELECT COALESCE(SUM(m."cantidad" * c."costoUnitario"), 0)
                              FROM "PREPARACION" p
                              JOIN "MOVIMIENTO_INSUMO" m ON m."idPreparacion" = p."idPreparacion"
                              JOIN "COSTO_INSUMO_HISTORICO" c
                                ON c."idInsumo" = m."idInsumo" AND c."vigenteDesde" = m."vigenteDesdeCosto"
                             WHERE p."idPedido" = :p
                               AND p."numeroDetalle" = :det
                               AND m."tipoMovimiento" = 'CONSUMO_PRODUCCION'
                            """, Map.of("p", datos.idPedido(), "det", orig.numeroDetalle()), BigDecimal.class);
                    if (costoPrepTotal == null || costoPrepTotal.signum() <= 0) {
                        throw new IllegalStateException("COSTO_HISTORICO_REQUERIDO");
                    }
                    BigDecimal costoTotalRedondeado = costoPrepTotal.setScale(4, RoundingMode.HALF_UP);
                    if (costoTotalRedondeado.signum() <= 0) {
                        throw new IllegalStateException("COSTO_HISTORICO_REQUERIDO");
                    }

                    if (excedenteCant.signum() == 0) {
                        costoElaboradoVendido = costoTotalRedondeado;
                    } else if (cantEntregada.signum() == 0) {
                        costoElaboradoVendido = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
                        excedenteCosto = costoTotalRedondeado;
                    } else {
                        BigDecimal vendidoProp = costoPrepTotal.multiply(cantEntregada)
                                .divide(totalPrep, 4, RoundingMode.HALF_UP);
                        BigDecimal remanenteCosto = costoTotalRedondeado.subtract(vendidoProp);
                        if (remanenteCosto.signum() <= 0) {
                            throw new IllegalStateException("COSTO_HISTORICO_REQUERIDO");
                        }
                        costoElaboradoVendido = vendidoProp;
                        excedenteCosto = remanenteCosto;
                    }
                }
                planLineas.add(new LineaPreparadaParaEscribir(
                        orig.numeroDetalle(), orig.idProducto(), "ELABORADO", cantEntregada,
                        costoElaboradoVendido, excedenteCant, excedenteCosto, null, null));
            } else {
                // REVENTA
                BigDecimal stockActual = prod.stockActualReventa();
                BigDecimal costoProm = prod.costoPromedioActualReventa();
                if (cantEntregada.signum() > 0) {
                    if (stockActual == null || stockActual.compareTo(cantEntregada) < 0) {
                        throw new IllegalStateException("STOCK_REVENTA_INSUFICIENTE");
                    }
                    if (costoProm == null || costoProm.signum() <= 0) {
                        throw new IllegalStateException("COSTO_HISTORICO_REQUERIDO");
                    }
                    BigDecimal nuevoStock = stockActual.subtract(cantEntregada).setScale(3, RoundingMode.UNNECESSARY);
                    BigDecimal costoTotalVenta = cantEntregada.multiply(costoProm).setScale(4, RoundingMode.HALF_UP);
                    planLineas.add(new LineaPreparadaParaEscribir(
                            orig.numeroDetalle(), orig.idProducto(), "REVENTA", cantEntregada,
                            null, BigDecimal.ZERO, null, nuevoStock,
                            new ReventaEgreso(costoProm.setScale(4, RoundingMode.HALF_UP), costoTotalVenta)));
                } else {
                    planLineas.add(new LineaPreparadaParaEscribir(
                            orig.numeroDetalle(), orig.idProducto(), "REVENTA", cantEntregada,
                            null, BigDecimal.ZERO, null, null, null));
                }
            }
        }

        // Paso 8: calcular C_e desde snapshots congelados del Pedido
        BigDecimal cargoRealCe = calcularCeDesdeSnapshot(
                baseMonetariaSinRedondeo,
                (String) pedidoRow.get("tipoAjuste"),
                (BigDecimal) pedidoRow.get("porcentajeAjuste"));

        BigDecimal declaradoDe = datos.efectivoDeclaradoRecibido() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : datos.efectivoDeclaradoRecibido().setScale(2, RoundingMode.HALF_UP);

        OffsetDateTime ahora = Objects.requireNonNull(
                sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", OffsetDateTime.class));

        // Paso 9: liquidar PE-1 + IMP-B/IMP-C (cero Cobro, cero PAGO, cero Caja)
        CoberturaEntrega cobertura = imputacion.liquidarCoberturaEntrega(
                idCliente, datos.idPedido(), idRepartidor, cargoRealCe, declaradoDe, ahora);

        // Paso 10: escrituras fisicas atomicas en orden compatible con triggers V1/V4
        sql.update("""
                INSERT INTO "ENTREGA" ("idPedido","fechaHoraEntrega","idUsuarioRepartidor")
                VALUES (:p,:fecha,:rep)
                """, Map.of("p", datos.idPedido(), "fecha", ahora, "rep", idRepartidor));

        List<ExcedenteGenerado> excedentesGenerados = new ArrayList<>();
        for (LineaPreparadaParaEscribir linea : planLineas) {
            sql.update("""
                    INSERT INTO "DETALLE_ENTREGA" (
                        "idPedido","numeroDetalle","cantidadEntregada","costoHistoricoElaboradoVendido")
                    VALUES (:p,:det,:cant,:costo)
                    """, new MapSqlParameterSource()
                    .addValue("p", datos.idPedido())
                    .addValue("det", linea.numeroDetalle())
                    .addValue("cant", linea.cantidadEntregada())
                    .addValue("costo", linea.costoHistoricoElaboradoVendido()));

            if (linea.excedenteCantidad().signum() > 0) {
                long idExc = siguiente("SELECT COALESCE(MAX(\"idExcedente\"),0)+1 FROM \"EXCEDENTE\"");
                sql.update("""
                        INSERT INTO "EXCEDENTE" (
                            "idExcedente","idPedidoOrigen","numeroDetalleOrigen","origenGeneracion",
                            "fechaHoraGeneracion","cantidadInicial","cantidadDisponible",
                            "costoHistoricoInicial","costoHistoricoDisponible","idMovimientoExcedenteOrigen")
                        VALUES (:id,:p,:det,'ENTREGA',:fecha,:cant,:cant,:costo,:costo,NULL)
                        """, new MapSqlParameterSource()
                        .addValue("id", idExc)
                        .addValue("p", datos.idPedido())
                        .addValue("det", linea.numeroDetalle())
                        .addValue("fecha", ahora)
                        .addValue("cant", linea.excedenteCantidad())
                        .addValue("costo", linea.excedenteCosto()));
                excedentesGenerados.add(new ExcedenteGenerado(
                        idExc, linea.idProducto(), linea.numeroDetalle(), linea.excedenteCantidad()));
            }

            if (linea.reventaEgreso() != null) {
                sql.update("""
                        UPDATE "PRODUCTO"
                           SET "stockActualReventa" = :stock
                         WHERE "idProducto" = :prod
                        """, Map.of("stock", linea.nuevoStockReventa(), "prod", linea.idProducto()));

                long idMovRev = siguiente("SELECT COALESCE(MAX(\"idMovimientoStockReventa\"),0)+1 FROM \"MOVIMIENTO_STOCK_REVENTA\"");
                long seqProd = Objects.requireNonNull(sql.queryForObject("""
                        SELECT COALESCE(MAX("numeroSecuenciaProducto"),0)+1
                          FROM "MOVIMIENTO_STOCK_REVENTA"
                         WHERE "idProducto" = :prod
                        """, Map.of("prod", linea.idProducto()), Long.class));

                sql.update("""
                        INSERT INTO "MOVIMIENTO_STOCK_REVENTA" (
                            "idMovimientoStockReventa","idProducto","numeroSecuenciaProducto",
                            "idRecepcionCompra","idPedidoEntrega","numeroDetalleEntrega",
                            "idValoracionReventa","idUsuarioResponsable","tipoMovimiento",
                            "cantidad","fechaHoraMovimiento","stockResultante",
                            "costoPromedioResultante","costoTotalHistoricoVenta","motivo","observacion")
                        VALUES (:id,:prod,:seq,NULL,:p,:det,NULL,:rep,'EGRESO_VENTA',:cant,:fecha,:stock,:prom,:total,NULL,NULL)
                        """, new MapSqlParameterSource()
                        .addValue("id", idMovRev)
                        .addValue("prod", linea.idProducto())
                        .addValue("seq", seqProd)
                        .addValue("p", datos.idPedido())
                        .addValue("det", linea.numeroDetalle())
                        .addValue("rep", idRepartidor)
                        .addValue("cant", linea.cantidadEntregada())
                        .addValue("fecha", ahora)
                        .addValue("stock", linea.nuevoStockReventa())
                        .addValue("prom", linea.reventaEgreso().costoPromedio())
                        .addValue("total", linea.reventaEgreso().costoTotalVenta()));
            }
        }

        // CARGO_POR_VENTA en MOVIMIENTO_CUENTA + ORIGEN_MOVIMIENTO_CUENTA
        long idMovCuenta = identificadores.siguienteMovimientoCuenta();
        long numSeqCliente = Objects.requireNonNull(sql.queryForObject("""
                SELECT COALESCE(MAX("numeroSecuenciaCliente"),0)+1
                  FROM "MOVIMIENTO_CUENTA"
                 WHERE "idCliente" = :c
                """, Map.of("c", idCliente), Long.class));
        var ultimosSaldos = sql.queryForList("""
                SELECT "saldoResultante"
                  FROM "MOVIMIENTO_CUENTA"
                 WHERE "idCliente" = :c
                 ORDER BY "numeroSecuenciaCliente" DESC
                 LIMIT 1
                """, Map.of("c", idCliente));
        BigDecimal saldoPrevio = ultimosSaldos.isEmpty()
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : ((BigDecimal) ultimosSaldos.getFirst().get("saldoResultante")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal nuevoSaldo = saldoPrevio.add(cargoRealCe).setScale(2, RoundingMode.HALF_UP);

        sql.update("""
                INSERT INTO "MOVIMIENTO_CUENTA" (
                    "idMovimientoCuenta","idCliente","numeroSecuenciaCliente","tipoMovimiento",
                    "importe","fechaHoraMovimiento","saldoResultante","idUsuarioAjuste","observacion")
                VALUES (:id,:c,:seq,'CARGO_POR_VENTA',:imp,:fecha,:saldo,NULL,NULL)
                """, new MapSqlParameterSource()
                .addValue("id", idMovCuenta)
                .addValue("c", idCliente)
                .addValue("seq", numSeqCliente)
                .addValue("imp", cargoRealCe)
                .addValue("fecha", ahora)
                .addValue("saldo", nuevoSaldo));
        sql.update("""
                INSERT INTO "ORIGEN_MOVIMIENTO_CUENTA" (
                    "idMovimientoCuenta","idPedidoEntrega","idCobro","idReembolso","idCobroAnulado")
                VALUES (:mov,:p,NULL,NULL,NULL)
                """, Map.of("mov", idMovCuenta, "p", datos.idPedido()));

        // PE-1
        sql.update("""
                INSERT INTO "RECONOCIMIENTO_ENTREGA_PE1" (
                    "idPedido","idCliente","claveIdempotenciaEntrega",
                    "cargoRealCe","anticipoExplicitoPe","fifoVinculanteFe",
                    "coberturaTotalKe","exigibleEe","declaradoRepartidorDe",
                    "reconocimientoInicialV0","reconocimientoVigenteVvig",
                    "modoEvidencia","estadoContencionUc07","fechaHoraRegistro")
                VALUES (:p,:c,:clave,:ce,:pe,:fe,:ke,:ee,:de,:v0,:v0,:modo,'SOLO_ORIGINALES',:fecha)
                """, new MapSqlParameterSource()
                .addValue("p", datos.idPedido())
                .addValue("c", idCliente)
                .addValue("clave", datos.claveIdempotencia())
                .addValue("ce", cargoRealCe)
                .addValue("pe", cobertura.anticipoExplicitoPe())
                .addValue("fe", cobertura.fifoVinculanteFe())
                .addValue("ke", cobertura.coberturaTotalKe())
                .addValue("ee", cobertura.exigibleEe())
                .addValue("de", declaradoDe)
                .addValue("v0", cobertura.reconocimientoInicialV0())
                .addValue("modo", cobertura.modoEvidencia())
                .addValue("fecha", ahora));

        // Vincular Entrega a Recorrido ANTES de pasar RECORRIDO_ASIGNACION a ENTREGADA
        sql.update("""
                INSERT INTO "RECORRIDO_ENTREGA" ("idRecorrido","idPedido","fechaHoraVinculacion")
                VALUES (:rec,:p,:fecha)
                """, Map.of("rec", idRecorrido, "p", datos.idPedido(), "fecha", ahora));

        sql.update("""
                UPDATE "RECORRIDO_ASIGNACION"
                   SET "estadoPertenencia" = 'ENTREGADA'
                 WHERE "idRecorridoAsignacion" = :ra
                """, Map.of("ra", idRecorridoAsignacion));

        sql.update("UPDATE \"PEDIDO\" SET \"estado\" = 'ENTREGADO' WHERE \"idPedido\" = :p",
                Map.of("p", datos.idPedido()));

        // UC-15: reevaluacion exclusivamente con hechos confirmados (sin considerar D_e)
        evaluadorCategoria.reevaluar(idCliente);

        return new EntregaRegistrada(
                datos.idPedido(),
                idRecorrido,
                ahora,
                "ENTREGADO",
                cargoRealCe,
                cobertura.anticipoExplicitoPe(),
                cobertura.fifoVinculanteFe(),
                cobertura.coberturaTotalKe(),
                cobertura.exigibleEe(),
                declaradoDe,
                cobertura.reconocimientoInicialV0(),
                cobertura.modoEvidencia(),
                List.copyOf(excedentesGenerados),
                true);
    }

    private EntregaRegistrada verificarIdempotenciaExistente(DatosEntrega datos, long idRepartidor) {
        var prev = sql.queryForList("""
                SELECT r."idPedido",r."idCliente",r."cargoRealCe",r."anticipoExplicitoPe",
                       r."fifoVinculanteFe",r."coberturaTotalKe",r."exigibleEe",
                       r."declaradoRepartidorDe",r."reconocimientoInicialV0",r."modoEvidencia",
                       e."fechaHoraEntrega",e."idUsuarioRepartidor",
                       re."idRecorrido",p."estado" AS estado_pedido
                  FROM "RECONOCIMIENTO_ENTREGA_PE1" r
                  JOIN "ENTREGA" e ON e."idPedido" = r."idPedido"
                  JOIN "RECORRIDO_ENTREGA" re ON re."idPedido" = r."idPedido"
                  JOIN "PEDIDO" p ON p."idPedido" = r."idPedido"
                 WHERE r."claveIdempotenciaEntrega" = :clave
                """, Map.of("clave", datos.claveIdempotencia()));
        if (prev.isEmpty()) {
            return null;
        }
        var row = prev.getFirst();
        long idPedidoPrev = ((Number) row.get("idPedido")).longValue();
        long idRepPrev = ((Number) row.get("idUsuarioRepartidor")).longValue();

        // Revalidar ambito e identidad en replay antes de devolver informacion
        if (idRepPrev != idRepartidor) {
            throw new SecurityException(PROHIBIDA);
        }
        if (idPedidoPrev != datos.idPedido()) {
            throw new IllegalStateException("IDEMPOTENCIA_CONFLICTO_DATOS");
        }

        BigDecimal deNorm = datos.efectivoDeclaradoRecibido() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : datos.efectivoDeclaradoRecibido().setScale(2, RoundingMode.HALF_UP);
        BigDecimal dePrev = ((BigDecimal) row.get("declaradoRepartidorDe")).setScale(2, RoundingMode.HALF_UP);
        if (deNorm.compareTo(dePrev) != 0) {
            throw new IllegalStateException("IDEMPOTENCIA_CONFLICTO_DATOS");
        }

        // Comparar mapa canonico de cantidades entregadas por idProducto
        var prevItems = sql.query("""
                SELECT dp."idProducto", de."cantidadEntregada"
                  FROM "DETALLE_ENTREGA" de
                  JOIN "DETALLE_PEDIDO" dp
                    ON dp."idPedido" = de."idPedido" AND dp."numeroDetalle" = de."numeroDetalle"
                 WHERE de."idPedido" = :p
                """, Map.of("p", idPedidoPrev),
                (rs, n) -> Map.entry(rs.getLong(1), rs.getBigDecimal(2).setScale(3, RoundingMode.HALF_UP)));
        Map<Long, BigDecimal> prevMapa = new HashMap<>();
        for (var entry : prevItems) {
            prevMapa.put(entry.getKey(), entry.getValue());
        }

        Map<Long, BigDecimal> nuevoMapa = new HashMap<>();
        for (ItemEntrega item : datos.detallesEntrega()) {
            if (!prevMapa.containsKey(item.idProducto())) {
                throw new IllegalStateException("IDEMPOTENCIA_CONFLICTO_DATOS");
            }
            nuevoMapa.put(item.idProducto(), item.cantidadEntregada().setScale(3, RoundingMode.HALF_UP));
        }
        for (var entry : prevMapa.entrySet()) {
            BigDecimal enviada = nuevoMapa.getOrDefault(entry.getKey(), BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
            if (enviada.compareTo(entry.getValue()) != 0) {
                throw new IllegalStateException("IDEMPOTENCIA_CONFLICTO_DATOS");
            }
        }

        var excedentes = sql.query("""
                SELECT x."idExcedente", dp."idProducto", x."numeroDetalleOrigen", x."cantidadInicial"
                  FROM "EXCEDENTE" x
                  JOIN "DETALLE_PEDIDO" dp
                    ON dp."idPedido" = x."idPedidoOrigen" AND dp."numeroDetalle" = x."numeroDetalleOrigen"
                 WHERE x."idPedidoOrigen" = :p AND x."origenGeneracion" = 'ENTREGA'
                 ORDER BY x."idExcedente" ASC
                """, Map.of("p", idPedidoPrev),
                (rs, n) -> new ExcedenteGenerado(
                        rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getBigDecimal(4)));

        return new EntregaRegistrada(
                idPedidoPrev,
                ((Number) row.get("idRecorrido")).longValue(),
                ((java.sql.Timestamp) row.get("fechaHoraEntrega")).toInstant().atOffset(java.time.ZoneOffset.UTC),
                (String) row.get("estado_pedido"),
                ((BigDecimal) row.get("cargoRealCe")).setScale(2, RoundingMode.HALF_UP),
                ((BigDecimal) row.get("anticipoExplicitoPe")).setScale(2, RoundingMode.HALF_UP),
                ((BigDecimal) row.get("fifoVinculanteFe")).setScale(2, RoundingMode.HALF_UP),
                ((BigDecimal) row.get("coberturaTotalKe")).setScale(2, RoundingMode.HALF_UP),
                ((BigDecimal) row.get("exigibleEe")).setScale(2, RoundingMode.HALF_UP),
                dePrev,
                ((BigDecimal) row.get("reconocimientoInicialV0")).setScale(2, RoundingMode.HALF_UP),
                (String) row.get("modoEvidencia"),
                excedentes,
                false);
    }

    private void verificarAusenciaAdicionalesUc07(long idPedido) {
        Integer ofertasAceptadas = sql.queryForObject("""
                SELECT count(*)
                  FROM "OFERTA_EXCEDENTE"
                 WHERE "idPedidoReceptor" = :p AND "respuesta" = 'ACEPTADA'
                """, Map.of("p", idPedido), Integer.class);
        Integer lineasTecnicas = sql.queryForObject("""
                SELECT count(*)
                  FROM "DETALLE_PEDIDO"
                 WHERE "idPedido" = :p AND "cantidadOriginal" = 0
                """, Map.of("p", idPedido), Integer.class);
        Integer imputaciones = sql.queryForObject("""
                SELECT count(*)
                  FROM "IMPUTACION_ENTREGA_ADICIONAL"
                 WHERE "idPedido" = :p
                """, Map.of("p", idPedido), Integer.class);
        if ((ofertasAceptadas != null && ofertasAceptadas > 0)
                || (lineasTecnicas != null && lineasTecnicas > 0)
                || (imputaciones != null && imputaciones > 0)) {
            throw new IllegalStateException("ADICIONALES_UC07_NO_SOPORTADOS");
        }
    }

    private static BigDecimal calcularCeDesdeSnapshot(
            BigDecimal baseSinRedondeo, String tipoAjuste, BigDecimal porcentajeAjuste) {
        BigDecimal base = baseSinRedondeo.setScale(2, RoundingMode.HALF_UP);
        if ("SIN_AJUSTE".equals(tipoAjuste)) {
            return base;
        }
        BigDecimal ajuste = base.multiply(porcentajeAjuste).divide(CIEN, 2, RoundingMode.HALF_UP);
        if ("DESCUENTO".equals(tipoAjuste)) {
            return base.subtract(ajuste).max(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        if ("RECARGO".equals(tipoAjuste)) {
            return base.add(ajuste);
        }
        throw new IllegalStateException("TIPO_AJUSTE_PEDIDO_INVALIDO");
    }

    private long siguiente(String consulta) {
        Long id = sql.getJdbcTemplate().queryForObject(consulta, Long.class);
        if (id == null || id <= 0) {
            throw new IllegalStateException("Sin identificador");
        }
        return id;
    }

    private static void validarDatos(DatosEntrega d) {
        if (d == null || d.idPedido() <= 0 || d.claveIdempotencia() == null
                || d.detallesEntrega() == null || d.detallesEntrega().isEmpty()) {
            throw new IllegalArgumentException(INVALIDA);
        }
        if (d.efectivoDeclaradoRecibido() != null) {
            BigDecimal de = d.efectivoDeclaradoRecibido();
            if (de.signum() < 0 || de.scale() > 2 || de.precision() - de.scale() > 16) {
                throw new IllegalArgumentException(INVALIDA);
            }
        }
        Set<Long> vistos = new HashSet<>();
        BigDecimal suma = BigDecimal.ZERO;
        for (ItemEntrega item : d.detallesEntrega()) {
            if (item == null || item.idProducto() <= 0 || item.cantidadEntregada() == null) {
                throw new IllegalArgumentException(INVALIDA);
            }
            BigDecimal c = item.cantidadEntregada();
            if (c.signum() < 0 || c.scale() > 3 || c.precision() - c.scale() > 15 || !vistos.add(item.idProducto())) {
                throw new IllegalArgumentException(INVALIDA);
            }
            suma = suma.add(c);
        }
        if (suma.signum() <= 0) {
            throw new IllegalArgumentException("CANTIDADES_EN_CERO");
        }
    }

    private record DetalleOriginal(
            int numeroDetalle,
            long idProducto,
            BigDecimal cantidadOriginal,
            BigDecimal precioUnitarioOriginal,
            BigDecimal porcentajePromocionOriginal) {}

    private record ProductoEstado(
            long idProducto,
            String tipoProducto,
            BigDecimal stockActualReventa,
            BigDecimal costoPromedioActualReventa) {}

    private record ReventaEgreso(BigDecimal costoPromedio, BigDecimal costoTotalVenta) {}

    private record LineaPreparadaParaEscribir(
            int numeroDetalle,
            long idProducto,
            String tipoProducto,
            BigDecimal cantidadEntregada,
            BigDecimal costoHistoricoElaboradoVendido,
            BigDecimal excedenteCantidad,
            BigDecimal excedenteCosto,
            BigDecimal nuevoStockReventa,
            ReventaEgreso reventaEgreso) {}
}
