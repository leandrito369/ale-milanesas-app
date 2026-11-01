package ar.edu.unam.alemilanesas.web;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Defensa local inicial, 5 fallos/15 min por usuario e IP.
 * NO es un limitador distribuido: despliegue multinstancia requiere control
 * compartido/ingress antes del gate B4-02C. No confiar en X-Forwarded-For.
 */
@Component
public class LimiteIntentosLogin {
    private static final int MAX_INTENTOS=5;
    private static final long VENTANA=TimeUnit.MINUTES.toNanos(15);
    private final ConcurrentHashMap<String,Estado> estados=new ConcurrentHashMap<>();

    private record Estado(int fallos, long hasta) {}

    public static final class IntentosExcedidos extends RuntimeException {}

    private static String usuario(String nombre) {
        if (nombre==null) return "-";
        String limpio=nombre.strip().toLowerCase(Locale.ROOT);
        return limpio.length()>80?limpio.substring(0,80):limpio;
    }

    private static String ip(String ip) {return ip==null?"unknown":ip;}

    public void verificar(String nombre,String origen) {
        long ahora=System.nanoTime();
        if (estados.size()>4096) estados.entrySet().removeIf(
                e -> ahora-e.getValue().hasta()>=0);
        if (estados.size()>8192) throw new IntentosExcedidos();
        for (String llave : new String[]{"U:"+usuario(nombre),"I:"+ip(origen)}) {
            Estado e=estados.get(llave);
            if (e!=null && e.fallos()>=MAX_INTENTOS && ahora-e.hasta()<0)
                throw new IntentosExcedidos();
        }
    }

    public void fallido(String nombre,String origen) {
        long ahora=System.nanoTime();
        for (String llave:new String[]{"U:"+usuario(nombre),"I:"+ip(origen)}) {
            estados.compute(llave,(k,anterior)->{
                if (anterior==null || ahora-anterior.hasta()>=0)
                    return new Estado(1,ahora+VENTANA);
                return new Estado(anterior.fallos()+1,anterior.hasta());
            });
        }
    }

    public void exitoso(String nombre,String origen) {
        estados.remove("U:"+usuario(nombre));
        estados.remove("I:"+ip(origen));
    }
}
