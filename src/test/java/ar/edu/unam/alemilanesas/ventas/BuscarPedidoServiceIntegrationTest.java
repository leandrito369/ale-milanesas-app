package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@Transactional
class BuscarPedidoServiceIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired BuscarPedidoService servicio;
    @Autowired CargarPedidoService pedidos;
    @Autowired AsignarRepartoService reparto;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    BusquedaPedidoFixture f;
    @BeforeEach void fixture() {f=tx.execute(s->new BusquedaPedidoFixture(j,pedidos,reparto));}
    CriterioBusquedaPedido criterio(Long pedido,Long cliente,LocalDate fecha,String estado,String canal) {
        return new CriterioBusquedaPedido(pedido,cliente,fecha,estado,canal);
    }
    List<PedidoResumen> buscar(String rol,CriterioBusquedaPedido c) {return servicio.buscarPedido(f.sesion(rol),c);}
    List<Long> ids(List<PedidoResumen> resultado) {return resultado.stream().map(PedidoResumen::idPedido).toList();}
    @Test void adminVeTodosLosPedidos() {
        var r=buscar("ADMINISTRADOR",null);assertEquals(f.ids(),ids(r));
        assertTrue(r.stream().allMatch(PedidoAdministrativo.class::isInstance));
        assertEquals(6,PedidoAdministrativo.class.getRecordComponents().length);
        assertNotNull(((PedidoAdministrativo)r.getFirst()).fechaHoraCarga());
        // Permiso directo conserva su ámbito aunque el actor también tenga REPARTIDOR.
        j.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'REPARTIDOR',now())",f.origen.base+1);
        UUID sesion=nuevaSesion(f.origen.base+1);
        assertEquals(f.ids(),ids(servicio.buscarPedido(sesion,null)));
    }
    @Test void vendedorVeTodosLosPedidos() {
        var r=buscar("VENDEDOR",null);assertEquals(f.ids(),ids(r));
        var p=assertInstanceOf(PedidoComercial.class,r.getFirst());assertEquals("EN_MORA",p.categoriaComercialAplicada());
        assertEquals(j.queryForObject("SELECT \"totalOriginal\" FROM \"PEDIDO\" WHERE \"idPedido\"=?",java.math.BigDecimal.class,f.online),p.totalOriginal());
        assertEquals(8,PedidoComercial.class.getRecordComponents().length);
    }
    @Test void repartidorVeSoloSusPedidosAsignadosVigentes() {
        var r=buscar("REPARTIDOR",null);assertEquals(List.of(f.asignacion.pendiente),ids(r));
        assertInstanceOf(PedidoRepartidor.class,r.getFirst());assertEquals(5,PedidoRepartidor.class.getRecordComponents().length);
        var c=criterio(f.asignacion.pendiente,null,null,"CANCELADO",null);
        assertTrue(buscar("REPARTIDOR",c).isEmpty()); // Pedido propio, filtros sin coincidencias: A1, no A3.
    }
    @Test void repartidorNoVePedidosSinAsignacion() {
        assertFalse(ids(buscar("REPARTIDOR",null)).contains(f.asignacion.cancelado));
        UUID sesion=f.sesion("REPARTIDOR");var c=criterio(f.asignacion.cancelado,null,null,null,null);
        assertThrows(SecurityException.class,()->servicio.buscarPedido(sesion,c));
        assertTrue(buscar("REPARTIDOR",criterio(null,f.origen.base+1,null,null,null)).isEmpty());
    }
    @Test void repartidorNoVePedidosConAsignacionCerrada() {
        assertFalse(ids(buscar("REPARTIDOR",null)).contains(f.asignacion.entregado));
        UUID sesion=f.sesion("REPARTIDOR");var c=criterio(f.asignacion.entregado,null,null,null,null);
        assertThrows(SecurityException.class,()->servicio.buscarPedido(sesion,c));
        reparto.asignarOReasignar(f.origen.vendedor(),f.asignacion.datos(f.asignacion.segundo,"Cambio vigente"));
        assertTrue(buscar("REPARTIDOR",null).isEmpty());
    }
    @Test void rechazaProveedorYSesionInvalida() {
        for(String rol:List.of("PROVEEDOR")) {
            UUID sesion=f.sesion(rol);assertThrows(SecurityException.class,()->servicio.buscarPedido(sesion,null));
        }
        assertThrows(SecurityException.class,()->servicio.buscarPedido(null,null));
        UUID sesion=f.sesion("VENDEDOR");
        j.update("UPDATE \"SESION_USUARIO\" SET \"fechaHoraRevocacion\"=now() WHERE \"idSesionUsuario\"=?",sesion);
        assertThrows(SecurityException.class,()->servicio.buscarPedido(sesion,null));
    }
    @Test void sinCoincidenciasRetornaListaVacia() {
        assertTrue(buscar("ADMINISTRADOR",criterio(Long.MAX_VALUE,null,null,null,null)).isEmpty());
        assertTrue(buscar("VENDEDOR",criterio(null,Long.MAX_VALUE,null,null,null)).isEmpty());
    }
    @Test void filtraPorIdPedido() {assertEquals(List.of(f.online),ids(buscar("VENDEDOR",criterio(f.online,null,null,null,null))));}
    @Test void filtraPorIdCliente() {
        assertEquals(List.of(f.online),ids(buscar("VENDEDOR",criterio(null,f.origen.base+1,null,null,null))));
        assertEquals(3,buscar("ADMINISTRADOR",criterio(null,f.origen.base,null,null,null)).size());
    }
    @Test void filtraPorEstado() {
        assertEquals(List.of(f.asignacion.entregado),ids(buscar("VENDEDOR",criterio(null,null,null,"ENTREGADO",null))));
        assertEquals(List.of(f.asignacion.cancelado),ids(buscar("ADMINISTRADOR",criterio(null,null,null,"CANCELADO",null))));
    }
    @Test void filtraPorFechaReparto() {assertEquals(List.of(f.online),ids(buscar("VENDEDOR",criterio(null,null,f.origen.hoy.plusDays(3),null,null))));}
    @Test void filtraPorCanalOrigen() {
        assertEquals(List.of(f.online),ids(buscar("VENDEDOR",criterio(null,null,null,null,"ONLINE"))));
        assertEquals(3,buscar("VENDEDOR",criterio(null,null,null,null,"INTERNO")).size());
    }
    @Test void combinaMultiplesFiltros() {
        var c=criterio(f.online,f.origen.base+1,f.origen.hoy.plusDays(3),"PENDIENTE","ONLINE");
        assertEquals(List.of(f.online),ids(buscar("VENDEDOR",c)));
        assertTrue(buscar("VENDEDOR",criterio(f.online,f.origen.base+1,f.origen.hoy.plusDays(3),"CANCELADO","ONLINE")).isEmpty());
    }
    @Test void rechazaCriterioInvalido() {
        UUID sesion=f.sesion("VENDEDOR");
        for(var c:List.of(criterio(0L,null,null,null,null),criterio(-1L,null,null,null,null),criterio(null,0L,null,null,null),
                criterio(null,null,null,"INVALIDO",null),criterio(null,null,null,"",null),criterio(null,null,null,null,"INVALIDO")))
            assertThrows(IllegalArgumentException.class,()->servicio.buscarPedido(sesion,c));
    }
    @Test void rollbackNoAplicaPorqueConsultaNoModificaEstado() {
        // Categoría actual distinta del snapshot histórico: UC34 no debe reevaluar ni reescribirlo.
        j.update("UPDATE \"CLIENTE\" SET \"categoriaComercial\"='CUMPLIDOR' WHERE \"idCliente\"=?",f.origen.base+1);
        var antes=snapshot();
        buscar("ADMINISTRADOR",null);var comercial=buscar("VENDEDOR",null);buscar("REPARTIDOR",null);
        assertEquals("EN_MORA",((PedidoComercial)comercial.getFirst()).categoriaComercialAplicada());
        assertEquals(antes,snapshot());
    }
    @Test void produccionVeSoloPedidosConProductosElaborados() {
        var r=buscar("RESPONSABLE_PRODUCCION",null);
        assertEquals(List.of(f.online,f.asignacion.pendiente),ids(r));
        assertTrue(r.stream().allMatch(PedidoProduccion.class::isInstance));
        assertEquals(5,PedidoProduccion.class.getRecordComponents().length);
        var filtro=criterio(f.asignacion.pendiente,null,null,"CANCELADO",null);
        assertTrue(buscar("RESPONSABLE_PRODUCCION",filtro).isEmpty());
        // Agotar ambos detalles propios del pedido; no basta con que contenga ELABORADO.
        for(int n=0;n<2;n++) {
            long receta=f.origen.base+500+n;
            j.update("INSERT INTO \"RECETA\" VALUES (?,?)",receta,f.origen.base+n);
            j.update("INSERT INTO \"VERSION_RECETA\" VALUES (?,1,now()-interval '1 day',NULL,1,?)",receta,n==0?"UN":"KG");
            j.update("""
                    INSERT INTO "PREPARACION" ("idPreparacion","idPedido","numeroDetalle","idReceta","numeroVersion","fechaHoraPreparacion","cantidadPreparada")
                    SELECT ?,"idPedido","numeroDetalle",?,1,now(),"cantidadOriginal" FROM "DETALLE_PEDIDO"
                    WHERE "idPedido"=? AND "numeroDetalle"=?
                    """,f.origen.base+600+n,receta,f.asignacion.pendiente,n+1);
        }
        assertEquals(List.of(f.online),ids(buscar("RESPONSABLE_PRODUCCION",null)));
        j.update("UPDATE \"PEDIDO\" SET \"estado\"='ENTREGADO' WHERE \"idPedido\"=?",f.online);
        assertTrue(buscar("RESPONSABLE_PRODUCCION",null).isEmpty());
        // REPARTIDOR conserva su ámbito propio; VENDEDOR conserva prioridad y proyección comercial.
        j.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'REPARTIDOR',now())",f.origen.base+2);
        // El trigger de roles revoca sesiones: iniciar otra tras cada cambio de rol.
        assertTrue(servicio.buscarPedido(nuevaSesion(f.origen.base+2),null).isEmpty());
        j.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'VENDEDOR',now())",f.origen.base+2);
        var directo=servicio.buscarPedido(nuevaSesion(f.origen.base+2),null);
        assertEquals(f.ids(),ids(directo)); assertTrue(directo.stream().allMatch(PedidoComercial.class::isInstance));
    }
    @Test void produccionPidiendoPedidoSinProductosElaboradosRetorna403() {
        UUID sesion=f.sesion("RESPONSABLE_PRODUCCION");
        for(long id:new long[]{f.asignacion.entregado,f.asignacion.cancelado,Long.MAX_VALUE}) {
            var c=criterio(id,null,null,null,null);
            assertEquals("FUERA_DE_CONTEXTO",assertThrows(FueraDeContextoException.class,()->servicio.buscarPedido(sesion,c)).getMessage());
        }
        j.update("UPDATE \"PRODUCTO\" SET \"tipoProducto\"='REVENTA',\"stockActualReventa\"=0 WHERE \"idProducto\" IN (?,?)",f.origen.base,f.origen.base+1);
        var c=criterio(f.asignacion.pendiente,null,null,null,null);
        assertThrows(FueraDeContextoException.class,()->servicio.buscarPedido(sesion,c));
        assertTrue(buscar("RESPONSABLE_PRODUCCION",null).isEmpty());
    }
    private Map<String,String> snapshot() {
        var datos=new HashMap<String,String>();
        for(String tabla:List.of("PEDIDO","DETALLE_PEDIDO","ASIGNACION_REPARTO","CLIENTE","SESION_USUARIO","MOVIMIENTO_CUENTA","MOVIMIENTO_CAJA"))
            datos.put(tabla,j.queryForObject("SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]')) FROM \""+tabla+"\" t",String.class));
        return datos;
    }
    private UUID nuevaSesion(long usuario) {
        UUID sesion=UUID.randomUUID();
        j.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,?,?,now(),now()+interval '1 hour')
                """,sesion,usuario,sesion.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return sesion;
    }
}
