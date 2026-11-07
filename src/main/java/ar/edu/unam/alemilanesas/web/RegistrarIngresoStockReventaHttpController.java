package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.abastecimiento.RegistrarIngresoStockReventaService;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/compras")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="compras-uc08-reventa-enabled",havingValue="true")
public class RegistrarIngresoStockReventaHttpController {
    private static final String PROVEEDOR="idProveedorDirecto";
    private static final Set<String> CAMPOS=Set.of("idProducto",PROVEEDOR,"cantidadRecibida",
            "codigoUnidadMedida","costoUnitarioCompra","comprobante");
    // Configuración local: conservar la precisión de la entrada sin cambiar el controller INSUMO.
    private static final JsonMapper JSON=JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final RegistrarIngresoStockReventaService ingresos;
    public RegistrarIngresoStockReventaHttpController(RegistrarIngresoStockReventaService ingresos) {
        this.ingresos=ingresos;
    }
    @PostMapping(path="/recepciones-reventa",consumes="application/json",produces="application/json")
    public ResponseEntity<RegistrarIngresoStockReventaService.IngresoReventaRegistrado> registrar(
            Authentication autenticacion,@RequestBody String body) {
        if(autenticacion==null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        Map<String,Object> datos;
        try { datos=JSON.readValue(body,new TypeReference<Map<String,Object>>() {}); }
        catch(JacksonException ex) { throw new IllegalArgumentException("Solicitud invalida",ex); }
        if(datos==null)throw new IllegalArgumentException("Solicitud invalida");
        if(datos.containsKey("idOrdenCompra"))throw new IllegalArgumentException("Orden no aplica REVENTA");
        if(!CAMPOS.containsAll(datos.keySet()))throw new IllegalArgumentException("Solicitud invalida");
        if(datos.get(PROVEEDOR)==null)throw new IllegalArgumentException("Proveedor directo requerido");
        if(!datos.keySet().containsAll(CAMPOS) || !(datos.get("codigoUnidadMedida") instanceof String unidad)
                || !(datos.get("comprobante") instanceof String comprobante))throw new IllegalArgumentException("Solicitud invalida");
        var d=new RegistrarIngresoStockReventaService.DatosIngresoReventa(entero(datos.get("idProducto")),
                entero(datos.get(PROVEEDOR)),decimal(datos.get("cantidadRecibida")),unidad,
                decimal(datos.get("costoUnitarioCompra")),comprobante);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ingresos.registrarIngresoStockPorCompra(actor.idSesion(),d));
    }
    private static BigDecimal decimal(Object n) {
        if(!(n instanceof Number))throw new IllegalArgumentException("Solicitud invalida");
        return new BigDecimal(n.toString());
    }
    private static long entero(Object n) {
        if(!(n instanceof java.math.BigInteger || n instanceof Integer || n instanceof Long))
            throw new IllegalArgumentException("Solicitud invalida");
        try { return decimal(n).longValueExact(); }
        catch(ArithmeticException ex) { throw new IllegalArgumentException("Solicitud invalida",ex); }
    }
}

@RestControllerAdvice(assignableTypes=RegistrarIngresoStockReventaHttpController.class)
class ErroresRegistrarIngresoStockReventaHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        String mensaje=ex==null || ex.getMessage()==null?"":ex.getMessage();
        HttpStatus estado=switch(mensaje) {
            case "Producto inexistente","Proveedor inexistente" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        String codigo=switch(mensaje) {
            case "Producto inexistente" -> "PRODUCTO_NO_ENCONTRADO";
            case "Producto no es REVENTA" -> "PRODUCTO_NO_ES_REVENTA";
            case "Producto inactivo" -> "PRODUCTO_INACTIVO";
            case "Proveedor inexistente" -> "PROVEEDOR_NO_ENCONTRADO";
            case "Proveedor inactivo" -> "PROVEEDOR_INACTIVO";
            case "Proveedor directo requerido" -> "PROVEEDOR_DIRECTO_REQUERIDO";
            case "Orden no aplica REVENTA" -> "ORDEN_NO_APLICA_REVENTA";
            case "Unidad no coincide" -> "UNIDAD_NO_COINCIDE";
            case "Valuación extraordinaria requerida" -> "VALUACION_EXTRAORDINARIA_REQUERIDA";
            default -> SOLICITUD_INVALIDA;
        };
        return respuesta(estado,codigo);
    }
}
