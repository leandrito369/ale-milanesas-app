package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.finanzas.ConsultarCuentaCorrienteService;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** UC-14 de solo lectura. La autorización y el snapshot financiero viven en el servicio. */
@Controller
@Profile("!prod")
public class CuentaCorrienteDemoController {
    private final ConsultarCuentaCorrienteService cuentas;
    public CuentaCorrienteDemoController(ConsultarCuentaCorrienteService cuentas) {
        this.cuentas = cuentas;
    }

    @GetMapping("/web/demo/cuenta-corriente")
    public String consultar(Authentication auth, @RequestParam MultiValueMap<String,String> params, Model model) {
        var d = EntradaDemo.leer(params, Set.of("idCliente","fechaDesde","fechaHasta"));
        model.addAttribute("filtro", d);
        Long id = EntradaDemo.id(d, "idCliente");
        var desde = EntradaDemo.fecha(d, "fechaDesde");
        var hasta = EntradaDemo.fecha(d, "fechaHasta");
        if (id != null) {
            model.addAttribute("cuenta", cuentas.consultarCuentaCorriente(EntradaDemo.sesion(auth), id, desde, hasta));
        } else if (desde != null || hasta != null) {
            throw new IllegalArgumentException("Cliente requerido");
        }
        return "demo/cuenta-corriente";
    }
}
