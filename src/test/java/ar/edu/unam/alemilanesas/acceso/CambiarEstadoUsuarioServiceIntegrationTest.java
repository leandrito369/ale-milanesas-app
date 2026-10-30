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

/** UC55 B3: reglas F07 y RD-RP009-001 sobre PostgreSQL MR5 real. */
@SpringBootTest
@Testcontainers
@Transactional
class CambiarEstadoUsuarioServiceIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CambiarEstadoUsuarioService servicio;

    private static final UUID ADMIN = UUID.fromString("55000000-0000-4000-8000-000000000001");
    private static final UUID SELLER = UUID.fromString("55000000-0000-4000-8000-000000000002");
    private static final UUID TARGET = UUID.fromString("55000000-0000-4000-8000-000000000003");

    @BeforeEach void fixtures() {
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol","nombreMostrado") VALUES
                ('ADMINISTRADOR','Administrador UC55'),('VENDEDOR','Vendedor UC55'),
                ('REPARTIDOR','Repartidor UC55'),('PROVEEDOR','Proveedor UC55')
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                "estado","cambioCredencialPendiente","fechaHoraAlta","fechaHoraDeshabilitacion")
            VALUES (55001,'admin_uc55','HASH_SINTETICO','HABILITADO',false,now(),null),
                   (55002,'vendedor_uc55','HASH_SINTETICO','HABILITADO',false,now(),null),
                   (55003,'repartidor_uc55','HASH_SINTETICO','HABILITADO',false,now(),null),
                   (55004,'proveedor_actual_uc55','HASH_SINTETICO','HABILITADO',false,now(),null),
                   (55005,'proveedor_anterior_uc55','HASH_SINTETICO','DESHABILITADO',false,
                    now(),now()-interval '1 day'),
                   (55006,'vendedor_baja_uc55','HASH_SINTETICO','DESHABILITADO',false,
                    now(),now()-interval '1 day')
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
            VALUES (55001,'ADMINISTRADOR',now()),(55002,'VENDEDOR',now()),
                   (55003,'REPARTIDOR',now()),(55004,'PROVEEDOR',now()),
                   (55005,'PROVEEDOR',now()),(55006,'VENDEDOR',now())
            """);
        jdbc.update("""
            INSERT INTO "PROVEEDOR" ("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
            VALUES (55101,'30550005501','Proveedor sintético UC55','ACTIVO',now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_PROVEEDOR" ("idUsuario","idProveedor","fechaHoraVinculacion")
            VALUES (55004,55101,now()),(55005,55101,now())
            """);
        jdbc.update("""
            INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
               "fechaHoraInicio","fechaHoraExpiracion")
            VALUES (?,55001,decode('ee5501','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,55002,decode('ee5502','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,55003,decode('ee5503','hex'),now()-interval '1 minute',now()+interval '1 hour')
            """, ADMIN,SELLER,TARGET);
    }

    private String estado(long id) {
        return jdbc.queryForObject("SELECT \"estado\" FROM \"USUARIO\" WHERE \"idUsuario\"=?",
                String.class,id);
    }
    private int sesionesRevocadas(long usuario) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM "SESION_USUARIO"
             WHERE "idUsuario"=? AND "fechaHoraRevocacion" IS NOT NULL
            """, Integer.class, usuario);
    }

    @Test void vendedorDeshabilitadoRevocaSesionYConservaRoles() {
        var r=servicio.cambiarEstadoUsuario(ADMIN,55002,"DESHABILITADO");
        assertTrue(r.huboCambio());
        assertEquals("DESHABILITADO",estado(55002));
        assertEquals(1,sesionesRevocadas(55002)); // trigger MR5, no duplicacion Java
        assertNotNull(jdbc.queryForObject("""
            SELECT "fechaHoraDeshabilitacion" FROM "USUARIO" WHERE "idUsuario"=55002
            """,java.time.OffsetDateTime.class));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "USUARIO_ROL"
             WHERE "idUsuario"=55002 AND "codigoRol"='VENDEDOR'
            """,Integer.class));
    }

    @Test void habilitacionLimpiaFechaSinRevivirSesionAnterior() {
        var r=servicio.cambiarEstadoUsuario(ADMIN,55006,"HABILITADO");
        assertTrue(r.huboCambio());
        assertEquals("HABILITADO",estado(55006));
        assertNull(jdbc.queryForObject("""
            SELECT "fechaHoraDeshabilitacion" FROM "USUARIO" WHERE "idUsuario"=55006
            """,java.time.OffsetDateTime.class));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "USUARIO_ROL" WHERE "idUsuario"=55006
            """,Integer.class));
    }

    @Test void noOpNoRevocaSesionNiModificaMarcaTemporal() {
        var r=servicio.cambiarEstadoUsuario(ADMIN,55002,"HABILITADO");
        assertFalse(r.huboCambio());
        assertEquals(0,sesionesRevocadas(55002));
        assertNull(jdbc.queryForObject("""
            SELECT "fechaHoraDeshabilitacion" FROM "USUARIO" WHERE "idUsuario"=55002
            """,java.time.OffsetDateTime.class));
    }

    @Test void soloAdminConSesionVigenteYClaveNoPendiente() {
        assertThrows(SecurityException.class,()->servicio.cambiarEstadoUsuario(
                SELLER,55003,"DESHABILITADO"));
        assertThrows(SecurityException.class,()->servicio.cambiarEstadoUsuario(
                UUID.randomUUID(),55003,"DESHABILITADO"));
        jdbc.update("""
            UPDATE "USUARIO" SET "cambioCredencialPendiente"=true WHERE "idUsuario"=55001
            """);
        assertThrows(SecurityException.class,()->servicio.cambiarEstadoUsuario(
                ADMIN,55003,"DESHABILITADO"));
        assertEquals("HABILITADO",estado(55003));
    }

    @Test void f07NoDeshabilitaUltimoAdministrador() {
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoUsuario(
                ADMIN,55001,"DESHABILITADO"));
        assertEquals("HABILITADO",estado(55001));
        assertEquals(0,sesionesRevocadas(55001));
    }

    @Test void otroAdministradorPermiteDeshabilitarUno() {
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                "estado","cambioCredencialPendiente","fechaHoraAlta")
            VALUES (55007,'admin_segundo_uc55','HASH_SINTETICO','HABILITADO',false,now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
            VALUES (55007,'ADMINISTRADOR',now())
            """);
        assertTrue(servicio.cambiarEstadoUsuario(ADMIN,55007,"DESHABILITADO").huboCambio());
        assertEquals("HABILITADO",estado(55001));
        assertEquals("DESHABILITADO",estado(55007));
    }

    @Test void proveedorDuplicadoNoSeHabilita() {
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoUsuario(
                ADMIN,55005,"HABILITADO"));
        assertEquals("DESHABILITADO",estado(55005));
        assertEquals(2,jdbc.queryForObject("""
            SELECT count(*) FROM "USUARIO_PROVEEDOR" WHERE "idProveedor"=55101
            """,Integer.class));
    }

    @Test void proveedorPuedeHabilitarseTrasDeshabilitarCuentaAnterior() {
        servicio.cambiarEstadoUsuario(ADMIN,55004,"DESHABILITADO");
        var r=servicio.cambiarEstadoUsuario(ADMIN,55005,"HABILITADO");
        assertTrue(r.huboCambio());
        assertEquals("HABILITADO",estado(55005));
        assertEquals("DESHABILITADO",estado(55004));
    }

    @Test void repartidorConPedidoPendienteRequiereUC43AntesDeBaja() {
        prepararPedidoPendiente();
        var error=assertThrows(
                CambiarEstadoUsuarioService.RepartidorConPedidosPendientesException.class,
                ()->servicio.cambiarEstadoUsuario(ADMIN,55003,"DESHABILITADO"));
        assertEquals(List.of(55301L),error.pedidosPendientes());
        assertEquals("HABILITADO",estado(55003));
        assertEquals(0,sesionesRevocadas(55003));
    }

    @Test void asignacionCerradaPermiteDeshabilitarRepartidor() {
        prepararPedidoPendiente();
        jdbc.update("""
            UPDATE "ASIGNACION_REPARTO" SET "fechaHoraFin"=now()
             WHERE "idPedido"=55301
            """);
        assertTrue(servicio.cambiarEstadoUsuario(ADMIN,55003,"DESHABILITADO").huboCambio());
        assertEquals(1,sesionesRevocadas(55003));
    }

    @Test void entradasInvalidasYObjetivoInexistenteNoEscriben() {
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoUsuario(
                ADMIN,0L,"DESHABILITADO"));
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoUsuario(
                ADMIN,55002,"SUSPENDIDO"));
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoUsuario(
                ADMIN,55002,null));
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoUsuario(
                ADMIN,55999,"HABILITADO"));
        assertEquals("HABILITADO",estado(55002));
    }

    private void prepararPedidoPendiente() {
        jdbc.update("""
            INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal",
                "estado","categoriaComercial","fechaHoraAlta")
            VALUES (55201,'Cliente sintético UC55','3764550201','ACTIVO','CUMPLIDOR',now())
            """);
        jdbc.update("""
            INSERT INTO "POLITICA_CATEGORIA_COMERCIAL"
              ("idPoliticaComercial","diasToleranciaMora",
               "vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
            VALUES (55202,7,now()-interval '1 day',now(),55001)
            """);
        jdbc.update("""
            INSERT INTO "PEDIDO"
              ("idPedido","idCliente","idUsuarioCreador","fechaHoraCarga",
               "fechaReparto","canalOrigen","estado","categoriaComercialAplicada",
               "idPoliticaComercialAplicada","tipoAjuste","porcentajeAjuste",
               "totalOriginal","direccionEntregaCalle","direccionEntregaLocalidad")
            VALUES (55301,55201,55002,now(),current_date,'INTERNO','PENDIENTE',
                    'CUMPLIDOR',55202,'SIN_AJUSTE',0,100.00,'Calle sintética','Posadas')
            """);
        jdbc.update("""
            INSERT INTO "ASIGNACION_REPARTO" ("idPedido","numeroAsignacion",
                "idUsuarioRepartidor","idUsuarioVendedor","fechaHoraInicio")
            VALUES (55301,1,55003,55002,now()-interval '1 minute')
            """);
    }
}
