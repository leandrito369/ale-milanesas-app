package ar.edu.unam.alemilanesas.maestros;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/** UC19 B3: no @Transactional en test, dos conexiones y COMMIT real. */
@SpringBootTest
@Testcontainers
class PrecioProductoConcurrenciaIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ActualizarPrecioVigenteService servicio;
    static final UUID ADMIN=UUID.fromString("19110000-0000-4000-8000-000000000001");

    @Test void mismaFechaMismoProductoSoloUnPrecioPuedeConfirmar() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            jdbc.update("INSERT INTO \"UNIDAD_MEDIDA\" (\"codigoUnidad\",\"nombre\") VALUES ('UN','Unidad')");
            jdbc.update("""
                INSERT INTO "ROL" ("codigoRol","nombreMostrado")
                VALUES ('ADMINISTRADOR','Admin carrera precios')
                """);
            jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (19111,'admin_precio_concur','HASH_TEST','HABILITADO',false,now())
                """);
            jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (19111,'ADMINISTRADOR',now())
                """);
            jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,19111,decode('191101','hex'),now()-interval '1 minute',
                        now()+interval '1 hour')
                """,ADMIN);
            jdbc.update("""
                INSERT INTO "PRODUCTO" ("idProducto","nombre","nombreNormalizado",
                     "tipoProducto","codigoUnidadVenta","activo")
                VALUES (19112,'Producto carrera','producto carrera','REVENTA','UN',true)
                """);
        });
        OffsetDateTime inicio=OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var latch=new CountDownLatch(1);
            var a=pool.submit(()->{
                latch.await();
                try {
                    servicio.actualizarPrecioVigente(ADMIN,19112,
                            new BigDecimal("110.00"),inicio,null);
                    return true;
                } catch(IllegalArgumentException ex) {return false;}
            });
            var b=pool.submit(()->{
                latch.await();
                try {
                    servicio.actualizarPrecioVigente(ADMIN,19112,
                            new BigDecimal("120.00"),inicio,null);
                    return true;
                } catch(IllegalArgumentException ex) {return false;}
            });
            latch.countDown();
            assertNotEquals(a.get(25,TimeUnit.SECONDS),b.get(25,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject(
            "SELECT count(*) FROM \"PRECIO_PRODUCTO\" WHERE \"idProducto\"=19112",
            Integer.class));
        assertEquals(1,jdbc.queryForObject(
            "SELECT count(*) FROM \"AUDITORIA_PRECIO\"",Integer.class));
    }
}
