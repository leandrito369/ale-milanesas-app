package ar.edu.unam.alemilanesas.maestros;

import java.util.UUID;
import java.util.List;
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

/** I1-02 B3: contratos UC02/32/38, MR5, RBAC, OTP y F09 sin B4. */
@SpringBootTest
@Testcontainers
@Transactional
class GestionClientesServiceIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired GestionClientesService servicio;

    static final UUID ADMIN=UUID.fromString("02000000-0000-4000-8000-000000000001");
    static final UUID VENDEDOR=UUID.fromString("02000000-0000-4000-8000-000000000002");
    static final UUID REPARTIDOR=UUID.fromString("02000000-0000-4000-8000-000000000003");
    static final UUID OTP=UUID.fromString("02000000-0000-4000-8000-000000000004");
    static final UUID SESION_ONLINE=UUID.fromString("02000000-0000-4000-8000-000000000005");

    @BeforeEach void semilla() {
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol","nombreMostrado")
            VALUES ('ADMINISTRADOR','Admin UC02'),('VENDEDOR','Vendedor UC02'),
                   ('REPARTIDOR','Repartidor UC02')
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                "estado","cambioCredencialPendiente","fechaHoraAlta")
            VALUES (20001,'admin_clientes','HASH_TEST','HABILITADO',false,now()),
                   (20002,'seller_clientes','HASH_TEST','HABILITADO',false,now()),
                   (20003,'delivery_clientes','HASH_TEST','HABILITADO',false,now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
            VALUES (20001,'ADMINISTRADOR',now()),(20002,'VENDEDOR',now()),
                   (20003,'REPARTIDOR',now())
            """);
        jdbc.update("""
            INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                "fechaHoraInicio","fechaHoraExpiracion")
            VALUES (?,20001,decode('020001','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,20002,decode('020002','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,20003,decode('020003','hex'),now()-interval '1 minute',now()+interval '1 hour')
            """,ADMIN,VENDEDOR,REPARTIDOR);
    }

    private GestionClientesService.DatosCliente dto(String nombres,String apellidos,String tel) {
        return new GestionClientesService.DatosCliente(
            nombres,apellidos,tel,"Calle San Martín","123",null,null,
            "Apóstoles","Misiones",null,"Centro",(short)3);
    }
    private GestionClientesService.DatosCliente distintoTelefono(String telefono) {
        return dto("Ana","López",telefono);
    }
    private int contar(String tabla) {
        return jdbc.queryForObject("SELECT count(*) FROM \""+tabla+"\"",Integer.class);
    }
    private void otp(long cliente) {
        jdbc.update("""
            INSERT INTO "DESAFIO_AUTENTICACION_CLIENTE"
                ("idDesafio","idCliente","telefonoDestino","codigoProtegido",
                 "fechaHoraCreacion","fechaHoraExpiracion","intentosFallidos")
            VALUES (?,?, '3764000001',decode('020004','hex'),
                    now()-interval '1 minute',now()+interval '1 hour',0)
            """,OTP,cliente);
        jdbc.update("""
            INSERT INTO "SESION_CLIENTE_ONLINE"
              ("idSesionOnline","idDesafio","tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
            VALUES (?,?,decode('020005','hex'),now()-interval '1 minute',now()+interval '1 hour')
            """,SESION_ONLINE,OTP);
    }
    @Test void uc02AltaInicializaEstadoCategoriaYSinHistorial() {
        var r=servicio.registrarCliente(VENDEDOR,dto("Ana","López","3764000001"));
        assertEquals("ACTIVO",r.estado());
        assertEquals("CUMPLIDOR",r.categoriaComercial());
        assertTrue(r.huboCambio());
        assertEquals(1,contar("CLIENTE"));
        assertEquals(0,contar("HISTORIAL_ESTADO_CLIENTE"));
    }

    @Test void uc02DuplicadoActivoEInactivoSeRechaza() {
        long id=servicio.registrarCliente(ADMIN,distintoTelefono("3764000001")).idCliente();
        assertThrows(IllegalArgumentException.class,()->servicio.registrarCliente(
                VENDEDOR,dto(" ana ","LÓPEZ","(376) 400-0001")));
        servicio.cambiarEstadoCliente(ADMIN,id,"INACTIVO","Prueba");
        assertThrows(IllegalArgumentException.class,()->servicio.registrarCliente(
                VENDEDOR,distintoTelefono("3764000001")));
        assertEquals(1,contar("CLIENTE"));
    }

    @Test void uc02TelefonoCompartidoSoloGeneraAdvertencia() {
        long primero=servicio.registrarCliente(VENDEDOR,distintoTelefono("3764000001")).idCliente();
        var segundo=servicio.registrarCliente(VENDEDOR,dto("Bruno","Gómez","3764000001"));
        assertEquals(List.of(primero),segundo.advertenciasParciales());
        assertEquals(2,contar("CLIENTE"));
    }

    @Test void uc02CamposInvalidosYRolNoAutorizado() {
        assertThrows(SecurityException.class,()->servicio.registrarCliente(
                REPARTIDOR,distintoTelefono("3764000001")));
        assertThrows(IllegalArgumentException.class,()->servicio.registrarCliente(
                VENDEDOR,dto("Ana","López","123")));
        assertThrows(IllegalArgumentException.class,()->servicio.registrarCliente(
                VENDEDOR,new GestionClientesService.DatosCliente("Ana",null,"3764000001",
                    null,null,null,null,"Apóstoles","Misiones",null,null,null)));
        assertEquals(0,contar("CLIENTE"));
    }

    @Test void uc32SoloVendedorModificaYPreservaCategoriaYEstado() {
        long id=servicio.registrarCliente(VENDEDOR,distintoTelefono("3764000001")).idCliente();
        assertThrows(SecurityException.class,()->servicio.modificarCliente(
                ADMIN,id,dto("Ana","Nueva","3764000001")));
        var r=servicio.modificarCliente(VENDEDOR,id,dto("Ana","Nueva","3764000001"));
        assertTrue(r.huboCambio());
        assertEquals("ACTIVO",r.estado());
        assertEquals("CUMPLIDOR",r.categoriaComercial());
        assertEquals("Nueva",jdbc.queryForObject(
                "SELECT \"apellidos\" FROM \"CLIENTE\" WHERE \"idCliente\"=?",String.class,id));
        assertEquals(0,contar("HISTORIAL_ESTADO_CLIENTE"));
    }

    @Test void uc32NoOpNoInvalidaOtpNiSesionOnline() {
        var datos=distintoTelefono("3764000001");
        long id=servicio.registrarCliente(VENDEDOR,datos).idCliente();
        otp(id);
        var r=servicio.modificarCliente(VENDEDOR,id,datos);
        assertFalse(r.huboCambio());
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM "DESAFIO_AUTENTICACION_CLIENTE"
            WHERE "fechaHoraBloqueo" IS NOT NULL
            """,Integer.class));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM "SESION_CLIENTE_ONLINE"
            WHERE "fechaHoraRevocacion" IS NOT NULL
            """,Integer.class));
    }

    @Test void uc32CambioTelefonoInvalidaOtpPendientePeroNoSesionExistente() {
        long id=servicio.registrarCliente(VENDEDOR,distintoTelefono("3764000001")).idCliente();
        otp(id);
        servicio.modificarCliente(VENDEDOR,id,distintoTelefono("3764000002"));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "DESAFIO_AUTENTICACION_CLIENTE"
            WHERE "idCliente"=? AND "fechaHoraBloqueo" IS NOT NULL
            """,Integer.class,id));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM "SESION_CLIENTE_ONLINE"
            WHERE "fechaHoraRevocacion" IS NOT NULL
            """,Integer.class));
    }

    @Test void uc32NoPuedeDuplicarNiModificarClienteInexistente() {
        long a=servicio.registrarCliente(VENDEDOR,distintoTelefono("3764000001")).idCliente();
        long b=servicio.registrarCliente(VENDEDOR,dto("Bruno","Gómez","3764000002")).idCliente();
        assertThrows(IllegalArgumentException.class,()->servicio.modificarCliente(
                VENDEDOR,b,distintoTelefono("3764000001")));
        assertThrows(IllegalArgumentException.class,()->servicio.modificarCliente(
                VENDEDOR,999999,distintoTelefono("3764000001")));
        assertEquals("Bruno",jdbc.queryForObject(
                "SELECT \"nombres\" FROM \"CLIENTE\" WHERE \"idCliente\"=?",String.class,b));
    }

    @Test void uc38SoloAdminYNoOpNoCreanHistorial() {
        long id=servicio.registrarCliente(VENDEDOR,distintoTelefono("3764000001")).idCliente();
        assertThrows(SecurityException.class,()->servicio.cambiarEstadoCliente(
                VENDEDOR,id,"INACTIVO","No autorizado"));
        var r=servicio.cambiarEstadoCliente(ADMIN,id,"ACTIVO","Ninguno");
        assertFalse(r.huboCambio());
        assertEquals(0,contar("HISTORIAL_ESTADO_CLIENTE"));
    }

    @Test void uc38BajaRevocaOtpYSesionYRegistraHistorial() {
        long id=servicio.registrarCliente(VENDEDOR,distintoTelefono("3764000001")).idCliente();
        otp(id);
        var r=servicio.cambiarEstadoCliente(ADMIN,id,"INACTIVO","Falta de contacto");
        assertEquals(1,r.numeroCambio());
        assertEquals("INACTIVO",r.estado());
        assertEquals(1,contar("HISTORIAL_ESTADO_CLIENTE"));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "DESAFIO_AUTENTICACION_CLIENTE"
            WHERE "fechaHoraBloqueo" IS NOT NULL
            """,Integer.class));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "SESION_CLIENTE_ONLINE"
            WHERE "fechaHoraRevocacion" IS NOT NULL
            """,Integer.class));
    }

    @Test void uc38ReactivarAgregaSegundoHistorialConservandoCuenta() {
        long id=servicio.registrarCliente(VENDEDOR,distintoTelefono("3764000001")).idCliente();
        servicio.cambiarEstadoCliente(ADMIN,id,"INACTIVO","Temporal");
        var r=servicio.cambiarEstadoCliente(ADMIN,id,"ACTIVO","Regularizado");
        assertEquals(2,r.numeroCambio());
        assertEquals("ACTIVO",r.estado());
        assertEquals(2,contar("HISTORIAL_ESTADO_CLIENTE"));
        assertEquals(1,contar("CLIENTE"));
    }

    @Test void uc38ReactivarConDomicilioInvalidoRechazaSinHistorialNuevo() {
        long id=servicio.registrarCliente(VENDEDOR,distintoTelefono("3764000001")).idCliente();
        servicio.cambiarEstadoCliente(ADMIN,id,"INACTIVO","Pendiente");
        jdbc.update("UPDATE \"CLIENTE\" SET \"calle\"=NULL WHERE \"idCliente\"=?",id);
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoCliente(
                ADMIN,id,"ACTIVO","Reintento"));
        assertEquals(1,contar("HISTORIAL_ESTADO_CLIENTE"));
    }

    @Test void uc38EstadoInvalidoOClienteAusenteNoEscribe() {
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoCliente(
                ADMIN,-1,"INACTIVO",null));
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoCliente(
                ADMIN,9999,"SUSPENDIDO",null));
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoCliente(
                ADMIN,9999,"INACTIVO",null));
        assertEquals(0,contar("HISTORIAL_ESTADO_CLIENTE"));
    }
}
