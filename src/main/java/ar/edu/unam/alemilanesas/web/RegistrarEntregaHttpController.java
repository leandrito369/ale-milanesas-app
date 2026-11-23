package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.DatosEntrega;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.EntregaRegistrada;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.ItemEntrega;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigDecimal;
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

/** UC-05: registro online de Entrega (originales), exclusivo REPARTIDOR y bajo gate independiente. */
@RestController
@RequestMapping("/api/v1/pedidos/{idPedido}/entrega")
@Profile("!prod")
@ConditionalOnProperty(prefix = "ale.http.writes", name = "entregas-uc05-enabled", havingValue = "true")
public class RegistrarEntregaHttpController {
    private static final String INVALIDA = "SOLICITUD_INVALIDA";
    private static final String DETALLES = "detallesEntrega";
    private static final String EFECTIVO = "efectivoDeclaradoRecibido";
    private static final String CLAVE = "claveIdempotencia";
    private static final String PRODUCTO = "idProducto";
    private static final String CANTIDAD = "cantidadEntregada";
    private static final Set<String> CAMPOS = Set.of(DETALLES, EFECTIVO, CLAVE);
    private static final Set<String> CAMPOS_ITEM = Set.of(PRODUCTO, CANTIDAD);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final RegistrarEntregaService entregas;

    public RegistrarEntregaHttpController(RegistrarEntregaService entregas) {
        this.entregas = entregas;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<EntregaRegistrada> registrar(
            Authentication autenticacion,
            @PathVariable long idPedido,
            @RequestBody String body) {
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        DatosEntrega datos = leer(idPedido, body);
        EntregaRegistrada resultado = entregas.registrarEntrega(actor.idSesion(), datos);
        return ResponseEntity.status(resultado.creado() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(resultado);
    }

    private static DatosEntrega leer(long idPedido, String body) {
        Map<String, Object> d;
        try {
            d = JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException ex) {
            throw new IllegalArgumentException(INVALIDA, ex);
        }
        if (d == null || !CAMPOS.containsAll(d.keySet())) {
            throw new IllegalArgumentException(INVALIDA);
        }
        Object rawDetalles = d.get(DETALLES);
        if (!(rawDetalles instanceof List<?> lista) || lista.isEmpty()) {
            throw new IllegalArgumentException(INVALIDA);
        }
        List<ItemEntrega> items = new ArrayList<>(lista.size());
        for (Object itemObj : lista) {
            if (!(itemObj instanceof Map<?, ?> item) || !CAMPOS_ITEM.containsAll(item.keySet())) {
                throw new IllegalArgumentException(INVALIDA);
            }
            items.add(new ItemEntrega(id(item.get(PRODUCTO)), decimal(item.get(CANTIDAD))));
        }

        Object rawEfectivo = d.get(EFECTIVO);
        BigDecimal efectivo = rawEfectivo == null ? null : decimal(rawEfectivo);

        Object rawClave = d.get(CLAVE);
        if (!(rawClave instanceof String claveTexto)) {
            throw new IllegalArgumentException(INVALIDA);
        }
        UUID clave;
        try {
            clave = UUID.fromString(claveTexto);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(INVALIDA, ex);
        }
        return new DatosEntrega(idPedido, List.copyOf(items), efectivo, clave);
    }

    private static long id(Object valor) {
        try {
            return decimal(valor).longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(INVALIDA, ex);
        }
    }

    private static BigDecimal decimal(Object valor) {
        if (!(valor instanceof Number n)) {
            throw new IllegalArgumentException(INVALIDA);
        }
        return new BigDecimal(n.toString());
    }
}

@RestControllerAdvice(assignableTypes = RegistrarEntregaHttpController.class)
class ErroresRegistrarEntregaHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalidos(Exception ex) {
        String codigo = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        return switch (codigo) {
            case "PEDIDO_NO_ENCONTRADO" -> respuesta(HttpStatus.NOT_FOUND, codigo);
            case "PRODUCTO_NO_EN_PEDIDO", "CANTIDAD_EXCEDE_ORIGINAL", "CANTIDADES_EN_CERO" ->
                respuesta(HttpStatus.BAD_REQUEST, codigo);
            default -> respuesta(HttpStatus.BAD_REQUEST, SOLICITUD_INVALIDA);
        };
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflicto(IllegalStateException ex) {
        String codigo = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        return switch (codigo) {
            case "PEDIDO_NO_PENDIENTE", "RECORRIDO_ACTIVO_REQUERIDO", "RECORRIDO_NO_ABIERTO",
                 "ASIGNACION_RECORRIDO_CONFLICTO", "ADICIONALES_UC07_NO_SOPORTADOS",
                 "IDEMPOTENCIA_CONFLICTO_DATOS", "DISPONIBILIDAD_ELABORADO_INSUFICIENTE",
                 "STOCK_REVENTA_INSUFICIENTE", "COSTO_HISTORICO_REQUERIDO",
                 "PEDIDO_SIN_DETALLES_ORIGINALES", "TIPO_AJUSTE_PEDIDO_INVALIDO" ->
                respuesta(HttpStatus.CONFLICT, codigo);
            default -> respuesta(HttpStatus.CONFLICT, "CONFLICTO_ENTREGA");
        };
    }
}
