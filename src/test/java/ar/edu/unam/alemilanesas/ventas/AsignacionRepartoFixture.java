package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService.DatosAsignacion;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** El llamador crea y confirma el fixture antes de abrir transacciones concurrentes o HTTP. */
public final class AsignacionRepartoFixture {
    public final PedidoFixture origen;
    public final long pendiente;
    public final long entregado;
    public final long cancelado;
    public final long primero;
    public final long segundo;
    public final long tercero;
    public final long deshabilitado;
    public AsignacionRepartoFixture(JdbcTemplate j,CargarPedidoService pedidos) {
        origen=new PedidoFixture(j);
        primero=origen.base+3;segundo=origen.base+10;deshabilitado=origen.base+11;tercero=origen.base+12;
        for(long id:new long[]{segundo,deshabilitado,tercero}) {
            j.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado",
                        "cambioCredencialPendiente","fechaHoraAlta","fechaHoraDeshabilitacion")
                    VALUES (?,?,'HASH_TEST',?,false,now(),CASE WHEN ? THEN now() ELSE NULL END)
                    """,id,"repartidor_uc43_"+id,id==deshabilitado?"DESHABILITADO":"HABILITADO",id==deshabilitado);
            j.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'REPARTIDOR',now())",id);
        }
        pendiente=pedidos.cargarPedido(vendedor(),origen.datos()).idPedido();
        entregado=pedidos.cargarPedido(vendedor(),origen.datos()).idPedido();
        cancelado=pedidos.cargarPedido(vendedor(),origen.datos()).idPedido();
        j.update("UPDATE \"PEDIDO\" SET \"estado\"='ENTREGADO' WHERE \"idPedido\"=?",entregado);
        j.update("UPDATE \"PEDIDO\" SET \"estado\"='CANCELADO' WHERE \"idPedido\"=?",cancelado);
    }
    public UUID vendedor() {return origen.vendedor();}
    public UUID admin() {return origen.sesiones.get("ADMINISTRADOR");}
    public DatosAsignacion datos(long repartidor,String motivo) {return new DatosAsignacion(pendiente,repartidor,motivo);}
    public Map<String,Object> body(long repartidor) {return Map.of("idUsuarioRepartidor",repartidor);}
}
