package ar.edu.unam.alemilanesas.bootstrap;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Pantalla pública informativa de bootstrap B1. No consulta Clientes ni otras entidades. */
@Controller
public class HomeController {
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("etapa", "B1 · Arquitectura ejecutable");
        return "home";
    }
}
