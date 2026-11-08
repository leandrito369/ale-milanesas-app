package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.GestionInsumosService;
import ar.edu.unam.alemilanesas.maestros.GestionInsumosService.InsumoResultado;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

/** UC50: alta del maestro sin existencia física ni costo inicial. */
@RestController
@RequestMapping("/api/v1/insumos")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="insumos-uc50-enabled",havingValue="true")
public class RegistroInsumoHttpController {
    private static final Set<String> CAMPOS = Set.of("nombre","codigoUnidadMedida","umbralMinimo","nivelObjetivoReposicion");
    private final GestionInsumosService insumos;
    public RegistroInsumoHttpController(GestionInsumosService insumos) { this.insumos = insumos; }

    @PostMapping(consumes="application/json",produces="application/json")
    public ResponseEntity<InsumoResultado> registrar(Authentication autenticacion,@RequestBody String body) {
        UUID sesion = CuerpoInsumoHttp.sesion(autenticacion);
        var d = CuerpoInsumoHttp.leer(body,CAMPOS);
        var r = insumos.registrarInsumo(sesion,CuerpoInsumoHttp.texto(d.get("nombre")),
                CuerpoInsumoHttp.texto(d.get("codigoUnidadMedida")),CuerpoInsumoHttp.decimal(d.get("umbralMinimo")),
                CuerpoInsumoHttp.decimal(d.get("nivelObjetivoReposicion")));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(r);
    }
}

/** Parser local compartido por las tres escrituras; no modifica el JSON global. */
final class CuerpoInsumoHttp {
    private static final String INVALIDA = "Solicitud invalida";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private CuerpoInsumoHttp() {}
    static UUID sesion(Authentication autenticacion) {
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        return actor.idSesion();
    }
    static Map<String,Object> leer(String body,Set<String> campos) {
        Map<String,Object> d;
        try { d = JSON.readValue(body,new TypeReference<Map<String,Object>>() {}); }
        catch (JacksonException ex) { throw new IllegalArgumentException(INVALIDA,ex); }
        if (d == null || !campos.containsAll(d.keySet()) || !d.keySet().containsAll(campos))
            throw new IllegalArgumentException(INVALIDA);
        return d;
    }
    static String texto(Object valor) {
        if (!(valor instanceof String s) || s.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(INVALIDA);
        return s;
    }
    static BigDecimal decimal(Object valor) {
        if (!(valor instanceof Number numero)) throw new IllegalArgumentException(INVALIDA);
        BigDecimal n = new BigDecimal(numero.toString());
        if (n.scale() > 3 || n.precision() - n.scale() > 15) throw new IllegalArgumentException(INVALIDA);
        return n;
    }
}

/** Códigos comunes; cada UC registra su propio advice acotado. */
abstract class ErroresInsumosHttp extends ErroresHttpGlobales {
    private static final String SIN_CAMBIO = "SIN_CAMBIO";
    @Override
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        String mensaje = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        HttpStatus estado = switch (mensaje) {
            case "Insumo inexistente" -> HttpStatus.NOT_FOUND;
            case "Insumo con dependencias históricas: no cambiar unidad de medida",
                 "Nombre de Insumo ya registrado, incluso inactivo",SIN_CAMBIO -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        String codigo = switch (mensaje) {
            case "Insumo inexistente" -> "INSUMO_NO_ENCONTRADO";
            case "Unidad inexistente" -> "UNIDAD_INEXISTENTE";
            case "Insumo con dependencias históricas: no cambiar unidad de medida" -> "CAMBIO_UNIDAD_BLOQUEADO";
            case "Nombre de Insumo ya registrado, incluso inactivo" -> "INSUMO_DUPLICADO";
            case SIN_CAMBIO -> SIN_CAMBIO;
            default -> SOLICITUD_INVALIDA;
        };
        return respuesta(estado,codigo);
    }
}

@RestControllerAdvice(assignableTypes=RegistroInsumoHttpController.class)
class ErroresRegistroInsumoHttp extends ErroresInsumosHttp {}
