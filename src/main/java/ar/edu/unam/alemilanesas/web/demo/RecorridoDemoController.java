package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService.DatosAsignacion;
import ar.edu.unam.alemilanesas.ventas.GestionarRecorridoRepartoService;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/** Adaptador HTML UC-43/C1-EXPLICITO. No cierra Recorridos ni realiza PE-2. */
@Controller
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="recorridos-uc43-enabled",havingValue="true")
public class RecorridoDemoController {
    private final GestionarRecorridoRepartoService recorridos;
    public RecorridoDemoController(GestionarRecorridoRepartoService recorridos) {
        this.recorridos = recorridos;
    }

    @GetMapping("/web/demo/recorridos")
    public String formulario() { return "demo/recorridos"; }

    @PostMapping("/web/demo/recorridos/abrir")
    public ModelAndView abrir(Authentication auth, @RequestParam MultiValueMap<String,String> params,
            RedirectAttributes redirect) {
        var d = EntradaDemo.leer(params, Set.of("idUsuarioRepartidor","referenciaOperativa"));
        var r = recorridos.abrirRecorrido(EntradaDemo.sesion(auth),
                EntradaDemo.idRequerido(d,"idUsuarioRepartidor"), EntradaDemo.opcional(d,"referenciaOperativa"));
        return confirmar(redirect, "Recorrido abierto: #" + r.idRecorrido() + ".");
    }

    @PostMapping("/web/demo/recorridos/asignar")
    public ModelAndView asignar(Authentication auth, @RequestParam MultiValueMap<String,String> params,
            RedirectAttributes redirect) {
        var d = EntradaDemo.leer(params, Set.of("idRecorrido","idPedido","idUsuarioRepartidor","motivoReasignacion"));
        var r = recorridos.asignarYVincularRecorrido(EntradaDemo.sesion(auth),
                new DatosAsignacion(EntradaDemo.idRequerido(d,"idPedido"),
                        EntradaDemo.idRequerido(d,"idUsuarioRepartidor"), EntradaDemo.opcional(d,"motivoReasignacion")),
                EntradaDemo.idRequerido(d,"idRecorrido"));
        return confirmar(redirect, "Pedido #" + r.idPedido() + " asignado y vinculado al Recorrido #" + r.idRecorrido() + ".");
    }

    @PostMapping("/web/demo/recorridos/vincular")
    public ModelAndView vincular(Authentication auth, @RequestParam MultiValueMap<String,String> params,
            RedirectAttributes redirect) {
        var d = EntradaDemo.leer(params, Set.of("idRecorrido","idPedido"));
        var r = recorridos.vincularPedidoARecorrido(EntradaDemo.sesion(auth),
                EntradaDemo.idRequerido(d,"idRecorrido"), EntradaDemo.idRequerido(d,"idPedido"));
        return confirmar(redirect, "Pedido #" + r.idPedido() + " vinculado al Recorrido #" + r.idRecorrido() + ".");
    }

    private static ModelAndView confirmar(RedirectAttributes redirect, String mensaje) {
        redirect.addFlashAttribute("mensajeExito", mensaje);
        var vista = new RedirectView("/web/demo/recorridos");
        vista.setContextRelative(true);
        vista.setStatusCode(HttpStatus.SEE_OTHER);
        return new ModelAndView(vista);
    }
}
