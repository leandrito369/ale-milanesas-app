package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService.*;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
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
@ConditionalOnProperty(prefix="ale.http.writes",name="pedidos-uc03-enabled",havingValue="true")
public class CargarPedidoDemoController {
    private static final Set<String> CAMPOS;
    static {
        var campos=new HashSet<>(Set.of("idCliente","fechaReparto","claveIdempotencia"));
        for (String campo:FormularioDemo.DIRECCION) campos.add(campo);
        for (int i=0;i<3;i++) {
            campos.add("items["+i+"].idProducto");
            campos.add("items["+i+"].cantidadSolicitada");
        }
        CAMPOS=Set.copyOf(campos);
    }
    private final CargarPedidoService pedidos;
    public CargarPedidoDemoController(CargarPedidoService pedidos) {this.pedidos=pedidos;}
    @GetMapping("/web/demo/pedidos/nuevo")
    public String nuevo(@RequestParam MultiValueMap<String,String> params,Model model) {
        var datos=EntradaDemo.leer(params,Set.of("idCliente"));
        EntradaDemo.id(datos,"idCliente");
        datos.put("claveIdempotencia",UUID.randomUUID().toString());
        model.addAttribute("form",FormularioDemo.pedido(datos));
        return "demo/pedido-nuevo";
    }
    @PostMapping("/web/demo/pedidos")
    public Object cargar(Authentication auth, @RequestParam MultiValueMap<String,String> params,
            Model model, HttpServletResponse response) {
        // Preservar solo campos conocidos: jamás agregar sesión/CSRF/autoridad al modelo.
        var d=EntradaDemo.leer(params,CAMPOS);
        model.addAttribute("form",FormularioDemo.pedido(d));
        try {
            var resultado=pedidos.cargarPedido(EntradaDemo.sesion(auth),datos(d));
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create("/web/demo/pedidos?idPedido="+resultado.idPedido())).build();
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            response.setStatus(ex.getMessage()!=null && ex.getMessage().toLowerCase(Locale.ROOT).contains("deuda")?409:400);
            model.addAttribute("codigo",response.getStatus()==409?"CLIENTE_CON_DEUDA":"SOLICITUD_INVALIDA");
            model.addAttribute("mensajeError","Revisá los campos, cliente, fecha, productos y configuración comercial. Conservá la clave para reintentar el mismo pedido.");
            return "demo/pedido-nuevo";
        }
    }
    static DatosPedido datos(Map<String,String> d) {
        List<ItemPedido> items=new ArrayList<>();
        for (int i=0;i<3;i++) {
            String prefijo="items["+i+"].";
            String producto=EntradaDemo.opcional(d,prefijo+"idProducto");
            String cantidad=EntradaDemo.opcional(d,prefijo+"cantidadSolicitada");
            if (i>0 && producto==null && cantidad==null) continue;
            if (cantidad==null) throw new IllegalArgumentException("Cantidad requerida");
            items.add(new ItemPedido(EntradaDemo.idRequerido(d,prefijo+"idProducto"),new BigDecimal(cantidad)));
        }
        String clave=EntradaDemo.requerido(d,"claveIdempotencia");
        UUID uuid=UUID.fromString(clave);
        if (!uuid.toString().equalsIgnoreCase(clave)) throw new IllegalArgumentException("Clave invalida");
        return new DatosPedido(EntradaDemo.idRequerido(d,"idCliente"),LocalDate.parse(EntradaDemo.requerido(d,"fechaReparto")),
                List.copyOf(items),new DireccionEntrega(
                    EntradaDemo.requerido(d,"calle"),EntradaDemo.opcional(d,"numero"),
                    EntradaDemo.opcional(d,"piso"),EntradaDemo.opcional(d,"unidadFuncional"),
                    EntradaDemo.requerido(d,"localidad"),EntradaDemo.opcional(d,"provincia"),
                    EntradaDemo.opcional(d,"referencia"),EntradaDemo.opcional(d,"zona")),uuid);
    }
}
