package ar.edu.unam.alemilanesas.maestros;

/** Parámetros opcionales de UC-33; filtros independientes, sin autorización implícita. */
public record CriterioBusquedaCliente(
        Long idCliente,
        String nombre,
        String telefonoPrincipal,
        String zona,
        String estado) {}
