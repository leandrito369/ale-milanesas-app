package ar.edu.unam.alemilanesas.acceso;

import java.util.List;

/** UC-56: proyección administrativa sin credenciales ni tokens; G0/REV02. */
public record UsuarioConsulta(
        long idUsuario,
        String nombreUsuario,
        String estado,
        boolean cambioCredencialPendiente,
        List<String> roles,
        Long idProveedor,
        long pedidosPendientesAsignados) {
    public UsuarioConsulta {
        roles = List.copyOf(roles);
    }
}
