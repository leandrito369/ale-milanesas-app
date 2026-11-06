package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.GestionClientesService;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * B4-03-01 / UC02: adaptador de prueba, NO activado en producción.
 * La autorización existe en el filtro HTTP y se revalida en transacción B3.
 * Feature flag false por defecto y prohibida por el perfil prod.
 */
@RestController
@RequestMapping("/api/v1")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes", name="clientes-uc02-enabled",
        havingValue="true")
public class RegistroClienteHttpController {
    private final GestionClientesService clientes;

    public RegistroClienteHttpController(GestionClientesService clientes) {
        this.clientes=clientes;
    }

    /** No devuelve datos ajenos ni advertencias con identificadores internos BC07. */
    public record AltaConfirmada(long idCliente, String estado, String categoriaComercial,
                                 boolean advertenciaCoincidenciaParcial) {}

    @PostMapping(path="/clientes",consumes="application/json",produces="application/json")
    public ResponseEntity<AltaConfirmada> registrar(Authentication auth,
                                                     @RequestBody Map<String,Object> datos) {
        UUID sesion=sesionAutenticada(auth);
        var dto=DatosClienteHttpParser.interpretar(datos);
        var r=clientes.registrarCliente(sesion,dto);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new AltaConfirmada(r.idCliente(),r.estado(),r.categoriaComercial(),
                        !r.advertenciasParciales().isEmpty()));
    }

    private static UUID sesionAutenticada(Authentication auth) {
        if (auth==null ||
                !(auth.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sin sesión válida");
        }
        return actor.idSesion();
    }
}

/** Respuestas deliberadamente genéricas; jamás devuelven SQL ni PII. */
@RestControllerAdvice(assignableTypes=RegistroClienteHttpController.class)
class ErroresRegistroClienteHttp extends ErroresHttpGlobales {
    @Override
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        if (ex instanceof GestionClientesService.ClienteDuplicadoException dup) {
            return respuesta(HttpStatus.CONFLICT, dup.inactivo()
                    ? "CLIENTE_INACTIVO_REQUIERE_ADMINISTRADOR"
                    : "CLIENTE_DUPLICADO");
        }
        return super.invalidos(ex);
    }
}
