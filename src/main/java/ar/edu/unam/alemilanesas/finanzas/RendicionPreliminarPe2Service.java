package ar.edu.unam.alemilanesas.finanzas;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * R09.3b-A — Subflujo preliminar de rendicion administrativa PE-2 (UC-29, exclusivo ADMINISTRADOR):
 * - Revalida ADMINISTRADOR habilitado antes y despues de adquirir lock A2(NS_RECORRIDO).
 * - Exige Recorrido explicito (A1/C1-EXPLICITO), FA-2 (cero Pedidos PENDIENTE activos)
 *   y Entregas con reconocimiento PE-1 consistente.
 * - Calcula exposicion esperada (SUM V_e^vig), imputacion propuesta, faltante y custodia no imputada.
 * - Exige discrepancia positiva real al iniciar PENDIENTE_CONCILIACION (prohibido 0/0 inicial, B1-R1).
 * - Persiste cabecera unica CIERRE_RECORRIDO_PE2, revision inicial append-only REVISION_CONCILIACION_PE2
 *   (numeroRevision=1, claveIdempotenciaRevision + huellaCanonica SHA-256), PROPUESTA_APLICACION_PENDIENTE,
 *   evidencia CUSTODIA_DIFERENCIA_RENDICION y transiciona RECORRIDO_REPARTO a PENDIENTE_CONCILIACION.
 * - Cero efectos financieros definitivos: 0 COBRO, 0 MOVIMIENTO_CUENTA(PAGO), 0 MOVIMIENTO_CAJA,
 *   0 APLICACION_RENDICION_K2, 0 extincion de V_e^vig y sin cierre terminal CERRADO.
 */
@Service
public class RendicionPreliminarPe2Service {
    private static final String ADMINISTRADOR = "ADMINISTRADOR";
    private static final String VERSION_HUELLA = "v1";
    private static final String UBICACION_DEFAULT = "CAJA_CUSTODIA_ADMIN";
    private static final String ESTADO_PENDIENTE_CONCILIACION = "PENDIENTE_CONCILIACION";
    private static final String INVALIDA = "SOLICITUD_INVALIDA";
    private static final String COL_ESTADO_CIERRE = "estadoCierre";
    private static final String COL_ID_PEDIDO = "idPedido";
    private static final String COL_ID_CLIENTE = "idCliente";
    private static final String PARAM_CLAVE = "clave";
    private static final String PARAM_CIERRE = "cierre";
    private static final String PARAM_IMPORTE = "importe";
    private static final String PARAM_ADMIN = "admin";
    private static final String PARAM_AHORA = "ahora";
    private static final String PARAM_MOTIVO = "motivo";
    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    private static final int MAX_MOTIVO = 1000;
    private static final int MAX_UBICACION = 80;

    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    private final ProtocoloIdentificadoresFinancieros identificadores;

    public RendicionPreliminarPe2Service(
            NamedParameterJdbcTemplate sql,
            AutorizacionSesionInterna autorizacion,
            ProtocoloIdentificadoresFinancieros identificadores) {
        this.sql = sql;
        this.autorizacion = autorizacion;
        this.identificadores = identificadores;
    }

    /**
     * Error de cobertura insuficiente (422 COBERTURA_NO_ACREDITADA) cuando una propuesta
     * supera el reconocimiento vigente V_e^vig de la Entrega.
     */
    public static final class CoberturaNoAcreditadaException extends IllegalStateException {
        private final long idPedido;
        private final BigDecimal solicitado;
        private final BigDecimal disponible;

        public CoberturaNoAcreditadaException(long idPedido, BigDecimal solicitado, BigDecimal disponible) {
            super("COBERTURA_NO_ACREDITADA");
            this.idPedido = idPedido;
            this.solicitado = solicitado;
            this.disponible = disponible;
        }

        public long getIdPedido() {
            return idPedido;
        }

        public BigDecimal getSolicitado() {
            return solicitado;
        }

        public BigDecimal getDisponible() {
            return disponible;
        }
    }

    public record PropuestaAplicacion(long idCliente, long idPedido, BigDecimal importePropuesto) {}

    public record DatosConciliacionPreliminar(
            long idRecorrido,
            UUID claveIdempotenciaPropuesta,
            BigDecimal importeEfectivoRecibido,
            String motivoDiferencia,
            String ubicacionFisica,
            List<PropuestaAplicacion> propuestas) {

        public DatosConciliacionPreliminar(
                long idRecorrido,
                UUID claveIdempotenciaPropuesta,
                BigDecimal importeEfectivoRecibido,
                String motivoDiferencia,
                List<PropuestaAplicacion> propuestas) {
            this(idRecorrido, claveIdempotenciaPropuesta, importeEfectivoRecibido,
                    motivoDiferencia, null, propuestas);
        }
    }

    public record EventoCustodiaRegistrado(
            long idEventoCustodia,
            String tipoEvento,
            Long idPedido,
            Long idCliente,
            BigDecimal importe,
            String ubicacionFisica) {}

    public record ConciliacionPreliminarRegistrada(
            long idCierreRecorrido,
            long idRevisionConciliacion,
            int numeroRevision,
            long idRecorrido,
            long idUsuarioRepartidor,
            long idUsuarioAdministrador,
            String estadoCierre,
            String estadoRevision,
            String estadoRecorrido,
            UUID claveIdempotenciaPropuesta,
            String versionHuella,
            String huellaCanonica,
            BigDecimal importeEsperado,
            BigDecimal importeDeclaradoTotal,
            BigDecimal importeEfectivoRecibido,
            BigDecimal importeImputadoPropuesto,
            BigDecimal importeImputadoConfirmado,
            BigDecimal importeDiferenciaFaltante,
            BigDecimal importeCustodiaNoImputada,
            String motivoDiferencia,
            OffsetDateTime fechaHoraRegistro,
            List<PropuestaAplicacion> propuestas,
            List<EventoCustodiaRegistrado> eventosCustodia,
            boolean creado) {}

    public record EntregaExposicion(
            long idPedido,
            long idCliente,
            BigDecimal cargoRealCe,
            BigDecimal coberturaTotalKe,
            BigDecimal exigibleEe,
            BigDecimal declaradoRepartidorDe,
            BigDecimal reconocimientoInicialV0,
            BigDecimal reconocimientoVigenteVvig,
            BigDecimal sobranteDeclaradoEx01,
            String modoEvidencia) {}

    public record ExposicionRecorrido(
            long idRecorrido,
            long idUsuarioRepartidor,
            String estadoOperativo,
            int pedidosPendientesActivos,
            BigDecimal importeEsperado,
            BigDecimal importeDeclaradoTotal,
            BigDecimal sobranteDeclaradoTotalEx01,
            Long idCierreExistente,
            String estadoCierreExistente,
            List<EntregaExposicion> entregas) {}

    private record EntradaNormalizada(
            long idRecorrido,
            UUID claveIdempotencia,
            BigDecimal efectivoRecibido,
            String motivo,
            String ubicacion,
            List<PropuestaAplicacion> propuestasOrdenadas) {}

    private record RecorridoRow(long idUsuarioRepartidor, String estadoOperativo) {}

    private record EntregaPe1(
            long idPedido,
            long idCliente,
            long idUsuarioRepartidorEntrega,
            BigDecimal cargoRealCe,
            BigDecimal coberturaTotalKe,
            BigDecimal exigibleEe,
            BigDecimal declaradoRepartidorDe,
            BigDecimal reconocimientoInicialV0,
            BigDecimal reconocimientoVigenteVvig,
            String modoEvidencia) {

        BigDecimal sobranteEx01() {
            if ("IMP_B".equals(modoEvidencia) && declaradoRepartidorDe.compareTo(reconocimientoInicialV0) > 0) {
                return declaradoRepartidorDe.subtract(reconocimientoInicialV0).setScale(2, RoundingMode.UNNECESSARY);
            }
            return CERO;
        }
    }

    private record TotalesConciliacion(
            BigDecimal importeEsperado,
            BigDecimal importeDeclaradoTotal,
            BigDecimal importeImputadoPropuesto,
            BigDecimal importeDiferenciaFaltante,
            BigDecimal importeCustodiaNoImputada) {}

    private record RevisionInicialRow(
            long idRevisionConciliacion,
            int numeroRevision,
            String estadoRevision,
            UUID claveIdempotenciaRevision,
            String versionHuella,
            String huellaCanonica,
            long idUsuarioAdmin,
            OffsetDateTime fechaHoraRevision,
            BigDecimal importeEsperado,
            BigDecimal importeEfectivoRecibido,
            BigDecimal importeImputadoPropuesto,
            BigDecimal importeDiferenciaFaltante,
            BigDecimal importeCustodiaNoImputada,
            String motivoRevision,
            String estadoCierre,
            BigDecimal importeImputadoConfirmado,
            String estadoOperativo) {}

    private record ContextoCustodia(
            long idCierre,
            long idRev,
            long idAdmin,
            String ubicacion,
            String motivo,
            OffsetDateTime ahora) {}

    /**
     * Proyeccion contextual UC-29 de solo lectura para ADMINISTRADOR habilitado (BC-07 D9).
     * No accede a saldos generales UC-14 ni muta estado.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExposicionRecorrido consultarExposicionRecorrido(UUID sesion, long idRecorrido) {
        autorizacion.exigirRoles(sesion, Set.of(ADMINISTRADOR));
        if (idRecorrido <= 0) {
            throw new IllegalArgumentException(INVALIDA);
        }

        RecorridoRow recorrido = cargarRecorrido(idRecorrido, false);
        int pendientes = contarPendientesActivos(idRecorrido);
        List<EntregaPe1> entregas = cargarEntregasPe1(idRecorrido, recorrido.idUsuarioRepartidor(), false);

        BigDecimal esperado = CERO;
        BigDecimal declarado = CERO;
        BigDecimal sobranteEx01Total = CERO;
        List<EntregaExposicion> detalle = new ArrayList<>(entregas.size());
        for (EntregaPe1 e : entregas) {
            BigDecimal sobrante = e.sobranteEx01();
            esperado = esperado.add(e.reconocimientoVigenteVvig());
            declarado = declarado.add(e.declaradoRepartidorDe());
            sobranteEx01Total = sobranteEx01Total.add(sobrante);
            detalle.add(new EntregaExposicion(
                    e.idPedido(), e.idCliente(), e.cargoRealCe(), e.coberturaTotalKe(), e.exigibleEe(),
                    e.declaradoRepartidorDe(), e.reconocimientoInicialV0(), e.reconocimientoVigenteVvig(),
                    sobrante, e.modoEvidencia()));
        }

        var cierres = sql.queryForList("""
                SELECT "idCierreRecorrido","estadoCierre"
                  FROM "CIERRE_RECORRIDO_PE2"
                 WHERE "idRecorrido" = :rec
                """, Map.of("rec", idRecorrido));
        Long idCierre = cierres.isEmpty() ? null : ((Number) cierres.getFirst().get("idCierreRecorrido")).longValue();
        String estadoCierre = cierres.isEmpty() ? null : (String) cierres.getFirst().get(COL_ESTADO_CIERRE);

        return new ExposicionRecorrido(
                idRecorrido,
                recorrido.idUsuarioRepartidor(),
                recorrido.estadoOperativo(),
                pendientes,
                esperado,
                declarado,
                sobranteEx01Total,
                idCierre,
                estadoCierre,
                List.copyOf(detalle));
    }

    /**
     * Registra la rendicion administrativa preliminar PE-2 en PENDIENTE_CONCILIACION (revision 1),
     * con idempotencia verificada por clave + huella canonica y cero efectos financieros definitivos.
     */
    @Transactional
    public ConciliacionPreliminarRegistrada registrarConciliacionPreliminar(
            UUID sesion,
            DatosConciliacionPreliminar datos) {
        autorizacion.exigirRoles(sesion, Set.of(ADMINISTRADOR));
        EntradaNormalizada entrada = validarYNormalizarEntrada(datos);
        String huellaCanonica = construirHuellaCanonica(entrada);

        // Lock A2 por Recorrido antes de leer/decidir replay o insertar (G-ARQ-N2-02)
        identificadores.bloquearRecorridoA2(entrada.idRecorrido());
        autorizacion.exigirRoles(sesion, Set.of(ADMINISTRADOR));
        long idAdmin = resolverIdUsuarioSesion(sesion);

        RecorridoRow recorrido = cargarRecorrido(entrada.idRecorrido(), true);

        // Verificar idempotencia, conflicto de ambito/payload y unicidad de cierre por Recorrido
        ConciliacionPreliminarRegistrada replay = verificarExistenteOIdempotencia(
                entrada, huellaCanonica, recorrido.idUsuarioRepartidor());
        if (replay != null) {
            return replay;
        }

        if (!"ABIERTO".equals(recorrido.estadoOperativo())) {
            throw new IllegalStateException("RECORRIDO_NO_ABIERTO");
        }

        // FA-2: prohibido iniciar conciliacion con Pedidos PENDIENTE activos
        if (contarPendientesActivos(entrada.idRecorrido()) > 0) {
            throw new IllegalStateException("FA2_PEDIDOS_PENDIENTES_ACTIVOS");
        }

        verificarIntegridadAsignacionesEntregadas(entrada.idRecorrido());
        List<EntregaPe1> entregas = cargarEntregasPe1(
                entrada.idRecorrido(), recorrido.idUsuarioRepartidor(), true);
        if (entregas.isEmpty()) {
            throw new IllegalStateException("RECORRIDO_SIN_ENTREGAS");
        }

        TotalesConciliacion totales = validarPropuestasYCalcularTotales(entrada, entregas);
        if (totales.importeDiferenciaFaltante().signum() == 0
                && totales.importeCustodiaNoImputada().signum() == 0) {
            throw new IllegalStateException("DIFERENCIA_POSITIVA_REQUERIDA");
        }

        return persistirConciliacionInicial(
                entrada, huellaCanonica, idAdmin, recorrido.idUsuarioRepartidor(), entregas, totales);
    }

    private EntradaNormalizada validarYNormalizarEntrada(DatosConciliacionPreliminar datos) {
        if (datos == null || datos.idRecorrido() <= 0 || datos.claveIdempotenciaPropuesta() == null
                || datos.propuestas() == null) {
            throw new IllegalArgumentException(INVALIDA);
        }
        BigDecimal recibido = normalizarImporte(datos.importeEfectivoRecibido(), true);
        String motivo = normalizarTexto(datos.motivoDiferencia(), MAX_MOTIVO);
        String ubicacion = datos.ubicacionFisica() == null
                ? UBICACION_DEFAULT
                : normalizarTexto(datos.ubicacionFisica(), MAX_UBICACION);

        Set<Long> pedidosVistos = new HashSet<>();
        List<PropuestaAplicacion> normalizadas = new ArrayList<>(datos.propuestas().size());
        for (PropuestaAplicacion p : datos.propuestas()) {
            if (p == null || p.idCliente() <= 0 || p.idPedido() <= 0) {
                throw new IllegalArgumentException(INVALIDA);
            }
            if (!pedidosVistos.add(p.idPedido())) {
                throw new IllegalArgumentException("PROPUESTA_PEDIDO_DUPLICADO");
            }
            BigDecimal importe = normalizarImporte(p.importePropuesto(), false);
            normalizadas.add(new PropuestaAplicacion(p.idCliente(), p.idPedido(), importe));
        }
        normalizadas.sort(Comparator.comparingLong(PropuestaAplicacion::idPedido));

        return new EntradaNormalizada(
                datos.idRecorrido(),
                datos.claveIdempotenciaPropuesta(),
                recibido,
                motivo,
                ubicacion,
                List.copyOf(normalizadas));
    }

    private static BigDecimal normalizarImporte(BigDecimal valor, boolean permitirCero) {
        if (valor == null) {
            throw new IllegalArgumentException(INVALIDA);
        }
        BigDecimal reducido = valor.stripTrailingZeros();
        if (reducido.scale() > 2) {
            throw new IllegalArgumentException(INVALIDA);
        }
        BigDecimal normalizado = valor.setScale(2, RoundingMode.UNNECESSARY);
        int cmp = normalizado.compareTo(CERO);
        if (cmp < 0 || (!permitirCero && cmp == 0)) {
            throw new IllegalArgumentException(INVALIDA);
        }
        return normalizado;
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

    private static String construirHuellaCanonica(EntradaNormalizada entrada) {
        String propuestasCanon = entrada.propuestasOrdenadas().stream()
                .map(p -> p.idCliente() + ":" + p.idPedido() + ":" + p.importePropuesto().toPlainString())
                .collect(Collectors.joining(";"));
        String payload = "PE2_PRELIM:" + VERSION_HUELLA
                + "|" + entrada.idRecorrido()
                + "|" + entrada.efectivoRecibido().toPlainString()
                + "|" + entrada.motivo()
                + "|" + entrada.ubicacion()
                + "|" + propuestasCanon;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private long resolverIdUsuarioSesion(UUID sesion) {
        return Objects.requireNonNull(sql.queryForObject(
                "SELECT \"idUsuario\" FROM \"SESION_USUARIO\" WHERE \"idSesionUsuario\" = :s",
                Map.of("s", sesion), Long.class));
    }

    private RecorridoRow cargarRecorrido(long idRecorrido, boolean forUpdate) {
        String consulta = forUpdate
                ? "SELECT \"idUsuarioRepartidor\",\"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\" = :rec FOR UPDATE"
                : "SELECT \"idUsuarioRepartidor\",\"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\" = :rec";
        var filas = sql.queryForList(consulta, Map.of("rec", idRecorrido));
        if (filas.isEmpty()) {
            throw new IllegalArgumentException("RECORRIDO_NO_ENCONTRADO");
        }
        var row = filas.getFirst();
        return new RecorridoRow(
                ((Number) row.get("idUsuarioRepartidor")).longValue(),
                (String) row.get("estadoOperativo"));
    }

    private int contarPendientesActivos(long idRecorrido) {
        Integer count = sql.queryForObject("""
                SELECT count(*)
                  FROM "RECORRIDO_ASIGNACION"
                 WHERE "idRecorrido" = :rec
                   AND "estadoPertenencia" = 'ACTIVA'
                """, Map.of("rec", idRecorrido), Integer.class);
        return count == null ? 0 : count;
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
                """, Map.of("rec", idRecorrido), Integer.class);
        if (huerfanas != null && huerfanas > 0) {
            throw new IllegalStateException("ENTREGA_INCONSISTENTE_EN_RECORRIDO");
        }
    }

    private List<EntregaPe1> cargarEntregasPe1(long idRecorrido, long idRepartidorRecorrido, boolean bloquearPe1) {
        Integer sinPe1 = sql.queryForObject("""
                SELECT count(*)
                  FROM "RECORRIDO_ENTREGA" re
                 WHERE re."idRecorrido" = :rec
                   AND NOT EXISTS (
                       SELECT 1 FROM "RECONOCIMIENTO_ENTREGA_PE1" pe1
                        WHERE pe1."idPedido" = re."idPedido"
                   )
                """, Map.of("rec", idRecorrido), Integer.class);
        if (sinPe1 != null && sinPe1 > 0) {
            throw new IllegalStateException("PE1_FALTANTE_EN_RECORRIDO");
        }

        String consulta = """
                SELECT re."idPedido",
                       e."idUsuarioRepartidor" AS "idRepEntrega",
                       pe1."idCliente",
                       pe1."cargoRealCe",
                       pe1."coberturaTotalKe",
                       pe1."exigibleEe",
                       pe1."declaradoRepartidorDe",
                       pe1."reconocimientoInicialV0",
                       pe1."reconocimientoVigenteVvig",
                       pe1."modoEvidencia"
                  FROM "RECORRIDO_ENTREGA" re
                  JOIN "ENTREGA" e ON e."idPedido" = re."idPedido"
                  JOIN "RECONOCIMIENTO_ENTREGA_PE1" pe1 ON pe1."idPedido" = re."idPedido"
                 WHERE re."idRecorrido" = :rec
                 ORDER BY re."idPedido" ASC
                """ + (bloquearPe1 ? " FOR SHARE OF pe1" : "");

        var filas = sql.queryForList(consulta, Map.of("rec", idRecorrido));
        List<EntregaPe1> resultado = new ArrayList<>(filas.size());
        for (var row : filas) {
            long idRepEntrega = ((Number) row.get("idRepEntrega")).longValue();
            if (idRepEntrega != idRepartidorRecorrido) {
                throw new IllegalStateException("REPARTIDOR_ENTREGA_INCONSISTENTE");
            }
            resultado.add(new EntregaPe1(
                    ((Number) row.get(COL_ID_PEDIDO)).longValue(),
                    ((Number) row.get(COL_ID_CLIENTE)).longValue(),
                    idRepEntrega,
                    escala2((BigDecimal) row.get("cargoRealCe")),
                    escala2((BigDecimal) row.get("coberturaTotalKe")),
                    escala2((BigDecimal) row.get("exigibleEe")),
                    escala2((BigDecimal) row.get("declaradoRepartidorDe")),
                    escala2((BigDecimal) row.get("reconocimientoInicialV0")),
                    escala2((BigDecimal) row.get("reconocimientoVigenteVvig")),
                    (String) row.get("modoEvidencia")));
        }
        return resultado;
    }

    private ConciliacionPreliminarRegistrada verificarExistenteOIdempotencia(
            EntradaNormalizada entrada,
            String huellaCanonica,
            long idRepartidor) {
        var porClaveCierre = sql.queryForList("""
                SELECT "idCierreRecorrido","idRecorrido"
                  FROM "CIERRE_RECORRIDO_PE2"
                 WHERE "claveIdempotenciaPropuesta" = :clave
                """, Map.of(PARAM_CLAVE, entrada.claveIdempotencia()));
        if (!porClaveCierre.isEmpty()) {
            long recExistente = ((Number) porClaveCierre.getFirst().get("idRecorrido")).longValue();
            if (recExistente != entrada.idRecorrido()) {
                throw new IllegalStateException("CLAVE_IDEMPOTENCIA_CONFLICTO_AMBITO");
            }
        }

        var porClaveRev = sql.queryForList("""
                SELECT r."idCierreRecorrido", r."numeroRevision", c."idRecorrido"
                  FROM "REVISION_CONCILIACION_PE2" r
                  JOIN "CIERRE_RECORRIDO_PE2" c ON c."idCierreRecorrido" = r."idCierreRecorrido"
                 WHERE r."claveIdempotenciaRevision" = :clave
                """, Map.of(PARAM_CLAVE, entrada.claveIdempotencia()));
        if (!porClaveRev.isEmpty()) {
            var revRow = porClaveRev.getFirst();
            long recRev = ((Number) revRow.get("idRecorrido")).longValue();
            int numRev = ((Number) revRow.get("numeroRevision")).intValue();
            if (recRev != entrada.idRecorrido() || numRev != 1) {
                throw new IllegalStateException("CLAVE_IDEMPOTENCIA_CONFLICTO_AMBITO");
            }
        }

        var cierres = sql.queryForList("""
                SELECT "idCierreRecorrido","estadoCierre","claveIdempotenciaPropuesta"
                  FROM "CIERRE_RECORRIDO_PE2"
                 WHERE "idRecorrido" = :rec
                 FOR UPDATE
                """, Map.of("rec", entrada.idRecorrido()));
        if (cierres.isEmpty()) {
            return null;
        }

        var cabecera = cierres.getFirst();
        long idCierre = ((Number) cabecera.get("idCierreRecorrido")).longValue();
        String estadoCierre = (String) cabecera.get(COL_ESTADO_CIERRE);
        UUID claveExistente = (UUID) cabecera.get("claveIdempotenciaPropuesta");

        if (entrada.claveIdempotencia().equals(claveExistente)) {
            return cargarYValidarReplay(idCierre, entrada.idRecorrido(), idRepartidor, huellaCanonica);
        }
        if (ESTADO_PENDIENTE_CONCILIACION.equals(estadoCierre)) {
            throw new IllegalStateException("CONCILIACION_YA_EXISTENTE");
        }
        throw new IllegalStateException("RECORRIDO_YA_CERRADO");
    }

    private ConciliacionPreliminarRegistrada cargarYValidarReplay(
            long idCierre,
            long idRecorrido,
            long idRepartidor,
            String huellaEsperada) {
        List<RevisionInicialRow> revRows = sql.query("""
                SELECT r."idRevisionConciliacion", r."numeroRevision", r."estadoRevision",
                       r."claveIdempotenciaRevision", r."versionHuella", r."huellaCanonica",
                       r."idUsuarioAdmin", r."fechaHoraRevision",
                       r."importeEsperado", r."importeEfectivoRecibido",
                       r."importeImputadoPropuesto", r."importeDiferenciaFaltante",
                       r."importeCustodiaNoImputada", r."motivoRevision",
                       c."estadoCierre", c."importeImputadoConfirmado",
                       rec."estadoOperativo"
                  FROM "REVISION_CONCILIACION_PE2" r
                  JOIN "CIERRE_RECORRIDO_PE2" c ON c."idCierreRecorrido" = r."idCierreRecorrido"
                  JOIN "RECORRIDO_REPARTO" rec ON rec."idRecorrido" = c."idRecorrido"
                 WHERE r."idCierreRecorrido" = :cierre
                   AND r."numeroRevision" = 1
                """, Map.of(PARAM_CIERRE, idCierre), (rs, i) -> new RevisionInicialRow(
                rs.getLong("idRevisionConciliacion"),
                rs.getInt("numeroRevision"),
                rs.getString("estadoRevision"),
                rs.getObject("claveIdempotenciaRevision", UUID.class),
                rs.getString("versionHuella"),
                rs.getString("huellaCanonica"),
                rs.getLong("idUsuarioAdmin"),
                rs.getObject("fechaHoraRevision", OffsetDateTime.class),
                escala2(rs.getBigDecimal("importeEsperado")),
                escala2(rs.getBigDecimal("importeEfectivoRecibido")),
                escala2(rs.getBigDecimal("importeImputadoPropuesto")),
                escala2(rs.getBigDecimal("importeDiferenciaFaltante")),
                escala2(rs.getBigDecimal("importeCustodiaNoImputada")),
                rs.getString("motivoRevision"),
                rs.getString(COL_ESTADO_CIERRE),
                escala2(rs.getBigDecimal("importeImputadoConfirmado")),
                rs.getString("estadoOperativo")));
        if (revRows.isEmpty()) {
            throw new IllegalStateException("REVISION_INICIAL_INCONSISTENTE");
        }
        RevisionInicialRow row = revRows.getFirst();
        if (!VERSION_HUELLA.equals(row.versionHuella())
                || !huellaEsperada.equals(row.huellaCanonica())) {
            throw new IllegalStateException("CLAVE_IDEMPOTENCIA_CONFLICTO_PAYLOAD");
        }

        long idRev = row.idRevisionConciliacion();
        List<PropuestaAplicacion> propuestas = sql.query("""
                SELECT "idCliente","idPedido","importePropuesto"
                  FROM "PROPUESTA_APLICACION_PENDIENTE"
                 WHERE "idRevisionConciliacion" = :rev
                 ORDER BY "idPedido" ASC
                """, Map.of("rev", idRev), (rs, i) -> new PropuestaAplicacion(
                rs.getLong(COL_ID_CLIENTE),
                rs.getLong(COL_ID_PEDIDO),
                escala2(rs.getBigDecimal("importePropuesto"))));

        List<EventoCustodiaRegistrado> eventos = sql.query("""
                SELECT "idEventoCustodia","tipoEvento","idPedido","idCliente","importe","ubicacionFisica"
                  FROM "CUSTODIA_DIFERENCIA_RENDICION"
                 WHERE "idRevisionConciliacion" = :rev
                 ORDER BY "idEventoCustodia" ASC
                """, Map.of("rev", idRev), (rs, i) -> new EventoCustodiaRegistrado(
                rs.getLong("idEventoCustodia"),
                rs.getString("tipoEvento"),
                rs.getObject(COL_ID_PEDIDO, Long.class),
                rs.getObject(COL_ID_CLIENTE, Long.class),
                escala2(rs.getBigDecimal(PARAM_IMPORTE)),
                rs.getString("ubicacionFisica")));

        BigDecimal declaradoTotal = Objects.requireNonNull(sql.queryForObject("""
                SELECT COALESCE(SUM(pe1."declaradoRepartidorDe"), 0)::NUMERIC(18,2)
                  FROM "RECORRIDO_ENTREGA" re
                  JOIN "RECONOCIMIENTO_ENTREGA_PE1" pe1 ON pe1."idPedido" = re."idPedido"
                 WHERE re."idRecorrido" = :rec
                """, Map.of("rec", idRecorrido), BigDecimal.class));

        return new ConciliacionPreliminarRegistrada(
                idCierre,
                idRev,
                row.numeroRevision(),
                idRecorrido,
                idRepartidor,
                row.idUsuarioAdmin(),
                row.estadoCierre(),
                row.estadoRevision(),
                row.estadoOperativo(),
                row.claveIdempotenciaRevision(),
                row.versionHuella(),
                row.huellaCanonica(),
                row.importeEsperado(),
                escala2(declaradoTotal),
                row.importeEfectivoRecibido(),
                row.importeImputadoPropuesto(),
                row.importeImputadoConfirmado(),
                row.importeDiferenciaFaltante(),
                row.importeCustodiaNoImputada(),
                row.motivoRevision(),
                row.fechaHoraRevision(),
                List.copyOf(propuestas),
                List.copyOf(eventos),
                false);
    }

    private TotalesConciliacion validarPropuestasYCalcularTotales(
            EntradaNormalizada entrada,
            List<EntregaPe1> entregas) {
        Map<Long, EntregaPe1> porPedido = HashMap.newHashMap(entregas.size());
        BigDecimal esperado = CERO;
        BigDecimal declaradoTotal = CERO;
        for (EntregaPe1 e : entregas) {
            porPedido.put(e.idPedido(), e);
            esperado = esperado.add(e.reconocimientoVigenteVvig());
            declaradoTotal = declaradoTotal.add(e.declaradoRepartidorDe());
        }

        BigDecimal totalPropuesto = CERO;
        for (PropuestaAplicacion p : entrada.propuestasOrdenadas()) {
            EntregaPe1 entrega = porPedido.get(p.idPedido());
            if (entrega == null) {
                throw new IllegalArgumentException("ENTREGA_NO_PERTENECE_A_RECORRIDO");
            }
            if (entrega.idCliente() != p.idCliente()) {
                throw new IllegalArgumentException("CLIENTE_NO_COINCIDE_CON_ENTREGA");
            }
            if (p.importePropuesto().compareTo(entrega.reconocimientoVigenteVvig()) > 0) {
                throw new CoberturaNoAcreditadaException(
                        p.idPedido(), p.importePropuesto(), entrega.reconocimientoVigenteVvig());
            }
            totalPropuesto = totalPropuesto.add(p.importePropuesto());
        }

        if (totalPropuesto.compareTo(entrada.efectivoRecibido()) > 0) {
            throw new IllegalArgumentException("IMPUTACION_EXCEDE_EFECTIVO_RECIBIDO");
        }

        BigDecimal custodia = entrada.efectivoRecibido().subtract(totalPropuesto)
                .setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal faltante = esperado.subtract(totalPropuesto)
                .setScale(2, RoundingMode.UNNECESSARY);

        return new TotalesConciliacion(esperado, declaradoTotal, totalPropuesto, faltante, custodia);
    }

    private ConciliacionPreliminarRegistrada persistirConciliacionInicial(
            EntradaNormalizada entrada,
            String huellaCanonica,
            long idAdmin,
            long idRepartidor,
            List<EntregaPe1> entregas,
            TotalesConciliacion totales) {
        OffsetDateTime ahora = Objects.requireNonNull(
                sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", OffsetDateTime.class));

        long idCierre = Objects.requireNonNull(sql.queryForObject("""
                INSERT INTO "CIERRE_RECORRIDO_PE2" (
                    "idRecorrido","claveIdempotenciaPropuesta",
                    "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                    "importeImputadoConfirmado","importeDiferenciaFaltante","importeCustodiaNoImputada",
                    "motivoDiferencia")
                VALUES (
                    :rec,:clave,:admin,:ahora,'PENDIENTE_CONCILIACION','EFECTIVO',
                    :esperado,:recibido,:propuesto,0,:faltante,:custodia,:motivo)
                RETURNING "idCierreRecorrido"
                """, new MapSqlParameterSource()
                .addValue("rec", entrada.idRecorrido())
                .addValue(PARAM_CLAVE, entrada.claveIdempotencia())
                .addValue(PARAM_ADMIN, idAdmin)
                .addValue(PARAM_AHORA, ahora)
                .addValue("esperado", totales.importeEsperado())
                .addValue("recibido", entrada.efectivoRecibido())
                .addValue("propuesto", totales.importeImputadoPropuesto())
                .addValue("faltante", totales.importeDiferenciaFaltante())
                .addValue("custodia", totales.importeCustodiaNoImputada())
                .addValue(PARAM_MOTIVO, entrada.motivo()), Long.class));

        long idRev = Objects.requireNonNull(sql.queryForObject("""
                INSERT INTO "REVISION_CONCILIACION_PE2" (
                    "idCierreRecorrido","numeroRevision","idRevisionAnterior","estadoRevision",
                    "claveIdempotenciaRevision","versionHuella","huellaCanonica",
                    "idUsuarioAdmin","fechaHoraRevision",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                    "importeDiferenciaFaltante","importeCustodiaNoImputada","motivoRevision")
                VALUES (
                    :cierre,1,NULL,'VIGENTE',:clave,:ver,:huella,:admin,:ahora,
                    :esperado,:recibido,:propuesto,:faltante,:custodia,:motivo)
                RETURNING "idRevisionConciliacion"
                """, new MapSqlParameterSource()
                .addValue(PARAM_CIERRE, idCierre)
                .addValue(PARAM_CLAVE, entrada.claveIdempotencia())
                .addValue("ver", VERSION_HUELLA)
                .addValue("huella", huellaCanonica)
                .addValue(PARAM_ADMIN, idAdmin)
                .addValue(PARAM_AHORA, ahora)
                .addValue("esperado", totales.importeEsperado())
                .addValue("recibido", entrada.efectivoRecibido())
                .addValue("propuesto", totales.importeImputadoPropuesto())
                .addValue("faltante", totales.importeDiferenciaFaltante())
                .addValue("custodia", totales.importeCustodiaNoImputada())
                .addValue(PARAM_MOTIVO, entrada.motivo()), Long.class));

        sql.update("""
                UPDATE "CIERRE_RECORRIDO_PE2"
                   SET "idRevisionVigente" = :rev
                 WHERE "idCierreRecorrido" = :cierre
                """, Map.of("rev", idRev, PARAM_CIERRE, idCierre));

        for (PropuestaAplicacion p : entrada.propuestasOrdenadas()) {
            sql.update("""
                    INSERT INTO "PROPUESTA_APLICACION_PENDIENTE" (
                        "idCierreRecorrido","idRevisionConciliacion","idCliente","idPedido",
                        "importePropuesto","fechaHoraRegistro")
                    VALUES (:cierre,:rev,:cliente,:pedido,:importe,:ahora)
                    """, new MapSqlParameterSource()
                    .addValue(PARAM_CIERRE, idCierre)
                    .addValue("rev", idRev)
                    .addValue("cliente", p.idCliente())
                    .addValue("pedido", p.idPedido())
                    .addValue(PARAM_IMPORTE, p.importePropuesto())
                    .addValue(PARAM_AHORA, ahora));
        }

        ContextoCustodia ctx = new ContextoCustodia(idCierre, idRev, idAdmin, entrada.ubicacion(), entrada.motivo(), ahora);
        List<EventoCustodiaRegistrado> eventos = registrarEventosCustodia(ctx, entregas, totales);

        sql.update("""
                UPDATE "RECORRIDO_REPARTO"
                   SET "estadoOperativo" = 'PENDIENTE_CONCILIACION'
                 WHERE "idRecorrido" = :rec
                """, Map.of("rec", entrada.idRecorrido()));

        return new ConciliacionPreliminarRegistrada(
                idCierre,
                idRev,
                1,
                entrada.idRecorrido(),
                idRepartidor,
                idAdmin,
                ESTADO_PENDIENTE_CONCILIACION,
                "VIGENTE",
                ESTADO_PENDIENTE_CONCILIACION,
                entrada.claveIdempotencia(),
                VERSION_HUELLA,
                huellaCanonica,
                totales.importeEsperado(),
                totales.importeDeclaradoTotal(),
                entrada.efectivoRecibido(),
                totales.importeImputadoPropuesto(),
                CERO,
                totales.importeDiferenciaFaltante(),
                totales.importeCustodiaNoImputada(),
                entrada.motivo(),
                ahora,
                entrada.propuestasOrdenadas(),
                List.copyOf(eventos),
                true);
    }

    private List<EventoCustodiaRegistrado> registrarEventosCustodia(
            ContextoCustodia ctx,
            List<EntregaPe1> entregas,
            TotalesConciliacion totales) {
        List<EventoCustodiaRegistrado> eventos = new ArrayList<>();
        if (totales.importeDiferenciaFaltante().signum() > 0) {
            eventos.add(insertarEventoCustodia(ctx, null, null, "FALTANTE_EFECTIVO",
                    totales.importeDiferenciaFaltante()));
        }
        if (totales.importeCustodiaNoImputada().signum() > 0) {
            eventos.add(insertarEventoCustodia(ctx, null, null, "CUSTODIA_NO_IMPUTADA",
                    totales.importeCustodiaNoImputada()));
        }
        for (EntregaPe1 e : entregas) {
            BigDecimal sobranteEx01 = e.sobranteEx01();
            if (sobranteEx01.signum() > 0) {
                eventos.add(insertarEventoCustodia(ctx, e.idPedido(), e.idCliente(),
                        "SOBRANTE_DECLARADO_EX01", sobranteEx01));
            }
        }
        return eventos;
    }

    private EventoCustodiaRegistrado insertarEventoCustodia(
            ContextoCustodia ctx,
            Long idPedido,
            Long idCliente,
            String tipoEvento,
            BigDecimal importe) {
        long idEvento = Objects.requireNonNull(sql.queryForObject("""
                INSERT INTO "CUSTODIA_DIFERENCIA_RENDICION" (
                    "idCierreRecorrido","idRevisionConciliacion","idPedido","idCliente",
                    "tipoEvento","importe","ubicacionFisica","motivo","idUsuarioAdmin","fechaHoraRegistro")
                VALUES (:cierre,:rev,:pedido,:cliente,:tipo,:importe,:ubic,:motivo,:admin,:ahora)
                RETURNING "idEventoCustodia"
                """, new MapSqlParameterSource()
                .addValue(PARAM_CIERRE, ctx.idCierre())
                .addValue("rev", ctx.idRev())
                .addValue("pedido", idPedido)
                .addValue("cliente", idCliente)
                .addValue("tipo", tipoEvento)
                .addValue(PARAM_IMPORTE, importe)
                .addValue("ubic", ctx.ubicacion())
                .addValue(PARAM_MOTIVO, ctx.motivo())
                .addValue(PARAM_ADMIN, ctx.idAdmin())
                .addValue(PARAM_AHORA, ctx.ahora()), Long.class));
        return new EventoCustodiaRegistrado(idEvento, tipoEvento, idPedido, idCliente, importe, ctx.ubicacion());
    }

    private static BigDecimal escala2(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }
}
