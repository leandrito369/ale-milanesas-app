package ar.edu.unam.alemilanesas.concurrencia;

import ar.edu.unam.alemilanesas.ventas.AsignacionRepartoFixture;
import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * C3-03 (Nivel R + P): UC-43 (AsignarRepartoService real) frente a prototipo
 * de UC-05 (Registrar Entrega sobre PEDIDO y ASIGNACION_REPARTO).
 *
 * No implementa UC-05 ni altera UC-43. Demuestra:
 * 1) Inversion ASIGNACION_REPARTO U -> PEDIDO U frente a UC-43 produce 40P01 real.
 * 2) Orden alineado (PEDIDO U -> ASIGNACION_REPARTO U + revalidacion) serializa sin 40P01.
 */
@SpringBootTest
@Testcontainers
class C303Uc43VsUc05IntercaladoIntegrationTest {
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
    @Autowired CargarPedidoService pedidos;
    @Autowired AsignarRepartoService asignaciones;

    @Test
    void c303aInversionAsignacionAntesDePedidoProduceDeadlock40P01() throws Exception {
        var f = tx.execute(s -> new AsignacionRepartoFixture(jdbc, pedidos));
        assertNotNull(f);
        asignaciones.asignarOReasignar(f.vendedor(), f.datos(f.primero, null));

        var prototipoBloqueoAsignacion = new CountDownLatch(1);
        try (var connPrototipo = dataSource.getConnection();
             var pool = Executors.newFixedThreadPool(2)) {
            connPrototipo.setAutoCommit(false);
            int pidPrototipo = ObservabilidadPostgresTestHelper.pid(connPrototipo);

            Future<String> futuroPrototipo = pool.submit(() -> {
                try (PreparedStatement ps1 = connPrototipo.prepareStatement("""
                        SELECT "numeroAsignacion" FROM "ASIGNACION_REPARTO"
                         WHERE "idPedido" = ? AND "fechaHoraFin" IS NULL FOR UPDATE
                        """)) {
                    ps1.setLong(1, f.pendiente);
                    try (ResultSet rs = ps1.executeQuery()) {
                        assertTrue(rs.next());
                    }
                    prototipoBloqueoAsignacion.countDown();

                    var esperas = ObservabilidadPostgresTestHelper.esperarBloqueoDePid(
                            jdbc, pidPrototipo, "ASIGNACION_REPARTO");
                    assertFalse(esperas.isEmpty(), "UC-43 debe estar esperando ASIGNACION_REPARTO");

                    try (PreparedStatement ps2 = connPrototipo.prepareStatement("""
                            SELECT "estado" FROM "PEDIDO" WHERE "idPedido" = ? FOR UPDATE
                            """)) {
                        ps2.setLong(1, f.pendiente);
                        ps2.executeQuery();
                    }
                    connPrototipo.commit();
                    return "COMMIT";
                } catch (Throwable t) {
                    connPrototipo.rollback();
                    String sqlState = ObservabilidadPostgresTestHelper.extraerSqlState(t);
                    return sqlState != null ? sqlState : t.getClass().getSimpleName();
                }
            });

            assertTrue(prototipoBloqueoAsignacion.await(10, TimeUnit.SECONDS));
            Future<String> futuroUc43 = pool.submit(() -> {
                try {
                    asignaciones.asignarOReasignar(
                            f.vendedor(), f.datos(f.segundo, "Reasignacion C3-03a"));
                    return "COMMIT";
                } catch (Throwable t) {
                    String sqlState = ObservabilidadPostgresTestHelper.extraerSqlState(t);
                    return sqlState != null ? sqlState : t.getClass().getSimpleName();
                }
            });

            String rProto = futuroPrototipo.get(25, TimeUnit.SECONDS);
            String rUc43 = futuroUc43.get(25, TimeUnit.SECONDS);

            assertTrue("40P01".equals(rProto) || "40P01".equals(rUc43),
                    "Se esperaba SQLSTATE 40P01 por inversion PEDIDO <-> ASIGNACION_REPARTO, fue: proto="
                            + rProto + ", uc43=" + rUc43);
            assertTrue("COMMIT".equals(rProto) || "COMMIT".equals(rUc43),
                    "Una de las transacciones debia sobrevivir tras resolver el deadlock");
        }
    }

    @Test
    void c303bOrdenAlineadoPedidoLuegoAsignacionSerializaSinDeadlockYRevalida() throws Exception {
        var f = tx.execute(s -> new AsignacionRepartoFixture(jdbc, pedidos));
        assertNotNull(f);
        asignaciones.asignarOReasignar(f.vendedor(), f.datos(f.primero, null));

        var prototipoBloqueoPedido = new CountDownLatch(1);
        try (var connPrototipo = dataSource.getConnection();
             var pool = Executors.newFixedThreadPool(2)) {
            connPrototipo.setAutoCommit(false);
            int pidPrototipo = ObservabilidadPostgresTestHelper.pid(connPrototipo);

            Future<String> futuroPrototipo = pool.submit(() -> {
                try {
                    try (PreparedStatement ps1 = connPrototipo.prepareStatement("""
                            SELECT "estado" FROM "PEDIDO" WHERE "idPedido" = ? FOR UPDATE
                            """)) {
                        ps1.setLong(1, f.pendiente);
                        try (ResultSet rs = ps1.executeQuery()) {
                            assertTrue(rs.next());
                            assertEquals("PENDIENTE", rs.getString(1));
                        }
                    }
                    prototipoBloqueoPedido.countDown();

                    var esperas = ObservabilidadPostgresTestHelper.esperarBloqueoDePid(
                            jdbc, pidPrototipo, "PEDIDO");
                    assertFalse(esperas.isEmpty(), "UC-43 debe esperar en PEDIDO FOR UPDATE");

                    try (PreparedStatement ps2 = connPrototipo.prepareStatement("""
                            SELECT "numeroAsignacion","idUsuarioRepartidor" FROM "ASIGNACION_REPARTO"
                             WHERE "idPedido" = ? AND "fechaHoraFin" IS NULL FOR UPDATE
                            """)) {
                        ps2.setLong(1, f.pendiente);
                        try (ResultSet rs = ps2.executeQuery()) {
                            assertTrue(rs.next());
                            assertEquals(f.primero, rs.getLong(2));
                        }
                    }
                    try (PreparedStatement insEntrega = connPrototipo.prepareStatement("""
                            INSERT INTO "ENTREGA" ("idPedido","fechaHoraEntrega","idUsuarioRepartidor")
                            VALUES (?, clock_timestamp(), ?)
                            """)) {
                        insEntrega.setLong(1, f.pendiente);
                        insEntrega.setLong(2, f.primero);
                        insEntrega.executeUpdate();
                    }
                    try (PreparedStatement updPedido = connPrototipo.prepareStatement("""
                            UPDATE "PEDIDO" SET "estado" = 'ENTREGADO' WHERE "idPedido" = ?
                            """)) {
                        updPedido.setLong(1, f.pendiente);
                        updPedido.executeUpdate();
                    }
                    try (PreparedStatement cerrarAsig = connPrototipo.prepareStatement("""
                            UPDATE "ASIGNACION_REPARTO" SET "fechaHoraFin" = clock_timestamp()
                             WHERE "idPedido" = ? AND "fechaHoraFin" IS NULL
                            """)) {
                        cerrarAsig.setLong(1, f.pendiente);
                        cerrarAsig.executeUpdate();
                    }
                    connPrototipo.commit();
                    return "COMMIT";
                } catch (Throwable t) {
                    connPrototipo.rollback();
                    throw new RuntimeException(t);
                }
            });

            assertTrue(prototipoBloqueoPedido.await(10, TimeUnit.SECONDS));
            Future<Throwable> errorUc43 = pool.submit(() -> {
                try {
                    asignaciones.asignarOReasignar(
                            f.vendedor(), f.datos(f.segundo, "Reasignacion tardia C3-03b"));
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            assertEquals("COMMIT", futuroPrototipo.get(25, TimeUnit.SECONDS));
            Throwable rechazo = errorUc43.get(25, TimeUnit.SECONDS);
            assertNotNull(rechazo, "UC-43 debe rechazar reasignacion tras entrega");
            assertNull(ObservabilidadPostgresTestHelper.extraerSqlState(rechazo),
                    "No debe haber error SQL ni 40P01");
            assertInstanceOf(IllegalArgumentException.class, rechazo);
            assertEquals("Pedido no está PENDIENTE", rechazo.getMessage());

            assertEquals("ENTREGADO", jdbc.queryForObject(
                    "SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=?", String.class, f.pendiente));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=?", Integer.class, f.pendiente));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"ENTREGA\" WHERE \"idPedido\"=?", Integer.class, f.pendiente));
        }
    }
}
