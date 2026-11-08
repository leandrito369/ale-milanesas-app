package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers(disabledWithoutDocker=true)
@Transactional
class AsignarRepartoServiceIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired AsignarRepartoService servicio;
    @Autowired CargarPedidoService pedidos;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    AsignacionRepartoFixture f;
    UUID vendedor;
    @BeforeEach void fixture() {f=tx.execute(s->new AsignacionRepartoFixture(j,pedidos));vendedor=f.vendedor();}
    AsignacionRegistrada asignar(long repartidor,String motivo) {return servicio.asignarOReasignar(vendedor,f.datos(repartidor,motivo));}
    List<Map<String,Object>> historial() {return j.queryForList("SELECT * FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? ORDER BY \"numeroAsignacion\"",f.pendiente);}
    void assertRechazo(String mensaje,DatosAsignacion datos) {
        var ex=assertThrows(IllegalArgumentException.class,()->servicio.asignarOReasignar(vendedor,datos));
        assertEquals(mensaje,ex.getMessage());
    }
    @Test void primeraAsignacionCreaFilaConNumeroUno() {
        var r=asignar(f.primero,null);assertEquals(f.pendiente,r.idPedido());assertEquals(1,r.numeroAsignacion());
        assertFalse(r.fueReasignacion());assertEquals(f.primero,r.idUsuarioRepartidor());assertNotNull(r.fechaHoraInicio());
        var fila=historial().getFirst();assertNull(fila.get("fechaHoraFin"));assertNull(fila.get("motivoReasignacion"));
        assertEquals(f.origen.base,((Number)fila.get("idUsuarioVendedor")).longValue());
    }
    @Test void reasignacionCierraPreviaEInsertaNueva() {
        asignar(f.primero,null);var r=asignar(f.segundo,"  Cambio de turno  ");
        assertTrue(r.fueReasignacion());assertEquals(2,r.numeroAsignacion());
        var filas=historial();assertEquals(2,filas.size());assertNotNull(filas.getFirst().get("fechaHoraFin"));
        assertNull(filas.getLast().get("fechaHoraFin"));assertEquals("Cambio de turno",filas.getLast().get("motivoReasignacion"));
    }
    @Test void reasignacionPreservaHistorial() {
        var pedido=j.queryForMap("SELECT * FROM \"PEDIDO\" WHERE \"idPedido\"=?",f.pendiente);
        var detalles=j.queryForList("SELECT * FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=? ORDER BY \"numeroDetalle\"",f.pendiente);
        var producto=j.queryForMap("SELECT * FROM \"PRODUCTO\" WHERE \"idProducto\"=?",f.origen.base);
        var cuenta=j.queryForList("SELECT * FROM \"MOVIMIENTO_CUENTA\" ORDER BY \"idMovimientoCuenta\"");
        var caja=j.queryForList("SELECT * FROM \"MOVIMIENTO_CAJA\" ORDER BY \"idMovimientoCaja\"");
        asignar(f.primero,null);var anterior=new HashMap<>(historial().getFirst());asignar(f.segundo,"Disponibilidad");
        var cerrada=new HashMap<>(historial().getFirst());assertNotNull(cerrada.remove("fechaHoraFin"));anterior.remove("fechaHoraFin");
        assertEquals(anterior,cerrada);assertEquals(pedido,j.queryForMap("SELECT * FROM \"PEDIDO\" WHERE \"idPedido\"=?",f.pendiente));
        assertEquals(detalles,j.queryForList("SELECT * FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=? ORDER BY \"numeroDetalle\"",f.pendiente));
        assertEquals(producto,j.queryForMap("SELECT * FROM \"PRODUCTO\" WHERE \"idProducto\"=?",f.origen.base));
        assertEquals(cuenta,j.queryForList("SELECT * FROM \"MOVIMIENTO_CUENTA\" ORDER BY \"idMovimientoCuenta\""));
        assertEquals(caja,j.queryForList("SELECT * FROM \"MOVIMIENTO_CAJA\" ORDER BY \"idMovimientoCaja\""));
    }
    @Test void reasignacionIncrementaNumeroAsignacion() {
        assertEquals(1,asignar(f.primero,null).numeroAsignacion());
        assertEquals(2,asignar(f.segundo,"Reemplazo").numeroAsignacion());
        assertEquals(3,asignar(f.primero,"Regreso").numeroAsignacion());
        assertEquals(1,historial().stream().filter(x->x.get("fechaHoraFin")==null).count());
    }
    @Test void rechazaMismoRepartidorYaVigente() {
        asignar(f.primero,null);var antes=historial();
        assertRechazo("Sin cambio: mismo repartidor ya asignado",f.datos(f.primero,"No cambia"));assertEquals(antes,historial());
    }
    @Test void rechazaPedidoNoPendiente() {
        for(long id:new long[]{f.entregado,f.cancelado}) assertRechazo("Pedido no está PENDIENTE",new DatosAsignacion(id,f.primero,null));
        assertTrue(historial().isEmpty());
    }
    @Test void rechazaRepartidorDeshabilitado() {assertRechazo("Repartidor no habilitado",f.datos(f.deshabilitado,null));}
    @Test void rechazaRepartidorSinRol() {assertRechazo("Usuario sin rol REPARTIDOR",f.datos(f.origen.base+2,null));}
    @Test void rechazaRepartidorInexistente() {assertRechazo("Repartidor inexistente",f.datos(Long.MAX_VALUE,null));}
    @Test void rechazaPedidoInexistente() {assertRechazo("Pedido inexistente",new DatosAsignacion(Long.MAX_VALUE,f.primero,null));}
    @Test void reasignacionSinMotivoRechaza() {
        asignar(f.primero,null);var antes=historial();
        assertRechazo("Motivo de reasignación requerido",f.datos(f.segundo,null));
        assertRechazo("Motivo de reasignación requerido",f.datos(f.segundo,"   "));assertEquals(antes,historial());
    }
    @Test void primeraAsignacionConMotivoRechaza() {
        assertRechazo("Motivo de reasignación no aplica en primera asignación",f.datos(f.primero,"Cambio"));
        assertTrue(historial().isEmpty());
    }
    @Test void motivoReasignacionExcede250CaracteresRechaza() {
        assertRechazo("Solicitud invalida",f.datos(f.primero,"x".repeat(251)));
        assertRechazo("Solicitud invalida",f.datos(f.primero,"abc\0"));
        assertRechazo("Solicitud invalida",new DatosAsignacion(0,f.primero,null));
        assertRechazo("Solicitud invalida",f.datos(0,null));assertRechazo("Solicitud invalida",null);
        asignar(f.primero,null);assertEquals(2,asignar(f.segundo,"x".repeat(250)).numeroAsignacion());
    }
    @Test void rechazaRolAdministrador() {
        var datos=f.datos(f.primero,null);
        for(String rol:List.of("ADMINISTRADOR","RESPONSABLE_PRODUCCION","REPARTIDOR","PROVEEDOR")) {
            UUID sesion=f.origen.sesiones.get(rol);assertThrows(SecurityException.class,()->servicio.asignarOReasignar(sesion,datos));
        }
        assertThrows(SecurityException.class,()->servicio.asignarOReasignar(null,datos));assertTrue(historial().isEmpty());
    }
    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void rollbackSiFallaMitadNoPersisteNada() {
        asignar(f.primero,null);var antes=historial();
        j.execute("CREATE FUNCTION fallo_reparto_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fallo controlado'; END; $$");
        j.execute("CREATE TRIGGER fallo_reparto_test BEFORE INSERT ON \"ASIGNACION_REPARTO\" FOR EACH ROW EXECUTE FUNCTION fallo_reparto_test()");
        try {
            var datos=f.datos(f.segundo,"Reemplazo");
            assertThrows(DataAccessException.class,()->servicio.asignarOReasignar(vendedor,datos));assertEquals(antes,historial());
        } finally {j.execute("DROP TRIGGER fallo_reparto_test ON \"ASIGNACION_REPARTO\"");j.execute("DROP FUNCTION fallo_reparto_test()");}
    }
    @Test void timestampUnicoGarantizaFinMayorIgualInicio() {
        var primera=asignar(f.primero,null);var segunda=asignar(f.segundo,"Inmediata");
        OffsetDateTime fin=j.queryForObject("SELECT \"fechaHoraFin\" FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? AND \"numeroAsignacion\"=1",OffsetDateTime.class,f.pendiente);
        assertFalse(fin.isBefore(primera.fechaHoraInicio()));assertTrue(fin.isEqual(segunda.fechaHoraInicio()));
    }
}
