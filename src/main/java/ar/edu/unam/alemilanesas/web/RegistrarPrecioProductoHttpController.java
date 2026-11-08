package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.ActualizarPrecioVigenteService;
import ar.edu.unam.alemilanesas.maestros.ActualizarPrecioVigenteService.PrecioActualizado;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** I1-04 / UC19: adaptador HTTP; vigencias, auditoría y transacción permanecen en B3. */
@RestController
@RequestMapping("/api/v1/productos/{idProducto}/precios")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="precios-uc19-enabled",havingValue="true")
public class RegistrarPrecioProductoHttpController {
    private static final String PRECIO = "nuevoPrecioBase";
    private static final String DESDE = "vigenteDesde";
    private static final String HASTA = "vigenteHasta";
    private static final String INVALIDA = "Solicitud invalida";
    private static final Set<String> CAMPOS = Set.of(PRECIO,DESDE,HASTA);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ActualizarPrecioVigenteService precios;
    public RegistrarPrecioProductoHttpController(ActualizarPrecioVigenteService precios) { this.precios = precios; }

    @PostMapping(consumes="application/json",produces="application/json")
    public ResponseEntity<PrecioActualizado> registrar(Authentication autenticacion,
            @PathVariable long idProducto, @RequestBody String body) {
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        Map<String,Object> datos;
        try { datos = JSON.readValue(body,new TypeReference<Map<String,Object>>() {}); }
        catch (JacksonException ex) { throw new IllegalArgumentException(INVALIDA,ex); }
        if (datos == null || !CAMPOS.containsAll(datos.keySet()) || !(datos.get(PRECIO) instanceof Number numero))
            throw new IllegalArgumentException(INVALIDA);
        BigDecimal precio = new BigDecimal(numero.toString());
        if (precio.signum() <= 0 || precio.scale() > 4 || precio.precision() > 18 || precio.precision() - precio.scale() > 14)
            throw new IllegalArgumentException(INVALIDA);
        OffsetDateTime desde = fecha(datos.get(DESDE));
        OffsetDateTime hasta = datos.get(HASTA) == null ? null : fecha(datos.get(HASTA));
        if (hasta != null && !hasta.isAfter(desde)) throw new IllegalArgumentException(INVALIDA);
        var resultado = precios.actualizarPrecioVigente(actor.idSesion(),idProducto,precio,desde,hasta);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(resultado);
    }
    private static OffsetDateTime fecha(Object valor) {
        if (!(valor instanceof String texto)) throw new IllegalArgumentException(INVALIDA);
        return OffsetDateTime.parse(texto);
    }
}

/** Errores acotados a UC19/UC20; nunca devolver texto SQL ni mensajes internos de B3. */
@RestControllerAdvice(assignableTypes={RegistrarPrecioProductoHttpController.class,ConsultarHistorialPreciosHttpController.class})
class ErroresPreciosProductoHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,
            DateTimeException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        String mensaje = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        HttpStatus estado = "Intervalo solapado con precio programado".equals(mensaje)
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        String codigo = switch (mensaje) {
            case "No se permiten precios retroactivos" -> "VIGENCIA_RETROACTIVA";
            case "Intervalo solapado con precio programado" -> "SOLAPE_VIGENCIA";
            case "Rango de consulta invalido" -> "RANGO_INVALIDO";
            default -> SOLICITUD_INVALIDA;
        };
        return respuesta(estado,codigo);
    }
}
