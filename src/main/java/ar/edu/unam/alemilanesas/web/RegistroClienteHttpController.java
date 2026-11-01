package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.GestionClientesService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * B4-03-01 / UC02: adaptador de prueba, NO activado en producción.
 * La autorización existe en el filtro HTTP y se revalida en transacción B3.
 * Feature flag false por defecto y prohibida por el perfil prod.
 */
@RestController
@RequestMapping("/api/v1")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes", name="clientes-uc02-enabled",
        havingValue="true")
public class RegistroClienteHttpController {
    private static final Set<String> CAMPOS=Set.of(
            "nombres","apellidos","telefonoPrincipal","calle","numeroPuerta",
            "piso","unidadFuncional","localidad","provincia",
            "referenciaDomicilio","zona","diaHabitualReparto");
    private final GestionClientesService clientes;

    public RegistroClienteHttpController(GestionClientesService clientes) {
        this.clientes=clientes;
    }

    /** No devuelve datos ajenos ni advertencias con identificadores internos BC07. */
    public record AltaConfirmada(long idCliente, String estado, String categoriaComercial,
                                 boolean advertenciaCoincidenciaParcial) {}

    @PostMapping(path="/clientes",consumes="application/json",produces="application/json")
    public ResponseEntity<AltaConfirmada> registrar(Authentication auth,
                                                     @RequestBody Map<String,Object> datos) {
        if (datos==null || !CAMPOS.containsAll(datos.keySet())) {
            throw new IllegalArgumentException("Campos de alta inválidos");
        }
        UUID sesion=sesionAutenticada(auth);
        var dto=new GestionClientesService.DatosCliente(
                texto(datos,"nombres"),texto(datos,"apellidos"),
                texto(datos,"telefonoPrincipal"),texto(datos,"calle"),
                texto(datos,"numeroPuerta"),texto(datos,"piso"),
                texto(datos,"unidadFuncional"),texto(datos,"localidad"),
                texto(datos,"provincia"),texto(datos,"referenciaDomicilio"),
                texto(datos,"zona"),dia(datos));
        var r=clientes.registrarCliente(sesion,dto);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new AltaConfirmada(r.idCliente(),r.estado(),r.categoriaComercial(),
                        !r.advertenciasParciales().isEmpty()));
    }

    private static UUID sesionAutenticada(Authentication auth) {
        if (auth==null ||
                !(auth.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sin sesión válida");
        }
        return actor.idSesion();
    }

    private static String texto(Map<String,Object> datos,String nombre) {
        Object valor=datos.get(nombre);
        if(valor==null) return null;
        if(!(valor instanceof String cadena)) {
            throw new IllegalArgumentException("Tipo de campo incorrecto");
        }
        return cadena;
    }

    private static Short dia(Map<String,Object> datos) {
        Object valor=datos.get("diaHabitualReparto");
        if (valor==null) return null;
        if (!(valor instanceof Integer entero) || entero<1 || entero>7) {
            throw new IllegalArgumentException("Día inválido");
        }
        return entero.shortValue();
    }
}

/** Respuestas deliberadamente genéricas; jamás devuelven SQL ni PII. */
@RestControllerAdvice(assignableTypes=RegistroClienteHttpController.class)
class ErroresRegistroClienteHttp {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalidos(IllegalArgumentException ex) {
        boolean repetido=ex.getMessage()!=null &&
                ex.getMessage().startsWith("Cliente ya registrado");
        return ResponseEntity.status(repetido ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("codigo",repetido ? "CLIENTE_DUPLICADO" : "SOLICITUD_INVALIDA"));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String,String>> prohibido() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("codigo","OPERACION_PROHIBIDA"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String,String>> persistencia() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("codigo","PERSISTENCIA_NO_DISPONIBLE"));
    }
}
