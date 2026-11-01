package ar.edu.unam.alemilanesas.web;

import java.util.List;
import java.util.UUID;

import ar.edu.unam.alemilanesas.maestros.BuscarClienteService;
import ar.edu.unam.alemilanesas.maestros.ClienteConsulta;
import ar.edu.unam.alemilanesas.maestros.CriterioBusquedaCliente;
import ar.edu.unam.alemilanesas.maestros.ConsultarInsumosUnidadesService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Primer slice HTTP B4: UC33 y UC36, solamente GET.
 * No acepta idSesion/roles/idUsuario por HTTP; identidad de Bearer autenticado.
 * Todo caso de uso revalida roles en B3 con la sesión original de MR5.
 */
@RestController
@RequestMapping("/api/v1")
public class ConsultasI1Controller {
    private final BuscarClienteService clientes;
    private final ConsultarInsumosUnidadesService insumos;

    public ConsultasI1Controller(BuscarClienteService clientes,
                                 ConsultarInsumosUnidadesService insumos) {
        this.clientes=clientes;
        this.insumos=insumos;
    }

    @GetMapping("/clientes")
    public ResponseEntity<List<ClienteConsulta>> clientes(
            Authentication authentication,
            @RequestParam(required=false) Long idCliente,
            @RequestParam(required=false) String nombre,
            @RequestParam(required=false) String telefonoPrincipal,
            @RequestParam(required=false) String zona,
            @RequestParam(required=false) String estado) {
        var filtros=new CriterioBusquedaCliente(
                idCliente,nombre,telefonoPrincipal,zona,estado);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clientes.buscarCliente(sesion(authentication),filtros));
    }

    @GetMapping("/insumos")
    public ResponseEntity<List<ConsultarInsumosUnidadesService.InsumoConsultado>> insumos(
            Authentication authentication,
            @RequestParam(required=false) Long idInsumo,
            @RequestParam(required=false) String nombre,
            @RequestParam(required=false) Boolean activo) {
        var filtros=new ConsultarInsumosUnidadesService.FiltroInsumo(idInsumo,nombre,activo);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(insumos.buscarInsumo(sesion(authentication),filtros));
    }

    @GetMapping("/unidades")
    public ResponseEntity<List<ConsultarInsumosUnidadesService.UnidadConsultada>> unidades(
            Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(insumos.listarUnidades(sesion(authentication)));
    }

    private static UUID sesion(Authentication authentication) {
        if(authentication==null ||
                !(authentication.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno id))
            throw new SecurityException("Sesión interna no autenticada");
        return id.idSesion();
    }
}
