package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.GestionProductoService;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

/** B4-03-07 / UC48: cambio de estado lógico de Producto, solo ADMIN y no-prod. */
@RestController
@RequestMapping("/api/v1/productos")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="productos-uc48-enabled",havingValue="true")
public class EstadoProductoHttpController {
    // Validar el documento completo impide claves duplicadas, campos extra y coerciones.
    private static final Pattern BODY_ESTADO=Pattern.compile(
            "\\A\\s*\\{\\s*\"activo\"\\s*:\\s*(true|false)\\s*\\}\\s*\\z");
    private final GestionProductoService productos;

    public EstadoProductoHttpController(GestionProductoService productos) {
        this.productos=productos;
    }

    public record EstadoProductoConfirmado(long idProducto,boolean activo,boolean huboCambio) {}

    @PatchMapping(path="/{idProducto}/estado",consumes="application/json",produces="application/json")
    public ResponseEntity<EstadoProductoConfirmado> cambiarEstado(
            @PathVariable long idProducto,Authentication autenticacion,@RequestBody String cuerpo) {
        if (autenticacion==null || !(autenticacion.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        Matcher campos=BODY_ESTADO.matcher(cuerpo);
        if (idProducto<=0 || !campos.matches()) {
            throw new IllegalArgumentException("Solicitud invalida");
        }
        var resultado=productos.cambiarEstadoProducto(
                actor.idSesion(),idProducto,Boolean.valueOf(campos.group(1)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new EstadoProductoConfirmado(
                        resultado.idProducto(),resultado.activo(),resultado.huboCambio()));
    }
}

/** Respuestas acotadas: nunca mostrar detalles SQL, credenciales ni datos del Producto. */
@RestControllerAdvice(assignableTypes=EstadoProductoHttpController.class)
class ErroresEstadoProductoHttp {
    private static final String CODIGO="codigo";

    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
    ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        HttpStatus estado=HttpStatus.BAD_REQUEST;
        String codigo="SOLICITUD_INVALIDA";
        if (ex instanceof IllegalArgumentException && ex.getMessage()!=null) {
            String mensaje=ex.getMessage();
            if ("Producto inexistente".equals(mensaje)) {
                estado=HttpStatus.NOT_FOUND;
                codigo="PRODUCTO_NO_ENCONTRADO";
            } else if ("Ficha inconsistente: corregir mediante UC-47".equals(mensaje)
                    || "Unidad de venta inexistente".equals(mensaje)
                    || "Datos obligatorios/tipo/unidad inválidos".equals(mensaje)) {
                estado=HttpStatus.CONFLICT;
                codigo="PRODUCTO_FICHA_INVALIDA";
            }
        }
        return ResponseEntity.status(estado).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,codigo));
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
