package ar.edu.unam.alemilanesas.acceso;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/** Caja negra, particiones y límites (AAA) de RD-UC53-001, sin Spring ni DB. */
class PoliticaCambioCredencialTest {
    private final PasswordEncoder encoder = new PasswordEncoder() {
        @Override public String encode(CharSequence raw) { return "hash$" + raw; }
        @Override public boolean matches(CharSequence raw, String encoded) {
            return encoded.equals(encode(raw));
        }
    };
    private final String vigente = encoder.encode("TemporalAnterior");

    @Test
    void pendienteNoExigeCredencialActual() {
        // Arrange: Usuario con clave temporal.
        // Act/Assert: puede cambiarla sin presentar el secreto anterior.
        assertDoesNotThrow(() -> PoliticaCambioCredencial.validar(
                "NuevaTemporal", "NuevaTemporal", null, true, vigente, encoder));
    }

    @Test
    void voluntarioExigeCredencialActualCoincidente() {
        assertDoesNotThrow(() -> PoliticaCambioCredencial.validar(
                "NuevaVoluntaria", "NuevaVoluntaria", "TemporalAnterior", false,
                vigente, encoder));
        assertThrows(IllegalArgumentException.class, () ->
                PoliticaCambioCredencial.validar(
                "NuevaVoluntaria", "NuevaVoluntaria", null, false, vigente, encoder));
        assertThrows(IllegalArgumentException.class, () ->
                PoliticaCambioCredencial.validar(
                "NuevaVoluntaria", "NuevaVoluntaria", "Incorrecta", false, vigente, encoder));
    }

    @Test
    void seRechazaReutilizacionDeClaveTemporal() {
        assertThrows(IllegalArgumentException.class, () ->
                PoliticaCambioCredencial.validar(
                "TemporalAnterior", "TemporalAnterior", null, true, vigente, encoder));
    }

    @Test
    void confirmacionDistintaOAusenteNoTieneEfecto() {
        assertThrows(IllegalArgumentException.class, () ->
                PoliticaCambioCredencial.validar(
                "NuevaTemporal", "OtraTemporal", null, true, vigente, encoder));
        assertThrows(IllegalArgumentException.class, () ->
                PoliticaCambioCredencial.validar(
                "NuevaTemporal", null, null, true, vigente, encoder));
    }

    @Test
    void particionesDeEntradaVaciaBlancaYNulaSonInvalidas() {
        for (String nueva : new String[]{null, "", "   "}) {
            assertThrows(IllegalArgumentException.class, () ->
                    PoliticaCambioCredencial.validar(nueva,nueva,null,true,vigente,encoder));
        }
    }

    @Test
    void fronteraBCryptEnBytes72Y73() {
        String setentaYDos = "a".repeat(72);
        String setentaYTres = "a".repeat(73);
        assertEquals(72,setentaYDos.getBytes(StandardCharsets.UTF_8).length);
        assertDoesNotThrow(() -> PoliticaCambioCredencial.validar(
                setentaYDos,setentaYDos,null,true,vigente,encoder));
        assertThrows(IllegalArgumentException.class, () ->
                PoliticaCambioCredencial.validar(
                setentaYTres,setentaYTres,null,true,vigente,encoder));
        // La longitud de caracteres no basta con caracteres multibyte.
        String multiByte = "á".repeat(37);
        assertThrows(IllegalArgumentException.class, () ->
                PoliticaCambioCredencial.validar(multiByte,multiByte,null,true,vigente,encoder));
    }
}
