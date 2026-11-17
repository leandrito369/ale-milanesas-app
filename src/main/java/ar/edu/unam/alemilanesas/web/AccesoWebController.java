package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.CambiarContrasenaService;
import ar.edu.unam.alemilanesas.acceso.SesionesUsuarioService;
import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

import org.springframework.core.env.Environment;
import org.springframework.ui.Model;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Sesion web basada en cookie protegida. No incorpora el Bearer en HTML ni JS.
 * CSRF separado en HttpSession por la cadena web.
 */
@Controller
public class AccesoWebController {
    private final SesionesUsuarioService sesiones;
    private final CambiarContrasenaService claves;
    private final LimiteIntentosLogin limite;
    private final Environment env;

    public AccesoWebController(SesionesUsuarioService sesiones,
                              CambiarContrasenaService claves, LimiteIntentosLogin limite, Environment env) {
        this.sesiones=sesiones;
        this.claves=claves;
        this.limite=limite;
        this.env=env;
    }

    @GetMapping("/web/login")
    public String loginFormulario() {return "web-login";}

    @PostMapping("/web/login")
    public String login(@RequestParam String nombreUsuario,@RequestParam String credencial,
                        HttpServletRequest req,HttpServletResponse res) {
        limite.verificar(nombreUsuario,req.getRemoteAddr());
        try {
            var sesion=sesiones.iniciarSesion(nombreUsuario,credencial);
            limite.exitoso(nombreUsuario,req.getRemoteAddr());
            // Login real: conservar protección de fijación sin repetirla en cada cookie.
            var estrategia = new org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy(
                    java.util.List.of(
                        new org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy(),
                        new org.springframework.security.web.csrf.CsrfAuthenticationStrategy(
                            new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository())));
            estrategia.onAuthentication(
                    org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                        nombreUsuario,null,java.util.List.of()),req,res);
            res.addHeader(HttpHeaders.SET_COOKIE,cookie(sesion.accessToken(),sesion.expiresIn()));
            return sesion.requiereCambioContrasena()?
                    "redirect:/web/cambiar-clave":"redirect:/web/inicio";
        } catch (TokenSesionInterna.AccesoRechazado ex) {
            limite.fallido(nombreUsuario,req.getRemoteAddr());
            return "redirect:/web/login?error";
        }
    }

    @GetMapping("/web/inicio")
    public String inicio(Authentication authentication, Model model) {
        if (restringido(authentication)) return "redirect:/web/cambiar-clave";
        var roles=authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        boolean vendedor=roles.contains("VENDEDOR");
        boolean demo=!env.matchesProfiles("prod");
        model.addAttribute("esAdmin",roles.contains("ADMINISTRADOR"));
        model.addAttribute("esVendedor",vendedor);
        model.addAttribute("esRepartidor",roles.contains("REPARTIDOR"));
        model.addAttribute("demoDisponible",demo);
        // Indicadores de presentación: no sustituyen los controles de cada ruta.
        model.addAttribute("uc03Disponible",demo && vendedor
                && env.getProperty("ale.http.writes.pedidos-uc03-enabled",Boolean.class,false));
        model.addAttribute("uc43Disponible",demo && vendedor
                && env.getProperty("ale.http.writes.pedidos-uc43-enabled",Boolean.class,false));
        return "web-inicio";
    }

    @GetMapping("/web/cambiar-clave")
    public String formularioCambio(Authentication authentication) {
        sesion(authentication);
        return "web-cambiar-clave";
    }

    @PostMapping("/web/cambiar-clave")
    public String cambioClave(Authentication authentication,
                              @RequestParam String nuevaCredencial,
                              @RequestParam String confirmacionNuevaCredencial,
                              @RequestParam(required=false) String credencialActual,
                              HttpServletResponse res) {
        claves.cambiarContrasena(sesion(authentication),nuevaCredencial,
                confirmacionNuevaCredencial,credencialActual);
        res.addHeader(HttpHeaders.SET_COOKIE,cookie("",0));
        return "redirect:/web/login?cambiada";
    }

    @PostMapping("/web/renew")
    public String renovar(Authentication authentication,HttpServletRequest req,
                          HttpServletResponse res) {
        if (restringido(authentication)) throw new SecurityException("Acceso no autorizado");
        String actual=SeguridadWebInterna.tokenCookie(req);
        if (actual==null) throw new TokenSesionInterna.AccesoRechazado();
        var nuevo=sesiones.renovar(sesion(authentication),actual);
        res.addHeader(HttpHeaders.SET_COOKIE,cookie(nuevo.accessToken(),nuevo.expiresIn()));
        return "redirect:/web/inicio";
    }

    @PostMapping("/web/logout")
    public String logout(Authentication authentication,HttpServletResponse res) {
        sesiones.cerrarSesion(sesion(authentication));
        res.addHeader(HttpHeaders.SET_COOKIE,cookie("",0));
        return "redirect:/web/login";
    }

    private static UUID sesion(Authentication auth) {
        if (auth==null || !(auth.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno p))
            throw new TokenSesionInterna.AccesoRechazado();
        return p.idSesion();
    }

    private static boolean restringido(Authentication auth) {
        return auth!=null && auth.getAuthorities().stream().anyMatch(
                x->SeguridadHttpInterna.ROL_CAMBIO_PENDIENTE.equals(x.getAuthority()));
    }

    private static String cookie(String token,int segundos) {
        return ResponseCookie.from(SeguridadWebInterna.COOKIE_SESION,token)
                .secure(true).httpOnly(true).sameSite("Strict")
                .path("/").maxAge(segundos).build().toString();
    }
}
