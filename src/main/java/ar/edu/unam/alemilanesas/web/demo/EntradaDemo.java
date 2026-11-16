package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.web.SeguridadHttpInterna.PrincipalInterno;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;

/** Frontera HTML estricta: nunca vincula identidad, roles ni propiedades arbitrarias. */
final class EntradaDemo {
    private EntradaDemo() {}
    static UUID sesion(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof PrincipalInterno p))
            throw new SecurityException("Sesion no valida");
        return p.idSesion();
    }
    static Map<String,String> leer(MultiValueMap<String,String> params, Set<String> campos) {
        Map<String,String> valores = new LinkedHashMap<>();
        campos.forEach(campo -> valores.put(campo,null));
        params.forEach((campo, lista) -> {
            // Validado por Spring Security; Thymeleaf puede emitir hidden explícito y automático.
            if ("_csrf".equals(campo)) return;
            if (!campos.contains(campo) && !"_csrf".equals(campo))
                throw new IllegalArgumentException("Campo no admitido");
            if (lista == null || lista.size() != 1 || lista.getFirst() == null
                    || lista.getFirst().indexOf('\0') >= 0 || lista.getFirst().length() > 1000)
                throw new IllegalArgumentException("Campo invalido");
            if (campos.contains(campo)) valores.put(campo, lista.getFirst());
        });
        return valores;
    }
    static String opcional(Map<String,String> datos, String campo) {
        String valor = datos.get(campo);
        return valor == null || valor.isBlank() ? null : valor;
    }
    static String requerido(Map<String,String> datos, String campo) {
        String valor = opcional(datos, campo);
        if (valor == null) throw new IllegalArgumentException("Campo requerido");
        return valor;
    }
    static Long id(Map<String,String> datos, String campo) {
        String valor = opcional(datos, campo);
        if (valor == null) return null;
        if (!valor.matches("[0-9]+")) throw new IllegalArgumentException("ID invalido");
        long id = Long.parseLong(valor);
        if (id <= 0) throw new IllegalArgumentException("ID invalido");
        return id;
    }
    static long idRequerido(Map<String,String> datos, String campo) {
        Long id = id(datos, campo);
        if (id == null) throw new IllegalArgumentException("ID requerido");
        return id;
    }
    static LocalDate fecha(Map<String,String> datos, String campo) {
        String valor = opcional(datos,campo);
        return valor == null ? null : LocalDate.parse(valor);
    }
}
