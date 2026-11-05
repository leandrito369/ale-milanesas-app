package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.CrearUsuarioService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** I1-01 / UC-17 (+ UC-18 inicial): alta administrativa solo en pruebas no productivas. */
@RestController
@RequestMapping("/api/v1/usuarios")
@Profile("!prod")
@ConditionalOnProperty(prefix = "ale.http.writes", name = "usuarios-uc17-enabled",
        havingValue = "true")
public class RegistroUsuarioHttpController {
    private static final Set<String> CAMPOS = Set.of(
            "nombreUsuario", "credencialInicial", "rolesIniciales", "idProveedor");

    private final CrearUsuarioService usuarios;

    public RegistroUsuarioHttpController(CrearUsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    /** Proyección mínima del contrato UC17: nunca incluir nombre ni credencial. */
    public record AltaConfirmada(long idUsuario, String estado,
                                boolean cambioCredencialPendiente, Set<String> roles) {}

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<AltaConfirmada> registrar(Authentication autenticacion,
            @RequestBody Map<String, Object> datos) {
        if (autenticacion == null || !(autenticacion.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        if (datos == null || !CAMPOS.containsAll(datos.keySet())
                || !(datos.get("nombreUsuario") instanceof String nombre)
                || !(datos.get("credencialInicial") instanceof String credencial)
                || !(datos.get("rolesIniciales") instanceof List<?> rolesRecibidos)
                || rolesRecibidos.isEmpty()) {
            throw new IllegalArgumentException("Solicitud invalida");
        }
        if (rolesRecibidos.stream().anyMatch(rol -> !(rol instanceof String))) {
            throw new IllegalArgumentException("Roles invalidos");
        }
        Set<String> roles = new HashSet<>();
        for (Object rol : rolesRecibidos) {
            if (!roles.add((String) rol)) {
                throw new IllegalArgumentException("Roles duplicados");
            }
        }
        Long idProveedor = null;
        Object proveedor = datos.get("idProveedor");
        if (proveedor != null) {
            if (!(proveedor instanceof Long || proveedor instanceof Integer)
                    || ((Number) proveedor).longValue() <= 0) {
                throw new IllegalArgumentException("Proveedor invalido");
            }
            idProveedor = ((Number) proveedor).longValue();
        }
        var creado = usuarios.crearUsuario(
                actor.idSesion(), nombre, credencial, Set.copyOf(roles), idProveedor);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new AltaConfirmada(creado.idUsuario(), creado.estado(),
                        creado.cambioCredencialPendiente(), creado.roles()));
    }
}

/** Errores deliberadamente neutros: no devolver nombres existentes, hashes ni SQL. */
@RestControllerAdvice(assignableTypes = RegistroUsuarioHttpController.class)
class ErroresRegistroUsuarioHttp {
    private static final String CODIGO = "codigo";

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
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
