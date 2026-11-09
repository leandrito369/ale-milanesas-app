package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService.*;
import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService.FueraDeContextoException;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
@RequestMapping("/api/v1/pedidos/{idPedido}/preparaciones")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="preparaciones-uc28-enabled",havingValue="true")
public class RegistrarPreparacionHttpController {
    private static final String INVALIDA = "SOLICITUD_INVALIDA";
    private static final String PRODUCTO = "idProducto";
    private static final String CANTIDAD = "cantidadPreparada";
    private static final String PARTIDAS = "partidas";
    private static final String INSUMO = "idInsumo";
    private static final String PARTIDA = "idPartidaInsumo";
    private static final String APLICADA = "cantidadAplicada";
    private static final Set<String> CAMPOS = Set.of(PRODUCTO,CANTIDAD,PARTIDAS);
    private static final Set<String> CAMPOS_PARTIDA = Set.of(INSUMO,PARTIDA,APLICADA);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final RegistrarPreparacionService preparaciones;
    public RegistrarPreparacionHttpController(RegistrarPreparacionService preparaciones) { this.preparaciones = preparaciones; }
    @PostMapping(consumes="application/json",produces="application/json")
    public ResponseEntity<PreparacionRegistrada> registrar(Authentication autenticacion,@PathVariable long idPedido,
            @RequestBody String body) {
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        Map<String,Object> d;
        try { d = JSON.readValue(body,new TypeReference<Map<String,Object>>() {}); }
        catch (JacksonException ex) { throw new IllegalArgumentException(INVALIDA,ex); }
        if (d == null || !CAMPOS.containsAll(d.keySet())) throw new IllegalArgumentException(INVALIDA);
        List<PartidaAplicada> override = d.get(PARTIDAS) == null ? null : partidas(d.get(PARTIDAS));
        var datos = new DatosPreparacion(idPedido,id(d.get(PRODUCTO)),decimal(d.get(CANTIDAD)),override);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(preparaciones.registrarPreparacionPedido(actor.idSesion(),datos));
    }
    private static List<PartidaAplicada> partidas(Object valor) {
        if (!(valor instanceof List<?> lista)) throw new IllegalArgumentException(INVALIDA);
        var resultado = new ArrayList<PartidaAplicada>();
        for (Object item : lista) {
            if (!(item instanceof Map<?,?> p) || !CAMPOS_PARTIDA.containsAll(p.keySet())) throw new IllegalArgumentException(INVALIDA);
            resultado.add(new PartidaAplicada(id(p.get(INSUMO)),id(p.get(PARTIDA)),decimal(p.get(APLICADA))));
        }
        return resultado;
    }
    private static long id(Object valor) {
        try { return decimal(valor).longValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException(INVALIDA,ex); }
    }
    private static BigDecimal decimal(Object valor) {
        if (!(valor instanceof Number n)) throw new IllegalArgumentException(INVALIDA);
        return new BigDecimal(n.toString());
    }
}

@RestControllerAdvice(assignableTypes=RegistrarPreparacionHttpController.class)
class ErroresRegistrarPreparacionHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        String codigo = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        return switch (codigo) {
            case "PEDIDO_NO_ENCONTRADO" -> respuesta(HttpStatus.NOT_FOUND,codigo);
            case "PEDIDO_NO_PENDIENTE","STOCK_UTILIZABLE_INSUFICIENTE","COSTO_HISTORICO_REQUERIDO" -> respuesta(HttpStatus.CONFLICT,codigo);
            case "PRODUCTO_NO_EN_PEDIDO","CANTIDAD_EXCEDE_ORIGINAL","SIN_RECETA_VIGENTE","RECETA_SIN_INSUMOS",
                    "INSUMO_INACTIVO","UNIDAD_NO_COINCIDE","CANTIDAD_CONSUMIDA_INSUFICIENTE" -> respuesta(HttpStatus.BAD_REQUEST,codigo);
            default -> respuesta(HttpStatus.BAD_REQUEST,SOLICITUD_INVALIDA);
        };
    }
}

/** UC34 §8: solo esta excepción nueva, incluso con la escritura UC28 desactivada. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=BuscarPedidoHttpController.class)
class ErroresContextoPreparacionHttp {
    @ExceptionHandler(FueraDeContextoException.class)
    public ResponseEntity<Map<String,String>> fueraDeContexto() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                .body(Map.of("codigo","FUERA_DE_CONTEXTO"));
    }
}
