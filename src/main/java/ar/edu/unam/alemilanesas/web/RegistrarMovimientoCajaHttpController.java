package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.finanzas.RegistrarMovimientoCajaService;
import ar.edu.unam.alemilanesas.finanzas.RegistrarMovimientoCajaService.ClaveCajaManualEnConflictoException;
import ar.edu.unam.alemilanesas.finanzas.RegistrarMovimientoCajaService.Resultado;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** UC-22 B4 propuesta y experimental: solo no-prod, ADMIN y flag falso por defecto. */
@RestController
@Profile("!prod")
@ConditionalOnProperty(prefix = "ale.http.writes", name = "finanzas-uc22-enabled", havingValue = "true")
@RequestMapping("/api/v1/finanzas/movimientos-caja")
public class RegistrarMovimientoCajaHttpController {
    private static final String CAMPO_TIPO = "tipoMovimiento";
    private static final String CAMPO_IMPORTE = "importe";
    private static final String CAMPO_CONCEPTO = "concepto";
    private static final String CAMPO_OBSERVACION = "observacion";
    private static final String CUERPO_NO_ADMITIDO = "Cuerpo no admitido";
    private static final Set<String> CAMPOS = Set.of(
            CAMPO_TIPO, CAMPO_IMPORTE, CAMPO_CONCEPTO, CAMPO_OBSERVACION);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final RegistrarMovimientoCajaService caja;

    public RegistrarMovimientoCajaHttpController(RegistrarMovimientoCajaService caja) {
        this.caja = caja;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<Resultado> registrar(Authentication autenticacion,
            @RequestHeader("Idempotency-Key") String clave,
            @RequestBody String body) {
        if (autenticacion == null
                || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        Map<String, Object> cuerpo;
        try {
            cuerpo = JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException ex) {
            throw new IllegalArgumentException(CUERPO_NO_ADMITIDO, ex);
        }
        // La whitelist impide vincular este CU a Cliente, Cobro, Pedido o Reembolso.
        // La deteccion semantica de intencion en texto libre sigue pendiente de RD/CR.
        if (cuerpo == null || !CAMPOS.containsAll(cuerpo.keySet()) || !cuerpo.containsKey(CAMPO_TIPO)
                || !cuerpo.containsKey(CAMPO_IMPORTE) || !cuerpo.containsKey(CAMPO_CONCEPTO)) {
            throw new IllegalArgumentException(CUERPO_NO_ADMITIDO);
        }
        UUID idempotencia = UUID.fromString(clave);
        Resultado resultado = caja.registrarMovimientoCaja(actor.idSesion(),
                texto(cuerpo.get(CAMPO_TIPO)), decimal(cuerpo.get(CAMPO_IMPORTE)),
                texto(cuerpo.get(CAMPO_CONCEPTO)), textoOpcional(cuerpo.get(CAMPO_OBSERVACION)), idempotencia);
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
        if (valor instanceof BigDecimal numero) {
            return numero;
        }
        if (valor instanceof BigInteger entero) {
            return new BigDecimal(entero);
        }
        throw new IllegalArgumentException("Importe invalido");
    }
}

@RestControllerAdvice(assignableTypes = RegistrarMovimientoCajaHttpController.class)
class ErroresRegistrarMovimientoCajaHttp extends ErroresHttpGlobales {
    @ExceptionHandler(ClaveCajaManualEnConflictoException.class)
    ResponseEntity<Map<String, String>> conflicto() {
        return respuesta(HttpStatus.CONFLICT, "CLAVE_OPERACION_EN_CONFLICTO");
    }
}
