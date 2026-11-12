package ar.edu.unam.alemilanesas.maestros;

import ar.edu.unam.alemilanesas.maestros.ActualizarPrecioVigenteService.PrecioActualizado;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService.DatosPedido;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService.DireccionEntrega;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService.ItemPedido;
import ar.edu.unam.alemilanesas.ventas.CargarPedidoService.PedidoCreado;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regresión obligatoria INC-UC19:
 * Verifica la eliminación del deadlock 40P01 entre UC-03 (CargarPedidoService)
 * y UC-19 (ActualizarPrecioVigenteService) para idCliente=1919001 mediante el
 * uso del namespace de locks advisory separado (1919,1).
 *
 * Se verifica además la persistencia coherente de ambas operaciones y el
 * replay idempotente de UC-03 tras la corrección de PR #85.
 */
@SpringBootTest
@Testcontainers
class PrecioProductoUc03DeadlockIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ActualizarPrecioVigenteService servicioPrecio;
    @Autowired CargarPedidoService servicioPedido;

    static final long ID_CLIENTE_PROBLEMATICO = 1919001L;
    static final long ID_PRODUCTO = 1919002L;
    static final long ID_PRECIO_INICIAL = 1919003L;
    static final long ID_USUARIO_ADMIN = 1919010L;
    static final long ID_USUARIO_VENDEDOR = 1919011L;
    static final long ID_POLITICA = 1919020L;

    static final UUID SESION_ADMIN = UUID.fromString("19190000-0000-4000-8000-000000000001");
    static final UUID SESION_VENDEDOR = UUID.fromString("19190000-0000-4000-8000-000000000002");

    LocalDate hoy;

    @BeforeEach
    void setupFixture() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            hoy = jdbc.queryForObject("SELECT (clock_timestamp() AT TIME ZONE 'America/Argentina/Buenos_Aires')::date", LocalDate.class);

            jdbc.update("INSERT INTO \"UNIDAD_MEDIDA\" (\"codigoUnidad\",\"nombre\") VALUES ('UN','Unidad') ON CONFLICT DO NOTHING");
            jdbc.update("INSERT INTO \"ROL\" (\"codigoRol\",\"nombreMostrado\") VALUES ('ADMINISTRADOR','Admin Deadlock Test') ON CONFLICT DO NOTHING");
            jdbc.update("INSERT INTO \"ROL\" (\"codigoRol\",\"nombreMostrado\") VALUES ('VENDEDOR','Vendedor Deadlock Test') ON CONFLICT DO NOTHING");

            // Usuarios y Roles
            jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (?, 'admin_deadlock_test', 'HASH_TEST', 'HABILITADO', false, now())
                ON CONFLICT ("idUsuario") DO NOTHING
                """, ID_USUARIO_ADMIN);
            jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (?, 'ADMINISTRADOR', now())
                ON CONFLICT DO NOTHING
                """, ID_USUARIO_ADMIN);
            jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?, ?, decode('191901','hex'), now() - interval '1 minute', now() + interval '1 hour')
                ON CONFLICT ("idSesionUsuario") DO NOTHING
                """, SESION_ADMIN, ID_USUARIO_ADMIN);

            jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (?, 'vendedor_deadlock_test', 'HASH_TEST', 'HABILITADO', false, now())
                ON CONFLICT ("idUsuario") DO NOTHING
                """, ID_USUARIO_VENDEDOR);
            jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (?, 'VENDEDOR', now())
                ON CONFLICT DO NOTHING
                """, ID_USUARIO_VENDEDOR);
            jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?, ?, decode('191902','hex'), now() - interval '1 minute', now() + interval '1 hour')
                ON CONFLICT ("idSesionUsuario") DO NOTHING
                """, SESION_VENDEDOR, ID_USUARIO_VENDEDOR);

            // Cliente problemático con idCliente = 1919001
            jdbc.update("""
                INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal","estado","categoriaComercial","fechaHoraAlta")
                VALUES (?, 'Cliente Colision Advisory 1919001', '3764191901', 'ACTIVO', 'CUMPLIDOR', now())
                ON CONFLICT ("idCliente") DO UPDATE SET "estado" = 'ACTIVO'
                """, ID_CLIENTE_PROBLEMATICO);

            // Producto
            jdbc.update("""
                INSERT INTO "PRODUCTO" ("idProducto","nombre","nombreNormalizado","tipoProducto","codigoUnidadVenta","activo")
                VALUES (?, 'Producto Deadlock Test', 'producto deadlock test', 'ELABORADO', 'UN', true)
                ON CONFLICT ("idProducto") DO NOTHING
                """, ID_PRODUCTO);

            // Precio inicial vigente
            jdbc.update("""
                INSERT INTO "PRECIO_PRODUCTO" ("idPrecio","idProducto","precioBase","vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                VALUES (?, ?, 100.0000, clock_timestamp() - interval '1 day', now(), ?)
                ON CONFLICT ("idPrecio") DO NOTHING
                """, ID_PRECIO_INICIAL, ID_PRODUCTO, ID_USUARIO_ADMIN);

            // Política Comercial y Ajuste
            var politicas = jdbc.queryForList("""
                SELECT "idPoliticaComercial" FROM "POLITICA_CATEGORIA_COMERCIAL"
                WHERE "vigenteDesde" <= clock_timestamp() AND ("vigenteHasta" IS NULL OR "vigenteHasta" > clock_timestamp())
                """, Long.class);
            long politicaId = politicas.isEmpty() ? ID_POLITICA : politicas.getFirst();
            if (politicas.isEmpty()) {
                jdbc.update("""
                    INSERT INTO "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial","diasToleranciaMora","vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                    VALUES (?, 5, clock_timestamp() - interval '10 days', now(), ?)
                    ON CONFLICT DO NOTHING
                    """, politicaId, ID_USUARIO_ADMIN);
                jdbc.update("INSERT INTO \"AJUSTE_POLITICA_CATEGORIA\" VALUES (?,'CUMPLIDOR','SIN_AJUSTE',0) ON CONFLICT DO NOTHING", politicaId);
                jdbc.update("INSERT INTO \"AJUSTE_POLITICA_CATEGORIA\" VALUES (?,'EN_MORA','RECARGO',10) ON CONFLICT DO NOTHING", politicaId);
            }
        });
    }

    @Test
    @DisplayName("UC-03 con idCliente=1919001 y UC-19 concurrentes finalizan sin deadlock 40P01, persisten datos y validan replay")
    void uc03Yuc19ConcurrentesFinalizanSinDeadlock() throws Exception {
        UUID claveIdempotencia = UUID.randomUUID();
        DireccionEntrega direccion = new DireccionEntrega("Calle Falsa", "1919", null, null, "Posadas", "Misiones", "Frente plaza", "Centro");
        DatosPedido datosPedido = new DatosPedido(
                ID_CLIENTE_PROBLEMATICO,
                hoy.plusDays(3),
                List.of(new ItemPedido(ID_PRODUCTO, new BigDecimal("2.000"))),
                direccion,
                claveIdempotencia
        );

        BigDecimal nuevoPrecio = new BigDecimal("150.00");
        OffsetDateTime inicioNuevoPrecio = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5);

        PedidoCreado pedidoCreado;
        PrecioActualizado precioActualizado;

        try (var pool = Executors.newFixedThreadPool(2)) {
            var inicio = new CountDownLatch(1);
            Future<PedidoCreado> futurePedido = pool.submit(() -> {
                inicio.await();
                return servicioPedido.cargarPedido(SESION_VENDEDOR, datosPedido);
            });
            Future<PrecioActualizado> futurePrecio = pool.submit(() -> {
                inicio.await();
                return servicioPrecio.actualizarPrecioVigente(
                        SESION_ADMIN,
                        ID_PRODUCTO,
                        nuevoPrecio,
                        inicioNuevoPrecio,
                        null
                );
            });

            inicio.countDown();

            // Timeout defensivo de 25 segundos; si hubiera deadlock 40P01 o bloqueo indefinido, fallaría aquí.
            pedidoCreado = futurePedido.get(25, TimeUnit.SECONDS);
            precioActualizado = futurePrecio.get(25, TimeUnit.SECONDS);
        }

        // 1. Verificación de UC-03: Pedido creado exitosamente sin 40P01
        assertNotNull(pedidoCreado);
        assertTrue(pedidoCreado.creado());
        assertTrue(pedidoCreado.idPedido() > 0);
        assertEquals("PENDIENTE", pedidoCreado.estado());
        assertEquals("INTERNO", pedidoCreado.canalOrigen());
        assertEquals(1, pedidoCreado.cantidadItems());
        assertEquals(new BigDecimal("200.00"), pedidoCreado.totalOriginal());

        // 2. Verificación de UC-19: Precio actualizado exitosamente sin 40P01
        assertNotNull(precioActualizado);
        assertTrue(precioActualizado.idPrecio() > 0);
        assertTrue(precioActualizado.idAuditoriaPrecio() > 0);
        assertEquals(ID_PRECIO_INICIAL, precioActualizado.idPrecioCerrado());

        // 3. Verificación de datos persistidos en base de datos
        Integer pedidosPersistidos = jdbc.queryForObject(
                "SELECT count(*) FROM \"PEDIDO\" WHERE \"idOperacionOrigen\" = ?",
                Integer.class,
                claveIdempotencia
        );
        assertEquals(1, pedidosPersistidos);

        Integer detallesPersistidos = jdbc.queryForObject(
                "SELECT count(*) FROM \"DETALLE_PEDIDO\" WHERE \"idPedido\" = ?",
                Integer.class,
                pedidoCreado.idPedido()
        );
        assertEquals(1, detallesPersistidos);

        Integer preciosPersistidos = jdbc.queryForObject(
                "SELECT count(*) FROM \"PRECIO_PRODUCTO\" WHERE \"idProducto\" = ? AND \"fechaHoraCancelacion\" IS NULL",
                Integer.class,
                ID_PRODUCTO
        );
        assertEquals(2, preciosPersistidos);

        Integer auditoriasPersistidas = jdbc.queryForObject(
                "SELECT count(*) FROM \"AUDITORIA_PRECIO\" WHERE \"idPrecio\" IN (?, ?)",
                Integer.class,
                ID_PRECIO_INICIAL,
                precioActualizado.idPrecio()
        );
        assertEquals(2, auditoriasPersistidas);

        // 4. Verificación obligatoria de Replay Idempotente de UC-03 (PR #85)
        PedidoCreado replay = servicioPedido.cargarPedido(SESION_VENDEDOR, datosPedido);
        assertNotNull(replay);
        assertFalse(replay.creado(), "El replay debe reportar creado=false");
        assertEquals(pedidoCreado.idPedido(), replay.idPedido());
        assertEquals(1, replay.cantidadItems(), "La corrección de PR #85 debe reflejarse en cantidadItems");
        assertEquals(pedidoCreado.totalOriginal(), replay.totalOriginal());
        assertEquals(pedidoCreado.estado(), replay.estado());

        // Confirmar que el replay no insertó un segundo pedido
        Integer pedidosTrasReplay = jdbc.queryForObject(
                "SELECT count(*) FROM \"PEDIDO\" WHERE \"idOperacionOrigen\" = ?",
                Integer.class,
                claveIdempotencia
        );
        assertEquals(1, pedidosTrasReplay);
    }
}
