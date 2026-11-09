package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.produccioninventario.ConsultarStockService;
import ar.edu.unam.alemilanesas.produccioninventario.ConsultarStockService.*;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** UC09: GET sin gate, exclusivamente Responsable de Producción. */
@RestController
@RequestMapping("/api/v1/stock")
public class ConsultarStockHttpController {
    private static final String TIPO="tipoInventario";
    private static final String ID="idElemento";
    private static final String BAJO="soloBajoUmbral";
    private static final Set<String> PARAMETROS=Set.of(TIPO,ID,BAJO);
    private final ConsultarStockService stock;
    public ConsultarStockHttpController(ConsultarStockService stock) {this.stock=stock;}
    @GetMapping(produces="application/json")
    public ResponseEntity<ResultadoStock> consultar(Authentication autenticacion,@RequestParam MultiValueMap<String,String> params) {
        if (autenticacion==null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        if (!PARAMETROS.containsAll(params.keySet())) throw new IllegalArgumentException("Parametro no admitido");
        for (var valores:params.values()) {
            if (valores.size()!=1 || valores.getFirst()==null || valores.getFirst().isBlank())
                throw new IllegalArgumentException("Parametro invalido");
        }
        var c=new CriterioStock(params.getFirst(TIPO),id(params.getFirst(ID)),booleano(params.getFirst(BAJO)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stock.consultarStock(actor.idSesion(),c));
    }
    private static Long id(String valor) {
        if (valor==null) return null;
        if (!valor.matches("\\d+")) throw new IllegalArgumentException("Identificador invalido");
        return Long.valueOf(valor);
    }
    private static boolean booleano(String valor) {
        if (valor==null) return false;
        return switch(valor) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Booleano invalido");
        };
    }
}

@RestControllerAdvice(assignableTypes=ConsultarStockHttpController.class)
class ErroresConsultarStockHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        String codigo=ex!=null && "SOLO_BAJO_UMBRAL_NO_APLICA_REVENTA".equals(ex.getMessage())
                ?"SOLO_BAJO_UMBRAL_NO_APLICA_REVENTA":SOLICITUD_INVALIDA;
        return respuesta(HttpStatus.BAD_REQUEST,codigo);
    }
}
