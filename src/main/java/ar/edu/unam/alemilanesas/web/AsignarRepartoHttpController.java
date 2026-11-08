package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService;
import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService.*;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigInteger;
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

/** UC43: confirmación online de asignación, bajo bandera independiente de UC03. */
@RestController
@RequestMapping("/api/v1/pedidos/{idPedido}/asignacion-reparto")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="pedidos-uc43-enabled",havingValue="true")
public class AsignarRepartoHttpController {
    private static final String REPARTIDOR="idUsuarioRepartidor";
    private static final String MOTIVO="motivoReasignacion";
    private static final String INVALIDA="Solicitud invalida";
    private static final Set<String> CAMPOS=Set.of(REPARTIDOR,MOTIVO);
    private static final JsonMapper JSON=JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final AsignarRepartoService reparto;
    public AsignarRepartoHttpController(AsignarRepartoService reparto) {this.reparto=reparto;}

    @PostMapping(consumes="application/json",produces="application/json")
    public ResponseEntity<AsignacionRegistrada> asignar(Authentication autenticacion,@PathVariable long idPedido,@RequestBody String body) {
        if (autenticacion==null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        var resultado=reparto.asignarOReasignar(actor.idSesion(),leer(idPedido,body));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(resultado);
    }
    private static DatosAsignacion leer(long pedido,String body) {
        Map<String,Object> d;
        try {d=JSON.readValue(body,new TypeReference<Map<String,Object>>() {});}
        catch (JacksonException ex) {throw new IllegalArgumentException(INVALIDA,ex);}
        if (d==null || !CAMPOS.containsAll(d.keySet()) || !(d.get(REPARTIDOR) instanceof BigInteger id))
            throw new IllegalArgumentException(INVALIDA);
        Object motivo=d.get(MOTIVO);
        if (motivo!=null && !(motivo instanceof String)) throw new IllegalArgumentException(INVALIDA);
        try {return new DatosAsignacion(pedido,id.longValueExact(),(String)motivo);}
        catch (ArithmeticException ex) {throw new IllegalArgumentException(INVALIDA,ex);}
    }
}

@RestControllerAdvice(assignableTypes=AsignarRepartoHttpController.class)
class ErroresAsignarRepartoHttp extends ErroresHttpGlobales {
    private record FalloHttp(HttpStatus estado,String codigo) {}
    private static final String REPARTIDOR_NO_ELEGIBLE="REPARTIDOR_NO_ELEGIBLE";
    private static final Map<String,FalloHttp> ERRORES=Map.ofEntries(
            Map.entry("Sin cambio: mismo repartidor ya asignado",new FalloHttp(HttpStatus.CONFLICT,"SIN_CAMBIO")),
            Map.entry("Pedido no está PENDIENTE",new FalloHttp(HttpStatus.CONFLICT,"PEDIDO_NO_PENDIENTE")),
            Map.entry("Motivo de reasignación requerido",new FalloHttp(HttpStatus.BAD_REQUEST,"MOTIVO_REQUERIDO")),
            Map.entry("Motivo de reasignación no aplica en primera asignación",new FalloHttp(HttpStatus.BAD_REQUEST,"MOTIVO_NO_APLICA")),
            Map.entry("Repartidor inexistente",new FalloHttp(HttpStatus.BAD_REQUEST,REPARTIDOR_NO_ELEGIBLE)),
            Map.entry("Repartidor no habilitado",new FalloHttp(HttpStatus.BAD_REQUEST,REPARTIDOR_NO_ELEGIBLE)),
            Map.entry("Usuario sin rol REPARTIDOR",new FalloHttp(HttpStatus.BAD_REQUEST,REPARTIDOR_NO_ELEGIBLE)),
            Map.entry("Pedido inexistente",new FalloHttp(HttpStatus.NOT_FOUND,"PEDIDO_NO_ENCONTRADO")));
    @Override
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        String mensaje=ex==null || ex.getMessage()==null?"":ex.getMessage();
        FalloHttp error=ERRORES.getOrDefault(mensaje,new FalloHttp(HttpStatus.BAD_REQUEST,SOLICITUD_INVALIDA));
        return respuesta(error.estado(),error.codigo());
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,String>> conflicto() {
        return respuesta(HttpStatus.CONFLICT,"CONFLICTO_ASIGNACION");
    }
}
