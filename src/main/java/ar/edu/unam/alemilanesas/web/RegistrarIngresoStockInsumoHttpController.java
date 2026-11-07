package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.abastecimiento.RegistrarIngresoStockInsumoService;
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

/** UC08 INSUMO: corte no-prod exclusivo de Responsable de Producción. */
@RestController
@RequestMapping("/api/v1/compras")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes", name="compras-uc08-insumo-enabled", havingValue="true")
public class RegistrarIngresoStockInsumoHttpController {
    private static final Set<String> CAMPOS = Set.of("idInsumo", "idOrdenCompra", "cantidadRecibida",
            "codigoUnidadMedida", "costoUnitarioCompra", "comprobante");
    private final RegistrarIngresoStockInsumoService ingresos;

    public RegistrarIngresoStockInsumoHttpController(RegistrarIngresoStockInsumoService ingresos) {
        this.ingresos = ingresos;
    }

    @PostMapping(path="/recepciones-insumo", consumes="application/json", produces="application/json")
    public ResponseEntity<RegistrarIngresoStockInsumoService.IngresoRegistrado> registrar(
            Authentication autenticacion, @RequestBody Map<String,Object> datos) {
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        if (datos == null || !CAMPOS.containsAll(datos.keySet()))
            throw new IllegalArgumentException("Solicitud invalida");
        if (datos.get("idOrdenCompra") == null) throw new IllegalArgumentException("Orden de compra requerida");
        if (!datos.keySet().containsAll(CAMPOS)
                || !(datos.get("codigoUnidadMedida") instanceof String unidad)
                || !(datos.get("comprobante") instanceof String comprobante))
            throw new IllegalArgumentException("Solicitud invalida");
        var d = new RegistrarIngresoStockInsumoService.DatosIngreso(
                entero(datos.get("idInsumo")), entero(datos.get("idOrdenCompra")),
                decimal(datos.get("cantidadRecibida")), unidad,
                decimal(datos.get("costoUnitarioCompra")), comprobante);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ingresos.registrarIngresoStockPorCompra(actor.idSesion(), d));
    }

    private static BigDecimal decimal(Object n) {
        if (!(n instanceof Number)) throw new IllegalArgumentException("Solicitud invalida");
        return new BigDecimal(n.toString());
    }
    private static long entero(Object n) {
        if (!(n instanceof Integer || n instanceof Long || n instanceof java.math.BigInteger))
            throw new IllegalArgumentException("Solicitud invalida");
        try { return decimal(n).longValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Solicitud invalida", ex); }
    }
}

@RestControllerAdvice(assignableTypes=RegistrarIngresoStockInsumoHttpController.class)
class ErroresRegistrarIngresoStockInsumoHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        String mensaje = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        HttpStatus estado = switch (mensaje) {
            case "Insumo inexistente", "Orden de compra inexistente" -> HttpStatus.NOT_FOUND;
            case "Orden cancelada", "Orden ya recibida", "Cantidad excede lo pendiente", "Orden de otro insumo" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        String codigo = switch (mensaje) {
            case "Insumo inexistente" -> "INSUMO_NO_ENCONTRADO";
            case "Insumo inactivo" -> "INSUMO_INACTIVO";
            case "Orden de compra requerida" -> "ORDEN_COMPRA_REQUERIDA";
            case "Orden de compra inexistente" -> "ORDEN_COMPRA_NO_ENCONTRADA";
            case "Orden cancelada" -> "ORDEN_CANCELADA";
            case "Orden ya recibida" -> "ORDEN_YA_RECIBIDA";
            case "Unidad no coincide" -> "UNIDAD_NO_COINCIDE";
            case "Cantidad excede lo pendiente" -> "CANTIDAD_EXCEDE_PENDIENTE";
            case "Orden de otro insumo" -> "ORDEN_DE_OTRO_INSUMO";
            default -> SOLICITUD_INVALIDA;
        };
        return respuesta(estado, codigo);
    }
}
