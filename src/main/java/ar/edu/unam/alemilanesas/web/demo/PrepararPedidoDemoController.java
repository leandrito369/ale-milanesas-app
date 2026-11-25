package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService.DatosPreparacion;
import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService;
import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService.CriterioBusquedaPedido;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Vertical G1 de UC-28. Reutiliza los servicios B3 integrados: no hay repositorio
 * paralelo ni datos simulados. La confirmacion mostrada proviene del id real
 * devuelto por RegistrarPreparacionService.
 */
@Controller
@Profile("!prod")
@ConditionalOnProperty(prefix = "ale.http.writes", name = "preparaciones-uc28-enabled", havingValue = "true")
public class PrepararPedidoDemoController {
    private static final Set<String> CAMPOS = Set.of("idProducto", "cantidadPreparada");
    private static final String AVISO = "UC-34 no expone el detalle del Pedido: informe el idProducto original y la cantidad a preparar. "
            + "El servicio valida en servidor producto, limite original, stock, partidas, receta y costo historico.";

    private final RegistrarPreparacionService preparaciones;
    private final BuscarPedidoService pedidos;

    public PrepararPedidoDemoController(RegistrarPreparacionService preparaciones, BuscarPedidoService pedidos) {
        this.preparaciones = preparaciones;
        this.pedidos = pedidos;
    }

    @GetMapping("/web/demo/preparaciones")
    public String seleccionar(@RequestParam MultiValueMap<String, String> params) {
        var datos = EntradaDemo.leer(params, Set.of("idPedido"));
        Long id = EntradaDemo.id(datos, "idPedido");
        // El GET contextual existente vuelve a validar ámbito en BuscarPedidoService.
        return id == null ? "demo/preparacion-seleccionar" : "redirect:/web/demo/pedidos/" + id + "/preparar";
    }

    @GetMapping("/web/demo/pedidos/{idPedido}/preparar")
    public String formulario(Authentication auth, @PathVariable long idPedido, Model model) {
        contexto(auth, idPedido, model);
        model.addAttribute("form", new LinkedHashMap<>(Map.of("idProducto", "", "cantidadPreparada", "")));
        model.addAttribute("aviso", AVISO);
        return "demo/pedido-preparar";
    }

    @PostMapping("/web/demo/pedidos/{idPedido}/preparar")
    public Object registrar(Authentication auth, @PathVariable long idPedido,
            @RequestParam MultiValueMap<String, String> params, Model model, HttpServletResponse response,
            RedirectAttributes redireccion) {
        Map<String, String> datos = EntradaDemo.leer(params, CAMPOS);
        model.addAttribute("form", datos);
        contexto(auth, idPedido, model);
        try {
            long idProducto = EntradaDemo.idRequerido(datos, "idProducto");
            BigDecimal cantidad = new BigDecimal(EntradaDemo.requerido(datos, "cantidadPreparada"));
            var resultado = preparaciones.registrarPreparacionPedido(EntradaDemo.sesion(auth),
                    new DatosPreparacion(idPedido, idProducto, cantidad, null));
            // Mensaje de un solo uso: viaja en la sesión del POST exitoso, nunca en la URL.
            redireccion.addFlashAttribute("mensajeExito",
                    "Preparación registrada con identificador real " + resultado.idPreparacion()
                            + " para el Pedido " + idPedido + ".");
            RedirectView vista = new RedirectView("/web/demo/pedidos/" + idPedido + "/preparar");
            vista.setContextRelative(true);
            vista.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(vista);
        } catch (IllegalArgumentException ex) {
            String codigo = codigo(ex.getMessage());
            response.setStatus(estado(codigo));
            model.addAttribute("codigo", codigo);
            model.addAttribute("mensajeError", mensaje(codigo));
            model.addAttribute("aviso", AVISO);
            return "demo/pedido-preparar";
        }
    }

    private void contexto(Authentication auth, long idPedido, Model model) {
        var lista = pedidos.buscarPedido(EntradaDemo.sesion(auth),
                new CriterioBusquedaPedido(idPedido, null, null, null, null));
        if (lista.isEmpty()) throw new IllegalArgumentException("Pedido inexistente");
        model.addAttribute("pedido", lista.getFirst());
        model.addAttribute("idPedido", idPedido);
    }

    private static String codigo(String mensaje) {
        String valor = mensaje == null ? "" : mensaje.trim();
        return switch (valor) {
            case "PEDIDO_NO_ENCONTRADO", "PEDIDO_NO_PENDIENTE", "PRODUCTO_NO_EN_PEDIDO",
                    "CANTIDAD_EXCEDE_ORIGINAL", "SIN_RECETA_VIGENTE", "RECETA_SIN_INSUMOS",
                    "INSUMO_INACTIVO", "UNIDAD_NO_COINCIDE", "CANTIDAD_CONSUMIDA_INSUFICIENTE",
                    "STOCK_UTILIZABLE_INSUFICIENTE", "COSTO_HISTORICO_REQUERIDO" -> valor;
            default -> "SOLICITUD_INVALIDA";
        };
    }

    private static int estado(String codigo) {
        return switch (codigo) {
            case "PEDIDO_NO_ENCONTRADO" -> HttpStatus.NOT_FOUND.value();
            case "PEDIDO_NO_PENDIENTE", "STOCK_UTILIZABLE_INSUFICIENTE", "COSTO_HISTORICO_REQUERIDO" ->
                    HttpStatus.CONFLICT.value();
            default -> HttpStatus.BAD_REQUEST.value();
        };
    }

    private static String mensaje(String codigo) {
        return switch (codigo) {
            case "PEDIDO_NO_ENCONTRADO" -> "El Pedido no existe o no está disponible para este actor.";
            case "PEDIDO_NO_PENDIENTE" -> "El Pedido ya no está PENDIENTE; no se puede preparar.";
            case "PRODUCTO_NO_EN_PEDIDO" -> "El producto informado no es un original del Pedido.";
            case "CANTIDAD_EXCEDE_ORIGINAL" -> "La cantidad supera la original pendiente de preparar.";
            case "SIN_RECETA_VIGENTE", "RECETA_SIN_INSUMOS" -> "No hay receta vigente válida para el producto.";
            case "INSUMO_INACTIVO", "UNIDAD_NO_COINCIDE" -> "Un insumo de la receta no es elegible.";
            case "CANTIDAD_CONSUMIDA_INSUFICIENTE" -> "La cantidad a consumir no es válida.";
            case "STOCK_UTILIZABLE_INSUFICIENTE" -> "No hay stock utilizable suficiente en las partidas.";
            case "COSTO_HISTORICO_REQUERIDO" -> "Falta el costo histórico vigente del insumo.";
            default -> "Revisá el producto y la cantidad informados.";
        };
    }
}
