package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService;
import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService;
import java.net.URI;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@Controller
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="pedidos-uc43-enabled",havingValue="true")
public class AsignarRepartoDemoController {
    private final AsignarRepartoService reparto;
    private final BuscarPedidoService pedidos;
    public AsignarRepartoDemoController(AsignarRepartoService reparto,BuscarPedidoService pedidos) {
        this.reparto=reparto;this.pedidos=pedidos;
    }
    @GetMapping("/web/demo/pedidos/{idPedido}/asignar")
    public String formulario(Authentication auth,@PathVariable long idPedido,Model model) {
        var lista=pedidos.buscarPedido(EntradaDemo.sesion(auth),new BuscarPedidoService.CriterioBusquedaPedido(idPedido,null,null,null,null));
        if (lista.isEmpty()) throw new PedidoNoEncontrado();
        model.addAttribute("pedido",lista.getFirst());
        model.addAttribute("idPedido",idPedido);
        model.addAttribute("form",Map.of("idUsuarioRepartidor","","motivoReasignacion",""));
        model.addAttribute("aviso","Asignación por ID sintético. Esta pantalla no consulta la asignación vigente ni promete un flujo de reasignación completo.");
        return "demo/pedido-asignar";
    }
    @PostMapping("/web/demo/pedidos/{idPedido}/asignar")
    public ResponseEntity<Void> asignar(Authentication auth,@PathVariable long idPedido,
            @RequestParam MultiValueMap<String,String> params) {
        var d=EntradaDemo.leer(params,Set.of("idUsuarioRepartidor","motivoReasignacion"));
        reparto.asignarOReasignar(EntradaDemo.sesion(auth),new AsignarRepartoService.DatosAsignacion(idPedido,
                EntradaDemo.idRequerido(d,"idUsuarioRepartidor"),EntradaDemo.opcional(d,"motivoReasignacion")));
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/web/demo/pedidos?idPedido="+idPedido)).build();
    }
    static final class PedidoNoEncontrado extends RuntimeException {
        private static final long serialVersionUID=1L;
    }
}
