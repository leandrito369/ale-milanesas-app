package ar.edu.unam.alemilanesas.maestros;

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

/** UC-33 / BC07: consultas autorizadas, sin fuga de datos financieros. */
@SpringBootTest
@Testcontainers
@Transactional
class BuscarClienteServiceIntegrationTest {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired BuscarClienteService servicio;

    final UUID sesionAdmin = UUID.fromString("c193a12a-8bee-4d87-a847-b754bb5958e8");
    final UUID sesionVendedor = UUID.fromString("d193a12a-8bee-4d87-a847-b754bb5958e8");
    final UUID sesionProduccion = UUID.fromString("e193a12a-8bee-4d87-a847-b754bb5958e8");

    @BeforeEach
    void datosFicticios() {
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol","nombreMostrado") VALUES
              ('ADMINISTRADOR','Administrador UC33'), ('VENDEDOR','Vendedor UC33'),
              ('RESPONSABLE_PRODUCCION','Producción UC33')
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                "estado","cambioCredencialPendiente","fechaHoraAlta") VALUES
              (9201,'admin_uc33','NO_ES_HASH_REAL','HABILITADO',false,now()),
              (9202,'vendedor_uc33','NO_ES_HASH_REAL','HABILITADO',false,now()),
              (9203,'prod_uc33','NO_ES_HASH_REAL','HABILITADO',false,now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion") VALUES
             (9201,'ADMINISTRADOR',now()),(9202,'VENDEDOR',now()),
             (9203,'RESPONSABLE_PRODUCCION',now())
            """);
        jdbc.update("""
            INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario",
                "tokenProtegido","fechaHoraInicio","fechaHoraExpiracion") VALUES
                (?,9201,decode('fc2201','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                (?,9202,decode('fc2202','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                (?,9203,decode('fc2203','hex'),now()-interval '1 minute',now()+interval '1 hour')
            """, sesionAdmin, sesionVendedor, sesionProduccion);
        jdbc.update("""
            INSERT INTO "CLIENTE" ("idCliente","nombres","apellidos","telefonoPrincipal",
                "calle","numeroPuerta","localidad","zona","estado","categoriaComercial","fechaHoraAlta")
            VALUES (9301,'Ana','Pérez','3755000301','Av Siempre Viva','120',
                    'Posadas','Centro','ACTIVO','CUMPLIDOR',now()),
                   (9302,'Bruno','Ruiz','3755000302','Calle Uno','32',
                    'Posadas','Sur','INACTIVO','EN_MORA',now())
            """);
    }

    @Test
    void vendedorBuscaClienteActivoSinInformacionFinanciera() {
        List<ClienteConsulta> encontrados = servicio.buscarCliente(sesionVendedor,
             new CriterioBusquedaCliente(null,"Ana",null,"Centro","ACTIVO"));
        assertEquals(1, encontrados.size());
        ClienteConsulta cliente = encontrados.getFirst();
        assertEquals(9301L, cliente.idCliente());
        assertEquals("Ana Pérez", cliente.nombre());
        assertEquals("3755000301", cliente.telefonoPrincipal());
        assertEquals("CUMPLIDOR", cliente.categoriaComercial());
        assertTrue(cliente.direccion().contains("120"));
        assertEquals(7, ClienteConsulta.class.getRecordComponents().length);
    }

    @Test
    void administradorPuedeLocalizarClienteInactivoSinModificarlo() {
        var encontrados = servicio.buscarCliente(sesionAdmin,
             new CriterioBusquedaCliente(9302L,null,null,null,"INACTIVO"));
        assertEquals(1, encontrados.size());
        assertEquals("Bruno Ruiz", encontrados.getFirst().nombre());
        assertEquals("EN_MORA", encontrados.getFirst().categoriaComercial());
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM \"CLIENTE\" WHERE \"idCliente\"=9302 AND \"estado\"='INACTIVO'",
            Integer.class));
    }

    @Test
    void produccionNoRecibeBusquedaGeneralUc33() {
        assertThrows(SecurityException.class, () -> servicio.buscarCliente(sesionProduccion,
             new CriterioBusquedaCliente(null,null,null,null,null)));
    }

    @Test
    void sesionRevocadaYEstatusPendienteNoConcedenBusqueda() {
        jdbc.update("UPDATE \"SESION_USUARIO\" SET \"fechaHoraRevocacion\"=now() WHERE \"idSesionUsuario\"=?",sesionVendedor);
        assertThrows(SecurityException.class, () -> servicio.buscarCliente(sesionVendedor,
             new CriterioBusquedaCliente(null,null,null,null,null)));
        jdbc.update("UPDATE \"USUARIO\" SET \"cambioCredencialPendiente\"=true WHERE \"idUsuario\"=9201");
        assertThrows(SecurityException.class, () -> servicio.buscarCliente(sesionAdmin,
             new CriterioBusquedaCliente(null,null,null,null,null)));
    }

    @Test
    void filtrosInvalidosSeRechazanYNoHayCambios() {
        assertThrows(IllegalArgumentException.class, () -> servicio.buscarCliente(sesionAdmin,
             new CriterioBusquedaCliente(null,null,null,null,"SUSPENDIDO")));
        assertThrows(IllegalArgumentException.class, () -> servicio.buscarCliente(sesionAdmin,
             new CriterioBusquedaCliente(-5L,null,null,null,null)));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM \"CLIENTE\"",Integer.class));
    }
}
