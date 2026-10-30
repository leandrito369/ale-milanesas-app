package ar.edu.unam.alemilanesas.acceso;

import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * UC-53 / RD-UC53-001: reglas testables de cambio de clave.
 * Validaciones técnicas BCrypt (72 bytes) sin agregar política funcional
 * de composición no ratificada. Ningún mensaje incluye secretos.
 */
public final class PoliticaCambioCredencial {
    private PoliticaCambioCredencial() {}

    public static void validar(String nueva, String confirmacion, String actual,
                               boolean cambioPendiente, String hashVigente,
                               PasswordEncoder encoder) {
        if (nueva == null || nueva.isBlank()
                || nueva.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Nueva credencial inválida");
        }
        if (confirmacion == null || !nueva.equals(confirmacion)) {
            throw new IllegalArgumentException("La confirmación no coincide");
        }
        // La nueva credencial no debe ser igual a la vigente, incluida
        // la clave temporal que se está sustituyendo (A2).
        if (encoder.matches(nueva, hashVigente)) {
            throw new IllegalArgumentException("La nueva credencial debe ser distinta");
        }
        if (!cambioPendiente) {
            if (actual == null || actual.isEmpty()
                    || !encoder.matches(actual, hashVigente)) {
                throw new IllegalArgumentException("Credencial actual no válida");
            }
        }
        // cambioPendiente=true: no se exige credencialActual, aunque se haya
        // incluido opcionalmente en la firma (ficha Real Paso 3).
    }
}
