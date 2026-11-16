package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
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
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** UC-23: puerto HTTP real, RBAC y PostgreSQL con MR5/F01. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ConsultarLibroDiarioHttpExternoTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    private static final AtomicLong SECUENCIA = new AtomicLong(2_300_000);
    private static final String RUTA = "/api/v1/finanzas/libro-diario";
    @Value("${local.server.port}") int puerto;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CobroManualService cobros;
    @Autowired ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private long base;
    private String admin;
    private String vendedor;
    private UUID sesionAdmin;
    private LocalDate fecha;
    private int movimientosAntes;

    @BeforeEach void preparar() {
        base = SECUENCIA.addAndGet(100);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Admin UC23'),('VENDEDOR','Vendedor UC23')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            jdbc.update("""
                    INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                        "estado","cambioCredencialPendiente","fechaHoraAlta") VALUES
                    (?,?,'HASH_TEST','HABILITADO',false,clock_timestamp()),
                    (?,?,'HASH_TEST','HABILITADO',false,clock_timestamp())
                    """, base, "admin_uc23_" + base, base + 1, "vendedor_uc23_" + base);
            jdbc.update("""
                    INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion") VALUES
                    (?,'ADMINISTRADOR',clock_timestamp()),(?,'VENDEDOR',clock_timestamp())
                    """, base, base + 1);
            sesionAdmin = UUID.randomUUID();
            admin = crearSesion(base, sesionAdmin);
            vendedor = crearSesion(base + 1, UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO "CLIENTE"("idCliente","nombres","telefonoPrincipal","estado",
                        "categoriaComercial","fechaHoraAlta")
                    VALUES(?,?,'3764000023','ACTIVO','CUMPLIDOR',clock_timestamp())
                    """, base + 10, "Cliente UC23 " + base);
            jdbc.update("""
                    INSERT INTO "POLITICA_CATEGORIA_COMERCIAL"("idPoliticaComercial",
                        "diasToleranciaMora","vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                    SELECT 2300001,7,clock_timestamp()-interval '10 days',clock_timestamp(),?
                     WHERE NOT EXISTS (SELECT 1 FROM "POLITICA_CATEGORIA_COMERCIAL")
                    """, base);
            jdbc.update("""
                    INSERT INTO "AJUSTE_POLITICA_CATEGORIA"("idPoliticaComercial","categoriaComercial",
                        "tipoAjuste","porcentajeAjuste") VALUES
                    (2300001,'CUMPLIDOR','SIN_AJUSTE',0),(2300001,'EN_MORA','SIN_AJUSTE',0)
                    ON CONFLICT DO NOTHING
                    """);
            fecha = jdbc.queryForObject("""
                    SELECT (clock_timestamp() AT TIME ZONE 'America/Argentina/Buenos_Aires')::date
                    """, LocalDate.class);
        });
        tx.executeWithoutResult(s -> {
            cobros.registrarCobro(sesionAdmin, base + 10, null, new BigDecimal("30.25"),
                    "EFECTIVO", UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO "MOVIMIENTO_CAJA"("idMovimientoCaja","tipoMovimiento","importe",
                        "fechaHoraMovimiento","origen","concepto","referencia","idUsuarioRegistro")
                    VALUES(?, 'EGRESO', 10.10,
                        (?::date::timestamp AT TIME ZONE 'America/Argentina/Buenos_Aires'),
                        'MANUAL','Gasto operativo','REF-UC23',?)
                    """, base + 90, fecha, base);
        });
        movimientosAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class);
    }

    private String crearSesion(long usuario, UUID id) {
        String token = TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES(?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour')
                """, id, usuario, TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private HttpResponse<String> consultar(String token, String query) throws Exception {
        var solicitud = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + RUTA + query))
                .timeout(Duration.ofSeconds(20)).GET();
        if (token != null) solicitud.header("Authorization", "Bearer " + token);
        return http.send(solicitud.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void administradorObtieneMovimientosOrigenesTotalesYNoMutaCaja() throws Exception {
        var r = consultar(admin, "?fechaInicial=" + fecha + "&fechaFinal=" + fecha);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var cuerpo = json.readTree(r.body());
        int cantidadEsperada = jdbc.queryForObject("""
                SELECT count(*) FROM "MOVIMIENTO_CAJA"
                 WHERE ("fechaHoraMovimiento" AT TIME ZONE 'America/Argentina/Buenos_Aires')::date=?
                """, Integer.class, fecha);
        BigDecimal ingresosEsperados = jdbc.queryForObject("""
                SELECT COALESCE(sum("importe"),0) FROM "MOVIMIENTO_CAJA"
                 WHERE "tipoMovimiento"='INGRESO'
                   AND ("fechaHoraMovimiento" AT TIME ZONE 'America/Argentina/Buenos_Aires')::date=?
                """, BigDecimal.class, fecha);
        BigDecimal egresosEsperados = jdbc.queryForObject("""
                SELECT COALESCE(sum("importe"),0) FROM "MOVIMIENTO_CAJA"
                 WHERE "tipoMovimiento"='EGRESO'
                   AND ("fechaHoraMovimiento" AT TIME ZONE 'America/Argentina/Buenos_Aires')::date=?
                """, BigDecimal.class, fecha);
        assertEquals(cantidadEsperada, cuerpo.get("movimientos").size(), r.body());
        assertEquals(0, ingresosEsperados.compareTo(cuerpo.get("totalIngresos").decimalValue()));
        assertEquals(0, egresosEsperados.compareTo(cuerpo.get("totalEgresos").decimalValue()));
        assertEquals(0, ingresosEsperados.subtract(egresosEsperados)
                .compareTo(cuerpo.get("resultadoMonetario").decimalValue()));
        assertTrue(r.body().contains("COBRO"));
        assertTrue(r.body().contains("MANUAL"));
        assertEquals(movimientosAntes,
                jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class));
    }

    @Test void rangoVacioDevuelveListaYTotalesCero() throws Exception {
        LocalDate vacia = fecha.minusYears(10);
        var r = consultar(admin, "?fechaInicial=" + vacia + "&fechaFinal=" + vacia);
        assertEquals(200, r.statusCode(), r.body());
        var cuerpo = json.readTree(r.body());
        assertEquals(0, cuerpo.get("movimientos").size());
        assertEquals("0", cuerpo.get("totalIngresos").asText());
        assertEquals("0", cuerpo.get("totalEgresos").asText());
        assertEquals("0", cuerpo.get("resultadoMonetario").asText());
        assertFalse(r.body().contains("saldoFisico"));
    }

    @Test void seguridadYParametrosSeAplicanEnServidor() throws Exception {
        String valido = "?fechaInicial=" + fecha + "&fechaFinal=" + fecha;
        assertEquals(401, consultar(null, valido).statusCode());
        assertEquals(403, consultar(vendedor, valido).statusCode());
        assertEquals(400, consultar(admin, "?fechaInicial=" + fecha).statusCode());
        assertEquals(400, consultar(admin, "?fechaInicial=" + fecha.plusDays(1)
                + "&fechaFinal=" + fecha).statusCode());
        assertEquals(400, consultar(admin, valido + "&intruso=1").statusCode());
        assertEquals(400, consultar(admin, "?fechaInicial=sin-fecha&fechaFinal=" + fecha).statusCode());
    }
}
