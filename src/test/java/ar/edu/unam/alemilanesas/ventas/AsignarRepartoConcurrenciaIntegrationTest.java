package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.acceso.CambiarEstadoUsuarioService;
import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService.AsignacionRegistrada;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers(disabledWithoutDocker=true)
class AsignarRepartoConcurrenciaIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired AsignarRepartoService servicio;
    @Autowired CargarPedidoService pedidos;
    @Autowired CambiarEstadoUsuarioService usuarios;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    private AsignacionRepartoFixture fixture() {return tx.execute(s->new AsignacionRepartoFixture(j,pedidos));}

    @Test void dosReasignacionesConcurrentesSoloUnaVigenteFinal() throws Exception {
        var f=fixture();servicio.asignarOReasignar(f.vendedor(),f.datos(f.primero,null));
        var inicio=new CountDownLatch(1);var listos=new CountDownLatch(2);var hilos=Executors.newFixedThreadPool(2);
        try {
            var a=hilos.submit(()->{listos.countDown();assertTrue(inicio.await(10,TimeUnit.SECONDS));
                return servicio.asignarOReasignar(f.vendedor(),f.datos(f.segundo,"Turno A"));});
            var b=hilos.submit(()->{listos.countDown();assertTrue(inicio.await(10,TimeUnit.SECONDS));
                return servicio.asignarOReasignar(f.origen.sesiones.get("VENDEDOR_2"),f.datos(f.tercero,"Turno B"));});
            assertTrue(listos.await(10,TimeUnit.SECONDS));inicio.countDown();
            List<AsignacionRegistrada> resultados=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertEquals(Set.of(2,3),new HashSet<>(resultados.stream().map(AsignacionRegistrada::numeroAsignacion).toList()));
            assertTrue(resultados.stream().allMatch(AsignacionRegistrada::fueReasignacion));
            assertEquals(3,j.queryForObject("SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=?",Integer.class,f.pendiente));
            assertEquals(1,j.queryForObject("SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? AND \"fechaHoraFin\" IS NULL",Integer.class,f.pendiente));
            assertEquals(2,j.queryForObject("SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? AND \"fechaHoraFin\" IS NOT NULL",Integer.class,f.pendiente));
        } finally {inicio.countDown();hilos.shutdownNow();}
    }
    @Test void concurrenteConUC55NoPermiteAsignarADeshabilitar() throws Exception {
        // Ambas ordenaciones se fuerzan y se observa la espera real de la segunda conexión en PostgreSQL.
        assertCarreraConUC55(true);assertCarreraConUC55(false);
    }
    private void assertCarreraConUC55(boolean asignacionPrimero) throws Exception {
        var f=fixture();var ganadorListo=new CountDownLatch(1);var segundoListo=new CountDownLatch(1);
        var pidSegundo=new AtomicInteger();var hilos=Executors.newFixedThreadPool(2);
        try {
            var ganador=hilos.submit(()->tx.execute(status->{
                operar(f,asignacionPrimero);ganadorListo.countDown();
                esperar(segundoListo);
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).until(()->Boolean.TRUE.equals(
                        j.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND locktype='advisory' AND classid=0 AND objid=9172001 AND objsubid=1 AND NOT granted)",Boolean.class,pidSegundo.get())));
                return true;
            }));
            var segundo=hilos.submit(()->{
                esperar(ganadorListo);
                try {
                    tx.executeWithoutResult(status->{
                        pidSegundo.set(j.queryForObject("SELECT pg_backend_pid()",Integer.class));segundoListo.countDown();
                        operar(f,!asignacionPrimero);
                    });
                    return null;
                } catch (RuntimeException ex) {return ex;}
            });
            assertTrue(ganador.get(20,TimeUnit.SECONDS));RuntimeException rechazo=segundo.get(20,TimeUnit.SECONDS);
            assertNotNull(rechazo);
            if (asignacionPrimero) {
                var ex=assertInstanceOf(CambiarEstadoUsuarioService.RepartidorConPedidosPendientesException.class,rechazo);
                assertEquals(List.of(f.pendiente),ex.pedidosPendientes());assertEstado(f,"HABILITADO",1);
            } else {
                assertInstanceOf(IllegalArgumentException.class,rechazo);assertEquals("Repartidor no habilitado",rechazo.getMessage());
                assertEstado(f,"DESHABILITADO",0);
            }
            assertEquals(0,j.queryForObject("""
                    SELECT count(*) FROM "ASIGNACION_REPARTO" a JOIN "USUARIO" u ON u."idUsuario"=a."idUsuarioRepartidor"
                    WHERE a."idPedido"=? AND a."fechaHoraFin" IS NULL AND u."estado"<>'HABILITADO'
                    """,Integer.class,f.pendiente));
        } finally {ganadorListo.countDown();segundoListo.countDown();hilos.shutdownNow();}
    }
    private void operar(AsignacionRepartoFixture f,boolean asignacion) {
        if (asignacion) servicio.asignarOReasignar(f.vendedor(),f.datos(f.primero,null));
        else usuarios.cambiarEstadoUsuario(f.admin(),f.primero,"DESHABILITADO");
    }
    private void assertEstado(AsignacionRepartoFixture f,String estado,int vigentes) {
        assertEquals(estado,j.queryForObject("SELECT \"estado\" FROM \"USUARIO\" WHERE \"idUsuario\"=?",String.class,f.primero));
        assertEquals(vigentes,j.queryForObject("SELECT count(*) FROM \"ASIGNACION_REPARTO\" WHERE \"idPedido\"=? AND \"fechaHoraFin\" IS NULL",Integer.class,f.pendiente));
    }
    private static void esperar(CountDownLatch latch) {
        try {assertTrue(latch.await(10,TimeUnit.SECONDS));}
        catch (InterruptedException ex) {Thread.currentThread().interrupt();throw new AssertionError(ex);}
    }
}
