package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.CambiarEstadoUsuarioService;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
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
 * I1-01 / UC55: cambio lógico de estado de Usuario, solo ADMIN y no-prod.
 * La selección UC56 pertenece al flujo de interfaz; la autorización y F07
 * se verifican de nuevo en el servicio transaccional.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="usuarios-uc55-enabled",havingValue="true")
public class EstadoUsuarioHttpController {
    // Restringir el documento completo evita claves repetidas, extras y coerciones.
    private static final Pattern CUERPO=Pattern.compile(
            "\\A\\s*\\{\\s*\"nuevoEstado\"\\s*:\\s*\"(HABILITADO|DESHABILITADO)\"\\s*\\}\\s*\\z");
    private final CambiarEstadoUsuarioService usuarios;

    public EstadoUsuarioHttpController(CambiarEstadoUsuarioService usuarios) {
        this.usuarios=usuarios;
    }

    public record EstadoUsuarioConfirmado(long idUsuario,String estado,boolean huboCambio) {}

    @PatchMapping(path="/{idUsuario}/estado",consumes="application/json",produces="application/json")
    public ResponseEntity<EstadoUsuarioConfirmado> cambiar(
            @PathVariable long idUsuario,Authentication autenticacion,@RequestBody String cuerpo) {
        if (autenticacion==null || !(autenticacion.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        Matcher datos=CUERPO.matcher(cuerpo);
        if (idUsuario<=0 || !datos.matches()) {
            throw new IllegalArgumentException("Solicitud invalida");
        }
        var resultado=usuarios.cambiarEstadoUsuario(actor.idSesion(),idUsuario,datos.group(1));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new EstadoUsuarioConfirmado(
                        resultado.idUsuario(),resultado.estado(),resultado.huboCambio()));
    }
}

/** Respuestas neutras, salvo los IDs de Pedidos que exige A4/RD-RP009-001. */
@RestControllerAdvice(assignableTypes=EstadoUsuarioHttpController.class)
class ErroresEstadoUsuarioHttp extends ErroresHttpGlobales {
    @ExceptionHandler(CambiarEstadoUsuarioService.RepartidorConPedidosPendientesException.class)
    ResponseEntity<Map<String,Object>> pedidosAReasignar(
            CambiarEstadoUsuarioService.RepartidorConPedidosPendientesException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(Map.of(CODIGO,"REPARTIDOR_CON_PEDIDOS_PENDIENTES",
                        "pedidosPendientes",ex.pedidosPendientes()));
    }

    @Override
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        if (ex instanceof IllegalArgumentException && ex.getMessage()!=null) {
            if ("Usuario inexistente".equals(ex.getMessage())) {
                return respuesta(HttpStatus.NOT_FOUND,"USUARIO_NO_ENCONTRADO");
            }
            if ("No puede deshabilitarse el ultimo ADMINISTRADOR habilitado"
                    .equals(ex.getMessage())
                    || "El Proveedor ya posee otra cuenta habilitada".equals(ex.getMessage())) {
                return conflicto();
            }
        }
        return super.invalidos(ex);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String,String>> conflicto() {
        return respuesta(HttpStatus.CONFLICT,"ESTADO_USUARIO_BLOQUEADO");
    }
}
