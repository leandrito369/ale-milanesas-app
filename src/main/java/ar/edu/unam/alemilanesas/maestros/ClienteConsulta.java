package ar.edu.unam.alemilanesas.maestros;

/** Proyección mínima UC-33/BC-07: deliberadamente sin saldo, cobros ni historial financiero. */
public record ClienteConsulta(
        long idCliente,
        String nombre,
        String telefonoPrincipal,
        String direccion,
        String zona,
        String estado,
        String categoriaComercial) {}
