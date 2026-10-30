package ar.edu.unam.alemilanesas.maestros;

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

/** Sin transacción de JUnit: dos inserts reales con el mismo nombre normalizado. */
@SpringBootTest
@Testcontainers
class ProductoConcurrenciaIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired GestionProductoService servicio;
    private static final UUID ADMIN=UUID.fromString("30310000-0000-4000-8000-000000000001");

    @Test void dosAltasSimultaneasNoDuplicanNombreNiIds() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO \"UNIDAD_MEDIDA\" (\"codigoUnidad\",\"nombre\") VALUES ('UN','Unidad')");
            jdbc.update("""
                INSERT INTO "ROL" ("codigoRol","nombreMostrado")
                VALUES ('ADMINISTRADOR','Admin UC30 concurrencia')
                """);
            jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (30311,'admin_prod_concur','HASH_TEST','HABILITADO',false,now())
                """);
            jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (30311,'ADMINISTRADOR',now())
                """);
            jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,30311,decode('303101','hex'),
                    now()-interval '1 minute',now()+interval '1 hour')
                """,ADMIN);
        });
        try(var pool=Executors.newFixedThreadPool(2)) {
            var iniciar=new CountDownLatch(1);
            var primero=pool.submit(()->{
                iniciar.await();
                try {
                    servicio.gestionarProducto(ADMIN,"  Milanesa  Familiar  ",null,"ELABORADO","UN");
                    return true;
                } catch(IllegalArgumentException ex){return false;}
            });
            var segundo=pool.submit(()->{
                iniciar.await();
                try {
                    servicio.gestionarProducto(ADMIN,"milanesa familiar",null,"ELABORADO","UN");
                    return true;
                } catch(IllegalArgumentException ex){return false;}
            });
            iniciar.countDown();
            assertNotEquals(primero.get(20,TimeUnit.SECONDS),
                            segundo.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject(
            "SELECT count(*) FROM \"PRODUCTO\" WHERE \"nombreNormalizado\"='milanesa familiar'",
            Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM \"PRODUCTO\"",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM \"PRECIO_PRODUCTO\"",Integer.class));
    }
}
