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

/**
 * Integración real PostgreSQL 17.11 + MR5/F01 para UC-53.
 * Testea estado/credenciales/roles/sesiones y RD-UC53-001.
 */
@SpringBootTest
@Testcontainers
@Transactional
class CambiarContrasenaServiceIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired CambiarContrasenaService servicio;

    private static final UUID PENDIENTE = UUID.fromString("35300000-6d34-41ae-9409-588c70000001");
    private static final UUID VOLUNTARIA = UUID.fromString("35300000-6d34-41ae-9409-588c70000002");
    private static final UUID VOLUNTARIA_SEGUNDA = UUID.fromString("35300000-6d34-41ae-9409-588c70000003");
    private static final UUID DESHABILITADA = UUID.fromString("35300000-6d34-41ae-9409-588c70000004");

    @BeforeEach
    void datosFicticios() {
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol","nombreMostrado") VALUES
              ('ADMINISTRADOR','Administrador UC53'),
              ('VENDEDOR','Vendedor UC53'),
              ('PROVEEDOR','Proveedor UC53')
            """);
        jdbc.update("""
            INSERT INTO "PROVEEDOR" ("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
            VALUES (53010,'30553330001','Proveedor sintético','ACTIVO',now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                  "estado","cambioCredencialPendiente","fechaHoraAlta","fechaHoraDeshabilitacion")
            VALUES (53001,'prov_uc53',?,'HABILITADO',true,now(),NULL),
                   (53002,'admin_uc53',?,'HABILITADO',false,now(),NULL),
                   (53003,'inactivo_uc53',?,'DESHABILITADO',false,now(),now())
            """, encoder.encode("TemporalInicial_53"),
                  encoder.encode("AnteriorValida_53"),
                  encoder.encode("Deshabilitada_53"));
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
            VALUES (53001,'PROVEEDOR',now()), (53002,'ADMINISTRADOR',now()),
                   (53003,'VENDEDOR',now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_PROVEEDOR" ("idUsuario","idProveedor","fechaHoraVinculacion")
            VALUES (53001,53010,now())
            """);
        jdbc.update("""
            INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario",
                "tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
            VALUES (?,53001,decode('f95301','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,53002,decode('f95302','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,53002,decode('f95303','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,53003,decode('f95304','hex'),now()-interval '1 minute',now()+interval '1 hour')
            """,PENDIENTE,VOLUNTARIA,VOLUNTARIA_SEGUNDA,DESHABILITADA);
    }

    private String hash(long usuario) {
        return jdbc.queryForObject("""
            SELECT "credencialProtegida" FROM "USUARIO" WHERE "idUsuario"=?
            """,String.class,usuario);
    }

    private boolean pendiente(long usuario) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT "cambioCredencialPendiente" FROM "USUARIO" WHERE "idUsuario"=?
            """,Boolean.class,usuario));
    }

    private int sesionesRevocadas(long usuario) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM "SESION_USUARIO"
             WHERE "idUsuario"=? AND "fechaHoraRevocacion" IS NOT NULL
            """,Integer.class,usuario);
    }

    @Test
    void sesionRestringidaPermiteCambioSinCredencialActual() {
        assertTrue(pendiente(53001));
        servicio.cambiarContrasena(PENDIENTE,"NuevaProveedor_53","NuevaProveedor_53",null);
        assertFalse(pendiente(53001));
        assertTrue(encoder.matches("NuevaProveedor_53",hash(53001)));
        assertFalse(encoder.matches("TemporalInicial_53",hash(53001)));
        assertEquals(1,sesionesRevocadas(53001)); // trigger MR5 vigente
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "USUARIO_ROL"
            WHERE "idUsuario"=53001 AND "codigoRol"='PROVEEDOR'
            """,Integer.class));
        assertThrows(SecurityException.class, () ->
            servicio.cambiarContrasena(PENDIENTE,"Segunda_53","Segunda_53",null));
    }

    @Test
    void cambioVoluntarioExigeLaClaveActualCorrecta() {
        servicio.cambiarContrasena(VOLUNTARIA,"NuevaAdmin_53",
                "NuevaAdmin_53","AnteriorValida_53");
        assertTrue(encoder.matches("NuevaAdmin_53",hash(53002)));
        assertFalse(pendiente(53002));
        assertEquals(2,sesionesRevocadas(53002));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "USUARIO_ROL" WHERE "idUsuario"=53002
              AND "codigoRol"='ADMINISTRADOR'
            """,Integer.class));
    }

    @Test
    void actualOmitidaOEquivocadaRechazaSinActualizar() {
        String anterior=hash(53002);
        assertThrows(IllegalArgumentException.class, () ->
            servicio.cambiarContrasena(VOLUNTARIA,"NuevaA","NuevaA",null));
        assertThrows(IllegalArgumentException.class, () ->
            servicio.cambiarContrasena(VOLUNTARIA,"NuevaB","NuevaB","Incorrecta"));
        assertEquals(anterior,hash(53002));
        assertEquals(0,sesionesRevocadas(53002));
    }

    @Test
    void noReutilizarClaveTemporalNiAceptarConfirmacionIncoherente() {
        String anterior=hash(53001);
        assertThrows(IllegalArgumentException.class, () ->
            servicio.cambiarContrasena(PENDIENTE,"TemporalInicial_53","TemporalInicial_53",null));
        assertThrows(IllegalArgumentException.class, () ->
            servicio.cambiarContrasena(PENDIENTE,"Nueva","Otra",null));
        assertEquals(anterior,hash(53001));
        assertTrue(pendiente(53001));
        assertEquals(0,sesionesRevocadas(53001));
    }

    @Test
    void sesionRevocadaYExpiradaProhibenCambio() {
        String antes=hash(53002);
        jdbc.update("""
            UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=clock_timestamp()
            WHERE "idSesionUsuario"=?
            """,VOLUNTARIA);
        assertThrows(SecurityException.class, () ->
            servicio.cambiarContrasena(VOLUNTARIA,"NuevaA","NuevaA","AnteriorValida_53"));
        jdbc.update("""
            UPDATE "SESION_USUARIO" SET "fechaHoraExpiracion"=clock_timestamp()-interval '1 second'
            WHERE "idSesionUsuario"=?
            """,VOLUNTARIA_SEGUNDA);
        assertThrows(SecurityException.class, () ->
            servicio.cambiarContrasena(VOLUNTARIA_SEGUNDA,"NuevaB","NuevaB","AnteriorValida_53"));
        assertEquals(antes,hash(53002));
    }

    @Test
    void deshabilitadoYSesionDesconocidaNoPuedenCambiar() {
        String original=hash(53003);
        assertThrows(SecurityException.class, () ->
            servicio.cambiarContrasena(DESHABILITADA,"NuevaC","NuevaC","Deshabilitada_53"));
        assertThrows(SecurityException.class, () ->
            servicio.cambiarContrasena(UUID.randomUUID(),"NuevaC","NuevaC",null));
        assertThrows(SecurityException.class, () ->
            servicio.cambiarContrasena(null,"NuevaC","NuevaC",null));
        assertEquals(original,hash(53003));
    }

    @Test
    void reglaLimiteDeBytesDeBCryptNoTruncaSecretos() {
        String anterior=hash(53001);
        String demasiadoLarga="x".repeat(73);
        assertThrows(IllegalArgumentException.class, () ->
            servicio.cambiarContrasena(PENDIENTE,demasiadoLarga,demasiadoLarga,null));
        assertEquals(anterior,hash(53001));
    }
}
