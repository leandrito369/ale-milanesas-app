package ar.edu.unam.alemilanesas.acceso;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-18 / RD-UC18-001: gestionar las asociaciones vigentes Usuario–Rol.
 *
 * Solo B3 interno, sin endpoint HTTP. El UUID de sesión no es autenticación
 * remota: la validación criptográfica pertenece a B4.
 *
 * Los locks se adquieren en el MISMO orden que UC-17: identificadores
 * (9172001), luego invariante F07/MR5 (60505,7).
 */
@Service
public class GestionarAsignacionesRolesService {
    private static final Set<String> ADMIN = Set.of("ADMINISTRADOR");
    private static final String PROVEEDOR = "PROVEEDOR";
    private static final String ADMINISTRADOR = "ADMINISTRADOR";

    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public GestionarAsignacionesRolesService(
            NamedParameterJdbcTemplate sql, AutorizacionSesionInterna autorizacion) {
        this.sql = sql;
        this.autorizacion = autorizacion;
    }

    /**
     * A1–A5: altas/bajas verificadas, no-op sin eventos, conservación de
     * último Rol y Administrador HABILITADO, auditoría y revocación de sesiones.
     */
    @Transactional
    public AsignacionesRolesActualizadas gestionarAsignacionesRoles(
            UUID sesionAdministrador,
            long idUsuario,
            Set<String> rolesAgregar,
            Set<String> rolesQuitar) {
        // No hacer pública la existencia de Usuarios frente a invocaciones no autorizadas.
        autorizacion.exigirRoles(sesionAdministrador, ADMIN);
        if (idUsuario <= 0) {
            throw new IllegalArgumentException("Usuario objetivo inválido");
        }
        Set<String> agregar = copiarRoles(rolesAgregar);
        Set<String> quitar = copiarRoles(rolesQuitar);
        Set<String> solapados = new HashSet<>(agregar);
        solapados.retainAll(quitar);
        if (!solapados.isEmpty()) {
            throw new IllegalArgumentException("No puede agregarse y quitarse el mismo Rol");
        }

        // Se serializan los comandos UC17/UC18 que comparten generador
        // MAX(idEventoRol)+1. Se toma la cerradura F07 ANTES de leer el estado.
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(9172001)");
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(60505, 7)");
        autorizacion.exigirRoles(sesionAdministrador, ADMIN);

        var usuario = sql.queryForList("""
                SELECT "idUsuario", "estado" FROM "USUARIO"
                WHERE "idUsuario"=:id FOR UPDATE
                """, Map.of("id", idUsuario));
        if (usuario.size() != 1) {
            throw new IllegalArgumentException("Usuario objetivo inexistente");
        }
        Long adminId = sql.queryForObject("""
                SELECT "idUsuario" FROM "SESION_USUARIO"
                WHERE "idSesionUsuario"=:sesion
                """, Map.of("sesion", sesionAdministrador), Long.class);
        if (adminId == null) {
            throw new SecurityException("Operación no autorizada");
        }
        Set<String> vigentes = new TreeSet<>(sql.queryForList("""
                SELECT "codigoRol" FROM "USUARIO_ROL" WHERE "idUsuario"=:id
                ORDER BY "codigoRol"
                """, Map.of("id", idUsuario), String.class));

        Set<String> aAgregar = new TreeSet<>(agregar);
        aAgregar.removeAll(vigentes);
        Set<String> aQuitar = new TreeSet<>(quitar);
        aQuitar.retainAll(vigentes);

        Set<String> finales = new TreeSet<>(vigentes);
        finales.removeAll(aQuitar);
        finales.addAll(aAgregar);
        if (finales.isEmpty()) {
            throw new IllegalArgumentException("El Usuario debe conservar al menos un Rol");
        }
        // UC-18 no incluye idProveedor: no inventar altas/bajas de vínculo
        // USUARIO_PROVEEDOR. Se conserva la exclusividad existente.
        if (finales.contains(PROVEEDOR) && finales.size() != 1) {
            throw new IllegalArgumentException("PROVEEDOR es un Rol exclusivo");
        }
        if (vigentes.contains(PROVEEDOR) != finales.contains(PROVEEDOR)) {
            throw new IllegalArgumentException(
                    "El cambio de vinculación PROVEEDOR requiere contrato explícito");
        }

        if (!aAgregar.isEmpty()) {
            Integer existentes = sql.queryForObject("""
                    SELECT count(*) FROM "ROL" WHERE "codigoRol" IN (:roles)
                    """, new MapSqlParameterSource("roles", aAgregar), Integer.class);
            if (existentes == null || existentes != aAgregar.size()) {
                throw new IllegalArgumentException("Rol inexistente en catálogo");
            }
        }
        // F07: una operación que retire ADMINISTRADOR de una cuenta habilitada
        // jamás puede suprimir al último administrador habilitado.
        if (aQuitar.contains(ADMINISTRADOR)
                && "HABILITADO".equals(usuario.getFirst().get("estado"))) {
            Integer administradores = sql.getJdbcTemplate().queryForObject("""
                    SELECT count(DISTINCT ur."idUsuario")
                    FROM "USUARIO_ROL" ur
                    JOIN "USUARIO" u ON u."idUsuario"=ur."idUsuario"
                    WHERE ur."codigoRol"='ADMINISTRADOR' AND u."estado"='HABILITADO'
                    """, Integer.class);
            if (administradores == null || administradores <= 1) {
                throw new IllegalArgumentException(
                        "No puede revocarse el último ADMINISTRADOR habilitado");
            }
        }

        // A3: no crear ni eliminar asociaciones ni emitir eventos inexistentes;
        // por lo tanto, los triggers MR5 tampoco revocan sesiones ante no-op.
        for (String rol : aQuitar) {
            int deleted = sql.update("""
                    DELETE FROM "USUARIO_ROL"
                     WHERE "idUsuario"=:id AND "codigoRol"=:rol
                    """, Map.of("id", idUsuario, "rol", rol));
            if (deleted != 1) {
                throw new IllegalStateException("La asignación cambió durante la operación");
            }
            registrarEvento(idUsuario, rol, "REVOCACION", adminId);
        }
        for (String rol : aAgregar) {
            sql.update("""
                    INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                    VALUES (:id,:rol,clock_timestamp())
                    """, Map.of("id", idUsuario, "rol", rol));
            registrarEvento(idUsuario, rol, "ASIGNACION", adminId);
        }
        // Los triggers de MR5 revocan las sesiones al INSERT/DELETE de
        // USUARIO_ROL. No duplicar la actualización ni el evento en Java.
        // Los constraint triggers diferidos verifican además último Rol,
        // último Administrador y exclusividad PROVEEDOR al COMMIT.
        return new AsignacionesRolesActualizadas(
                idUsuario, finales, aAgregar, aQuitar, !aAgregar.isEmpty() || !aQuitar.isEmpty());
    }

    private void registrarEvento(long idUsuario, String rol, String tipo, long administrador) {
        Long siguiente = sql.getJdbcTemplate().queryForObject(
                "SELECT COALESCE(MAX(\"idEventoRol\"),0)+1 FROM \"EVENTO_ASIGNACION_ROL\"",
                Long.class);
        if (siguiente == null || siguiente <= 0) {
            throw new IllegalStateException("No es posible asignar identificador de evento");
        }
        sql.update("""
                INSERT INTO "EVENTO_ASIGNACION_ROL"
                    ("idEventoRol","idUsuario","codigoRol","tipoEvento",
                     "fechaHoraEvento","idUsuarioAdministrador")
                VALUES (:idEvento,:idUsuario,:rol,:tipo,clock_timestamp(),:admin)
                """, new MapSqlParameterSource()
                .addValue("idEvento", siguiente)
                .addValue("idUsuario", idUsuario)
                .addValue("rol", rol)
                .addValue("tipo", tipo)
                .addValue("admin", administrador));
    }

    private static Set<String> copiarRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Collections.emptySet();
        }
        if (roles.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Rol nulo inválido");
        }
        return Set.copyOf(roles);
    }
}
