package ar.edu.unam.alemilanesas.web.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

/** Mensajes de catálogo; nunca propagar mensajes SQL ni excepciones al HTML. */
@ControllerAdvice(basePackageClasses=ConsultasDemoController.class)
@Profile("!prod")
public class ErroresDemoAdvice {
    @ModelAttribute
    public void roles(org.springframework.security.core.Authentication auth,org.springframework.ui.Model model) {
        var roles=auth==null?java.util.Set.<String>of():auth.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet());
        boolean operativo=!roles.contains(ar.edu.unam.alemilanesas.web.SeguridadHttpInterna.ROL_CAMBIO_PENDIENTE);
        model.addAttribute("esAdmin",operativo && roles.contains("ADMINISTRADOR"));
        model.addAttribute("esVendedor",operativo && roles.contains("VENDEDOR"));
        model.addAttribute("esRepartidor",operativo && roles.contains("REPARTIDOR"));
    }
    private static ModelAndView error(HttpStatus status,String codigo) {
        var vista=new ModelAndView("demo/error");
        vista.setStatus(status);
        vista.addObject("codigo",codigo);
        vista.addObject("codigoEstado",status.value());
        vista.addObject("tituloError","No se pudo completar la operación");
        vista.addObject("detalleError","Revisá los datos y permisos o volvé a intentarlo cuando el servicio esté disponible.");
        vista.addObject("mensaje","No se pudo completar la operación. Revisá los datos y permisos o volvé a intentarlo cuando el servicio esté disponible.");
        return vista;
    }
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class,MethodArgumentTypeMismatchException.class})
    public ModelAndView invalido(Exception ex) {
        String mensaje=ex.getMessage()==null?"":ex.getMessage();
        return switch(mensaje) {
            case "Pedido inexistente" -> error(HttpStatus.NOT_FOUND,"PEDIDO_NO_ENCONTRADO");
            case "Sin cambio: mismo repartidor ya asignado" -> error(HttpStatus.CONFLICT,"SIN_CAMBIO");
            case "Pedido no está PENDIENTE" -> error(HttpStatus.CONFLICT,"PEDIDO_NO_PENDIENTE");
            case "Motivo de reasignación requerido" -> error(HttpStatus.BAD_REQUEST,"MOTIVO_REQUERIDO");
            case "Motivo de reasignación no aplica en primera asignación" -> error(HttpStatus.BAD_REQUEST,"MOTIVO_NO_APLICA");
            case "Repartidor inexistente", "Repartidor no habilitado", "Usuario sin rol REPARTIDOR" ->
                error(HttpStatus.BAD_REQUEST,"REPARTIDOR_NO_ELEGIBLE");
            default -> error(HttpStatus.BAD_REQUEST,"SOLICITUD_INVALIDA");
        };
    }
    @ExceptionHandler(SecurityException.class)
    public ModelAndView prohibido() {return error(HttpStatus.FORBIDDEN,"OPERACION_PROHIBIDA");}
    @ExceptionHandler(DataAccessException.class)
    public ModelAndView persistencia() {return error(HttpStatus.SERVICE_UNAVAILABLE,"PERSISTENCIA_NO_DISPONIBLE");}
    @ExceptionHandler(IllegalStateException.class)
    public ModelAndView conflicto() {return error(HttpStatus.CONFLICT,"CONFLICTO_OPERACION");}
    @ExceptionHandler(AsignarRepartoDemoController.PedidoNoEncontrado.class)
    public ModelAndView inexistente() {return error(HttpStatus.NOT_FOUND,"PEDIDO_NO_ENCONTRADO");}
}
