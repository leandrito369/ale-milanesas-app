package ar.edu.unam.alemilanesas.finanzas;

import ar.edu.unam.alemilanesas.gfis.GfisLabSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/** UC-14 R09.3a: consulta read-only sobre PostgreSQL 17.11 y Flyway V1-V4. */
@SpringBootTest
@Testcontainers
@Transactional
class ConsultarCuentaCorrienteServiceIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", db::getJdbcUrl);
        registro.add("spring.datasource.username", db::getUsername);
        registro.add("spring.datasource.password", db::getPassword);
    }

    private static final long ID_ADMIN = 91401L;
    private static final long ID_VENDEDOR = 91402L;
    private static final long ID_REPARTIDOR = 91403L;
    private static final long ID_CLIENTE = 91411L;
    private static final long ID_POLITICA = 91421L;
    private static final long ID_PRODUCTO = 91431L;

    private static final UUID SESION_ADMIN =
            UUID.fromString("91400000-0000-4000-8000-000000000001");
    private static final UUID SESION_VENDEDOR =
            UUID.fromString("91400000-0000-4000-8000-000000000002");
    private static final UUID SESION_REPARTIDOR =
            UUID.fromString("91400000-0000-4000-8000-000000000003");

    @Autowired JdbcTemplate jdbc;
    @Autowired ConsultarCuentaCorrienteService servicio;

    @BeforeEach
    void semillaBase() {
        jdbc.update("""
                INSERT INTO "ROL" ("codigoRol","nombreMostrado") VALUES
                ('ADMINISTRADOR','Administrador UC14'),
                ('VENDEDOR','Vendedor UC14'),
                ('REPARTIDOR','Repartidor UC14')
                ON CONFLICT ("codigoRol") DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta") VALUES
                (?,'admin_uc14','HASH_TEST','HABILITADO',false,clock_timestamp()),
                (?,'vendedor_uc14','HASH_TEST','HABILITADO',false,clock_timestamp()),
                (?,'repartidor_uc14','HASH_TEST','HABILITADO',false,clock_timestamp())
                """, ID_ADMIN, ID_VENDEDOR, ID_REPARTIDOR);
        jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion") VALUES
                (?,'ADMINISTRADOR',clock_timestamp()),
                (?,'VENDEDOR',clock_timestamp()),
                (?,'REPARTIDOR',clock_timestamp())
                """, ID_ADMIN, ID_VENDEDOR, ID_REPARTIDOR);
        jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion") VALUES
                (?,?,decode('914001','hex'),clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour'),
                (?,?,decode('914002','hex'),clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour'),
                (?,?,decode('914003','hex'),clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour')
                """, SESION_ADMIN, ID_ADMIN, SESION_VENDEDOR, ID_VENDEDOR,
                SESION_REPARTIDOR, ID_REPARTIDOR);
        crearCliente(ID_CLIENTE, "Cliente UC14");
        jdbc.update("""
                INSERT INTO "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial",
                    "diasToleranciaMora","vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                VALUES (?,7,clock_timestamp()-interval '30 days',clock_timestamp(),?)
                """, ID_POLITICA, ID_ADMIN);
        jdbc.update("""
                INSERT INTO "AJUSTE_POLITICA_CATEGORIA"
                    ("idPoliticaComercial","categoriaComercial","tipoAjuste","porcentajeAjuste")
                VALUES (?,'CUMPLIDOR','SIN_AJUSTE',0),
                       (?,'EN_MORA','SIN_AJUSTE',0)
                """, ID_POLITICA, ID_POLITICA);
        jdbc.update("INSERT INTO \"UNIDAD_MEDIDA\" VALUES ('UN','Unidad') ON CONFLICT DO NOTHING");
        jdbc.update("""
                INSERT INTO "PRODUCTO" ("idProducto","nombre","nombreNormalizado",
                    "tipoProducto","codigoUnidadVenta","activo")
                VALUES (?,'Producto UC14','producto uc14','ELABORADO','UN',true)
                """, ID_PRODUCTO);
    }

    @Test
    void cuentaSinMovimientosDevuelveTresMagnitudesEnCero() {
        var estado = consultar(ID_CLIENTE, null, null);

        importe("0.00", estado.saldoConfirmado());
        importe("0.00", estado.importeTransitoValidado());
        importe("0.00", estado.saldoExigibleProvisional());
        assertTrue(estado.movimientos().isEmpty());
        assertNotNull(estado.fechaHoraCorte());
    }

    @Test
    void saldoConfirmadoDistingueDeudaYSaldoAFavor() {
        movimientoAjuste(914101, ID_CLIENTE, 1, "AJUSTE_DEBITO",
                "100.00", "100.00", instante(20));
        var deudor = consultar(ID_CLIENTE, null, null);
        importe("100.00", deudor.saldoConfirmado());
        importe("100.00", deudor.saldoExigibleProvisional());

        movimientoAjuste(914102, ID_CLIENTE, 2, "AJUSTE_CREDITO",
                "150.00", "-50.00", instante(21));
        var aFavor = consultar(ID_CLIENTE, null, null);
        importe("-50.00", aFavor.saldoConfirmado());
        importe("-50.00", aFavor.saldoExigibleProvisional());
        assertEquals(2, aFavor.movimientos().size());
        assertEquals("AJUSTE_USUARIO", aFavor.movimientos().getFirst().origen().tipo());
        assertEquals(ID_VENDEDOR, aFavor.movimientos().getFirst().origen().idReferencia());
    }

    @Test
    void transitoValidoUsaVigentePe1YNoDeclaracionBruta() {
        long recorrido = recorrido("UC14-TRANSITO");
        long pedido = pedidoConPe1(914201, ID_CLIENTE, recorrido,
                "100.00", "75.00", "IMP_B");
        movimientoCargo(914211, pedido, 1, "100.00", "100.00", instante(20));

        var estado = consultar(ID_CLIENTE, null, null);

        importe("100.00", estado.saldoConfirmado());
        importe("75.00", estado.importeTransitoValidado());
        importe("25.00", estado.saldoExigibleProvisional());
    }

    @Test
    void declaracionSinReconocimientoCanonicoNoReduceExigible() {
        long recorrido = recorrido("UC14-IMP-C");
        long pedido = pedidoConPe1(914301, ID_CLIENTE, recorrido,
                "100.00", "100.00", "IMP_C");
        movimientoCargo(914311, pedido, 1, "100.00", "100.00", instante(20));

        BigDecimal declarado = jdbc.queryForObject("""
                SELECT "declaradoRepartidorDe" FROM "RECONOCIMIENTO_ENTREGA_PE1"
                 WHERE "idPedido"=?
                """, BigDecimal.class, pedido);
        importe("100.00", declarado);

        var estado = consultar(ID_CLIENTE, null, null);
        importe("0.00", estado.importeTransitoValidado());
        importe("100.00", estado.saldoExigibleProvisional());
    }

    @Test
    void reconocimientoParcialmenteRendidoRestaSoloVigenteSinDoblePago() {
        long recorrido = recorrido("UC14-PARCIAL");
        long pedido = pedidoConPe1(914401, ID_CLIENTE, recorrido,
                "100.00", "75.00", "IMP_B");
        movimientoCargo(914411, pedido, 1, "100.00", "100.00", instante(20));

        GfisLabSupport.ejecutarCierreLimpioT1K2EnTx(
                jdbc, recorrido, ID_ADMIN, UUID.randomUUID(), "v1", "uc14-parcial",
                new BigDecimal("50.00"), new BigDecimal("50.00"),
                List.of(new GfisLabSupport.AplicacionPlan(
                        ID_CLIENTE, pedido, new BigDecimal("50.00"))),
                true, true);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");

        var estado = consultar(ID_CLIENTE, null, null);
        importe("50.00", estado.saldoConfirmado());
        importe("25.00", estado.importeTransitoValidado());
        importe("25.00", estado.saldoExigibleProvisional());
        assertEquals(List.of("CARGO_POR_VENTA", "PAGO"),
                estado.movimientos().stream()
                        .map(ConsultarCuentaCorrienteService.MovimientoCuenta::tipoMovimiento)
                        .toList());
        assertEquals("COBRO", estado.movimientos().get(1).origen().tipo());
    }

    @Test
    void variasEntregasDelMismoClienteSumanSoloReconocimientosVigentes() {
        long recorrido = recorrido("UC14-MULTIPLE");
        long pedidoA = pedidoConPe1(914501, ID_CLIENTE, recorrido,
                "100.00", "30.00", "IMP_B");
        long pedidoB = pedidoConPe1(914502, ID_CLIENTE, recorrido,
                "120.00", "40.00", "IMP_B");
        movimientoCargo(914511, pedidoA, 1, "100.00", "100.00", instante(20));
        movimientoCargo(914512, pedidoB, 2, "120.00", "220.00", instante(21));

        var estado = consultar(ID_CLIENTE, null, null);
        importe("220.00", estado.saldoConfirmado());
        importe("70.00", estado.importeTransitoValidado());
        importe("150.00", estado.saldoExigibleProvisional());
    }

    @Test
    void filtroDeFechasRestringeHistorialPeroNoLosTresSaldos() {
        movimientoAjuste(914601, ID_CLIENTE, 1, "AJUSTE_DEBITO",
                "10.00", "10.00", instante(20));
        movimientoAjuste(914602, ID_CLIENTE, 2, "AJUSTE_DEBITO",
                "5.00", "15.00", instante(21));
        movimientoAjuste(914603, ID_CLIENTE, 3, "AJUSTE_CREDITO",
                "3.00", "12.00", instante(22));

        var completo = consultar(ID_CLIENTE, null, null);
        var filtrado = consultar(ID_CLIENTE,
                LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 21));

        assertEquals(3, completo.movimientos().size());
        assertEquals(1, filtrado.movimientos().size());
        assertEquals(914602L, filtrado.movimientos().getFirst().idMovimientoCuenta());
        importe(completo.saldoConfirmado().toPlainString(), filtrado.saldoConfirmado());
        importe(completo.importeTransitoValidado().toPlainString(),
                filtrado.importeTransitoValidado());
        importe(completo.saldoExigibleProvisional().toPlainString(),
                filtrado.saldoExigibleProvisional());
    }

    @Test
    void soloVendedorValidaSesionClienteYFechasSinAmpliarRbac() {
        assertDoesNotThrow(() -> consultar(ID_CLIENTE, null, null));
        assertThrows(SecurityException.class,
                () -> servicio.consultarCuentaCorriente(SESION_ADMIN, ID_CLIENTE, null, null));
        assertThrows(SecurityException.class,
                () -> servicio.consultarCuentaCorriente(SESION_REPARTIDOR, ID_CLIENTE, null, null));
        assertThrows(SecurityException.class,
                () -> servicio.consultarCuentaCorriente(UUID.randomUUID(), ID_CLIENTE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> consultar(99999999L, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> consultar(ID_CLIENTE,
                        LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 21)));
    }

    @Test
    void consultaNoEscribeCuentaCobroCajaNiEstructurasGfis() {
        long recorrido = recorrido("UC14-READONLY");
        long pedido = pedidoConPe1(914701, ID_CLIENTE, recorrido,
                "80.00", "60.00", "IMP_B");
        movimientoCargo(914711, pedido, 1, "80.00", "80.00", instante(20));
        Map<String, Integer> antes = conteosFinancieros();

        var estado = consultar(ID_CLIENTE, null, null);

        assertFalse(estado.movimientos().isEmpty());
        assertEquals(antes, conteosFinancieros());
    }

    private ConsultarCuentaCorrienteService.EstadoCuentaCorriente consultar(
            long idCliente, LocalDate desde, LocalDate hasta) {
        return servicio.consultarCuentaCorriente(SESION_VENDEDOR, idCliente, desde, hasta);
    }

    private void crearCliente(long idCliente, String nombre) {
        jdbc.update("""
                INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal",
                    "estado","categoriaComercial","fechaHoraAlta")
                VALUES (?,?,?,'ACTIVO','CUMPLIDOR',clock_timestamp())
                """, idCliente, nombre, "376" + idCliente);
    }

    private long recorrido(String referencia) {
        return GfisLabSupport.crearRecorridoExplicito(jdbc, ID_REPARTIDOR, referencia);
    }

    private long pedidoConPe1(long idPedido,
                              long idCliente,
                              long idRecorrido,
                              String cargo,
                              String declarado,
                              String modoEvidencia) {
        jdbc.update("""
                INSERT INTO "PEDIDO" ("idPedido","idCliente","idUsuarioCreador",
                    "fechaHoraCarga","fechaReparto","canalOrigen","estado",
                    "categoriaComercialAplicada","idPoliticaComercialAplicada",
                    "tipoAjuste","porcentajeAjuste","totalOriginal","idOperacionOrigen",
                    "direccionEntregaCalle","direccionEntregaNumero","direccionEntregaLocalidad")
                VALUES (?,?,?,clock_timestamp(),current_date+1,'INTERNO','PENDIENTE',
                    'CUMPLIDOR',?,'SIN_AJUSTE',0,?,?,'Calle UC14','123','Posadas')
                """, idPedido, idCliente, ID_VENDEDOR, ID_POLITICA,
                new BigDecimal(cargo), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO "DETALLE_PEDIDO" ("idPedido","numeroDetalle","idProducto",
                    "codigoUnidadVenta","cantidadOriginal","precioUnitarioOriginal",
                    "porcentajePromocionOriginal")
                VALUES (?,1,?,'UN',1.000,?,0)
                """, idPedido, ID_PRODUCTO, new BigDecimal(cargo));
        GfisLabSupport.asignarYVincularPedidoEnLab(
                jdbc, idPedido, ID_REPARTIDOR, ID_VENDEDOR, idRecorrido);
        GfisLabSupport.registrarEntregaPe1EnLaboratorio(
                jdbc, idPedido, idCliente, ID_REPARTIDOR, idRecorrido,
                new BigDecimal(cargo), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), new BigDecimal(declarado), modoEvidencia);
        return idPedido;
    }

    private void movimientoCargo(long idMovimiento,
                                 long idPedido,
                                 long secuencia,
                                 String importe,
                                 String saldo,
                                 OffsetDateTime fecha) {
        jdbc.update("""
                INSERT INTO "MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCliente",
                    "numeroSecuenciaCliente","tipoMovimiento","importe",
                    "fechaHoraMovimiento","saldoResultante")
                VALUES (?,?,?,'CARGO_POR_VENTA',?,?,?)
                """, idMovimiento, ID_CLIENTE, secuencia,
                new BigDecimal(importe), fecha, new BigDecimal(saldo));
        jdbc.update("""
                INSERT INTO "ORIGEN_MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idPedidoEntrega")
                VALUES (?,?)
                """, idMovimiento, idPedido);
    }

    private void movimientoAjuste(long idMovimiento,
                                  long idCliente,
                                  long secuencia,
                                  String tipo,
                                  String importe,
                                  String saldo,
                                  OffsetDateTime fecha) {
        jdbc.update("""
                INSERT INTO "MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCliente",
                    "numeroSecuenciaCliente","tipoMovimiento","importe",
                    "fechaHoraMovimiento","saldoResultante","idUsuarioAjuste","observacion")
                VALUES (?,?,?,?,?,?,?,?,'Fixture UC14')
                """, idMovimiento, idCliente, secuencia, tipo,
                new BigDecimal(importe), fecha, new BigDecimal(saldo), ID_VENDEDOR);
    }

    private Map<String, Integer> conteosFinancieros() {
        var resultado = new LinkedHashMap<String, Integer>();
        for (String tabla : List.of(
                "MOVIMIENTO_CUENTA", "ORIGEN_MOVIMIENTO_CUENTA", "COBRO",
                "MOVIMIENTO_CAJA", "RECONOCIMIENTO_ENTREGA_PE1",
                "EVENTO_IMPUTACION_FINANCIERA", "APLICACION_RENDICION_K2")) {
            resultado.put(tabla,
                    jdbc.queryForObject("SELECT count(*) FROM \"" + tabla + "\"", Integer.class));
        }
        return resultado;
    }

    private static OffsetDateTime instante(int dia) {
        return OffsetDateTime.of(2026, 11, dia, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
    }

    private static void importe(String esperado, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(esperado).compareTo(actual),
                () -> "Importe esperado " + esperado + " pero fue " + actual);
    }
}
