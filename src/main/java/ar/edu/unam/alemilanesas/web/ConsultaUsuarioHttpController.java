package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.BuscarUsuarioService;
import ar.edu.unam.alemilanesas.acceso.CriterioBusquedaUsuario;
import ar.edu.unam.alemilanesas.acceso.UsuarioConsulta;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataAccessException;
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

/** I1-01 / UC-56: búsqueda administrativa de Usuarios, exclusivamente de lectura. */
@RestController
@RequestMapping("/api/v1/usuarios")
public class ConsultaUsuarioHttpController {
    private static final String NOMBRE_USUARIO = "nombreUsuario";
    private static final String ESTADO = "estado";
    private static final String CODIGO_ROL = "codigoRol";
    private static final Set<String> PARAMETROS = Set.of(NOMBRE_USUARIO, ESTADO, CODIGO_ROL);

    private final BuscarUsuarioService usuarios;

    public ConsultaUsuarioHttpController(BuscarUsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping(produces = "application/json")
    public ResponseEntity<List<UsuarioConsulta>> buscar(
            Authentication autenticacion,
            @RequestParam MultiValueMap<String, String> parametros) {
        if (autenticacion == null || !(autenticacion.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        if (parametros == null || !PARAMETROS.containsAll(parametros.keySet())) {
            throw new IllegalArgumentException("Parametro no admitido");
        }
        for (var parametro : parametros.entrySet()) {
            List<String> valores = parametro.getValue();
            if (valores == null || valores.size() != 1 || valores.getFirst() == null
                    || valores.getFirst().indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Parametro invalido");
            }
        }

        String nombre = parametros.getFirst(NOMBRE_USUARIO);
        String estado = parametros.getFirst(ESTADO);
        String codigoRol = parametros.getFirst(CODIGO_ROL);
        // La ficha Real de UC-56 exige uno o más criterios; nunca enumerar todo el catálogo.
        if (nombre == null && estado == null && codigoRol == null) {
            throw new IllegalArgumentException("Se requiere un criterio de busqueda");
        }

        var criterio = new CriterioBusquedaUsuario(nombre, estado, codigoRol);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(usuarios.buscarUsuario(actor.idSesion(), criterio));
    }
}

/** Respuestas de error neutras; nunca devolver SQL, contraseñas ni identificadores de sesión. */
@RestControllerAdvice(assignableTypes = ConsultaUsuarioHttpController.class)
class ErroresConsultaUsuarioHttp {
    private static final String CODIGO = "codigo";

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidos() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO, "SOLICITUD_INVALIDA"));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> prohibido() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO, "OPERACION_PROHIBIDA"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> persistencia() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO, "PERSISTENCIA_NO_DISPONIBLE"));
    }
}
