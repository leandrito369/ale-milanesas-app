package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.GestionInsumosService;
import ar.edu.unam.alemilanesas.maestros.GestionInsumosService.EstadoInsumo;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/insumos")
@Profile("!prod")
@ConditionalOnProperty(prefix="ale.http.writes",name="insumos-uc52-enabled",havingValue="true")
public class EstadoInsumoHttpController {
    private final GestionInsumosService insumos;
    public EstadoInsumoHttpController(GestionInsumosService insumos) { this.insumos = insumos; }

    @PatchMapping(path="/{idInsumo}/estado",consumes="application/json",produces="application/json")
    public ResponseEntity<EstadoInsumo> cambiar(@PathVariable long idInsumo,
            Authentication autenticacion,@RequestBody String body) {
        UUID sesion = CuerpoInsumoHttp.sesion(autenticacion);
        var d = CuerpoInsumoHttp.leer(body,Set.of("activo"));
        if (!(d.get("activo") instanceof Boolean activo)) throw new IllegalArgumentException("Solicitud invalida");
        var r = insumos.cambiarEstadoInsumo(sesion,idInsumo,activo);
        if (!r.huboCambio()) throw new IllegalArgumentException("SIN_CAMBIO");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r);
    }
}

@RestControllerAdvice(assignableTypes=EstadoInsumoHttpController.class)
class ErroresEstadoInsumoHttp extends ErroresInsumosHttp {}
