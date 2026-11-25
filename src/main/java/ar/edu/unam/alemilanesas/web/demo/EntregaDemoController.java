package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.DatosEntrega;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.ItemEntrega;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/** UC-05 original-only. Sin consulta de pedidos ajenos, sin proyección de deuda ni costos. */
@Controller
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="entregas-uc05-enabled",havingValue="true")
public class EntregaDemoController {
    private static final Set<String> CAMPOS;
    private static final int RENGLONES = 3; // mismo límite de la carga web de Pedido
    static {
        var c = new HashSet<>(Set.of("idPedido","efectivoDeclaradoRecibido","claveIdempotencia"));
        for (int i=0;i<RENGLONES;i++) {
            c.add("items["+i+"].idProducto"); c.add("items["+i+"].cantidadEntregada");
        }
        CAMPOS = Set.copyOf(c);
    }
    private final RegistrarEntregaService entregas;
    public EntregaDemoController(RegistrarEntregaService entregas) { this.entregas = entregas; }

    @GetMapping("/web/demo/entregar")
    public String formulario(@RequestParam MultiValueMap<String,String> params, Model model) {
        var d = EntradaDemo.leer(params, Set.of("idPedido"));
        EntradaDemo.id(d,"idPedido");
        d.put("claveIdempotencia",UUID.randomUUID().toString());
        modelo(d,model);
        return "demo/entregar";
    }

    @PostMapping("/web/demo/entregar")
    public Object registrar(Authentication auth, @RequestParam MultiValueMap<String,String> params,
            Model model, HttpServletResponse response, RedirectAttributes redirect) {
        var d = EntradaDemo.leer(params,CAMPOS);
        modelo(d,model);
        try {
            var r = entregas.registrarEntrega(EntradaDemo.sesion(auth), comando(d));
            // No exponer DTO financiero PE-1/IMP-B al HTML ni derivar éxito de parámetros de URL.
            redirect.addFlashAttribute("mensajeExito", r.creado()
                    ? "Entrega del Pedido #" + r.idPedido() + " registrada."
                    : "Entrega del Pedido #" + r.idPedido() + " ya registrada (reintento idempotente).");
            var vista = new RedirectView("/web/demo/entregar");
            vista.setContextRelative(true);
            vista.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(vista);
        } catch (IllegalArgumentException ex) {
            String codigo = Objects.toString(ex.getMessage(),"");
            response.setStatus("PEDIDO_NO_ENCONTRADO".equals(codigo) ? 404 : 400);
            model.addAttribute("mensajeError", switch(codigo) {
                case "PEDIDO_NO_ENCONTRADO" -> "El Pedido no está disponible.";
                case "PRODUCTO_NO_EN_PEDIDO" -> "El producto no pertenece a los originales del Pedido.";
                case "CANTIDAD_EXCEDE_ORIGINAL" -> "La cantidad excede la original.";
                case "CANTIDADES_EN_CERO" -> "Ingresá al menos una cantidad positiva.";
                default -> "Revisá los identificadores, las cantidades y el efectivo declarado.";
            });
            return "demo/entregar";
        } catch (IllegalStateException ex) {
            response.setStatus(409);
            model.addAttribute("mensajeError", switch(Objects.toString(ex.getMessage(),"")) {
                case "RECORRIDO_ACTIVO_REQUERIDO", "RECORRIDO_NO_ABIERTO", "ASIGNACION_RECORRIDO_CONFLICTO" ->
                    "No hay un Recorrido abierto compatible con la asignación vigente.";
                case "ADICIONALES_UC07_NO_SOPORTADOS" -> "Este corte admite solo originales; no se confirmó la Entrega.";
                case "IDEMPOTENCIA_CONFLICTO_DATOS" -> "La clave corresponde a otros datos. No se registró otra Entrega.";
                case "PEDIDO_NO_PENDIENTE" -> "El Pedido ya no está pendiente.";
                case "DISPONIBILIDAD_ELABORADO_INSUFICIENTE", "STOCK_REVENTA_INSUFICIENTE" ->
                    "No hay disponibilidad suficiente para entregar.";
                default -> "No se pudo registrar la Entrega con los datos indicados.";
            });
            return "demo/entregar";
        }
    }

    private static void modelo(Map<String,String> datos, Model model) {
        var form = new LinkedHashMap<String,String>();
        for (String campo : CAMPOS) form.put(campo, Objects.toString(datos.get(campo),""));
        model.addAttribute("form",form);
    }

    private static DatosEntrega comando(Map<String,String> d) {
        var items = new ArrayList<ItemEntrega>();
        for (int i=0;i<RENGLONES;i++) {
            String prefijo="items["+i+"].";
            String producto=EntradaDemo.opcional(d,prefijo+"idProducto");
            String cantidad=EntradaDemo.opcional(d,prefijo+"cantidadEntregada");
            if (producto==null && cantidad==null) continue;
            items.add(new ItemEntrega(EntradaDemo.idRequerido(d,prefijo+"idProducto"),
                    importe(EntradaDemo.requerido(d,prefijo+"cantidadEntregada"),3)));
        }
        if (items.isEmpty()) throw new IllegalArgumentException("CANTIDADES_EN_CERO");
        String clave=EntradaDemo.requerido(d,"claveIdempotencia");
        UUID uuid=UUID.fromString(clave);
        if (!uuid.toString().equalsIgnoreCase(clave)) throw new IllegalArgumentException("Clave invalida");
        String efectivo=EntradaDemo.opcional(d,"efectivoDeclaradoRecibido");
        return new DatosEntrega(EntradaDemo.idRequerido(d,"idPedido"),List.copyOf(items),
                efectivo==null?null:importe(efectivo,2),uuid);
    }
    private static BigDecimal importe(String valor,int escala) {
        if (!valor.matches("[0-9]{1,15}([.][0-9]{1,"+escala+"})?"))
            throw new IllegalArgumentException("Importe invalido");
        return new BigDecimal(valor);
    }
}
