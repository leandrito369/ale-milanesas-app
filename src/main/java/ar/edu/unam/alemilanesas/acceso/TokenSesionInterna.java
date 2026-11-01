package ar.edu.unam.alemilanesas.acceso;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/** B4-02A/AUTH-01..04: token opaco, nunca UUID ni contrasena. */
public final class TokenSesionInterna {
    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenSesionInterna() {}

    public static String nuevo() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static Optional<byte[]> huella(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        byte[] secreto;
        try { secreto = Base64.getUrlDecoder().decode(token); }
        catch (IllegalArgumentException ex) { return Optional.empty(); }
        if (secreto.length != 32 ||
                !Base64.getUrlEncoder().withoutPadding().encodeToString(secreto).equals(token)) {
            return Optional.empty();
        }
        try { return Optional.of(MessageDigest.getInstance("SHA-256").digest(secreto)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 no disponible", ex); }
    }

    public static final class AccesoRechazado extends RuntimeException {
        public AccesoRechazado() { super("Acceso rechazado"); }
    }
}
