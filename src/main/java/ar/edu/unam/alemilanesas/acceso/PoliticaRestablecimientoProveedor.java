package ar.edu.unam.alemilanesas.acceso;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Validaciones puras UC-54; no reemplazan las comprobaciones SQL del vínculo. */
public final class PoliticaRestablecimientoProveedor {
    private PoliticaRestablecimientoProveedor() {}

    public static void validar(boolean identidadComercialVerificada, String temporal,
                                boolean rolExclusivo, boolean vinculoConsistente,
                                boolean cuentaDuplicadaHabilitada) {
        if (!identidadComercialVerificada) {
            throw new IllegalArgumentException("Identidad comercial no confirmada");
        }
        if (temporal == null || temporal.isBlank()
                || temporal.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Credencial temporal inválida");
        }
        if (!rolExclusivo || !vinculoConsistente || cuentaDuplicadaHabilitada) {
            throw new IllegalArgumentException("Rol PROVEEDOR y vínculo comercial inconsistentes");
        }
    }
}
