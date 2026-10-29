package ar.edu.unam.alemilanesas.acceso;

import java.util.List;
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

/** I1-01/UC-56: búsqueda de usuarios con sesión activa y privilegio ADMIN real. */
@SpringBootTest
@Testcontainers
@Transactional
class BuscarUsuarioServiceIntegrationTest {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired BuscarUsuarioService servicio;

    final UUID sesionAdmin = UUID.fromString("a193a12a-8bee-4d87-a847-b754bb5958e8");
    final UUID sesionVendedor = UUID.fromString("b193a12a-8bee-4d87-a847-b754bb5958e8");

    @BeforeEach
    void prepararDatosFicticios() {
        jdbc.update("INSERT INTO \"ROL\" (\"codigoRol\",\"nombreMostrado\") VALUES ('ADMINISTRADOR','Administrador ficticio')");
        jdbc.update("INSERT INTO \"ROL\" (\"codigoRol\",\"nombreMostrado\") VALUES ('VENDEDOR','Vendedor ficticio')");
        jdbc.update("""
                INSERT INTO "USUARIO"
                   ("idUsuario","nombreUsuario","credencialProtegida","estado",
                    "cambioCredencialPendiente","fechaHoraAlta")
                VALUES (9101,'admin_i1','NO_ES_HASH_REAL','HABILITADO',false,now()),
                       (9102,'vendedor_i1','NO_ES_HASH_REAL','HABILITADO',false,now())
                """);
        jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (9101,'ADMINISTRADOR',now()), (9102,'VENDEDOR',now())
                """);
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"
                    ("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,9101,decode('fe0101','hex'),now() - interval '1 minute',now() + interval '1 hour')
                """, sesionAdmin);
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"
                    ("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,9102,decode('fe0102','hex'),now() - interval '1 minute',now() + interval '1 hour')
                """, sesionVendedor);
    }

    @Test
    void administradorPuedeFiltrarPorRolEstadoYNombre() {
        var resultado = servicio.buscarUsuario(sesionAdmin,
                new CriterioBusquedaUsuario("Vendedor", "HABILITADO", "VENDEDOR"));
        assertEquals(1, resultado.size());
        UsuarioConsulta item = resultado.getFirst();
        assertEquals("vendedor_i1", item.nombreUsuario());
        assertEquals(List.of("VENDEDOR"), item.roles());
        assertEquals(0, item.pedidosPendientesAsignados());
        assertNull(item.idProveedor());
        assertFalse(item.cambioCredencialPendiente());
        assertEquals(7, UsuarioConsulta.class.getRecordComponents().length);
    }

    @Test
    void vendedorNoPuedeBuscarUsuariosNiConSesionValida() {
        assertThrows(SecurityException.class, () -> servicio.buscarUsuario(sesionVendedor,
                new CriterioBusquedaUsuario(null,null,null)));
    }

    @Test
    void sesionRevocadaEsDenegadaAunqueElRolSeaAdministrador() {
        jdbc.update("""
             UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=now()
              WHERE "idSesionUsuario"=?
             """, sesionAdmin);
        assertThrows(SecurityException.class, () -> servicio.buscarUsuario(sesionAdmin,
                new CriterioBusquedaUsuario(null,null,null)));
    }

    @Test
    void credencialPendienteBloqueaUc56YFormatoInvalidoEsRechazado() {
        jdbc.update("UPDATE \"USUARIO\" SET \"cambioCredencialPendiente\"=true WHERE \"idUsuario\"=9101");
        assertThrows(SecurityException.class, () -> servicio.buscarUsuario(sesionAdmin,
                new CriterioBusquedaUsuario(null,null,null)));
        jdbc.update("UPDATE \"USUARIO\" SET \"cambioCredencialPendiente\"=false WHERE \"idUsuario\"=9101");
        assertThrows(IllegalArgumentException.class, () -> servicio.buscarUsuario(sesionAdmin,
                new CriterioBusquedaUsuario(null,"PENDIENTE",null)));
        assertThrows(IllegalArgumentException.class, () -> servicio.buscarUsuario(sesionAdmin,
                new CriterioBusquedaUsuario(null,null,"SUPERADMIN")));
    }
}
