package ar.edu.unam.alemilanesas.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errores de negocio controlados; no exponer SQL, roles ni secretos en respuesta. */
@RestControllerAdvice(assignableTypes=ConsultasI1Controller.class)
public class ErroresApiInterna {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalida(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("codigo","SOLICITUD_INVALIDA"));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String,String>> noAutorizado(SecurityException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("codigo","OPERACION_PROHIBIDA"));
    }
}
