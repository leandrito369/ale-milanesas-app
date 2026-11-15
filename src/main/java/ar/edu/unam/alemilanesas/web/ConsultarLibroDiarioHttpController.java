package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.finanzas.ConsultarLibroDiarioService;
import ar.edu.unam.alemilanesas.finanzas.ConsultarLibroDiarioService.LibroDiario;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** UC-23 B4 propuesta: la ruta HTTP no forma parte de la firma G0 ratificada. */
@RestController
@RequestMapping("/api/v1/finanzas/libro-diario")
public class ConsultarLibroDiarioHttpController {
    private static final String INICIAL = "fechaInicial";
    private static final String FINAL = "fechaFinal";
    private static final Set<String> PARAMETROS = Set.of(INICIAL, FINAL);
    private final ConsultarLibroDiarioService libroDiario;

    public ConsultarLibroDiarioHttpController(ConsultarLibroDiarioService libroDiario) {
        this.libroDiario = libroDiario;
    }

    @GetMapping(produces = "application/json")
    public ResponseEntity<LibroDiario> consultar(Authentication autenticacion,
            @RequestParam MultiValueMap<String, String> parametros) {
        if (autenticacion == null
                || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        if (!PARAMETROS.equals(parametros.keySet())) {
            throw new IllegalArgumentException("Fechas obligatorias o parametro no admitido");
        }
        for (var valores : parametros.values()) {
            if (valores.size() != 1 || valores.getFirst() == null || valores.getFirst().isBlank()) {
                throw new IllegalArgumentException("Parametro invalido");
            }
        }
        LocalDate inicial = LocalDate.parse(parametros.getFirst(INICIAL));
        LocalDate fechaFinal = LocalDate.parse(parametros.getFirst(FINAL));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(libroDiario.consultarLibroDiario(actor.idSesion(), inicial, fechaFinal));
    }
}

@RestControllerAdvice(assignableTypes = ConsultarLibroDiarioHttpController.class)
class ErroresConsultarLibroDiarioHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class})
    public ResponseEntity<Map<String, String>> invalidos(Exception ex) {
        return respuesta(HttpStatus.BAD_REQUEST, SOLICITUD_INVALIDA);
    }
}
