package ar.edu.unam.alemilanesas.abastecimiento;

import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class RegistrarIngresoStockInsumoServiceIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl); r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired RegistrarIngresoStockInsumoService servicio;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    IngresoStockFixture f;
    @BeforeEach void fixture() { f=new IngresoStockFixture(j,tx); }
    RegistrarIngresoStockInsumoService.IngresoRegistrado ingreso(String cantidad) {
        return servicio.registrarIngresoStockPorCompra(f.produccion(),f.datos(cantidad));
    }
    BigDecimal numero(String sql,Object...args) { return j.queryForObject(sql,BigDecimal.class,args); }
    void igual(String esperado,BigDecimal actual) { assertEquals(0,new BigDecimal(esperado).compareTo(actual)); }
    void stock(String esperado) { igual(esperado,numero("SELECT \"stockActual\" FROM \"INSUMO\" WHERE \"idInsumo\"=?",f.base)); }
    void rechazo(String mensaje,RegistrarIngresoStockInsumoService.DatosIngreso d) {
        assertEquals(mensaje,assertThrows(IllegalArgumentException.class,
                ()->servicio.registrarIngresoStockPorCompra(f.produccion(),d)).getMessage());
        stock("10");
    }
    @Test void ingresoSimpleCreaRecepcionMovimientoPartidaYDetalle() {
        var r=ingreso("5");
        assertEquals(1,j.queryForObject("""
                SELECT count(*) FROM "RECEPCION_COMPRA" r JOIN "MOVIMIENTO_INSUMO" m USING ("idRecepcionCompra")
                JOIN "PARTIDA_INSUMO" p USING ("idRecepcionCompra")
                JOIN "DETALLE_MOVIMIENTO_PARTIDA" d USING ("idMovimientoInsumo","idPartidaInsumo")
                WHERE r."idRecepcionCompra"=? AND r."factorConversionAplicado"=1
                AND r."idProveedorDirecto" IS NULL AND r."idProductoReventa" IS NULL
                AND p."fechaVencimiento" IS NULL AND p."codigoLote"='LOTE_' || r."idRecepcionCompra"
                """,Integer.class,r.idRecepcionCompra()));
    }
    @Test void ingresoActualizaStockActualDelInsumo() throws Exception {
        ingreso("5.125");stock("15.125");
        var otro=new IngresoStockFixture(j,tx);
        // Mismo insumo y otro insumo en paralelo: no perder stock ni colisionar IDs MAX+1 globales.
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var resultados=pool.invokeAll(java.util.List.<java.util.concurrent.Callable<RegistrarIngresoStockInsumoService.IngresoRegistrado>>of(
                    ()->ingreso("5"), ()->ingreso("5"),
                    ()->servicio.registrarIngresoStockPorCompra(otro.produccion(),otro.datos("5"))),
                    20,java.util.concurrent.TimeUnit.SECONDS);
            var recepciones=new java.util.HashSet<Long>();
            var movimientos=new java.util.HashSet<Long>();
            var partidas=new java.util.HashSet<Long>();
            for(var futuro:resultados) {
                var r=futuro.get();recepciones.add(r.idRecepcionCompra());
                movimientos.add(r.idMovimientoInsumo());partidas.add(r.idPartidaInsumo());
            }
            assertEquals(3,recepciones.size());assertEquals(3,movimientos.size());assertEquals(3,partidas.size());
        }
        stock("25.125");
        igual("15",numero("SELECT \"stockActual\" FROM \"INSUMO\" WHERE \"idInsumo\"=?",otro.base));
    }
    @Test void ingresoParcialDejaOrdenEnEstadoParcialmenteRecibida() {
        assertEquals("PARCIALMENTE_RECIBIDA",ingreso("5").estadoOrdenCompra());
        assertEquals("PARCIALMENTE_RECIBIDA",j.queryForObject("SELECT \"estado\" FROM \"ORDEN_COMPRA\" WHERE \"idOrdenCompra\"=?",String.class,f.base));
        assertNull(j.queryForObject("SELECT \"fechaHoraCierre\" FROM \"EPISODIO_FALTANTE\" WHERE \"idEpisodio\"=?",Object.class,f.base));
    }
    @Test void ingresoCompletoDejaOrdenEnEstadoRecibida() {
        ingreso("5"); assertEquals("RECIBIDA",ingreso("15").estadoOrdenCompra());stock("30");
        assertNotNull(j.queryForObject("SELECT \"fechaHoraCierre\" FROM \"EPISODIO_FALTANTE\" WHERE \"idEpisodio\"=?",Object.class,f.base));
    }
    @Test void costoHistoricoCierraVigenciaAnteriorYCreaNueva() {
        var r=ingreso("5");
        assertEquals(1,j.queryForObject("""
                SELECT count(*) FROM "COSTO_INSUMO_HISTORICO" nuevo JOIN "COSTO_INSUMO_HISTORICO" previo
                ON previo."idInsumo"=nuevo."idInsumo" AND previo."vigenteHasta"=nuevo."vigenteDesde"
                JOIN "MOVIMIENTO_INSUMO" m ON m."idInsumo"=nuevo."idInsumo"
                AND m."vigenteDesdeCosto"=nuevo."vigenteDesde"
                JOIN "RECEPCION_COMPRA" r ON r."idRecepcionCompra"=nuevo."idRecepcionCompra"
                WHERE nuevo."idRecepcionCompra"=? AND nuevo."vigenteHasta" IS NULL AND nuevo."fuenteCosto"='COMPRA'
                AND nuevo."costoUnitario"=3.5 AND r."fechaHoraRecepcion"=nuevo."vigenteDesde"
                """,Integer.class,r.idRecepcionCompra()));
    }
    @Test void partidaCreadaConCantidadDisponibleIgualACantidadInicial() {
        var r=ingreso("5"); igual("5",numero("SELECT \"cantidadInicial\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",r.idPartidaInsumo()));
        igual("5",numero("SELECT \"cantidadDisponible\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",r.idPartidaInsumo()));
    }
    @Test void detalleMovimientoVinculadoAMovimientoYPartida() {
        var r=ingreso("5");
        assertEquals(1,j.queryForObject("""
                SELECT count(*) FROM "DETALLE_MOVIMIENTO_PARTIDA" d
                JOIN "MOVIMIENTO_INSUMO" m USING ("idMovimientoInsumo","idInsumo")
                JOIN "PARTIDA_INSUMO" p USING ("idPartidaInsumo","idInsumo")
                WHERE m."idMovimientoInsumo"=? AND p."idPartidaInsumo"=? AND d."cantidadAplicada"=m."cantidad"
                """,Integer.class,r.idMovimientoInsumo(),r.idPartidaInsumo()));
    }
    @Test void triggerActualizaCantidadDisponibleEnPartida() {
        var r=ingreso("5");
        igual("5",numero("SELECT \"cantidadDisponible\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",r.idPartidaInsumo()));
        // Un segundo ingreso sobre una partida ya llena debe ser rechazado por el CHECK de V3.
        assertThrows(DataAccessException.class,()->j.update("""
                UPDATE "DETALLE_MOVIMIENTO_PARTIDA" SET "cantidadAplicada"="cantidadAplicada"+1
                WHERE "idMovimientoInsumo"=?
                """,r.idMovimientoInsumo()));
        igual("5",numero("SELECT \"cantidadDisponible\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",r.idPartidaInsumo()));
    }
    @Test void rollbackSiFallaMitadNoPersisteNada() {
        j.execute("""
                CREATE FUNCTION fallo_uc08_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Fallo sintetico al registrar detalle'; END $$
                """);
        j.execute("CREATE TRIGGER fallo_uc08_test BEFORE INSERT ON \"DETALLE_MOVIMIENTO_PARTIDA\" FOR EACH ROW EXECUTE FUNCTION fallo_uc08_test()");
        try {
            assertThrows(DataAccessException.class,()->ingreso("5"));stock("10");
            assertEquals(0,j.queryForObject("SELECT count(*) FROM \"RECEPCION_COMPRA\" WHERE \"idOrdenCompra\"=?",Integer.class,f.base));
            assertEquals(0,j.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_INSUMO\" WHERE \"idInsumo\"=?",Integer.class,f.base));
            assertEquals(1,j.queryForObject("SELECT count(*) FROM \"PARTIDA_INSUMO\" WHERE \"idInsumo\"=?",Integer.class,f.base));
            assertEquals(1,j.queryForObject("SELECT count(*) FROM \"COSTO_INSUMO_HISTORICO\" WHERE \"idInsumo\"=? AND \"vigenteHasta\" IS NULL",Integer.class,f.base));
            assertEquals("PENDIENTE",j.queryForObject("SELECT \"estado\" FROM \"ORDEN_COMPRA\" WHERE \"idOrdenCompra\"=?",String.class,f.base));
        } finally { j.execute("DROP TRIGGER fallo_uc08_test ON \"DETALLE_MOVIMIENTO_PARTIDA\"");j.execute("DROP FUNCTION fallo_uc08_test()"); }
    }
    @Test void rechazaSiUnidadNoCoincideConUnidadBaseDelInsumo() {
        var d=f.datos("5");rechazo("Unidad no coincide",new RegistrarIngresoStockInsumoService.DatosIngreso(f.base,f.base,d.cantidadRecibida(),"G",d.costoUnitarioCompra(),d.comprobante()));
    }
    @Test void rechazaSiOrdenNoExiste() {
        var d=f.datos("5");rechazo("Orden de compra inexistente",new RegistrarIngresoStockInsumoService.DatosIngreso(f.base,Long.MAX_VALUE,d.cantidadRecibida(),"KG",d.costoUnitarioCompra(),d.comprobante()));
        rechazo("Orden de compra requerida",new RegistrarIngresoStockInsumoService.DatosIngreso(f.base,0,d.cantidadRecibida(),"KG",d.costoUnitarioCompra(),d.comprobante()));
    }
    @Test void rechazaSiOrdenYaRecibidaCompletamente() {
        j.update("UPDATE \"ORDEN_COMPRA\" SET \"estado\"='RECIBIDA' WHERE \"idOrdenCompra\"=?",f.base);rechazo("Orden ya recibida",f.datos("5"));
        j.update("UPDATE \"ORDEN_COMPRA\" SET \"estado\"='CANCELADA' WHERE \"idOrdenCompra\"=?",f.base);rechazo("Orden cancelada",f.datos("5"));
    }
    @Test void rechazaSiCantidadExcedeLoPendienteDeLaOrden() { rechazo("Cantidad excede lo pendiente",f.datos("20.001")); }
    @Test void rechazaSiInsumoNoExisteOInactivo() {
        var d=f.datos("5");rechazo("Insumo inexistente",new RegistrarIngresoStockInsumoService.DatosIngreso(Long.MAX_VALUE,f.base,d.cantidadRecibida(),"KG",d.costoUnitarioCompra(),d.comprobante()));
        rechazo("Orden de otro insumo",new RegistrarIngresoStockInsumoService.DatosIngreso(f.base+1,f.base,d.cantidadRecibida(),"KG",d.costoUnitarioCompra(),d.comprobante()));
        j.update("UPDATE \"INSUMO\" SET \"activo\"=false WHERE \"idInsumo\"=?",f.base);rechazo("Insumo inactivo",d);
    }
    @Test void rechazaRolVendedorRepartidorProveedor() {
        for (String rol:new String[]{"VENDEDOR","ADMINISTRADOR","REPARTIDOR","PROVEEDOR"})
            assertThrows(SecurityException.class,()->servicio.registrarIngresoStockPorCompra(f.sesiones.get(rol),f.datos("5")));
        assertThrows(SecurityException.class,()->servicio.registrarIngresoStockPorCompra(null,f.datos("5")));stock("10");
    }
}
