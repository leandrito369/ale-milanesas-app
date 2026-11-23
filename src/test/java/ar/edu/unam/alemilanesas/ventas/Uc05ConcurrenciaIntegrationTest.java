package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import ar.edu.unam.alemilanesas.finanzas.ImputacionFinancieraService;
import ar.edu.unam.alemilanesas.produccioninventario.PreparacionFixture;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.DatosEntrega;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.EntregaRegistrada;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.ItemEntrega;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Concurrencia real UC-05 <-> UC-28, UC-05 <-> UC-43, UC-05 <-> UC-29 (alta + L02 anulacion)
 * y contencion sobre stock REVENTA / idempotencia en PostgreSQL 17.11 con 0 deadlocks (40P01).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class Uc05ConcurrenciaIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired RegistrarPreparacionService preparaciones;
    @Autowired AsignarRepartoService asignaciones;
    @Autowired GestionarRecorridoRepartoService recorridos;
    @Autowired CobroManualService cobros;
    @Autowired ImputacionFinancieraService imputacion;
    @Autowired RegistrarEntregaService entregas;

    private static String sqlState(Throwable t) {
        Throwable c = t;
        while (c != null) {
            if (c instanceof SQLException sqlEx && sqlEx.getSQLState() != null) return sqlEx.getSQLState();
            c = c.getCause();
        }
        return "";
    }

    @Test
    void uc05ContraUc28YUc43EnParaleloNoProduceDeadlock() throws Exception {
        for (int ronda = 0; ronda < 3; ronda++) {
            var f = new PreparacionFixture(jdbc, tx, pedidos);
            long rep1 = f.actores.base + 3;
            long rep2 = tx.execute(s -> {
                long r2 = f.actores.base + 90;
                jdbc.update("""
                        INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta")
                        VALUES (?,?,'HASH_TEST','HABILITADO',false,now())
                        """, r2, "rep_c43_" + r2);
                jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'REPARTIDOR',now())", r2);
                return r2;
            });

            preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("3"));
            var rec1 = recorridos.abrirRecorrido(f.actores.vendedor(), rep1, "REC-C1-" + ronda);
            recorridos.abrirRecorrido(f.actores.vendedor(), rep2, "REC-C2-" + ronda);
            recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                    new AsignarRepartoService.DatosAsignacion(f.pedido, rep1, null), rec1.idRecorrido());

            AtomicInteger deadlocks = new AtomicInteger();
            CountDownLatch listos = new CountDownLatch(3);
            CountDownLatch largada = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
                Future<String> fUc05 = pool.submit(() -> {
                    listos.countDown();
                    assertTrue(largada.await(5, TimeUnit.SECONDS));
                    try {
                        entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                                f.pedido, List.of(new ItemEntrega(f.producto, new BigDecimal("2.000"))),
                                new BigDecimal("50.00"), UUID.randomUUID()));
                        return "UC05_OK";
                    } catch (Throwable t) {
                        if ("40P01".equals(sqlState(t))) deadlocks.incrementAndGet();
                        return t.getClass().getSimpleName() + ":" + t.getMessage();
                    }
                });
                Future<String> fUc28 = pool.submit(() -> {
                    listos.countDown();
                    assertTrue(largada.await(5, TimeUnit.SECONDS));
                    try {
                        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("2"));
                        return "UC28_OK";
                    } catch (Throwable t) {
                        if ("40P01".equals(sqlState(t))) deadlocks.incrementAndGet();
                        return t.getClass().getSimpleName() + ":" + t.getMessage();
                    }
                });
                Future<String> fUc43 = pool.submit(() -> {
                    listos.countDown();
                    assertTrue(largada.await(5, TimeUnit.SECONDS));
                    try {
                        asignaciones.asignarOReasignar(f.actores.vendedor(),
                                new AsignarRepartoService.DatosAsignacion(f.pedido, rep2, "Turno B"));
                        return "UC43_OK";
                    } catch (Throwable t) {
                        if ("40P01".equals(sqlState(t))) deadlocks.incrementAndGet();
                        return t.getClass().getSimpleName() + ":" + t.getMessage();
                    }
                });

                assertTrue(listos.await(5, TimeUnit.SECONDS));
                largada.countDown();
                String r05 = fUc05.get(20, TimeUnit.SECONDS);
                String r28 = fUc28.get(20, TimeUnit.SECONDS);
                String r43 = fUc43.get(20, TimeUnit.SECONDS);

                assertEquals(0, deadlocks.get(), "0 deadlocks 40P01 permitidos: " + r05 + " | " + r28 + " | " + r43);
                // Si UC43 reasigno antes, UC05 no debe haber confirmado entrega para rep1
                String estado = jdbc.queryForObject("SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=?", String.class, f.pedido);
                if ("UC43_OK".equals(r43)) {
                    assertNotEquals("UC05_OK", r05, "Si UC-43 reasigno antes, UC-05 debe rechazar");
                    assertEquals("PENDIENTE", estado);
                } else if ("UC05_OK".equals(r05)) {
                    assertEquals("ENTREGADO", estado);
                }
            }
        }
    }

    @Test
    void uc05ContraUc29AltaYAnulacionConcurrenteMismoClienteSinDeadlockNiDobleCobro() throws Exception {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idCliente = f.actores.base;
        long idRep = f.actores.base + 3;
        UUID sesionAdmin = f.actores.sesiones.get("ADMINISTRADOR");

        // Prepara 4 pedidos para entregar en paralelo mientras se registran y anulan cobros en el mismo Cliente
        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), idRep, "REC-C29");
        List<Long> listaPedidos = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long p = pedidos.cargarPedido(f.actores.vendedor(), new CargarPedidoService.DatosPedido(
                    idCliente, f.actores.hoy.plusDays(1),
                    List.of(new CargarPedidoService.ItemPedido(f.producto, new BigDecimal("1.000"))),
                    f.actores.datos().direccionEntrega(), UUID.randomUUID())).idPedido();
            preparaciones.registrarPreparacionPedido(f.produccion(),
                    new RegistrarPreparacionService.DatosPreparacion(p, f.producto, new BigDecimal("1.000"), null));
            recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                    new AsignarRepartoService.DatosAsignacion(p, idRep, null), rec.idRecorrido());
            listaPedidos.add(p);
        }

        // Cobro previo libre + otro cobro para anular concurrentemente
        cobros.registrarCobro(f.actores.vendedor(), idCliente, null, new BigDecimal("50.00"), "EFECTIVO", UUID.randomUUID());
        var cobroAnulable = cobros.registrarCobro(f.actores.vendedor(), idCliente, null, new BigDecimal("30.00"), "TRANSFERENCIA", UUID.randomUUID());
        // Reserva del cobro 0? Dejamos ambos libres y ejecutamos anulacion + cobro nuevo + entregas en paralelo

        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger duplicados = new AtomicInteger();
        List<Throwable> inesperados = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch listos = new CountDownLatch(6);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            List<Future<?>> futuros = new ArrayList<>();
            for (Long p : listaPedidos) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    assertTrue(largada.await(5, TimeUnit.SECONDS));
                    try {
                        entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                                p, List.of(new ItemEntrega(f.producto, new BigDecimal("1.000"))),
                                new BigDecimal("40.00"), UUID.randomUUID()));
                    } catch (Throwable t) {
                        if ("40P01".equals(sqlState(t))) deadlocks.incrementAndGet();
                        else if ("23505".equals(sqlState(t))) duplicados.incrementAndGet();
                        else inesperados.add(t);
                    }
                    return null;
                }));
            }
            futuros.add(pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    cobros.registrarCobro(f.actores.vendedor(), idCliente, null,
                            new BigDecimal("25.00"), "EFECTIVO", UUID.randomUUID());
                } catch (Throwable t) {
                    if ("40P01".equals(sqlState(t))) deadlocks.incrementAndGet();
                    else if ("23505".equals(sqlState(t))) duplicados.incrementAndGet();
                    else inesperados.add(t);
                }
                return null;
            }));
            futuros.add(pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    cobros.anularCobroManual(sesionAdmin, cobroAnulable.idCobro(), "Anulacion concurrente con UC-05");
                } catch (IllegalArgumentException | IllegalStateException expectedIfConsumedByFifo) {
                    String msg = String.valueOf(expectedIfConsumedByFifo.getMessage());
                    if (!msg.contains("IMP-B") && !msg.contains("reserva") && !msg.contains("aplicacion")) {
                        inesperados.add(expectedIfConsumedByFifo);
                    }
                } catch (Throwable t) {
                    String msg = String.valueOf(t.getMessage());
                    if ("40P01".equals(sqlState(t))) deadlocks.incrementAndGet();
                    else if ("23505".equals(sqlState(t))) duplicados.incrementAndGet();
                    else if (!("P0001".equals(sqlState(t)) && msg.contains("DC05-07-A"))) inesperados.add(t);
                }
                return null;
            }));

            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            for (Future<?> fu : futuros) fu.get(30, TimeUnit.SECONDS);
        }

        assertEquals(0, deadlocks.get());
        assertEquals(0, duplicados.get());
        assertTrue(inesperados.isEmpty(), "Errores inesperados: " + inesperados);

        assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM \"PEDIDO\" WHERE \"idCliente\"=? AND \"estado\"='ENTREGADO'",
                Integer.class, idCliente));
        // Cero Cobros nuevos creados por UC-05 (2 iniciales + 1 de UC-29 = 3)
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\" WHERE \"idCliente\"=?", Integer.class, idCliente));

        // Secuencias contiguas de Cuenta Corriente sin huecos ni colisiones
        Integer totalMovs = jdbc.queryForObject(
                "SELECT count(*) FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=?", Integer.class, idCliente);
        Long maxSeq = jdbc.queryForObject(
                "SELECT max(\"numeroSecuenciaCliente\") FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=?", Long.class, idCliente);
        assertEquals(totalMovs.longValue(), maxSeq.longValue());
    }

    @Test
    void stockReventaCompartidoYReintentoConcurrenteIdempotenteSinPerdidaNiColision() throws Exception {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idCliente = f.actores.base;
        long idRep = f.actores.base + 3;
        long idReventa = f.actores.base + 1;

        var p1p2 = tx.execute(s -> {
            jdbc.update("""
                    UPDATE "PRODUCTO"
                       SET "tipoProducto"='REVENTA',"stockActualReventa"=3.000,"costoPromedioActualReventa"=50.0000
                     WHERE "idProducto"=?
                    """, idReventa);
            long p1 = pedidos.cargarPedido(f.actores.vendedor(), new CargarPedidoService.DatosPedido(
                    idCliente, f.actores.hoy.plusDays(1),
                    List.of(new CargarPedidoService.ItemPedido(idReventa, new BigDecimal("2.000"))),
                    f.actores.datos().direccionEntrega(), UUID.randomUUID())).idPedido();
            long p2 = pedidos.cargarPedido(f.actores.vendedor(), new CargarPedidoService.DatosPedido(
                    idCliente, f.actores.hoy.plusDays(1),
                    List.of(new CargarPedidoService.ItemPedido(idReventa, new BigDecimal("2.000"))),
                    f.actores.datos().direccionEntrega(), UUID.randomUUID())).idPedido();
            return new long[]{p1, p2};
        });
        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), idRep, "REC-REV-CONC");
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(p1p2[0], idRep, null), rec.idRecorrido());
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(p1p2[1], idRep, null), rec.idRecorrido());

        // Competencia por stock=3.000 entre p1 (2.000) y p2 (2.000): exactamente 1 confirma y 1 rechaza sin stock negativo
        CountDownLatch l1 = new CountDownLatch(2);
        CountDownLatch g1 = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> a = pool.submit(() -> {
                l1.countDown();
                assertTrue(g1.await(5, TimeUnit.SECONDS));
                try {
                    entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                            p1p2[0], List.of(new ItemEntrega(idReventa, new BigDecimal("2.000"))), null, UUID.randomUUID()));
                    return "OK";
                } catch (IllegalStateException ex) { return ex.getMessage(); }
            });
            Future<String> b = pool.submit(() -> {
                l1.countDown();
                assertTrue(g1.await(5, TimeUnit.SECONDS));
                try {
                    entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                            p1p2[1], List.of(new ItemEntrega(idReventa, new BigDecimal("2.000"))), null, UUID.randomUUID()));
                    return "OK";
                } catch (IllegalStateException ex) { return ex.getMessage(); }
            });
            assertTrue(l1.await(5, TimeUnit.SECONDS));
            g1.countDown();
            List<String> res = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertTrue(res.contains("OK") && res.contains("STOCK_REVENTA_INSUFICIENTE"), "Res=" + res);
        }
        assertEquals(0, new BigDecimal("1.000").compareTo(jdbc.queryForObject(
                "SELECT \"stockActualReventa\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?", BigDecimal.class, idReventa)));

        // Reintentos concurrentes con misma claveIdempotencia en un 3er pedido => exactamente 1 creado=true y 2 creado=false
        long p3 = pedidos.cargarPedido(f.actores.vendedor(), new CargarPedidoService.DatosPedido(
                idCliente, f.actores.hoy.plusDays(1),
                List.of(new CargarPedidoService.ItemPedido(idReventa, new BigDecimal("1.000"))),
                f.actores.datos().direccionEntrega(), UUID.randomUUID())).idPedido();
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(p3, idRep, null), rec.idRecorrido());
        UUID clave = UUID.randomUUID();
        DatosEntrega cmd = new DatosEntrega(
                p3, List.of(new ItemEntrega(idReventa, new BigDecimal("1.000"))), new BigDecimal("10.00"), clave);
        CountDownLatch l2 = new CountDownLatch(3);
        CountDownLatch g2 = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            List<Future<EntregaRegistrada>> fs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                fs.add(pool.submit(() -> {
                    l2.countDown();
                    assertTrue(g2.await(5, TimeUnit.SECONDS));
                    return entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), cmd);
                }));
            }
            assertTrue(l2.await(5, TimeUnit.SECONDS));
            g2.countDown();
            int creados = 0;
            for (var fut : fs) if (fut.get(20, TimeUnit.SECONDS).creado()) creados++;
            assertEquals(1, creados);
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
                "SELECT \"stockActualReventa\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?", BigDecimal.class, idReventa)));
    }
}
