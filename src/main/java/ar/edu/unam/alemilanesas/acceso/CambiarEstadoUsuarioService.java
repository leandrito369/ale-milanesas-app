package ar.edu.unam.alemilanesas.acceso;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-55 cambiarEstadoUsuario incluye la localizacion por UC-56.
 * Servicio exclusivamente B3; una UUID de sesion no es un token HTTP.
 *
 * Se toman los locks en orden compatible con UC-17/UC-18:
 * 9172001 -> 60505,7 -> USUARIO objetivo FOR UPDATE.
 * MR5 valida invariantes F07 al COMMIT y revoca sesiones en trigger.
 */
@Service
public class CambiarEstadoUsuarioService {
    private static final Set<String> ADMIN = Set.of("ADMINISTRADOR");
    private static final Set<String> ESTADOS = Set.of("HABILITADO", "DESHABILITADO");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public CambiarEstadoUsuarioService(
            NamedParameterJdbcTemplate sql, AutorizacionSesionInterna autorizacion) {
        this.sql = sql;
        this.autorizacion = autorizacion;
    }

    public record CambioEstadoUsuario(long idUsuario, String estado, boolean huboCambio) {}

    /** A4: rechazar sin cambio y exponer ids de Pedidos a reasignar por UC-43. */
    public static class RepartidorConPedidosPendientesException extends IllegalStateException {
        private final List<Long> pedidosPendientes;
        public RepartidorConPedidosPendientesException(List<Long> pedidos) {
            super("Repartidor tiene Pedidos PENDIENTE: reasignar previamente mediante UC-43");
            this.pedidosPendientes = List.copyOf(pedidos);
        }
        public List<Long> pedidosPendientes() {
            return pedidosPendientes;
        }
    }

    @Transactional
    public CambioEstadoUsuario cambiarEstadoUsuario(
            UUID sesionAdministrador, long idUsuario, String nuevoEstado) {
        // Autorizar antes de descubrir existencia de la cuenta objetivo.
        autorizacion.exigirRoles(sesionAdministrador, ADMIN);
        if (idUsuario <= 0 || nuevoEstado == null || !ESTADOS.contains(nuevoEstado)) {
            throw new IllegalArgumentException("Usuario o estado objetivo invalido");
        }

        // Serializa los escritores de identidad que participan del protocolo
        // y estabiliza invariantes de roles/Proveedor antes de consultarlas.
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(9172001)");
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(60505,7)");
        autorizacion.exigirRoles(sesionAdministrador, ADMIN);

        var objetivo = sql.queryForList("""
                SELECT "idUsuario","estado" FROM "USUARIO"
                 WHERE "idUsuario"=:id FOR UPDATE
                """, Map.of("id", idUsuario));
        if (objetivo.size() != 1) {
            throw new IllegalArgumentException("Usuario inexistente");
        }
        String previo = (String) objetivo.getFirst().get("estado");
        if (previo.equals(nuevoEstado)) {
            return new CambioEstadoUsuario(idUsuario, previo, false);
        }

        if ("DESHABILITADO".equals(nuevoEstado)) {
            Integer administradores = sql.queryForObject("""
                    SELECT count(DISTINCT u."idUsuario") FROM "USUARIO" u
                     JOIN "USUARIO_ROL" ur ON ur."idUsuario"=u."idUsuario"
                     WHERE u."estado"='HABILITADO'
                       AND ur."codigoRol"='ADMINISTRADOR'
                    """, Map.of(), Integer.class);
            boolean esAdmin = existeRol(idUsuario, "ADMINISTRADOR");
            if (esAdmin && (administradores == null || administradores <= 1)) {
                throw new IllegalArgumentException(
                        "No puede deshabilitarse el ultimo ADMINISTRADOR habilitado");
            }
            if (existeRol(idUsuario, "REPARTIDOR")) {
                List<Long> pendientes = sql.queryForList("""
                        SELECT DISTINCT p."idPedido" FROM "ASIGNACION_REPARTO" a
                         JOIN "PEDIDO" p ON p."idPedido"=a."idPedido"
                         WHERE a."idUsuarioRepartidor"=:id
                           AND a."fechaHoraFin" IS NULL
                           AND p."estado"='PENDIENTE'
                         ORDER BY p."idPedido"
                        """, Map.of("id", idUsuario), Long.class);
                if (!pendientes.isEmpty()) {
                    throw new RepartidorConPedidosPendientesException(pendientes);
                }
            }
        } else if (existeRol(idUsuario, "PROVEEDOR")) {
            var vinculos = sql.queryForList("""
                    SELECT "idProveedor" FROM "USUARIO_PROVEEDOR"
                     WHERE "idUsuario"=:id
                    """, Map.of("id", idUsuario), Long.class);
            if (vinculos.size() != 1) {
                throw new IllegalStateException("Proveedor sin vinculo valido");
            }
            Integer activos = sql.queryForObject("""
                    SELECT count(*) FROM "USUARIO_PROVEEDOR" up
                     JOIN "USUARIO" u ON u."idUsuario"=up."idUsuario"
                     WHERE up."idProveedor"=:proveedor
                       AND up."idUsuario"<>:id
                       AND u."estado"='HABILITADO'
                    """, Map.of("proveedor", vinculos.getFirst(), "id", idUsuario),
                    Integer.class);
            if (activos == null || activos != 0) {
                throw new IllegalArgumentException(
                        "El Proveedor ya posee otra cuenta habilitada");
            }
        }

        int afectados = sql.update("""
                UPDATE "USUARIO"
                   SET "estado"=:estado,
                       "fechaHoraDeshabilitacion"=CASE
                           WHEN :estado='DESHABILITADO' THEN clock_timestamp()
                           ELSE NULL END
                 WHERE "idUsuario"=:id AND "estado"<>:estado
                """, Map.of("id", idUsuario, "estado", nuevoEstado));
        if (afectados != 1) {
            throw new IllegalStateException("El estado del Usuario cambio durante la operacion");
        }
        // Trigger MR5 tg_mr5_revocar_sesiones_usuario ejecuta la revocacion
        // atomica; no hacer UPDATE duplicado ni borrar USUARIO_ROL.
        return new CambioEstadoUsuario(idUsuario, nuevoEstado, true);
    }

    private boolean existeRol(long idUsuario, String rol) {
        Integer cantidad = sql.queryForObject("""
                SELECT count(*) FROM "USUARIO_ROL"
                 WHERE "idUsuario"=:id AND "codigoRol"=:rol
                """, Map.of("id", idUsuario, "rol", rol), Integer.class);
        return cantidad != null && cantidad > 0;
    }
}
