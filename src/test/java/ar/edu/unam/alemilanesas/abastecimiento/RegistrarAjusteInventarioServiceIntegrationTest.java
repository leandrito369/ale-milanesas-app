package ar.edu.unam.alemilanesas.abastecimiento;

import ar.edu.unam.alemilanesas.abastecimiento.RegistrarAjusteInventarioService.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static ar.edu.unam.alemilanesas.abastecimiento.AjusteInventarioFixture.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class RegistrarAjusteInventarioServiceIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl); r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired RegistrarAjusteInventarioService servicio;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    AjusteInventarioFixture f;
    @BeforeEach void fixture() { f = new AjusteInventarioFixture(j,tx); }
    AjusteRegistrado registrar(DatosAjuste d) { return servicio.registrarAjusteInventario(f.produccion(),d); }
    BigDecimal stock() { return j.queryForObject("SELECT \"stockActual\" FROM \"INSUMO\" WHERE \"idInsumo\"=?",BigDecimal.class,f.insumo); }
    long partida() { return j.queryForObject("SELECT MIN(\"idPartidaInsumo\") FROM \"PARTIDA_INSUMO\" WHERE \"idInsumo\"=?",Long.class,f.insumo); }
    PartidaAjuste descuento(long id,String cantidad,Boolean apertura) { return new PartidaAjuste(id,n(cantidad),null,null,apertura); }
    void sinEfectos() {
        assertEquals(0,stock().signum());
        for (String tabla:List.of("MOVIMIENTO_INSUMO","PARTIDA_INSUMO","DETALLE_MOVIMIENTO_PARTIDA","COSTO_INSUMO_HISTORICO"))
            assertEquals(0,j.queryForObject("SELECT count(*) FROM \""+tabla+"\" WHERE \"idInsumo\"=?",Integer.class,f.insumo));
    }
    void invalido(String mensaje,DatosAjuste d) {
        assertEquals(mensaje,assertThrows(IllegalArgumentException.class,()->registrar(d)).getMessage());
    }
    @Test void saldoInicialCreaCostoPartidasDetallesYMovimiento() {
        var r = registrar(f.inicial());
        assertEquals(2,r.cantidadPartidas()); assertEquals(n("10.000"),stock());
        assertEquals(2,j.queryForObject("""
                SELECT count(*) FROM "MOVIMIENTO_INSUMO" m JOIN "COSTO_INSUMO_HISTORICO" c
                ON c."idInsumo"=m."idInsumo" AND c."vigenteDesde"=m."vigenteDesdeCosto"
                JOIN "DETALLE_MOVIMIENTO_PARTIDA" d ON d."idMovimientoInsumo"=m."idMovimientoInsumo" AND d."idInsumo"=m."idInsumo"
                JOIN "PARTIDA_INSUMO" p ON p."idPartidaInsumo"=d."idPartidaInsumo" AND p."idInsumo"=d."idInsumo"
                WHERE m."idMovimientoInsumo"=? AND c."fuenteCosto"='SALDO_INICIAL'
                AND c."idRecepcionCompra" IS NULL AND m."idRecepcionCompra" IS NULL
                AND m."idPreparacion" IS NULL AND p."idRecepcionCompra" IS NULL
                AND p."cantidadInicial"=p."cantidadDisponible" AND d."cantidadAplicada"=p."cantidadInicial"
                """,Integer.class,r.idMovimientoInsumo()));
        assertEquals(n("10.000"),j.queryForObject("SELECT SUM(\"cantidadAplicada\") FROM \"DETALLE_MOVIMIENTO_PARTIDA\" WHERE \"idMovimientoInsumo\"=?",BigDecimal.class,r.idMovimientoInsumo()));
    }
    @Test void saldoInicialEstableceNumeroSecuenciaInsumoEn1() {
        var r=registrar(f.inicial());
        assertEquals(1,j.queryForObject("SELECT \"numeroSecuenciaInsumo\" FROM \"MOVIMIENTO_INSUMO\" WHERE \"idMovimientoInsumo\"=?",Integer.class,r.idMovimientoInsumo()));
        var d=f.datos("AJUSTE_POSITIVO","1",null,List.of(nueva("1")));
        registrar(d);registrar(d); // Dos envíos válidos conservan el riesgo residual de G4.
        assertEquals(n("12.000"),stock());
    }
    @Test void saldoInicialEstableceOrigenAperturaInventario() {
        var r=registrar(f.inicial());
        assertEquals("APERTURA_INVENTARIO",j.queryForObject("SELECT \"origen\" FROM \"MOVIMIENTO_INSUMO\" WHERE \"idMovimientoInsumo\"=?",String.class,r.idMovimientoInsumo()));
    }
    @Test void saldoInicialSinCostoEsRechazado() {
        for(BigDecimal costo:Arrays.asList(null,BigDecimal.ZERO,n("-1")))
            invalido("COSTO_VALUACION_REQUERIDO",f.datos("SALDO_INICIAL","5",costo,List.of(nueva("5"))));
        sinEfectos();
    }
    @Test void saldoInicialDuplicadoEsRechazado() throws Exception {
        registrar(f.inicial());
        invalido("SALDO_INICIAL_DUPLICADO",f.inicial());
        assertEquals(n("10.000"),stock());
        var otro = new AjusteInventarioFixture(j,tx);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var inicio=new CountDownLatch(1);
            Callable<String> intento=()->{inicio.await();try {
                servicio.registrarAjusteInventario(otro.produccion(),otro.inicial());return "CREADO";
            } catch(IllegalArgumentException ex) {return ex.getMessage();}};
            var a=pool.submit(intento);var b=pool.submit(intento);inicio.countDown();
            var resultados=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertTrue(resultados.contains("CREADO"));assertTrue(resultados.contains("SALDO_INICIAL_DUPLICADO"));
        }
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_INSUMO\" WHERE \"idInsumo\"=?",Integer.class,otro.insumo));
        assertEquals(1,j.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='uq_movimiento_insumo_saldo_inicial' AND indexdef LIKE '%UNIQUE%WHERE%'",Integer.class));
    }
    @Test void ajustePositivoSinCostoUsaVigente() {
        invalido("COSTO_HISTORICO_REQUERIDO",f.datos("AJUSTE_POSITIVO","1",null,List.of(nueva("1"))));
        registrar(f.inicial());registrar(f.datos("AJUSTE_POSITIVO","3",null,List.of(nueva("3"))));
        assertEquals(n("13.000"),stock());
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"COSTO_INSUMO_HISTORICO\" WHERE \"idInsumo\"=?",Integer.class,f.insumo));
    }
    @Test void ajustePositivoConCostoCreaNuevaVigencia() {
        registrar(f.inicial());var r=registrar(f.datos("AJUSTE_POSITIVO","2",n("3.5000"),List.of(nueva("2"))));
        assertEquals(1,j.queryForObject("""
                SELECT count(*) FROM "COSTO_INSUMO_HISTORICO" anterior
                JOIN "COSTO_INSUMO_HISTORICO" nuevo ON anterior."idInsumo"=nuevo."idInsumo"
                    AND anterior."vigenteHasta"=nuevo."vigenteDesde"
                JOIN "MOVIMIENTO_INSUMO" m ON m."idInsumo"=nuevo."idInsumo" AND m."vigenteDesdeCosto"=nuevo."vigenteDesde"
                WHERE m."idMovimientoInsumo"=? AND nuevo."fuenteCosto"='AJUSTE_POSITIVO'
                    AND nuevo."costoUnitario"=3.5 AND nuevo."vigenteHasta" IS NULL
                """,Integer.class,r.idMovimientoInsumo()));
    }
    @Test void ajusteNegativoDescuentaPartidaEspecifica() {
        registrar(f.inicial());long id=partida();
        j.update("UPDATE \"PARTIDA_INSUMO\" SET \"fechaVencimiento\"=current_date-1 WHERE \"idPartidaInsumo\"=?",id);
        registrar(f.datos("AJUSTE_NEGATIVO","2",n("-99"),List.of(descuento(id,"2",null))));
        assertEquals(n("8.000"),stock());
        assertNull(j.queryForObject("SELECT \"fechaHoraApertura\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",OffsetDateTime.class,id));
        registrar(f.datos("AJUSTE_NEGATIVO","1",null,List.of(descuento(id,"1",true))));
        assertEquals(n("2.000"),j.queryForObject("SELECT \"cantidadDisponible\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",BigDecimal.class,id));
        assertNotNull(j.queryForObject("SELECT \"fechaHoraApertura\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",OffsetDateTime.class,id));
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"COSTO_INSUMO_HISTORICO\" WHERE \"idInsumo\"=?",Integer.class,f.insumo));
    }
    @Test void ajusteNegativoExcedeDisponibleEsRechazado() {
        registrar(f.inicial());
        invalido("Cantidad excede disponible en partida",f.datos("AJUSTE_NEGATIVO","6",null,List.of(descuento(partida(),"6",false))));
        assertEquals(n("10.000"),stock());
    }
    @Test void ajusteNegativoDejaStockNegativoEsRechazado() {
        registrar(f.inicial());long id=partida();
        // Inconsistencia histórica simulada: disponibilidad de partida superior al stock físico.
        j.update("UPDATE \"INSUMO\" SET \"stockActual\"=1 WHERE \"idInsumo\"=?",f.insumo);
        var datos=f.datos("AJUSTE_NEGATIVO","2",null,List.of(descuento(id,"2",true)));
        var ex=assertThrows(DataIntegrityViolationException.class,()->registrar(datos));
        assertTrue(ex.getMostSpecificCause().getMessage().contains("ck_insumo_0"));
        assertEquals(n("1.000"),stock());
        assertEquals(n("5.000"),j.queryForObject("SELECT \"cantidadDisponible\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",BigDecimal.class,id));
        assertNull(j.queryForObject("SELECT \"fechaHoraApertura\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",OffsetDateTime.class,id));
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_INSUMO\" WHERE \"idInsumo\"=?",Integer.class,f.insumo));
    }
    @Test void sumaPartidasDistintaACantidadEsRechazada() {
        invalido("Solicitud invalida",f.datos("SALDO_INICIAL","10",n("2"),List.of(nueva("9"))));
        invalido("Solicitud invalida",f.datos("SALDO_INICIAL","1.0001",n("2"),List.of(nueva("1.0001"))));
        sinEfectos();
    }
    @Test void partidaNoPerteneceAlInsumoEsRechazada() {
        registrar(f.inicial());
        invalido("Partida no pertenece al insumo",f.datos("AJUSTE_NEGATIVO","1",null,List.of(descuento(f.compra.base,"1",false))));
        assertEquals(n("10.000"),stock());
    }
    @Test void rollbackSiFallaMitadNoPersisteNada() {
        j.execute("""
                CREATE FUNCTION uc42_test_fallo() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                IF NEW."cantidadAplicada"=7 THEN RAISE EXCEPTION 'Fallo sintético segundo detalle'; END IF;
                RETURN NEW; END $$
                """);
        j.execute("CREATE TRIGGER uc42_test_fallo BEFORE INSERT ON \"DETALLE_MOVIMIENTO_PARTIDA\" FOR EACH ROW EXECUTE FUNCTION uc42_test_fallo()");
        try {
            var datos=f.datos("SALDO_INICIAL","12",n("2"),List.of(nueva("5"),nueva("7")));
            assertThrows(DataAccessException.class,()->registrar(datos));
            sinEfectos();
        } finally {
            j.execute("DROP TRIGGER uc42_test_fallo ON \"DETALLE_MOVIMIENTO_PARTIDA\"");j.execute("DROP FUNCTION uc42_test_fallo()");
        }
    }
    @Test void rechazaRolVendedorAdminRepartidorProveedor() {
        var datos=f.inicial();
        for(String rol:List.of("VENDEDOR","ADMINISTRADOR","REPARTIDOR","PROVEEDOR")) {
            var sesion=f.compra.sesiones.get(rol);
            assertThrows(SecurityException.class,()->servicio.registrarAjusteInventario(sesion,datos));
        }
        sinEfectos();
    }
}
