package ar.edu.unam.alemilanesas.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LimiteIntentosLoginTest {
    @Test
    void unaCuentaValidaNoLimpiaElPresupuestoDeUnaDireccion() {
        var limite=new LimiteIntentosLogin();
        String origen="192.0.2.83";
        for(int i=0;i<4;i++) {
            String usuario="desconocido_"+i;
            limite.verificar(usuario,origen);
            limite.fallido(usuario,origen);
        }
        limite.exitoso("cuenta_conocida",origen);
        limite.verificar("otro_desconocido",origen);
        limite.fallido("otro_desconocido",origen);
        assertThrows(LimiteIntentosLogin.IntentosExcedidos.class,
                ()->limite.verificar("cuenta_distinta",origen));
    }

    @Test
    void demasiadosIntentosSobreUnaCuentaDesdeDistintosOrigenesBloquean() {
        var limite=new LimiteIntentosLogin();
        for(int i=0;i<5;i++) {
            String origen="198.51.100."+i;
            limite.verificar("objetivo",origen);
            limite.fallido("objetivo",origen);
        }
        assertThrows(LimiteIntentosLogin.IntentosExcedidos.class,
                ()->limite.verificar("OBJETIVO","203.0.113.25"));
    }
}
