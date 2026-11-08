package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.finanzas.AplicarAjusteComercialService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService.*;
import java.math.BigDecimal;
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
@Testcontainers
@Transactional
class CargarPedidoServiceIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired CargarPedidoService pedidos;
    @Autowired AplicarAjusteComercialService ajustes;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    PedidoFixture f;
    UUID vendedor;
    @BeforeEach void fixture() {f=tx.execute(s->new PedidoFixture(j));vendedor=f.vendedor();}
    PedidoCreado cargar() {return pedidos.cargarPedido(vendedor,f.datos());}
    DatosPedido items(List<ItemPedido> items) {
        var d=f.datos();return new DatosPedido(d.idCliente(),d.fechaReparto(),items,d.direccionEntrega(),d.claveIdempotencia());
    }
    DatosPedido cliente(long id) {
        var d=f.datos();return new DatosPedido(id,d.fechaReparto(),d.items(),d.direccionEntrega(),d.claveIdempotencia());
    }
    Map<String,Object> fila(long id) {return j.queryForMap("SELECT * FROM \"PEDIDO\" WHERE \"idPedido\"=?",id);}
    int contarPedidos() {return j.queryForObject("SELECT count(*) FROM \"PEDIDO\" WHERE \"idCliente\"=?",Integer.class,f.base);}
    @Test void cargarPedidoSimpleRetornaPedidoPendiente() {
        var p=cargar();assertTrue(p.creado());assertEquals("PENDIENTE",p.estado());assertEquals("INTERNO",p.canalOrigen());
        assertEquals(f.hoy.plusDays(2),p.fechaReparto());assertEquals(2,p.cantidadItems());assertEquals(1,contarPedidos());
        assertEquals(List.of(1,2),j.queryForList("SELECT \"numeroDetalle\" FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=? ORDER BY \"numeroDetalle\"",Integer.class,p.idPedido()));
        assertEquals("Calle UC03",fila(p.idPedido()).get("direccionEntregaCalle"));
        j.update("UPDATE \"CLIENTE\" SET \"calle\"='Otra calle' WHERE \"idCliente\"=?",f.base);
        assertEquals("Calle UC03",fila(p.idPedido()).get("direccionEntregaCalle"));
    }
    @Test void cargarPedidoCongelaPrecioVigente() {
        // Un precio futuro cancelado no desplaza al precio vigente.
        j.update("""
                INSERT INTO "PRECIO_PRODUCTO" ("idPrecio","idProducto","precioBase","vigenteDesde","fechaHoraRegistro",
                    "idUsuarioRegistro","fechaHoraCancelacion","idUsuarioCancelacion")
                VALUES (?,?,999,now()+interval '1 day',now(),?,now(),?)
                """,f.base+10,f.base,f.base+1,f.base+1);
        var p=cargar();
        j.update("UPDATE \"PRECIO_PRODUCTO\" SET \"precioBase\"=500 WHERE \"idPrecio\"=?",f.base);
        assertEquals(new BigDecimal("100.0000"),j.queryForObject("SELECT \"precioUnitarioOriginal\" FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=? AND \"numeroDetalle\"=1",BigDecimal.class,p.idPedido()));
        assertEquals(new BigDecimal("0.00"),j.queryForObject("SELECT \"porcentajePromocionOriginal\" FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=? AND \"numeroDetalle\"=1",BigDecimal.class,p.idPedido()));
    }
    @Test void cargarPedidoCongelaCodigoUnidadVenta() {
        var p=cargar();j.update("UPDATE \"PRODUCTO\" SET \"codigoUnidadVenta\"='UN' WHERE \"idProducto\"=?",f.base+1);
        assertEquals(List.of("UN","KG"),j.queryForList("SELECT \"codigoUnidadVenta\" FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=? ORDER BY \"numeroDetalle\"",String.class,p.idPedido()));
    }
    @Test void cargarPedidoAplicaAjusteCumplidor() {
        var p=cargar();var fila=fila(p.idPedido());assertEquals("CUMPLIDOR",p.categoriaComercialAplicada());
        assertEquals("DESCUENTO",fila.get("tipoAjuste"));assertEquals(new BigDecimal("10.00"),fila.get("porcentajeAjuste"));
        assertEquals(f.politica,((Number)fila.get("idPoliticaComercialAplicada")).longValue());
        assertEquals(new BigDecimal("450.00"),p.totalOriginal());
    }
    @Test void cargarPedidoAplicaAjusteEnMora() {
        var d=cliente(f.base+1);var p=pedidos.cargarPedido(vendedor,d);var fila=fila(p.idPedido());
        assertEquals("EN_MORA",p.categoriaComercialAplicada());assertEquals("RECARGO",fila.get("tipoAjuste"));
        assertEquals(new BigDecimal("20.00"),fila.get("porcentajeAjuste"));assertEquals(new BigDecimal("600.00"),p.totalOriginal());
        assertEquals("EN_MORA",j.queryForObject("SELECT \"categoriaComercial\" FROM \"CLIENTE\" WHERE \"idCliente\"=?",String.class,f.base+1));
    }
    @Test void cargarPedidoCalculaTotalOriginalDespuesDeAjuste() {
        j.update("UPDATE \"PRECIO_PRODUCTO\" SET \"precioBase\"=1.2345 WHERE \"idPrecio\"=?",f.base);
        var d=items(List.of(new ItemPedido(f.base,new BigDecimal("1.111"))));var p=pedidos.cargarPedido(vendedor,d);
        // 1.2345 * 1.111 = 1.3715295; base monetaria 1.37, ajuste 0.14, total congelado 1.23.
        assertEquals(new BigDecimal("1.23"),p.totalOriginal());
        assertEquals(p.totalOriginal(),fila(p.idPedido()).get("totalOriginal"));
    }
    @Test void cargarPedidoRespetaCkPedido5() {
        var p=cargar();var fila=fila(p.idPedido());assertEquals("INTERNO",fila.get("canalOrigen"));
        assertNull(fila.get("idSesionOnline"));assertEquals(f.base,((Number)fila.get("idUsuarioCreador")).longValue());
        assertNotNull(fila.get("fechaHoraCarga"));assertNull(fila.get("motivoCancelacion"));
    }
    @Test void idempotenciaClaveDuplicadaRetornaMismoPedido() {
        var d=f.datos();var primero=pedidos.cargarPedido(vendedor,d);
        j.update("UPDATE \"CLIENTE\" SET \"estado\"='INACTIVO' WHERE \"idCliente\"=?",f.base);
        j.update("UPDATE \"PRODUCTO\" SET \"activo\"=false WHERE \"idProducto\"=?",f.base);
        var segundo=pedidos.cargarPedido(f.sesiones.get("VENDEDOR_2"),d);
        assertEquals(primero.idPedido(),segundo.idPedido());assertFalse(segundo.creado());assertEquals(1,contarPedidos());
        assertEquals(primero.totalOriginal(),segundo.totalOriginal());
        assertEquals(2,j.queryForObject("SELECT count(*) FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=?",Integer.class,primero.idPedido()));
        var otroCliente=new DatosPedido(f.base+1,d.fechaReparto(),d.items(),d.direccionEntrega(),d.claveIdempotencia());
        assertThrows(IllegalArgumentException.class,()->pedidos.cargarPedido(vendedor,otroCliente));
    }
    @Test void rechazaClienteInexistenteOInactivo() {
        for(long id:new long[]{f.base+2,f.base+99}) {
            var d=cliente(id);assertThrows(IllegalArgumentException.class,()->pedidos.cargarPedido(vendedor,d));
        }
        assertEquals(0,contarPedidos());
    }
    @Test void rechazaFechaRepartoRetroactiva() {
        var d=f.datos();var pasado=new DatosPedido(d.idCliente(),f.hoy.minusDays(1),d.items(),d.direccionEntrega(),d.claveIdempotencia());
        assertThrows(IllegalArgumentException.class,()->pedidos.cargarPedido(vendedor,pasado));
        var hoy=new DatosPedido(d.idCliente(),f.hoy,d.items(),d.direccionEntrega(),d.claveIdempotencia());
        assertEquals(f.hoy,pedidos.cargarPedido(vendedor,hoy).fechaReparto());
    }
    @Test void rechazaItemsVacios() {
        var d=items(List.of());assertThrows(IllegalArgumentException.class,()->pedidos.cargarPedido(vendedor,d));
        var repetido=items(List.of(new ItemPedido(f.base,BigDecimal.ONE),new ItemPedido(f.base,BigDecimal.ONE)));
        assertThrows(IllegalArgumentException.class,()->pedidos.cargarPedido(vendedor,repetido));
    }
    @Test void rechazaProductoInexistenteOInactivo() {
        for(long id:new long[]{f.base+4,f.base+99}) {
            var d=items(List.of(new ItemPedido(id,BigDecimal.ONE)));
            assertThrows(IllegalArgumentException.class,()->pedidos.cargarPedido(vendedor,d));
        }
    }
    @Test void rechazaProductoSinPrecioVigente() {
        var d=items(List.of(new ItemPedido(f.base+3,BigDecimal.ONE)));
        assertThrows(IllegalArgumentException.class,()->pedidos.cargarPedido(vendedor,d));
        // A4: una política ausente también rechaza sin persistir ni inventar ajustes.
        j.update("DELETE FROM \"AJUSTE_POLITICA_CATEGORIA\" WHERE \"idPoliticaComercial\"=?",f.politica);
        var valido=f.datos();assertThrows(IllegalArgumentException.class,()->pedidos.cargarPedido(vendedor,valido));
        assertEquals(0,contarPedidos());
    }
    @Test void rechazaCantidadNoPositiva() {
        for(String valor:List.of("0","-1","1.0001","1000000000000000")) {
            var d=items(List.of(new ItemPedido(f.base,new BigDecimal(valor))));
            assertThrows(IllegalArgumentException.class,()->pedidos.cargarPedido(vendedor,d));
        }
    }
    @Test void rechazaRolAdministradorProduccionRepartidorProveedor() {
        var d=f.datos();
        for(String rol:List.of("ADMINISTRADOR","RESPONSABLE_PRODUCCION","REPARTIDOR","PROVEEDOR")) {
            UUID sesion=f.sesiones.get(rol);assertThrows(SecurityException.class,()->pedidos.cargarPedido(sesion,d));
        }
        assertThrows(SecurityException.class,()->pedidos.cargarPedido(null,d));
        assertEquals(0,contarPedidos());
    }
    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void rollbackSiFallaMitadNoPersisteNada() {
        j.execute("""
                CREATE FUNCTION fallo_detalle_uc03_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW."numeroDetalle"=2 THEN RAISE EXCEPTION 'fallo controlado de prueba'; END IF; RETURN NEW; END; $$
                """);
        j.execute("CREATE TRIGGER fallo_detalle_uc03_test BEFORE INSERT ON \"DETALLE_PEDIDO\" FOR EACH ROW EXECUTE FUNCTION fallo_detalle_uc03_test()");
        try {
            var d=cliente(f.base+1);
            assertThrows(DataAccessException.class,()->pedidos.cargarPedido(vendedor,d));
            assertEquals(0,j.queryForObject("SELECT count(*) FROM \"PEDIDO\" WHERE \"idOperacionOrigen\"=?",Integer.class,d.claveIdempotencia()));
            assertEquals(0,j.queryForObject("SELECT count(*) FROM \"DETALLE_PEDIDO\" WHERE \"idProducto\" IN (?,?)",Integer.class,f.base,f.base+1));
            assertEquals("CUMPLIDOR",j.queryForObject("SELECT \"categoriaComercial\" FROM \"CLIENTE\" WHERE \"idCliente\"=?",String.class,f.base+1));
        } finally {
            j.execute("DROP TRIGGER fallo_detalle_uc03_test ON \"DETALLE_PEDIDO\"");j.execute("DROP FUNCTION fallo_detalle_uc03_test()");
        }
    }
    @Test void totalOriginalPersistidoCoincideConValorDeUC16() {
        var esperado=ajustes.aplicarAjusteComercialSegunCategoria(f.base,new BigDecimal("500.00"));
        var p=cargar();assertEquals(esperado.totalOriginal(),fila(p.idPedido()).get("totalOriginal"));
        assertEquals(esperado.totalOriginal(),p.totalOriginal());assertEquals(new BigDecimal("450.00"),p.totalOriginal());
    }
    @Test void totalOriginalEsReconstruibleAPreAjuste() {
        j.update("UPDATE \"AJUSTE_POLITICA_CATEGORIA\" SET \"porcentajeAjuste\"=5 WHERE \"idPoliticaComercial\"=? AND \"categoriaComercial\"='CUMPLIDOR'",f.politica);
        var p=cargar();var fila=fila(p.idPedido());assertEquals("DESCUENTO",fila.get("tipoAjuste"));
        assertEquals(new BigDecimal("5.00"),fila.get("porcentajeAjuste"));assertEquals(new BigDecimal("475.00"),p.totalOriginal());
        assertEquals(0,new BigDecimal("500.00").compareTo(p.totalOriginal().divide(new BigDecimal("0.95"))));
    }
}
