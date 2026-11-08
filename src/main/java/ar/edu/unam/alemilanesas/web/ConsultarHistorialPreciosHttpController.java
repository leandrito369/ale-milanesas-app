package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.ConsultarHistorialPreciosService;
import ar.edu.unam.alemilanesas.maestros.ConsultarHistorialPreciosService.HistorialPrecios;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** I1-04 / UC20: consulta de ADMIN independiente de la bandera de escritura UC19. */
@RestController
@RequestMapping("/api/v1/productos/{idProducto}/precios")
public class ConsultarHistorialPreciosHttpController {
    private static final String DESDE = "desde";
    private static final String HASTA = "hasta";
    private static final Set<String> PARAMETROS = Set.of(DESDE,HASTA);
    private final ConsultarHistorialPreciosService precios;
    public ConsultarHistorialPreciosHttpController(ConsultarHistorialPreciosService precios) { this.precios = precios; }

    @GetMapping(produces="application/json")
    public ResponseEntity<HistorialPrecios> consultar(Authentication autenticacion, @PathVariable long idProducto,
            @RequestParam MultiValueMap<String,String> params) {
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        if (idProducto <= 0 || !PARAMETROS.containsAll(params.keySet())) throw new IllegalArgumentException("Parametro no admitido");
        for (var valores : params.values()) {
            if (valores.size() != 1 || valores.getFirst() == null || valores.getFirst().isBlank())
                throw new IllegalArgumentException("Parametro invalido");
        }
        LocalDate desde = fecha(params.getFirst(DESDE));
        LocalDate hasta = fecha(params.getFirst(HASTA));
        if (desde != null && hasta != null && desde.isAfter(hasta))
            throw new IllegalArgumentException("Rango de consulta invalido");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(precios.consultarHistorialPrecios(actor.idSesion(),idProducto,desde,hasta));
    }
    private static LocalDate fecha(String valor) { return valor == null ? null : LocalDate.parse(valor); }
}
