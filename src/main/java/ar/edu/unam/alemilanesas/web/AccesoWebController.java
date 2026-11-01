package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.CambiarContrasenaService;
import ar.edu.unam.alemilanesas.acceso.SesionesUsuarioService;
import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

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

    public AccesoWebController(SesionesUsuarioService sesiones,
                              CambiarContrasenaService claves, LimiteIntentosLogin limite) {
        this.sesiones=sesiones;
        this.claves=claves;
        this.limite=limite;
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
            res.addHeader(HttpHeaders.SET_COOKIE,cookie(sesion.accessToken(),sesion.expiresIn()));
            return sesion.requiereCambioContrasena()?
                    "redirect:/web/cambiar-clave":"redirect:/web/inicio";
        } catch (TokenSesionInterna.AccesoRechazado ex) {
            limite.fallido(nombreUsuario,req.getRemoteAddr());
            return "redirect:/web/login?error";
        }
    }

    @GetMapping("/web/inicio")
    public String inicio(Authentication authentication) {
        if (restringido(authentication)) return "redirect:/web/cambiar-clave";
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
