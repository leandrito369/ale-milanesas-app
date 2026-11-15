package ar.edu.unam.alemilanesas.concurrencia;

import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C3-08 (Nivel R + P): UC-29 (CobroManualService real) frente a prototipo
 * de locks/generadores de UC-05 (ENTREGA + CARGO_POR_VENTA).
 *
 * Restricción D5/D6: NO implementa ni asume fórmula de importe reconocido en
 * efectivo en tránsito ni crea PAGO_EN_TRANSITO. Estudia exclusivamente
 * serialización, inversión de locks CLIENTE <-> PEDIDO y generadores 2929001.
 */
@SpringBootTest
@Testcontainers
class C308Uc29VsUc05IntercaladoIntegrationTest {
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
    @Autowired CargarPedidoService pedidos;
    @Autowired CobroManualService cobros;

    @Test
    void c308aInversionPedidoAntesDeClienteProduceDeadlock40P01ConUc29() throws Exception {
        var f = new PedidoFixture(jdbc);
        long idPedido = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long idCliente = f.base;
        UUID claveCobro = UUID.randomUUID();

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
                        ps1.setLong(1, idPedido);
                        try (ResultSet rs = ps1.executeQuery()) {
                            assertTrue(rs.next());
                        }
                    }
                    prototipoBloqueoPedido.countDown();

                    var esperas = ObservabilidadPostgresTestHelper.esperarBloqueoDePid(
                            jdbc, pidPrototipo, "PEDIDO");
                    assertFalse(esperas.isEmpty(), "UC-29 debe quedar esperando PEDIDO FOR SHARE");

                    try (PreparedStatement ps2 = connPrototipo.prepareStatement("""
                            SELECT "idCliente" FROM "CLIENTE" WHERE "idCliente" = ? FOR UPDATE
                            """)) {
                        ps2.setLong(1, idCliente);
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

            assertTrue(prototipoBloqueoPedido.await(10, TimeUnit.SECONDS));
            Future<String> futuroUc29 = pool.submit(() -> {
                try {
                    cobros.registrarCobro(f.vendedor(), idCliente, idPedido,
                            new BigDecimal("100.00"), "EFECTIVO", claveCobro);
                    return "COMMIT";
                } catch (Throwable t) {
                    String sqlState = ObservabilidadPostgresTestHelper.extraerSqlState(t);
                    return sqlState != null ? sqlState : t.getClass().getSimpleName();
                }
            });

            String rProto = futuroPrototipo.get(25, TimeUnit.SECONDS);
            String rUc29 = futuroUc29.get(25, TimeUnit.SECONDS);

            assertTrue("40P01".equals(rProto) || "40P01".equals(rUc29),
                    "Se esperaba SQLSTATE 40P01 por inversion PEDIDO <-> CLIENTE, fue: proto="
                            + rProto + ", uc29=" + rUc29);
            assertTrue("COMMIT".equals(rProto) || "COMMIT".equals(rUc29),
                    "Una de las transacciones debia sobrevivir tras resolver el deadlock");
        }
    }

    @Test
    void c308bOrdenAlineado2929001ClientePedidoSerializaSinColisionDeSecuencia() throws Exception {
        var f = new PedidoFixture(jdbc);
        long idPedido = pedidos.cargarPedido(f.vendedor(), f.datos()).idPedido();
        long idCliente = f.base;
        UUID claveCobro = UUID.randomUUID();
        BigDecimal totalCargo = jdbc.queryForObject(
                "SELECT \"totalOriginal\" FROM \"PEDIDO\" WHERE \"idPedido\" = ?", BigDecimal.class, idPedido);
        long repartidor = f.base + 3;

        var prototipoBloqueo2929001 = new CountDownLatch(1);
        try (var connPrototipo = dataSource.getConnection();
             var pool = Executors.newFixedThreadPool(2)) {
            connPrototipo.setAutoCommit(false);
            int pidPrototipo = ObservabilidadPostgresTestHelper.pid(connPrototipo);

            Future<String> futuroPrototipo = pool.submit(() -> {
                try {
                    try (PreparedStatement psAdv = connPrototipo.prepareStatement(
                            "SELECT pg_advisory_xact_lock(2929001)")) {
                        psAdv.execute();
                    }
                    try (PreparedStatement psCli = connPrototipo.prepareStatement("""
                            SELECT "idCliente" FROM "CLIENTE" WHERE "idCliente" = ? FOR UPDATE
                            """)) {
                        psCli.setLong(1, idCliente);
                        assertTrue(psCli.executeQuery().next());
                    }
                    try (PreparedStatement psPed = connPrototipo.prepareStatement("""
                            SELECT "estado" FROM "PEDIDO" WHERE "idPedido" = ? FOR UPDATE
                            """)) {
                        psPed.setLong(1, idPedido);
                        assertTrue(psPed.executeQuery().next());
                    }
                    prototipoBloqueo2929001.countDown();

                    var esperas = ObservabilidadPostgresTestHelper.esperarBloqueoAdvisory1(
                            jdbc, pidPrototipo, 2929001L);
                    assertFalse(esperas.isEmpty(), "UC-29 debe esperar advisory 2929001");

                    try (PreparedStatement insEnt = connPrototipo.prepareStatement("""
                            INSERT INTO "ENTREGA" ("idPedido","fechaHoraEntrega","idUsuarioRepartidor")
                            VALUES (?, clock_timestamp(), ?)
                            """)) {
                        insEnt.setLong(1, idPedido);
                        insEnt.setLong(2, repartidor);
                        insEnt.executeUpdate();
                    }
                    long idMov;
                    try (PreparedStatement psId = connPrototipo.prepareStatement(
                            "SELECT COALESCE(MAX(\"idMovimientoCuenta\"),0)+1 FROM \"MOVIMIENTO_CUENTA\"");
                         ResultSet rs = psId.executeQuery()) {
                        assertTrue(rs.next());
                        idMov = rs.getLong(1);
                    }
                    long seq;
                    try (PreparedStatement psSeq = connPrototipo.prepareStatement(
                            "SELECT COALESCE(MAX(\"numeroSecuenciaCliente\"),0)+1 FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=?");) {
                        psSeq.setLong(1, idCliente);
                        try (ResultSet rs = psSeq.executeQuery()) {
                            assertTrue(rs.next());
                            seq = rs.getLong(1);
                        }
                    }
                    try (PreparedStatement insMov = connPrototipo.prepareStatement("""
                            INSERT INTO "MOVIMIENTO_CUENTA"
                                ("idMovimientoCuenta","idCliente","numeroSecuenciaCliente",
                                 "tipoMovimiento","importe","fechaHoraMovimiento","saldoResultante")
                            VALUES (?, ?, ?, 'CARGO_POR_VENTA', ?, clock_timestamp(), ?)
                            """)) {
                        insMov.setLong(1, idMov);
                        insMov.setLong(2, idCliente);
                        insMov.setLong(3, seq);
                        insMov.setBigDecimal(4, totalCargo);
                        insMov.setBigDecimal(5, totalCargo);
                        insMov.executeUpdate();
                    }
                    try (PreparedStatement insOrig = connPrototipo.prepareStatement("""
                            INSERT INTO "ORIGEN_MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idPedidoEntrega")
                            VALUES (?, ?)
                            """)) {
                        insOrig.setLong(1, idMov);
                        insOrig.setLong(2, idPedido);
                        insOrig.executeUpdate();
                    }
                    try (PreparedStatement updPed = connPrototipo.prepareStatement("""
                            UPDATE "PEDIDO" SET "estado" = 'ENTREGADO' WHERE "idPedido" = ?
                            """)) {
                        updPed.setLong(1, idPedido);
                        updPed.executeUpdate();
                    }
                    connPrototipo.commit();
                    return "COMMIT";
                } catch (Throwable t) {
                    connPrototipo.rollback();
                    throw new RuntimeException(t);
                }
            });

            assertTrue(prototipoBloqueo2929001.await(10, TimeUnit.SECONDS));
            Future<CobroManualService.Resultado> futuroUc29 = pool.submit(() ->
                    cobros.registrarCobro(f.vendedor(), idCliente, idPedido,
                            new BigDecimal("100.00"), "EFECTIVO", claveCobro));

            assertEquals("COMMIT", futuroPrototipo.get(25, TimeUnit.SECONDS));
            CobroManualService.Resultado resCobro = futuroUc29.get(25, TimeUnit.SECONDS);

            assertEquals("CONFIRMADO", resCobro.estado());
            assertEquals(0, resCobro.saldoConfirmado().compareTo(totalCargo.subtract(new BigDecimal("100.00"))));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT count(*) FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\" = ?",
                    Integer.class, idCliente));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"COBRO\" WHERE \"claveIdempotenciaOrigen\" = ?",
                    Integer.class, claveCobro));
        }
    }
}
