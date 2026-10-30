package ar.edu.unam.alemilanesas.finanzas;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Fronteras temporales UC15: días completos, > estricto, sin reloj JVM. */
class EvaluadorCategoriaComercialInternoTest {
    private static final Instant DB_NOW = Instant.parse("2026-10-30T14:00:00Z");

    @Test void toleranciaCeroNoEsMoraDentroDeDiaCero() {
        assertFalse(EvaluadorCategoriaComercialInterno.superaToleranciaDiasCompletos(
                DB_NOW.minus(1, ChronoUnit.MILLIS), DB_NOW, 0));
        assertFalse(EvaluadorCategoriaComercialInterno.superaToleranciaDiasCompletos(
                DB_NOW.minus(23, ChronoUnit.HOURS), DB_NOW, 0));
    }

    @Test void toleranciaCeroCambiaSoloAlPrimerDiaCompleto() {
        assertTrue(EvaluadorCategoriaComercialInterno.superaToleranciaDiasCompletos(
                DB_NOW.minus(24, ChronoUnit.HOURS), DB_NOW, 0));
    }

    @Test void toleranciaSieteEsIgualdadAunConSegundosExtra() {
        assertFalse(EvaluadorCategoriaComercialInterno.superaToleranciaDiasCompletos(
                DB_NOW.minus(7, ChronoUnit.DAYS).minusSeconds(1), DB_NOW, 7));
        assertFalse(EvaluadorCategoriaComercialInterno.superaToleranciaDiasCompletos(
                DB_NOW.minus(7, ChronoUnit.DAYS), DB_NOW, 7));
        assertTrue(EvaluadorCategoriaComercialInterno.superaToleranciaDiasCompletos(
                DB_NOW.minus(8, ChronoUnit.DAYS), DB_NOW, 7));
    }

    @Test void deudaConFechaFuturaNoEsMora() {
        assertFalse(EvaluadorCategoriaComercialInterno.superaToleranciaDiasCompletos(
                DB_NOW.plus(2, ChronoUnit.DAYS), DB_NOW, 0));
    }

    @Test void fechasNulasYUmbralNegativoNoSeAceptan() {
        assertThrows(IllegalArgumentException.class, () ->
                EvaluadorCategoriaComercialInterno.superaToleranciaDiasCompletos(
                        null, DB_NOW, 1));
        assertThrows(IllegalArgumentException.class, () ->
                EvaluadorCategoriaComercialInterno.superaToleranciaDiasCompletos(
                        DB_NOW, null, 1));
        assertThrows(IllegalArgumentException.class, () ->
                EvaluadorCategoriaComercialInterno.superaToleranciaDiasCompletos(
                        DB_NOW, DB_NOW, -1));
    }
}
