package ar.edu.unam.alemilanesas.acceso;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** UC54 — particiones equivalentes/valores límite sin Spring (AAA). */
class PoliticaRestablecimientoProveedorTest {
    @Test void a1RechazaIdentidadNoConfirmada() {
        assertThrows(IllegalArgumentException.class, () ->
            PoliticaRestablecimientoProveedor.validar(false,"TemporalSegura_01",true,true,false));
    }
    @Test void validoConProveedorExclusivoYVinculo() {
        assertDoesNotThrow(() ->
            PoliticaRestablecimientoProveedor.validar(true,"TemporalSegura_01",true,true,false));
    }
    @Test void a2RechazaRolMixtoOVinculoAusenteOCuentaDuplicada() {
        assertThrows(IllegalArgumentException.class, () ->
            PoliticaRestablecimientoProveedor.validar(true,"TemporalSegura_01",false,true,false));
        assertThrows(IllegalArgumentException.class, () ->
            PoliticaRestablecimientoProveedor.validar(true,"TemporalSegura_01",true,false,false));
        assertThrows(IllegalArgumentException.class, () ->
            PoliticaRestablecimientoProveedor.validar(true,"TemporalSegura_01",true,true,true));
    }
    @Test void credencialesVaciasNulasYBlancasInvalidas() {
        for (var value : new String[]{null,""," "}) {
            assertThrows(IllegalArgumentException.class, () ->
                PoliticaRestablecimientoProveedor.validar(true,value,true,true,false));
        }
    }
    @Test void fronteraTecnicaBCryptPorBytes() {
        String limite="a".repeat(72),fuera="a".repeat(73),multibyte="á".repeat(37);
        assertDoesNotThrow(() ->
            PoliticaRestablecimientoProveedor.validar(true,limite,true,true,false));
        for (String invalid : new String[]{fuera,multibyte}) {
            assertThrows(IllegalArgumentException.class, () ->
                PoliticaRestablecimientoProveedor.validar(true,invalid,true,true,false));
        }
    }
}
