package ar.edu.unam.alemilanesas.finanzas;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Una sola prueba SIN @Transactional: cada comando del servicio confirma
 * o revierte de verdad al COMMIT, ejecutando triggers diferidos MR5/F01.
 * PostgreSQL aislado de clase; solo datos sintéticos.
 */
@SpringBootTest
@Testcontainers
class CobroManualCommitRollbackIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",db::getJdbcUrl);
        registry.add("spring.datasource.username",db::getUsername);
        registry.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CobroManualService servicio;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void faltaPoliticaRevierteTresTablasYConfirmacionEjecutaContraasientos() {
        UUID sesion = UUID.fromString("29110000-0000-4000-8000-000000000001");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol","nombreMostrado")
            VALUES ('ADMINISTRADOR','Administrador BC06 commit')
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                "estado","cambioCredencialPendiente","fechaHoraAlta")
            VALUES (29111,'admin_bc06_commit','HASH_TEST','HABILITADO',false,now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
            VALUES (29111,'ADMINISTRADOR',now())
            """);
        jdbc.update("""
            INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                "fechaHoraInicio","fechaHoraExpiracion")
            VALUES (?,29111,decode('bc061111','hex'),now()-interval '1 minute',
                now()+interval '1 hour')
            """,sesion);
        jdbc.update("""
            INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal",
                "estado","categoriaComercial","fechaHoraAlta")
            VALUES (29112,'Cliente Cobro commit','3764001111','INACTIVO','CUMPLIDOR',now())
            """);
        });
        UUID clave = UUID.fromString("29110000-0000-4000-8000-000000000002");

        // Este fallo ocurre DESPUES de insertar las 4 filas de Cobro/Cuenta/Origen/Caja.
        // El cierre real de la transacción debe revertirlas por completo.
        assertThrows(IllegalStateException.class, () -> servicio.registrarCobro(
                sesion,29112,null,new BigDecimal("10.00"),"EFECTIVO",clave));
        assertEquals(0,contar("COBRO"));
        assertEquals(0,contar("MOVIMIENTO_CUENTA"));
        assertEquals(0,contar("ORIGEN_MOVIMIENTO_CUENTA"));
        assertEquals(0,contar("MOVIMIENTO_CAJA"));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
        jdbc.update("""
            INSERT INTO "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial",
                "diasToleranciaMora","vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
            VALUES (29113,7,now()-interval '1 day',now(),29111)
            """);
        jdbc.update("""
            INSERT INTO "AJUSTE_POLITICA_CATEGORIA" ("idPoliticaComercial",
                "categoriaComercial","tipoAjuste","porcentajeAjuste")
            VALUES (29113,'CUMPLIDOR','SIN_AJUSTE',0),
                   (29113,'EN_MORA','SIN_AJUSTE',0)
            """);
        });
        var cobro = servicio.registrarCobro(sesion,29112,null,
                new BigDecimal("10.00"),"EFECTIVO",clave);
        assertEquals("CONFIRMADO",cobro.estado());
        assertEquals(1,contar("COBRO"));
        assertEquals(1,contar("MOVIMIENTO_CUENTA"));
        assertEquals(1,contar("ORIGEN_MOVIMIENTO_CUENTA"));
        assertEquals(1,contar("MOVIMIENTO_CAJA"));

        var mismo = servicio.registrarCobro(sesion,29112,null,
                new BigDecimal("10.00"),"EFECTIVO",clave);
        assertEquals(cobro.idCobro(),mismo.idCobro());
        assertEquals(1,contar("MOVIMIENTO_CAJA"));

        var anulado = servicio.anularCobroManual(sesion,cobro.idCobro(),"Error de carga");
        assertEquals("ANULADO",anulado.estado());
        assertEquals(0,BigDecimal.ZERO.compareTo(anulado.saldoConfirmado()));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "MOVIMIENTO_CAJA" WHERE "idCobroAnulado"=?
            """,Integer.class,cobro.idCobro()));
        assertEquals(2,contar("MOVIMIENTO_CUENTA"));
        assertEquals(2,contar("ORIGEN_MOVIMIENTO_CUENTA"));
        assertEquals(2,contar("MOVIMIENTO_CAJA"));
        assertEquals("ANULADO",jdbc.queryForObject("""
            SELECT "estadoCobro" FROM "COBRO" WHERE "idCobro"=?
            """,String.class,cobro.idCobro()));
    }

    private int contar(String tabla) {
        return jdbc.queryForObject("SELECT count(*) FROM \""+tabla+"\"",Integer.class);
    }
}
