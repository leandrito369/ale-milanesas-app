package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.GestionInsumosService;
import ar.edu.unam.alemilanesas.maestros.GestionInsumosService.InsumoResultado;
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
@ConditionalOnProperty(prefix="ale.http.writes",name="insumos-uc51-enabled",havingValue="true")
public class ModificacionInsumoHttpController {
    private static final Set<String> CAMPOS = Set.of("nombre","codigoUnidadMedida");
    private final GestionInsumosService insumos;
    public ModificacionInsumoHttpController(GestionInsumosService insumos) { this.insumos = insumos; }

    @PutMapping(path="/{idInsumo}",consumes="application/json",produces="application/json")
    public ResponseEntity<InsumoResultado> modificar(@PathVariable long idInsumo,
            Authentication autenticacion,@RequestBody String body) {
        UUID sesion = CuerpoInsumoHttp.sesion(autenticacion);
        var d = CuerpoInsumoHttp.leer(body,CAMPOS);
        var r = insumos.modificarInsumo(sesion,idInsumo,CuerpoInsumoHttp.texto(d.get("nombre")),
                CuerpoInsumoHttp.texto(d.get("codigoUnidadMedida")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r);
    }
}

@RestControllerAdvice(assignableTypes=ModificacionInsumoHttpController.class)
class ErroresModificacionInsumoHttp extends ErroresInsumosHttp {}
