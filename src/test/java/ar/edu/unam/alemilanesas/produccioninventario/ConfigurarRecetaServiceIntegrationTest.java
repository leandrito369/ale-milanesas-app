package ar.edu.unam.alemilanesas.produccioninventario;

import ar.edu.unam.alemilanesas.produccioninventario.ConfigurarRecetaService.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
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
class ConfigurarRecetaServiceIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl); r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }
    @Autowired ConfigurarRecetaService servicio;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    RecetaFixture f;
    @BeforeEach void fixture() { f = new RecetaFixture(j, tx); }
    RecetaRegistrada crear(DatosReceta d) { return servicio.configurarRecetaProducto(f.admin(), d); }
    void assertRechazo(String codigo, DatosReceta d) {
        assertEquals(codigo, assertThrows(IllegalArgumentException.class, () -> crear(d)).getMessage());
    }
    DatosReceta conItems(List<ItemReceta> items) {
        return new DatosReceta(f.producto, BigDecimal.ONE, "UN", f.fecha(2), null, items);
    }
    @Test void primeraVersionCreaRecetaYDetalles() {
        var r = crear(f.datos(f.fecha(2), null));
        assertEquals(1, r.numeroVersion()); assertEquals(2, r.cantidadItems()); assertNull(r.versionCerrada());
        assertEquals(f.producto, j.queryForObject("SELECT \"idProducto\" FROM \"RECETA\" WHERE \"idReceta\"=?", Long.class, r.idReceta()));
        assertEquals(2, j.queryForObject("SELECT count(*) FROM \"DETALLE_VERSION_RECETA\" WHERE \"idReceta\"=? AND \"codigoUnidadCantidad\"='KG'", Integer.class, r.idReceta()));
        assertEquals(new BigDecimal("0.200"), j.queryForObject("SELECT \"cantidadNecesaria\" FROM \"DETALLE_VERSION_RECETA\" WHERE \"idReceta\"=? AND \"idInsumo\"=?", BigDecimal.class, r.idReceta(), f.insumo));
        assertEquals(1, f.versiones());
    }
    @Test void segundaVersionUsaMismaReceta() throws Exception {
        var frontera = f.fecha(3);
        var primera = f.datos(f.fecha(2), frontera);
        var segunda = f.datos(frontera, null);
        // Dos confirmaciones concurrentes sin receta previa: identidad única y números distintos.
        var inicio = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { inicio.await(); return crear(primera); });
            var b = pool.submit(() -> { inicio.await(); return crear(segunda); });
            inicio.countDown();
            var r1 = a.get(30, TimeUnit.SECONDS);
            var r2 = b.get(30, TimeUnit.SECONDS);
            assertEquals(r1.idReceta(), r2.idReceta());
            assertEquals(Set.of(1, 2), Set.of(r1.numeroVersion(), r2.numeroVersion()));
            assertNull(r1.versionCerrada()); assertNull(r2.versionCerrada());
            assertEquals(2, f.versiones());
            assertEquals(1, j.queryForObject("SELECT count(*) FROM \"RECETA\" WHERE \"idProducto\"=?", Integer.class, f.producto));
        }
    }
    @Test void segundaVersionAcotaVigencia() {
        long id = f.sembrarVigente();
        var d = f.datos(f.fecha(2).plusNanos(987654321), null);
        var r = crear(d); assertEquals(id, r.idReceta()); assertEquals(1, r.versionCerrada());
        assertEquals(2, r.numeroVersion()); assertEquals(987654000, r.vigenteDesde().getNano());
        assertTrue(j.queryForObject("SELECT \"vigenteHasta\" FROM \"VERSION_RECETA\" WHERE \"idReceta\"=? AND \"numeroVersion\"=1", OffsetDateTime.class, id).isEqual(r.vigenteDesde()));
        assertEquals(1, j.queryForObject("SELECT count(*) FROM \"DETALLE_VERSION_RECETA\" WHERE \"idReceta\"=? AND \"numeroVersion\"=1", Integer.class, id));
        assertTrue(j.queryForObject("SELECT \"vigenteDesde\" FROM \"VERSION_RECETA\" WHERE \"idReceta\"=? AND \"numeroVersion\"=2", OffsetDateTime.class, id).isEqual(r.vigenteDesde()));
    }
    @Test void rechazaSolapeConVersionFutura() {
        var desde = f.fecha(3); var hasta = f.fecha(5);
        var r = crear(f.datos(desde, hasta));
        for (var d : List.of(f.datos(f.fecha(2), null), f.datos(desde, null), f.datos(f.fecha(4), null)))
            assertRechazo("SOLAPE_VERSION_FUTURA", d);
        assertEquals(1, f.versiones());
        assertTrue(j.queryForObject("SELECT \"vigenteHasta\" FROM \"VERSION_RECETA\" WHERE \"idReceta\"=?", OffsetDateTime.class, r.idReceta()).isEqual(hasta));
        // Adyacencia permitida; no basta que exista alguna versión futura para rechazar.
        assertEquals(2, crear(f.datos(f.fecha(2), desde)).numeroVersion());
        assertEquals(3, crear(f.datos(hasta, null)).numeroVersion());
    }
    @Test void rechazaProductoREVENTA() {
        var d = f.datos(f.fecha(2), null);
        assertRechazo("PRODUCTO_NO_ES_ELABORADO", new DatosReceta(f.actores.base, d.cantidadRendimientoBase(), "UN", d.vigenteDesde(), null, d.itemsInsumo()));
        j.update("UPDATE \"PRODUCTO\" SET \"activo\"=false WHERE \"idProducto\"=?", f.producto);
        assertRechazo("PRODUCTO_INACTIVO", d);
    }
    @Test void rechazaProductoInexistente() {
        var d = f.datos(f.fecha(2), null);
        assertRechazo("PRODUCTO_INEXISTENTE", new DatosReceta(Long.MAX_VALUE, BigDecimal.ONE, "UN", d.vigenteDesde(), null, d.itemsInsumo()));
    }
    @Test void rechazaItemsInsumoVacio() {
        assertRechazo("SOLICITUD_INVALIDA", conItems(List.of()));
        assertRechazo("SOLICITUD_INVALIDA", conItems(null)); assertRechazo("SOLICITUD_INVALIDA", null);
        var desde = f.fecha(2);
        for (var d : List.of(f.datos(desde, desde), f.datos(desde.plusNanos(100), desde.plusNanos(800))))
            assertRechazo("SOLICITUD_INVALIDA", d);
        assertRechazo("VIGENCIA_RETROACTIVA", f.datos(f.fecha(-1), null));
        assertEquals(0, f.versiones());
    }
    @Test void rechazaInsumoDuplicado() {
        var item = new ItemReceta(f.insumo, BigDecimal.ONE, "KG");
        assertRechazo("SOLICITUD_INVALIDA", conItems(List.of(item, item)));
    }
    @Test void rechazaInsumoInexistenteOInactivo() {
        assertRechazo("INSUMO_INEXISTENTE", conItems(List.of(new ItemReceta(Long.MAX_VALUE, BigDecimal.ONE, "KG"))));
        j.update("UPDATE \"INSUMO\" SET \"activo\"=false WHERE \"idInsumo\"=?", f.insumo);
        assertRechazo("INSUMO_INACTIVO", f.datos(f.fecha(2), null)); assertEquals(0, f.versiones());
    }
    @Test void rechazaCantidadNecesariaNoPositiva() {
        for (String n : List.of("0", "-1", "0.0001", "1000000000000000", "1E+18")) {
            assertRechazo("SOLICITUD_INVALIDA", conItems(List.of(new ItemReceta(f.insumo, new BigDecimal(n), "KG"))));
            var d = f.datos(f.fecha(2), null);
            assertRechazo("SOLICITUD_INVALIDA", new DatosReceta(f.producto, new BigDecimal(n), "UN", d.vigenteDesde(), null, d.itemsInsumo()));
        }
        var max = new BigDecimal("999999999999999.999");
        var r = crear(new DatosReceta(f.producto, max, "UN", f.fecha(2), null, List.of(new ItemReceta(f.insumo, max, "KG"))));
        assertEquals(max, r.cantidadRendimientoBase());
        assertEquals(max, j.queryForObject("SELECT \"cantidadNecesaria\" FROM \"DETALLE_VERSION_RECETA\" WHERE \"idReceta\"=?", BigDecimal.class, r.idReceta()));
    }
    @Test void rechazaUnidadNoCoincide() {
        var d = f.datos(f.fecha(2), null);
        assertRechazo("UNIDAD_NO_COINCIDE", new DatosReceta(f.producto, BigDecimal.ONE, "KG", d.vigenteDesde(), null, d.itemsInsumo()));
        assertRechazo("UNIDAD_NO_COINCIDE", conItems(List.of(new ItemReceta(f.insumo, BigDecimal.ONE, "UN"))));
        assertEquals(0, f.versiones());
    }
    @Test void rollbackSiFallaMitadNoPersisteNada() {
        // Falla real en el segundo detalle, después de crear versión/acotar la previa.
        j.execute("""
                CREATE FUNCTION test_uc10_rechazar_detalle() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW."cantidadNecesaria"=13 THEN RAISE EXCEPTION 'fallo sintetico UC10'; END IF; RETURN NEW; END $$
                """);
        j.execute("CREATE TRIGGER test_uc10_fallo BEFORE INSERT ON \"DETALLE_VERSION_RECETA\" FOR EACH ROW EXECUTE FUNCTION test_uc10_rechazar_detalle()");
        var d = conItems(List.of(new ItemReceta(f.insumo, BigDecimal.ONE, "KG"), new ItemReceta(f.insumo + 1, new BigDecimal("13"), "KG")));
        try {
            assertThrows(DataAccessException.class, () -> crear(d));
            assertEquals(0, j.queryForObject("SELECT count(*) FROM \"RECETA\" WHERE \"idProducto\"=?", Integer.class, f.producto));
            long id = f.sembrarVigente();
            assertThrows(DataAccessException.class, () -> crear(d)); assertEquals(1, f.versiones());
            assertNull(j.queryForObject("SELECT \"vigenteHasta\" FROM \"VERSION_RECETA\" WHERE \"idReceta\"=?", OffsetDateTime.class, id));
            assertEquals(1, j.queryForObject("SELECT count(*) FROM \"DETALLE_VERSION_RECETA\" WHERE \"idReceta\"=?", Integer.class, id));
        } finally {
            j.execute("DROP TRIGGER test_uc10_fallo ON \"DETALLE_VERSION_RECETA\"");
            j.execute("DROP FUNCTION test_uc10_rechazar_detalle()");
        }
    }
    @Test void rechazaRolNoAdmin() {
        var d = f.datos(f.fecha(2), null);
        for (String rol : List.of("VENDEDOR", "RESPONSABLE_PRODUCCION", "REPARTIDOR", "PROVEEDOR"))
            assertThrows(SecurityException.class, () -> servicio.configurarRecetaProducto(f.actores.sesiones.get(rol), d));
        assertThrows(SecurityException.class, () -> servicio.configurarRecetaProducto(null, d));
    }
}
