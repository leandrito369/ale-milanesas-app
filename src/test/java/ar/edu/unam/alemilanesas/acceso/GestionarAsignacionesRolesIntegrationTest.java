package ar.edu.unam.alemilanesas.acceso;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UC-18: altas, revocaciones, invariantes de Rol, auditoría y sesiones.
 * Datos falsos y base Testcontainers, sin API HTTP.
 */
@SpringBootTest
@Testcontainers
@Transactional
class GestionarAsignacionesRolesIntegrationTest {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired GestionarAsignacionesRolesService servicio;
    private final UUID sesionAdmin = UUID.fromString("c31283f0-342a-40a9-995a-f54567af1231");
    private final UUID sesionVendedor = UUID.fromString("c31283f0-342a-40a9-995a-f54567af1232");
    private final UUID sesionObjetivo = UUID.fromString("c31283f0-342a-40a9-995a-f54567af1233");

    @BeforeEach
    void preparar() {
        jdbc.update("""
                INSERT INTO "ROL" ("codigoRol","nombreMostrado") VALUES
                  ('ADMINISTRADOR','Administrador UC18'), ('VENDEDOR','Vendedor UC18'),
                  ('REPARTIDOR','Repartidor UC18'), ('PROVEEDOR','Proveedor UC18')
                """);
        jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (18001,'admin_uc18','FICTICIO','HABILITADO',false,now()),
                       (18002,'vendedor_uc18','FICTICIO','HABILITADO',false,now()),
                       (18003,'destino_uc18','FICTICIO','HABILITADO',false,now()),
                       (18004,'proveedor_uc18','FICTICIO','HABILITADO',true,now())
                """);
        jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (18001,'ADMINISTRADOR',now()),(18001,'VENDEDOR',now()),
                       (18002,'VENDEDOR',now()),(18003,'VENDEDOR',now()),
                       (18003,'REPARTIDOR',now()),(18004,'PROVEEDOR',now())
                """);
        jdbc.update("""
                INSERT INTO "PROVEEDOR" ("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                VALUES (18101,'30556667788','Comercial ficticio','ACTIVO',now())
                """);
        jdbc.update("""
                INSERT INTO "USUARIO_PROVEEDOR" ("idUsuario","idProveedor","fechaHoraVinculacion")
                VALUES (18004,18101,now())
                """);
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"
                   ("idSesionUsuario","idUsuario","tokenProtegido","fechaHoraInicio",
                    "fechaHoraExpiracion")
                VALUES (?,18001,decode('f11801','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                       (?,18002,decode('f11802','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                       (?,18003,decode('f11803','hex'),now()-interval '1 minute',now()+interval '1 hour')
                """, sesionAdmin, sesionVendedor, sesionObjetivo);
    }

    private int contarEventos(long usuario, String tipo) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM "EVENTO_ASIGNACION_ROL"
                WHERE "idUsuario"=? AND "tipoEvento"=?
                """, Integer.class, usuario, tipo);
    }

    @Test
    void revocacionValidaGeneraEventoYRevocaSesion() {
        var r = servicio.gestionarAsignacionesRoles(
                sesionAdmin,18003,Set.of(),Set.of("VENDEDOR"));
        assertTrue(r.huboCambio());
        assertEquals(Set.of("REPARTIDOR"),r.rolesVigentes());
        assertEquals(Set.of("VENDEDOR"),r.rolesRevocados());
        assertEquals(1,contarEventos(18003,"REVOCACION"));
        assertEquals(0,contarEventos(18003,"ASIGNACION"));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "EVENTO_ASIGNACION_ROL"
                WHERE "idUsuario"=18003 AND "codigoRol"='VENDEDOR'
                  AND "tipoEvento"='REVOCACION' AND "idUsuarioAdministrador"=18001
                """, Integer.class));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                WHERE "idUsuario"=18003 AND "fechaHoraRevocacion" IS NOT NULL
                """,Integer.class));
    }

    @Test
    void asignacionValidaGeneraEventoCanonico() {
        var r = servicio.gestionarAsignacionesRoles(
                sesionAdmin,18002,Set.of("REPARTIDOR"),Set.of());
        assertTrue(r.huboCambio());
        assertEquals(Set.of("VENDEDOR","REPARTIDOR"),r.rolesVigentes());
        assertEquals(1,contarEventos(18002,"ASIGNACION"));
        assertEquals(0,contarEventos(18002,"REVOCACION"));
        // MR5 revoca sesiones incluso por ASIGNACION: efecto físico documentado.
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                WHERE "idUsuario"=18002 AND "fechaHoraRevocacion" IS NOT NULL
                """,Integer.class));
    }

    @Test
    void a3NoOpNoEmiteEventosNiRevocaSesiones() {
        var r=servicio.gestionarAsignacionesRoles(
                sesionAdmin,18003,Set.of("VENDEDOR"),Set.of("ADMINISTRADOR"));
        assertFalse(r.huboCambio());
        assertEquals(0,contarEventos(18003,"ASIGNACION"));
        assertEquals(0,contarEventos(18003,"REVOCACION"));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                WHERE "idUsuario"=18003 AND "fechaHoraRevocacion" IS NULL
                """,Integer.class));
    }

    @Test
    void f07NoPuedeRevocarseElUltimoAdministradorAunqueLeQuedeVendedor() {
        assertThrows(IllegalArgumentException.class,
                ()->servicio.gestionarAsignacionesRoles(
                        sesionAdmin,18001,Set.of(),Set.of("ADMINISTRADOR")));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO_ROL"
                WHERE "idUsuario"=18001 AND "codigoRol"='ADMINISTRADOR'
                """,Integer.class));
        assertEquals(0,contarEventos(18001,"REVOCACION"));
    }

    @Test
    void noPuedeRetirarUltimoRolNiMezclarProveedor() {
        assertThrows(IllegalArgumentException.class,
                ()->servicio.gestionarAsignacionesRoles(
                        sesionAdmin,18002,Set.of(),Set.of("VENDEDOR")));
        assertThrows(IllegalArgumentException.class,
                ()->servicio.gestionarAsignacionesRoles(
                        sesionAdmin,18004,Set.of("VENDEDOR"),Set.of()));
        assertThrows(IllegalArgumentException.class,
                ()->servicio.gestionarAsignacionesRoles(
                        sesionAdmin,18004,Set.of("VENDEDOR"),Set.of("PROVEEDOR")));
        assertEquals(0,contarEventos(18004,"REVOCACION"));
    }

    @Test
    void a5VendedorNoPuedeAsignarNiRevocar() {
        assertThrows(SecurityException.class,
                ()->servicio.gestionarAsignacionesRoles(
                        sesionVendedor,18003,Set.of("ADMINISTRADOR"),Set.of()));
        assertThrows(SecurityException.class,
                ()->servicio.gestionarAsignacionesRoles(
                        UUID.randomUUID(),18003,Set.of(),Set.of("VENDEDOR")));
        assertEquals(0,contarEventos(18003,"ASIGNACION"));
        assertEquals(0,contarEventos(18003,"REVOCACION"));
    }

    @Test
    void rolNoExistenteYConflictoDeConjuntosRechazados() {
        assertThrows(IllegalArgumentException.class,
                ()->servicio.gestionarAsignacionesRoles(
                        sesionAdmin,18003,Set.of("SUPERVISOR"),Set.of()));
        assertThrows(IllegalArgumentException.class,
                ()->servicio.gestionarAsignacionesRoles(
                        sesionAdmin,18003,Set.of("VENDEDOR"),Set.of("VENDEDOR")));
        assertEquals(0,contarEventos(18003,"ASIGNACION"));
    }
}
