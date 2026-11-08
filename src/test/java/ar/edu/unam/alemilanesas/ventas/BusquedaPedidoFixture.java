package ar.edu.unam.alemilanesas.ventas;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Datos sintéticos de ambos canales; no implementa el alta ONLINE de UC21. */
public final class BusquedaPedidoFixture {
    public final AsignacionRepartoFixture asignacion;
    public final PedidoFixture origen;
    public final long online;
    public BusquedaPedidoFixture(JdbcTemplate j,CargarPedidoService pedidos,AsignarRepartoService reparto) {
        asignacion=new AsignacionRepartoFixture(j,pedidos);origen=asignacion.origen;
        reparto.asignarOReasignar(origen.vendedor(),asignacion.datos(asignacion.primero,null));
        // Historia cerrada de A; B conserva la vigente. El cambio de estado prepara datos, no ejecuta Entrega.
        j.update("UPDATE \"PEDIDO\" SET \"estado\"='PENDIENTE' WHERE \"idPedido\"=?",asignacion.entregado);
        reparto.asignarOReasignar(origen.vendedor(),new AsignarRepartoService.DatosAsignacion(asignacion.entregado,asignacion.primero,null));
        reparto.asignarOReasignar(origen.vendedor(),new AsignarRepartoService.DatosAsignacion(asignacion.entregado,asignacion.segundo,"Fixture"));
        j.update("UPDATE \"PEDIDO\" SET \"estado\"='ENTREGADO' WHERE \"idPedido\"=?",asignacion.entregado);
        var d=origen.datos();
        online=pedidos.cargarPedido(origen.vendedor(),new CargarPedidoService.DatosPedido(origen.base+1,origen.hoy.plusDays(3),
                d.items(),d.direccionEntrega(),d.claveIdempotencia())).idPedido();
        UUID desafio=UUID.randomUUID();UUID sesion=UUID.randomUUID();
        j.update("""
                INSERT INTO "DESAFIO_AUTENTICACION_CLIENTE" ("idDesafio","idCliente","telefonoDestino","codigoProtegido",
                    "fechaHoraCreacion","fechaHoraExpiracion","intentosFallidos","fechaHoraValidacion")
                VALUES (?,?,'3764003400',?,now()-interval '1 minute',now()+interval '1 hour',0,now())
                """,desafio,origen.base+1,new byte[]{1,2,3});
        j.update("""
                INSERT INTO "SESION_CLIENTE_ONLINE" ("idSesionOnline","idDesafio","tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,?,?,now(),now()+interval '1 hour')
                """,sesion,desafio,sesion.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        j.update("UPDATE \"PEDIDO\" SET \"canalOrigen\"='ONLINE',\"idUsuarioCreador\"=NULL,\"idSesionOnline\"=? WHERE \"idPedido\"=?",sesion,online);
        j.update("UPDATE \"PEDIDO\" SET \"fechaHoraCarga\"=now() WHERE \"idPedido\" IN (?,?,?,?)",
                asignacion.pendiente,asignacion.entregado,asignacion.cancelado,online);
    }
    public UUID sesion(String rol) {return origen.sesiones.get(rol);}
    public String token(String rol) {return origen.tokens.get(rol);}
    public List<Long> ids() {return List.of(online,asignacion.cancelado,asignacion.entregado,asignacion.pendiente);}
}
