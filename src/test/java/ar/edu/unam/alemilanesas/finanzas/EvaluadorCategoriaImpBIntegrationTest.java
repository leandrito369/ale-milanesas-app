package ar.edu.unam.alemilanesas.finanzas;

import ar.edu.unam.alemilanesas.produccioninventario.PreparacionFixture;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService;
import ar.edu.unam.alemilanesas.ventas.AsignarRepartoService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.GestionarRecorridoRepartoService;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.DatosEntrega;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.ItemEntrega;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/** H2 (UC-15 + IMP-B): segregacion de reservas activas, aplicaciones explicitas, liberacion, reembolsos, rollback y concurrencia. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class EvaluadorCategoriaImpBIntegrationTest {
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
    @Autowired GestionarRecorridoRepartoService recorridos;
    @Autowired RegistrarEntregaService entregas;
    @Autowired CobroManualService cobros;
    @Autowired ImputacionFinancieraService imputacion;
    @Autowired EvaluadorCategoriaComercialInterno evaluador;

    private String categoria(long idCliente) {
        return jdbc.queryForObject(
                "SELECT \"categoriaComercial\" FROM \"CLIENTE\" WHERE \"idCliente\"=?",
                String.class, idCliente);
    }

    private void insertarDeudaVencida(long idCliente, long idMov, BigDecimal importe, int diasAtras) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "MOVIMIENTO_CUENTA" (
                    "idMovimientoCuenta","idCliente","numeroSecuenciaCliente","tipoMovimiento",
                    "importe","fechaHoraMovimiento","saldoResultante")
                VALUES (?,?,1,'AJUSTE_DEBITO',?,clock_timestamp() - make_interval(days => ?),?)
                """, idMov, idCliente, importe, diasAtras, importe));
    }

    private static String sqlState(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx && sqlEx.getSQLState() != null) return sqlEx.getSQLState();
            cur = cur.getCause();
        }
        return "";
    }

    @Test
    void escenariosAB_deudaVencidaAYReservaOPagoConsolidadoParaPedidoBMantienenEnMora() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idCliente = f.actores.base;
        long idRep = f.actores.base + 3;
        UUID vendedor = f.actores.vendedor();

        // Deuda A vieja vencida = 100.00 (> 5 dias de tolerancia)
        insertarDeudaVencida(idCliente, f.actores.base + 500, new BigDecimal("100.00"), 10);
        assertEquals("EN_MORA", tx.execute(s -> evaluador.reevaluar(idCliente)));

        // Pedido B por 100.00 (precio 100 * 1 = 100, con EN_MORA +20% = 120; usamos 1.000 para B)
        long pedB = pedidos.cargarPedido(vendedor, new CargarPedidoService.DatosPedido(
                idCliente, f.actores.hoy.plusDays(1),
                List.of(new CargarPedidoService.ItemPedido(f.producto, new BigDecimal("1.000"))),
                f.actores.datos().direccionEntrega(), UUID.randomUUID())).idPedido();
        preparaciones.registrarPreparacionPedido(f.produccion(),
                new RegistrarPreparacionService.DatosPreparacion(pedB, f.producto, new BigDecimal("1.000"), null));

        // Cobro libre de 100.00 vuelve CUMPLIDOR; al reservarlo explicitamente para B debe volver a EN_MORA (a)
        var cobroB = cobros.registrarCobro(vendedor, idCliente, null,
                new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());
        assertEquals("CUMPLIDOR", categoria(idCliente));

        imputacion.registrarReservaAnticipo(vendedor, cobroB.idCobro(), pedB,
                new BigDecimal("100.00"), "Reserva para B");
        assertEquals("EN_MORA", categoria(idCliente));
        assertEquals("EN_MORA", tx.execute(s -> evaluador.reevaluar(idCliente)));

        // Entregar B consolida los 100.00 en B; A vencida sigue impaga => continua EN_MORA (b)
        var rec = recorridos.abrirRecorrido(vendedor, idRep, "REC-H2-AB");
        recorridos.asignarYVincularRecorrido(vendedor,
                new AsignarRepartoService.DatosAsignacion(pedB, idRep, null), rec.idRecorrido());
        var resB = entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                pedB, List.of(new ItemEntrega(f.producto, new BigDecimal("1.000"))),
                new BigDecimal("20.00"), UUID.randomUUID()));

        assertEquals(0, new BigDecimal("100.00").compareTo(resB.anticipoExplicitoPe()));
        assertEquals("EN_MORA", categoria(idCliente));
        assertEquals("EN_MORA", tx.execute(s -> evaluador.reevaluar(idCliente)));
    }

    @Test
    void escenariosCD_liberacionAutorizadaYCreditoLibreExtinguenMoraConservandoReserva() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idCliente = f.actores.base;
        UUID vendedor = f.actores.vendedor();

        insertarDeudaVencida(idCliente, f.actores.base + 510, new BigDecimal("100.00"), 10);
        long pedB = pedidos.cargarPedido(vendedor, new CargarPedidoService.DatosPedido(
                idCliente, f.actores.hoy.plusDays(1),
                List.of(new CargarPedidoService.ItemPedido(f.producto, new BigDecimal("1.000"))),
                f.actores.datos().direccionEntrega(), UUID.randomUUID())).idPedido();

        var cobroReservado = cobros.registrarCobro(vendedor, idCliente, null,
                new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());
        imputacion.registrarReservaAnticipo(vendedor, cobroReservado.idCobro(), pedB,
                new BigDecimal("100.00"), "Reserva B completa");
        assertEquals("EN_MORA", tx.execute(s -> evaluador.reevaluar(idCliente)));

        // Liberacion parcial (50 de 100): solo 50 libre => A=100 sigue EN_MORA
        imputacion.registrarLiberacionAnticipo(vendedor, cobroReservado.idCobro(), pedB,
                new BigDecimal("50.00"), "Liberacion parcial");
        assertEquals("EN_MORA", categoria(idCliente));

        // Liberacion restante (50): vuelve 100 libre elegible a FIFO => extingue A => CUMPLIDOR (c)
        imputacion.registrarLiberacionAnticipo(vendedor, cobroReservado.idCobro(), pedB,
                new BigDecimal("50.00"), "Liberacion total");
        assertEquals("CUMPLIDOR", categoria(idCliente));

        // Volver a reservar 100 para B => EN_MORA, y registrar cobro libre adicional de 100 => CUMPLIDOR (d)
        imputacion.registrarReservaAnticipo(vendedor, cobroReservado.idCobro(), pedB,
                new BigDecimal("100.00"), "Re-reserva B");
        assertEquals("EN_MORA", categoria(idCliente));

        cobros.registrarCobro(vendedor, idCliente, null,
                new BigDecimal("100.00"), "TRANSFERENCIA", UUID.randomUUID());
        assertEquals("CUMPLIDOR", categoria(idCliente));

        // Reserva en B intacta en VW_IMP_A
        BigDecimal reservadoB = jdbc.queryForObject("""
                SELECT "importeReservadoActivo" FROM "VW_IMP_A_DISPONIBILIDAD_COBRO" WHERE "idCobro"=?
                """, BigDecimal.class, cobroReservado.idCobro());
        assertEquals(0, new BigDecimal("100.00").compareTo(reservadoB));
    }

    @Test
    void escenarioE_anulacionCobroYReembolsoActivoNoGeneranCreditoFicticio() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idCliente = f.actores.base;
        UUID vendedor = f.actores.vendedor();
        UUID admin = f.actores.sesiones.get("ADMINISTRADOR");

        insertarDeudaVencida(idCliente, f.actores.base + 520, new BigDecimal("100.00"), 10);
        var c1 = cobros.registrarCobro(vendedor, idCliente, null,
                new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());
        assertEquals("CUMPLIDOR", categoria(idCliente));

        // Anulacion restaura deuda A vencida => EN_MORA
        cobros.anularCobroManual(admin, c1.idCobro(), "Anulacion verificada");
        assertEquals("EN_MORA", categoria(idCliente));

        // Nuevo cobro de 100 con REEMBOLSO PENDIENTE por 100 => NO extingue A => EN_MORA
        var c2 = cobros.registrarCobro(vendedor, idCliente, null,
                new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());
        assertEquals("CUMPLIDOR", categoria(idCliente));
        long idReemb = f.actores.base + 525;
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "REEMBOLSO" ("idReembolso","idCobro","importeReembolso","fechaHoraCreacion","motivo","estado")
                VALUES (?,?,100.00,clock_timestamp(),'Reembolso pendiente E','PENDIENTE')
                """, idReemb, c2.idCobro()));
        assertEquals("EN_MORA", tx.execute(s -> evaluador.reevaluar(idCliente)));

        // Reembolso PROCESADO (1 EGRESO caja + 1 AJUSTE_DEBITO idReembolso): tampoco lava mora previa A
        tx.executeWithoutResult(s -> {
            long idCaja = jdbc.queryForObject("SELECT nextval('ale_gestion.\"seq_gfis_mov_caja_id\"')", Long.class);
            long idMov = jdbc.queryForObject("SELECT nextval('ale_gestion.\"seq_gfis_mov_cuenta_id\"')", Long.class);
            long seq = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(\"numeroSecuenciaCliente\"),0)+1 FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=?",
                    Long.class, idCliente);
            BigDecimal prevSaldo = jdbc.queryForObject(
                    "SELECT \"saldoResultante\" FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=? ORDER BY \"numeroSecuenciaCliente\" DESC LIMIT 1",
                    BigDecimal.class, idCliente);
            jdbc.update("""
                    INSERT INTO "MOVIMIENTO_CAJA" (
                        "idMovimientoCaja","tipoMovimiento","importe","fechaHoraMovimiento","origen",
                        "concepto","idReembolso")
                    VALUES (?,'EGRESO',100.00,clock_timestamp(),'REEMBOLSO','Devolucion procesada',?)
                    """, idCaja, idReemb);
            jdbc.update("""
                    INSERT INTO "MOVIMIENTO_CUENTA" (
                        "idMovimientoCuenta","idCliente","numeroSecuenciaCliente","tipoMovimiento",
                        "importe","fechaHoraMovimiento","saldoResultante")
                    VALUES (?,?,?,'AJUSTE_DEBITO',100.00,clock_timestamp(),?)
                    """, idMov, idCliente, seq, prevSaldo.add(new BigDecimal("100.00")));
            jdbc.update("""
                    INSERT INTO "ORIGEN_MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idReembolso") VALUES (?,?)
                    """, idMov, idReemb);
            jdbc.update("""
                    UPDATE "REEMBOLSO" SET "estado"='PROCESADO',"fechaHoraProcesamiento"=clock_timestamp()
                     WHERE "idReembolso"=?
                    """, idReemb);
        });
        assertEquals("EN_MORA", tx.execute(s -> evaluador.reevaluar(idCliente)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void escenarioF_rollbackYConcurrenciaPreservanClasificacionYSinDuplicados() throws Exception {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idCliente = f.actores.base;
        long idRep = f.actores.base + 3;
        UUID vendedor = f.actores.vendedor();

        insertarDeudaVencida(idCliente, f.actores.base + 530, new BigDecimal("100.00"), 10);
        long pedB = pedidos.cargarPedido(vendedor, new CargarPedidoService.DatosPedido(
                idCliente, f.actores.hoy.plusDays(1),
                List.of(new CargarPedidoService.ItemPedido(f.producto, new BigDecimal("1.000"))),
                f.actores.datos().direccionEntrega(), UUID.randomUUID())).idPedido();
        preparaciones.registrarPreparacionPedido(f.produccion(),
                new RegistrarPreparacionService.DatosPreparacion(pedB, f.producto, new BigDecimal("1.000"), null));

        var cobroB = cobros.registrarCobro(vendedor, idCliente, null,
                new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());
        assertEquals("CUMPLIDOR", categoria(idCliente));

        // Rollback ante falla en EVENTO_IMPUTACION_FINANCIERA conserva CUMPLIDOR e historial intacto
        jdbc.execute("""
                CREATE FUNCTION fallo_impb_uc15_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'fallo controlado reserva'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fallo_impb_uc15_test BEFORE INSERT ON "EVENTO_IMPUTACION_FINANCIERA"
                FOR EACH ROW EXECUTE FUNCTION fallo_impb_uc15_test()
                """);
        try {
            assertThrows(DataAccessException.class, () -> imputacion.registrarReservaAnticipo(
                    vendedor, cobroB.idCobro(), pedB, new BigDecimal("100.00"), "Fallo"));
            assertEquals("CUMPLIDOR", categoria(idCliente));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM \"EVENTO_IMPUTACION_FINANCIERA\" WHERE \"idCliente\"=?",
                    Integer.class, idCliente));
        } finally {
            jdbc.execute("DROP TRIGGER fallo_impb_uc15_test ON \"EVENTO_IMPUTACION_FINANCIERA\"");
            jdbc.execute("DROP FUNCTION fallo_impb_uc15_test()");
        }

        // Reservar B y lanzar concurrencia: entrega B + cobro libre (distinto cobro) + reevaluacion
        imputacion.registrarReservaAnticipo(vendedor, cobroB.idCobro(), pedB,
                new BigDecimal("100.00"), "Reserva pre concurrencia");
        assertEquals("EN_MORA", categoria(idCliente));

        var rec = recorridos.abrirRecorrido(vendedor, idRep, "REC-H2-CONC");
        recorridos.asignarYVincularRecorrido(vendedor,
                new AsignarRepartoService.DatosAsignacion(pedB, idRep, null), rec.idRecorrido());

        UUID claveEntrega = UUID.randomUUID();
        AtomicInteger deadlocks = new AtomicInteger();
        List<Throwable> errores = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch listos = new CountDownLatch(3);
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            Future<?> fEntrega1 = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                            pedB, List.of(new ItemEntrega(f.producto, new BigDecimal("1.000"))),
                            null, claveEntrega));
                } catch (Throwable t) {
                    if ("40P01".equals(sqlState(t))) deadlocks.incrementAndGet();
                    else errores.add(t);
                }
                return null;
            });
            Future<?> fEntregaReplay = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                            pedB, List.of(new ItemEntrega(f.producto, new BigDecimal("1.000"))),
                            null, claveEntrega));
                } catch (Throwable t) {
                    if ("40P01".equals(sqlState(t))) deadlocks.incrementAndGet();
                    else errores.add(t);
                }
                return null;
            });
            Future<?> fReeval = pool.submit(() -> {
                listos.countDown();
                assertTrue(largada.await(5, TimeUnit.SECONDS));
                try {
                    tx.execute(s -> evaluador.reevaluar(idCliente));
                } catch (Throwable t) {
                    if ("40P01".equals(sqlState(t))) deadlocks.incrementAndGet();
                    else errores.add(t);
                }
                return null;
            });
            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            fEntrega1.get(20, TimeUnit.SECONDS);
            fEntregaReplay.get(20, TimeUnit.SECONDS);
            fReeval.get(20, TimeUnit.SECONDS);
        }
        assertEquals(0, deadlocks.get());
        assertTrue(errores.isEmpty(), "Errores=" + errores);
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM \"EVENTO_IMPUTACION_FINANCIERA\" WHERE \"idPedido\"=? AND \"tipoEvento\"='CONSOLIDACION_ANTICIPO'",
                Integer.class, pedB));
        assertEquals("EN_MORA", categoria(idCliente));
    }
}
