package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.ConsultaComercialProveedorService;
import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * UC56 (cuenta) + UC37 (identidad comercial), únicamente Administrador.
 * Esta lectura no confirma ni marca automáticamente identidadComercialVerificada.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
public class ConsultaComercialProveedorHttpController {
    private final ConsultaComercialProveedorService proveedores;

    public ConsultaComercialProveedorHttpController(
            ConsultaComercialProveedorService proveedores) {
        this.proveedores=proveedores;
    }

    @GetMapping(path="/proveedores",produces="application/json")
    public ResponseEntity<List<ConsultaComercialProveedorService.IdentidadComercial>>
            buscar(Authentication autenticacion,
                   @RequestParam(required=false) Long idUsuario,
                   @RequestParam(required=false) String nombreUsuario,
                   @RequestParam(required=false) String cuit) {
        if (autenticacion==null || !(autenticacion.getPrincipal()
                instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new TokenSesionInterna.AccesoRechazado();
        }
        UUID sesion=actor.idSesion();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(proveedores.buscar(sesion,idUsuario,nombreUsuario,cuit));
    }
}

/** Errores neutros: no filtrar datos comerciales ni detalles de SQL. */
@RestControllerAdvice(assignableTypes=ConsultaComercialProveedorHttpController.class)
class ErroresConsultaComercialProveedorHttp extends ErroresHttpGlobales {
    @Override
    public ResponseEntity<Map<String,String>> persistencia() {
        return respuesta(HttpStatus.SERVICE_UNAVAILABLE, "CONSULTA_NO_DISPONIBLE");
    }
}
