package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService.*;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** UC03: entrada HTTP estricta; autorización y creación atómica permanecen en B3. */
@RestController
@RequestMapping("/api/v1/pedidos")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="pedidos-uc03-enabled",havingValue="true")
public class CargarPedidoHttpController {
    private static final String INVALIDA = "Solicitud invalida";
    private static final String CLIENTE = "idCliente";
    private static final String FECHA = "fechaReparto";
    private static final String ITEMS = "items";
    private static final String DIRECCION = "direccionEntrega";
    private static final String CLAVE = "claveIdempotencia";
    private static final String PRODUCTO = "idProducto";
    private static final String CANTIDAD = "cantidadSolicitada";
    private static final Set<String> CAMPOS = Set.of(CLIENTE,FECHA,ITEMS,DIRECCION,CLAVE);
    private static final Set<String> CAMPOS_ITEM = Set.of(PRODUCTO,CANTIDAD);
    private static final Set<String> CAMPOS_DIRECCION = Set.of("calle","numero","piso","unidadFuncional","localidad","provincia","referencia","zona");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final CargarPedidoService pedidos;
    public CargarPedidoHttpController(CargarPedidoService pedidos) {this.pedidos=pedidos;}

    /** La marca técnica creado se decide dentro de la transacción y no forma parte del JSON. */
    public record RespuestaPedido(long idPedido,String estado,String canalOrigen,LocalDate fechaReparto,
            String categoriaComercialAplicada,BigDecimal totalOriginal,int cantidadItems) {}

    @PostMapping(consumes="application/json",produces="application/json")
    public ResponseEntity<RespuestaPedido> cargar(Authentication autenticacion,@RequestBody String body) {
        if (autenticacion==null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        var pedido=pedidos.cargarPedido(actor.idSesion(),leer(body));
        var respuesta=new RespuestaPedido(pedido.idPedido(),pedido.estado(),pedido.canalOrigen(),pedido.fechaReparto(),
                pedido.categoriaComercialAplicada(),pedido.totalOriginal(),pedido.cantidadItems());
        return ResponseEntity.status(pedido.creado()?HttpStatus.CREATED:HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(respuesta);
    }
    private static DatosPedido leer(String body) {
        Map<String,Object> datos;
        try {datos=JSON.readValue(body,new TypeReference<Map<String,Object>>() {});}
        catch (JacksonException ex) {throw new IllegalArgumentException(INVALIDA,ex);}
        var d=mapa(datos,CAMPOS);
        if (!d.keySet().containsAll(CAMPOS) || !(d.get(ITEMS) instanceof List<?> elementos))
            throw new IllegalArgumentException(INVALIDA);
        var items=elementos.stream().map(CargarPedidoHttpController::item).toList();
        String clave=texto(d,CLAVE);
        UUID uuid=UUID.fromString(clave);
        if (!uuid.toString().equalsIgnoreCase(clave)) throw new IllegalArgumentException(INVALIDA);
        return new DatosPedido(entero(d.get(CLIENTE)),LocalDate.parse(texto(d,FECHA)),items,direccion(d.get(DIRECCION)),uuid);
    }
    private static ItemPedido item(Object valor) {
        var d=mapa(valor,CAMPOS_ITEM);
        if (!(d.get(CANTIDAD) instanceof Number cantidad)) throw new IllegalArgumentException(INVALIDA);
        return new ItemPedido(entero(d.get(PRODUCTO)),new BigDecimal(cantidad.toString()));
    }
    private static DireccionEntrega direccion(Object valor) {
        var d=mapa(valor,CAMPOS_DIRECCION);
        return new DireccionEntrega(texto(d,"calle"),opcional(d,"numero"),opcional(d,"piso"),opcional(d,"unidadFuncional"),
                texto(d,"localidad"),opcional(d,"provincia"),opcional(d,"referencia"),opcional(d,"zona"));
    }
    private static Map<?,?> mapa(Object valor,Set<String> campos) {
        if (!(valor instanceof Map<?,?> mapa) || !campos.containsAll(mapa.keySet()))
            throw new IllegalArgumentException(INVALIDA);
        return mapa;
    }
    private static long entero(Object valor) {
        if (!(valor instanceof BigInteger numero)) throw new IllegalArgumentException(INVALIDA);
        try {return numero.longValueExact();}
        catch (ArithmeticException ex) {throw new IllegalArgumentException(INVALIDA,ex);}
    }
    private static String texto(Map<?,?> datos,String campo) {
        if (!(datos.get(campo) instanceof String texto)) throw new IllegalArgumentException(INVALIDA);
        return texto;
    }
    private static String opcional(Map<?,?> datos,String campo) {
        return datos.get(campo)==null?null:texto(datos,campo);
    }
}

@RestControllerAdvice(assignableTypes=CargarPedidoHttpController.class)
class ErroresCargarPedidoHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,DateTimeException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        boolean deuda=ex instanceof IllegalArgumentException && ex.getMessage()!=null
                && ex.getMessage().toLowerCase(Locale.ROOT).contains("deuda");
        return respuesta(deuda?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST,
                deuda?"CLIENTE_CON_DEUDA":SOLICITUD_INVALIDA);
    }
}
