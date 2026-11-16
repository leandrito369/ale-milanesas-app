package ar.edu.unam.alemilanesas.web.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Ilustrativa: sin dependencias de servicios, repositorios ni consultas. RBAC en cadena web. */
@Controller
@Profile("!prod")
public class MisEntregasDemoController {
    @GetMapping("/web/demo/mis-entregas")
    public String mostrar(Model model) {
        model.addAttribute("mensaje","Mis entregas — Módulo en preparación. La consulta de pedidos asignados estará disponible cuando UC-04 esté implementado y probado");
        model.addAttribute("aviso","UC04 aún no implementado");
        return "demo/mis-entregas";
    }
}
