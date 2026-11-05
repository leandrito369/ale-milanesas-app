package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.GestionProductoService;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** B4-03-06 / UC47: modificación de ficha, exclusivamente ADMIN y no-prod. */
@RestController
@RequestMapping("/api/v1/productos")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="productos-uc47-enabled",havingValue="true")
public class ModificacionProductoHttpController {
    private static final String DESCRIPCION="descripcion";
    private static final Set<String> CAMPOS=Set.of(
            "nombre",DESCRIPCION,"tipoProducto","codigoUnidadVenta");
    private final GestionProductoService productos;

    public ModificacionProductoHttpController(GestionProductoService productos) {
        this.productos=productos;
    }

    public record ProductoModificado(long idProducto,boolean activo,boolean huboCambio) {}

    @PutMapping(path="/{idProducto}",consumes="application/json",produces="application/json")
    public ResponseEntity<ProductoModificado> modificar(
            @PathVariable long idProducto, Authentication autenticacion,
            @RequestBody Map<String,Object> datos) {
        if (autenticacion==null || !(autenticacion.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        if (idProducto<=0 || datos==null || !CAMPOS.containsAll(datos.keySet())
                || !(datos.get("nombre") instanceof String nombre)
                || !(datos.get("tipoProducto") instanceof String tipo)
                || !(datos.get("codigoUnidadVenta") instanceof String unidad)
                || (datos.get(DESCRIPCION)!=null
                    && !(datos.get(DESCRIPCION) instanceof String))) {
            throw new IllegalArgumentException("Solicitud invalida");
        }
        String descripcion=(String)datos.get(DESCRIPCION);
        // PostgreSQL TEXT no admite NUL; no enviar error SQL por dato invalido.
        if (descripcion!=null && descripcion.indexOf('\0')>=0) {
            throw new IllegalArgumentException("Descripcion invalida");
        }
        var r=productos.modificarProducto(actor.idSesion(),idProducto,nombre,descripcion,tipo,unidad);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ProductoModificado(r.idProducto(),r.activo(),r.huboCambio()));
    }
}

/** Nunca copiar mensajes de excepcion/SQL ni atributos financieros a la respuesta. */
@RestControllerAdvice(assignableTypes=ModificacionProductoHttpController.class)
class ErroresModificacionProductoHttp {
    private static final String CODIGO="codigo";

    @ExceptionHandler(GestionProductoService.ProductoDuplicadoException.class)
    ResponseEntity<Map<String,Object>> duplicado(GestionProductoService.ProductoDuplicadoException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,"PRODUCTO_DUPLICADO","coincidencia",ex.coincidencia()));
    }

    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
    ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        HttpStatus estado=HttpStatus.BAD_REQUEST;
        String codigo="SOLICITUD_INVALIDA";
        if (ex instanceof IllegalArgumentException && ex.getMessage()!=null) {
            String msg=ex.getMessage();
            if ("Producto inexistente".equals(msg)) {
                estado=HttpStatus.NOT_FOUND;
                codigo="PRODUCTO_NO_ENCONTRADO";
            } else if (msg.startsWith("Producto con historia:")) {
                estado=HttpStatus.CONFLICT;
                codigo="PRODUCTO_CON_DEPENDENCIAS";
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
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,"PERSISTENCIA_NO_DISPONIBLE"));
    }
}
