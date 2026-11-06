package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.GestionarAsignacionesRolesService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * I1-01 / UC-18: asociaciones Usuario–Rol administradas bajo gate no-prod.
 * No altera el catálogo ROL, el vínculo PROVEEDOR ni el esquema MR5/F01.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="usuarios-uc18-enabled",havingValue="true")
public class GestionRolesHttpController {
    private static final Set<String> CAMPOS=Set.of("rolesAgregar","rolesQuitar");
    private final GestionarAsignacionesRolesService roles;

    public GestionRolesHttpController(GestionarAsignacionesRolesService roles) {
        this.roles=roles;
    }

    /** La respuesta excluye identidades de sesión, datos personales y eventos internos. */
    public record RolesConfirmados(long idUsuario,Set<String> rolesVigentes,boolean huboCambio) {}

    @PatchMapping(path="/{idUsuario}/roles",consumes="application/json",produces="application/json")
    public ResponseEntity<RolesConfirmados> gestionar(
            @PathVariable long idUsuario, Authentication autenticacion,
            @RequestBody Map<String,Object> datos) {
        if (autenticacion==null || !(autenticacion.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        if (idUsuario<=0 || datos==null || !CAMPOS.containsAll(datos.keySet())) {
            throw new IllegalArgumentException("Solicitud invalida");
        }
        Set<String> agregar=leerRoles(datos,"rolesAgregar");
        Set<String> quitar=leerRoles(datos,"rolesQuitar");
        var resultado=roles.gestionarAsignacionesRoles(
                actor.idSesion(),idUsuario,agregar,quitar);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RolesConfirmados(resultado.idUsuario(),
                        resultado.rolesVigentes(),resultado.huboCambio()));
    }

    private static Set<String> leerRoles(Map<String,Object> datos,String campo) {
        if (!datos.containsKey(campo)) return Set.of();
        if (!(datos.get(campo) instanceof List<?> valores)) {
            throw new IllegalArgumentException("Se exige una lista de Roles");
        }
        Set<String> roles=new HashSet<>();
        for (Object valor : valores) {
            if (!(valor instanceof String rol) || rol.isBlank()
                    || rol.indexOf('\0')>=0 || !roles.add(rol)) {
                throw new IllegalArgumentException("Rol invalido o repetido");
            }
        }
        return Set.copyOf(roles);
    }
}

/** Errores HTTP neutros: sin SQL, credenciales, tokens ni detalle de roles. */
@RestControllerAdvice(assignableTypes=GestionRolesHttpController.class)
class ErroresGestionRolesHttp {
    private static final String CODIGO="codigo";

    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
    ResponseEntity<Map<String,String>> invalidos() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,"SOLICITUD_INVALIDA"));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String,String>> prohibido() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,"OPERACION_PROHIBIDA"));
    }

    @ExceptionHandler({DataAccessException.class,IllegalStateException.class})
    ResponseEntity<Map<String,String>> persistencia() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,"PERSISTENCIA_NO_DISPONIBLE"));
    }
}
