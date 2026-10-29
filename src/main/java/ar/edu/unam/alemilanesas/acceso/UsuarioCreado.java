package ar.edu.unam.alemilanesas.acceso;

import java.util.Set;

/** Salida limitada de UC-17; nunca contiene credenciales ni hashes. */
public record UsuarioCreado(
        long idUsuario,
        String nombreUsuario,
        String estado,
        boolean cambioCredencialPendiente,
        Set<String> roles) {
    public UsuarioCreado {
        roles = Set.copyOf(roles);
    }
}
