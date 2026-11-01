package ar.edu.unam.alemilanesas.acceso;

import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenSesionInternaTest {
    @Test
    void emiteSecretoDe32BytesYAlmacenaSoloSHA256DeBytesOriginales() throws Exception {
        String a=TokenSesionInterna.nuevo();
        String b=TokenSesionInterna.nuevo();
        assertEquals(43,a.length());
        assertNotEquals(a,b);
        byte[] bytes=Base64.getUrlDecoder().decode(a);
        assertEquals(32,bytes.length);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes),
                TokenSesionInterna.huella(a).orElseThrow());
    }

    @Test
    void formatoNoCanonicoNoSeAcepta() {
        assertTrue(TokenSesionInterna.huella("").isEmpty());
        assertTrue(TokenSesionInterna.huella("a".repeat(43)+"=").isEmpty());
        assertTrue(TokenSesionInterna.huella("a".repeat(42)+"*").isEmpty());
        assertTrue(TokenSesionInterna.huella(java.util.UUID.randomUUID().toString()).isEmpty());
    }
}
