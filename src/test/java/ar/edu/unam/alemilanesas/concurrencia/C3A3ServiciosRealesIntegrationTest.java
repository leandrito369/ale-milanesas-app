package ar.edu.unam.alemilanesas.concurrencia;

import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import ar.edu.unam.alemilanesas.produccioninventario.PreparacionFixture;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * C3-A.3 / Nivel R: intercalados deterministas sobre servicios ya integrados.
 *
 * El tercero (controlador de barrera) retiene temporalmente una fila real.
 * Los otros dos hilos llaman servicios productivos sin modificar su código.
 * Las esperas se observan en pg_locks / pg_blocking_pids, no con sleeps.
 *
 * La serialización de estos dos casos NO certifica H/RT-UC07-04 ni UC-05/07/41.
 */
@SpringBootTest
@Testcontainers
class C3A3ServiciosRealesIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", db::getJdbcUrl);
        registro.add("spring.datasource.username", db::getUsername);
        registro.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired RegistrarPreparacionService preparaciones;
    @Autowired CobroManualService cobros;

    private static final long CLIENTE_COLISION_COBRO = 2929001L;

    private CargarPedidoService.DatosPedido datos(PedidoFixture f, long cliente, long producto) {
        return new CargarPedidoService.DatosPedido(
                cliente, f.hoy.plusDays(2),
                List.of(new CargarPedidoService.ItemPedido(producto, new BigDecimal("1.000"))),
                new CargarPedidoService.DireccionEntrega(
                        "Calle de laboratorio", "100", null, null, "Posadas", "Misiones", null, null),
                UUID.randomUUID());
    }

    private void insertarCliente(long id) {
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO "CLIENTE"
                    ("idCliente","nombres","telefonoPrincipal","estado","categoriaComercial","fechaHoraAlta")
                VALUES (?, 'Cliente sintetico C3A3', '3764000000', 'ACTIVO', 'CUMPLIDOR', now())
                """, id));
    }

    /** La fila bloqueada existe y se conserva hasta rollback del controlador. */
    private static void bloquearFila(Connection conexion, String sql, long id) throws Exception {
        conexion.setAutoCommit(false);
        try (PreparedStatement p = conexion.prepareStatement(sql)) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next(), "La fila de la barrera debe existir");
            }
        }
    }

    private static int pid(Connection conexion) throws Exception {
        try (PreparedStatement p = conexion.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rs = p.executeQuery()) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    /**
     * Devuelve el PID que tiene A1(id) concedido y está bloqueado por la
     * conexión controladora. Esto acredita progreso real hasta la barrera.
     */
    private int esperarConcesionYEspera(long clave, int pidBloqueador) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(25))
                .until(() -> !sesionesConcedidasYBloqueadas(clave, pidBloqueador).isEmpty());
        return sesionesConcedidasYBloqueadas(clave, pidBloqueador).getFirst();
    }

    private List<Integer> sesionesConcedidasYBloqueadas(long clave, int pidBloqueador) {
        return jdbc.queryForList("""
                SELECT a.pid FROM pg_locks l
                JOIN pg_stat_activity a ON a.pid = l.pid
                WHERE l.locktype = 'advisory'
                  AND l.classid::bigint = 0
                  AND l.objid::bigint = ?
                  AND l.objsubid = 1 AND l.granted
                  AND a.wait_event_type = 'Lock'
                  AND ? = ANY(pg_blocking_pids(a.pid))
                """, Integer.class, clave, pidBloqueador);
    }

    /** El segundo servicio espera A1(id), retenido por el primer servicio. */
    private void esperarAdvisoryPendiente(long clave, int pidTitular) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(25))
                .until(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                          SELECT 1 FROM pg_locks l
                          WHERE l.locktype = 'advisory'
                            AND l.classid::bigint = 0
                            AND l.objid::bigint = ?
                            AND l.objsubid = 1 AND NOT l.granted
                            AND ? = ANY(pg_blocking_pids(l.pid))
                        )
                        """, Boolean.class, clave, pidTitular)));
    }

    @Test
    void c301Uc03YUc28MuestranContencionEntreClienteEInsumoSinDeadlock() throws Exception {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        // Mismo número físico para dos entidades distintas: CLIENTE e INSUMO.
        insertarCliente(f.insumo);
        var datosPedido = datos(f.actores, f.insumo, f.producto);

        try (var bloqueador = dataSource.getConnection();
             var pool = Executors.newFixedThreadPool(2)) {
            // UC-03 llega a A1(idCliente) y queda esperando el precio.
            // UC-28 no consulta PRECIO_PRODUCTO; luego solicitará A1(idInsumo).
            bloquearFila(bloqueador, """
                    SELECT "idPrecio" FROM "PRECIO_PRODUCTO"
                    WHERE "idProducto"=? FOR UPDATE
                    """, f.producto);
            int pidBloqueador = pid(bloqueador);
            try {
                var pedidoFuturo = pool.submit(() ->
                        pedidos.cargarPedido(f.actores.vendedor(), datosPedido));
                int pidPedido = esperarConcesionYEspera(f.insumo, pidBloqueador);

                var preparacionFutura = pool.submit(() ->
                        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("1")));
                esperarAdvisoryPendiente(f.insumo, pidPedido);

                // Libera la barrera: ambos servicios completan sus COMMIT reales.
                bloqueador.rollback();
                var pedido = pedidoFuturo.get(25, TimeUnit.SECONDS);
                var preparacion = preparacionFutura.get(25, TimeUnit.SECONDS);

                assertTrue(pedido.creado());
                assertTrue(pedido.idPedido() > 0);
                assertEquals(f.pedido, preparacion.idPedido());
                assertEquals(1, f.preparaciones());
                assertEquals(1, jdbc.queryForObject("""
                        SELECT count(*) FROM "PEDIDO" WHERE "idOperacionOrigen"=?
                        """, Integer.class, datosPedido.claveIdempotencia()));
                assertEquals(0, f.stock(f.insumo).compareTo(new BigDecimal("9.800")));
                assertEquals(0, f.disponible(f.lote).compareTo(f.stock(f.insumo)));
            } finally {
                bloqueador.rollback();
            }
        }
    }

    @Test
    void c302Uc29YUc03SerializanMismoAdvisorySinDobleAsientoNiDeadlock() throws Exception {
        var f = tx.execute(status -> new PedidoFixture(jdbc));
        insertarCliente(CLIENTE_COLISION_COBRO);

        // UC-29 registra un cobro vinculado a un Pedido existente del Cliente.
        var anterior = pedidos.cargarPedido(
                f.vendedor(), datos(f, CLIENTE_COLISION_COBRO, f.base));
        var nuevo = datos(f, CLIENTE_COLISION_COBRO, f.base);
        UUID claveCobro = UUID.randomUUID();

        try (var bloqueador = dataSource.getConnection();
             var pool = Executors.newFixedThreadPool(2)) {
            // UC-29 obtiene A1(2929001), queda esperando CLIENTE FOR UPDATE;
            // UC-03 queda a su vez esperando ese A1 compartido.
            bloquearFila(bloqueador, """
                    SELECT "idCliente" FROM "CLIENTE"
                    WHERE "idCliente"=? FOR UPDATE
                    """, CLIENTE_COLISION_COBRO);
            int pidBloqueador = pid(bloqueador);
            try {
                var cobroFuturo = pool.submit(() -> cobros.registrarCobro(
                        f.vendedor(), CLIENTE_COLISION_COBRO, anterior.idPedido(),
                        new BigDecimal("20.00"), "EFECTIVO", claveCobro));
                int pidCobro = esperarConcesionYEspera(CLIENTE_COLISION_COBRO, pidBloqueador);

                var pedidoFuturo = pool.submit(() ->
                        pedidos.cargarPedido(f.vendedor(), nuevo));
                esperarAdvisoryPendiente(CLIENTE_COLISION_COBRO, pidCobro);

                bloqueador.rollback();
                var cobro = cobroFuturo.get(25, TimeUnit.SECONDS);
                var pedido = pedidoFuturo.get(25, TimeUnit.SECONDS);

                assertEquals("CONFIRMADO", cobro.estado());
                assertTrue(pedido.creado());
                assertNotEquals(anterior.idPedido(), pedido.idPedido());
                assertEquals(2, jdbc.queryForObject("""
                        SELECT count(*) FROM "PEDIDO" WHERE "idCliente"=?
                        """, Integer.class, CLIENTE_COLISION_COBRO));
                assertEquals(1, jdbc.queryForObject("""
                        SELECT count(*) FROM "COBRO"
                        WHERE "idCliente"=? AND "claveIdempotenciaOrigen"=?
                        """, Integer.class, CLIENTE_COLISION_COBRO, claveCobro));
                assertEquals(1, jdbc.queryForObject("""
                        SELECT count(*) FROM "MOVIMIENTO_CAJA" WHERE "idCobro"=?
                        """, Integer.class, cobro.idCobro()));
            } finally {
                bloqueador.rollback();
            }
        }
    }
}
