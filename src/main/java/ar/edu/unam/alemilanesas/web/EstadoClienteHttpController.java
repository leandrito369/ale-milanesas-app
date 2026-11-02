package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.GestionClientesService;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** B4-03-03 / UC38: cambiar estado de Cliente, solo Admin y solo no-prod. */
@RestController
@RequestMapping("/api/v1/clientes")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes", name="clientes-uc38-enabled",
        havingValue="true")
public class EstadoClienteHttpController {
    private static final String NUEVO_ESTADO = "nuevoEstado";
    private static final String MOTIVO = "motivo";
    private final GestionClientesService clientes;

    public EstadoClienteHttpController(GestionClientesService clientes) {
        this.clientes=clientes;
    }

    public record EstadoConfirmado(long idCliente, String estado, boolean huboCambio) {}

    @PatchMapping(path="/{idCliente}/estado", consumes="application/json",
            produces="application/json")
    public ResponseEntity<EstadoConfirmado> cambiar(
            @PathVariable long idCliente, Authentication autenticacion,
            @RequestBody Map<String,Object> datos) {
        if (autenticacion==null || !(autenticacion.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        // Lista blanca: ni el actor, ni idCliente, ni otras propiedades se toman del JSON.
        if (datos==null || !datos.containsKey(NUEVO_ESTADO) || datos.size()>2
                || !datos.keySet().stream().allMatch(
                    clave -> NUEVO_ESTADO.equals(clave) || MOTIVO.equals(clave))
                || !(datos.get(NUEVO_ESTADO) instanceof String estado)
                || !("ACTIVO".equals(estado) || "INACTIVO".equals(estado))
                || (datos.get(MOTIVO)!=null && !(datos.get(MOTIVO) instanceof String))) {
            throw new IllegalArgumentException("Solicitud invalida");
        }
        String nuevoEstado=(String)datos.get(NUEVO_ESTADO);
        String motivo=(String)datos.get(MOTIVO);
        var resultado=clientes.cambiarEstadoCliente(actor.idSesion(),idCliente,nuevoEstado,motivo);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new EstadoConfirmado(resultado.idCliente(),resultado.estado(),
                        resultado.huboCambio()));
    }
}

/** Errores genéricos sin SQL, PII ni detalle de identidad del actor. */
@RestControllerAdvice(assignableTypes=EstadoClienteHttpController.class)
class ErroresEstadoClienteHttp {
    private static final String CODIGO = "codigo";

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        HttpStatus estado="Cliente inexistente".equals(ex.getMessage())
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(estado).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,estado==HttpStatus.NOT_FOUND
                        ? "CLIENTE_NO_ENCONTRADO" : "SOLICITUD_INVALIDA"));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String,String>> prohibido() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,"OPERACION_PROHIBIDA"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String,String>> persistencia() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,"PERSISTENCIA_NO_DISPONIBLE"));
    }
}
