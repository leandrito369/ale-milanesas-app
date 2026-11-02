package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.GestionClientesService;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * B4-03-02 / UC32. PUT es representación COMPLETA de datos comerciales;
 * no es PATCH parcial. Servicio B3 controla OTP, concurrencia y transacción.
 * Solo permitido mediante gate explicito en test/no-prod.
 */
@RestController
@RequestMapping("/api/v1/clientes")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes", name="clientes-uc32-enabled",
        havingValue="true")
public class ModificacionClienteHttpController {
    private final GestionClientesService clientes;

    public ModificacionClienteHttpController(GestionClientesService clientes) {
        this.clientes=clientes;
    }

    public record ModificacionConfirmada(long idCliente, String estado,
                                         String categoriaComercial,boolean huboCambio,
                                         boolean advertenciaCoincidenciaParcial) {}

    @PutMapping(path="/{idCliente}",consumes="application/json",produces="application/json")
    public ResponseEntity<ModificacionConfirmada> modificar(
            @PathVariable long idCliente, Authentication autenticacion,
            @RequestBody Map<String,Object> datos) {
        if (autenticacion==null || !(autenticacion.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        UUID sesion=actor.idSesion();
        var dto=DatosClienteHttpParser.interpretar(datos);
        var r=clientes.modificarCliente(sesion,idCliente,dto);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ModificacionConfirmada(r.idCliente(),r.estado(),
                        r.categoriaComercial(),r.huboCambio(),
                        !r.advertenciasParciales().isEmpty()));
    }
}

/** Sin nombres, teléfonos ni detalles SQL en las respuestas de error. */
@RestControllerAdvice(assignableTypes=ModificacionClienteHttpController.class)
class ErroresModificacionClienteHttp {
    private static final String CODIGO="codigo";

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalidos(IllegalArgumentException ex) {
        HttpStatus estado=HttpStatus.BAD_REQUEST;
        String codigo="SOLICITUD_INVALIDA";
        if (ex instanceof GestionClientesService.ClienteDuplicadoException) {
            estado=HttpStatus.CONFLICT;
            codigo="CLIENTE_DUPLICADO";
        } else if ("Cliente inexistente".equals(ex.getMessage())) {
            estado=HttpStatus.NOT_FOUND;
            codigo="CLIENTE_NO_ENCONTRADO";
        }
        return ResponseEntity.status(estado).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,codigo));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String,String>> prohibido() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,"OPERACION_PROHIBIDA"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String,String>> persistencia() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,"PERSISTENCIA_NO_DISPONIBLE"));
    }
}
