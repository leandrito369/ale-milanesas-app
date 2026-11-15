package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.finanzas.RegistrarMovimientoCajaService;
import ar.edu.unam.alemilanesas.finanzas.RegistrarMovimientoCajaService.ClaveCajaManualEnConflictoException;
import ar.edu.unam.alemilanesas.finanzas.RegistrarMovimientoCajaService.Resultado;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** UC-22 B4 propuesta y experimental: solo no-prod, ADMIN y flag falso por defecto. */
@RestController
@Profile("!prod")
@ConditionalOnProperty(prefix = "ale.http.writes", name = "finanzas-uc22-enabled", havingValue = "true")
@RequestMapping("/api/v1/finanzas/movimientos-caja")
public class RegistrarMovimientoCajaHttpController {
    private static final Set<String> CAMPOS = Set.of("tipoMovimiento", "importe", "concepto", "observacion");
    private final RegistrarMovimientoCajaService caja;

    public RegistrarMovimientoCajaHttpController(RegistrarMovimientoCajaService caja) {
        this.caja = caja;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<Resultado> registrar(Authentication autenticacion,
            @RequestHeader("Idempotency-Key") String clave,
            @RequestBody Map<String, Object> cuerpo) {
        if (autenticacion == null
                || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        // La whitelist impide vincular este CU a Cliente, Cobro, Pedido o Reembolso.
        // La deteccion semantica de intencion en texto libre sigue pendiente de RD/CR.
        if (cuerpo == null || !CAMPOS.containsAll(cuerpo.keySet()) || !cuerpo.containsKey("tipoMovimiento")
                || !cuerpo.containsKey("importe") || !cuerpo.containsKey("concepto")) {
            throw new IllegalArgumentException("Cuerpo no admitido");
        }
        UUID idempotencia = UUID.fromString(clave);
        Resultado resultado = caja.registrarMovimientoCaja(actor.idSesion(),
                texto(cuerpo.get("tipoMovimiento")), decimal(cuerpo.get("importe")),
                texto(cuerpo.get("concepto")), textoOpcional(cuerpo.get("observacion")), idempotencia);
        return ResponseEntity.status(resultado.creado() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(resultado);
    }

    private static String texto(Object valor) {
        if (!(valor instanceof String texto)) throw new IllegalArgumentException("Texto invalido");
        return texto;
    }

    private static String textoOpcional(Object valor) {
        return valor == null ? null : texto(valor);
    }

    private static BigDecimal decimal(Object valor) {
        if (!(valor instanceof Number numero)) throw new IllegalArgumentException("Importe invalido");
        return new BigDecimal(numero.toString());
    }
}

@RestControllerAdvice(assignableTypes = RegistrarMovimientoCajaHttpController.class)
class ErroresRegistrarMovimientoCajaHttp extends ErroresHttpGlobales {
    @ExceptionHandler(ClaveCajaManualEnConflictoException.class)
    ResponseEntity<Map<String, String>> conflicto() {
        return respuesta(HttpStatus.CONFLICT, "CLAVE_OPERACION_EN_CONFLICTO");
    }
}
