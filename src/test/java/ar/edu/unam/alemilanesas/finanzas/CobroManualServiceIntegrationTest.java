package ar.edu.unam.alemilanesas.finanzas;

import java.math.BigDecimal;
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

/** UC29 B3: probar transacciones y permisos con MR5+F01 reales, NO E2E B4. */
@SpringBootTest
@Testcontainers
@Transactional
class CobroManualServiceIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",db::getJdbcUrl);
        registry.add("spring.datasource.username",db::getUsername);
        registry.add("spring.datasource.password",db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CobroManualService servicio;

    private static final UUID ADMIN = UUID.fromString("29000000-0000-4000-8000-000000000001");
    private static final UUID VENDEDOR = UUID.fromString("29000000-0000-4000-8000-000000000002");
    private static final UUID REPARTIDOR = UUID.fromString("29000000-0000-4000-8000-000000000003");

    @BeforeEach void semilla() {
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol","nombreMostrado") VALUES
            ('ADMINISTRADOR','Administrador BC06'),
            ('VENDEDOR','Vendedor BC06'),('REPARTIDOR','Repartidor BC06')
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                "estado","cambioCredencialPendiente","fechaHoraAlta") VALUES
            (29001,'admin_bc06','HASH_TEST','HABILITADO',false,now()),
            (29002,'seller_bc06','HASH_TEST','HABILITADO',false,now()),
            (29003,'delivery_bc06','HASH_TEST','HABILITADO',false,now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
            VALUES (29001,'ADMINISTRADOR',now()),(29002,'VENDEDOR',now()),
                   (29003,'REPARTIDOR',now())
            """);
        jdbc.update("""
            INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                "fechaHoraInicio","fechaHoraExpiracion") VALUES
            (?,29001,decode('bc0601','hex'),now()-interval '1 minute',now()+interval '1 hour'),
            (?,29002,decode('bc0602','hex'),now()-interval '1 minute',now()+interval '1 hour'),
            (?,29003,decode('bc0603','hex'),now()-interval '1 minute',now()+interval '1 hour')
            """,ADMIN,VENDEDOR,REPARTIDOR);
        jdbc.update("""
            INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal",
                "estado","categoriaComercial","fechaHoraAlta")
            VALUES (29011,'Cliente BC06','3764000000','ACTIVO','CUMPLIDOR',now())
            """);
        jdbc.update("""
            INSERT INTO "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial",
                "diasToleranciaMora","vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
            VALUES (29021,7,now()-interval '10 days',now(),29001)
            """);
        jdbc.update("""
            INSERT INTO "AJUSTE_POLITICA_CATEGORIA"
                ("idPoliticaComercial","categoriaComercial","tipoAjuste","porcentajeAjuste")
            VALUES (29021,'CUMPLIDOR','SIN_AJUSTE',0),
                   (29021,'EN_MORA','SIN_AJUSTE',0)
            """);
    }

    private int n(String tabla) {
        return jdbc.queryForObject("SELECT count(*) FROM \""+tabla+"\"",Integer.class);
    }

    @Test void bc0601RepartidorNoPuedeCobrar() {
        assertThrows(SecurityException.class,()->servicio.registrarCobro(
                REPARTIDOR,29011,null,new BigDecimal("25.00"),"EFECTIVO",UUID.randomUUID()));
        assertEquals(0,n("COBRO"));
        assertEquals(0,n("MOVIMIENTO_CUENTA"));
        assertEquals(0,n("MOVIMIENTO_CAJA"));
    }

    @Test void bc0602VendedorRegistraPagoYUnIngresoAtomicos() {
        var resultado=servicio.registrarCobro(VENDEDOR,29011,null,
                new BigDecimal("25.00"),"EFECTIVO",UUID.randomUUID());
        assertEquals("CONFIRMADO",resultado.estado());
        assertEquals(0,new BigDecimal("-25.00").compareTo(resultado.saldoConfirmado()));
        assertEquals("CUMPLIDOR",resultado.categoriaComercial());
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "COBRO" WHERE "idCobro"=? AND "origen"='ADMINISTRACION'
              AND "estadoCobro"='CONFIRMADO' AND "idUsuarioRegistrador"=29002
            """,Integer.class,resultado.idCobro()));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "MOVIMIENTO_CUENTA" m
              JOIN "ORIGEN_MOVIMIENTO_CUENTA" o
                ON o."idMovimientoCuenta"=m."idMovimientoCuenta"
             WHERE o."idCobro"=? AND m."tipoMovimiento"='PAGO'
            """,Integer.class,resultado.idCobro()));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "MOVIMIENTO_CAJA"
            WHERE "idCobro"=? AND "tipoMovimiento"='INGRESO' AND "origen"='COBRO'
            """,Integer.class,resultado.idCobro()));
    }

    @Test void bc0603AdministradorRegistraYReintentoIdenticoNoDuplica() {
        UUID clave=UUID.randomUUID();
        var original=servicio.registrarCobro(ADMIN,29011,null,
                new BigDecimal("15.50"),"TRANSFERENCIA",clave);
        var repeticion=servicio.registrarCobro(ADMIN,29011,null,
                new BigDecimal("15.50"),"TRANSFERENCIA",clave);
        assertEquals(original.idCobro(),repeticion.idCobro());
        assertEquals(1,n("COBRO"));
        assertEquals(1,n("MOVIMIENTO_CUENTA"));
        assertEquals(1,n("MOVIMIENTO_CAJA"));
        assertThrows(IllegalArgumentException.class,()->servicio.registrarCobro(
                ADMIN,29011,null,new BigDecimal("30.00"),"TRANSFERENCIA",clave));
    }

    @Test void bc0604VendedorNoAnulaAunqueHayaCobroConfirmado() {
        var cobro=servicio.registrarCobro(ADMIN,29011,null,
                new BigDecimal("10.00"),"EFECTIVO",UUID.randomUUID());
        assertThrows(SecurityException.class,()->servicio.anularCobroManual(
                VENDEDOR,cobro.idCobro(),"Motivo sin permiso"));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM "ORIGEN_MOVIMIENTO_CUENTA"
                WHERE "idCobroAnulado"=?
            """,Integer.class,cobro.idCobro()));
    }

    @Test void bc0605AdministradorAnulaConDosContraasientos() {
        var cobro=servicio.registrarCobro(ADMIN,29011,null,
                new BigDecimal("12.00"),"EFECTIVO",UUID.randomUUID());
        var anulado=servicio.anularCobroManual(ADMIN,cobro.idCobro(),"Error de importe");
        assertEquals("ANULADO",anulado.estado());
        assertEquals(0,BigDecimal.ZERO.compareTo(anulado.saldoConfirmado()));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "MOVIMIENTO_CUENTA" m
              JOIN "ORIGEN_MOVIMIENTO_CUENTA" o
              ON o."idMovimientoCuenta"=m."idMovimientoCuenta"
              WHERE o."idCobroAnulado"=? AND m."tipoMovimiento"='ANULACION_COBRO'
            """,Integer.class,cobro.idCobro()));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "MOVIMIENTO_CAJA"
            WHERE "idCobroAnulado"=? AND "tipoMovimiento"='EGRESO'
            """,Integer.class,cobro.idCobro()));
        assertThrows(IllegalArgumentException.class,()->servicio.anularCobroManual(
                ADMIN,cobro.idCobro(),"Segundo intento"));
    }

    @Test void bc0606UnCobroOnlineNoSeAnulaManualmente() {
        jdbc.update("""
            INSERT INTO "COBRO" ("idCobro","idCliente","fechaHoraCobro",
                "importe","medioPago","origen","estadoCobro")
            VALUES (29199,29011,now(),50.00,'MERCADOPAGO','ONLINE','CONFIRMADO')
            """);
        assertThrows(IllegalArgumentException.class,()->servicio.anularCobroManual(
                ADMIN,29199,"No corresponde"));
        assertEquals("CONFIRMADO",jdbc.queryForObject("""
            SELECT "estadoCobro" FROM "COBRO" WHERE "idCobro"=29199
            """,String.class));
    }

    @Test void rechazoDeImportesMediosPedidoYMotivoInvalidos() {
        assertThrows(IllegalArgumentException.class,()->servicio.registrarCobro(
                ADMIN,29011,null,new BigDecimal("-1.00"),"EFECTIVO",UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->servicio.registrarCobro(
                ADMIN,29011,null,new BigDecimal("0.001"),"EFECTIVO",UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->servicio.registrarCobro(
                ADMIN,29011,null,new BigDecimal("10.00"),"MERCADOPAGO",UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->servicio.registrarCobro(
                ADMIN,29011,999999L,new BigDecimal("10.00"),"EFECTIVO",UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->servicio.anularCobroManual(
                ADMIN,1L,"   "));
        assertEquals(0,n("COBRO"));
    }

    @Test void sesionRevocadaBloqueaElCobro() {
        jdbc.update("""
            UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=now()
            WHERE "idSesionUsuario"=?
            """, VENDEDOR);
        assertThrows(SecurityException.class,()->servicio.registrarCobro(
                VENDEDOR,29011,null,new BigDecimal("12.00"),"EFECTIVO",UUID.randomUUID()));
        assertEquals(0,n("COBRO"));
    }
}
