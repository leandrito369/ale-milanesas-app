package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.produccioninventario.ConfigurarRecetaService;
import ar.edu.unam.alemilanesas.produccioninventario.ConfigurarRecetaService.*;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
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

@RestController
@RequestMapping("/api/v1/productos/{idProducto}/receta")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes", name="recetas-uc10-enabled", havingValue="true")
public class ConfigurarRecetaHttpController {
    private static final String INVALIDA = "SOLICITUD_INVALIDA";
    private static final String CANTIDAD = "cantidadRendimientoBase";
    private static final String UNIDAD = "codigoUnidadRendimiento";
    private static final String DESDE = "vigenteDesde";
    private static final String HASTA = "vigenteHasta";
    private static final String ITEMS = "itemsInsumo";
    private static final String INSUMO = "idInsumo";
    private static final String NECESARIA = "cantidadNecesaria";
    private static final String UNIDAD_ITEM = "codigoUnidadCantidad";
    private static final Set<String> CAMPOS = Set.of(CANTIDAD, UNIDAD, DESDE, HASTA, ITEMS);
    private static final Set<String> CAMPOS_ITEM = Set.of(INSUMO, NECESARIA, UNIDAD_ITEM);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ConfigurarRecetaService recetas;
    public ConfigurarRecetaHttpController(ConfigurarRecetaService recetas) { this.recetas = recetas; }

    @PostMapping(consumes="application/json", produces="application/json")
    public ResponseEntity<RecetaRegistrada> configurar(Authentication autenticacion,
            @PathVariable long idProducto, @RequestBody String body) {
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        Map<String, Object> d;
        try { d = JSON.readValue(body, new TypeReference<Map<String, Object>>() {}); }
        catch (JacksonException ex) { throw new IllegalArgumentException(INVALIDA, ex); }
        if (d == null || !CAMPOS.containsAll(d.keySet()) || !(d.get(ITEMS) instanceof List<?> lista))
            throw new IllegalArgumentException(INVALIDA);
        var items = new ArrayList<ItemReceta>();
        for (Object valor : lista) items.add(item(valor));
        var datos = new DatosReceta(idProducto, decimal(d.get(CANTIDAD)), texto(d.get(UNIDAD)),
                fecha(d.get(DESDE)), d.get(HASTA) == null ? null : fecha(d.get(HASTA)), items);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(recetas.configurarRecetaProducto(actor.idSesion(), datos));
    }
    private static ItemReceta item(Object valor) {
        if (!(valor instanceof Map<?, ?> m) || !CAMPOS_ITEM.containsAll(m.keySet())) throw new IllegalArgumentException(INVALIDA);
        long id;
        try { id = decimal(m.get(INSUMO)).longValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException(INVALIDA, ex); }
        return new ItemReceta(id, decimal(m.get(NECESARIA)), texto(m.get(UNIDAD_ITEM)));
    }
    private static BigDecimal decimal(Object valor) {
        if (!(valor instanceof Number n)) throw new IllegalArgumentException(INVALIDA);
        return new BigDecimal(n.toString());
    }
    private static String texto(Object valor) {
        if (!(valor instanceof String s)) throw new IllegalArgumentException(INVALIDA);
        return s;
    }
    private static OffsetDateTime fecha(Object valor) { return OffsetDateTime.parse(texto(valor)); }
}

@RestControllerAdvice(assignableTypes=ConfigurarRecetaHttpController.class)
class ErroresConfigurarRecetaHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalidos(Exception ex) {
        String mensaje = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        return switch (mensaje) {
            case "PRODUCTO_INEXISTENTE", "INSUMO_INEXISTENTE" -> respuesta(HttpStatus.NOT_FOUND, mensaje);
            case "SOLAPE_VERSION_FUTURA" -> respuesta(HttpStatus.CONFLICT, mensaje);
            case "PRODUCTO_NO_ES_ELABORADO", "PRODUCTO_INACTIVO", "INSUMO_INACTIVO", "UNIDAD_NO_COINCIDE",
                    "VIGENCIA_RETROACTIVA" -> respuesta(HttpStatus.BAD_REQUEST, mensaje);
            default -> respuesta(HttpStatus.BAD_REQUEST, SOLICITUD_INVALIDA);
        };
    }
}
