package ar.edu.unam.alemilanesas.produccioninventario;

import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class RegistrarPreparacionServiceIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl); r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired RegistrarPreparacionService servicio;
    @Autowired CargarPedidoService pedidos;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    PreparacionFixture f;
    @BeforeEach void fixture() { f = new PreparacionFixture(j,tx,pedidos); }
    PreparacionRegistrada preparar(String n) { return servicio.registrarPreparacionPedido(f.produccion(),f.datos(n)); }
    void assertRechazo(String codigo,DatosPreparacion d) {
        UUID sesion = f.produccion();
        assertEquals(codigo,assertThrows(IllegalArgumentException.class,() -> servicio.registrarPreparacionPedido(sesion,d)).getMessage());
    }
    void assertDecimal(String esperado,BigDecimal actual) { assertEquals(0,new BigDecimal(esperado).compareTo(actual)); }
    @Test void preparacionSimpleCreaRegistroYConsumeInsumos() {
        var antes = f.snapshot(); var r = preparar("5");
        assertEquals(f.pedido,r.idPedido()); assertEquals(f.producto,r.idProducto()); assertEquals(1,r.numeroDetalle());
        assertEquals(2,r.cantidadInsumosConsumidos()); assertEquals(1,f.preparaciones());
        assertEquals(2,j.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_INSUMO\" WHERE \"idPreparacion\"=?",Integer.class,r.idPreparacion()));
        assertDecimal("9",f.stock(f.insumo)); assertDecimal("7.5",f.stock(f.insumo+1));
        var despues = f.snapshot();
        for (String tabla : List.of("PEDIDO","DETALLE_PEDIDO","COSTO_INSUMO_HISTORICO","SOLICITUD_COTIZACION"))
            assertEquals(antes.get(tabla),despues.get(tabla));
        assertEquals(2,j.queryForObject("""
                SELECT count(*) FROM "MOVIMIENTO_INSUMO" m WHERE m."idPreparacion"=? AND m."cantidad"=(
                    SELECT SUM(d."cantidadAplicada") FROM "DETALLE_MOVIMIENTO_PARTIDA" d WHERE d."idMovimientoInsumo"=m."idMovimientoInsumo")
                """,Integer.class,r.idPreparacion()));
    }
    @Test void preparacionCalculaCantidadConsumidaCorrectamente() {
        var r = preparar("5.000"); // 0.200 * 5.000 / 1.000 = 1.000; otro insumo 0.500 * 5 = 2.500.
        assertDecimal("1.000",consumido(r.idPreparacion(),f.insumo)); assertDecimal("2.500",consumido(r.idPreparacion(),f.insumo+1));
        j.update("UPDATE \"VERSION_RECETA\" SET \"cantidadRendimientoBase\"=2 WHERE \"idReceta\"=?",f.receta);
        j.update("UPDATE \"DETALLE_VERSION_RECETA\" SET \"cantidadNecesaria\"=0.001 WHERE \"idReceta\"=? AND \"idInsumo\"=?",f.receta,f.insumo);
        assertDecimal("0.001",consumido(preparar("1").idPreparacion(),f.insumo)); // 0.0005 -> HALF_UP 0.001.
        j.update("UPDATE \"VERSION_RECETA\" SET \"cantidadRendimientoBase\"=3 WHERE \"idReceta\"=?",f.receta);
        var antes = f.snapshot(); assertRechazo("CANTIDAD_CONSUMIDA_INSUFICIENTE",f.datos("0.001")); assertEquals(antes,f.snapshot());
    }
    BigDecimal consumido(long preparacion,long insumo) {
        return j.queryForObject("SELECT \"cantidad\" FROM \"MOVIMIENTO_INSUMO\" WHERE \"idPreparacion\"=? AND \"idInsumo\"=?",BigDecimal.class,preparacion,insumo);
    }
    @Test void preparacionSeleccionaPartidasPorD4() {
        j.update("DELETE FROM \"PARTIDA_INSUMO\" WHERE \"idInsumo\"=?",f.insumo);
        LocalDate hoy = j.queryForObject("SELECT CURRENT_DATE",LocalDate.class); long b = f.lote+10;
        var q = new BigDecimal("0.2");
        f.partida(b+4,f.insumo,q,null,f.fecha.minusDays(8),null);
        f.partida(b+2,f.insumo,q,hoy.plusDays(1),f.fecha.minusDays(7),null);
        f.partida(b+1,f.insumo,q,hoy.plusDays(20),f.fecha.minusDays(10),f.fecha.minusDays(3));
        f.partida(b,f.insumo,q,null,f.fecha.minusDays(10),f.fecha.minusDays(5));
        f.partida(b+3,f.insumo,q,hoy.plusDays(1),f.fecha.minusDays(8),null);
        f.partida(b+5,f.insumo,q,hoy.plusDays(1),f.fecha.minusDays(8),null);
        f.partida(b+6,f.insumo,q,hoy.plusDays(1),f.fecha.minusDays(10),f.fecha.minusDays(3));
        j.update("UPDATE \"INSUMO\" SET \"stockActual\"=1.4 WHERE \"idInsumo\"=?",f.insumo);
        for (long esperado : new long[]{b,b+6,b+1,b+3,b+5,b+2,b+4}) {
            long prep = preparar("1").idPreparacion();
            assertEquals(esperado,j.queryForObject("""
                    SELECT d."idPartidaInsumo" FROM "DETALLE_MOVIMIENTO_PARTIDA" d JOIN "MOVIMIENTO_INSUMO" m USING ("idMovimientoInsumo")
                    WHERE m."idPreparacion"=? AND d."idInsumo"=?
                    """,Long.class,prep,f.insumo));
        }
        assertDecimal("0",f.stock(f.insumo));
    }
    @Test void preparacionConOverrideManualUsaPartidasEspecificadas() {
        long extra = f.lote+10;
        f.partida(extra,f.insumo,new BigDecimal("10"),null,f.fecha,null);
        j.update("UPDATE \"INSUMO\" SET \"stockActual\"=20 WHERE \"idInsumo\"=?",f.insumo);
        var seleccion = List.of(new PartidaAplicada(f.insumo,extra,BigDecimal.ONE),f.override().get(1));
        servicio.registrarPreparacionPedido(f.produccion(),f.manual("5",seleccion));
        assertDecimal("10",f.disponible(f.lote)); assertDecimal("9",f.disponible(extra));
        var antes = f.snapshot();
        for (var invalida : List.of(List.of(seleccion.getFirst()),
                List.of(seleccion.getFirst(),seleccion.getFirst(),seleccion.getLast()),
                List.of(new PartidaAplicada(f.insumo,extra,new BigDecimal("2")),seleccion.getLast()),
                List.of(new PartidaAplicada(f.insumo,f.lote+1,BigDecimal.ONE),seleccion.getLast()),
                List.of(new PartidaAplicada(f.insumo,extra,BigDecimal.ONE),new PartidaAplicada(f.insumo+99,f.lote+1,new BigDecimal("2.5")))))
            assertRechazo("SOLICITUD_INVALIDA",f.manual("5",invalida));
        j.update("UPDATE \"PARTIDA_INSUMO\" SET \"fechaVencimiento\"=CURRENT_DATE-1 WHERE \"idPartidaInsumo\"=?",extra);
        assertRechazo("SOLICITUD_INVALIDA",f.manual("5",seleccion));
        j.update("UPDATE \"PARTIDA_INSUMO\" SET \"fechaVencimiento\"=NULL WHERE \"idPartidaInsumo\"=?",extra);
        assertEquals(antes,f.snapshot());
    }
    @Test void preparacionSinRecetaVigenteRechaza() {
        j.update("UPDATE \"VERSION_RECETA\" SET \"vigenteHasta\"=? WHERE \"idReceta\"=?",f.fecha.minusDays(1),f.receta);
        assertRechazo("SIN_RECETA_VIGENTE",f.datos("5")); assertEquals(0,f.preparaciones());
        j.update("UPDATE \"VERSION_RECETA\" SET \"vigenteHasta\"=NULL WHERE \"idReceta\"=?",f.receta);
        j.update("DELETE FROM \"DETALLE_VERSION_RECETA\" WHERE \"idReceta\"=?",f.receta);
        assertRechazo("RECETA_SIN_INSUMOS",f.datos("5"));
    }
    @Test void preparacionProductoREVENTARechaza() {
        j.update("UPDATE \"PRODUCTO\" SET \"tipoProducto\"='REVENTA',\"stockActualReventa\"=0 WHERE \"idProducto\"=?",f.producto);
        assertRechazo("SIN_RECETA_VIGENTE",f.datos("5")); assertEquals(0,f.preparaciones());
    }
    @Test void preparacionConPedidoNoPendienteRechaza() {
        for (String estado : List.of("ENTREGADO","CANCELADO")) {
            j.update("UPDATE \"PEDIDO\" SET \"estado\"=? WHERE \"idPedido\"=?",estado,f.pedido);
            assertRechazo("PEDIDO_NO_PENDIENTE",f.datos("5"));
        }
        assertRechazo("PEDIDO_NO_ENCONTRADO",new DatosPreparacion(Long.MAX_VALUE,f.producto,BigDecimal.ONE,null));
        j.update("UPDATE \"PEDIDO\" SET \"estado\"='PENDIENTE' WHERE \"idPedido\"=?",f.pedido);
        assertRechazo("PRODUCTO_NO_EN_PEDIDO",new DatosPreparacion(f.pedido,f.producto+1,BigDecimal.ONE,null));
    }
    @Test void preparacionExcedeCantidadOriginalRechaza() {
        preparar("4"); assertRechazo("CANTIDAD_EXCEDE_ORIGINAL",f.datos("7")); assertEquals(1,f.preparaciones());
        for (String n : List.of("0","-1","0.0001","1000000000000000")) assertRechazo("SOLICITUD_INVALIDA",f.datos(n));
        assertRechazo("SOLICITUD_INVALIDA",f.manual("1",List.of()));
        assertRechazo("SOLICITUD_INVALIDA",null);
        preparar("4"); // Reintento parcial no deduplicado: cabe en el límite, se registra otra preparación.
        assertEquals(2,f.preparaciones());
        assertDecimal("8",j.queryForObject("SELECT SUM(\"cantidadPreparada\") FROM \"PREPARACION\" WHERE \"idPedido\"=?",BigDecimal.class,f.pedido));
    }
    @Test void preparacionStockUtilizableInsuficienteRechaza() {
        j.update("UPDATE \"PARTIDA_INSUMO\" SET \"fechaVencimiento\"=CURRENT_DATE-1 WHERE \"idInsumo\"=?",f.insumo);
        var antes = f.snapshot(); assertRechazo("STOCK_UTILIZABLE_INSUFICIENTE",f.datos("5")); assertEquals(antes,f.snapshot());
        j.update("UPDATE \"PARTIDA_INSUMO\" SET \"fechaVencimiento\"=CURRENT_DATE WHERE \"idInsumo\"=?",f.insumo);
        assertNotNull(preparar("5")); // Vence hoy: aún utilizable, también para override manual.
    }
    @Test void preparacionAbrePartidaCerradaAplicandoD2() {
        var registroFuturo = f.fecha.plusDays(1);
        j.update("UPDATE \"PARTIDA_INSUMO\" SET \"fechaHoraRegistro\"=? WHERE \"idPartidaInsumo\"=?",registroFuturo,f.lote);
        var r = preparar("5");
        assertTrue(j.queryForObject("SELECT \"fechaHoraApertura\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",OffsetDateTime.class,f.lote).isEqual(registroFuturo));
        assertTrue(j.queryForObject("SELECT \"fechaHoraApertura\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",OffsetDateTime.class,f.lote+1).isEqual(r.fechaHoraPreparacion()));
        preparar("1");
        assertTrue(j.queryForObject("SELECT \"fechaHoraApertura\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",OffsetDateTime.class,f.lote+1).isEqual(r.fechaHoraPreparacion()));
    }
    @Test void preparacionNoDuplicaDescuentoDePartida() {
        preparar("5"); assertDecimal("9",f.disponible(f.lote)); assertDecimal("7.5",f.disponible(f.lote+1));
        assertDecimal("9",f.stock(f.insumo)); assertDecimal("7.5",f.stock(f.insumo+1));
    }
    @Test void preparacionDisparaEpisodioFaltanteSiBajaDeUmbral() {
        j.update("UPDATE \"INSUMO\" SET \"umbralMinimo\"=9.5 WHERE \"idInsumo\"=?",f.insumo);
        var r = preparar("5"); preparar("1");
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"EPISODIO_FALTANTE\" WHERE \"idInsumo\"=? AND \"fechaHoraCierre\" IS NULL",Integer.class,f.insumo));
        assertEquals(r.idPreparacion(),j.queryForObject("""
                SELECT m."idPreparacion" FROM "EPISODIO_FALTANTE" e JOIN "MOVIMIENTO_INSUMO" m ON m."idMovimientoInsumo"=e."idMovimientoApertura"
                WHERE e."idInsumo"=?
                """,Long.class,f.insumo));
        assertEquals(0,j.queryForObject("SELECT count(*) FROM \"SOLICITUD_COTIZACION\"",Integer.class));
    }
    @Test void preparacionRegistraOrigenProduccion() {
        var r = preparar("5");
        assertEquals(2,j.queryForObject("""
                SELECT count(*) FROM "MOVIMIENTO_INSUMO" WHERE "idPreparacion"=? AND "origen"='PRODUCCION'
                    AND "tipoMovimiento"='CONSUMO_PRODUCCION' AND "idRecepcionCompra" IS NULL AND "referenciaOrigen" IS NULL
                    AND "motivo" IS NULL AND "observacion" IS NULL AND "fechaHora"=? AND "idUsuarioResponsable"=?
                    AND "vigenteDesdeCosto"=?
                """,Integer.class,r.idPreparacion(),r.fechaHoraPreparacion(),f.actores.base+2,f.fecha.minusDays(3)));
    }
    @Test void rechazaUnidadNoCoincide() {
        j.update("UPDATE \"VERSION_RECETA\" SET \"codigoUnidadRendimiento\"='KG' WHERE \"idReceta\"=?",f.receta);
        assertRechazo("UNIDAD_NO_COINCIDE",f.datos("5"));
        j.update("UPDATE \"VERSION_RECETA\" SET \"codigoUnidadRendimiento\"='UN' WHERE \"idReceta\"=?",f.receta);
        j.update("UPDATE \"DETALLE_VERSION_RECETA\" SET \"codigoUnidadCantidad\"='UN' WHERE \"idReceta\"=?",f.receta);
        assertRechazo("UNIDAD_NO_COINCIDE",f.datos("5"));
        j.update("UPDATE \"INSUMO\" SET \"activo\"=false WHERE \"idInsumo\"=?",f.insumo);
        assertRechazo("INSUMO_INACTIVO",f.datos("5"));
    }
    @Test void rechazaRolNoProduccion() {
        var d = f.datos("5");
        for (String rol : List.of("ADMINISTRADOR","VENDEDOR","REPARTIDOR","PROVEEDOR")) {
            UUID sesion = f.actores.sesiones.get(rol);
            assertThrows(SecurityException.class,() -> servicio.registrarPreparacionPedido(sesion,d));
        }
        assertThrows(SecurityException.class,() -> servicio.registrarPreparacionPedido(null,d));
    }
    @Test void rollbackSiFallaMitadNoPersisteNada() {
        j.update("DELETE FROM \"COSTO_INSUMO_HISTORICO\" WHERE \"idInsumo\"=?",f.insumo+1);
        var sinCosto = f.snapshot(); assertRechazo("COSTO_HISTORICO_REQUERIDO",f.datos("5")); assertEquals(sinCosto,f.snapshot());
        j.update("INSERT INTO \"COSTO_INSUMO_HISTORICO\" (\"idInsumo\",\"vigenteDesde\",\"costoUnitario\",\"fuenteCosto\") VALUES (?,?,100,'SALDO_INICIAL')",f.insumo+1,f.fecha.minusDays(3));
        j.update("UPDATE \"INSUMO\" SET \"umbralMinimo\"=9.5 WHERE \"idInsumo\"=?",f.insumo);
        j.execute("""
                CREATE FUNCTION test_uc28_fallo() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW."cantidad"=2.5 THEN RAISE EXCEPTION 'fallo sintetico UC28'; END IF; RETURN NEW; END $$
                """);
        j.execute("CREATE TRIGGER test_uc28_fallo BEFORE INSERT ON \"MOVIMIENTO_INSUMO\" FOR EACH ROW EXECUTE FUNCTION test_uc28_fallo()");
        var d = f.datos("5"); var antes = f.snapshot(); UUID sesion = f.produccion();
        try { assertThrows(DataAccessException.class,() -> servicio.registrarPreparacionPedido(sesion,d)); assertEquals(antes,f.snapshot()); }
        finally { j.execute("DROP TRIGGER test_uc28_fallo ON \"MOVIMIENTO_INSUMO\""); j.execute("DROP FUNCTION test_uc28_fallo()"); }
        // Partidas suficientes pero stock físico inconsistente: ck_insumo_0 también revierte todo.
        j.update("UPDATE \"INSUMO\" SET \"stockActual\"=0 WHERE \"idInsumo\"=?",f.insumo+1);
        var inconsistente = f.snapshot();
        assertThrows(DataAccessException.class,() -> servicio.registrarPreparacionPedido(sesion,d)); assertEquals(inconsistente,f.snapshot());
    }
}
