package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.ventas.CargarPedidoService.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Transacciones reales independientes: ni @Transactional de test ni mocks para deduplicar. */
@SpringBootTest
@Testcontainers
class CargarPedidoConcurrenciaIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired CargarPedidoService pedidos;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    PedidoFixture f;
    @BeforeEach void fixture() {f=tx.execute(s->new PedidoFixture(j));}
    private List<PedidoCreado> simultaneos(Callable<PedidoCreado> uno,Callable<PedidoCreado> dos) throws Exception {
        var inicio=new CountDownLatch(1);var listos=new CountDownLatch(2);
        var hilos=Executors.newFixedThreadPool(2);
        try {
            var a=hilos.submit(()->{listos.countDown();assertTrue(inicio.await(10,TimeUnit.SECONDS));return uno.call();});
            var b=hilos.submit(()->{listos.countDown();assertTrue(inicio.await(10,TimeUnit.SECONDS));return dos.call();});
            assertTrue(listos.await(10,TimeUnit.SECONDS));inicio.countDown();
            return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        } finally {inicio.countDown();hilos.shutdownNow();}
    }
    @Test void mismaClaveConcurrenteCreaUnSoloPedidoYUnSoloJuegoDeDetalles() throws Exception {
        var d=f.datos();
        var resultados=simultaneos(()->pedidos.cargarPedido(f.vendedor(),d),
                ()->pedidos.cargarPedido(f.sesiones.get("VENDEDOR_2"),d));
        assertEquals(resultados.getFirst().idPedido(),resultados.getLast().idPedido());
        assertEquals(1,resultados.stream().filter(PedidoCreado::creado).count());
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"PEDIDO\" WHERE \"idOperacionOrigen\"=?",Integer.class,d.claveIdempotencia()));
        assertEquals(2,j.queryForObject("SELECT count(*) FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\"=?",Integer.class,resultados.getFirst().idPedido()));
        assertEquals(resultados.getFirst().totalOriginal(),resultados.getLast().totalOriginal());
    }
    @Test void clientesDistintosConcurrentesObtienenIdsSinColision() throws Exception {
        var g=tx.execute(s->new PedidoFixture(j));var primero=f.datos();var segundo=g.datos();
        var resultados=simultaneos(()->pedidos.cargarPedido(f.vendedor(),primero),()->pedidos.cargarPedido(g.vendedor(),segundo));
        assertTrue(resultados.stream().allMatch(PedidoCreado::creado));
        assertNotEquals(resultados.getFirst().idPedido(),resultados.getLast().idPedido());
        assertEquals(2,j.queryForObject("SELECT count(*) FROM \"PEDIDO\" WHERE \"idOperacionOrigen\" IN (?,?)",Integer.class,primero.claveIdempotencia(),segundo.claveIdempotencia()));
    }
}
