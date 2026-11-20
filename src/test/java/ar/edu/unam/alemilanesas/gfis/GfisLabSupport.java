package ar.edu.unam.alemilanesas.gfis;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Soporte de laboratorio aislado G-FIS (Fase B).
 * Carga explicitamente el SQL candidato bajo src/test/resources/gfis/
 * exclusivamente dentro de PostgreSQL efimero (Testcontainers).
 * NO modifica src/main/** ni promueve V4 a Flyway de aplicacion.
 */
public final class GfisLabSupport {
    public static final String RUTA_V4_CANDIDATE = "gfis/V4_CANDIDATE__pe1_pe2_imp01_lab.sql";
    public static final String RUTA_V4_L02_CANDIDATE = "gfis/V4_CANDIDATE_L02_ANULACION_LAB.sql";

    // Namespaces candidatos A2-GLOBAL (G-ARQ-N2-02): dos enteros por dominio/recurso
    public static final int NS_RECORRIDO = 4100;
    public static final int NS_FINANZAS_PUENTE = 2929;
    public static final int NS_CLIENTE = 3202;
    public static final int NS_PEDIDO = 3003;
    public static final int NS_INSUMO = 8081;

    // Lock puente de compatibilidad con escritores legacy (CobroManualService / UC-22)
    public static final long LOCK_LEGACY_FINANCIERO = 2929001L;

    public static final class CoberturaNoAcreditadaException extends RuntimeException {
        public final long idPedido;
        public final BigDecimal solicitado;
        public final BigDecimal disponible;
        public CoberturaNoAcreditadaException(long idPedido, BigDecimal solicitado, BigDecimal disponible) {
            super("422 COBERTURA_NO_ACREDITADA: pedido=" + idPedido
                    + " solicitado=" + solicitado + " disponible=" + disponible);
            this.idPedido = idPedido;
            this.solicitado = solicitado;
            this.disponible = disponible;
        }
    }

    public record ResultadoPreflightRcg06(
            int senalA_cobroIdPedidoPendienteSinReserva,
            int senalB_saldoAFavorConPedidosPendientes,
            int senalC_reembolsoPendienteSinConciliar,
            int senalD_creditoSinIdPedidoPotencialmenteConsumido,
            int senalE_pagosOnlineYEntregasPendientesDeCierre) {
        public boolean requiereContencionSelectivaImpC() {
            return senalA_cobroIdPedidoPendienteSinReserva > 0
                    || senalB_saldoAFavorConPedidosPendientes > 0
                    || senalC_reembolsoPendienteSinConciliar > 0
                    || senalD_creditoSinIdPedidoPotencialmenteConsumido > 0
                    || senalE_pagosOnlineYEntregasPendientesDeCierre > 0;
        }
    }

    public record AplicacionPlan(long idCliente, long idPedido, BigDecimal importeAplicado) {}

    public record ResultadoCierreLab(long idCierre, String estadoCierre, boolean replay, String reciboJson) {}

    public record ResultadoRevisionLab(
            long idCierre,
            long idRevision,
            int numeroRevision,
            String estadoRevision,
            boolean replay) {}

    public record EvidenciaEgresoCustodia(
            String numeroReciboDevolucion,
            String receptorIdentificacion,
            String motivo) {}

    public record ResultadoAnulacionC04Lab(
            long idCobro,
            long idMovimientoCuenta,
            long idMovimientoCaja,
            boolean replay,
            String reciboJson) {}

    private GfisLabSupport() {}

    public static void aplicarMigracionCandidataV4EnLaboratorio(DataSource dataSource) {
        aplicarScriptSqlEnLaboratorio(dataSource, RUTA_V4_CANDIDATE);
    }

    public static void aplicarCorreccionCandidataL02EnLaboratorio(DataSource dataSource) {
        aplicarScriptSqlEnLaboratorio(dataSource, RUTA_V4_L02_CANDIDATE);
    }

    private static void aplicarScriptSqlEnLaboratorio(DataSource dataSource, String ruta) {
        String sql;
        try {
            sql = new ClassPathResource(ruta).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer SQL candidato G-FIS: " + ruta, ex);
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute(sql);
            c.commit();
        } catch (SQLException ex) {
            throw new IllegalStateException("Error aplicando SQL candidato en laboratorio G-FIS: " + ruta, ex);
        }
    }

    public static void bloquearA2(JdbcTemplate jdbc, int namespace, long recursoId) {
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(?, hashtext(?::text))",
                Object.class,
                namespace,
                Long.toString(recursoId));
    }

    /**
     * Adquiere multiples locks A2(namespace, hashtext(id::text)) en estricto orden
     * ascendente de clave hash int4 para eliminar ciclos incluso ante colisiones hash.
     */
    public static void bloquearA2LoteOrdenadoPorHash(JdbcTemplate jdbc, int namespace, List<Long> recursoIds) {
        if (recursoIds.isEmpty()) {
            return;
        }
        String csv = recursoIds.stream()
                .distinct()
                .map(Object::toString)
                .collect(Collectors.joining(","));
        jdbc.queryForList("""
                SELECT pg_advisory_xact_lock(?, k)
                  FROM (
                      SELECT DISTINCT hashtext(v::text) AS k
                        FROM unnest(string_to_array(?, ',')::bigint[]) AS v
                       ORDER BY 1 ASC
                  ) ordenado
                """, Object.class, namespace, csv);
    }

    public static void bloquearPuenteLegacyFinanciero(JdbcTemplate jdbc) {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + LOCK_LEGACY_FINANCIERO + ")");
    }

    public static long siguientePkCobroB2(JdbcTemplate jdbc) {
        Long id = jdbc.queryForObject("SELECT nextval('\"seq_gfis_cobro_id\"')", Long.class);
        return Objects.requireNonNull(id);
    }

    public static long siguientePkMovCuentaB2(JdbcTemplate jdbc) {
        Long id = jdbc.queryForObject("SELECT nextval('\"seq_gfis_mov_cuenta_id\"')", Long.class);
        return Objects.requireNonNull(id);
    }

    public static long siguientePkMovCajaB2(JdbcTemplate jdbc) {
        Long id = jdbc.queryForObject("SELECT nextval('\"seq_gfis_mov_caja_id\"')", Long.class);
        return Objects.requireNonNull(id);
    }

    public static long crearRepartidorAdicionalEnLab(JdbcTemplate jdbc, long idUsuario) {
        jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado",
                    "cambioCredencialPendiente","fechaHoraAlta")
                VALUES (?,?,'HASH_TEST','HABILITADO',false,now())
                """, idUsuario, "rep_gfis_" + idUsuario);
        jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'REPARTIDOR',now())", idUsuario);
        return idUsuario;
    }

    public static long crearRecorridoExplicito(JdbcTemplate jdbc, long idRepartidor, String ref) {
        Long id = jdbc.queryForObject("""
                INSERT INTO "RECORRIDO_REPARTO" ("idUsuarioRepartidor","fechaHoraCreacion","estadoOperativo","referenciaOperativa")
                VALUES (?, clock_timestamp(), 'ABIERTO', ?)
                RETURNING "idRecorrido"
                """, Long.class, idRepartidor, ref);
        return Objects.requireNonNull(id);
    }

    public static int asignarYVincularPedidoEnLab(
            JdbcTemplate jdbc, long idPedido, long idRepartidor, long idVendedor, long idRecorrido) {
        Integer siguiente = jdbc.queryForObject(
                "SELECT COALESCE(MAX(\"numeroAsignacion\"), 0) + 1 FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=?",
                Integer.class, idPedido);
        int num = Objects.requireNonNull(siguiente);
        jdbc.update("""
                INSERT INTO "ASIGNACION_REPARTO" (
                    "idPedido","numeroAsignacion","idUsuarioRepartidor","idUsuarioVendedor",
                    "fechaHoraInicio","fechaHoraFin","motivoReasignacion")
                VALUES (?,?,?,?,clock_timestamp(),NULL,NULL)
                """, idPedido, num, idRepartidor, idVendedor);
        vincularAsignacionARecorridoEnLab(jdbc, idRecorrido, idPedido, num);
        return num;
    }

    public static long vincularAsignacionARecorridoEnLab(
            JdbcTemplate jdbc, long idRecorrido, long idPedido, int numeroAsignacion) {
        Long id = jdbc.queryForObject("""
                INSERT INTO "RECORRIDO_ASIGNACION" (
                    "idRecorrido","idPedido","numeroAsignacion","estadoPertenencia","fechaHoraVinculacion")
                VALUES (?,?,?,'ACTIVA',clock_timestamp())
                RETURNING "idRecorridoAsignacion"
                """, Long.class, idRecorrido, idPedido, numeroAsignacion);
        return Objects.requireNonNull(id);
    }

    public static void desvincularAsignacionEnLab(
            JdbcTemplate jdbc, long idPedido, int numeroAsignacion, String estadoDestino, String motivo) {
        int filas = jdbc.update("""
                UPDATE "RECORRIDO_ASIGNACION"
                   SET "estadoPertenencia" = ?,
                       "fechaHoraDesvinculacion" = clock_timestamp(),
                       "motivoDesvinculacion" = ?
                 WHERE "idPedido" = ?
                   AND "numeroAsignacion" = ?
                   AND "estadoPertenencia" = 'ACTIVA'
                """, estadoDestino, motivo, idPedido, numeroAsignacion);
        if (filas != 1) {
            throw new IllegalStateException("No existia pertenencia ACTIVA para desvincular en Pedido=" + idPedido);
        }
    }

    /**
     * Registra una ENTREGA + DETALLE_ENTREGA + snapshot PE-1 y la vincula al Recorrido.
     * Exige pertenencia ACTIVA previa en RECORRIDO_ASIGNACION y ASIGNACION_REPARTO vigente.
     * Excluye UC-07 (solo originales).
     */
    public static void registrarEntregaPe1EnLaboratorio(
            JdbcTemplate jdbc,
            long idPedido,
            long idCliente,
            long idRepartidor,
            long idRecorrido,
            BigDecimal cargoRealCe,
            BigDecimal anticipoPe,
            BigDecimal fifoFe,
            BigDecimal declaradoDe,
            String modoEvidencia) {

        BigDecimal ke = anticipoPe.add(fifoFe);
        BigDecimal ee = cargoRealCe.subtract(ke);
        BigDecimal v0 = "IMP_C".equals(modoEvidencia)
                ? BigDecimal.ZERO.setScale(2)
                : declaradoDe.min(ee);

        jdbc.update("UPDATE \"PEDIDO\" SET \"estado\"='ENTREGADO' WHERE \"idPedido\"=?", idPedido);
        jdbc.update("""
                INSERT INTO "ENTREGA" ("idPedido","fechaHoraEntrega","idUsuarioRepartidor")
                VALUES (?, clock_timestamp(), ?)
                """, idPedido, idRepartidor);
        jdbc.update("""
                INSERT INTO "DETALLE_ENTREGA" ("idPedido","numeroDetalle","cantidadEntregada")
                SELECT "idPedido","numeroDetalle","cantidadOriginal"
                  FROM "DETALLE_PEDIDO" WHERE "idPedido"=?
                """, idPedido);
        jdbc.update("""
                INSERT INTO "RECONOCIMIENTO_ENTREGA_PE1" (
                    "idPedido","idCliente","claveIdempotenciaEntrega",
                    "cargoRealCe","anticipoExplicitoPe","fifoVinculanteFe",
                    "coberturaTotalKe","exigibleEe","declaradoRepartidorDe",
                    "reconocimientoInicialV0","reconocimientoVigenteVvig",
                    "modoEvidencia","estadoContencionUc07","fechaHoraRegistro")
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'SOLO_ORIGINALES',clock_timestamp())
                """,
                idPedido, idCliente, UUID.randomUUID(),
                cargoRealCe, anticipoPe, fifoFe,
                ke, ee, declaradoDe,
                v0, v0, modoEvidencia);
        jdbc.update("""
                INSERT INTO "RECORRIDO_ENTREGA" ("idRecorrido","idPedido","fechaHoraVinculacion")
                VALUES (?,?,clock_timestamp())
                """, idRecorrido, idPedido);
        jdbc.update("""
                UPDATE "RECORRIDO_ASIGNACION"
                   SET "estadoPertenencia" = 'ENTREGADA'
                 WHERE "idPedido" = ?
                   AND "idRecorrido" = ?
                   AND "estadoPertenencia" = 'ACTIVA'
                """, idPedido, idRecorrido);
    }

    private static void exigirAdminHabilitadoEnLab(JdbcTemplate jdbc, long idAdmin) {
        Integer adminValido = jdbc.queryForObject("""
                SELECT count(*)
                  FROM "USUARIO" u
                  JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
                 WHERE u."idUsuario" = ?
                   AND u."estado" = 'HABILITADO'
                   AND ur."codigoRol" = 'ADMINISTRADOR'
                """, Integer.class, idAdmin);
        if (adminValido == null || adminValido == 0) {
            throw new SecurityException("C1-a/RBAC 403: solo ADMINISTRADOR habilitado puede operar o consultar replay de cierre");
        }
    }

    /**
     * Registra un cierre LIMPIO en estado CONFIRMADO en una unica transaccion ACID T1-C2 + K2
     * (sin diferencias ni custodia abierta).
     */
    public static ResultadoCierreLab ejecutarCierreLimpioT1K2EnTx(
            JdbcTemplate jdbc,
            long idRecorrido,
            long idAdmin,
            UUID claveIdempotenciaCierre,
            String versionHuella,
            String huellaCanonica,
            BigDecimal importeEsperado,
            BigDecimal importeEfectivoRecibido,
            List<AplicacionPlan> aplicaciones,
            boolean usarPuenteLegacy2929001,
            boolean usarSecuenciasB2) {

        exigirAdminHabilitadoEnLab(jdbc, idAdmin);
        bloquearA2(jdbc, NS_RECORRIDO, idRecorrido);
        if (usarPuenteLegacy2929001) {
            bloquearPuenteLegacyFinanciero(jdbc);
        }

        ResultadoCierreLab replay = verificarReplayCierreDefinitivo(
                jdbc, idRecorrido, claveIdempotenciaCierre, versionHuella, huellaCanonica);
        if (replay != null) {
            return replay;
        }

        BigDecimal totalImputado = aplicaciones.stream()
                .map(AplicacionPlan::importeAplicado)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);

        Map<Long, BigDecimal> coberturaPorPedido = bloquearClientesPedidosYPe1(jdbc, aplicaciones);

        String reciboJson = "{\"idRecorrido\":" + idRecorrido
                + ",\"estado\":\"CONFIRMADO\""
                + ",\"imputado\":\"" + totalImputado
                + "\",\"custodia\":\"0.00\",\"faltante\":\"0.00\"}";

        var insertado = jdbc.queryForMap("""
                INSERT INTO "CIERRE_RECORRIDO_PE2" (
                    "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                    "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                    "importeImputadoConfirmado","importeDiferenciaFaltante","importeCustodiaNoImputada",
                    "reciboHistoricoJson")
                VALUES (?,?,?,?,?,clock_timestamp(),'CONFIRMADO','EFECTIVO',?,?,?,?,0,0,?::jsonb)
                RETURNING "idCierreRecorrido", "reciboHistoricoJson"::text AS recibo
                """,
                idRecorrido, claveIdempotenciaCierre, versionHuella, huellaCanonica,
                idAdmin, importeEsperado, importeEfectivoRecibido, totalImputado, totalImputado, reciboJson);

        long cierreId = ((Number) Objects.requireNonNull(insertado.get("idCierreRecorrido"))).longValue();
        String reciboNormalizado = (String) Objects.requireNonNull(insertado.get("recibo"));

        confirmarAplicacionesFinancierasK2EnTx(
                jdbc, cierreId, idAdmin, claveIdempotenciaCierre,
                aplicaciones, coberturaPorPedido, usarSecuenciasB2);

        jdbc.update("UPDATE \"RECORRIDO_REPARTO\" SET \"estadoOperativo\"='CERRADO' WHERE \"idRecorrido\"=?",
                idRecorrido);

        return new ResultadoCierreLab(cierreId, "CONFIRMADO", false, reciboNormalizado);
    }

    /**
     * Bloqueante 1 (G-ARQ-N2-01 s8) + Alternativa A (revisiones versionadas):
     * Registra una rendicion con diferencias en PENDIENTE_CONCILIACION creando
     * la cabecera y su revision 1 (VIGENTE) con propuestas de planificacion y custodia.
     * NO crea ningun COBRO, MOVIMIENTO_CUENTA, MOVIMIENTO_CAJA, APLICACION_RENDICION_K2
     * ni extingue V_e^{vig}. Tampoco emite recibo definitivo T1.
     */
    public static ResultadoCierreLab registrarPendienteConciliacionEnTx(
            JdbcTemplate jdbc,
            long idRecorrido,
            long idAdmin,
            UUID claveIdempotenciaPropuesta,
            BigDecimal importeEsperado,
            BigDecimal importeEfectivoRecibido,
            BigDecimal importeDiferenciaFaltante,
            BigDecimal importeCustodiaNoImputada,
            String motivoDiferencia,
            List<AplicacionPlan> propuestasPlanificacion) {

        exigirAdminHabilitadoEnLab(jdbc, idAdmin);
        bloquearA2(jdbc, NS_RECORRIDO, idRecorrido);

        BigDecimal totalPropuesto = propuestasPlanificacion.stream()
                .map(AplicacionPlan::importeAplicado)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);

        Long cierreId = jdbc.queryForObject("""
                INSERT INTO "CIERRE_RECORRIDO_PE2" (
                    "idRecorrido","claveIdempotenciaPropuesta",
                    "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                    "importeImputadoConfirmado","importeDiferenciaFaltante","importeCustodiaNoImputada",
                    "motivoDiferencia")
                VALUES (?,?,?,clock_timestamp(),'PENDIENTE_CONCILIACION','EFECTIVO',?,?,?,0,?,?,?)
                RETURNING "idCierreRecorrido"
                """,
                Long.class,
                idRecorrido, claveIdempotenciaPropuesta, idAdmin,
                importeEsperado, importeEfectivoRecibido, totalPropuesto,
                importeDiferenciaFaltante, importeCustodiaNoImputada, motivoDiferencia);
        long idCierre = Objects.requireNonNull(cierreId);

        String huellaRev1 = construirHuellaRevision(
                idCierre, importeEsperado, importeEfectivoRecibido, totalPropuesto,
                importeDiferenciaFaltante, importeCustodiaNoImputada, motivoDiferencia, propuestasPlanificacion);

        Long revId = jdbc.queryForObject("""
                INSERT INTO "REVISION_CONCILIACION_PE2" (
                    "idCierreRecorrido","numeroRevision","idRevisionAnterior","estadoRevision",
                    "claveIdempotenciaRevision","versionHuella","huellaCanonica",
                    "idUsuarioAdmin","fechaHoraRevision",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                    "importeDiferenciaFaltante","importeCustodiaNoImputada","motivoRevision")
                VALUES (?,1,NULL,'VIGENTE',?,'v1',?,?,clock_timestamp(),?,?,?,?,?,?)
                RETURNING "idRevisionConciliacion"
                """,
                Long.class,
                idCierre, claveIdempotenciaPropuesta, huellaRev1, idAdmin,
                importeEsperado, importeEfectivoRecibido, totalPropuesto,
                importeDiferenciaFaltante, importeCustodiaNoImputada, motivoDiferencia);
        long idRev = Objects.requireNonNull(revId);

        jdbc.update("""
                UPDATE "CIERRE_RECORRIDO_PE2"
                   SET "idRevisionVigente" = ?
                 WHERE "idCierreRecorrido" = ?
                """, idRev, idCierre);

        registrarPropuestasYCustodiaDeRevision(
                jdbc, idCierre, idRev, idAdmin,
                importeDiferenciaFaltante, importeCustodiaNoImputada,
                motivoDiferencia, propuestasPlanificacion);

        jdbc.update("UPDATE \"RECORRIDO_REPARTO\" SET \"estadoOperativo\"='PENDIENTE_CONCILIACION' WHERE \"idRecorrido\"=?",
                idRecorrido);

        return new ResultadoCierreLab(idCierre, "PENDIENTE_CONCILIACION", false, null);
    }

    /**
     * Alternativa A (CANDIDATA, NO RATIFICADA): rectifica una conciliacion en
     * PENDIENTE_CONCILIACION emitiendo una nueva revision append-only (numeroRevision = N+1),
     * superando la anterior sin borrar historial, revalidando pertenencia y cobertura (422)
     * y soportando replay idempotente por claveIdempotenciaRevision (Escenario F).
     * Produce 0 efectos financieros definitivos (0 Cobro, 0 PAGO, 0 Caja, 0 extincion V_e).
     */
    public static ResultadoRevisionLab rectificarPendienteConciliacionEnTx(
            JdbcTemplate jdbc,
            long idCierreRecorrido,
            long idRecorrido,
            long idAdmin,
            UUID claveIdempotenciaRevision,
            BigDecimal importeEsperado,
            BigDecimal importeEfectivoRecibido,
            BigDecimal importeDiferenciaFaltante,
            BigDecimal importeCustodiaNoImputada,
            String motivoRevision,
            List<AplicacionPlan> propuestasCorregidas) {

        exigirAdminHabilitadoEnLab(jdbc, idAdmin);
        bloquearA2(jdbc, NS_RECORRIDO, idRecorrido);

        BigDecimal totalPropuesto = propuestasCorregidas.stream()
                .map(AplicacionPlan::importeAplicado)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        String huella = construirHuellaRevision(
                idCierreRecorrido, importeEsperado, importeEfectivoRecibido, totalPropuesto,
                importeDiferenciaFaltante, importeCustodiaNoImputada, motivoRevision, propuestasCorregidas);

        // Escenario F: replay idempotente de rectificacion por claveIdempotenciaRevision
        var previas = jdbc.queryForList("""
                SELECT "idRevisionConciliacion","idCierreRecorrido","numeroRevision",
                       "estadoRevision","versionHuella","huellaCanonica"
                  FROM "REVISION_CONCILIACION_PE2"
                 WHERE "claveIdempotenciaRevision" = ?
                """, claveIdempotenciaRevision);
        if (!previas.isEmpty()) {
            var row = previas.getFirst();
            if (((Number) row.get("idCierreRecorrido")).longValue() != idCierreRecorrido) {
                throw new IllegalStateException("REV CONFLICTO AMBITO 409: claveIdempotenciaRevision pertenece a otro cierre");
            }
            if (!"v1".equals(row.get("versionHuella")) || !huella.equals(row.get("huellaCanonica"))) {
                throw new IllegalStateException("REV CONFLICTO HUELLA 409: claveIdempotenciaRevision reutilizada con otros datos");
            }
            return new ResultadoRevisionLab(
                    idCierreRecorrido,
                    ((Number) row.get("idRevisionConciliacion")).longValue(),
                    ((Number) row.get("numeroRevision")).intValue(),
                    (String) row.get("estadoRevision"),
                    true);
        }

        // Bloquear cabecera de cierre y verificar estado PENDIENTE_CONCILIACION (Escenario G)
        var cabecera = jdbc.queryForMap("""
                SELECT "idRecorrido","estadoCierre","idRevisionVigente"
                  FROM "CIERRE_RECORRIDO_PE2"
                 WHERE "idCierreRecorrido" = ?
                 FOR UPDATE
                """, idCierreRecorrido);
        if (((Number) cabecera.get("idRecorrido")).longValue() != idRecorrido) {
            throw new IllegalStateException("Ambito inconsistente de cierre/recorrido en rectificacion");
        }
        String estadoActual = (String) cabecera.get("estadoCierre");
        if (!"PENDIENTE_CONCILIACION".equals(estadoActual)) {
            throw new IllegalStateException("REV CONFLICTO 409: no se puede rectificar cierre en estado terminal " + estadoActual);
        }

        // Escenario D: revalidar cobertura disponible (422 COBERTURA_NO_ACREDITADA) y pertenencia
        for (var prop : propuestasCorregidas) {
            Integer pertenece = jdbc.queryForObject("""
                    SELECT count(*) FROM "RECORRIDO_ENTREGA"
                     WHERE "idRecorrido" = ? AND "idPedido" = ?
                    """, Integer.class, idRecorrido, prop.idPedido());
            if (pertenece == null || pertenece == 0) {
                throw new IllegalArgumentException("C1/REV: Pedido " + prop.idPedido() + " no pertenece al Recorrido " + idRecorrido);
            }
            BigDecimal disponible = jdbc.queryForObject("""
                    SELECT "reconocimientoVigenteVvig" FROM "RECONOCIMIENTO_ENTREGA_PE1"
                     WHERE "idPedido" = ? FOR SHARE
                    """, BigDecimal.class, prop.idPedido());
            BigDecimal disp = disponible == null ? BigDecimal.ZERO : disponible;
            if (prop.importeAplicado().compareTo(disp) > 0) {
                throw new CoberturaNoAcreditadaException(prop.idPedido(), prop.importeAplicado(), disp);
            }
        }

        // Obtener ultima revision para encadenar (puede ser VIGENTE o INVALIDADA en Escenario H)
        var ultima = jdbc.queryForMap("""
                SELECT "idRevisionConciliacion","numeroRevision","estadoRevision"
                  FROM "REVISION_CONCILIACION_PE2"
                 WHERE "idCierreRecorrido" = ?
                 ORDER BY "numeroRevision" DESC
                 LIMIT 1
                 FOR UPDATE
                """, idCierreRecorrido);
        long idAnterior = ((Number) ultima.get("idRevisionConciliacion")).longValue();
        int numAnterior = ((Number) ultima.get("numeroRevision")).intValue();
        String estadoAnterior = (String) ultima.get("estadoRevision");

        if ("VIGENTE".equals(estadoAnterior)) {
            jdbc.update("""
                    UPDATE "REVISION_CONCILIACION_PE2"
                       SET "estadoRevision" = 'SUPERADA'
                     WHERE "idRevisionConciliacion" = ?
                    """, idAnterior);
        }

        int nuevoNumero = numAnterior + 1;
        Long nuevoId = jdbc.queryForObject("""
                INSERT INTO "REVISION_CONCILIACION_PE2" (
                    "idCierreRecorrido","numeroRevision","idRevisionAnterior","estadoRevision",
                    "claveIdempotenciaRevision","versionHuella","huellaCanonica",
                    "idUsuarioAdmin","fechaHoraRevision",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoPropuesto",
                    "importeDiferenciaFaltante","importeCustodiaNoImputada","motivoRevision")
                VALUES (?,?,?,'VIGENTE',?,'v1',?,?,clock_timestamp(),?,?,?,?,?,?)
                RETURNING "idRevisionConciliacion"
                """,
                Long.class,
                idCierreRecorrido, nuevoNumero, idAnterior,
                claveIdempotenciaRevision, huella, idAdmin,
                importeEsperado, importeEfectivoRecibido, totalPropuesto,
                importeDiferenciaFaltante, importeCustodiaNoImputada, motivoRevision);
        long idNuevaRev = Objects.requireNonNull(nuevoId);

        registrarPropuestasYCustodiaDeRevision(
                jdbc, idCierreRecorrido, idNuevaRev, idAdmin,
                importeDiferenciaFaltante, importeCustodiaNoImputada,
                motivoRevision, propuestasCorregidas);

        jdbc.update("""
                UPDATE "CIERRE_RECORRIDO_PE2"
                   SET "idRevisionVigente" = ?,
                       "importeEsperado" = ?,
                       "importeEfectivoRecibido" = ?,
                       "importeImputadoPropuesto" = ?,
                       "importeDiferenciaFaltante" = ?,
                       "importeCustodiaNoImputada" = ?,
                       "motivoDiferencia" = ?
                 WHERE "idCierreRecorrido" = ?
                """,
                idNuevaRev, importeEsperado, importeEfectivoRecibido, totalPropuesto,
                importeDiferenciaFaltante, importeCustodiaNoImputada, motivoRevision, idCierreRecorrido);

        return new ResultadoRevisionLab(idCierreRecorrido, idNuevaRev, nuevoNumero, "VIGENTE", false);
    }

    /**
     * Escenario H (Alternativa A): invalida formalmente la revision VIGENTE sin borrar
     * evidencia historica y desvincula idRevisionVigente de la cabecera, impidiendo
     * confirmar T1-C2 hasta que un ADMIN emita una nueva revision valida.
     */
    public static void invalidarRevisionPendienteEnTx(
            JdbcTemplate jdbc,
            long idCierreRecorrido,
            long idRecorrido,
            long idAdmin,
            String motivoInvalidacion) {

        exigirAdminHabilitadoEnLab(jdbc, idAdmin);
        bloquearA2(jdbc, NS_RECORRIDO, idRecorrido);

        var cabecera = jdbc.queryForMap("""
                SELECT "idRecorrido","estadoCierre","idRevisionVigente"
                  FROM "CIERRE_RECORRIDO_PE2"
                 WHERE "idCierreRecorrido" = ?
                 FOR UPDATE
                """, idCierreRecorrido);
        if (((Number) cabecera.get("idRecorrido")).longValue() != idRecorrido) {
            throw new IllegalStateException("Ambito inconsistente al invalidar revision");
        }
        if (!"PENDIENTE_CONCILIACION".equals(cabecera.get("estadoCierre"))) {
            throw new IllegalStateException("REV CONFLICTO 409: no se puede invalidar un cierre terminal");
        }
        Object revVigObj = cabecera.get("idRevisionVigente");
        if (revVigObj == null) {
            throw new IllegalStateException("REV CONFLICTO 409: no existe revision VIGENTE para invalidar");
        }
        long idRevVig = ((Number) revVigObj).longValue();

        jdbc.update("""
                UPDATE "REVISION_CONCILIACION_PE2"
                   SET "estadoRevision" = 'INVALIDADA',
                       "idUsuarioInvalidacion" = ?,
                       "motivoInvalidacion" = ?,
                       "fechaHoraInvalidacion" = clock_timestamp()
                 WHERE "idRevisionConciliacion" = ?
                """, idAdmin, motivoInvalidacion, idRevVig);

        jdbc.update("""
                UPDATE "CIERRE_RECORRIDO_PE2"
                   SET "idRevisionVigente" = NULL
                 WHERE "idCierreRecorrido" = ?
                """, idCierreRecorrido);
    }

    private static void registrarPropuestasYCustodiaDeRevision(
            JdbcTemplate jdbc,
            long idCierre,
            long idRev,
            long idAdmin,
            BigDecimal importeDiferenciaFaltante,
            BigDecimal importeCustodiaNoImputada,
            String motivo,
            List<AplicacionPlan> propuestas) {

        for (var prop : propuestas) {
            jdbc.update("""
                    INSERT INTO "PROPUESTA_APLICACION_PENDIENTE" (
                        "idCierreRecorrido","idRevisionConciliacion","idCliente","idPedido",
                        "importePropuesto","fechaHoraRegistro")
                    VALUES (?,?,?,?,?,clock_timestamp())
                    """, idCierre, idRev, prop.idCliente(), prop.idPedido(), prop.importeAplicado());
        }
        if (importeDiferenciaFaltante.signum() > 0) {
            jdbc.update("""
                    INSERT INTO "CUSTODIA_DIFERENCIA_RENDICION" (
                        "idCierreRecorrido","idRevisionConciliacion","tipoEvento",
                        "importe","motivo","idUsuarioAdmin","fechaHoraRegistro")
                    VALUES (?,?,'FALTANTE_EFECTIVO',?,?,?,clock_timestamp())
                    """, idCierre, idRev, importeDiferenciaFaltante, motivo, idAdmin);
        }
        if (importeCustodiaNoImputada.signum() > 0) {
            jdbc.update("""
                    INSERT INTO "CUSTODIA_DIFERENCIA_RENDICION" (
                        "idCierreRecorrido","idRevisionConciliacion","tipoEvento",
                        "importe","motivo","idUsuarioAdmin","fechaHoraRegistro")
                    VALUES (?,?,'CUSTODIA_NO_IMPUTADA',?,?,?,clock_timestamp())
                    """, idCierre, idRev, importeCustodiaNoImputada, motivo, idAdmin);
        }
    }

    private static String construirHuellaRevision(
            long idCierre,
            BigDecimal esperado,
            BigDecimal recibido,
            BigDecimal propuesto,
            BigDecimal faltante,
            BigDecimal custodia,
            String motivo,
            List<AplicacionPlan> apps) {
        String appsCanon = apps.stream()
                .sorted(Comparator.comparingLong(AplicacionPlan::idPedido))
                .map(a -> a.idCliente() + ":" + a.idPedido() + ":" + a.importeAplicado().toPlainString())
                .collect(Collectors.joining(";"));
        String motivoNorm = motivo == null ? "" : motivo.trim().replaceAll("\\s+", " ");
        return "REV:v1:" + idCierre + "|" + esperado.toPlainString() + "|" + recibido.toPlainString()
                + "|" + propuesto.toPlainString() + "|" + faltante.toPlainString()
                + "|" + custodia.toPlainString() + "|" + motivoNorm + "|" + appsCanon;
    }

    /**
     * Sobrecarga compatible con tests existentes (asume numeroRevisionEsperado = 1).
     */
    public static ResultadoCierreLab confirmarResolucionT1K2EnTx(
            JdbcTemplate jdbc,
            long idCierreRecorrido,
            long idRecorrido,
            long idAdminResolucion,
            UUID claveIdempotenciaCierre,
            String versionHuella,
            String huellaCanonica,
            String tipoResolucionAdmin,
            String motivoResolucionAdmin,
            EvidenciaEgresoCustodia egresoCustodia,
            boolean usarPuenteLegacy2929001,
            boolean usarSecuenciasB2) {
        return confirmarResolucionT1K2EnTx(
                jdbc, idCierreRecorrido, idRecorrido, idAdminResolucion, 1,
                claveIdempotenciaCierre, versionHuella, huellaCanonica,
                tipoResolucionAdmin, motivoResolucionAdmin, egresoCustodia,
                usarPuenteLegacy2929001, usarSecuenciasB2);
    }

    /**
     * Bloqueante 1 + Bloqueante 4 + Escenario E (control de revision esperada):
     * En UNA UNICA transaccion ACID T1-C2:
     * 1) Valida revision VIGENTE == numeroRevisionEsperado (evita confirmar revision obsoleta)
     * 2) Transiciona revision VIGENTE -> CONFIRMADA_T1 y cierre -> CERRADO_CON_DIFERENCIA_RESUELTA
     * 3) Emite de manera indivisible Cobros K2, Cuenta, Caja, aplicaciones y extincion parcial V_e
     *    exclusivamente a partir de las propuestas de la revision confirmada
     * 4) Si hay devolucion fisica de custodia, registra EGRESO_CUSTODIA_RENDICION sobre el evento
     *    CUSTODIA_NO_IMPUTADA de esa misma revision confirmada
     * 5) Persiste reciboHistoricoJson inmutable para replay C1-a y cierra el Recorrido.
     */
    public static ResultadoCierreLab confirmarResolucionT1K2EnTx(
            JdbcTemplate jdbc,
            long idCierreRecorrido,
            long idRecorrido,
            long idAdminResolucion,
            int numeroRevisionEsperado,
            UUID claveIdempotenciaCierre,
            String versionHuella,
            String huellaCanonica,
            String tipoResolucionAdmin,
            String motivoResolucionAdmin,
            EvidenciaEgresoCustodia egresoCustodia,
            boolean usarPuenteLegacy2929001,
            boolean usarSecuenciasB2) {

        exigirAdminHabilitadoEnLab(jdbc, idAdminResolucion);
        bloquearA2(jdbc, NS_RECORRIDO, idRecorrido);
        if (usarPuenteLegacy2929001) {
            bloquearPuenteLegacyFinanciero(jdbc);
        }

        ResultadoCierreLab replay = verificarReplayCierreDefinitivo(
                jdbc, idRecorrido, claveIdempotenciaCierre, versionHuella, huellaCanonica);
        if (replay != null) {
            return replay;
        }

        var cabecera = jdbc.queryForMap("""
                SELECT "idRecorrido","estadoCierre","idRevisionVigente","importeImputadoPropuesto",
                       "importeDiferenciaFaltante","importeCustodiaNoImputada"
                  FROM "CIERRE_RECORRIDO_PE2"
                 WHERE "idCierreRecorrido" = ?
                 FOR UPDATE
                """, idCierreRecorrido);
        if (((Number) cabecera.get("idRecorrido")).longValue() != idRecorrido) {
            throw new IllegalStateException("Ambito inconsistente de cierre/recorrido");
        }
        String estadoCab = (String) cabecera.get("estadoCierre");
        if (!"PENDIENTE_CONCILIACION".equals(estadoCab)) {
            throw new IllegalStateException("C1-a CONFLICTO 409: cierre ya se encuentra en estado terminal " + estadoCab);
        }
        Object revVigObj = cabecera.get("idRevisionVigente");
        if (revVigObj == null) {
            throw new IllegalStateException("REV CONFLICTO 409: no existe revision VIGENTE (revision previa invalidada)");
        }
        long idRevVigente = ((Number) revVigObj).longValue();

        var rev = jdbc.queryForMap("""
                SELECT "numeroRevision","estadoRevision"
                  FROM "REVISION_CONCILIACION_PE2"
                 WHERE "idRevisionConciliacion" = ?
                 FOR UPDATE
                """, idRevVigente);
        int numRevActual = ((Number) rev.get("numeroRevision")).intValue();
        if (numRevActual != numeroRevisionEsperado || !"VIGENTE".equals(rev.get("estadoRevision"))) {
            throw new IllegalStateException(
                    "REV CONFLICTO 409 REVISION_OBSOLETA: se esperaba revision " + numeroRevisionEsperado
                            + " pero la revision vigente es " + numRevActual);
        }

        BigDecimal totalPropuesto = (BigDecimal) cabecera.get("importeImputadoPropuesto");
        BigDecimal faltante = (BigDecimal) cabecera.get("importeDiferenciaFaltante");
        BigDecimal custodia = (BigDecimal) cabecera.get("importeCustodiaNoImputada");

        List<AplicacionPlan> propuestas = jdbc.query("""
                SELECT "idCliente","idPedido","importePropuesto"
                  FROM "PROPUESTA_APLICACION_PENDIENTE"
                 WHERE "idRevisionConciliacion" = ?
                 ORDER BY "idPedido" ASC
                """,
                (rs, n) -> new AplicacionPlan(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3)),
                idRevVigente);

        Map<Long, BigDecimal> coberturaPorPedido = bloquearClientesPedidosYPe1(jdbc, propuestas);

        jdbc.update("""
                UPDATE "REVISION_CONCILIACION_PE2"
                   SET "estadoRevision" = 'CONFIRMADA_T1'
                 WHERE "idRevisionConciliacion" = ?
                """, idRevVigente);

        String reciboJson = "{\"idRecorrido\":" + idRecorrido
                + ",\"estado\":\"CERRADO_CON_DIFERENCIA_RESUELTA\""
                + ",\"numeroRevisionConfirmada\":" + numRevActual
                + ",\"tipoResolucion\":\"" + tipoResolucionAdmin + "\""
                + ",\"imputado\":\"" + totalPropuesto
                + "\",\"custodia\":\"" + custodia
                + "\",\"faltante\":\"" + faltante + "\"}";

        String reciboNormalizado = jdbc.queryForObject("""
                UPDATE "CIERRE_RECORRIDO_PE2"
                   SET "estadoCierre" = 'CERRADO_CON_DIFERENCIA_RESUELTA',
                       "claveIdempotenciaCierre" = ?,
                       "versionHuella" = ?,
                       "huellaCanonica" = ?,
                       "importeImputadoConfirmado" = "importeImputadoPropuesto",
                       "idUsuarioResolucionAdmin" = ?,
                       "tipoResolucionAdmin" = ?,
                       "motivoResolucionAdmin" = ?,
                       "fechaHoraResolucion" = clock_timestamp(),
                       "reciboHistoricoJson" = ?::jsonb
                 WHERE "idCierreRecorrido" = ?
                 RETURNING "reciboHistoricoJson"::text
                """,
                String.class,
                claveIdempotenciaCierre, versionHuella, huellaCanonica,
                idAdminResolucion, tipoResolucionAdmin, motivoResolucionAdmin,
                reciboJson, idCierreRecorrido);

        confirmarAplicacionesFinancierasK2EnTx(
                jdbc, idCierreRecorrido, idAdminResolucion, claveIdempotenciaCierre,
                propuestas, coberturaPorPedido, usarSecuenciasB2);

        if (egresoCustodia != null && custodia.signum() > 0) {
            Long idEventoCustodia = jdbc.queryForObject("""
                    SELECT "idEventoCustodia"
                      FROM "CUSTODIA_DIFERENCIA_RENDICION"
                     WHERE "idCierreRecorrido" = ?
                       AND "idRevisionConciliacion" = ?
                       AND "tipoEvento" = 'CUSTODIA_NO_IMPUTADA'
                     ORDER BY "idEventoCustodia" ASC LIMIT 1
                    """, Long.class, idCierreRecorrido, idRevVigente);
            jdbc.update("""
                    INSERT INTO "EGRESO_CUSTODIA_RENDICION" (
                        "idEventoCustodia","idCierreRecorrido","tipoEgreso","importeEgreso",
                        "numeroReciboDevolucion","receptorIdentificacion","motivo",
                        "idUsuarioAdmin","fechaHoraEgreso")
                    VALUES (?,?,'DEVOLUCION_FISICA_CON_RECIBO',?,?,?,?,?,clock_timestamp())
                    """,
                    Objects.requireNonNull(idEventoCustodia), idCierreRecorrido, custodia,
                    egresoCustodia.numeroReciboDevolucion(),
                    egresoCustodia.receptorIdentificacion(),
                    egresoCustodia.motivo(),
                    idAdminResolucion);
        }

        jdbc.update("UPDATE \"RECORRIDO_REPARTO\" SET \"estadoOperativo\"='CERRADO' WHERE \"idRecorrido\"=?",
                idRecorrido);

        return new ResultadoCierreLab(
                idCierreRecorrido, "CERRADO_CON_DIFERENCIA_RESUELTA", false, Objects.requireNonNull(reciboNormalizado));
    }

    private static ResultadoCierreLab verificarReplayCierreDefinitivo(
            JdbcTemplate jdbc, long idRecorrido, UUID claveIdempotenciaCierre,
            String versionHuella, String huellaCanonica) {
        var existentes = jdbc.queryForList("""
                SELECT "idCierreRecorrido","idRecorrido","estadoCierre","versionHuella",
                       "huellaCanonica","reciboHistoricoJson"::text AS recibo
                  FROM "CIERRE_RECORRIDO_PE2"
                 WHERE "claveIdempotenciaCierre" = ?
                """, claveIdempotenciaCierre);
        if (existentes.isEmpty()) {
            return null;
        }
        var row = existentes.getFirst();
        long recorridoPersistido = ((Number) row.get("idRecorrido")).longValue();
        if (recorridoPersistido != idRecorrido) {
            throw new IllegalStateException(
                    "C1-a CONFLICTO AMBITO 409: claveIdempotenciaCierre pertenece a otro Recorrido");
        }
        if (!versionHuella.equals(row.get("versionHuella"))
                || !huellaCanonica.equals(row.get("huellaCanonica"))) {
            throw new IllegalStateException(
                    "C1-a CONFLICTO HUELLA 409: misma claveIdempotenciaCierre con distinta huella");
        }
        return new ResultadoCierreLab(
                ((Number) row.get("idCierreRecorrido")).longValue(),
                (String) row.get("estadoCierre"),
                true,
                (String) row.get("recibo"));
    }

    private static Map<Long, BigDecimal> bloquearClientesPedidosYPe1(
            JdbcTemplate jdbc, List<AplicacionPlan> aplicaciones) {
        List<Long> clientesOrdenados = aplicaciones.stream()
                .map(AplicacionPlan::idCliente).distinct().sorted().toList();
        bloquearA2LoteOrdenadoPorHash(jdbc, NS_CLIENTE, clientesOrdenados);
        for (long idCliente : clientesOrdenados) {
            jdbc.queryForObject("SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE",
                    Long.class, idCliente);
        }
        List<AplicacionPlan> appsOrdenadas = aplicaciones.stream()
                .sorted(Comparator.comparingLong(AplicacionPlan::idPedido)).toList();
        Map<Long, BigDecimal> coberturaPorPedido = new LinkedHashMap<>();
        for (var app : appsOrdenadas) {
            jdbc.queryForObject("SELECT \"idPedido\" FROM \"PEDIDO\" WHERE \"idPedido\"=? FOR UPDATE",
                    Long.class, app.idPedido());
            BigDecimal vvigActual = jdbc.queryForObject(
                    "SELECT \"reconocimientoVigenteVvig\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=? FOR UPDATE",
                    BigDecimal.class, app.idPedido());
            coberturaPorPedido.put(app.idPedido(), vvigActual == null ? BigDecimal.ZERO : vvigActual);
        }
        return coberturaPorPedido;
    }

    private static void confirmarAplicacionesFinancierasK2EnTx(
            JdbcTemplate jdbc,
            long cierreId,
            long idAdmin,
            UUID claveIdempotenciaCierre,
            List<AplicacionPlan> aplicaciones,
            Map<Long, BigDecimal> coberturaPorPedido,
            boolean usarSecuenciasB2) {

        List<AplicacionPlan> appsOrdenadas = aplicaciones.stream()
                .sorted(Comparator.comparingLong(AplicacionPlan::idPedido)).toList();
        for (var app : appsOrdenadas) {
            BigDecimal disponible = coberturaPorPedido.getOrDefault(app.idPedido(), BigDecimal.ZERO);
            if (app.importeAplicado().compareTo(disponible) > 0) {
                throw new CoberturaNoAcreditadaException(app.idPedido(), app.importeAplicado(), disponible);
            }
        }

        List<Long> clientesOrdenados = appsOrdenadas.stream()
                .map(AplicacionPlan::idCliente).distinct().sorted().toList();
        Map<Long, List<AplicacionPlan>> porCliente = new LinkedHashMap<>();
        for (long idCliente : clientesOrdenados) {
            porCliente.put(idCliente, appsOrdenadas.stream().filter(a -> a.idCliente() == idCliente).toList());
        }

        for (var entry : porCliente.entrySet()) {
            long idCliente = entry.getKey();
            List<AplicacionPlan> appsCliente = entry.getValue();
            BigDecimal importeCobroCliente = appsCliente.stream()
                    .map(AplicacionPlan::importeAplicado)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);

            long idCobro = usarSecuenciasB2
                    ? siguientePkCobroB2(jdbc)
                    : Objects.requireNonNull(jdbc.queryForObject(
                            "SELECT COALESCE(MAX(\"idCobro\"),0)+1 FROM \"COBRO\"", Long.class));
            long idMovCuenta = usarSecuenciasB2
                    ? siguientePkMovCuentaB2(jdbc)
                    : Objects.requireNonNull(jdbc.queryForObject(
                            "SELECT COALESCE(MAX(\"idMovimientoCuenta\"),0)+1 FROM \"MOVIMIENTO_CUENTA\"", Long.class));
            long idMovCaja = usarSecuenciasB2
                    ? siguientePkMovCajaB2(jdbc)
                    : Objects.requireNonNull(jdbc.queryForObject(
                            "SELECT COALESCE(MAX(\"idMovimientoCaja\"),0)+1 FROM \"MOVIMIENTO_CAJA\"", Long.class));

            long seqCliente = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT COALESCE(MAX(\"numeroSecuenciaCliente\"),0)+1 FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=?",
                    Long.class, idCliente));
            BigDecimal saldoPrevio = jdbc.query(
                    "SELECT \"saldoResultante\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY \"numeroSecuenciaCliente\" DESC LIMIT 1",
                    rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO.setScale(2),
                    idCliente);
            BigDecimal saldoFinal = saldoPrevio.subtract(importeCobroCliente);

            UUID claveCobroCliente = UUID.nameUUIDFromBytes(
                    ("K2:" + claveIdempotenciaCierre + ":" + idCliente).getBytes(StandardCharsets.UTF_8));

            jdbc.update("""
                    INSERT INTO "COBRO" (
                        "idCobro","idCliente","idPedido","idUsuarioRegistrador","fechaHoraCobro",
                        "importe","medioPago","origen","claveIdempotenciaOrigen","estadoCobro")
                    VALUES (?,?,NULL,?,clock_timestamp(),?,'EFECTIVO','REPARTO',?,'CONFIRMADO')
                    """, idCobro, idCliente, idAdmin, importeCobroCliente, claveCobroCliente);

            jdbc.update("""
                    INSERT INTO "MOVIMIENTO_CUENTA" (
                        "idMovimientoCuenta","idCliente","numeroSecuenciaCliente","tipoMovimiento",
                        "importe","fechaHoraMovimiento","saldoResultante")
                    VALUES (?,?,?,'PAGO',?,clock_timestamp(),?)
                    """, idMovCuenta, idCliente, seqCliente, importeCobroCliente, saldoFinal);

            jdbc.update("""
                    INSERT INTO "ORIGEN_MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCobro")
                    VALUES (?,?)
                    """, idMovCuenta, idCobro);

            jdbc.update("""
                    INSERT INTO "MOVIMIENTO_CAJA" (
                        "idMovimientoCaja","tipoMovimiento","importe","fechaHoraMovimiento",
                        "origen","concepto","idUsuarioRegistro","idCobro")
                    VALUES (?,'INGRESO',?,clock_timestamp(),'COBRO','Rendicion reparto K2',?,?)
                    """, idMovCaja, importeCobroCliente, idAdmin, idCobro);

            jdbc.update("""
                    INSERT INTO "COBRO_RENDICION_K2" ("idCierreRecorrido","idCliente","idCobro")
                    VALUES (?,?,?)
                    """, cierreId, idCliente, idCobro);

            for (var app : appsCliente) {
                jdbc.update("""
                        INSERT INTO "APLICACION_RENDICION_K2" (
                            "idCierreRecorrido","idCliente","idPedido","idCobro",
                            "importeAplicado","fechaHoraAplicacion")
                        VALUES (?,?,?,?,?,clock_timestamp())
                        """, cierreId, idCliente, app.idPedido(), idCobro, app.importeAplicado());

                jdbc.update("""
                        INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                            "idCliente","idCobro","idPedido","idCierreRecorrido",
                            "tipoEvento","importe","idUsuarioOperador","fechaHoraEvento","observacion")
                        VALUES (?,?,?,?,'APLICACION_RENDICION_K2',?,?,clock_timestamp(),'Aplicacion T1/K2')
                        """, idCliente, idCobro, app.idPedido(), cierreId, app.importeAplicado(), idAdmin);

                jdbc.update("""
                        UPDATE "RECONOCIMIENTO_ENTREGA_PE1"
                           SET "reconocimientoVigenteVvig" = "reconocimientoVigenteVvig" - ?
                         WHERE "idPedido" = ?
                        """, app.importeAplicado(), app.idPedido());
            }
        }
    }

    /**
     * Bloqueante 5 (C-04 / ART-GCON-D1): ejecuta anulacion manual idempotente usando
     * fn_mr3_anular_cobro_manual (L02 64-bit) y ANULACION_COBRO_IDEMPOTENCIA.
     */
    public static ResultadoAnulacionC04Lab ejecutarAnulacionIdempotenteC04EnTx(
            JdbcTemplate jdbc,
            long idCobro,
            long idAdmin,
            UUID claveIdempotenciaAnulacion,
            String motivoRaw) {

        exigirAdminHabilitadoEnLab(jdbc, idAdmin);
        if (motivoRaw == null || motivoRaw.isBlank()) {
            throw new IllegalArgumentException("Motivo de anulacion obligatorio");
        }
        String motivoNormalizado = motivoRaw.trim().replaceAll("\\s+", " ");
        String huella = "C04:v1:" + idCobro + ":" + motivoNormalizado;

        var previas = jdbc.queryForList("""
                SELECT "idCobro","huellaCanonica","idMovimientoCuentaContraasiento",
                       "idMovimientoCajaContraasiento","reciboHistoricoJson"::text AS recibo
                  FROM "ANULACION_COBRO_IDEMPOTENCIA"
                 WHERE "claveIdempotenciaAnulacion" = ?
                """, claveIdempotenciaAnulacion);
        if (!previas.isEmpty()) {
            var row = previas.getFirst();
            if (((Number) row.get("idCobro")).longValue() != idCobro
                    || !huella.equals(row.get("huellaCanonica"))) {
                throw new IllegalStateException(
                        "C04 CONFLICTO 409: claveIdempotenciaAnulacion reutilizada con distinto payload");
            }
            return new ResultadoAnulacionC04Lab(
                    idCobro,
                    ((Number) row.get("idMovimientoCuentaContraasiento")).longValue(),
                    ((Number) row.get("idMovimientoCajaContraasiento")).longValue(),
                    true,
                    (String) row.get("recibo"));
        }

        Long idCliente = jdbc.queryForObject(
                "SELECT \"idCliente\" FROM \"COBRO\" WHERE \"idCobro\"=?", Long.class, idCobro);
        if (idCliente == null) {
            throw new IllegalArgumentException("Cobro inexistente: " + idCobro);
        }

        long idMovCuenta = siguientePkMovCuentaB2(jdbc);
        long idMovCaja = siguientePkMovCajaB2(jdbc);

        jdbc.queryForList("SELECT \"fn_mr3_anular_cobro_manual\"(?,?,?,?,?)",
                idCobro, idAdmin, idMovCuenta, idMovCaja, motivoNormalizado);

        String reciboJson = "{\"idCobro\":" + idCobro
                + ",\"idMovimientoCuenta\":" + idMovCuenta
                + ",\"idMovimientoCaja\":" + idMovCaja
                + ",\"motivo\":\"" + motivoNormalizado + "\"}";

        String reciboNormalizado = jdbc.queryForObject("""
                INSERT INTO "ANULACION_COBRO_IDEMPOTENCIA" (
                    "claveIdempotenciaAnulacion","idCobro","idCliente","idUsuarioAdministrador",
                    "versionHuella","huellaCanonica","motivoNormalizado",
                    "idMovimientoCuentaContraasiento","idMovimientoCajaContraasiento",
                    "fechaHoraAnulacion","reciboHistoricoJson")
                VALUES (?,?,?,?,'v1',?,?,?,?,clock_timestamp(),?::jsonb)
                RETURNING "reciboHistoricoJson"::text
                """,
                String.class,
                claveIdempotenciaAnulacion, idCobro, idCliente, idAdmin,
                huella, motivoNormalizado, idMovCuenta, idMovCaja, reciboJson);

        return new ResultadoAnulacionC04Lab(
                idCobro, idMovCuenta, idMovCaja, false, Objects.requireNonNull(reciboNormalizado));
    }

    /**
     * Evalua las cinco senales literales de preflight RCG-06 (ACTA_G_COM_001 / ADENDA_RD_I302_E07)
     * para un Cliente dado, sin backfill heuristico de COBRO.idPedido.
     */
    public static ResultadoPreflightRcg06 evaluarPreflightRcg06Cliente(JdbcTemplate jdbc, long idCliente) {
        int senalA = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*)
                  FROM "COBRO" c
                  JOIN "PEDIDO" p ON p."idPedido" = c."idPedido"
                 WHERE c."idCliente" = ?
                   AND c."estadoCobro" = 'CONFIRMADO'
                   AND p."estado" = 'PENDIENTE'
                   AND NOT EXISTS (
                       SELECT 1 FROM "EVENTO_IMPUTACION_FINANCIERA" e
                        WHERE e."idCobro" = c."idCobro" AND e."idPedido" = p."idPedido"
                   )
                """, Integer.class, idCliente));

        int senalB = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT CASE
                    WHEN COALESCE((
                        SELECT mc."saldoResultante" FROM "MOVIMIENTO_CUENTA" mc
                         WHERE mc."idCliente" = ?
                         ORDER BY mc."numeroSecuenciaCliente" DESC LIMIT 1
                    ), 0) < 0
                    AND EXISTS (
                        SELECT 1 FROM "PEDIDO" p WHERE p."idCliente" = ? AND p."estado" = 'PENDIENTE'
                    ) THEN 1 ELSE 0 END
                """, Integer.class, idCliente, idCliente));

        int senalC = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*)
                  FROM "REEMBOLSO" r
                  JOIN "COBRO" c ON c."idCobro" = r."idCobro"
                 WHERE c."idCliente" = ?
                   AND r."estado" = 'PENDIENTE'
                """, Integer.class, idCliente));

        int senalD = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*)
                  FROM "COBRO" c
                 WHERE c."idCliente" = ?
                   AND c."estadoCobro" = 'CONFIRMADO'
                   AND c."idPedido" IS NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM "EVENTO_IMPUTACION_FINANCIERA" e
                        WHERE e."idCobro" = c."idCobro"
                   )
                """, Integer.class, idCliente));

        int senalE = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*)
                  FROM "COBRO" c
                 WHERE c."idCliente" = ?
                   AND c."origen" = 'ONLINE'
                   AND c."estadoCobro" = 'CONFIRMADO'
                   AND EXISTS (
                       SELECT 1 FROM "ENTREGA" en
                       JOIN "PEDIDO" p ON p."idPedido" = en."idPedido"
                       LEFT JOIN "RECORRIDO_ENTREGA" re ON re."idPedido" = en."idPedido"
                       LEFT JOIN "CIERRE_RECORRIDO_PE2" cr
                              ON cr."idRecorrido" = re."idRecorrido"
                             AND cr."estadoCierre" IN ('CONFIRMADO', 'CERRADO_CON_DIFERENCIA_RESUELTA')
                        WHERE p."idCliente" = c."idCliente"
                          AND cr."idCierreRecorrido" IS NULL
                   )
                """, Integer.class, idCliente));

        return new ResultadoPreflightRcg06(senalA, senalB, senalC, senalD, senalE);
    }

    public static int backendPid(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
