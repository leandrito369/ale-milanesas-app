package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import ar.edu.unam.alemilanesas.finanzas.RegistrarMovimientoCajaService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** UC-22: HTTP real, PostgreSQL, idempotencia, concurrencia y separacion contable. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ale.http.writes.finanzas-uc22-enabled=true")
@Testcontainers(disabledWithoutDocker = true)
class RegistrarMovimientoCajaHttpExternoTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    private static final AtomicLong SECUENCIA = new AtomicLong(2_200_000);
    private static final String RUTA = "/api/v1/finanzas/movimientos-caja";
    private static final JsonMapper JSON_EXACTO = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    @Value("${local.server.port}") int puerto;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Autowired RegistrarMovimientoCajaService caja;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private long base;
    private UUID sesionAdmin;
    private String admin;
    private String vendedor;

    @BeforeEach void preparar() {
        base = SECUENCIA.addAndGet(20);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Admin UC22'),('VENDEDOR','Vendedor UC22')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            jdbc.update("""
                    INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                        "estado","cambioCredencialPendiente","fechaHoraAlta") VALUES
                    (?,?,'HASH_TEST','HABILITADO',false,clock_timestamp()),
                    (?,?,'HASH_TEST','HABILITADO',false,clock_timestamp())
                    """, base, "admin_uc22_" + base, base + 1, "vendedor_uc22_" + base);
            jdbc.update("""
                    INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion") VALUES
                    (?,'ADMINISTRADOR',clock_timestamp()),(?,'VENDEDOR',clock_timestamp())
                    """, base, base + 1);
            sesionAdmin = UUID.randomUUID();
            admin = sesion(base, sesionAdmin);
            vendedor = sesion(base + 1, UUID.randomUUID());
        });
    }

    private String sesion(long usuario, UUID id) {
        String token = TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES(?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour')
                """, id, usuario, TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private HttpResponse<String> enviar(String token, UUID clave, Object cuerpo) throws Exception {
        return enviarCrudo(token, clave, json.writeValueAsString(cuerpo));
    }

    private HttpResponse<String> enviarCrudo(String token, UUID clave, String cuerpoJson) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + RUTA))
                .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (clave != null) b.header("Idempotency-Key", clave.toString());
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(cuerpoJson)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> valido(String concepto) {
        return Map.of("tipoMovimiento", "EGRESO", "importe", new BigDecimal("125.40"),
                "concepto", concepto, "observacion", "Comprobante interno");
    }

    private int contar(String tabla) {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + tabla + "\"", Integer.class);
    }

    @Test void administradorRegistraUnaFilaManualSinTocarCuentaCobroClienteOReembolso() throws Exception {
        int cobros = contar("COBRO"), cuentas = contar("MOVIMIENTO_CUENTA");
        int clientes = contar("CLIENTE"), reembolsos = contar("REEMBOLSO");
        UUID clave = UUID.randomUUID();
        var r = enviar(admin, clave, valido("Compra de elementos de limpieza " + base));
        assertEquals(201, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        long id = json.readTree(r.body()).get("idMovimientoCaja").asLong();
        var fila = jdbc.queryForMap("SELECT * FROM \"MOVIMIENTO_CAJA\" WHERE \"idMovimientoCaja\"=?", id);
        assertEquals("MANUAL", fila.get("origen"));
        assertEquals("EGRESO", fila.get("tipoMovimiento"));
        assertEquals("UC22:" + clave, fila.get("referencia"));
        assertNull(fila.get("idCobro"));
        assertNull(fila.get("idCobroAnulado"));
        assertNull(fila.get("idReembolso"));
        assertEquals(cobros, contar("COBRO"));
        assertEquals(cuentas, contar("MOVIMIENTO_CUENTA"));
        assertEquals(clientes, contar("CLIENTE"));
        assertEquals(reembolsos, contar("REEMBOLSO"));
    }

    @Test void reintentoIgualNoDuplicaYMismaClaveConOtroPayloadDa409() throws Exception {
        UUID clave = UUID.randomUUID();
        var primero = enviar(admin, clave, valido("Ajuste operativo " + base));
        var repetido = enviar(admin, clave, valido("Ajuste operativo " + base));
        assertEquals(201, primero.statusCode(), primero.body());
        assertEquals(200, repetido.statusCode(), repetido.body());
        assertEquals(json.readTree(primero.body()).get("idMovimientoCaja").asLong(),
                json.readTree(repetido.body()).get("idMovimientoCaja").asLong());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM "MOVIMIENTO_CAJA" WHERE "referencia"=?
                """, Integer.class, "UC22:" + clave));
        var conflicto = enviar(admin, clave, Map.of("tipoMovimiento", "INGRESO",
                "importe", 125.40, "concepto", "Ajuste operativo " + base));
        assertEquals(409, conflicto.statusCode(), conflicto.body());
    }

    @Test void rechazaCredencialesEntradasInvalidasYVinculosContables() throws Exception {
        UUID clave = UUID.randomUUID();
        assertEquals(401, enviar(null, clave, valido("Sin sesion")).statusCode());
        assertEquals(403, enviar(vendedor, clave, valido("Sin rol")).statusCode());
        assertEquals(400, enviar(admin, clave, Map.of("tipoMovimiento", "SALIDA",
                "importe", 1, "concepto", "Tipo invalido")).statusCode());
        assertEquals(400, enviar(admin, clave, Map.of("tipoMovimiento", "EGRESO",
                "importe", 0, "concepto", "Importe invalido")).statusCode());
        assertEquals(400, enviar(admin, clave, Map.of("tipoMovimiento", "EGRESO",
                "importe", 1, "concepto", " ")).statusCode());
        assertEquals(400, enviar(admin, clave, Map.of("tipoMovimiento", "INGRESO",
                "importe", 10, "concepto", "Pago aislado", "idCliente", 99)).statusCode());
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM "MOVIMIENTO_CAJA" WHERE "referencia"=?
                """, Integer.class, "UC22:" + clave));
    }

    @Test void concurrenciaUsaIdsUnicosBajoElLockCompartido() throws Exception {
        int cantidad = 12;
        try (var ejecutor = Executors.newFixedThreadPool(6)) {
            var tareas = new ArrayList<java.util.concurrent.Callable<Long>>();
            for (int i = 0; i < cantidad; i++) {
                int numero = i;
                tareas.add(() -> caja.registrarMovimientoCaja(sesionAdmin,
                        numero % 2 == 0 ? "INGRESO" : "EGRESO", new BigDecimal("1.00"),
                        "Concurrencia UC22 " + base + "-" + numero, null, UUID.randomUUID())
                        .idMovimientoCaja());
            }
            var resultados = ejecutor.invokeAll(tareas);
            ejecutor.shutdown();
            assertTrue(ejecutor.awaitTermination(20, TimeUnit.SECONDS));
            var ids = new HashSet<Long>();
            for (var resultado : resultados) ids.add(resultado.get(20, TimeUnit.SECONDS));
            assertEquals(cantidad, ids.size());
        }
    }

    @Test void aceptaLimitesExactosNumeric18_2ConPrecisionJsonYPostgres() throws Exception {
        String valorMaximo = "9999999999999999.99";
        BigDecimal esperadoMax = new BigDecimal(valorMaximo);
        UUID claveMax = UUID.randomUUID();
        String payloadMax = "{\"tipoMovimiento\":\"INGRESO\",\"importe\":" + valorMaximo
                + ",\"concepto\":\"Maximo NUMERIC 18_2 " + base + "\"}";
        var respMax = enviarCrudo(admin, claveMax, payloadMax);
        assertEquals(201, respMax.statusCode(), respMax.body());
        assertTrue(respMax.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertTrue(respMax.body().contains(valorMaximo), respMax.body());
        JsonNode nodoMax = JSON_EXACTO.readTree(respMax.body());
        assertEquals(0, esperadoMax.compareTo(nodoMax.get("importe").decimalValue()));
        long idMax = nodoMax.get("idMovimientoCaja").asLong();
        BigDecimal persistidoMax = jdbc.queryForObject("""
                SELECT "importe" FROM "MOVIMIENTO_CAJA" WHERE "idMovimientoCaja"=?
                """, BigDecimal.class, idMax);
        assertNotNull(persistidoMax);
        assertEquals(0, esperadoMax.compareTo(persistidoMax));
        assertEquals(2, persistidoMax.scale());

        var reintentoMax = enviarCrudo(admin, claveMax, payloadMax);
        assertEquals(200, reintentoMax.statusCode(), reintentoMax.body());
        assertEquals(idMax, JSON_EXACTO.readTree(reintentoMax.body()).get("idMovimientoCaja").asLong());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM "MOVIMIENTO_CAJA" WHERE "referencia"=?
                """, Integer.class, "UC22:" + claveMax));

        UUID claveMin = UUID.randomUUID();
        var respMin = enviarCrudo(admin, claveMin,
                "{\"tipoMovimiento\":\"INGRESO\",\"importe\":0.01,\"concepto\":\"Minimo valido " + base + "\"}");
        assertEquals(201, respMin.statusCode(), respMin.body());
        JsonNode nodoMin = JSON_EXACTO.readTree(respMin.body());
        assertEquals(0, new BigDecimal("0.01").compareTo(nodoMin.get("importe").decimalValue()));
        BigDecimal persistidoMin = jdbc.queryForObject("""
                SELECT "importe" FROM "MOVIMIENTO_CAJA" WHERE "idMovimientoCaja"=?
                """, BigDecimal.class, nodoMin.get("idMovimientoCaja").asLong());
        assertEquals(0, new BigDecimal("0.01").compareTo(persistidoMin));
        assertEquals(2, persistidoMin.scale());

        UUID claveCeros = UUID.randomUUID();
        var respCeros = enviarCrudo(admin, claveCeros,
                "{\"tipoMovimiento\":\"EGRESO\",\"importe\":10.500,\"concepto\":\"Ceros finales " + base + "\"}");
        assertEquals(201, respCeros.statusCode(), respCeros.body());
        BigDecimal persistidoCeros = jdbc.queryForObject("""
                SELECT "importe" FROM "MOVIMIENTO_CAJA" WHERE "referencia"=?
                """, BigDecimal.class, "UC22:" + claveCeros);
        assertEquals(0, new BigDecimal("10.50").compareTo(persistidoCeros));
        assertEquals(2, persistidoCeros.scale());
    }

    @Test void rechazaImportesInvalidosDeRangoEscalaCeroNegativosONuloAntesDeInsert() throws Exception {
        int movimientosAntes = contar("MOVIMIENTO_CAJA");
        int cobros = contar("COBRO");
        int cuentas = contar("MOVIMIENTO_CUENTA");
        int clientes = contar("CLIENTE");
        int reembolsos = contar("REEMBOLSO");

        var payloadsInvalidos = java.util.List.of(
                "{\"tipoMovimiento\":\"INGRESO\",\"importe\":10000000000000000,\"concepto\":\"Igual a 10^16\"}",
                "{\"tipoMovimiento\":\"INGRESO\",\"importe\":1E+16,\"concepto\":\"Cientifico 1E+16\"}",
                "{\"tipoMovimiento\":\"EGRESO\",\"importe\":10.123,\"concepto\":\"Tres decimales\"}",
                "{\"tipoMovimiento\":\"EGRESO\",\"importe\":9999999999999999.999,\"concepto\":\"Sin redondeo\"}",
                "{\"tipoMovimiento\":\"EGRESO\",\"importe\":0.001,\"concepto\":\"Subcentavo\"}",
                "{\"tipoMovimiento\":\"EGRESO\",\"importe\":0,\"concepto\":\"Cero\"}",
                "{\"tipoMovimiento\":\"EGRESO\",\"importe\":-0.01,\"concepto\":\"Negativo\"}",
                "{\"tipoMovimiento\":\"EGRESO\",\"importe\":-10,\"concepto\":\"Negativo entero\"}",
                "{\"tipoMovimiento\":\"EGRESO\",\"importe\":null,\"concepto\":\"Nulo\"}",
                "{\"tipoMovimiento\":\"EGRESO\",\"importe\":\"10.00\",\"concepto\":\"Texto no numerico\"}",
                "{\"tipoMovimiento\":\"EGRESO\",\"tipoMovimiento\":\"INGRESO\",\"importe\":10,\"concepto\":\"Duplicado\"}"
        );

        for (String payload : payloadsInvalidos) {
            UUID clave = UUID.randomUUID();
            var resp = enviarCrudo(admin, clave, payload);
            assertEquals(400, resp.statusCode(), () -> "Payload debia dar 400: " + payload + " -> " + resp.body());
            assertTrue(resp.body().contains("SOLICITUD_INVALIDA"), resp.body());
            assertFalse(resp.body().contains("22003"), resp.body());
            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM "MOVIMIENTO_CAJA" WHERE "referencia"=?
                    """, Integer.class, "UC22:" + clave));
        }

        var importesServicioInvalidos = java.util.Arrays.asList(
                null,
                BigDecimal.ZERO,
                new BigDecimal("-0.01"),
                new BigDecimal("10.123"),
                new BigDecimal("9999999999999999.999"),
                new BigDecimal("10000000000000000"),
                new BigDecimal("1E+16")
        );
        for (BigDecimal invalido : importesServicioInvalidos) {
            UUID claveDirecta = UUID.randomUUID();
            String conceptoDirecto = "Servicio directo " + base;
            assertThrows(IllegalArgumentException.class, () -> caja.registrarMovimientoCaja(
                    sesionAdmin, "INGRESO", invalido, conceptoDirecto, null, claveDirecta));
        }

        assertEquals(movimientosAntes, contar("MOVIMIENTO_CAJA"));
        assertEquals(cobros, contar("COBRO"));
        assertEquals(cuentas, contar("MOVIMIENTO_CUENTA"));
        assertEquals(clientes, contar("CLIENTE"));
        assertEquals(reembolsos, contar("REEMBOLSO"));
    }
}
