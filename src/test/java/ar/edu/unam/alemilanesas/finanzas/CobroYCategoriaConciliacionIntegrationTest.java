package ar.edu.unam.alemilanesas.finanzas;

import java.math.BigDecimal;
import java.util.UUID;

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
 * Conciliación B3 de UC29 + UC15 + UC16 + UC57 con COMMIT real de cada operación.
 * Sin @Transactional de JUnit ni creación de PEDIDO. No acredita E2E B4.
 */
@SpringBootTest
@Testcontainers
class CobroYCategoriaConciliacionIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",db::getJdbcUrl);
        registry.add("spring.datasource.username",db::getUsername);
        registry.add("spring.datasource.password",db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired CobroManualService cobroService;
    @Autowired AplicarAjusteComercialService ajusteService;

    @Test void moraCobroFifoDescuentoYAnulacionRestauraMoraConRedondeoHalfUp() {
        UUID admin=UUID.fromString("29151600-0000-4000-8000-000000000001");
        UUID vendedor=UUID.fromString("29151600-0000-4000-8000-000000000002");

        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            jdbc.update("""
                INSERT INTO "ROL" ("codigoRol","nombreMostrado") VALUES
                ('ADMINISTRADOR','Admin Conciliacion'),('VENDEDOR','Vendedor Conciliacion')
                """);
            jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta") VALUES
                (29501,'admin_concil','HASH_TEST','HABILITADO',false,now()),
                (29502,'seller_concil','HASH_TEST','HABILITADO',false,now())
                """);
            jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion") VALUES
                (29501,'ADMINISTRADOR',now()),(29502,'VENDEDOR',now())
                """);
            jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion") VALUES
                (?,29501,decode('295001','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                (?,29502,decode('295002','hex'),now()-interval '1 minute',now()+interval '1 hour')
                """,admin,vendedor);
            jdbc.update("""
                INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal",
                    "estado","categoriaComercial","fechaHoraAlta")
                VALUES (29511,'Cliente Conciliado','3764995511','ACTIVO','CUMPLIDOR',now())
                """);
            jdbc.update("""
                INSERT INTO "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial",
                    "diasToleranciaMora","vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                VALUES (29521,7,clock_timestamp()-interval '10 days',clock_timestamp(),29501)
                """);
            jdbc.update("""
                INSERT INTO "AJUSTE_POLITICA_CATEGORIA"
                    ("idPoliticaComercial","categoriaComercial","tipoAjuste","porcentajeAjuste")
                VALUES (29521,'CUMPLIDOR','DESCUENTO',7.50),
                       (29521,'EN_MORA','RECARGO',5.25)
                """);
            jdbc.update("""
                INSERT INTO "MOVIMIENTO_CUENTA"
                    ("idMovimientoCuenta","idCliente","numeroSecuenciaCliente",
                     "tipoMovimiento","importe","fechaHoraMovimiento","saldoResultante")
                VALUES
                (29531,29511,1,'AJUSTE_DEBITO',100.00,
                 clock_timestamp()-interval '8 days'-interval '2 hours',100.00),
                (29532,29511,2,'AJUSTE_DEBITO',50.00,
                 clock_timestamp()-interval '2 days',150.00)
                """);
        });

        // Deuda antigua impaga => EN_MORA. Base 100.105 => 100.11.
        var mora=ajusteService.aplicarAjusteComercialSegunCategoria(
                29511L,new BigDecimal("100.105"));
        assertEquals("EN_MORA",mora.categoriaComercialAplicada());
        assertEquals("RECARGO",mora.tipoAjuste());
        assertEquals(0,new BigDecimal("5.26").compareTo(mora.importeAjuste()));
        assertEquals(0,new BigDecimal("105.37").compareTo(mora.totalOriginal()));

        UUID clave=UUID.fromString("29151600-0000-4000-8000-000000000099");
        var cobro=cobroService.registrarCobro(
                vendedor,29511L,null,new BigDecimal("100.00"),"EFECTIVO",clave);
        assertEquals("CONFIRMADO",cobro.estado());
        assertEquals(0,new BigDecimal("50.00").compareTo(cobro.saldoConfirmado()));
        assertEquals("CUMPLIDOR",cobro.categoriaComercial());

        var cumplidor=ajusteService.aplicarAjusteComercialSegunCategoria(
                29511L,new BigDecimal("100.105"));
        assertEquals("CUMPLIDOR",cumplidor.categoriaComercialAplicada());
        assertEquals("DESCUENTO",cumplidor.tipoAjuste());
        assertEquals(0,new BigDecimal("7.51").compareTo(cumplidor.importeAjuste()));
        assertEquals(0,new BigDecimal("92.60").compareTo(cumplidor.totalOriginal()));

        var anulado=cobroService.anularCobroManual(admin,cobro.idCobro(),
                "Efectivo mal imputado");
        assertEquals("ANULADO",anulado.estado());
        assertEquals(0,new BigDecimal("150.00").compareTo(anulado.saldoConfirmado()));
        assertEquals("EN_MORA",anulado.categoriaComercial());
        assertEquals("EN_MORA",jdbc.queryForObject(
                "SELECT \"categoriaComercial\" FROM \"CLIENTE\" WHERE \"idCliente\"=29511",
                String.class));

        assertEquals(1,jdbc.queryForObject(
                "SELECT count(*) FROM \"COBRO\" WHERE \"idCliente\"=29511",
                Integer.class));
        assertEquals(4,jdbc.queryForObject(
                "SELECT count(*) FROM \"MOVIMIENTO_CUENTA\" WHERE \"idCliente\"=29511",
                Integer.class));
        assertEquals(2,jdbc.queryForObject(
                "SELECT count(*) FROM \"MOVIMIENTO_CAJA\"",
                Integer.class));
        assertEquals(0,jdbc.queryForObject(
                "SELECT count(*) FROM \"PEDIDO\"",
                Integer.class));
    }
}
