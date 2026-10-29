package ar.edu.unam.alemilanesas.acceso;

import java.util.Set;

/**
 * UC-18: resultado de la operación interna.
 * No expone token, credencial ni privilegios fuera de los Roles vigentes.
 */
public record AsignacionesRolesActualizadas(
        long idUsuario, Set<String> rolesVigentes, Set<String> rolesAgregados,
        Set<String> rolesRevocados, boolean huboCambio) {
    public AsignacionesRolesActualizadas {
        rolesVigentes = Set.copyOf(rolesVigentes);
        rolesAgregados = Set.copyOf(rolesAgregados);
        rolesRevocados = Set.copyOf(rolesRevocados);
    }
}
