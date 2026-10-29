package ar.edu.unam.alemilanesas.acceso;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * Concurrencia F07: dos Administradores intentan revocarse ADMINISTRADOR;
 * exactamente uno puede hacerlo y el segundo debe conservar último Admin.
 *
 * No se usa @Transactional en el test: cada servicio corre/COMMIT
 * en su propio hilo y PostgreSQL aplica triggers diferidos MR5.
 */
@SpringBootTest
@Testcontainers
class UltimoAdministradorConcurrenteIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired GestionarAsignacionesRolesService servicio;

    @Test
    void exactamenteUnaRevocacionConcurrentePuedeConfirmarse() throws Exception {
        var a=UUID.fromString("c123adf0-9638-4cba-b099-7bc8ae000001");
        var b=UUID.fromString("c123adf0-9638-4cba-b099-7bc8ae000002");
        // Semilla con COMMIT, visible para los dos hilos distintos.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.update("""
                    INSERT INTO "ROL" ("codigoRol","nombreMostrado") VALUES
                       ('ADMINISTRADOR','Administrador Concurrencia'),
                       ('VENDEDOR','Vendedor Concurrencia')
                    """);
            jdbc.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                      "estado","cambioCredencialPendiente","fechaHoraAlta")
                    VALUES
                      (28001,'admin_A_f07','FICTICIO','HABILITADO',false,now()),
                      (28002,'admin_B_f07','FICTICIO','HABILITADO',false,now())
                    """);
            jdbc.update("""
                    INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                    VALUES
                     (28001,'ADMINISTRADOR',now()),(28001,'VENDEDOR',now()),
                     (28002,'ADMINISTRADOR',now()),(28002,'VENDEDOR',now())
                    """);
            jdbc.update("""
                    INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario",
                      "tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                    VALUES
                     (?,28001,decode('fa2801','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                     (?,28002,decode('fa2802','hex'),now()-interval '1 minute',now()+interval '1 hour')
                    """,a,b);
        });

        var ready=new CountDownLatch(2);
        var go=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Future<Boolean> fa=executor.submit(() -> {
                ready.countDown();
                if (!go.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Timeout barrier A");
                try {
                    servicio.gestionarAsignacionesRoles(a,28001,Set.of(),Set.of("ADMINISTRADOR"));
                    return true;
                } catch (IllegalArgumentException ex) {
                    return false;
                }
            });
            Future<Boolean> fb=executor.submit(() -> {
                ready.countDown();
                if (!go.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Timeout barrier B");
                try {
                    servicio.gestionarAsignacionesRoles(b,28002,Set.of(),Set.of("ADMINISTRADOR"));
                    return true;
                } catch (IllegalArgumentException ex) {
                    return false;
                }
            });
            assertTrue(ready.await(15,TimeUnit.SECONDS));
            go.countDown();
            boolean aOk=fa.get(45,TimeUnit.SECONDS);
            boolean bOk=fb.get(45,TimeUnit.SECONDS);
            assertNotEquals(aOk,bOk,"F07 exige exactamente un Administrador restante");
        }
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO_ROL" ur
                 JOIN "USUARIO" u ON u."idUsuario"=ur."idUsuario"
                 WHERE ur."codigoRol"='ADMINISTRADOR' AND u."estado"='HABILITADO'
                """,Integer.class));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "EVENTO_ASIGNACION_ROL"
                WHERE "tipoEvento"='REVOCACION' AND "codigoRol"='ADMINISTRADOR'
                """,Integer.class));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                WHERE "fechaHoraRevocacion" IS NOT NULL
                """,Integer.class));
        assertEquals(2,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO_ROL" WHERE "codigoRol"='VENDEDOR'
                """,Integer.class));
    }
}
