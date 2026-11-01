package ar.edu.unam.alemilanesas.acceso;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
 * UC55 F07: dos transacciones independientes compiten para deshabilitar
 * administradores entre sí. No @Transactional en test: se verifican COMMIT,
 * constraint triggers diferidos y revocación real del usuario perdedor.
 */
@SpringBootTest
@Testcontainers
class CambiarEstadoUsuarioConcurrenteIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",db::getJdbcUrl);
        registry.add("spring.datasource.username",db::getUsername);
        registry.add("spring.datasource.password",db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CambiarEstadoUsuarioService servicio;
    @Autowired PlatformTransactionManager transactions;
    private static final UUID A=UUID.fromString("55110000-0000-4000-8000-000000000001");
    private static final UUID B=UUID.fromString("55110000-0000-4000-8000-000000000002");

    @Test void dosAdministradoresNoPuedenDeshabilitarseMutuamente() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO "ROL" ("codigoRol","nombreMostrado")
                VALUES ('ADMINISTRADOR','Administrador concurrencia UC55')
                """);
            jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (55111,'admin_a_uc55','HASH_SINTETICO','HABILITADO',false,now()),
                       (55112,'admin_b_uc55','HASH_SINTETICO','HABILITADO',false,now())
                """);
            jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (55111,'ADMINISTRADOR',now()),(55112,'ADMINISTRADOR',now())
                """);
            jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,55111,decode('ee5511','hex'),now()-interval '1 minute',
                        now()+interval '1 hour'),
                       (?,55112,decode('ee5512','hex'),now()-interval '1 minute',
                        now()+interval '1 hour')
                """,A,B);
        });
        try (var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch inicio=new CountDownLatch(1);
            var a=pool.submit(() -> {
                inicio.await();
                try {
                    servicio.cambiarEstadoUsuario(A,55112,"DESHABILITADO");
                    return true;
                } catch (SecurityException | IllegalArgumentException ex) {
                    return false;
                }
            });
            var b=pool.submit(() -> {
                inicio.await();
                try {
                    servicio.cambiarEstadoUsuario(B,55111,"DESHABILITADO");
                    return true;
                } catch (SecurityException | IllegalArgumentException ex) {
                    return false;
                }
            });
            inicio.countDown();
            boolean primero=a.get(20,TimeUnit.SECONDS);
            boolean segundo=b.get(20,TimeUnit.SECONDS);
            assertNotEquals(primero,segundo,
                    "Solo una baja puede confirmar; la otra se rechaza al revalidar");
        }
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "USUARIO" u JOIN "USUARIO_ROL" r
              ON r."idUsuario"=u."idUsuario"
             WHERE r."codigoRol"='ADMINISTRADOR' AND u."estado"='HABILITADO'
            """,Integer.class));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "SESION_USUARIO"
             WHERE "fechaHoraRevocacion" IS NOT NULL
            """,Integer.class));
    }
}
