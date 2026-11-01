package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.CambiarContrasenaService;
import ar.edu.unam.alemilanesas.acceso.RestablecerAccesoUsuarioService;
import ar.edu.unam.alemilanesas.acceso.SesionesUsuarioService;
import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP JSON exclusivamente para sesiones de Usuario; Cliente ONLINE no usa UC01.
 * Nunca acepta idUsuario, idSesionUsuario o roles en payload para autenticar al actor.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthInternaController {
    public record Login(String nombreUsuario,String credencial) {}
    public record CambioClave(String nuevaCredencial,String confirmacionNuevaCredencial,
                              String credencialActual) {}
    public record Restablecimiento(Boolean identidadComercialVerificada,
                                  String credencialTemporal) {}

    private final SesionesUsuarioService sesiones;
    private final CambiarContrasenaService claves;
    private final RestablecerAccesoUsuarioService restablecer;
    private final LimiteIntentosLogin limite;

    public AuthInternaController(SesionesUsuarioService sesiones,
                                CambiarContrasenaService claves,
                                RestablecerAccesoUsuarioService restablecer,
                                LimiteIntentosLogin limite) {
        this.sesiones=sesiones;
        this.claves=claves;
        this.restablecer=restablecer;
        this.limite=limite;
    }

    @PostMapping(path="/auth/login",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SesionesUsuarioService.TokenEmitido> login(
            @RequestBody Login datos,HttpServletRequest request) {
        if (datos==null) throw new TokenSesionInterna.AccesoRechazado();
        String origen=request.getRemoteAddr();
        limite.verificar(datos.nombreUsuario(),origen);
        try {
            var emitido=sesiones.iniciarSesion(datos.nombreUsuario(),datos.credencial());
            limite.exitoso(datos.nombreUsuario(),origen);
            return ResponseEntity.ok().body(emitido);
        } catch (TokenSesionInterna.AccesoRechazado ex) {
            limite.fallido(datos.nombreUsuario(),origen);
            throw ex;
        }
    }

    @PostMapping("/auth/renew")
    public ResponseEntity<Map<String,Object>> renovar(
            Authentication authentication,HttpServletRequest request) {
        String header=request.getHeader("Authorization");
        if (header==null || !header.startsWith("Bearer "))
            throw new TokenSesionInterna.AccesoRechazado();
        var token=sesiones.renovar(sesion(authentication),header.substring(7));
        return ResponseEntity.ok().body(Map.of(
                "accessToken",token.accessToken(),
                "tokenType",token.tokenType(),
                "expiresIn",token.expiresIn()));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        sesiones.cerrarSesion(sesion(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path="/auth/password/change",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> cambiarContrasena(Authentication authentication,
                                                   @RequestBody CambioClave datos) {
        if(datos==null) throw new IllegalArgumentException("Datos requeridos");
        claves.cambiarContrasena(sesion(authentication),datos.nuevaCredencial(),
                datos.confirmacionNuevaCredencial(),datos.credencialActual());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path="/usuarios/{id}/restablecer-acceso",
                 consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> restablecer(Authentication authentication,@PathVariable long id,
                                            @RequestBody Restablecimiento datos) {
        if(datos==null) throw new IllegalArgumentException("Datos requeridos");
        restablecer.restablecerAccesoUsuario(sesion(authentication),id,
                datos.identidadComercialVerificada(),datos.credencialTemporal());
        return ResponseEntity.noContent().build();
    }

    private static UUID sesion(Authentication auth) {
        if (auth==null || !(auth.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno interno))
            throw new TokenSesionInterna.AccesoRechazado();
        return interno.idSesion();
    }
}

/** Mensajes uniformes; no filtrar hash, contraseña, SQL ni IDs de sesion. */
@RestControllerAdvice(assignableTypes=AuthInternaController.class)
class ErroresAutenticacionHttp {
    private static final String CODIGO = "codigo";
    @ExceptionHandler(TokenSesionInterna.AccesoRechazado.class)
    ResponseEntity<Map<String,String>> rechazo() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate","Bearer realm=\"ale-interno\"")
                .body(Map.of(CODIGO,"AUTENTICACION_RECHAZADA"));
    }

    @ExceptionHandler(LimiteIntentosLogin.IntentosExcedidos.class)
    ResponseEntity<Map<String,String>> rateLimit() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After","900")
                .body(Map.of(CODIGO,"INTENTOS_EXCEDIDOS"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalidos() {
        return ResponseEntity.badRequest().body(Map.of(CODIGO,"SOLICITUD_INVALIDA"));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String,String>> noAutorizado() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate","Bearer realm=\"ale-interno\"")
                .body(Map.of(CODIGO,"SESION_INVALIDA"));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String,String>> conflicto() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(CODIGO,"CONFLICTO_ESTADO"));
    }
}
