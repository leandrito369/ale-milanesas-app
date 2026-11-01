package ar.edu.unam.alemilanesas.finanzas;

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

@SpringBootTest
@Testcontainers
class PoliticaCategoriaConcurrenciaIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tx;
    @Autowired ConfigurarPoliticaCategoriaComercialService servicio;
    static final UUID ADMIN=UUID.fromString("15770000-0000-4000-8000-000000000001");

    @Test void dosAltasMismaVigenciaUnaConfirmaAmbosAjustes() throws Exception {
        new TransactionTemplate(tx).executeWithoutResult(status->{
            jdbc.update("""
                INSERT INTO "ROL" ("codigoRol","nombreMostrado")
                VALUES ('ADMINISTRADOR','Admin politica competencia')
                """);
            jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (15771,'admin_competencia','HASH_TEST','HABILITADO',false,now())
                """);
            jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (15771,'ADMINISTRADOR',now())
                """);
            jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario",
                     "tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,15771,decode('157701','hex'),now()-interval '1 minute',
                    now()+interval '1 hour')
                """,ADMIN);
        });
        var cumple=new ConfigurarPoliticaCategoriaComercialService.AjusteCategoria(
                "DESCUENTO",new BigDecimal("10.00"));
        var mora=new ConfigurarPoliticaCategoriaComercialService.AjusteCategoria(
                "RECARGO",new BigDecimal("5.00"));
        OffsetDateTime inicio=OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch inicioSimultaneo=new CountDownLatch(1);
            var a=pool.submit(()->{
                inicioSimultaneo.await();
                try {
                    servicio.configurarPoliticaCategoriaComercial(
                            ADMIN,7,inicio,null,cumple,mora);
                    return true;
                } catch(IllegalArgumentException ex){return false;}
            });
            var b=pool.submit(()->{
                inicioSimultaneo.await();
                try {
                    servicio.configurarPoliticaCategoriaComercial(
                            ADMIN,7,inicio,null,cumple,mora);
                    return true;
                } catch(IllegalArgumentException ex){return false;}
            });
            inicioSimultaneo.countDown();
            assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "POLITICA_CATEGORIA_COMERCIAL"
                """,Integer.class));
        assertEquals(2,jdbc.queryForObject("""
                SELECT count(*) FROM "AJUSTE_POLITICA_CATEGORIA"
                """,Integer.class));
    }
}
