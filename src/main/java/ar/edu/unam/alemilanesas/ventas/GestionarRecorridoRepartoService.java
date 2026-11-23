package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import ar.edu.unam.alemilanesas.finanzas.ProtocoloIdentificadoresFinancieros;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C1-EXPLICITO (RD-GFIS-AB-001): gestion explicita de RECORRIDO_REPARTO (FA-1),
 * pertenencia pre-ENTREGA en RECORRIDO_ASIGNACION (A1) y control FA-2 al cerrar.
 * Nunca deriva Recorrido simplemente a partir de Repartidor + fecha.
 */
@Service
public class GestionarRecorridoRepartoService {
    private static final String VENDEDOR = "VENDEDOR";
    private static final String INVALIDA = "SOLICITUD_INVALIDA";

    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    private final ProtocoloIdentificadoresFinancieros identificadores;
    private final AsignarRepartoService asignaciones;

    public GestionarRecorridoRepartoService(
            NamedParameterJdbcTemplate sql,
            AutorizacionSesionInterna autorizacion,
            ProtocoloIdentificadoresFinancieros identificadores,
            AsignarRepartoService asignaciones) {
        this.sql = sql;
        this.autorizacion = autorizacion;
        this.identificadores = identificadores;
        this.asignaciones = asignaciones;
    }

    public record RecorridoAbierto(
            long idRecorrido,
            long idUsuarioRepartidor,
            String estadoOperativo,
            String referenciaOperativa,
            OffsetDateTime fechaHoraCreacion) {}

    public record VinculacionRecorrido(
            long idRecorridoAsignacion,
            long idRecorrido,
            long idPedido,
            int numeroAsignacion,
            String estadoPertenencia,
            OffsetDateTime fechaHoraVinculacion) {}

    @Transactional
    public RecorridoAbierto abrirRecorrido(UUID sesion, long idUsuarioRepartidor, String referenciaOperativa) {
        autorizacion.exigirRoles(sesion, Set.of(VENDEDOR));
        if (idUsuarioRepartidor <= 0) throw new IllegalArgumentException(INVALIDA);
        String ref = null;
        if (referenciaOperativa != null) {
            ref = referenciaOperativa.trim();
            if (ref.isEmpty() || ref.length() > 120 || ref.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(INVALIDA);
            }
        }

        identificadores.bloquearRepartidorA2(idUsuarioRepartidor);
        autorizacion.exigirRoles(sesion, Set.of(VENDEDOR));
        exigirRepartidorHabilitado(idUsuarioRepartidor);

        Integer abierto = sql.queryForObject("""
                SELECT count(*) FROM "RECORRIDO_REPARTO"
                 WHERE "idUsuarioRepartidor" = :rep AND "estadoOperativo" = 'ABIERTO'
                """, Map.of("rep", idUsuarioRepartidor), Integer.class);
        if (abierto != null && abierto > 0) {
            throw new IllegalStateException("RECORRIDO_ABIERTO_EXISTENTE");
        }

        long idRecorrido = identificadores.siguienteRecorrido();
        OffsetDateTime ahora = Objects.requireNonNull(
                sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", OffsetDateTime.class));
        sql.update("""
                INSERT INTO "RECORRIDO_REPARTO" (
                    "idRecorrido","idUsuarioRepartidor","fechaHoraCreacion","estadoOperativo","referenciaOperativa")
                VALUES (:id,:rep,:ahora,'ABIERTO',:ref)
                """, new MapSqlParameterSource()
                .addValue("id", idRecorrido)
                .addValue("rep", idUsuarioRepartidor)
                .addValue("ahora", ahora)
                .addValue("ref", ref));
        return new RecorridoAbierto(idRecorrido, idUsuarioRepartidor, "ABIERTO", ref, ahora);
    }

    /**
     * Vincula una ASIGNACION_REPARTO vigente de UC-43 a un Recorrido ABIERTO explicito.
     */
    @Transactional
    public VinculacionRecorrido vincularPedidoARecorrido(UUID sesion, long idRecorrido, long idPedido) {
        autorizacion.exigirRoles(sesion, Set.of(VENDEDOR));
        if (idRecorrido <= 0 || idPedido <= 0) throw new IllegalArgumentException(INVALIDA);

        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(9172001)");
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(60505,7)");
        autorizacion.exigirRoles(sesion, Set.of(VENDEDOR));

        return vincularPedidoInterno(idRecorrido, idPedido);
    }

    /**
     * Operacion compuesta atomica UC-43 + C1-EXPLICITO: asigna o reasigna en ASIGNACION_REPARTO
     * (desvinculando cualquier pertenencia ACTIVA previa) y vincula la asignacion vigente al idRecorrido explicito.
     */
    @Transactional
    public VinculacionRecorrido asignarYVincularRecorrido(
            UUID sesion,
            AsignarRepartoService.DatosAsignacion datos,
            long idRecorrido) {
        autorizacion.exigirRoles(sesion, Set.of(VENDEDOR));
        if (datos == null || idRecorrido <= 0) throw new IllegalArgumentException(INVALIDA);

        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(9172001)");
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(60505,7)");
        autorizacion.exigirRoles(sesion, Set.of(VENDEDOR));

        asignaciones.asignarOReasignar(sesion, datos);

        var prevActiva = sql.queryForList("""
                SELECT "idRecorridoAsignacion"
                  FROM "RECORRIDO_ASIGNACION"
                 WHERE "idPedido" = :pedido AND "estadoPertenencia" = 'ACTIVA'
                 FOR UPDATE
                """, Map.of("pedido", datos.idPedido()));

        if (!prevActiva.isEmpty()) {
            long idRaPrevia = ((Number) prevActiva.getFirst().get("idRecorridoAsignacion")).longValue();
            OffsetDateTime ahoraDesv = Objects.requireNonNull(
                    sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", OffsetDateTime.class));
            String motivoDesv = datos.motivoReasignacion() == null || datos.motivoReasignacion().isBlank()
                    ? "Reasignacion UC-43" : datos.motivoReasignacion().trim();
            sql.update("""
                    UPDATE "RECORRIDO_ASIGNACION"
                       SET "estadoPertenencia" = 'DESVINCULADA_POR_REASIGNACION',
                           "fechaHoraDesvinculacion" = :ahora,
                           "motivoDesvinculacion" = :motivo
                     WHERE "idRecorridoAsignacion" = :id
                    """, Map.of("ahora", ahoraDesv, "motivo", motivoDesv, "id", idRaPrevia));
        }

        return vincularPedidoInterno(idRecorrido, datos.idPedido());
    }

    /**
     * Guard fail-closed de cierre operativo sin PE-2 (RD-GFIS-AB-001 §B1-R1 / FA-2):
     * - Si existen Pedidos PENDIENTE activos -> FA-2 (FA2_PEDIDOS_PENDIENTES_ACTIVOS).
     * - Si existen RECORRIDO_ENTREGA o PE-1 sin CIERRE_RECORRIDO_PE2 terminal -> RENDICION_PE2_PENDIENTE.
     * - Recorridos vacios/desvinculados sin Entregas tampoco cierran sin PE-2 por ausencia de
     *   contrato ratificado para cierre con cero aplicaciones (CIERRE_SIN_PE2_NO_AUTORIZADO).
     */
    @Transactional
    public void cerrarRecorridoSinPendientes(UUID sesion, long idRecorrido) {
        autorizacion.exigirRoles(sesion, Set.of(VENDEDOR, "ADMINISTRADOR"));
        if (idRecorrido <= 0) throw new IllegalArgumentException(INVALIDA);

        identificadores.bloquearRecorridoA2(idRecorrido);
        autorizacion.exigirRoles(sesion, Set.of(VENDEDOR, "ADMINISTRADOR"));

        var rec = sql.queryForList("""
                SELECT "estadoOperativo"
                  FROM "RECORRIDO_REPARTO"
                 WHERE "idRecorrido" = :rec
                 FOR UPDATE
                """, Map.of("rec", idRecorrido));
        if (rec.isEmpty()) throw new IllegalArgumentException("RECORRIDO_NO_ENCONTRADO");
        if (!"ABIERTO".equals(rec.getFirst().get("estadoOperativo"))) {
            throw new IllegalStateException("RECORRIDO_NO_ABIERTO");
        }

        Integer pendientes = sql.queryForObject("""
                SELECT count(*)
                  FROM "RECORRIDO_ASIGNACION" ra
                  JOIN "PEDIDO" p ON p."idPedido" = ra."idPedido"
                 WHERE ra."idRecorrido" = :rec
                   AND ra."estadoPertenencia" = 'ACTIVA'
                   AND p."estado" = 'PENDIENTE'
                """, Map.of("rec", idRecorrido), Integer.class);
        if (pendientes != null && pendientes > 0) {
            throw new IllegalStateException("FA2_PEDIDOS_PENDIENTES_ACTIVOS");
        }

        Boolean requiereRendicionPe2 = sql.queryForObject("""
                SELECT (
                    EXISTS (
                        SELECT 1
                          FROM "RECORRIDO_ENTREGA" re
                         WHERE re."idRecorrido" = :rec
                    )
                    OR EXISTS (
                        SELECT 1
                          FROM "RECORRIDO_ASIGNACION" ra
                          JOIN "RECONOCIMIENTO_ENTREGA_PE1" pe1
                            ON pe1."idPedido" = ra."idPedido"
                         WHERE ra."idRecorrido" = :rec
                           AND ra."estadoPertenencia" IN ('ACTIVA', 'ENTREGADA')
                    )
                )
                AND NOT EXISTS (
                    SELECT 1
                      FROM "CIERRE_RECORRIDO_PE2" cr
                     WHERE cr."idRecorrido" = :rec
                       AND cr."estadoCierre" IN ('CONFIRMADO', 'CERRADO_CON_DIFERENCIA_RESUELTA')
                )
                """, Map.of("rec", idRecorrido), Boolean.class);
        if (Boolean.TRUE.equals(requiereRendicionPe2)) {
            throw new IllegalStateException("RENDICION_PE2_PENDIENTE");
        }

        throw new IllegalStateException("CIERRE_SIN_PE2_NO_AUTORIZADO");
    }

    private VinculacionRecorrido vincularPedidoInterno(long idRecorrido, long idPedido) {
        var pedRows = sql.queryForList("""
                SELECT "estado" FROM "PEDIDO" WHERE "idPedido" = :pedido FOR SHARE
                """, Map.of("pedido", idPedido));
        if (pedRows.isEmpty()) throw new IllegalArgumentException("PEDIDO_NO_ENCONTRADO");
        if (!"PENDIENTE".equals(pedRows.getFirst().get("estado"))) {
            throw new IllegalStateException("PEDIDO_NO_PENDIENTE");
        }

        var asigRows = sql.queryForList("""
                SELECT "numeroAsignacion","idUsuarioRepartidor"
                  FROM "ASIGNACION_REPARTO"
                 WHERE "idPedido" = :pedido AND "fechaHoraFin" IS NULL
                 FOR UPDATE
                """, Map.of("pedido", idPedido));
        if (asigRows.isEmpty()) throw new IllegalStateException("ASIGNACION_VIGENTE_REQUERIDA");
        int numAsig = ((Number) asigRows.getFirst().get("numeroAsignacion")).intValue();
        long repAsig = ((Number) asigRows.getFirst().get("idUsuarioRepartidor")).longValue();

        var recRows = sql.queryForList("""
                SELECT "idUsuarioRepartidor","estadoOperativo"
                  FROM "RECORRIDO_REPARTO"
                 WHERE "idRecorrido" = :rec
                 FOR SHARE
                """, Map.of("rec", idRecorrido));
        if (recRows.isEmpty()) throw new IllegalArgumentException("RECORRIDO_NO_ENCONTRADO");
        if (!"ABIERTO".equals(recRows.getFirst().get("estadoOperativo"))) {
            throw new IllegalStateException("RECORRIDO_NO_ABIERTO");
        }
        long repRec = ((Number) recRows.getFirst().get("idUsuarioRepartidor")).longValue();
        if (repRec != repAsig) {
            throw new IllegalArgumentException("REPARTIDOR_RECORRIDO_NO_COINCIDE");
        }

        Integer yaActiva = sql.queryForObject("""
                SELECT count(*) FROM "RECORRIDO_ASIGNACION"
                 WHERE "idPedido" = :pedido AND "estadoPertenencia" = 'ACTIVA'
                """, Map.of("pedido", idPedido), Integer.class);
        if (yaActiva != null && yaActiva > 0) {
            throw new IllegalStateException("PERTENENCIA_ACTIVA_DUPLICADA");
        }

        long idRa = identificadores.siguienteRecorridoAsignacion();
        OffsetDateTime ahora = Objects.requireNonNull(
                sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", OffsetDateTime.class));
        sql.update("""
                INSERT INTO "RECORRIDO_ASIGNACION" (
                    "idRecorridoAsignacion","idRecorrido","idPedido","numeroAsignacion",
                    "estadoPertenencia","fechaHoraVinculacion","fechaHoraDesvinculacion","motivoDesvinculacion")
                VALUES (:id,:rec,:pedido,:num,'ACTIVA',:ahora,NULL,NULL)
                """, new MapSqlParameterSource()
                .addValue("id", idRa)
                .addValue("rec", idRecorrido)
                .addValue("pedido", idPedido)
                .addValue("num", numAsig)
                .addValue("ahora", ahora));
        return new VinculacionRecorrido(idRa, idRecorrido, idPedido, numAsig, "ACTIVA", ahora);
    }

    private void exigirRepartidorHabilitado(long idUsuarioRepartidor) {
        var filas = sql.queryForList("""
                SELECT u."estado",
                       EXISTS(SELECT 1 FROM "USUARIO_ROL" r
                               WHERE r."idUsuario" = u."idUsuario" AND r."codigoRol" = 'REPARTIDOR') AS ok
                  FROM "USUARIO" u
                 WHERE u."idUsuario" = :rep
                """, Map.of("rep", idUsuarioRepartidor));
        if (filas.isEmpty()) throw new IllegalArgumentException("Repartidor inexistente");
        if (!"HABILITADO".equals(filas.getFirst().get("estado"))) {
            throw new IllegalArgumentException("Repartidor no habilitado");
        }
        if (!Boolean.TRUE.equals(filas.getFirst().get("ok"))) {
            throw new IllegalArgumentException("Usuario sin rol REPARTIDOR");
        }
    }
}
