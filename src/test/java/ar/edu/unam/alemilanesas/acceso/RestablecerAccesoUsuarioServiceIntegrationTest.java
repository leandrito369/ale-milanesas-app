package ar.edu.unam.alemilanesas.acceso;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** UC-54/REV02: RD-UC54-001 y triggers MR5 en PostgreSQL real. */
@SpringBootTest
@Testcontainers
@Transactional
class RestablecerAccesoUsuarioServiceIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired RestablecerAccesoUsuarioService service;

    static final UUID ADMIN=UUID.fromString("ef540000-3321-4e1e-b5a0-111111000001");
    static final UUID SELLER=UUID.fromString("ef540000-3321-4e1e-b5a0-111111000002");
    static final UUID P1=UUID.fromString("ef540000-3321-4e1e-b5a0-111111000003");
    static final UUID P2=UUID.fromString("ef540000-3321-4e1e-b5a0-111111000004");

    @BeforeEach void fixture() {
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol","nombreMostrado") VALUES
              ('ADMINISTRADOR','Admin UC54'),
              ('VENDEDOR','Ventas UC54'),
              ('PROVEEDOR','Proveedor UC54')
            """);
        jdbc.update("""
            INSERT INTO "PROVEEDOR" ("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
            VALUES (95401,'30559995401','Proveedor comercial A','ACTIVO',now()),
                   (95402,'30559995402','Proveedor comercial B','INACTIVO',now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
              "estado","cambioCredencialPendiente","fechaHoraAlta","fechaHoraDeshabilitacion")
            VALUES
              (95401,'admin_54',?,'HABILITADO',false,now(),NULL),
              (95402,'seller_54',?,'HABILITADO',false,now(),NULL),
              (95403,'provider_active_54',?,'HABILITADO',false,now(),NULL),
              (95404,'provider_disabled_54',?,'DESHABILITADO',false,now(),now())
            """, encoder.encode("AdminOriginal_54"),encoder.encode("SellerOriginal_54"),
                  encoder.encode("ProviderOriginal_54"),encoder.encode("DisabledOriginal_54"));
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
            VALUES (95401,'ADMINISTRADOR',now()),(95402,'VENDEDOR',now()),
                   (95403,'PROVEEDOR',now()),(95404,'PROVEEDOR',now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_PROVEEDOR" ("idUsuario","idProveedor","fechaHoraVinculacion")
            VALUES (95403,95401,now()),(95404,95402,now())
            """);
        jdbc.update("""
            INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                "fechaHoraInicio","fechaHoraExpiracion")
            VALUES
               (?,95401,decode('a95401','hex'),now()-interval '1 minute',now()+interval '1 hour'),
               (?,95402,decode('a95402','hex'),now()-interval '1 minute',now()+interval '1 hour'),
               (?,95403,decode('a95403','hex'),now()-interval '1 minute',now()+interval '1 hour'),
               (?,95403,decode('a95404','hex'),now()-interval '1 minute',now()+interval '1 hour')
            """,ADMIN,SELLER,P1,P2);
    }
    private String hash(long id) {
        return jdbc.queryForObject("SELECT \"credencialProtegida\" FROM \"USUARIO\" WHERE \"idUsuario\"=?",
                String.class,id);
    }
    private boolean pendiente(long id) {
        return jdbc.queryForObject("SELECT \"cambioCredencialPendiente\" FROM \"USUARIO\" WHERE \"idUsuario\"=?",
                Boolean.class,id);
    }
    private int revocadas(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM \"SESION_USUARIO\" WHERE \"idUsuario\"=? AND \"fechaHoraRevocacion\" IS NOT NULL",
                Integer.class,id);
    }

    @Test void adminRestableceProveedorConfirmadoYRevocaTodasSusSesiones() {
        String anterior=hash(95403);
        service.restablecerAccesoUsuario(ADMIN,95403,true,"TemporalNueva_54");
        assertFalse(anterior.equals(hash(95403)));
        assertTrue(encoder.matches("TemporalNueva_54",hash(95403)));
        assertTrue(pendiente(95403));
        assertEquals(2,revocadas(95403));
        assertEquals(0,revocadas(95401));
        assertEquals(1,jdbc.queryForObject(
            "SELECT count(*) FROM \"USUARIO_ROL\" WHERE \"idUsuario\"=95403 AND \"codigoRol\"='PROVEEDOR'",Integer.class));
        assertEquals(1,jdbc.queryForObject(
            "SELECT count(*) FROM \"USUARIO_PROVEEDOR\" WHERE \"idUsuario\"=95403 AND \"idProveedor\"=95401",Integer.class));
    }
    @Test void proveedorDeshabilitadoNoSeHabilitaAutomaticamente() {
        service.restablecerAccesoUsuario(ADMIN,95404,true,"TemporalDisabled_54");
        assertTrue(encoder.matches("TemporalDisabled_54",hash(95404)));
        assertTrue(pendiente(95404));
        assertEquals("DESHABILITADO",jdbc.queryForObject(
            "SELECT \"estado\" FROM \"USUARIO\" WHERE \"idUsuario\"=95404",String.class));
    }
    @Test void a1SinConfirmacionNoMutaNiRevoca() {
        String old=hash(95403);
        assertThrows(IllegalArgumentException.class,()->
            service.restablecerAccesoUsuario(ADMIN,95403,false,"NuevaClave_54"));
        assertThrows(IllegalArgumentException.class,()->
            service.restablecerAccesoUsuario(ADMIN,95403,null,"NuevaClave_54"));
        assertEquals(old,hash(95403));assertFalse(pendiente(95403));assertEquals(0,revocadas(95403));
    }
    @Test void a2CuentaInternaNoEsProveedor() {
        String old=hash(95402);
        assertThrows(IllegalArgumentException.class,()->
            service.restablecerAccesoUsuario(ADMIN,95402,true,"Temporal_54"));
        assertEquals(old,hash(95402));
    }
    @Test void a2RechazaProveedorConRolInternoAdicional() {
        jdbc.update("INSERT INTO \"USUARIO_ROL\" (\"idUsuario\",\"codigoRol\",\"fechaHoraAsignacion\") VALUES (95403,'VENDEDOR',now())");
        String old=hash(95403);
        assertThrows(IllegalArgumentException.class,()->
            service.restablecerAccesoUsuario(ADMIN,95403,true,"Temporal_54"));
        assertEquals(old,hash(95403));
    }
    @Test void a2RechazaVinculoInexistenteSinModificarCredencial() {
        jdbc.update("DELETE FROM \"USUARIO_PROVEEDOR\" WHERE \"idUsuario\"=95403");
        String old=hash(95403);
        assertThrows(IllegalArgumentException.class,()->
            service.restablecerAccesoUsuario(ADMIN,95403,true,"Temporal_54"));
        assertEquals(old,hash(95403));
    }
    @Test void a2RechazaSegundaCuentaHabilitadaVinculada() {
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
              "estado","cambioCredencialPendiente","fechaHoraAlta")
            VALUES (95405,'otro_prov_54','HASH_FICTICIO','HABILITADO',false,now())
            """);
        jdbc.update("INSERT INTO \"USUARIO_ROL\" (\"idUsuario\",\"codigoRol\",\"fechaHoraAsignacion\") VALUES (95405,'PROVEEDOR',now())");
        jdbc.update("INSERT INTO \"USUARIO_PROVEEDOR\" (\"idUsuario\",\"idProveedor\",\"fechaHoraVinculacion\") VALUES (95405,95401,now())");
        String old=hash(95403);
        assertThrows(IllegalArgumentException.class,()->
            service.restablecerAccesoUsuario(ADMIN,95403,true,"Temporal_54"));
        assertEquals(old,hash(95403));
    }
    @Test void a5ActorNoAutorizadoYSesionRevocadaNoRevelaCuenta() {
        assertThrows(SecurityException.class,()->
            service.restablecerAccesoUsuario(SELLER,95403,true,"Temporal_54"));
        jdbc.update("UPDATE \"SESION_USUARIO\" SET \"fechaHoraRevocacion\"=now() WHERE \"idSesionUsuario\"=?",ADMIN);
        assertThrows(SecurityException.class,()->
            service.restablecerAccesoUsuario(ADMIN,95403,true,"Temporal_54"));
        assertThrows(SecurityException.class,()->
            service.restablecerAccesoUsuario(UUID.randomUUID(),95403,true,"Temporal_54"));
    }
    @Test void temporalVaciaFueraLimiteOYALaActualSeRechaza() {
        String old=hash(95403);
        for(var clave:new String[]{"","   ","x".repeat(73),"ProviderOriginal_54"}) {
            assertThrows(IllegalArgumentException.class,()->
                service.restablecerAccesoUsuario(ADMIN,95403,true,clave));
        }
        assertEquals(old,hash(95403));
        assertEquals(0,revocadas(95403));
    }
}
