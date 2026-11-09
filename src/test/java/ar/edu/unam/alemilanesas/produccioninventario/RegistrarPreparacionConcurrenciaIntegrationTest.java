package ar.edu.unam.alemilanesas.produccioninventario;

import ar.edu.unam.alemilanesas.abastecimiento.RegistrarAjusteInventarioService;
import ar.edu.unam.alemilanesas.abastecimiento.RegistrarAjusteInventarioService.*;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import java.math.BigDecimal;
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

@SpringBootTest
@Testcontainers
class RegistrarPreparacionConcurrenciaIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl); r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired RegistrarPreparacionService servicio;
    @Autowired RegistrarAjusteInventarioService ajustes;
    @Autowired CargarPedidoService pedidos;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    PreparacionFixture f;
    @BeforeEach void fixture() { f = new PreparacionFixture(j,tx,pedidos); }
    private List<Object> concurrentes(Callable<?> a,Callable<?> b) throws Exception {
        var inicio = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Object> primero = pool.submit(() -> ejecutar(inicio,a));
            Future<Object> segundo = pool.submit(() -> ejecutar(inicio,b));
            inicio.countDown();
            return List.of(primero.get(35,TimeUnit.SECONDS),segundo.get(35,TimeUnit.SECONDS));
        }
    }
    private Object ejecutar(CountDownLatch inicio,Callable<?> operacion) throws Exception {
        inicio.await();
        try { return operacion.call(); }
        catch (RuntimeException ex) { return ex; }
    }
    @Test void dosPreparacionesConcurrentesMismoPedidoSoloUnaRespetaCantidadOriginal() throws Exception {
        UUID sesion = f.produccion(); var datos = f.datos("7");
        var resultados = concurrentes(() -> servicio.registrarPreparacionPedido(sesion,datos),
                () -> servicio.registrarPreparacionPedido(sesion,datos));
        assertEquals(1,resultados.stream().filter(RegistrarPreparacionService.PreparacionRegistrada.class::isInstance).count());
        var fallos = resultados.stream().filter(IllegalArgumentException.class::isInstance).map(IllegalArgumentException.class::cast).toList();
        assertEquals(1,fallos.size()); assertEquals("CANTIDAD_EXCEDE_ORIGINAL",fallos.getFirst().getMessage());
        assertEquals(1,f.preparaciones());
        assertEquals(new BigDecimal("7.000"),j.queryForObject("SELECT SUM(\"cantidadPreparada\") FROM \"PREPARACION\" WHERE \"idPedido\"=?",BigDecimal.class,f.pedido));
        assertEquals(new BigDecimal("8.600"),f.stock(f.insumo)); assertEquals(f.stock(f.insumo),f.disponible(f.lote));
        assertEquals(new BigDecimal("6.500"),f.stock(f.insumo+1)); assertEquals(f.stock(f.insumo+1),f.disponible(f.lote+1));
    }
    @Test void preparacionConcurrenteConUC42NoDuplicaDescuento() throws Exception {
        UUID sesion = f.produccion(); var preparacion = f.datos("5");
        var ajuste = new DatosAjuste("INSUMO",f.insumo,"AJUSTE_NEGATIVO",BigDecimal.ONE,"Conteo concurrente",null,
                List.of(new PartidaAjuste(f.lote,BigDecimal.ONE,null,null,false)));
        var resultados = concurrentes(() -> servicio.registrarPreparacionPedido(sesion,preparacion),
                () -> ajustes.registrarAjusteInventario(sesion,ajuste));
        assertEquals(1,resultados.stream().filter(RegistrarPreparacionService.PreparacionRegistrada.class::isInstance).count());
        assertEquals(1,resultados.stream().filter(AjusteRegistrado.class::isInstance).count());
        assertEquals(1,f.preparaciones()); assertEquals(new BigDecimal("8.000"),f.stock(f.insumo));
        assertEquals(f.stock(f.insumo),f.disponible(f.lote)); assertEquals(new BigDecimal("7.500"),f.disponible(f.lote+1));
        assertEquals(2,j.queryForObject("SELECT count(DISTINCT \"numeroSecuenciaInsumo\") FROM \"MOVIMIENTO_INSUMO\" WHERE \"idInsumo\"=?",Integer.class,f.insumo));
        assertEquals(new BigDecimal("2.000"),j.queryForObject("SELECT SUM(\"cantidadAplicada\") FROM \"DETALLE_MOVIMIENTO_PARTIDA\" WHERE \"idInsumo\"=?",BigDecimal.class,f.insumo));
    }
}
