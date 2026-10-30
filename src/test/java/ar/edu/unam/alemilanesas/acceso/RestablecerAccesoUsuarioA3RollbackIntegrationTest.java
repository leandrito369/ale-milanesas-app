package ar.edu.unam.alemilanesas.acceso;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UC-54 A3: rollback físico en PostgreSQL MR-5/F01.
 *
 * No usa @Transactional en el test: si existiera una transacción exterior,
 * los SELECT posteriores observarían modificaciones no confirmadas y la
 * prueba no demostraría realmente un rollback.
 *
 * Se aplica UPDATE de USUARIO, se comprueba el trigger de sesiones dentro
 * de la misma transacción y se simula fallo de persistencia ANTES del COMMIT.
 * Después se consulta PostgreSQL fuera de ella para comprobar rollback real.
 */
@SpringBootTest
@Testcontainers
class RestablecerAccesoUsuarioA3RollbackIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url", postgres::getJdbcUrl);
        props.add("spring.datasource.username", postgres::getUsername);
        props.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager manager;
    @Autowired RestablecerAccesoUsuarioService service;

    private static final UUID ADMIN = UUID.fromString("a3540000-0000-4000-8000-000000000001");
    private static final UUID PROV_1 = UUID.fromString("a3540000-0000-4000-8000-000000000002");
    private static final UUID PROV_2 = UUID.fromString("a3540000-0000-4000-8000-000000000003");

    @Test
    void a3_falloDespuesDeUpdateRevierteHashIndicadorYTrigger() {
        var tx = new TransactionTemplate(manager);
        String claveAnterior = "AnteriorProveedor_A3_2026";
        String nuevaTemporal = "TemporalNoConfirmada_A3_2026";
        String hashAnterior = encoder.encode(claveAnterior);

        // Arrange: commit real de una cuenta PROVEEDOR y dos sesiones previas.
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO "ROL" ("codigoRol", "nombreMostrado") VALUES
                  ('ADMINISTRADOR', 'Administrador test A3'),
                  ('PROVEEDOR', 'Proveedor test A3')
                """);
            jdbc.update("""
                INSERT INTO "PROVEEDOR"
                    ("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                VALUES (97401,'30559997401','Proveedor sintético A3','ACTIVO',now())
                """);
            jdbc.update("""
                INSERT INTO "USUARIO"
                    ("idUsuario","nombreUsuario","credencialProtegida","estado",
                     "cambioCredencialPendiente","fechaHoraAlta")
                VALUES (97401,'admin_test_a3',?,'HABILITADO',false,now()),
                       (97402,'proveedor_test_a3',?,'HABILITADO',false,now())
                """, encoder.encode("AdminFicticia_A3"), hashAnterior);
            jdbc.update("""
                INSERT INTO "USUARIO_ROL"
                    ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (97401,'ADMINISTRADOR',now()),(97402,'PROVEEDOR',now())
                """);
            jdbc.update("""
                INSERT INTO "USUARIO_PROVEEDOR"
                    ("idUsuario","idProveedor","fechaHoraVinculacion")
                VALUES (97402,97401,now())
                """);
            jdbc.update("""
                INSERT INTO "SESION_USUARIO"
                    ("idSesionUsuario","idUsuario","tokenProtegido",
                     "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,97401,decode('e39701','hex'),
                         now()-interval '1 minute',now()+interval '1 hour'),
                       (?,97402,decode('e39702','hex'),
                         now()-interval '1 minute',now()+interval '1 hour'),
                       (?,97402,decode('e39703','hex'),
                         now()-interval '1 minute',now()+interval '1 hour')
                """, ADMIN, PROV_1, PROV_2);
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(hashAnterior, obtenerHashProveedor());
        assertFalse(indicadorPendiente());
        assertEquals(0, contarSesionesRevocadas());

        // Act: la operación real pasa A1/A2, cambia hash y activa el
        // trigger MR5. Inyectamos fallo de la operación tras el UPDATE,
        // pero antes de confirmar la transacción (alternativa A3).
        var fallo = assertThrows(DataAccessResourceFailureException.class,
                () -> tx.executeWithoutResult(status -> {
                    service.restablecerAccesoUsuario(
                            ADMIN,97402,true,nuevaTemporal);

                    // Prueba indispensable: realmente ocurrió el UPDATE y se
                    // disparó la revocación de sesiones ANTES del fallo.
                    assertTrue(encoder.matches(nuevaTemporal,obtenerHashProveedor()));
                    assertTrue(indicadorPendiente());
                    assertEquals(2, contarSesionesRevocadas());

                    throw new DataAccessResourceFailureException(
                            "Fallo sintético A3 en finalización de persistencia");
                }));
        assertFalse(fallo.getMessage().contains(nuevaTemporal));
        assertFalse(fallo.getMessage().contains(hashAnterior));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

        // Assert: consulta post-rollback en una nueva transacción/conexión.
        assertEquals(hashAnterior, obtenerHashProveedor(),
                "Se debe conservar exactamente el hash previamente confirmado");
        assertTrue(encoder.matches(claveAnterior,obtenerHashProveedor()));
        assertFalse(encoder.matches(nuevaTemporal,obtenerHashProveedor()));
        assertFalse(indicadorPendiente());
        assertEquals("HABILITADO",jdbc.queryForObject(
                "SELECT \"estado\" FROM \"USUARIO\" WHERE \"idUsuario\"=97402",String.class));
        assertEquals(0,contarSesionesRevocadas(),
                "MR5 debe revertir el efecto del trigger de sesiones");
        assertEquals(2,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                 WHERE "idUsuario"=97402 AND "fechaHoraRevocacion" IS NULL
                """,Integer.class));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO_ROL"
                 WHERE "idUsuario"=97402 AND "codigoRol"='PROVEEDOR'
                """,Integer.class));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO_PROVEEDOR"
                 WHERE "idUsuario"=97402 AND "idProveedor"=97401
                """,Integer.class));
        assertEquals(0,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                 WHERE "idUsuario"=97401 AND "fechaHoraRevocacion" IS NOT NULL
                """,Integer.class));
    }

    private String obtenerHashProveedor() {
        return jdbc.queryForObject("""
                SELECT "credencialProtegida" FROM "USUARIO"
                 WHERE "idUsuario"=97402
                """, String.class);
    }

    private boolean indicadorPendiente() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT "cambioCredencialPendiente" FROM "USUARIO"
                 WHERE "idUsuario"=97402
                """,Boolean.class));
    }

    private int contarSesionesRevocadas() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                 WHERE "idUsuario"=97402 AND "fechaHoraRevocacion" IS NOT NULL
                """,Integer.class);
    }
}
