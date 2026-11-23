package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService.DatosAsignacion;
import ar.edu.unam.alemilanesas.ventas.GestionarRecorridoRepartoService;
import ar.edu.unam.alemilanesas.ventas.GestionarRecorridoRepartoService.RecorridoAbierto;
import ar.edu.unam.alemilanesas.ventas.GestionarRecorridoRepartoService.VinculacionRecorrido;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.math.BigInteger;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * H1 (C1-EXPLICITO / UC-43): endpoints auxiliares minimos para apertura de Recorrido,
 * asignacion+vinculacion (o vinculacion explicita) y cierre sin pendientes, exclusivos VENDEDOR
 * bajo gate independiente y bloqueo estricto en prod.
 */
@RestController
@RequestMapping("/api/v1/recorridos")
@Profile("!prod")
@ConditionalOnProperty(prefix = "ale.http.writes", name = "recorridos-uc43-enabled", havingValue = "true")
public class RecorridoRepartoHttpController {
    private static final String INVALIDA = "SOLICITUD_INVALIDA";
    private static final String REPARTIDOR = "idUsuarioRepartidor";
    private static final String REFERENCIA = "referenciaOperativa";
    private static final String PEDIDO = "idPedido";
    private static final String MOTIVO = "motivoReasignacion";
    private static final Set<String> CAMPOS_ABRIR = Set.of(REPARTIDOR, REFERENCIA);
    private static final Set<String> CAMPOS_ASIGNAR = Set.of(PEDIDO, REPARTIDOR, MOTIVO);
    private static final Set<String> CAMPOS_VINCULAR = Set.of(PEDIDO);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final GestionarRecorridoRepartoService recorridos;

    public RecorridoRepartoHttpController(GestionarRecorridoRepartoService recorridos) {
        this.recorridos = recorridos;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<RecorridoAbierto> abrir(
            Authentication autenticacion,
            @RequestBody String body) {
        var actor = actor(autenticacion);
        Map<String, Object> d = parse(body, CAMPOS_ABRIR);
        long idRepartidor = idRequerido(d.get(REPARTIDOR));
        Object refRaw = d.get(REFERENCIA);
        if (refRaw != null && !(refRaw instanceof String)) {
            throw new IllegalArgumentException(INVALIDA);
        }
        var res = recorridos.abrirRecorrido(actor.idSesion(), idRepartidor, (String) refRaw);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(res);
    }

    @PostMapping(path = "/{idRecorrido}/asignaciones", consumes = "application/json", produces = "application/json")
    public ResponseEntity<VinculacionRecorrido> asignarYVincular(
            Authentication autenticacion,
            @PathVariable long idRecorrido,
            @RequestBody String body) {
        var actor = actor(autenticacion);
        Map<String, Object> d = parse(body, CAMPOS_ASIGNAR);
        long idPedido = idRequerido(d.get(PEDIDO));
        long idRepartidor = idRequerido(d.get(REPARTIDOR));
        Object motivoRaw = d.get(MOTIVO);
        if (motivoRaw != null && !(motivoRaw instanceof String)) {
            throw new IllegalArgumentException(INVALIDA);
        }
        var res = recorridos.asignarYVincularRecorrido(
                actor.idSesion(),
                new DatosAsignacion(idPedido, idRepartidor, (String) motivoRaw),
                idRecorrido);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(res);
    }

    @PostMapping(path = "/{idRecorrido}/vinculaciones", consumes = "application/json", produces = "application/json")
    public ResponseEntity<VinculacionRecorrido> vincularExistente(
            Authentication autenticacion,
            @PathVariable long idRecorrido,
            @RequestBody String body) {
        var actor = actor(autenticacion);
        Map<String, Object> d = parse(body, CAMPOS_VINCULAR);
        long idPedido = idRequerido(d.get(PEDIDO));
        var res = recorridos.vincularPedidoARecorrido(actor.idSesion(), idRecorrido, idPedido);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(res);
    }

    @PostMapping(path = "/{idRecorrido}/cierre", produces = "application/json")
    public ResponseEntity<Map<String, Object>> cerrar(
            Authentication autenticacion,
            @PathVariable long idRecorrido) {
        var actor = actor(autenticacion);
        recorridos.cerrarRecorridoSinPendientes(actor.idSesion(), idRecorrido);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("idRecorrido", idRecorrido, "estadoOperativo", "CERRADO"));
    }

    private static SeguridadHttpInterna.PrincipalInterno actor(Authentication autenticacion) {
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor)) {
            throw new SecurityException("Sesion no valida");
        }
        return actor;
    }

    private static Map<String, Object> parse(String body, Set<String> permitidos) {
        Map<String, Object> d;
        try {
            d = JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException ex) {
            throw new IllegalArgumentException(INVALIDA, ex);
        }
        if (d == null || !permitidos.containsAll(d.keySet())) {
            throw new IllegalArgumentException(INVALIDA);
        }
        return d;
    }

    private static long idRequerido(Object valor) {
        if (!(valor instanceof BigInteger id)) {
            throw new IllegalArgumentException(INVALIDA);
        }
        try {
            return id.longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(INVALIDA, ex);
        }
    }
}

@RestControllerAdvice(assignableTypes = RecorridoRepartoHttpController.class)
class ErroresRecorridoRepartoHttp extends ErroresHttpGlobales {
    private record FalloHttp(HttpStatus estado, String codigo) {}

    private static final String REPARTIDOR_NO_ELEGIBLE = "REPARTIDOR_NO_ELEGIBLE";
    private static final Map<String, FalloHttp> ARGUMENTOS = Map.ofEntries(
            Map.entry("Repartidor inexistente", new FalloHttp(HttpStatus.BAD_REQUEST, REPARTIDOR_NO_ELEGIBLE)),
            Map.entry("Repartidor no habilitado", new FalloHttp(HttpStatus.BAD_REQUEST, REPARTIDOR_NO_ELEGIBLE)),
            Map.entry("Usuario sin rol REPARTIDOR", new FalloHttp(HttpStatus.BAD_REQUEST, REPARTIDOR_NO_ELEGIBLE)),
            Map.entry("Motivo de reasignación requerido", new FalloHttp(HttpStatus.BAD_REQUEST, "MOTIVO_REQUERIDO")),
            Map.entry("Motivo de reasignación no aplica en primera asignación", new FalloHttp(HttpStatus.BAD_REQUEST, "MOTIVO_NO_APLICA")),
            Map.entry("Sin cambio: mismo repartidor ya asignado", new FalloHttp(HttpStatus.CONFLICT, "SIN_CAMBIO")),
            Map.entry("Pedido no está PENDIENTE", new FalloHttp(HttpStatus.CONFLICT, "PEDIDO_NO_PENDIENTE")),
            Map.entry("Pedido inexistente", new FalloHttp(HttpStatus.NOT_FOUND, "PEDIDO_NO_ENCONTRADO")),
            Map.entry("PEDIDO_NO_ENCONTRADO", new FalloHttp(HttpStatus.NOT_FOUND, "PEDIDO_NO_ENCONTRADO")),
            Map.entry("RECORRIDO_NO_ENCONTRADO", new FalloHttp(HttpStatus.NOT_FOUND, "RECORRIDO_NO_ENCONTRADO")),
            Map.entry("REPARTIDOR_RECORRIDO_NO_COINCIDE", new FalloHttp(HttpStatus.CONFLICT, "REPARTIDOR_RECORRIDO_NO_COINCIDE")));

    @Override
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalidos(Exception ex) {
        String msg = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        FalloHttp error = ARGUMENTOS.getOrDefault(msg, new FalloHttp(HttpStatus.BAD_REQUEST, SOLICITUD_INVALIDA));
        return respuesta(error.estado(), error.codigo());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflicto(IllegalStateException ex) {
        String codigo = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        return switch (codigo) {
            case "RECORRIDO_ABIERTO_EXISTENTE", "RECORRIDO_NO_ABIERTO", "PEDIDO_NO_PENDIENTE",
                 "ASIGNACION_VIGENTE_REQUERIDA", "PERTENENCIA_ACTIVA_DUPLICADA",
                 "FA2_PEDIDOS_PENDIENTES_ACTIVOS", "RENDICION_PE2_PENDIENTE",
                 "CIERRE_SIN_PE2_NO_AUTORIZADO" -> respuesta(HttpStatus.CONFLICT, codigo);
            default -> respuesta(HttpStatus.CONFLICT, "CONFLICTO_RECORRIDO");
        };
    }
}
