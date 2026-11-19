package ar.edu.unam.alemilanesas.concurrencia;

import ar.edu.unam.alemilanesas.maestros.ActualizarPrecioVigenteService;
import ar.edu.unam.alemilanesas.maestros.ActualizarPrecioVigenteService.PrecioActualizado;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C3-04 (Nivel R + P): UC-19 (ActualizarPrecioVigenteService real) frente a
 * prototipo de UC-07 Parte A (captura de snapshot comercial de oferta).
 *
 * Verifica:
 * 1) Parte A con PRODUCTO S -> PRECIO_PRODUCTO S -> clock_timestamp() posterior
 *    a esperas serializa con UC-19 sin 40P01 y con snapshot coherente (precio anterior).
 * 2) UC-19 retenido en A2(1919,1) toma clock_timestamp() ANTES de esperar A2(1919,1),
 *    mientras Parte A esperando PRODUCTO S captura precio actualizado tras COMMIT de UC-19.
 */
@SpringBootTest
@Testcontainers
class C304Uc19VsUc07ParteAIntercaladoIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate tx;
    @Autowired ActualizarPrecioVigenteService precios;

    private record SnapshotCapturado(long idPrecio, BigDecimal precioBase, OffsetDateTime instanteCaptura) {}

    @Test
    void c304aParteAConSnapshotAnteriorSerializaSinDeadlockFrenteAUc19() throws Exception {
        var f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idProducto = f.base;
        OffsetDateTime desdeNuevo = jdbc.queryForObject(
                "SELECT clock_timestamp() + interval '5 seconds'", OffsetDateTime.class);

        var parteABloqueoProducto = new CountDownLatch(1);
        try (var connParteA = dataSource.getConnection();
             var pool = Executors.newFixedThreadPool(2)) {
            connParteA.setAutoCommit(false);
            int pidParteA = ObservabilidadPostgresTestHelper.pid(connParteA);

            Future<SnapshotCapturado> futuroParteA = pool.submit(() -> {
                try {
                    try (PreparedStatement psProd = connParteA.prepareStatement("""
                            SELECT "idProducto" FROM "PRODUCTO" WHERE "idProducto" = ? FOR SHARE
                            """)) {
                        psProd.setLong(1, idProducto);
                        try (ResultSet rs = psProd.executeQuery()) {
                            assertTrue(rs.next());
                        }
                    }
                    try (PreparedStatement psPrecios = connParteA.prepareStatement("""
                            SELECT "idPrecio","precioBase" FROM "PRECIO_PRODUCTO"
                             WHERE "idProducto" = ? AND "fechaHoraCancelacion" IS NULL
                             ORDER BY "vigenteDesde","idPrecio" FOR SHARE
                            """)) {
                        psPrecios.setLong(1, idProducto);
                        psPrecios.executeQuery();
                    }
                    parteABloqueoProducto.countDown();

                    var esperas = ObservabilidadPostgresTestHelper.esperarBloqueoDePid(
                            jdbc, pidParteA, "PRODUCTO");
                    assertFalse(esperas.isEmpty(), "UC-19 debe esperar PRODUCTO FOR UPDATE");

                    OffsetDateTime tCaptura;
                    try (PreparedStatement psReloj = connParteA.prepareStatement("SELECT clock_timestamp()");
                         ResultSet rsR = psReloj.executeQuery()) {
                        assertTrue(rsR.next());
                        tCaptura = rsR.getObject(1, OffsetDateTime.class);
                    }
                    SnapshotCapturado snap;
                    try (PreparedStatement psElegido = connParteA.prepareStatement("""
                            SELECT "idPrecio","precioBase" FROM "PRECIO_PRODUCTO"
                             WHERE "idProducto" = ? AND "fechaHoraCancelacion" IS NULL
                               AND "vigenteDesde" <= ?
                               AND ("vigenteHasta" IS NULL OR "vigenteHasta" > ?)
                            """)) {
                        psElegido.setLong(1, idProducto);
                        psElegido.setObject(2, tCaptura);
                        psElegido.setObject(3, tCaptura);
                        try (ResultSet rs = psElegido.executeQuery()) {
                            assertTrue(rs.next());
                            snap = new SnapshotCapturado(rs.getLong(1), rs.getBigDecimal(2), tCaptura);
                        }
                    }
                    connParteA.commit();
                    return snap;
                } catch (Throwable t) {
                    connParteA.rollback();
                    throw new RuntimeException(t);
                }
            });

            assertTrue(parteABloqueoProducto.await(10, TimeUnit.SECONDS));
            Future<PrecioActualizado> futuroUc19 = pool.submit(() -> precios.actualizarPrecioVigente(
                    f.sesiones.get("ADMINISTRADOR"), idProducto, new BigDecimal("250.0000"), desdeNuevo, null));

            SnapshotCapturado snap = futuroParteA.get(25, TimeUnit.SECONDS);
            PrecioActualizado actualizado = futuroUc19.get(25, TimeUnit.SECONDS);

            assertEquals(f.base, snap.idPrecio());
            assertEquals(0, snap.precioBase().compareTo(new BigDecimal("100.0000")));
            assertTrue(actualizado.idPrecio() > f.base);
            assertEquals(0, jdbc.queryForObject(
                    "SELECT \"precioBase\" FROM \"PRECIO_PRODUCTO\" WHERE \"idPrecio\" = ?",
                    BigDecimal.class, actualizado.idPrecio()).compareTo(new BigDecimal("250.0000")));
            assertTrue(snap.instanteCaptura().isBefore(actualizado.vigenteDesde()),
                    "El snapshot anterior debe quedar estrictamente antes de la nueva vigencia");
        }
    }

    @Test
    void c304bUc19ActualizaPrecioAntesDeCapturaParteAYEvidenciaRelojPrevioAAdvisory() throws Exception {
        var f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idProducto = f.base;

        // Barrera en A2(1919,1) para forzar que UC-19 tome clock_timestamp() antes de adquirir (1919,1)
        // y retenga PRODUCTO U mientras Parte A espera PRODUCTO S.
        try (var connCtrl = dataSource.getConnection();
             var connParteA = dataSource.getConnection();
             var pool = Executors.newFixedThreadPool(2)) {
            connCtrl.setAutoCommit(false);
            try {
            try (PreparedStatement psAdv = connCtrl.prepareStatement("SELECT pg_advisory_xact_lock(1919, 1)")) {
                psAdv.execute();
            }
            int pidCtrl = ObservabilidadPostgresTestHelper.pid(connCtrl);

            OffsetDateTime desdeNuevo = jdbc.queryForObject(
                    "SELECT clock_timestamp() + interval '150 milliseconds'", OffsetDateTime.class);

            Future<PrecioActualizado> futuroUc19 = pool.submit(() -> precios.actualizarPrecioVigente(
                    f.sesiones.get("ADMINISTRADOR"), idProducto, new BigDecimal("350.0000"), desdeNuevo, null));

            var esperaAdv = ObservabilidadPostgresTestHelper.esperarBloqueoAdvisory2(
                    jdbc, pidCtrl, 1919, 1);
            assertFalse(esperaAdv.isEmpty(), "UC-19 debe estar esperando A2(1919,1) tras tomar PRODUCTO U y clock_timestamp()");
            int pidUc19 = ((Number) esperaAdv.getFirst().get("pid")).intValue();

            connParteA.setAutoCommit(false);
            Future<SnapshotCapturado> futuroParteA = pool.submit(() -> {
                try {
                    try (PreparedStatement psProd = connParteA.prepareStatement("""
                            SELECT "idProducto" FROM "PRODUCTO" WHERE "idProducto" = ? FOR SHARE
                            """)) {
                        psProd.setLong(1, idProducto);
                        psProd.executeQuery();
                    }
                    try (PreparedStatement psPrecios = connParteA.prepareStatement("""
                            SELECT "idPrecio","precioBase" FROM "PRECIO_PRODUCTO"
                             WHERE "idProducto" = ? AND "fechaHoraCancelacion" IS NULL
                             ORDER BY "vigenteDesde","idPrecio" FOR SHARE
                            """)) {
                        psPrecios.setLong(1, idProducto);
                        psPrecios.executeQuery();
                    }
                    OffsetDateTime tCaptura;
                    try (PreparedStatement psReloj = connParteA.prepareStatement("SELECT clock_timestamp()");
                         ResultSet rsR = psReloj.executeQuery()) {
                        assertTrue(rsR.next());
                        tCaptura = rsR.getObject(1, OffsetDateTime.class);
                    }
                    try (PreparedStatement psElegido = connParteA.prepareStatement("""
                            SELECT "idPrecio","precioBase" FROM "PRECIO_PRODUCTO"
                             WHERE "idProducto" = ? AND "fechaHoraCancelacion" IS NULL
                               AND "vigenteDesde" <= ?
                               AND ("vigenteHasta" IS NULL OR "vigenteHasta" > ?)
                            """)) {
                        psElegido.setLong(1, idProducto);
                        psElegido.setObject(2, tCaptura);
                        psElegido.setObject(3, tCaptura);
                        try (ResultSet rs = psElegido.executeQuery()) {
                            assertTrue(rs.next());
                            SnapshotCapturado s = new SnapshotCapturado(
                                    rs.getLong(1), rs.getBigDecimal(2), tCaptura);
                            connParteA.commit();
                            return s;
                        }
                    }
                } catch (Throwable t) {
                    connParteA.rollback();
                    throw new RuntimeException(t);
                }
            });

            var esperaProd = ObservabilidadPostgresTestHelper.esperarBloqueoDePid(
                    jdbc, pidUc19, "PRODUCTO");
            assertFalse(esperaProd.isEmpty(), "Parte A debe esperar a UC-19 en PRODUCTO");

            // Dejamos pasar el instante desdeNuevo para comprobar que UC-19 ya había validado
            // vigenteDesde antes de esperar A2(1919,1), y al liberar commit Parte A ve el precio nuevo.
            Thread.sleep(250);
            connCtrl.rollback();

            PrecioActualizado actualizado = futuroUc19.get(25, TimeUnit.SECONDS);
            SnapshotCapturado snap = futuroParteA.get(25, TimeUnit.SECONDS);

            assertEquals(actualizado.idPrecio(), snap.idPrecio(),
                    "Parte A debe capturar el precio actualizado tras esperar PRODUCTO S");
            assertEquals(0, snap.precioBase().compareTo(new BigDecimal("350.0000")));
            assertFalse(snap.instanteCaptura().isBefore(actualizado.vigenteDesde()),
                    "El reloj posterior a esperas debe ser >= vigenteDesde del precio capturado");
            } finally {
                connCtrl.rollback();
                pool.shutdownNow();
            }
        }
    }
}
