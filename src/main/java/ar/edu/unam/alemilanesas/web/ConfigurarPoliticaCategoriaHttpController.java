package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.finanzas.ConfigurarPoliticaCategoriaComercialService;
import ar.edu.unam.alemilanesas.finanzas.ConfigurarPoliticaCategoriaComercialService.AjusteCategoria;
import ar.edu.unam.alemilanesas.finanzas.ConfigurarPoliticaCategoriaComercialService.PoliticaCreada;
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
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** I1-05 / UC57: adaptador HTTP de configuración, sin exponer UC15/16. */
@RestController
@RequestMapping("/api/v1/categorias-comerciales/politicas")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="categorias-uc57-enabled",havingValue="true")
public class ConfigurarPoliticaCategoriaHttpController {
    private static final String DIAS = "diasToleranciaMora";
    private static final String DESDE = "vigenteDesde";
    private static final String HASTA = "vigenteHasta";
    private static final String CUMPLIDOR = "ajusteCumplidor";
    private static final String MORA = "ajusteEnMora";
    private static final String TIPO = "tipoAjuste";
    private static final String PORCENTAJE = "porcentajeAjuste";
    private static final String INVALIDA = "Solicitud invalida";
    private static final Set<String> CAMPOS = Set.of(DIAS,DESDE,HASTA,CUMPLIDOR,MORA);
    private static final Set<String> CAMPOS_AJUSTE = Set.of(TIPO,PORCENTAJE);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ConfigurarPoliticaCategoriaComercialService politicas;
    public ConfigurarPoliticaCategoriaHttpController(ConfigurarPoliticaCategoriaComercialService politicas) {
        this.politicas = politicas;
    }

    @PostMapping(consumes="application/json",produces="application/json")
    public ResponseEntity<PoliticaCreada> configurar(Authentication autenticacion, @RequestBody String body) {
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        Map<String,Object> datos;
        try { datos = JSON.readValue(body,new TypeReference<Map<String,Object>>() {}); }
        catch (JacksonException ex) { throw new IllegalArgumentException(INVALIDA,ex); }
        if (datos == null || !CAMPOS.containsAll(datos.keySet())) throw new IllegalArgumentException(INVALIDA);
        int dias = entero(datos.get(DIAS));
        OffsetDateTime desde = fecha(datos.get(DESDE));
        OffsetDateTime hasta = datos.get(HASTA) == null ? null : fecha(datos.get(HASTA));
        if (dias < 0 || hasta != null && !hasta.isAfter(desde)) throw new IllegalArgumentException(INVALIDA);
        var resultado = politicas.configurarPoliticaCategoriaComercial(actor.idSesion(),dias,desde,hasta,
                ajuste(datos.get(CUMPLIDOR)),ajuste(datos.get(MORA)));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(resultado);
    }
    private static AjusteCategoria ajuste(Object valor) {
        if (!(valor instanceof Map<?,?> p) || !CAMPOS_AJUSTE.containsAll(p.keySet())
                || !(p.get(TIPO) instanceof String tipo) || !(p.get(PORCENTAJE) instanceof Number numero))
            throw new IllegalArgumentException(INVALIDA);
        // B3 conserva rango, escala NUMERIC(5,2), tipo y compatibilidad por categoría.
        return new AjusteCategoria(tipo,new BigDecimal(numero.toString()));
    }
    private static int entero(Object valor) {
        if (!(valor instanceof java.math.BigInteger || valor instanceof Integer || valor instanceof Long))
            throw new IllegalArgumentException(INVALIDA);
        try { return new BigDecimal(valor.toString()).intValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException(INVALIDA,ex); }
    }
    private static OffsetDateTime fecha(Object valor) {
        if (!(valor instanceof String texto)) throw new IllegalArgumentException(INVALIDA);
        return OffsetDateTime.parse(texto);
    }
}

@RestControllerAdvice(assignableTypes=ConfigurarPoliticaCategoriaHttpController.class)
class ErroresConfigurarPoliticaCategoriaHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,DateTimeException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        String mensaje = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        return switch (mensaje) {
            case "Politica futura solapada" -> respuesta(HttpStatus.CONFLICT,"SOLAPE_VIGENCIA");
            case "No truncar politica futura programada" -> respuesta(HttpStatus.CONFLICT,"VIGENCIA_FUTURA_NO_TRUNCADA");
            default -> respuesta(HttpStatus.BAD_REQUEST,SOLICITUD_INVALIDA);
        };
    }
}
