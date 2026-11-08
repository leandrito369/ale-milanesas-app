package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.abastecimiento.RegistrarAjusteInventarioService;
import ar.edu.unam.alemilanesas.abastecimiento.RegistrarAjusteInventarioService.*;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/inventario")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="compras-uc42-enabled",havingValue="true")
public class RegistrarAjusteInventarioHttpController {
    private static final String INVALIDA = "Solicitud invalida";
    private static final String TIPO_ELEMENTO = "tipoElemento";
    private static final String ID_ELEMENTO = "idElemento";
    private static final String TIPO_MOVIMIENTO = "tipoMovimiento";
    private static final String MOTIVO = "motivo";
    private static final String CANTIDAD = "cantidad";
    private static final String PARTIDAS = "partidas";
    private static final String COSTO = "costoValuacion";
    private static final String ID_PARTIDA = "idPartidaInsumo";
    private static final String LOTE = "codigoLote";
    private static final String VENCIMIENTO = "fechaVencimiento";
    private static final String APERTURA = "marcarApertura";
    private static final Set<String> OBLIGATORIOS = Set.of(TIPO_ELEMENTO,ID_ELEMENTO,TIPO_MOVIMIENTO,CANTIDAD,MOTIVO,PARTIDAS);
    private static final Set<String> CAMPOS = Set.of(TIPO_ELEMENTO,ID_ELEMENTO,TIPO_MOVIMIENTO,CANTIDAD,MOTIVO,COSTO,PARTIDAS);
    private static final Set<String> CAMPOS_PARTIDA = Set.of(ID_PARTIDA,CANTIDAD,LOTE,VENCIMIENTO,APERTURA);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final RegistrarAjusteInventarioService ajustes;
    public RegistrarAjusteInventarioHttpController(RegistrarAjusteInventarioService ajustes) { this.ajustes = ajustes; }

    @PostMapping(path="/ajustes",consumes="application/json",produces="application/json")
    public ResponseEntity<AjusteRegistrado> registrar(Authentication autenticacion, @RequestBody String body) {
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        Map<String,Object> d;
        try { d = JSON.readValue(body,new TypeReference<Map<String,Object>>() {}); }
        catch (JacksonException ex) { throw new IllegalArgumentException(INVALIDA,ex); }
        if (d == null || !CAMPOS.containsAll(d.keySet()) || !d.keySet().containsAll(OBLIGATORIOS)
                || !(d.get(PARTIDAS) instanceof List<?> lista)) throw new IllegalArgumentException(INVALIDA);
        var partidas = lista.stream().map(RegistrarAjusteInventarioHttpController::partida).toList();
        var datos = new DatosAjuste(texto(d.get(TIPO_ELEMENTO)),entero(d.get(ID_ELEMENTO)),texto(d.get(TIPO_MOVIMIENTO)),
                decimal(d.get(CANTIDAD)),texto(d.get(MOTIVO)),d.get(COSTO) == null ? null : decimal(d.get(COSTO)),partidas);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ajustes.registrarAjusteInventario(actor.idSesion(),datos));
    }
    private static PartidaAjuste partida(Object valor) {
        if (!(valor instanceof Map<?,?> p) || !CAMPOS_PARTIDA.containsAll(p.keySet()) || !p.containsKey(CANTIDAD))
            throw new IllegalArgumentException(INVALIDA);
        if (p.get(APERTURA) != null && !(p.get(APERTURA) instanceof Boolean)) throw new IllegalArgumentException(INVALIDA);
        return new PartidaAjuste(p.get(ID_PARTIDA) == null ? null : entero(p.get(ID_PARTIDA)),decimal(p.get(CANTIDAD)),
                p.get(LOTE) == null ? null : texto(p.get(LOTE)),
                p.get(VENCIMIENTO) == null ? null : fecha(p.get(VENCIMIENTO)), (Boolean) p.get(APERTURA));
    }
    private static LocalDate fecha(Object valor) {
        try { return LocalDate.parse(texto(valor)); }
        catch (java.time.DateTimeException ex) { throw new IllegalArgumentException(INVALIDA,ex); }
    }
    private static String texto(Object valor) {
        if (!(valor instanceof String s)) throw new IllegalArgumentException(INVALIDA);
        return s;
    }
    private static BigDecimal decimal(Object n) {
        if (!(n instanceof Number)) throw new IllegalArgumentException(INVALIDA);
        return new BigDecimal(n.toString());
    }
    private static long entero(Object n) {
        if (!(n instanceof java.math.BigInteger || n instanceof Integer || n instanceof Long))
            throw new IllegalArgumentException(INVALIDA);
        try { return decimal(n).longValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException(INVALIDA,ex); }
    }
}

@RestControllerAdvice(assignableTypes=RegistrarAjusteInventarioHttpController.class)
class ErroresRegistrarAjusteInventarioHttp extends ErroresHttpGlobales {
    private static final String DUPLICADO = "SALDO_INICIAL_DUPLICADO";
    @Override
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        String mensaje = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        HttpStatus estado = switch (mensaje) {
            case "Insumo inexistente" -> HttpStatus.NOT_FOUND;
            case DUPLICADO,"Cantidad excede disponible en partida" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        String codigo = switch (mensaje) {
            case "Insumo inexistente" -> "INSUMO_NO_ENCONTRADO";
            case "Insumo inactivo" -> "INSUMO_INACTIVO";
            case "Partida no pertenece al insumo" -> "PARTIDA_NO_PERTENECE";
            case "Cantidad excede disponible en partida" -> "CANTIDAD_EXCEDE_DISPONIBLE";
            case DUPLICADO,"COSTO_VALUACION_REQUERIDO","COSTO_HISTORICO_REQUERIDO" -> mensaje;
            default -> SOLICITUD_INVALIDA;
        };
        return respuesta(estado,codigo);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String,String>> integridad(DataIntegrityViolationException ex) {
        Throwable causa = ex.getMostSpecificCause();
        if (causa instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())
                && sql.getMessage() != null && sql.getMessage().lines().findFirst().orElse("")
                    .contains("\"uq_movimiento_insumo_saldo_inicial\""))
            return respuesta(HttpStatus.CONFLICT,DUPLICADO);
        return persistencia();
    }
}
