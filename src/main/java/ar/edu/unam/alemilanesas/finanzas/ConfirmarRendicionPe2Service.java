package ar.edu.unam.alemilanesas.finanzas;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import ar.edu.unam.alemilanesas.finanzas.RendicionPreliminarPe2Service.CoberturaNoAcreditadaException;
import ar.edu.unam.alemilanesas.finanzas.RendicionPreliminarPe2Service.PropuestaAplicacion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * R09.3b-B — Confirmacion financiera definitiva PE-2 (UC-29, exclusivo ADMINISTRADOR, T1-C2 / K2 / C1-a / E-04):
 * - Cierre limpio directo (ABIERTO -> CONFIRMADO) con cero discrepancias y aplicaciones positivas.
 * - Confirmacion de conciliacion resuelta (PENDIENTE_CONCILIACION -> CERRADO_CON_DIFERENCIA_RESUELTA,
 *   o CONFIRMADO via B1-R1 cuando exista revision vigente N>1 acreditada a 0/0).
 * - En una unica transaccion ACID T1-C2:
 *   - Revalida ADMINISTRADOR habilitado antes y despues de A2(NS_RECORRIDO) -> A1(2929001), incluso en replay.
 *   - Emite a lo sumo 1 COBRO (origen='REPARTO', medioPago='EFECTIVO', estadoCobro='CONFIRMADO') por Cliente/cierre,
 *     con 1 MOVIMIENTO_CUENTA(PAGO), 1 ORIGEN_MOVIMIENTO_CUENTA, 1 MOVIMIENTO_CAJA(INGRESO), COBRO_RENDICION_K2,
 *     1..N APLICACION_RENDICION_K2, EVENTO_IMPUTACION_FINANCIERA(APLICACION_RENDICION_K2),
 *     extincion exacta de RECONOCIMIENTO_ENTREGA_PE1.reconocimientoVigenteVvig y reevaluacion UC-15.
 *   - Registra EGRESO_CUSTODIA_RENDICION (DEVOLUCION_FISICA_CON_RECIBO) sin crear Caja de ventas cuando aplica E-04.
 *   - Persiste reciboHistoricoJson inmutable, versionHuella 'v1' con serializacion inyectiva (4-byte BE length + UTF-8)
 *     y cierra RECORRIDO_REPARTO a CERRADO en el mismo COMMIT.
 * - Fuera de este corte: rectificaciones/invalidacion R09.3b-C (B1-R1/B1-R2), cierre de Recorrido vacio sin PE-2
 *   (G_ARQ_N2_01 s3.2) y exposicion HTTP/UI UC-29.
 */
@Service
public class ConfirmarRendicionPe2Service {
    private static final String ADMINISTRADOR = "ADMINISTRADOR";
    private static final String VERSION_HUELLA = "v1";
    private static final String ESTADO_PENDIENTE = "PENDIENTE_CONCILIACION";
    private static final String ESTADO_CONFIRMADO = "CONFIRMADO";
    private static final String ESTADO_RESUELTA = "CERRADO_CON_DIFERENCIA_RESUELTA";
    private static final String ESTADO_CERRADO = "CERRADO";
    private static final String RES_FALTANTE = "FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO";
    private static final String RES_CUSTODIA = "CUSTODIA_DEVUELTA_FISICAMENTE";
    private static final String RES_MIXTA = "MIXTA_FALTANTE_Y_CUSTODIA";
    private static final String RES_B1_R1 = "RECTIFICACION_ACREDITADA_SIN_DIFERENCIA";
    private static final String INVALIDA = "SOLICITUD_INVALIDA";
    private static final String ERR_TIPO_INCOHERENTE = "TIPO_RESOLUCION_INCOHERENTE";

    private static final String COL_ID_CIERRE = "idCierreRecorrido";
    private static final String COL_ID_RECORRIDO = "idRecorrido";
    private static final String COL_ID_CLIENTE = "idCliente";
    private static final String COL_ID_PEDIDO = "idPedido";
    private static final String COL_ID_COBRO = "idCobro";
    private static final String COL_ID_REV_VIGENTE = "idRevisionVigente";
    private static final String COL_ESTADO_CIERRE = "estadoCierre";
    private static final String COL_TIPO_RES = "tipoResolucionAdmin";
    private static final String COL_IMPORTE = "importe";
    private static final String COL_IMP_ESPERADO = "importeEsperado";
    private static final String COL_IMP_RECIBIDO = "importeEfectivoRecibido";
    private static final String COL_IMP_CONFIRMADO = "importeImputadoConfirmado";
    private static final String COL_IMP_FALTANTE = "importeDiferenciaFaltante";
    private static final String COL_IMP_CUSTODIA = "importeCustodiaNoImputada";
    private static final String COL_IMP_APLICADO = "importeAplicado";
    private static final String COL_MOV_CUENTA = "idMovimientoCuenta";
    private static final String COL_MOV_CAJA = "idMovimientoCaja";
    private static final String COL_EVENTO_CUST = "idEventoCustodia";
    private static final String COL_IMP_EGRESO = "importeEgreso";
    private static final String COL_RECIBO_DEV = "numeroReciboDevolucion";
    private static final String COL_RECEPTOR = "receptorIdentificacion";

    private static final String PARAM_CIERRE = "cierre";
    private static final String PARAM_CLIENTE = "cliente";
    private static final String PARAM_PEDIDO = "pedido";
    private static final String PARAM_COBRO = "cobro";
    private static final String PARAM_ADMIN = "admin";
    private static final String PARAM_AHORA = "ahora";
    private static final String PARAM_REC = "rec";
    private static final String PARAM_REV = "rev";
    private static final String PARAM_CLAVE = "clave";
    private static final String PARAM_RECIBO = "recibo";
    private static final String PARAM_IMPORTE = "importe";

    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    private static final int MAX_MOTIVO = 1000;
    private static final int MAX_RECIBO = 80;
    private static final int MAX_RECEPTOR = 120;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    private final ProtocoloIdentificadoresFinancieros identificadores;
    private final EvaluadorCategoriaComercialInterno evaluadorCategoria;

    public ConfirmarRendicionPe2Service(
            NamedParameterJdbcTemplate sql,
            AutorizacionSesionInterna autorizacion,
            ProtocoloIdentificadoresFinancieros identificadores,
            EvaluadorCategoriaComercialInterno evaluadorCategoria) {
        this.sql = sql;
        this.autorizacion = autorizacion;
        this.identificadores = identificadores;
        this.evaluadorCategoria = evaluadorCategoria;
    }

    public record DatosConfirmacionLimpia(
            long idRecorrido,
            UUID claveIdempotenciaCierre,
            BigDecimal importeEfectivoRecibido,
            List<PropuestaAplicacion> aplicaciones) {}

    public record EvidenciaEgresoCustodia(
            String numeroReciboDevolucion,
            String receptorIdentificacion,
            String motivo) {}

    public record DatosConfirmacionConciliacion(
            long idRecorrido,
            int numeroRevisionEsperado,
            UUID claveIdempotenciaCierre,
            String tipoResolucionAdmin,
            String motivoResolucionAdmin,
            EvidenciaEgresoCustodia egresoCustodia) {}

    public record CobroK2Registrado(
            long idCliente,
            long idCobro,
            BigDecimal importe,
            long idMovimientoCuenta,
            long idMovimientoCaja) {}

    public record AplicacionK2Registrada(
            long idCliente,
            long idPedido,
            long idCobro,
            BigDecimal importeAplicado) {}

    public record EgresoCustodiaRegistrado(
            long idEgresoCustodia,
            long idEventoCustodia,
            BigDecimal importeEgreso,
            String numeroReciboDevolucion,
            String receptorIdentificacion) {}

    public record RendicionConfirmada(
            long idCierreRecorrido,
            long idRecorrido,
            Long idRevisionConfirmada,
            Integer numeroRevisionConfirmada,
            String estadoCierre,
            String estadoRecorrido,
            String tipoResolucionAdmin,
            UUID claveIdempotenciaCierre,
            String versionHuella,
            String huellaCanonica,
            BigDecimal importeEsperado,
            BigDecimal importeEfectivoRecibido,
            BigDecimal importeImputadoConfirmado,
            BigDecimal importeDiferenciaFaltante,
            BigDecimal importeCustodiaNoImputada,
            String reciboHistoricoJson,
            List<CobroK2Registrado> cobros,
            List<AplicacionK2Registrada> aplicaciones,
            EgresoCustodiaRegistrado egresoCustodia,
            boolean creado) {}

    private record EntradaLimpia(
            long idRecorrido,
            UUID claveCierre,
            BigDecimal efectivoRecibido,
            List<PropuestaAplicacion> aplicacionesOrdenadas) {}

    private record EntradaConciliacion(
            long idRecorrido,
            int numeroRevisionEsperado,
            UUID claveCierre,
            String tipoResolucionAdmin,
            String motivoResolucionAdmin,
            EvidenciaEgresoCustodia egresoNormalizado) {}

    private record RecorridoRow(long idUsuarioRepartidor, String estadoOperativo) {}

    private record EntregaPe1(
            long idPedido,
            long idCliente,
            long idRepEntrega,
            BigDecimal vVig) {}

    private record PlanCobroCliente(
            long idCliente,
            long idCobro,
            long idMovimientoCuenta,
            long idMovimientoCaja,
            BigDecimal importeCobro,
            List<PropuestaAplicacion> aplicaciones) {}

    private record ContextoRecibo(
            long idRecorrido,
            String estadoDestino,
            boolean viaB1R1,
            Integer numeroRevisionConfirmada,
            String tipoResolucionAdmin,
            BigDecimal esperado,
            BigDecimal recibido,
            BigDecimal confirmado,
            BigDecimal faltante,
            BigDecimal custodia,
            List<PlanCobroCliente> planClientes,
            Long idEventoCustodia,
            EvidenciaEgresoCustodia egreso) {}

    private record ContextoEfectosK2(
            long idCierre,
            long idAdmin,
            UUID claveCierre,
            OffsetDateTime ahora,
            List<PlanCobroCliente> planClientes) {}

    private record ResultadoEfectosK2(
            List<CobroK2Registrado> cobros,
            List<AplicacionK2Registrada> aplicaciones) {}

    private record CabeceraPendiente(
            long idCierreRecorrido,
            String estadoCierre,
            Long idRevisionVigente,
            BigDecimal importeEsperado,
            BigDecimal importeEfectivoRecibido,
            BigDecimal importeImputadoPropuesto,
            BigDecimal importeDiferenciaFaltante,
            BigDecimal importeCustodiaNoImputada) {}

    private record CabeceraTerminalReplay(
            long idCierreRecorrido,
            long idRecorrido,
            Long idRevisionVigente,
            String estadoCierre,
            String estadoRecorrido,
            String tipoResolucionAdmin,
            UUID claveIdempotenciaCierre,
            String versionHuella,
            String huellaCanonica,
            BigDecimal importeEsperado,
            BigDecimal importeEfectivoRecibido,
            BigDecimal importeImputadoConfirmado,
            BigDecimal importeDiferenciaFaltante,
            BigDecimal importeCustodiaNoImputada,
            String reciboHistoricoJson) {}

    /**
     * Confirmacion directa T1-C2 de un Recorrido ABIERTO sin discrepancias (estado CONFIRMADO).
     */
    @Transactional
    public RendicionConfirmada confirmarCierreLimpio(UUID sesion, DatosConfirmacionLimpia datos) {
        autorizacion.exigirRoles(sesion, Set.of(ADMINISTRADOR));
        EntradaLimpia entrada = validarEntradaLimpia(datos);
        String huella = construirHuellaLimpia(entrada);

        identificadores.bloquearRecorridoA2(entrada.idRecorrido());
        identificadores.bloquear();
        autorizacion.exigirRoles(sesion, Set.of(ADMINISTRADOR));
        long idAdmin = resolverIdUsuarioSesion(sesion);

        RecorridoRow recorrido = cargarRecorridoForUpdate(entrada.idRecorrido());
        RendicionConfirmada replay = verificarReplayOConflictoLimpio(entrada.idRecorrido(), entrada.claveCierre(), huella);
        if (replay != null) {
            return replay;
        }

        if (!"ABIERTO".equals(recorrido.estadoOperativo())) {
            throw new IllegalStateException("RECORRIDO_NO_ABIERTO");
        }
        exigirSinPendientesFa2(entrada.idRecorrido());
        verificarIntegridadAsignacionesEntregadas(entrada.idRecorrido());

        List<EntregaPe1> entregas = cargarEntregasPe1SinBloqueo(entrada.idRecorrido(), recorrido.idUsuarioRepartidor());
        if (entregas.isEmpty()) {
            throw new IllegalStateException("RECORRIDO_SIN_ENTREGAS");
        }

        BigDecimal esperado = validarCoherenciaLimpiaYCalcularEsperado(entrada, entregas);
        if (entrada.aplicacionesOrdenadas().isEmpty() || esperado.signum() == 0) {
            throw new IllegalStateException("CIERRE_CERO_APLICACIONES_NO_AUTORIZADO");
        }

        Map<Long, BigDecimal> vvigPorPedido = bloquearClientesPedidosYPe1(entrada.aplicacionesOrdenadas());
        validarCoberturaVvig(entrada.aplicacionesOrdenadas(), vvigPorPedido);

        OffsetDateTime ahora = instantePg();
        List<PlanCobroCliente> planClientes = planificarCobrosK2(entrada.aplicacionesOrdenadas());
        String reciboJson = serializarRecibo(new ContextoRecibo(
                entrada.idRecorrido(), ESTADO_CONFIRMADO, false, null, null,
                esperado, entrada.efectivoRecibido(), esperado, CERO, CERO,
                planClientes, null, null));

        var insertado = sql.queryForMap("""
                INSERT INTO "CIERRE_RECORRIDO_PE2" (
                    "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                    "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                    "importeImputadoConfirmado","importeDiferenciaFaltante","importeCustodiaNoImputada",
                    "reciboHistoricoJson")
                VALUES (
                    :rec,:clave,:ver,:huella,:admin,:ahora,'CONFIRMADO','EFECTIVO',
                    :esperado,:recibido,:imputado,:imputado,0,0,:recibo::jsonb)
                RETURNING "idCierreRecorrido","reciboHistoricoJson"::text AS recibo
                """, new MapSqlParameterSource()
                .addValue(PARAM_REC, entrada.idRecorrido())
                .addValue(PARAM_CLAVE, entrada.claveCierre())
                .addValue("ver", VERSION_HUELLA)
                .addValue("huella", huella)
                .addValue(PARAM_ADMIN, idAdmin)
                .addValue(PARAM_AHORA, ahora)
                .addValue("esperado", esperado)
                .addValue("recibido", entrada.efectivoRecibido())
                .addValue("imputado", esperado)
                .addValue(PARAM_RECIBO, reciboJson));

        long idCierre = ((Number) Objects.requireNonNull(insertado.get(COL_ID_CIERRE))).longValue();
        String reciboNormalizado = (String) Objects.requireNonNull(insertado.get(PARAM_RECIBO));

        ResultadoEfectosK2 efectos = ejecutarEfectosFinancierosK2(
                new ContextoEfectosK2(idCierre, idAdmin, entrada.claveCierre(), ahora, planClientes));
        cerrarRecorrido(entrada.idRecorrido());

        return new RendicionConfirmada(
                idCierre, entrada.idRecorrido(), null, null,
                ESTADO_CONFIRMADO, ESTADO_CERRADO, null,
                entrada.claveCierre(), VERSION_HUELLA, huella,
                esperado, entrada.efectivoRecibido(), esperado, CERO, CERO,
                reciboNormalizado, efectos.cobros(), efectos.aplicaciones(), null, true);
    }

    /**
     * Confirmacion definitiva T1-C2 de una conciliacion previamente registrada en PENDIENTE_CONCILIACION.
     */
    @Transactional
    public RendicionConfirmada confirmarConciliacionResuelta(UUID sesion, DatosConfirmacionConciliacion datos) {
        autorizacion.exigirRoles(sesion, Set.of(ADMINISTRADOR));
        EntradaConciliacion entrada = validarEntradaConciliacion(datos);
        String huella = construirHuellaConciliacion(entrada);

        identificadores.bloquearRecorridoA2(entrada.idRecorrido());
        identificadores.bloquear();
        autorizacion.exigirRoles(sesion, Set.of(ADMINISTRADOR));
        long idAdmin = resolverIdUsuarioSesion(sesion);

        cargarRecorridoForUpdate(entrada.idRecorrido());
        RendicionConfirmada replay = verificarReplayClaveCierre(entrada.idRecorrido(), entrada.claveCierre(), huella);
        if (replay != null) {
            return replay;
        }

        CabeceraPendiente cabecera = cargarCabeceraParaConfirmar(entrada.idRecorrido());
        exigirSinPendientesFa2(entrada.idRecorrido());

        long idRevVigente = exigirRevisionVigenteCoincidente(cabecera, entrada.numeroRevisionEsperado());
        boolean esB1R1Cero = cabecera.importeDiferenciaFaltante().signum() == 0
                && cabecera.importeCustodiaNoImputada().signum() == 0;
        String estadoDestino = validarResolucionYDeterminarEstadoDestino(cabecera, entrada, esB1R1Cero);

        List<PropuestaAplicacion> propuestas = cargarPropuestasRevision(idRevVigente);
        Long idEventoCustodia = cabecera.importeCustodiaNoImputada().signum() > 0
                ? resolverEventoCustodiaVigente(cabecera.idCierreRecorrido(), idRevVigente, entrada.egresoNormalizado())
                : null;

        Map<Long, BigDecimal> vvigPorPedido = bloquearClientesPedidosYPe1(propuestas);
        validarCoberturaVvig(propuestas, vvigPorPedido);

        OffsetDateTime ahora = instantePg();
        List<PlanCobroCliente> planClientes = planificarCobrosK2(propuestas);
        String reciboJson = serializarRecibo(new ContextoRecibo(
                entrada.idRecorrido(), estadoDestino, esB1R1Cero, entrada.numeroRevisionEsperado(),
                entrada.tipoResolucionAdmin(),
                cabecera.importeEsperado(), cabecera.importeEfectivoRecibido(),
                cabecera.importeImputadoPropuesto(), cabecera.importeDiferenciaFaltante(),
                cabecera.importeCustodiaNoImputada(), planClientes, idEventoCustodia, entrada.egresoNormalizado()));

        sql.update("""
                UPDATE "REVISION_CONCILIACION_PE2"
                   SET "estadoRevision" = 'CONFIRMADA_T1'
                 WHERE "idRevisionConciliacion" = :rev
                """, Map.of(PARAM_REV, idRevVigente));

        String reciboNormalizado = Objects.requireNonNull(sql.queryForObject("""
                UPDATE "CIERRE_RECORRIDO_PE2"
                   SET "estadoCierre" = :estado,
                       "claveIdempotenciaCierre" = :clave,
                       "versionHuella" = :ver,
                       "huellaCanonica" = :huella,
                       "importeImputadoConfirmado" = "importeImputadoPropuesto",
                       "idUsuarioResolucionAdmin" = :admin,
                       "tipoResolucionAdmin" = :tipoRes,
                       "motivoResolucionAdmin" = :motivoRes,
                       "fechaHoraResolucion" = :ahora,
                       "reciboHistoricoJson" = :recibo::jsonb
                 WHERE "idCierreRecorrido" = :cierre
                 RETURNING "reciboHistoricoJson"::text
                """, new MapSqlParameterSource()
                .addValue("estado", estadoDestino)
                .addValue(PARAM_CLAVE, entrada.claveCierre())
                .addValue("ver", VERSION_HUELLA)
                .addValue("huella", huella)
                .addValue(PARAM_ADMIN, idAdmin)
                .addValue("tipoRes", entrada.tipoResolucionAdmin())
                .addValue("motivoRes", entrada.motivoResolucionAdmin())
                .addValue(PARAM_AHORA, ahora)
                .addValue(PARAM_RECIBO, reciboJson)
                .addValue(PARAM_CIERRE, cabecera.idCierreRecorrido()), String.class));

        ResultadoEfectosK2 efectos = ejecutarEfectosFinancierosK2(new ContextoEfectosK2(
                cabecera.idCierreRecorrido(), idAdmin, entrada.claveCierre(), ahora, planClientes));

        EgresoCustodiaRegistrado egresoRegistrado = idEventoCustodia == null ? null : registrarEgresoCustodia(
                cabecera.idCierreRecorrido(), idEventoCustodia, cabecera.importeCustodiaNoImputada(),
                entrada.egresoNormalizado(), idAdmin, ahora);

        cerrarRecorrido(entrada.idRecorrido());

        return new RendicionConfirmada(
                cabecera.idCierreRecorrido(), entrada.idRecorrido(),
                idRevVigente, entrada.numeroRevisionEsperado(),
                estadoDestino, ESTADO_CERRADO, entrada.tipoResolucionAdmin(),
                entrada.claveCierre(), VERSION_HUELLA, huella,
                cabecera.importeEsperado(), cabecera.importeEfectivoRecibido(),
                cabecera.importeImputadoPropuesto(), cabecera.importeDiferenciaFaltante(),
                cabecera.importeCustodiaNoImputada(), reciboNormalizado,
                efectos.cobros(), efectos.aplicaciones(), egresoRegistrado, true);
    }

    private EntradaLimpia validarEntradaLimpia(DatosConfirmacionLimpia datos) {
        if (datos == null || datos.idRecorrido() <= 0 || datos.claveIdempotenciaCierre() == null
                || datos.aplicaciones() == null) {
            throw new IllegalArgumentException(INVALIDA);
        }
        BigDecimal recibido = normalizarImporte(datos.importeEfectivoRecibido(), true);
        Set<Long> vistos = new HashSet<>();
        List<PropuestaAplicacion> apps = new ArrayList<>(datos.aplicaciones().size());
        for (PropuestaAplicacion p : datos.aplicaciones()) {
            if (p == null || p.idCliente() <= 0 || p.idPedido() <= 0) {
                throw new IllegalArgumentException(INVALIDA);
            }
            if (!vistos.add(p.idPedido())) {
                throw new IllegalArgumentException("APLICACION_PEDIDO_DUPLICADO");
            }
            apps.add(new PropuestaAplicacion(p.idCliente(), p.idPedido(), normalizarImporte(p.importePropuesto(), false)));
        }
        apps.sort(Comparator.comparingLong(PropuestaAplicacion::idPedido));
        return new EntradaLimpia(datos.idRecorrido(), datos.claveIdempotenciaCierre(), recibido, List.copyOf(apps));
    }

    private EntradaConciliacion validarEntradaConciliacion(DatosConfirmacionConciliacion datos) {
        if (datos == null || datos.idRecorrido() <= 0 || datos.numeroRevisionEsperado() <= 0
                || datos.claveIdempotenciaCierre() == null || datos.tipoResolucionAdmin() == null) {
            throw new IllegalArgumentException(INVALIDA);
        }
        String tipo = datos.tipoResolucionAdmin().trim();
        if (!Set.of(RES_FALTANTE, RES_CUSTODIA, RES_MIXTA, RES_B1_R1).contains(tipo)) {
            throw new IllegalArgumentException("TIPO_RESOLUCION_INVALIDO");
        }
        String motivo = normalizarTexto(datos.motivoResolucionAdmin(), MAX_MOTIVO);
        EvidenciaEgresoCustodia egreso = null;
        if (datos.egresoCustodia() != null) {
            egreso = new EvidenciaEgresoCustodia(
                    normalizarTexto(datos.egresoCustodia().numeroReciboDevolucion(), MAX_RECIBO),
                    normalizarTexto(datos.egresoCustodia().receptorIdentificacion(), MAX_RECEPTOR),
                    normalizarTexto(datos.egresoCustodia().motivo(), MAX_MOTIVO));
        }
        return new EntradaConciliacion(
                datos.idRecorrido(), datos.numeroRevisionEsperado(),
                datos.claveIdempotenciaCierre(), tipo, motivo, egreso);
    }

    private static BigDecimal normalizarImporte(BigDecimal valor, boolean permitirCero) {
        if (valor == null || valor.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(INVALIDA);
        }
        BigDecimal norm = valor.setScale(2, RoundingMode.UNNECESSARY);
        int cmp = norm.compareTo(CERO);
        if (cmp < 0 || (!permitirCero && cmp == 0)) {
            throw new IllegalArgumentException(INVALIDA);
        }
        return norm;
    }

    private static String normalizarTexto(String texto, int maxLen) {
        if (texto == null || texto.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(INVALIDA);
        }
        String norm = texto.trim().replaceAll("\\s+", " ");
        if (norm.isEmpty() || norm.length() > maxLen) {
            throw new IllegalArgumentException(INVALIDA);
        }
        return norm;
    }

    /**
     * Serializacion canonica inyectiva SHA-256 v1 (longitud prefijada 4 bytes big-endian + UTF-8 por campo),
     * coherente con R09.3b-A y libre de ambiguedad por delimitadores.
     */
    private static String construirHuellaLimpia(EntradaLimpia entrada) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            actualizarCampo(md, "PE2_CONF_LIMPIO");
            actualizarCampo(md, VERSION_HUELLA);
            actualizarCampo(md, Long.toString(entrada.idRecorrido()));
            actualizarCampo(md, entrada.efectivoRecibido().toPlainString());
            actualizarCampo(md, Integer.toString(entrada.aplicacionesOrdenadas().size()));
            for (PropuestaAplicacion a : entrada.aplicacionesOrdenadas()) {
                actualizarCampo(md, Long.toString(a.idCliente()));
                actualizarCampo(md, Long.toString(a.idPedido()));
                actualizarCampo(md, a.importePropuesto().toPlainString());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private static String construirHuellaConciliacion(EntradaConciliacion entrada) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            actualizarCampo(md, "PE2_CONF_CONC");
            actualizarCampo(md, VERSION_HUELLA);
            actualizarCampo(md, Long.toString(entrada.idRecorrido()));
            actualizarCampo(md, Integer.toString(entrada.numeroRevisionEsperado()));
            actualizarCampo(md, entrada.tipoResolucionAdmin());
            actualizarCampo(md, entrada.motivoResolucionAdmin());
            if (entrada.egresoNormalizado() == null) {
                actualizarCampo(md, "0");
            } else {
                actualizarCampo(md, "1");
                actualizarCampo(md, entrada.egresoNormalizado().numeroReciboDevolucion());
                actualizarCampo(md, entrada.egresoNormalizado().receptorIdentificacion());
                actualizarCampo(md, entrada.egresoNormalizado().motivo());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private static void actualizarCampo(MessageDigest md, String valor) {
        byte[] bytes = valor.getBytes(StandardCharsets.UTF_8);
        md.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        md.update(bytes);
    }

    private long resolverIdUsuarioSesion(UUID sesion) {
        return Objects.requireNonNull(sql.queryForObject(
                "SELECT \"idUsuario\" FROM \"SESION_USUARIO\" WHERE \"idSesionUsuario\" = :s",
                Map.of("s", sesion), Long.class));
    }

    private OffsetDateTime instantePg() {
        return Objects.requireNonNull(sql.getJdbcTemplate().queryForObject(
                "SELECT clock_timestamp()", (rs, n) -> rs.getObject(1, OffsetDateTime.class)));
    }

    private RecorridoRow cargarRecorridoForUpdate(long idRecorrido) {
        var filas = sql.queryForList("""
                SELECT "idUsuarioRepartidor","estadoOperativo"
                  FROM "RECORRIDO_REPARTO"
                 WHERE "idRecorrido" = :rec
                 FOR UPDATE
                """, Map.of(PARAM_REC, idRecorrido));
        if (filas.isEmpty()) {
            throw new IllegalArgumentException("RECORRIDO_NO_ENCONTRADO");
        }
        var row = filas.getFirst();
        return new RecorridoRow(
                ((Number) row.get("idUsuarioRepartidor")).longValue(),
                (String) row.get("estadoOperativo"));
    }

    private void exigirSinPendientesFa2(long idRecorrido) {
        Integer activos = sql.queryForObject("""
                SELECT count(*)
                  FROM "RECORRIDO_ASIGNACION"
                 WHERE "idRecorrido" = :rec
                   AND "estadoPertenencia" = 'ACTIVA'
                """, Map.of(PARAM_REC, idRecorrido), Integer.class);
        if (activos != null && activos > 0) {
            throw new IllegalStateException("FA2_PEDIDOS_PENDIENTES_ACTIVOS");
        }
    }

    private void verificarIntegridadAsignacionesEntregadas(long idRecorrido) {
        Integer huerfanas = sql.queryForObject("""
                SELECT count(*)
                  FROM "RECORRIDO_ASIGNACION" ra
                 WHERE ra."idRecorrido" = :rec
                   AND ra."estadoPertenencia" = 'ENTREGADA'
                   AND NOT EXISTS (
                       SELECT 1 FROM "RECORRIDO_ENTREGA" re
                        WHERE re."idRecorrido" = ra."idRecorrido"
                          AND re."idPedido" = ra."idPedido"
                   )
                """, Map.of(PARAM_REC, idRecorrido), Integer.class);
        if (huerfanas != null && huerfanas > 0) {
            throw new IllegalStateException("ENTREGA_INCONSISTENTE_EN_RECORRIDO");
        }
    }

    private List<EntregaPe1> cargarEntregasPe1SinBloqueo(long idRecorrido, long idRepRecorrido) {
        Integer sinPe1 = sql.queryForObject("""
                SELECT count(*)
                  FROM "RECORRIDO_ENTREGA" re
                 WHERE re."idRecorrido" = :rec
                   AND NOT EXISTS (
                       SELECT 1 FROM "RECONOCIMIENTO_ENTREGA_PE1" pe1
                        WHERE pe1."idPedido" = re."idPedido"
                   )
                """, Map.of(PARAM_REC, idRecorrido), Integer.class);
        if (sinPe1 != null && sinPe1 > 0) {
            throw new IllegalStateException("PE1_FALTANTE_EN_RECORRIDO");
        }
        var filas = sql.queryForList("""
                SELECT re."idPedido", e."idUsuarioRepartidor" AS "idRepEntrega",
                       pe1."idCliente", pe1."reconocimientoVigenteVvig"
                  FROM "RECORRIDO_ENTREGA" re
                  JOIN "ENTREGA" e ON e."idPedido" = re."idPedido"
                  JOIN "RECONOCIMIENTO_ENTREGA_PE1" pe1 ON pe1."idPedido" = re."idPedido"
                 WHERE re."idRecorrido" = :rec
                 ORDER BY re."idPedido" ASC
                """, Map.of(PARAM_REC, idRecorrido));
        List<EntregaPe1> out = new ArrayList<>(filas.size());
        for (var row : filas) {
            long idRepEnt = ((Number) row.get("idRepEntrega")).longValue();
            if (idRepEnt != idRepRecorrido) {
                throw new IllegalStateException("REPARTIDOR_ENTREGA_INCONSISTENTE");
            }
            out.add(new EntregaPe1(
                    ((Number) row.get(COL_ID_PEDIDO)).longValue(),
                    ((Number) row.get(COL_ID_CLIENTE)).longValue(),
                    idRepEnt,
                    escala2((BigDecimal) row.get("reconocimientoVigenteVvig"))));
        }
        return out;
    }

    private BigDecimal validarCoherenciaLimpiaYCalcularEsperado(EntradaLimpia entrada, List<EntregaPe1> entregas) {
        Map<Long, EntregaPe1> porPedido = HashMap.newHashMap(entregas.size());
        BigDecimal esperado = CERO;
        for (EntregaPe1 e : entregas) {
            porPedido.put(e.idPedido(), e);
            esperado = esperado.add(e.vVig());
        }
        BigDecimal totalAplicado = CERO;
        for (PropuestaAplicacion a : entrada.aplicacionesOrdenadas()) {
            EntregaPe1 ent = porPedido.get(a.idPedido());
            if (ent == null) {
                throw new IllegalArgumentException("ENTREGA_NO_PERTENECE_A_RECORRIDO");
            }
            if (ent.idCliente() != a.idCliente()) {
                throw new IllegalArgumentException("CLIENTE_NO_COINCIDE_CON_ENTREGA");
            }
            if (a.importePropuesto().compareTo(ent.vVig()) > 0) {
                throw new CoberturaNoAcreditadaException(a.idPedido(), a.importePropuesto(), ent.vVig());
            }
            totalAplicado = totalAplicado.add(a.importePropuesto());
        }
        if (totalAplicado.compareTo(entrada.efectivoRecibido()) > 0) {
            throw new IllegalArgumentException("IMPUTACION_EXCEDE_EFECTIVO_RECIBIDO");
        }
        if (totalAplicado.compareTo(esperado) != 0 || entrada.efectivoRecibido().compareTo(esperado) != 0) {
            throw new IllegalStateException("DIFERENCIA_REQUIERE_CONCILIACION_PRELIMINAR");
        }
        return esperado;
    }

    private RendicionConfirmada verificarReplayOConflictoLimpio(long idRecorrido, UUID claveCierre, String huella) {
        RendicionConfirmada replay = verificarReplayClaveCierre(idRecorrido, claveCierre, huella);
        if (replay != null) {
            return replay;
        }
        var existentes = sql.queryForList("""
                SELECT "estadoCierre"
                  FROM "CIERRE_RECORRIDO_PE2"
                 WHERE "idRecorrido" = :rec
                 FOR UPDATE
                """, Map.of(PARAM_REC, idRecorrido));
        if (existentes.isEmpty()) {
            return null;
        }
        String estado = (String) existentes.getFirst().get(COL_ESTADO_CIERRE);
        if (ESTADO_PENDIENTE.equals(estado)) {
            throw new IllegalStateException("CONCILIACION_PENDIENTE_EXISTENTE");
        }
        throw new IllegalStateException("RECORRIDO_YA_CERRADO");
    }

    private RendicionConfirmada verificarReplayClaveCierre(long idRecorrido, UUID claveCierre, String huellaEsperada) {
        List<CabeceraTerminalReplay> filas = sql.query("""
                SELECT c."idCierreRecorrido", c."idRecorrido", c."idRevisionVigente",
                       c."estadoCierre", r."estadoOperativo", c."tipoResolucionAdmin",
                       c."claveIdempotenciaCierre", c."versionHuella", c."huellaCanonica",
                       c."importeEsperado", c."importeEfectivoRecibido",
                       c."importeImputadoConfirmado", c."importeDiferenciaFaltante",
                       c."importeCustodiaNoImputada", c."reciboHistoricoJson"::text AS recibo
                  FROM "CIERRE_RECORRIDO_PE2" c
                  JOIN "RECORRIDO_REPARTO" r ON r."idRecorrido" = c."idRecorrido"
                 WHERE c."claveIdempotenciaCierre" = :clave
                """, Map.of(PARAM_CLAVE, claveCierre), (rs, i) -> new CabeceraTerminalReplay(
                rs.getLong(COL_ID_CIERRE),
                rs.getLong(COL_ID_RECORRIDO),
                rs.getObject(COL_ID_REV_VIGENTE, Long.class),
                rs.getString(COL_ESTADO_CIERRE),
                rs.getString("estadoOperativo"),
                rs.getString(COL_TIPO_RES),
                rs.getObject("claveIdempotenciaCierre", UUID.class),
                rs.getString("versionHuella"),
                rs.getString("huellaCanonica"),
                escala2(rs.getBigDecimal(COL_IMP_ESPERADO)),
                escala2(rs.getBigDecimal(COL_IMP_RECIBIDO)),
                escala2(rs.getBigDecimal(COL_IMP_CONFIRMADO)),
                escala2(rs.getBigDecimal(COL_IMP_FALTANTE)),
                escala2(rs.getBigDecimal(COL_IMP_CUSTODIA)),
                rs.getString(PARAM_RECIBO)));
        if (filas.isEmpty()) {
            return null;
        }
        CabeceraTerminalReplay row = filas.getFirst();
        if (row.idRecorrido() != idRecorrido) {
            throw new IllegalStateException("CLAVE_IDEMPOTENCIA_CONFLICTO_AMBITO");
        }
        if (!VERSION_HUELLA.equals(row.versionHuella()) || !huellaEsperada.equals(row.huellaCanonica())) {
            throw new IllegalStateException("CLAVE_IDEMPOTENCIA_CONFLICTO_PAYLOAD");
        }
        return construirRespuestaReplay(row);
    }

    private RendicionConfirmada construirRespuestaReplay(CabeceraTerminalReplay row) {
        Integer numRev = row.idRevisionVigente() == null ? null : sql.queryForObject("""
                SELECT "numeroRevision"
                  FROM "REVISION_CONCILIACION_PE2"
                 WHERE "idRevisionConciliacion" = :rev
                """, Map.of(PARAM_REV, row.idRevisionVigente()), Integer.class);

        List<CobroK2Registrado> cobros = sql.query("""
                SELECT ck."idCliente", ck."idCobro", c."importe",
                       omc."idMovimientoCuenta", mj."idMovimientoCaja"
                  FROM "COBRO_RENDICION_K2" ck
                  JOIN "COBRO" c ON c."idCobro" = ck."idCobro"
                  JOIN "ORIGEN_MOVIMIENTO_CUENTA" omc ON omc."idCobro" = ck."idCobro"
                  JOIN "MOVIMIENTO_CAJA" mj ON mj."idCobro" = ck."idCobro" AND mj."origen" = 'COBRO'
                 WHERE ck."idCierreRecorrido" = :cierre
                 ORDER BY ck."idCliente" ASC
                """, Map.of(PARAM_CIERRE, row.idCierreRecorrido()), (rs, i) -> new CobroK2Registrado(
                rs.getLong(COL_ID_CLIENTE),
                rs.getLong(COL_ID_COBRO),
                escala2(rs.getBigDecimal(COL_IMPORTE)),
                rs.getLong(COL_MOV_CUENTA),
                rs.getLong(COL_MOV_CAJA)));

        List<AplicacionK2Registrada> apps = sql.query("""
                SELECT "idCliente","idPedido","idCobro","importeAplicado"
                  FROM "APLICACION_RENDICION_K2"
                 WHERE "idCierreRecorrido" = :cierre
                 ORDER BY "idPedido" ASC
                """, Map.of(PARAM_CIERRE, row.idCierreRecorrido()), (rs, i) -> new AplicacionK2Registrada(
                rs.getLong(COL_ID_CLIENTE),
                rs.getLong(COL_ID_PEDIDO),
                rs.getLong(COL_ID_COBRO),
                escala2(rs.getBigDecimal(COL_IMP_APLICADO))));

        List<EgresoCustodiaRegistrado> egresos = sql.query("""
                SELECT "idEgresoCustodia","idEventoCustodia","importeEgreso",
                       "numeroReciboDevolucion","receptorIdentificacion"
                  FROM "EGRESO_CUSTODIA_RENDICION"
                 WHERE "idCierreRecorrido" = :cierre
                 ORDER BY "idEgresoCustodia" ASC
                """, Map.of(PARAM_CIERRE, row.idCierreRecorrido()), (rs, i) -> new EgresoCustodiaRegistrado(
                rs.getLong("idEgresoCustodia"),
                rs.getLong(COL_EVENTO_CUST),
                escala2(rs.getBigDecimal(COL_IMP_EGRESO)),
                rs.getString(COL_RECIBO_DEV),
                rs.getString(COL_RECEPTOR)));

        return new RendicionConfirmada(
                row.idCierreRecorrido(), row.idRecorrido(), row.idRevisionVigente(), numRev,
                row.estadoCierre(), row.estadoRecorrido(), row.tipoResolucionAdmin(),
                row.claveIdempotenciaCierre(), row.versionHuella(), row.huellaCanonica(),
                row.importeEsperado(), row.importeEfectivoRecibido(), row.importeImputadoConfirmado(),
                row.importeDiferenciaFaltante(), row.importeCustodiaNoImputada(),
                row.reciboHistoricoJson(), List.copyOf(cobros), List.copyOf(apps),
                egresos.isEmpty() ? null : egresos.getFirst(), false);
    }

    private CabeceraPendiente cargarCabeceraParaConfirmar(long idRecorrido) {
        var filas = sql.queryForList("""
                SELECT "idCierreRecorrido","estadoCierre","idRevisionVigente",
                       "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                       "importeDiferenciaFaltante","importeCustodiaNoImputada"
                  FROM "CIERRE_RECORRIDO_PE2"
                 WHERE "idRecorrido" = :rec
                 FOR UPDATE
                """, Map.of(PARAM_REC, idRecorrido));
        if (filas.isEmpty()) {
            throw new IllegalStateException("CONCILIACION_NO_ENCONTRADA");
        }
        var row = filas.getFirst();
        String estado = (String) row.get(COL_ESTADO_CIERRE);
        if (!ESTADO_PENDIENTE.equals(estado)) {
            throw new IllegalStateException("RECORRIDO_YA_CERRADO");
        }
        Number revObj = (Number) row.get(COL_ID_REV_VIGENTE);
        return new CabeceraPendiente(
                ((Number) row.get(COL_ID_CIERRE)).longValue(),
                estado,
                revObj == null ? null : revObj.longValue(),
                escala2((BigDecimal) row.get(COL_IMP_ESPERADO)),
                escala2((BigDecimal) row.get(COL_IMP_RECIBIDO)),
                escala2((BigDecimal) row.get("importeImputadoPropuesto")),
                escala2((BigDecimal) row.get(COL_IMP_FALTANTE)),
                escala2((BigDecimal) row.get(COL_IMP_CUSTODIA)));
    }

    private long exigirRevisionVigenteCoincidente(CabeceraPendiente cabecera, int numeroEsperado) {
        if (cabecera.idRevisionVigente() == null) {
            throw new IllegalStateException("SIN_REVISION_VIGENTE");
        }
        var rev = sql.queryForMap("""
                SELECT "numeroRevision","estadoRevision"
                  FROM "REVISION_CONCILIACION_PE2"
                 WHERE "idRevisionConciliacion" = :rev
                 FOR UPDATE
                """, Map.of(PARAM_REV, cabecera.idRevisionVigente()));
        int actual = ((Number) rev.get("numeroRevision")).intValue();
        if (actual != numeroEsperado || !"VIGENTE".equals(rev.get("estadoRevision"))) {
            throw new IllegalStateException("REVISION_OBSOLETA");
        }
        return cabecera.idRevisionVigente();
    }

    private String validarResolucionYDeterminarEstadoDestino(
            CabeceraPendiente cabecera, EntradaConciliacion entrada, boolean esB1R1Cero) {
        String tipo = entrada.tipoResolucionAdmin();
        boolean tieneEgreso = entrada.egresoNormalizado() != null;

        if (esB1R1Cero) {
            if (!RES_B1_R1.equals(tipo) || tieneEgreso || entrada.numeroRevisionEsperado() <= 1) {
                throw new IllegalArgumentException(ERR_TIPO_INCOHERENTE);
            }
            return ESTADO_CONFIRMADO;
        }
        if (RES_B1_R1.equals(tipo)) {
            throw new IllegalStateException("BLANQUEAMIENTO_NO_AUTORIZADO");
        }

        boolean hayFaltante = cabecera.importeDiferenciaFaltante().signum() > 0;
        boolean hayCustodia = cabecera.importeCustodiaNoImputada().signum() > 0;
        String tipoEsperado = resolverTipoEsperadoConDiferencia(hayFaltante, hayCustodia);
        if (!tipoEsperado.equals(tipo) || tieneEgreso != hayCustodia) {
            throw new IllegalArgumentException(ERR_TIPO_INCOHERENTE);
        }
        return ESTADO_RESUELTA;
    }

    private static String resolverTipoEsperadoConDiferencia(boolean hayFaltante, boolean hayCustodia) {
        if (hayFaltante && hayCustodia) {
            return RES_MIXTA;
        }
        return hayFaltante ? RES_FALTANTE : RES_CUSTODIA;
    }

    private List<PropuestaAplicacion> cargarPropuestasRevision(long idRevision) {
        return sql.query("""
                SELECT "idCliente","idPedido","importePropuesto"
                  FROM "PROPUESTA_APLICACION_PENDIENTE"
                 WHERE "idRevisionConciliacion" = :rev
                 ORDER BY "idPedido" ASC
                """, Map.of(PARAM_REV, idRevision), (rs, i) -> new PropuestaAplicacion(
                rs.getLong(COL_ID_CLIENTE),
                rs.getLong(COL_ID_PEDIDO),
                escala2(rs.getBigDecimal("importePropuesto"))));
    }

    private long resolverEventoCustodiaVigente(long idCierre, long idRev, EvidenciaEgresoCustodia egreso) {
        Integer reciboDup = sql.queryForObject("""
                SELECT count(*)
                  FROM "EGRESO_CUSTODIA_RENDICION"
                 WHERE "numeroReciboDevolucion" = :num
                """, Map.of("num", egreso.numeroReciboDevolucion()), Integer.class);
        if (reciboDup != null && reciboDup > 0) {
            throw new IllegalStateException("RECIBO_EGRESO_DUPLICADO");
        }
        var eventos = sql.queryForList("""
                SELECT "idEventoCustodia"
                  FROM "CUSTODIA_DIFERENCIA_RENDICION"
                 WHERE "idCierreRecorrido" = :cierre
                   AND "idRevisionConciliacion" = :rev
                   AND "tipoEvento" = 'CUSTODIA_NO_IMPUTADA'
                 ORDER BY "idEventoCustodia" ASC
                 LIMIT 1
                """, Map.of(PARAM_CIERRE, idCierre, PARAM_REV, idRev), Long.class);
        if (eventos.isEmpty()) {
            throw new IllegalStateException("EVENTO_CUSTODIA_NO_ENCONTRADO");
        }
        return eventos.getFirst();
    }

    private Map<Long, BigDecimal> bloquearClientesPedidosYPe1(List<PropuestaAplicacion> apps) {
        List<Long> clientesOrdenados = apps.stream()
                .map(PropuestaAplicacion::idCliente).distinct().sorted().toList();
        for (long idCliente : clientesOrdenados) {
            identificadores.bloquearClienteA2(idCliente);
        }
        for (long idCliente : clientesOrdenados) {
            sql.queryForObject(
                    "SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\" = :cliente FOR UPDATE",
                    Map.of(PARAM_CLIENTE, idCliente), Long.class);
        }
        Map<Long, BigDecimal> vvigPorPedido = LinkedHashMap.newLinkedHashMap(apps.size());
        for (PropuestaAplicacion app : apps) {
            sql.queryForObject(
                    "SELECT \"idPedido\" FROM \"PEDIDO\" WHERE \"idPedido\" = :pedido FOR UPDATE",
                    Map.of(PARAM_PEDIDO, app.idPedido()), Long.class);
            BigDecimal vvig = sql.queryForObject("""
                    SELECT "reconocimientoVigenteVvig"
                      FROM "RECONOCIMIENTO_ENTREGA_PE1"
                     WHERE "idPedido" = :pedido
                     FOR UPDATE
                    """, Map.of(PARAM_PEDIDO, app.idPedido()), BigDecimal.class);
            vvigPorPedido.put(app.idPedido(), vvig == null ? CERO : escala2(vvig));
        }
        return vvigPorPedido;
    }

    private static void validarCoberturaVvig(List<PropuestaAplicacion> apps, Map<Long, BigDecimal> vvigPorPedido) {
        for (PropuestaAplicacion app : apps) {
            BigDecimal disp = vvigPorPedido.getOrDefault(app.idPedido(), CERO);
            if (app.importePropuesto().compareTo(disp) > 0) {
                throw new CoberturaNoAcreditadaException(app.idPedido(), app.importePropuesto(), disp);
            }
        }
    }

    private List<PlanCobroCliente> planificarCobrosK2(List<PropuestaAplicacion> appsOrdenadas) {
        Map<Long, List<PropuestaAplicacion>> porCliente = new TreeMap<>();
        for (PropuestaAplicacion app : appsOrdenadas) {
            porCliente.computeIfAbsent(app.idCliente(), k -> new ArrayList<>()).add(app);
        }
        List<PlanCobroCliente> plan = new ArrayList<>(porCliente.size());
        for (var entry : porCliente.entrySet()) {
            BigDecimal totalCliente = entry.getValue().stream()
                    .map(PropuestaAplicacion::importePropuesto)
                    .reduce(CERO, BigDecimal::add);
            plan.add(new PlanCobroCliente(
                    entry.getKey(),
                    identificadores.siguienteCobro(),
                    identificadores.siguienteMovimientoCuenta(),
                    identificadores.siguienteMovimientoCaja(),
                    totalCliente,
                    List.copyOf(entry.getValue())));
        }
        return plan;
    }

    private ResultadoEfectosK2 ejecutarEfectosFinancierosK2(ContextoEfectosK2 ctx) {
        List<CobroK2Registrado> cobrosOut = new ArrayList<>(ctx.planClientes().size());
        List<AplicacionK2Registrada> appsOut = new ArrayList<>();
        for (PlanCobroCliente plan : ctx.planClientes()) {
            registrarCobroCuentaCajaK2(ctx, plan);
            cobrosOut.add(new CobroK2Registrado(
                    plan.idCliente(), plan.idCobro(), plan.importeCobro(),
                    plan.idMovimientoCuenta(), plan.idMovimientoCaja()));
            registrarAplicacionesClienteK2(ctx, plan, appsOut);
            evaluadorCategoria.reevaluar(plan.idCliente(), ctx.ahora());
        }
        return new ResultadoEfectosK2(List.copyOf(cobrosOut), List.copyOf(appsOut));
    }

    private void registrarCobroCuentaCajaK2(ContextoEfectosK2 ctx, PlanCobroCliente plan) {
        long seqCliente = Objects.requireNonNull(sql.queryForObject("""
                SELECT COALESCE(MAX("numeroSecuenciaCliente"), 0) + 1
                  FROM "MOVIMIENTO_CUENTA"
                 WHERE "idCliente" = :cliente
                """, Map.of(PARAM_CLIENTE, plan.idCliente()), Long.class));
        var saldos = sql.queryForList("""
                SELECT "saldoResultante"
                  FROM "MOVIMIENTO_CUENTA"
                 WHERE "idCliente" = :cliente
                 ORDER BY "numeroSecuenciaCliente" DESC
                 LIMIT 1
                """, Map.of(PARAM_CLIENTE, plan.idCliente()), BigDecimal.class);
        BigDecimal saldoPrevio = saldos.isEmpty() ? CERO : escala2(saldos.getFirst());
        BigDecimal saldoFinal = saldoPrevio.subtract(plan.importeCobro()).setScale(2, RoundingMode.UNNECESSARY);
        UUID claveCobroCliente = UUID.nameUUIDFromBytes(
                ("K2:" + ctx.claveCierre() + ":" + plan.idCliente()).getBytes(StandardCharsets.UTF_8));

        sql.update("""
                INSERT INTO "COBRO" (
                    "idCobro","idCliente","idPedido","idUsuarioRegistrador","fechaHoraCobro",
                    "importe","medioPago","origen","claveIdempotenciaOrigen","estadoCobro")
                VALUES (:cobro,:cliente,NULL,:admin,:ahora,:importe,'EFECTIVO','REPARTO',:clave,'CONFIRMADO')
                """, new MapSqlParameterSource()
                .addValue(PARAM_COBRO, plan.idCobro())
                .addValue(PARAM_CLIENTE, plan.idCliente())
                .addValue(PARAM_ADMIN, ctx.idAdmin())
                .addValue(PARAM_AHORA, ctx.ahora())
                .addValue(PARAM_IMPORTE, plan.importeCobro())
                .addValue(PARAM_CLAVE, claveCobroCliente));

        sql.update("""
                INSERT INTO "MOVIMIENTO_CUENTA" (
                    "idMovimientoCuenta","idCliente","numeroSecuenciaCliente","tipoMovimiento",
                    "importe","fechaHoraMovimiento","saldoResultante")
                VALUES (:mov,:cliente,:seq,'PAGO',:importe,:ahora,:saldo)
                """, new MapSqlParameterSource()
                .addValue("mov", plan.idMovimientoCuenta())
                .addValue(PARAM_CLIENTE, plan.idCliente())
                .addValue("seq", seqCliente)
                .addValue(PARAM_IMPORTE, plan.importeCobro())
                .addValue(PARAM_AHORA, ctx.ahora())
                .addValue("saldo", saldoFinal));

        sql.update("""
                INSERT INTO "ORIGEN_MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCobro")
                VALUES (:mov,:cobro)
                """, Map.of("mov", plan.idMovimientoCuenta(), PARAM_COBRO, plan.idCobro()));

        sql.update("""
                INSERT INTO "MOVIMIENTO_CAJA" (
                    "idMovimientoCaja","tipoMovimiento","importe","fechaHoraMovimiento",
                    "origen","concepto","idUsuarioRegistro","idCobro")
                VALUES (:caja,'INGRESO',:importe,:ahora,'COBRO','Rendicion reparto K2',:admin,:cobro)
                """, new MapSqlParameterSource()
                .addValue("caja", plan.idMovimientoCaja())
                .addValue(PARAM_IMPORTE, plan.importeCobro())
                .addValue(PARAM_AHORA, ctx.ahora())
                .addValue(PARAM_ADMIN, ctx.idAdmin())
                .addValue(PARAM_COBRO, plan.idCobro()));

        sql.update("""
                INSERT INTO "COBRO_RENDICION_K2" ("idCierreRecorrido","idCliente","idCobro")
                VALUES (:cierre,:cliente,:cobro)
                """, Map.of(PARAM_CIERRE, ctx.idCierre(), PARAM_CLIENTE, plan.idCliente(), PARAM_COBRO, plan.idCobro()));
    }

    private void registrarAplicacionesClienteK2(
            ContextoEfectosK2 ctx, PlanCobroCliente plan, List<AplicacionK2Registrada> appsOut) {
        for (PropuestaAplicacion app : plan.aplicaciones()) {
            sql.update("""
                    INSERT INTO "APLICACION_RENDICION_K2" (
                        "idCierreRecorrido","idCliente","idPedido","idCobro",
                        "importeAplicado","fechaHoraAplicacion")
                    VALUES (:cierre,:cliente,:pedido,:cobro,:importe,:ahora)
                    """, new MapSqlParameterSource()
                    .addValue(PARAM_CIERRE, ctx.idCierre())
                    .addValue(PARAM_CLIENTE, plan.idCliente())
                    .addValue(PARAM_PEDIDO, app.idPedido())
                    .addValue(PARAM_COBRO, plan.idCobro())
                    .addValue(PARAM_IMPORTE, app.importePropuesto())
                    .addValue(PARAM_AHORA, ctx.ahora()));

            long idEventoImp = identificadores.siguienteEventoImp();
            sql.update("""
                    INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                        "idEventoImputacion","idCliente","idCobro","idPedido","idCierreRecorrido",
                        "tipoEvento","importe","idUsuarioOperador","fechaHoraEvento","observacion")
                    VALUES (:ev,:cliente,:cobro,:pedido,:cierre,'APLICACION_RENDICION_K2',:importe,:admin,:ahora,'Aplicacion T1-C2/K2')
                    """, new MapSqlParameterSource()
                    .addValue("ev", idEventoImp)
                    .addValue(PARAM_CLIENTE, plan.idCliente())
                    .addValue(PARAM_COBRO, plan.idCobro())
                    .addValue(PARAM_PEDIDO, app.idPedido())
                    .addValue(PARAM_CIERRE, ctx.idCierre())
                    .addValue(PARAM_IMPORTE, app.importePropuesto())
                    .addValue(PARAM_ADMIN, ctx.idAdmin())
                    .addValue(PARAM_AHORA, ctx.ahora()));

            sql.update("""
                    UPDATE "RECONOCIMIENTO_ENTREGA_PE1"
                       SET "reconocimientoVigenteVvig" = "reconocimientoVigenteVvig" - :importe
                     WHERE "idPedido" = :pedido
                    """, Map.of(PARAM_IMPORTE, app.importePropuesto(), PARAM_PEDIDO, app.idPedido()));

            appsOut.add(new AplicacionK2Registrada(
                    plan.idCliente(), app.idPedido(), plan.idCobro(), app.importePropuesto()));
        }
    }

    private EgresoCustodiaRegistrado registrarEgresoCustodia(
            long idCierre,
            long idEventoCustodia,
            BigDecimal importeCustodia,
            EvidenciaEgresoCustodia egreso,
            long idAdmin,
            OffsetDateTime ahora) {
        long idEgreso = Objects.requireNonNull(sql.queryForObject("""
                INSERT INTO "EGRESO_CUSTODIA_RENDICION" (
                    "idEventoCustodia","idCierreRecorrido","tipoEgreso","importeEgreso",
                    "numeroReciboDevolucion","receptorIdentificacion","motivo",
                    "idUsuarioAdmin","fechaHoraEgreso")
                VALUES (:ev,:cierre,'DEVOLUCION_FISICA_CON_RECIBO',:importe,:recibo,:receptor,:motivo,:admin,:ahora)
                RETURNING "idEgresoCustodia"
                """, new MapSqlParameterSource()
                .addValue("ev", idEventoCustodia)
                .addValue(PARAM_CIERRE, idCierre)
                .addValue(PARAM_IMPORTE, importeCustodia)
                .addValue(PARAM_RECIBO, egreso.numeroReciboDevolucion())
                .addValue("receptor", egreso.receptorIdentificacion())
                .addValue("motivo", egreso.motivo())
                .addValue(PARAM_ADMIN, idAdmin)
                .addValue(PARAM_AHORA, ahora), Long.class));
        return new EgresoCustodiaRegistrado(
                idEgreso, idEventoCustodia, importeCustodia,
                egreso.numeroReciboDevolucion(), egreso.receptorIdentificacion());
    }

    private void cerrarRecorrido(long idRecorrido) {
        sql.update("""
                UPDATE "RECORRIDO_REPARTO"
                   SET "estadoOperativo" = 'CERRADO'
                 WHERE "idRecorrido" = :rec
                """, Map.of(PARAM_REC, idRecorrido));
    }

    private static String serializarRecibo(ContextoRecibo ctx) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION_HUELLA);
        root.put(COL_ID_RECORRIDO, ctx.idRecorrido());
        root.put(COL_ESTADO_CIERRE, ctx.estadoDestino());
        root.put("viaB1R1", ctx.viaB1R1());
        root.put("numeroRevisionConfirmada", ctx.numeroRevisionConfirmada());
        root.put(COL_TIPO_RES, ctx.tipoResolucionAdmin());
        root.put(COL_IMP_ESPERADO, ctx.esperado().toPlainString());
        root.put(COL_IMP_RECIBIDO, ctx.recibido().toPlainString());
        root.put(COL_IMP_CONFIRMADO, ctx.confirmado().toPlainString());
        root.put(COL_IMP_FALTANTE, ctx.faltante().toPlainString());
        root.put(COL_IMP_CUSTODIA, ctx.custodia().toPlainString());

        List<Map<String, Object>> cobrosJson = new ArrayList<>(ctx.planClientes().size());
        List<Map<String, Object>> appsJson = new ArrayList<>();
        for (PlanCobroCliente p : ctx.planClientes()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put(COL_ID_CLIENTE, p.idCliente());
            c.put(COL_ID_COBRO, p.idCobro());
            c.put(COL_IMPORTE, p.importeCobro().toPlainString());
            c.put(COL_MOV_CUENTA, p.idMovimientoCuenta());
            c.put(COL_MOV_CAJA, p.idMovimientoCaja());
            cobrosJson.add(c);
            for (PropuestaAplicacion a : p.aplicaciones()) {
                Map<String, Object> ap = new LinkedHashMap<>();
                ap.put(COL_ID_CLIENTE, a.idCliente());
                ap.put(COL_ID_PEDIDO, a.idPedido());
                ap.put(COL_ID_COBRO, p.idCobro());
                ap.put(COL_IMP_APLICADO, a.importePropuesto().toPlainString());
                appsJson.add(ap);
            }
        }
        root.put("cobros", cobrosJson);
        root.put("aplicaciones", appsJson);

        if (ctx.idEventoCustodia() != null && ctx.egreso() != null) {
            Map<String, Object> eg = new LinkedHashMap<>();
            eg.put(COL_EVENTO_CUST, ctx.idEventoCustodia());
            eg.put(COL_IMP_EGRESO, ctx.custodia().toPlainString());
            eg.put(COL_RECIBO_DEV, ctx.egreso().numeroReciboDevolucion());
            eg.put(COL_RECEPTOR, ctx.egreso().receptorIdentificacion());
            root.put("egresoCustodia", eg);
        } else {
            root.put("egresoCustodia", null);
        }
        return JSON.writeValueAsString(root);
    }

    private static BigDecimal escala2(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }
}
