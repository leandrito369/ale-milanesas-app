package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService;
import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService.*;
import ar.edu.unam.alemilanesas.web.comun.ErroresHttpGlobales;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** UC34: GET sin gate, con whitelist de criterios y proyecciones BC07 decididas en B3. */
@RestController
@RequestMapping("/api/v1/pedidos")
public class BuscarPedidoHttpController {
    private static final String PEDIDO="idPedido";
    private static final String CLIENTE="idCliente";
    private static final String FECHA="fechaReparto";
    private static final String ESTADO="estado";
    private static final String CANAL="canalOrigen";
    private static final Set<String> PARAMETROS=Set.of(PEDIDO,CLIENTE,FECHA,ESTADO,CANAL);
    private final BuscarPedidoService pedidos;
    public BuscarPedidoHttpController(BuscarPedidoService pedidos) {this.pedidos=pedidos;}

    @GetMapping(produces="application/json")
    public ResponseEntity<List<PedidoResumen>> buscar(Authentication autenticacion,@RequestParam MultiValueMap<String,String> params) {
        if (autenticacion==null || !(autenticacion.getPrincipal() instanceof SeguridadHttpInterna.PrincipalInterno actor))
            throw new SecurityException("Sesion no valida");
        if (!PARAMETROS.containsAll(params.keySet())) throw new IllegalArgumentException("Parametro no admitido");
        for (var valores:params.values()) {
            if (valores.size()!=1 || valores.getFirst()==null || valores.getFirst().isBlank())
                throw new IllegalArgumentException("Parametro invalido");
        }
        var criterio=new CriterioBusquedaPedido(id(params.getFirst(PEDIDO)),id(params.getFirst(CLIENTE)),
                fecha(params.getFirst(FECHA)),params.getFirst(ESTADO),params.getFirst(CANAL));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pedidos.buscarPedido(actor.idSesion(),criterio));
    }
    private static Long id(String valor) {
        if (valor==null) return null;
        if (!valor.matches("[0-9]+")) throw new IllegalArgumentException("Identificador invalido");
        return Long.valueOf(valor);
    }
    private static LocalDate fecha(String valor) {return valor==null?null:LocalDate.parse(valor);}
}

@RestControllerAdvice(assignableTypes=BuscarPedidoHttpController.class)
class ErroresBuscarPedidoHttp extends ErroresHttpGlobales {
    @Override
    @ExceptionHandler({IllegalArgumentException.class,DateTimeException.class})
    public ResponseEntity<Map<String,String>> invalidos(Exception ex) {
        return respuesta(HttpStatus.BAD_REQUEST,SOLICITUD_INVALIDA);
    }
}
