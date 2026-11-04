package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.BuscarProductoService;
import ar.edu.unam.alemilanesas.maestros.BuscarProductoService.CriterioProducto;
import ar.edu.unam.alemilanesas.maestros.BuscarProductoService.ProductoConsulta;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** B4-03-05 / UC35: busqueda de Productos en solo lectura (ADMIN y VENDEDOR). */
@RestController
@RequestMapping("/api/v1/productos")
public class ConsultaProductoHttpController {
    private static final String ID_PRODUCTO = "idProducto";
    private static final String NOMBRE = "nombre";
    private static final String TIPO_PRODUCTO = "tipoProducto";
    private static final String ACTIVO = "activo";
    private static final Set<String> PARAMETROS = Set.of(
            ID_PRODUCTO, NOMBRE, TIPO_PRODUCTO, ACTIVO);
    private final BuscarProductoService productos;

    public ConsultaProductoHttpController(BuscarProductoService productos) {
        this.productos = productos;
    }

    @GetMapping(produces = "application/json")
    public ResponseEntity<List<ProductoConsulta>> buscar(
            Authentication autenticacion,
            @RequestParam MultiValueMap<String, String> params) {
        if (autenticacion == null || !(autenticacion.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        if (params == null || !PARAMETROS.containsAll(params.keySet())) {
            throw new IllegalArgumentException("Parametro no admitido");
        }
        for (var entry : params.entrySet()) {
            List<String> valores = entry.getValue();
            if (valores == null || valores.size() != 1 || valores.getFirst() == null
                    || valores.getFirst().indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Parametro invalido");
            }
        }
        Long idProducto = parsearId(params.getFirst(ID_PRODUCTO));
        String nombre = params.getFirst(NOMBRE);
        String tipoProducto = params.getFirst(TIPO_PRODUCTO);
        Boolean activo = parsearBooleano(params.getFirst(ACTIVO));

        var criterio = new CriterioProducto(idProducto, nombre, tipoProducto, activo);
        var resultado = productos.buscarProducto(actor.idSesion(), criterio);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(resultado);
    }

    private static Long parsearId(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.trim();
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException("idProducto vacio");
        }
        try {
            long id = Long.parseLong(limpio);
            if (id <= 0) {
                throw new IllegalArgumentException("idProducto no positivo");
            }
            return id;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("idProducto invalido", ex);
        }
    }

    private static Boolean parsearBooleano(String valor) {
        if (valor == null) {
            return null;
        }
        return switch (valor.trim()) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("activo invalido");
        };
    }
}

/** Nunca exponer mensajes SQL internos ni detalles de infraestructura. */
@RestControllerAdvice(assignableTypes = ConsultaProductoHttpController.class)
class ErroresConsultaProductoHttp {
    private static final String CODIGO = "codigo";

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidos() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO, "SOLICITUD_INVALIDA"));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> prohibido() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO, "OPERACION_PROHIBIDA"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> persistencia() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO, "PERSISTENCIA_NO_DISPONIBLE"));
    }
}

