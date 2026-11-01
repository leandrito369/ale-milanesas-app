package ar.edu.unam.alemilanesas.maestros;

import java.math.BigDecimal;
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

@SpringBootTest
@Testcontainers
class InsumoConcurrenciaIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tx;
    @Autowired GestionInsumosService gestion;
    private static final UUID ADMIN=UUID.fromString("50520000-0000-4000-8000-000000000001");

    @Test void dosAltasMismoNombreNoDuplicanNiInventanMovimiento() throws Exception {
        new TransactionTemplate(tx).executeWithoutResult(status->{
            jdbc.update("""
                INSERT INTO "UNIDAD_MEDIDA" ("codigoUnidad","nombre")
                VALUES ('KG','Kilogramo')
                """);
            jdbc.update("""
                INSERT INTO "ROL" ("codigoRol","nombreMostrado")
                VALUES ('ADMINISTRADOR','Admin carrera insumo')
                """);
            jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (50521,'admin_carrera_insumo','HASH_TEST','HABILITADO',false,now())
                """);
            jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (50521,'ADMINISTRADOR',now())
                """);
            jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,50521,decode('505201','hex'),
                    now()-interval '1 minute',now()+interval '1 hour')
                """,ADMIN);
        });
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var first=pool.submit(()->{
                start.await();
                try {
                    gestion.registrarInsumo(ADMIN," Harina  integral ","KG",
                        new BigDecimal("1.000"),new BigDecimal("2.000"));
                    return true;
                }catch(IllegalArgumentException e){return false;}
            });
            var second=pool.submit(()->{
                start.await();
                try {
                    gestion.registrarInsumo(ADMIN,"harina integral","KG",
                        new BigDecimal("1.000"),new BigDecimal("2.000"));
                    return true;
                }catch(IllegalArgumentException e){return false;}
            });
            start.countDown();
            assertNotEquals(first.get(20,TimeUnit.SECONDS),
                            second.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM \"INSUMO\"",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_INSUMO\"",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM \"COSTO_INSUMO_HISTORICO\"",Integer.class));
    }
}
