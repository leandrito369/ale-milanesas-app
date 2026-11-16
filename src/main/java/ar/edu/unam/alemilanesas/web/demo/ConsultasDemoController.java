package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.maestros.*;
import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@Controller
@Profile("!prod")
public class ConsultasDemoController {
    private final BuscarClienteService clientes;
    private final BuscarProductoService productos;
    private final BuscarPedidoService pedidos;
    public ConsultasDemoController(BuscarClienteService clientes, BuscarProductoService productos,
            BuscarPedidoService pedidos) {
        this.clientes=clientes; this.productos=productos; this.pedidos=pedidos;
    }
    @GetMapping("/web/demo/clientes")
    public String clientes(Authentication auth, @RequestParam MultiValueMap<String,String> params, Model model) {
        var d=EntradaDemo.leer(params,Set.of("idCliente","nombre","telefonoPrincipal","zona","estado"));
        model.addAttribute("filtro",d);
        model.addAttribute("clientes",clientes.buscarCliente(EntradaDemo.sesion(auth),new CriterioBusquedaCliente(
                EntradaDemo.id(d,"idCliente"),EntradaDemo.opcional(d,"nombre"),EntradaDemo.opcional(d,"telefonoPrincipal"),
                EntradaDemo.opcional(d,"zona"),EntradaDemo.opcional(d,"estado"))));
        return "demo/clientes";
    }
    @GetMapping("/web/demo/productos")
    public String productos(Authentication auth, @RequestParam MultiValueMap<String,String> params, Model model) {
        var d=EntradaDemo.leer(params,Set.of("idProducto","nombre","tipoProducto","activo"));
        String activo=EntradaDemo.opcional(d,"activo");
        if (activo!=null && !Set.of("true","false").contains(activo)) throw new IllegalArgumentException("Activo invalido");
        model.addAttribute("filtro",d);
        model.addAttribute("avisoPrecio","Precio previo al pedido pendiente de implementación UC35. El precio final se determina en servidor al confirmar UC03.");
        model.addAttribute("productos",productos.buscarProducto(EntradaDemo.sesion(auth),new BuscarProductoService.CriterioProducto(
                EntradaDemo.id(d,"idProducto"),EntradaDemo.opcional(d,"nombre"),EntradaDemo.opcional(d,"tipoProducto"),
                activo==null?null:Boolean.valueOf(activo))));
        return "demo/productos";
    }
    @GetMapping("/web/demo/pedidos")
    public String pedidos(Authentication auth, @RequestParam MultiValueMap<String,String> params, Model model) {
        var d=EntradaDemo.leer(params,Set.of("idPedido","idCliente","fechaReparto","estado","canalOrigen"));
        model.addAttribute("filtro",d);
        model.addAttribute("pedidos",pedidos.buscarPedido(EntradaDemo.sesion(auth),new BuscarPedidoService.CriterioBusquedaPedido(
                EntradaDemo.id(d,"idPedido"),EntradaDemo.id(d,"idCliente"),EntradaDemo.fecha(d,"fechaReparto"),
                EntradaDemo.opcional(d,"estado"),EntradaDemo.opcional(d,"canalOrigen"))));
        return "demo/pedidos";
    }
}
