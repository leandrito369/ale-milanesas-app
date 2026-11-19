package ar.edu.unam.alemilanesas.gfis;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

    // Namespaces candidatos A2-GLOBAL (G-ARQ-N2-02): dos enteros por dominio/recurso
    public static final int NS_RECORRIDO = 4100;
    public static final int NS_FINANZAS_PUENTE = 2929;
    public static final int NS_CLIENTE = 3202;
    public static final int NS_PEDIDO = 3003;
    public static final int NS_INSUMO = 8081;

    // Lock puente de compatibilidad con escritores legacy (CobroManualService / UC-22)
    public static final long LOCK_LEGACY_FINANCIERO = 2929001L;

    private GfisLabSupport() {}

    public static void aplicarMigracionCandidataV4EnLaboratorio(DataSource dataSource) {
        String sql;
        try {
            sql = new ClassPathResource(RUTA_V4_CANDIDATE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer SQL candidato G-FIS: " + RUTA_V4_CANDIDATE, ex);
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute(sql);
            c.commit();
        } catch (SQLException ex) {
            throw new IllegalStateException("Error aplicando SQL candidato V4 en laboratorio G-FIS", ex);
        }
    }

    public static void bloquearA2(JdbcTemplate jdbc, int namespace, long recursoId) {
        int idInt = Math.toIntExact(recursoId);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", Object.class, namespace, idInt);
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

    /**
     * Registra una ENTREGA + DETALLE_ENTREGA + snapshot PE-1 y la vincula a un Recorrido.
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
    }

    public static long crearRecorridoExplicito(JdbcTemplate jdbc, long idRepartidor, String ref) {
        Long id = jdbc.queryForObject("""
                INSERT INTO "RECORRIDO_REPARTO" ("idUsuarioRepartidor","fechaHoraCreacion","estadoOperativo","referenciaOperativa")
                VALUES (?, clock_timestamp(), 'ABIERTO', ?)
                RETURNING "idRecorrido"
                """, Long.class, idRepartidor, ref);
        return Objects.requireNonNull(id);
    }

    public record AplicacionPlan(long idCliente, long idPedido, BigDecimal importeAplicado) {}

    public record ResultadoCierreLab(long idCierre, String estadoCierre, boolean replay, String reciboJson) {}

    /**
     * Ejecuta un cierre T1 + K2 en una unica transaccion ACID (llamado dentro de TransactionTemplate),
     * respetando el orden total candidato A2-GLOBAL:
     *   A2(NS_RECORRIDO, idRecorrido) -> puente legacy 2929001 (si convive con MAX+1)
     *   -> CLIENTE FOR UPDATE (ASC) -> PEDIDO FOR UPDATE (ASC) -> PE-1 FOR UPDATE (ASC).
     */
    public static ResultadoCierreLab ejecutarCierreT1K2EnTx(
            JdbcTemplate jdbc,
            long idRecorrido,
            long idAdmin,
            UUID claveIdempotencia,
            String versionHuella,
            String huellaCanonica,
            String estadoCierreDestino,
            BigDecimal importeEsperado,
            BigDecimal importeEfectivoRecibido,
            BigDecimal importeDiferenciaFaltante,
            BigDecimal importeCustodiaNoImputada,
            String motivoDiferencia,
            String motivoResolucionAdmin,
            List<AplicacionPlan> aplicaciones,
            boolean usarPuenteLegacy2929001,
            boolean usarSecuenciasB2) {

        bloquearA2(jdbc, NS_RECORRIDO, idRecorrido);
        if (usarPuenteLegacy2929001) {
            bloquearPuenteLegacyFinanciero(jdbc);
        }

        // C1-a: verificacion idempotente por claveIdempotenciaCierre
        var existentes = jdbc.queryForList("""
                SELECT "idCierreRecorrido","estadoCierre","versionHuella","huellaCanonica","reciboHistoricoJson"::text AS recibo
                  FROM "CIERRE_RECORRIDO_PE2"
                 WHERE "claveIdempotenciaCierre" = ?
                """, claveIdempotencia);
        if (!existentes.isEmpty()) {
            var row = existentes.getFirst();
            if (!versionHuella.equals(row.get("versionHuella"))
                    || !huellaCanonica.equals(row.get("huellaCanonica"))) {
                throw new IllegalStateException("C1-a CONFLICTO 409: misma claveIdempotenciaCierre con distinta huella");
            }
            return new ResultadoCierreLab(
                    ((Number) row.get("idCierreRecorrido")).longValue(),
                    (String) row.get("estadoCierre"),
                    true,
                    (String) row.get("recibo"));
        }

        // Orden determinista de locks: CLIENTE ASC -> PEDIDO ASC -> PE-1 ASC
        List<Long> clientesOrdenados = aplicaciones.stream()
                .map(AplicacionPlan::idCliente).distinct().sorted().toList();
        for (long idCliente : clientesOrdenados) {
            bloquearA2(jdbc, NS_CLIENTE, idCliente);
            jdbc.queryForObject("SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE",
                    Long.class, idCliente);
        }
        List<AplicacionPlan> appsOrdenadas = aplicaciones.stream()
                .sorted(Comparator.comparingLong(AplicacionPlan::idPedido)).toList();
        for (var app : appsOrdenadas) {
            jdbc.queryForObject("SELECT \"idPedido\" FROM \"PEDIDO\" WHERE \"idPedido\"=? FOR UPDATE",
                    Long.class, app.idPedido());
            jdbc.queryForObject("SELECT \"idPedido\" FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=? FOR UPDATE",
                    Long.class, app.idPedido());
        }

        BigDecimal totalImputado = appsOrdenadas.stream()
                .map(AplicacionPlan::importeAplicado)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);

        String reciboJson = "{\"idRecorrido\":" + idRecorrido
                + ",\"estado\":\"" + estadoCierreDestino
                + "\",\"imputado\":\"" + totalImputado
                + "\",\"custodia\":\"" + importeCustodiaNoImputada
                + "\",\"faltante\":\"" + importeDiferenciaFaltante + "\"}";

        boolean tieneDiferencia = importeDiferenciaFaltante.signum() > 0 || importeCustodiaNoImputada.signum() > 0;
        Long idAdminRes = ("CONFIRMADO".equals(estadoCierreDestino) && tieneDiferencia) ? idAdmin : null;

        Long idCierre = jdbc.queryForObject("""
                INSERT INTO "CIERRE_RECORRIDO_PE2" (
                    "idRecorrido","claveIdempotenciaCierre","versionHuella","huellaCanonica",
                    "idUsuarioAdministrador","fechaHoraRegistro","estadoCierre","medioPagoPermitido",
                    "importeEsperado","importeEfectivoRecibido","importeImputadoTotal",
                    "importeDiferenciaFaltante","importeCustodiaNoImputada",
                    "motivoDiferencia","idUsuarioResolucionAdmin","motivoResolucionAdmin",
                    "fechaHoraResolucion","reciboHistoricoJson")
                VALUES (?,?,?,?,?,clock_timestamp(),?,'EFECTIVO',?,?,?,?,?,?,?,?,
                        CASE WHEN ?::bigint IS NOT NULL THEN clock_timestamp() ELSE NULL END,
                        ?::jsonb)
                RETURNING "idCierreRecorrido"
                """,
                Long.class,
                idRecorrido, claveIdempotencia, versionHuella, huellaCanonica,
                idAdmin, estadoCierreDestino,
                importeEsperado, importeEfectivoRecibido, totalImputado,
                importeDiferenciaFaltante, importeCustodiaNoImputada,
                motivoDiferencia, idAdminRes, motivoResolucionAdmin,
                idAdminRes, reciboJson);
        long cierreId = Objects.requireNonNull(idCierre);

        // Agrupar aplicaciones por Cliente (K2: 1 COBRO REPARTO por Cliente y Cierre)
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

            // Ordinal de negocio por Cliente bajo lock de CLIENTE (B2-HIBRIDO)
            long seqCliente = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT COALESCE(MAX(\"numeroSecuenciaCliente\"),0)+1 FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=?",
                    Long.class, idCliente));
            BigDecimal saldoPrevio = jdbc.query(
                    "SELECT \"saldoResultante\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY \"numeroSecuenciaCliente\" DESC LIMIT 1",
                    rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO.setScale(2),
                    idCliente);
            BigDecimal saldoFinal = saldoPrevio.subtract(importeCobroCliente);

            // Clave idempotencia distinta por Cliente/Cierre (S04 / L10)
            UUID claveCobroCliente = UUID.nameUUIDFromBytes(
                    ("K2:" + claveIdempotencia + ":" + idCliente).getBytes(StandardCharsets.UTF_8));

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

        if (importeDiferenciaFaltante.signum() > 0) {
            jdbc.update("""
                    INSERT INTO "CUSTODIA_DIFERENCIA_RENDICION" (
                        "idCierreRecorrido","tipoEvento","importe","motivo","idUsuarioAdmin","fechaHoraRegistro")
                    VALUES (?,'FALTANTE_EFECTIVO',?,?,?,clock_timestamp())
                    """, cierreId, importeDiferenciaFaltante, motivoDiferencia, idAdmin);
        }
        if (importeCustodiaNoImputada.signum() > 0) {
            jdbc.update("""
                    INSERT INTO "CUSTODIA_DIFERENCIA_RENDICION" (
                        "idCierreRecorrido","tipoEvento","importe","motivo","idUsuarioAdmin","fechaHoraRegistro")
                    VALUES (?,'CUSTODIA_NO_IMPUTADA',?,?,?,clock_timestamp())
                    """, cierreId, importeCustodiaNoImputada, motivoDiferencia, idAdmin);
        }

        String nuevoEstadoRecorrido = "CONFIRMADO".equals(estadoCierreDestino)
                ? "CERRADO" : "PENDIENTE_CONCILIACION";
        jdbc.update("UPDATE \"RECORRIDO_REPARTO\" SET \"estadoOperativo\"=? WHERE \"idRecorrido\"=?",
                nuevoEstadoRecorrido, idRecorrido);

        return new ResultadoCierreLab(cierreId, estadoCierreDestino, false, reciboJson);
    }

    public static boolean estaBloqueadoPorPid(DataSource ds, int pidEsperando, int pidBloqueador) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ? = ANY(pg_blocking_pids(?))")) {
            ps.setInt(1, pidBloqueador);
            ps.setInt(2, pidEsperando);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static int backendPid(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
