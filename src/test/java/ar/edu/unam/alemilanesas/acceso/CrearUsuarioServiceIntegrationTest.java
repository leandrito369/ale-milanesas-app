package ar.edu.unam.alemilanesas.acceso;

import java.util.Set;
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

/** UC17 + inclusión inicial UC18: PostgreSQL y RD 2026-10-29 reales. */
@SpringBootTest
@Testcontainers
@Transactional
class CrearUsuarioServiceIntegrationTest {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CrearUsuarioService servicio;
    @Autowired PasswordEncoder encoder;

    final UUID sesionAdmin = UUID.fromString("cd0ed92b-2606-4285-8c6f-39453791b212");
    final UUID sesionVendedor = UUID.fromString("cd0ed92b-2606-4285-8c6f-39453791b213");

    @BeforeEach
    void preparar() {
        jdbc.update("""
                INSERT INTO "ROL" ("codigoRol","nombreMostrado") VALUES
                ('ADMINISTRADOR','Administrador UC17'), ('VENDEDOR','Vendedor UC17'),
                ('REPARTIDOR','Repartidor UC17'), ('PROVEEDOR','Proveedor UC17')
                """);
        jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta") VALUES
                (91001,'admin_uc17','HASH_FICTICIO_SEMILLA','HABILITADO',false,now()),
                (91002,'seller_uc17','HASH_FICTICIO_SEMILLA','HABILITADO',false,now())
                """);
        jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (91001,'ADMINISTRADOR',now()),(91002,'VENDEDOR',now())
                """);
        jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion") VALUES
                (?,91001,decode('dfde01','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                (?,91002,decode('dfde02','hex'),now()-interval '1 minute',now()+interval '1 hour')
                """, sesionAdmin, sesionVendedor);
        jdbc.update("""
                INSERT INTO "PROVEEDOR"
                    ("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                VALUES (92001,'30556667778','Proveedor ficticio','ACTIVO',now()),
                       (92002,'30556667779','Proveedor inactivo','INACTIVO',now())
                """);
    }

    @Test
    void usuarioInternoEsOperativoYEventosAsignacionPorRol() {
        var creado = servicio.crearUsuario(sesionAdmin,"nuevo_interno_uc17",
                "Clave-Interna-Sintetica-1!", Set.of("VENDEDOR","REPARTIDOR"), null);
        assertEquals("HABILITADO",creado.estado());
        assertFalse(creado.cambioCredencialPendiente());
        assertEquals(Set.of("VENDEDOR","REPARTIDOR"),creado.roles());
        String hash = jdbc.queryForObject("""
                SELECT "credencialProtegida" FROM "USUARIO" WHERE "idUsuario"=?
                """, String.class, creado.idUsuario());
        assertNotEquals("Clave-Interna-Sintetica-1!",hash);
        assertTrue(encoder.matches("Clave-Interna-Sintetica-1!",hash));
        assertEquals(2,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO_ROL" WHERE "idUsuario"=?
                """,Integer.class,creado.idUsuario()));
        assertEquals(2,jdbc.queryForObject("""
                SELECT count(*) FROM "EVENTO_ASIGNACION_ROL"
                WHERE "idUsuario"=? AND "tipoEvento"='ASIGNACION'
                    AND "idUsuarioAdministrador"=91001
                """,Integer.class,creado.idUsuario()));
        assertEquals(0,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO_PROVEEDOR" WHERE "idUsuario"=?
                """,Integer.class,creado.idUsuario()));
    }

    @Test
    void proveedorRecibeCredencialTemporalYAsociacionExclusiva() {
        var creado = servicio.crearUsuario(sesionAdmin,"prov_uc17",
                "Clave-Temporal-Sintetica-1!",Set.of("PROVEEDOR"),92001L);
        assertTrue(creado.cambioCredencialPendiente());
        assertEquals(Set.of("PROVEEDOR"),creado.roles());
        assertEquals(92001L,jdbc.queryForObject("""
                SELECT "idProveedor" FROM "USUARIO_PROVEEDOR" WHERE "idUsuario"=?
                """,Long.class,creado.idUsuario()));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "EVENTO_ASIGNACION_ROL"
                WHERE "idUsuario"=? AND "tipoEvento"='ASIGNACION'
                """,Integer.class,creado.idUsuario()));
        assertThrows(IllegalArgumentException.class, () -> servicio.crearUsuario(sesionAdmin,
                "segundo_prov_uc17","Otra-Temporal-1!",Set.of("PROVEEDOR"),92001L));
    }

    @Test
    void rechazoPorRolInvalidoMezclaYProveedorInactivoNoCreaParcial() {
        assertThrows(IllegalArgumentException.class, () -> servicio.crearUsuario(sesionAdmin,
                "sin_rol","Clave-1!",Set.of(),null));
        assertThrows(IllegalArgumentException.class, () -> servicio.crearUsuario(sesionAdmin,
                "mixto_uc17","Clave-1!",Set.of("VENDEDOR","PROVEEDOR"),92001L));
        assertThrows(IllegalArgumentException.class, () -> servicio.crearUsuario(sesionAdmin,
                "inactivo_uc17","Clave-1!",Set.of("PROVEEDOR"),92002L));
        assertThrows(IllegalArgumentException.class, () -> servicio.crearUsuario(sesionAdmin,
                "inventado_uc17","Clave-1!",Set.of("SUPERVISOR"),null));
        assertEquals(0,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO" WHERE "nombreUsuario" IN
                ('sin_rol','mixto_uc17','inactivo_uc17','inventado_uc17')
                """,Integer.class));
    }

    @Test
    void rolVendedorNoPuedeCrearNiAsignaEventos() {
        assertThrows(SecurityException.class, () -> servicio.crearUsuario(sesionVendedor,
                "no_autorizado_uc17","Clave-1!",Set.of("VENDEDOR"),null));
        assertThrows(SecurityException.class, () -> servicio.crearUsuario(
                UUID.randomUUID(),"sin_sesion_uc17","Clave-1!",Set.of("VENDEDOR"),null));
        assertEquals(0,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO"
                WHERE "nombreUsuario" IN ('no_autorizado_uc17','sin_sesion_uc17')
                """,Integer.class));
    }

    @Test
    void duplicadoInsensitiveYTokenRevocadoSonRechazados() {
        var creado=servicio.crearUsuario(sesionAdmin,"UnicoUC17","Clave-1!",
                Set.of("VENDEDOR"),null);
        assertThrows(IllegalArgumentException.class, () -> servicio.crearUsuario(sesionAdmin,
                "unicouc17","Clave-2!",Set.of("VENDEDOR"),null));
        jdbc.update("""
                UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=now()
                 WHERE "idSesionUsuario"=?
                """,sesionAdmin);
        assertThrows(SecurityException.class, () -> servicio.crearUsuario(sesionAdmin,
                "otro_uc17","Clave-3!",Set.of("VENDEDOR"),null));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO"
                 WHERE lower("nombreUsuario")='unicouc17'
                """,Integer.class));
    }
}
