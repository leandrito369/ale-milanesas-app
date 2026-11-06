package ar.edu.unam.alemilanesas.web.comun;

import ar.edu.unam.alemilanesas.web.ConsultaUsuarioHttpController;
import ar.edu.unam.alemilanesas.web.RegistroUsuarioHttpController;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handlers HTTP comunes (400/403/503 con Cache-Control: no-store) reutilizados
 * por los controladores sin mapeo específico y como clase base para los demás.
 */
@RestControllerAdvice(assignableTypes = {
        ConsultaUsuarioHttpController.class,
        RegistroUsuarioHttpController.class
})
public class ErroresHttpGlobales {
    protected static final String CODIGO = "codigo";
    protected static final String SOLICITUD_INVALIDA = "SOLICITUD_INVALIDA";

    protected static ResponseEntity<Map<String, String>> respuesta(
            HttpStatus estado, String codigo) {
        return ResponseEntity.status(estado)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO, codigo));
    }

    public ResponseEntity<Map<String, String>> invalidos() {
        return invalidos(null);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalidos(Exception ex) {
        return respuesta(HttpStatus.BAD_REQUEST, SOLICITUD_INVALIDA);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> prohibido() {
        return respuesta(HttpStatus.FORBIDDEN, "OPERACION_PROHIBIDA");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> persistencia() {
        return respuesta(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCIA_NO_DISPONIBLE");
    }
}
