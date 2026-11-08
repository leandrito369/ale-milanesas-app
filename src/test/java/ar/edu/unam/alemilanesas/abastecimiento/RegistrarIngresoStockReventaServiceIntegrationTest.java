package ar.edu.unam.alemilanesas.abastecimiento;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class RegistrarIngresoStockReventaServiceIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired RegistrarIngresoStockReventaService servicio;
    @Autowired RegistrarIngresoStockInsumoService insumos;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    IngresoReventaFixture f;
    @BeforeEach void fixture() {f=new IngresoReventaFixture(j,tx);}
    RegistrarIngresoStockReventaService.IngresoReventaRegistrado ingreso(String cantidad,String costo) {
        return servicio.registrarIngresoStockPorCompra(f.produccion(),f.datos(cantidad,costo));
    }
    BigDecimal numero(String sql,Object...args) {return j.queryForObject(sql,BigDecimal.class,args);}
    void producto(String stock,String costo) {
        j.update("UPDATE \"PRODUCTO\" SET \"stockActualReventa\"=?,\"costoPromedioActualReventa\"=? WHERE \"idProducto\"=?",
                stock==null?null:new BigDecimal(stock),costo==null?null:new BigDecimal(costo),f.base);
    }
    void resultado(String stock,String costo) {
        assertEquals(new BigDecimal(stock).setScale(3),numero("SELECT \"stockActualReventa\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",f.base));
        assertEquals(new BigDecimal(costo).setScale(4),numero("SELECT \"costoPromedioActualReventa\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",f.base));
    }
    void rechazo(String mensaje,RegistrarIngresoStockReventaService.DatosIngresoReventa d) {
        var sesion=f.produccion();
        assertEquals(mensaje,assertThrows(IllegalArgumentException.class,
                ()->servicio.registrarIngresoStockPorCompra(sesion,d)).getMessage());
        assertEquals(0,j.queryForObject("SELECT count(*) FROM \"RECEPCION_COMPRA\" WHERE \"idProductoReventa\"=?",Integer.class,f.base));
    }
    @Test void ingresoSimpleCreaRecepcionYMovimiento() {
        var tablasInsumo=List.of("SELECT count(*) FROM \"PARTIDA_INSUMO\"","SELECT count(*) FROM \"DETALLE_MOVIMIENTO_PARTIDA\"",
                "SELECT count(*) FROM \"COSTO_INSUMO_HISTORICO\"","SELECT count(*) FROM \"MOVIMIENTO_INSUMO\"");
        var antes=tablasInsumo.stream().map(q->j.queryForObject(q,Integer.class)).toList();
        var r=ingreso("10","200");
        assertEquals(1,j.queryForObject("""
                SELECT count(*) FROM "RECEPCION_COMPRA" r JOIN "MOVIMIENTO_STOCK_REVENTA" m USING ("idRecepcionCompra")
                WHERE r."idRecepcionCompra"=? AND m."idMovimientoStockReventa"=? AND r."idProveedorDirecto"=?
                AND r."idOrdenCompra" IS NULL AND r."idInsumoRecibido" IS NULL AND r."factorConversionAplicado"=1
                AND r."codigoUnidadMedida"='UN' AND r."costoUnitarioReal"=200 AND r."cantidadRecibida"=10
                AND m."tipoMovimiento"='INGRESO_COMPRA' AND m."numeroSecuenciaProducto"=1
                AND m."fechaHoraMovimiento"=r."fechaHoraRecepcion" AND m."costoPromedioResultante"=150
                """,Integer.class,r.idRecepcionCompra(),r.idMovimientoStockReventa(),f.base));
        assertEquals(antes,tablasInsumo.stream().map(q->j.queryForObject(q,Integer.class)).toList());
    }
    @Test void ingresoActualizaStockYCostoPromedio() throws Exception {
        ingreso("10","200");resultado("20","150");
        var otro=new IngresoReventaFixture(j,tx);
        var insumo=new IngresoStockFixture(j,tx);
        try(var pool=Executors.newFixedThreadPool(4)) {
            // Dos ingresos al mismo producto, otro producto y un INSUMO comparten IDs de recepción.
            var resultados=pool.invokeAll(List.<Callable<Long>>of(
                    ()->ingreso("1","150").idRecepcionCompra(),()->ingreso("1","150").idRecepcionCompra(),
                    ()->servicio.registrarIngresoStockPorCompra(otro.produccion(),otro.datos("1","100")).idRecepcionCompra(),
                    ()->insumos.registrarIngresoStockPorCompra(insumo.produccion(),insumo.datos("1")).idRecepcionCompra()),20,TimeUnit.SECONDS);
            var ids=new java.util.HashSet<Long>();for(var futuro:resultados)ids.add(futuro.get());assertEquals(4,ids.size());
        }
        resultado("22","150");
        assertEquals(3,j.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_STOCK_REVENTA\" WHERE \"idProducto\"=?",Integer.class,f.base));
        assertEquals(3,j.queryForObject("SELECT max(\"numeroSecuenciaProducto\") FROM \"MOVIMIENTO_STOCK_REVENTA\" WHERE \"idProducto\"=?",Integer.class,f.base));
    }
    @Test void primerIngresoConStockNullInicializaCostoAlDeCompra() {
        for(String stock:new String[]{null,"0"}) {
            producto(stock,null);var r=ingreso("12","850.0000");resultado("12","850");assertEquals(new BigDecimal("850.0000"),r.nuevoCostoPromedio());
        }
        // Sin existencias, el costo anterior no aporta ponderación, aunque esté informado.
        producto("0","999");ingreso("1","20");resultado("1","20");
    }
    @Test void segundoIngresoCalculaPromedioPonderadoCorrectamente() {
        producto(null,null);ingreso("10","100");var r=ingreso("10","200");
        resultado("20","150");assertEquals(new BigDecimal("150.0000"),r.nuevoCostoPromedio());
        producto("1","1.0000");assertEquals(new BigDecimal("1.0001"),ingreso("1","1.0001").nuevoCostoPromedio());
        producto("3","0.3333");assertEquals(new BigDecimal("0.5000"),ingreso("1","1.0000").nuevoCostoPromedio());
    }
    @Test void ingresoConMismoCostoMantienePromedio() {
        assertEquals(new BigDecimal("100.0000"),ingreso("0.125","100").nuevoCostoPromedio());resultado("10.125","100");
    }
    @Test void rechazaSiStockPositivoSinCostoValido() {
        for(String costo:new String[]{null,"0"}) {
            producto("10",costo);rechazo("Valuación extraordinaria requerida",f.datos("10","200"));
        }
    }
    @Test void rechazaSiProductoNoEsReventa() {
        var d=f.datos("10","200");
        rechazo("Producto no es REVENTA",new RegistrarIngresoStockReventaService.DatosIngresoReventa(f.base+1,f.base,d.cantidadRecibida(),"UN",d.costoUnitarioCompra(),d.comprobante()));
        rechazo("Producto inexistente",new RegistrarIngresoStockReventaService.DatosIngresoReventa(Long.MAX_VALUE,f.base,d.cantidadRecibida(),"UN",d.costoUnitarioCompra(),d.comprobante()));
        j.update("UPDATE \"PRODUCTO\" SET \"activo\"=false WHERE \"idProducto\"=?",f.base);rechazo("Producto inactivo",d);
        for(String rol:List.of("ADMINISTRADOR","VENDEDOR","REPARTIDOR","PROVEEDOR")) {
            var noAutorizada=f.sesiones.get(rol);assertThrows(SecurityException.class,()->servicio.registrarIngresoStockPorCompra(noAutorizada,d));
        }
        assertThrows(SecurityException.class,()->servicio.registrarIngresoStockPorCompra(null,d));
    }
    @Test void rechazaSiProveedorInactivo() {
        j.update("UPDATE \"PROVEEDOR\" SET \"estado\"='INACTIVO' WHERE \"idProveedor\"=?",f.base);
        rechazo("Proveedor inactivo",f.datos("10","200"));
        var d=f.datos("10","200");rechazo("Proveedor inexistente",new RegistrarIngresoStockReventaService.DatosIngresoReventa(f.base,Long.MAX_VALUE,d.cantidadRecibida(),"UN",d.costoUnitarioCompra(),d.comprobante()));
        rechazo("Proveedor directo requerido",new RegistrarIngresoStockReventaService.DatosIngresoReventa(f.base,0,d.cantidadRecibida(),"UN",d.costoUnitarioCompra(),d.comprobante()));
    }
    @Test void rechazaSiUnidadNoCoincide() {
        var d=f.datos("10","200");rechazo("Unidad no coincide",new RegistrarIngresoStockReventaService.DatosIngresoReventa(f.base,f.base,d.cantidadRecibida(),"KG",d.costoUnitarioCompra(),d.comprobante()));
    }
    @Test void rechazaSiPrecisionExcedida() {
        for(String cantidad:List.of("1.0001","1000000000000000","0","-1"))rechazo("Solicitud invalida",f.datos(cantidad,"100"));
        for(String costo:List.of("1.00001","100000000000000","0","-1"))rechazo("Solicitud invalida",f.datos("1",costo));
        producto("999999999999999.999","100");rechazo("Solicitud invalida",f.datos("0.001","100"));
    }
    @Test void rollbackSiFallaMitadNoPersisteNada() {
        j.execute("""
                CREATE FUNCTION fallo_reventa_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NOT EXISTS (SELECT 1 FROM "PRODUCTO" WHERE "idProducto"=NEW."idProducto"
                      AND "stockActualReventa"=NEW."stockResultante"
                      AND "costoPromedioActualReventa"=NEW."costoPromedioResultante") THEN
                    RAISE EXCEPTION 'Valoracion no actualizada antes del movimiento';
                  END IF;
                  RAISE EXCEPTION 'Fallo sintetico posterior a valoracion';
                END $$
                """);
        j.execute("CREATE TRIGGER fallo_reventa_test BEFORE INSERT ON \"MOVIMIENTO_STOCK_REVENTA\" FOR EACH ROW EXECUTE FUNCTION fallo_reventa_test()");
        try {
            var error=assertThrows(DataAccessException.class,()->ingreso("10","200"));
            assertTrue(error.getMostSpecificCause().getMessage().contains("Fallo sintetico posterior a valoracion"));
            resultado("10","100");
            assertEquals(0,j.queryForObject("SELECT count(*) FROM \"RECEPCION_COMPRA\" WHERE \"idProductoReventa\"=?",Integer.class,f.base));
            assertEquals(0,j.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_STOCK_REVENTA\" WHERE \"idProducto\"=?",Integer.class,f.base));
        } finally {j.execute("DROP TRIGGER fallo_reventa_test ON \"MOVIMIENTO_STOCK_REVENTA\"");j.execute("DROP FUNCTION fallo_reventa_test()");}
    }
}
