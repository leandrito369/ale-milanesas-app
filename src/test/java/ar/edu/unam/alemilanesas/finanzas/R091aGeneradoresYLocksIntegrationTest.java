package ar.edu.unam.alemilanesas.finanzas;

import ar.edu.unam.alemilanesas.gfis.GfisLabSupport;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
 * R09.1a: pruebas de integracion para cutover de generadores globales V4
 * (seq_gfis_cobro_id, seq_gfis_mov_cuenta_id, seq_gfis_mov_caja_id), orden de locks
 * en anularCobroManual (lectura sin lock -> A2 -> CLIENTE U -> COBRO U), compatibilidad
 * con CobroManualService y RegistrarMovimientoCajaService, concurrencia, idempotencia y rollback.
 */
@SpringBootTest
@Testcontainers
class R091aGeneradoresYLocksIntegrationTest {

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
    @Autowired CobroManualService cobroService;
    @Autowired RegistrarMovimientoCajaService cajaService;
    @Autowired CargarPedidoService pedidoService;

    private record ActoresFinanzas(
            PedidoFixture fixture,
            long idAdmin,
            long idVendedor,
            long idRepartidor,
            long idCliente,
            UUID sesionAdmin,
            UUID sesionVendedor,
            UUID sesionRepartidor) {}

    private ActoresFinanzas crearActores() {
        PedidoFixture f = tx.execute(s -> new PedidoFixture(jdbc));
        assertNotNull(f);
        long idVendedor = f.base;
        long idAdmin = f.base + 1;
        long idRepartidor = f.base + 3;
        UUID sesionAdmin = f.sesiones.get("ADMINISTRADOR");
        UUID sesionRepartidor = f.sesiones.get("REPARTIDOR");
        return new ActoresFinanzas(
                f, idAdmin, idVendedor, idRepartidor, f.base,
                sesionAdmin, f.vendedor(), sesionRepartidor);
    }

    @Test
    void generadoresV4UsadosPorCobroAnulacionYCajaConHuecosDeRollbackYOrdinalConsecutivo() {
        ActoresFinanzas ctx = crearActores();

        // Asegurar rango distintivo en las tres secuencias V4 respecto a MAX+1 para probar consumo real de nextval.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SELECT pg_catalog.setval('ale_gestion.\"seq_gfis_cobro_id\"'::regclass, 500000, true)");
            jdbc.execute("SELECT pg_catalog.setval('ale_gestion.\"seq_gfis_mov_cuenta_id\"'::regclass, 600000, true)");
            jdbc.execute("SELECT pg_catalog.setval('ale_gestion.\"seq_gfis_mov_caja_id\"'::regclass, 700000, true)");
        });

        // 1) Alta de cobro usa las tres secuencias V4 y deja ordinal numeroSecuenciaCliente = 1.
        UUID claveAlta = UUID.randomUUID();
        var cobro1 = cobroService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null,
                new BigDecimal("120.00"), "EFECTIVO", claveAlta);
        assertTrue(cobro1.idCobro() > 500_000L);

        // Replay idempotente: mismo idCobro y sin consumir secuencias innecesariamente.
        var cobroReplay = cobroService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null,
                new BigDecimal("120.00"), "EFECTIVO", claveAlta);
        assertEquals(cobro1.idCobro(), cobroReplay.idCobro());
        assertThrows(IllegalArgumentException.class, () -> cobroService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null,
                new BigDecimal("125.00"), "EFECTIVO", claveAlta));

        // 2) Caja manual usa seq_gfis_mov_caja_id y no toca COBRO ni MOVIMIENTO_CUENTA.
        int cobrosAntesCaja = contar("COBRO");
        int cuentasAntesCaja = contar("MOVIMIENTO_CUENTA");
        var caja1 = cajaService.registrarMovimientoCaja(
                ctx.sesionAdmin(), "INGRESO", new BigDecimal("55.00"),
                "Caja R09.1a", "Obs", UUID.randomUUID());
        assertTrue(caja1.creado());
        assertTrue(caja1.idMovimientoCaja() > 700_000L);
        assertEquals(cobrosAntesCaja, contar("COBRO"));
        assertEquals(cuentasAntesCaja, contar("MOVIMIENTO_CUENTA"));

        // 3) Rollback tras fallo en UC-15 (sin politica vigente temporal) deja hueco tecnico pero 0 filas parciales.
        long cobrosAntesRollback = contar("COBRO");
        long cuentasAntesRollback = contar("MOVIMIENTO_CUENTA");
        long cajaAntesRollback = contar("MOVIMIENTO_CAJA");
        long origenAntesRollback = contar("ORIGEN_MOVIMIENTO_CUENTA");

        long idClienteSinPolitica = ctx.idCliente() + 90_000L;
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal",
                    "estado","categoriaComercial","fechaHoraAlta")
                VALUES (?,'Cliente Rollback R091a','3764009999','ACTIVO','CUMPLIDOR',now())
                """, idClienteSinPolitica));

        // Al insertar un cobro con reserva activa en IMP-B y luego intentar anularlo,
        // la funcion L02 falla tras consumir nextval de mov_cuenta y mov_caja y revierte todo.
        long pedReserva = pedidoService.cargarPedido(ctx.sesionVendedor(), ctx.fixture().datos()).idPedido();
        var cobroConReserva = cobroService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), pedReserva,
                new BigDecimal("40.00"), "EFECTIVO", UUID.randomUUID());
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idCliente","idCobro","idPedido","idCierreRecorrido","tipoEvento",
                    "importe","idUsuarioOperador","fechaHoraEvento","observacion")
                VALUES (?,?,?,NULL,'RESERVA_ANTICIPO',40.00,?,clock_timestamp(),'Reserva R09.1a')
                """, ctx.idCliente(), cobroConReserva.idCobro(), pedReserva, ctx.idVendedor()));

        long cobrosAntesFalloAnul = contar("COBRO");
        long cuentasAntesFalloAnul = contar("MOVIMIENTO_CUENTA");
        long cajaAntesFalloAnul = contar("MOVIMIENTO_CAJA");

        DataAccessException exReserva = assertThrows(DataAccessException.class,
                () -> cobroService.anularCobroManual(ctx.sesionAdmin(), cobroConReserva.idCobro(), "Debe rechazar"));
        assertTrue(exReserva.getMessage().contains("DC05-07-A"));
        assertEquals(cobrosAntesFalloAnul, contar("COBRO"));
        assertEquals(cuentasAntesFalloAnul, contar("MOVIMIENTO_CUENTA"));
        assertEquals(cajaAntesFalloAnul, contar("MOVIMIENTO_CAJA"));

        // 4) Anulacion valida posterior sobre cobro1 funciona pese al hueco tecnico en seq_gfis_* y mantiene ordinal consecutivo.
        var anulado = cobroService.anularCobroManual(ctx.sesionAdmin(), cobro1.idCobro(), "Anulacion valida R09.1a");
        assertEquals("ANULADO", anulado.estado());
        assertEquals(0, new BigDecimal("-40.00").compareTo(anulado.saldoConfirmado()));

        // El hueco en idMovimientoCuenta no rompe la consecutividad de numeroSecuenciaCliente (1, 2, 3).
        List<Long> seqCliente = jdbc.queryForList("""
                SELECT "numeroSecuenciaCliente"
                  FROM "MOVIMIENTO_CUENTA"
                 WHERE "idCliente" = ?
                 ORDER BY "numeroSecuenciaCliente" ASC
                """, Long.class, ctx.idCliente());
        assertEquals(List.of(1L, 2L, 3L), seqCliente);

        // El contraasiento en Caja uso un ID > caja1 y > 700_000 (con salto tecnico de la transaccion revertida).
        Long idCajaAnulacion = jdbc.queryForObject("""
                SELECT "idMovimientoCaja" FROM "MOVIMIENTO_CAJA" WHERE "idCobroAnulado" = ?
                """, Long.class, cobro1.idCobro());
        assertNotNull(idCajaAnulacion);
        assertTrue(idCajaAnulacion > caja1.idMovimientoCaja() + 1L);

        // Repartidor y Vendedor conservan restricciones RBAC sin efectos colaterales.
        assertThrows(SecurityException.class, () -> cobroService.registrarCobro(
                ctx.sesionRepartidor(), ctx.idCliente(), null,
                new BigDecimal("10.00"), "EFECTIVO", UUID.randomUUID()));
        assertThrows(SecurityException.class, () -> cobroService.anularCobroManual(
                ctx.sesionVendedor(), cobroConReserva.idCobro(), "Vendedor sin permiso"));
        assertThrows(SecurityException.class, () -> cajaService.registrarMovimientoCaja(
                ctx.sesionVendedor(), "INGRESO", new BigDecimal("10.00"),
                "Sin rol", null, UUID.randomUUID()));
        assertTrue(cobrosAntesRollback >= 1 && cuentasAntesRollback >= 1
                && cajaAntesRollback >= 1 && origenAntesRollback >= 1);
    }

    @Test
    void anularCobroManualTomaA2YClienteAntesDeCobroEnPgLocksYConviveSinDeadlockConT1K2() throws Exception {
        ActoresFinanzas ctx = crearActores();

        var cobro1 = cobroService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null,
                new BigDecimal("90.00"), "EFECTIVO", UUID.randomUUID());

        // 1) Observar directamente en pg_locks que anularCobroManual espera CLIENTE sin retener RowExclusive/Exclusive sobre COBRO.
        try (Connection barreraCliente = dataSource.getConnection();
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            barreraCliente.setAutoCommit(false);
            int pidBarrera = GfisLabSupport.backendPid(barreraCliente);
            try (PreparedStatement ps = barreraCliente.prepareStatement(
                    "SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE")) {
                ps.setLong(1, ctx.idCliente());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                }
            }

            Future<CobroManualService.Resultado> futAnul1 = pool.submit(() ->
                    cobroService.anularCobroManual(ctx.sesionAdmin(), cobro1.idCobro(), "Orden locks R09.1a"));

            // Esperar a que futAnul1 tome A2(3202, hashtext(idCliente)) y quede bloqueada esperando CLIENTE FOR UPDATE.
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(25)).until(() -> {
                Integer esperandoConA2 = jdbc.queryForObject("""
                        SELECT count(*)
                          FROM pg_stat_activity a
                          JOIN pg_locks la
                            ON la.pid = a.pid
                           AND la.locktype = 'advisory'
                           AND la.classid::int = 3202
                           AND la.objid::int = pg_catalog.hashtext(?::text)
                           AND la.granted = true
                         WHERE ? = ANY(pg_blocking_pids(a.pid))
                        """, Integer.class, Long.toString(ctx.idCliente()), pidBarrera);
                return esperandoConA2 != null && esperandoConA2 == 1;
            });

            // Mientras anularCobroManual espera CLIENTE FOR UPDATE, NO debe haber tomado ningun RowShare/RowExclusive/ShareLock sobre COBRO.
            Integer locksSobreCobro = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM pg_locks l
                      JOIN pg_class c ON c.oid = l.relation
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                      JOIN pg_stat_activity a ON a.pid = l.pid
                     WHERE n.nspname = 'ale_gestion'
                       AND c.relname = 'COBRO'
                       AND l.granted = true
                       AND l.mode IN ('RowShareLock', 'RowExclusiveLock', 'ShareUpdateExclusiveLock', 'ExclusiveLock', 'AccessExclusiveLock')
                       AND ? = ANY(pg_blocking_pids(a.pid))
                    """, Integer.class, pidBarrera);
            assertEquals(0, locksSobreCobro,
                    "anularCobroManual no debe bloquear COBRO antes de obtener CLIENTE FOR UPDATE");

            // Segundo intento concurrente sobre el mismo idCobro: al liberar barrera, solo 1 anula y el otro rechaza limpio.
            Future<String> futAnul2 = pool.submit(() -> {
                try {
                    cobroService.anularCobroManual(ctx.sesionAdmin(), cobro1.idCobro(), "Segundo intento concurrente");
                    return "OK";
                } catch (IllegalArgumentException ex) {
                    return "RECHAZADO";
                }
            });

            barreraCliente.commit();

            var res1 = futAnul1.get(20, TimeUnit.SECONDS);
            var res2 = futAnul2.get(20, TimeUnit.SECONDS);
            assertEquals("ANULADO", res1.estado());
            assertEquals("RECHAZADO", res2);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"MOVIMIENTO_CAJA\" WHERE \"idCobroAnulado\"=?",
                    Integer.class, cobro1.idCobro()));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM \"ORIGEN_MOVIMIENTO_CUENTA\" WHERE \"idCobroAnulado\"=?",
                    Integer.class, cobro1.idCobro()));
        }

        // 2) Intercalado concurrente real: anularCobroManual (via CobroManualService) vs cierre T1/K2 (sin puente 2929001 y con puente)
        //    sobre el MISMO Cliente, verificando 0 deadlocks 40P01 y 0 colisiones 23505.
        for (boolean usarPuente : List.of(false, true)) {
            var cobroManual = cobroService.registrarCobro(
                    ctx.sesionVendedor(), ctx.idCliente(), null,
                    new BigDecimal("30.00"), "EFECTIVO", UUID.randomUUID());
            long ped = pedidoService.cargarPedido(ctx.sesionVendedor(), ctx.fixture().datos()).idPedido();
            long rec = GfisLabSupport.crearRecorridoExplicito(jdbc, ctx.idRepartidor(), "REC-R091A-" + usarPuente);
            GfisLabSupport.asignarYVincularPedidoEnLab(jdbc, ped, ctx.idRepartidor(), ctx.idVendedor(), rec);
            GfisLabSupport.registrarEntregaPe1EnLaboratorio(jdbc, ped, ctx.idCliente(), ctx.idRepartidor(), rec,
                    new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    new BigDecimal("100.00"), "IMP_B");

            AtomicInteger deadlocks = new AtomicInteger();
            AtomicInteger duplicados = new AtomicInteger();
            List<Exception> errores = new ArrayList<>();
            CountDownLatch listos = new CountDownLatch(2);
            CountDownLatch largada = new CountDownLatch(1);

            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<?> fAnul = pool.submit(() -> {
                    listos.countDown();
                    try {
                        assertTrue(largada.await(5, TimeUnit.SECONDS));
                        cobroService.anularCobroManual(ctx.sesionAdmin(), cobroManual.idCobro(),
                                "Intercalado R09.1a puente=" + usarPuente);
                    } catch (Exception ex) {
                        clasificar(ex, deadlocks, duplicados, errores);
                    }
                });
                Future<?> fT1 = pool.submit(() -> {
                    listos.countDown();
                    try {
                        assertTrue(largada.await(5, TimeUnit.SECONDS));
                        tx.executeWithoutResult(s -> GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                                jdbc, rec, ctx.idAdmin(), UUID.randomUUID(), "v1", "h-r091a-" + usarPuente,
                                new BigDecimal("100.00"), new BigDecimal("100.00"),
                                List.of(new GfisLabSupport.AplicacionPlan(ctx.idCliente(), ped, new BigDecimal("100.00"))),
                                usarPuente, true));
                    } catch (Exception ex) {
                        clasificar(ex, deadlocks, duplicados, errores);
                    }
                });

                assertTrue(listos.await(5, TimeUnit.SECONDS));
                largada.countDown();
                fAnul.get(20, TimeUnit.SECONDS);
                fT1.get(20, TimeUnit.SECONDS);

                assertEquals(0, deadlocks.get());
                assertEquals(0, duplicados.get());
                assertTrue(errores.isEmpty(), "Errores inesperados en intercalado: " + errores);
            }
        }
    }

    @Test
    void concurrenciaMultiClienteCobrosAnulacionesYCajaSinDeadlocksNiColisionesYClienteBigint64Bit() throws Exception {
        ActoresFinanzas ctx = crearActores();
        long clienteBig = 3_000_000_123L; // > Integer.MAX_VALUE
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal",
                    "estado","categoriaComercial","fechaHoraAlta")
                VALUES (?,'Cliente 64bit R091a','3764008888','ACTIVO','CUMPLIDOR',now())
                """, clienteBig));

        // Verificar flujo completo BIGINT > Integer.MAX_VALUE en servicio productivo sin SQLSTATE 22003.
        var cobroBig = cobroService.registrarCobro(
                ctx.sesionVendedor(), clienteBig, null,
                new BigDecimal("85.00"), "TRANSFERENCIA", UUID.randomUUID());
        var cobroBigAnulado = cobroService.anularCobroManual(
                ctx.sesionAdmin(), cobroBig.idCobro(), "Anulacion BIGINT > 2^31-1");
        assertEquals("ANULADO", cobroBigAnulado.estado());
        assertEquals(0, BigDecimal.ZERO.compareTo(cobroBigAnulado.saldoConfirmado()));

        // Pre-crear 3 cobros para anular en paralelo junto a altas y movimientos de caja.
        List<Long> cobrosParaAnular = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            cobrosParaAnular.add(cobroService.registrarCobro(
                    ctx.sesionVendedor(), ctx.idCliente(), null,
                    new BigDecimal("10.00"), "EFECTIVO", UUID.randomUUID()).idCobro());
        }

        int totalTareas = 9;
        CountDownLatch listos = new CountDownLatch(totalTareas);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger duplicados = new AtomicInteger();
        List<Exception> errores = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(totalTareas)) {
            List<Future<?>> futuros = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                long cli = (i % 2 == 0) ? ctx.idCliente() : clienteBig;
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    try {
                        assertTrue(largada.await(5, TimeUnit.SECONDS));
                        cobroService.registrarCobro(ctx.sesionVendedor(), cli, null,
                                new BigDecimal("20.00"), "EFECTIVO", UUID.randomUUID());
                    } catch (Exception ex) {
                        clasificar(ex, deadlocks, duplicados, errores);
                    }
                }));
            }
            for (Long idCobroAnular : cobrosParaAnular) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    try {
                        assertTrue(largada.await(5, TimeUnit.SECONDS));
                        cobroService.anularCobroManual(ctx.sesionAdmin(), idCobroAnular, "Concurrente R09.1a");
                    } catch (Exception ex) {
                        clasificar(ex, deadlocks, duplicados, errores);
                    }
                }));
            }
            for (int i = 0; i < 3; i++) {
                int idx = i;
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    try {
                        assertTrue(largada.await(5, TimeUnit.SECONDS));
                        cajaService.registrarMovimientoCaja(ctx.sesionAdmin(),
                                idx % 2 == 0 ? "INGRESO" : "EGRESO",
                                new BigDecimal("15.00"), "Concurrente Caja " + idx, null, UUID.randomUUID());
                    } catch (Exception ex) {
                        clasificar(ex, deadlocks, duplicados, errores);
                    }
                }));
            }

            assertTrue(listos.await(5, TimeUnit.SECONDS));
            largada.countDown();
            for (Future<?> futuro : futuros) {
                futuro.get(20, TimeUnit.SECONDS);
            }
        }

        assertEquals(0, deadlocks.get());
        assertEquals(0, duplicados.get());
        assertTrue(errores.isEmpty(), "Errores en concurrencia multi-cliente: " + errores);

        // Verificar unicidad de todos los IDs generados y saldos finales exactos.
        Set<Long> cobros = new HashSet<>(jdbc.queryForList("SELECT \"idCobro\" FROM \"COBRO\"", Long.class));
        assertEquals(contar("COBRO"), cobros.size());
        Set<Long> cuentas = new HashSet<>(jdbc.queryForList("SELECT \"idMovimientoCuenta\" FROM \"MOVIMIENTO_CUENTA\"", Long.class));
        assertEquals(contar("MOVIMIENTO_CUENTA"), cuentas.size());
        Set<Long> cajas = new HashSet<>(jdbc.queryForList("SELECT \"idMovimientoCaja\" FROM \"MOVIMIENTO_CAJA\"", Long.class));
        assertEquals(contar("MOVIMIENTO_CAJA"), cajas.size());

        BigDecimal saldoClienteBase = jdbc.queryForObject("""
                SELECT "saldoResultante" FROM "MOVIMIENTO_CUENTA"
                 WHERE "idCliente"=? ORDER BY "numeroSecuenciaCliente" DESC LIMIT 1
                """, BigDecimal.class, ctx.idCliente());
        assertEquals(0, new BigDecimal("-40.00").compareTo(saldoClienteBase));

        BigDecimal saldoClienteBig = jdbc.queryForObject("""
                SELECT "saldoResultante" FROM "MOVIMIENTO_CUENTA"
                 WHERE "idCliente"=? ORDER BY "numeroSecuenciaCliente" DESC LIMIT 1
                """, BigDecimal.class, clienteBig);
        assertEquals(0, new BigDecimal("-20.00").compareTo(saldoClienteBig));
    }

    @Test
    void resincronizacionLaboratorioDeTresSecuenciasBajoA1YLockTableSharePermiteCutoverLimpio() {
        ActoresFinanzas ctx = crearActores();

        // Simular datos preexistentes mas altos insertados antes del cutover en las 3 tablas financieras.
        long maxCobroSimulado = 9_100_001L;
        long maxCuentaSimulado = 9_200_001L;
        long maxCajaSimulado = 9_300_001L;

        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO "COBRO" ("idCobro","idCliente","idUsuarioRegistrador",
                        "fechaHoraCobro","importe","medioPago","origen","claveIdempotenciaOrigen","estadoCobro")
                    VALUES (?,?,?,clock_timestamp(),50.00,'EFECTIVO','ADMINISTRACION',?,'CONFIRMADO')
                    """, maxCobroSimulado, ctx.idCliente(), ctx.idAdmin(), UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO "MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCliente",
                        "numeroSecuenciaCliente","tipoMovimiento","importe","fechaHoraMovimiento","saldoResultante")
                    VALUES (?,?,1,'PAGO',50.00,clock_timestamp(),-50.00)
                    """, maxCuentaSimulado, ctx.idCliente());
            jdbc.update("""
                    INSERT INTO "ORIGEN_MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCobro")
                    VALUES (?,?)
                    """, maxCuentaSimulado, maxCobroSimulado);
            jdbc.update("""
                    INSERT INTO "MOVIMIENTO_CAJA" ("idMovimientoCaja","tipoMovimiento","importe",
                        "fechaHoraMovimiento","origen","concepto","idUsuarioRegistro","idCobro")
                    VALUES (?,'INGRESO',50.00,clock_timestamp(),'COBRO','Pre-cutover simulado',?,?)
                    """, maxCajaSimulado, ctx.idAdmin(), maxCobroSimulado);

            // Re-seed protegido equivalente a ADAPT-06 exclusivamente en PostgreSQL descartable.
            jdbc.execute("SELECT pg_catalog.pg_advisory_xact_lock(2929001)");
            jdbc.execute("LOCK TABLE ale_gestion.\"COBRO\", ale_gestion.\"MOVIMIENTO_CUENTA\", ale_gestion.\"MOVIMIENTO_CAJA\" IN SHARE MODE");
            jdbc.execute("""
                    SELECT pg_catalog.setval(
                        'ale_gestion."seq_gfis_cobro_id"'::regclass,
                        GREATEST((SELECT COALESCE(MAX("idCobro"), 0) FROM ale_gestion."COBRO"), 1),
                        true)
                    """);
            jdbc.execute("""
                    SELECT pg_catalog.setval(
                        'ale_gestion."seq_gfis_mov_cuenta_id"'::regclass,
                        GREATEST((SELECT COALESCE(MAX("idMovimientoCuenta"), 0) FROM ale_gestion."MOVIMIENTO_CUENTA"), 1),
                        true)
                    """);
            jdbc.execute("""
                    SELECT pg_catalog.setval(
                        'ale_gestion."seq_gfis_mov_caja_id"'::regclass,
                        GREATEST((SELECT COALESCE(MAX("idMovimientoCaja"), 0) FROM ale_gestion."MOVIMIENTO_CAJA"), 1),
                        true)
                    """);
        });

        var cobroPostCutover = cobroService.registrarCobro(
                ctx.sesionVendedor(), ctx.idCliente(), null,
                new BigDecimal("25.00"), "EFECTIVO", UUID.randomUUID());
        assertEquals(maxCobroSimulado + 1L, cobroPostCutover.idCobro());

        var cajaPostCutover = cajaService.registrarMovimientoCaja(
                ctx.sesionAdmin(), "EGRESO", new BigDecimal("12.00"),
                "Post reseed R09.1a", null, UUID.randomUUID());
        assertTrue(cajaPostCutover.idMovimientoCaja() > maxCajaSimulado + 1L);

        var anuladoPostCutover = cobroService.anularCobroManual(
                ctx.sesionAdmin(), maxCobroSimulado, "Anulacion cobro pre-cutover");
        assertEquals("ANULADO", anuladoPostCutover.estado());
        assertEquals(0, new BigDecimal("-25.00").compareTo(anuladoPostCutover.saldoConfirmado()));
    }

    private int contar(String tabla) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM \"" + tabla + "\"", Integer.class);
        return n == null ? 0 : n;
    }

    private static void clasificar(
            Exception ex, AtomicInteger deadlocks, AtomicInteger duplicados, List<Exception> errores) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx && sqlEx.getSQLState() != null) {
                if ("40P01".equals(sqlEx.getSQLState())) {
                    deadlocks.incrementAndGet();
                } else if ("23505".equals(sqlEx.getSQLState())) {
                    duplicados.incrementAndGet();
                }
            }
            cur = cur.getCause();
        }
        errores.add(ex);
    }
}
