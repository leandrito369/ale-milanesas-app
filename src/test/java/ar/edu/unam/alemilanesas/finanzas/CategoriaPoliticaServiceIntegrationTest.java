package ar.edu.unam.alemilanesas.finanzas;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

@SpringBootTest
@Testcontainers
@Transactional
class CategoriaPoliticaServiceIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",db::getJdbcUrl);
        registry.add("spring.datasource.username",db::getUsername);
        registry.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired EvaluadorCategoriaComercialInterno evaluador;
    @Autowired AplicarAjusteComercialService aplicar;
    @Autowired ConfigurarPoliticaCategoriaComercialService configurar;
    private static final UUID ADMIN=UUID.fromString("15570000-0000-4000-8000-000000000001");
    private static final UUID VENDEDOR=UUID.fromString("15570000-0000-4000-8000-000000000002");

    @BeforeEach void datos() {
        jdbc.update("""
                INSERT INTO "ROL" ("codigoRol","nombreMostrado")
                VALUES ('ADMINISTRADOR','Administrador UC57'),('VENDEDOR','Vendedor UC57')
                """);
        jdbc.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (15701,'admin_politica','HASH_TEST','HABILITADO',false,now()),
                       (15702,'seller_politica','HASH_TEST','HABILITADO',false,now())
                """);
        jdbc.update("""
                INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES (15701,'ADMINISTRADOR',now()),(15702,'VENDEDOR',now())
                """);
        jdbc.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario",
                    "tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,15701,decode('155701','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                       (?,15702,decode('155702','hex'),now()-interval '1 minute',now()+interval '1 hour')
                """,ADMIN,VENDEDOR);
        jdbc.update("""
                INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal",
                    "estado","categoriaComercial","fechaHoraAlta")
                VALUES (15711,'Cliente base','3764005711','ACTIVO','CUMPLIDOR',now()),
                       (15712,'Cliente con deuda','3764005712','ACTIVO','CUMPLIDOR',now()),
                       (15713,'Cliente pagado','3764005713','ACTIVO','CUMPLIDOR',now()),
                       (15714,'Cliente inactivo','3764005714','INACTIVO','CUMPLIDOR',now())
                """);
    }

    private void politicaInicial(int dias,String tipoCum,String valorCum,String tipoMora,String valorMora) {
        jdbc.update("""
                INSERT INTO "POLITICA_CATEGORIA_COMERCIAL"
                    ("idPoliticaComercial","diasToleranciaMora",
                     "vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                VALUES (15721,?,now()-interval '5 days',now(),15701)
                """,dias);
        jdbc.update("""
                INSERT INTO "AJUSTE_POLITICA_CATEGORIA"
                    ("idPoliticaComercial","categoriaComercial","tipoAjuste","porcentajeAjuste")
                VALUES (15721,'CUMPLIDOR',?,?),(15721,'EN_MORA',?,?)
                """,tipoCum,new BigDecimal(valorCum),tipoMora,new BigDecimal(valorMora));
    }
    private ConfigurarPoliticaCategoriaComercialService.AjusteCategoria ajuste(String t,String p) {
        return new ConfigurarPoliticaCategoriaComercialService.AjusteCategoria(t,new BigDecimal(p));
    }
    private OffsetDateTime futuro(int dias) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(dias);
    }
    private int contar(String tabla) {
        return jdbc.queryForObject("SELECT count(*) FROM \""+tabla+"\"",Integer.class);
    }
    private void movimiento(long id,long cliente,long sec,String tipo,String monto,int dias,int horas) {
        jdbc.update("""
                INSERT INTO "MOVIMIENTO_CUENTA"
                  ("idMovimientoCuenta","idCliente","numeroSecuenciaCliente",
                   "tipoMovimiento","importe","fechaHoraMovimiento","saldoResultante")
                VALUES (?,?,?,?,?,clock_timestamp()-(?::integer * interval '1 day')
                                       -(?::integer * interval '1 hour'),?)
                """,id,cliente,sec,tipo,new BigDecimal(monto),dias,horas,new BigDecimal(monto));
    }

    @Test void uc57PrimeraVersionFuturaGeneraPoliticaYDosAjustes() {
        var creada=configurar.configurarPoliticaCategoriaComercial(ADMIN,7,futuro(2),null,
                ajuste("DESCUENTO","10.00"),ajuste("RECARGO","5.00"));
        assertNull(creada.politicaCerrada());
        assertEquals(1,contar("POLITICA_CATEGORIA_COMERCIAL"));
        assertEquals(2,contar("AJUSTE_POLITICA_CATEGORIA"));
        assertEquals(15701L,jdbc.queryForObject("""
                SELECT "idUsuarioRegistro" FROM "POLITICA_CATEGORIA_COMERCIAL"
                WHERE "idPoliticaComercial"=?
                """,Long.class,creada.idPoliticaComercial()));
        assertThrows(IllegalStateException.class,()->evaluador.reevaluar(15711));
    }

    @Test void uc57CierraSoloExtremoFuturoDePoliticaVigente() {
        politicaInicial(7,"DESCUENTO","10.00","RECARGO","4.00");
        OffsetDateTime proximo=futuro(3);
        var nueva=configurar.configurarPoliticaCategoriaComercial(ADMIN,3,proximo,null,
                ajuste("SIN_AJUSTE","0.00"),ajuste("RECARGO","6.00"));
        assertEquals(15721L,nueva.politicaCerrada());
        assertEquals(2,contar("POLITICA_CATEGORIA_COMERCIAL"));
        assertEquals(4,contar("AJUSTE_POLITICA_CATEGORIA"));
        OffsetDateTime fin=jdbc.queryForObject("""
                SELECT "vigenteHasta" FROM "POLITICA_CATEGORIA_COMERCIAL"
                WHERE "idPoliticaComercial"=15721
                """,(rs,i)->rs.getObject(1,OffsetDateTime.class));
        assertTrue(fin.isEqual(proximo));
        assertEquals(7,jdbc.queryForObject("""
                SELECT "diasToleranciaMora" FROM "POLITICA_CATEGORIA_COMERCIAL"
                WHERE "idPoliticaComercial"=15721
                """,Integer.class));
        assertEquals("CUMPLIDOR",evaluador.reevaluar(15711));
    }

    @Test void uc57RechazaSolapeYNoTocaPeriodoPrevio() {
        OffsetDateTime primero=futuro(2), fin=futuro(6);
        configurar.configurarPoliticaCategoriaComercial(ADMIN,7,primero,fin,
                ajuste("SIN_AJUSTE","0"),ajuste("RECARGO","5.00"));
        assertThrows(IllegalArgumentException.class,()->configurar.configurarPoliticaCategoriaComercial(
                ADMIN,3,futuro(3),null,ajuste("SIN_AJUSTE","0"),ajuste("RECARGO","2")));
        assertEquals(1,contar("POLITICA_CATEGORIA_COMERCIAL"));
        assertEquals(2,contar("AJUSTE_POLITICA_CATEGORIA"));
    }

    @Test void uc57ValidacionRbacyPorcentajesNoCreanFilas() {
        assertThrows(SecurityException.class,()->configurar.configurarPoliticaCategoriaComercial(
                VENDEDOR,7,futuro(2),null,ajuste("DESCUENTO","10"),ajuste("RECARGO","5")));
        assertThrows(IllegalArgumentException.class,()->configurar.configurarPoliticaCategoriaComercial(
                ADMIN,-1,futuro(2),null,ajuste("DESCUENTO","10"),ajuste("RECARGO","5")));
        assertThrows(IllegalArgumentException.class,()->configurar.configurarPoliticaCategoriaComercial(
                ADMIN,7,futuro(2),futuro(1),ajuste("DESCUENTO","10"),ajuste("RECARGO","5")));
        assertThrows(IllegalArgumentException.class,()->configurar.configurarPoliticaCategoriaComercial(
                ADMIN,7,futuro(2),null,ajuste("SIN_AJUSTE","5"),ajuste("RECARGO","5")));
        assertThrows(IllegalArgumentException.class,()->configurar.configurarPoliticaCategoriaComercial(
                ADMIN,7,futuro(2),null,ajuste("DESCUENTO","101"),ajuste("RECARGO","5")));
        assertThrows(IllegalArgumentException.class,()->configurar.configurarPoliticaCategoriaComercial(
                ADMIN,7,futuro(2),null,null,ajuste("RECARGO","5")));
        assertEquals(0,contar("POLITICA_CATEGORIA_COMERCIAL"));
    }

    @Test void uc15DeudaOchoDiasEsMoraConToleranciaSiete() {
        politicaInicial(7,"DESCUENTO","10","RECARGO","5");
        movimiento(15731,15712,1,"AJUSTE_DEBITO","100.00",8,1);
        assertEquals("EN_MORA",evaluador.reevaluar(15712));
        assertEquals("EN_MORA",jdbc.queryForObject("""
                SELECT "categoriaComercial" FROM "CLIENTE" WHERE "idCliente"=15712
                """,String.class));
        assertEquals("EN_MORA",evaluador.reevaluar(15712)); // no-op
    }

    @Test void uc15ImputaCreditosFifoYExcluyeDeudaAntiguaExtinguida() {
        politicaInicial(7,"DESCUENTO","10","RECARGO","5");
        movimiento(15731,15713,1,"AJUSTE_DEBITO","100.00",8,1);
        movimiento(15732,15713,2,"AJUSTE_DEBITO","100.00",2,1);
        movimiento(15733,15713,3,"AJUSTE_CREDITO","100.00",1,0);
        assertEquals("CUMPLIDOR",evaluador.reevaluar(15713));
        assertEquals(3,jdbc.queryForObject("""
                SELECT count(*) FROM "MOVIMIENTO_CUENTA" WHERE "idCliente"=15713
                """,Integer.class));
    }

    @Test void uc15NoInventarToleranciaNiCliente() {
        assertThrows(IllegalStateException.class,()->evaluador.reevaluar(15711));
        assertThrows(IllegalArgumentException.class,()->evaluador.reevaluar(99999));
    }

    @Test void uc16AplicaDescuentoCongelableSinEscribirPedido() {
        politicaInicial(7,"DESCUENTO","10.00","RECARGO","5.00");
        var resultado=aplicar.aplicarAjusteComercialSegunCategoria(15711,new BigDecimal("125.00"));
        assertEquals("CUMPLIDOR",resultado.categoriaComercialAplicada());
        assertEquals(15721,resultado.idPoliticaComercial());
        assertEquals("DESCUENTO",resultado.tipoAjuste());
        assertEquals(0,new BigDecimal("12.50").compareTo(resultado.importeAjuste()));
        assertEquals(0,new BigDecimal("112.50").compareTo(resultado.totalOriginal()));
        assertEquals(0,contar("PEDIDO"));
    }

    @Test void uc16MoraAplicaRecargoSinModificarMovimientoOriginal() {
        politicaInicial(7,"DESCUENTO","10","RECARGO","5");
        movimiento(15731,15712,1,"AJUSTE_DEBITO","100.00",8,1);
        var resultado=aplicar.aplicarAjusteComercialSegunCategoria(15712,new BigDecimal("200.00"));
        assertEquals("EN_MORA",resultado.categoriaComercialAplicada());
        assertEquals("RECARGO",resultado.tipoAjuste());
        assertEquals(0,new BigDecimal("10.00").compareTo(resultado.importeAjuste()));
        assertEquals(0,new BigDecimal("210.00").compareTo(resultado.totalOriginal()));
        assertEquals(1,contar("MOVIMIENTO_CUENTA"));
    }

    @Test void uc16SinAjusteYSubtotalCeroSonValidos() {
        politicaInicial(0,"SIN_AJUSTE","0","SIN_AJUSTE","0");
        var r=aplicar.aplicarAjusteComercialSegunCategoria(15711,BigDecimal.ZERO);
        assertEquals("SIN_AJUSTE",r.tipoAjuste());
        assertEquals(0,BigDecimal.ZERO.compareTo(r.totalOriginal()));
        assertEquals(0,BigDecimal.ZERO.compareTo(r.importeAjuste()));
    }

    @Test void uc16RechazaClienteInactivoSubtotalNegativoYPoliticaAusente() {
        assertThrows(IllegalArgumentException.class,()->aplicar.aplicarAjusteComercialSegunCategoria(
                15714,new BigDecimal("10")));
        assertThrows(IllegalArgumentException.class,()->aplicar.aplicarAjusteComercialSegunCategoria(
                15711,new BigDecimal("-1")));
        assertThrows(IllegalStateException.class,()->aplicar.aplicarAjusteComercialSegunCategoria(
                15711,new BigDecimal("10")));
        assertEquals(0,contar("PEDIDO"));
    }

    @Test void uc57NoReescribeCategoriaNiTotalDePedidoPrevio() {
        politicaInicial(7,"DESCUENTO","10","RECARGO","5");
        jdbc.update("""
                INSERT INTO "PEDIDO" ("idPedido","idCliente","idUsuarioCreador",
                "fechaHoraCarga","fechaReparto","canalOrigen","estado",
                "categoriaComercialAplicada","idPoliticaComercialAplicada",
                "tipoAjuste","porcentajeAjuste","totalOriginal",
                "direccionEntregaCalle","direccionEntregaLocalidad")
                VALUES (15741,15711,15702,now(),current_date,'INTERNO','PENDIENTE',
                        'CUMPLIDOR',15721,'DESCUENTO',10.00,90.00,'Calle test','Posadas')
                """);
        configurar.configurarPoliticaCategoriaComercial(ADMIN,0,futuro(2),null,
                ajuste("SIN_AJUSTE","0"),ajuste("SIN_AJUSTE","0"));
        assertEquals(15721L,jdbc.queryForObject("""
                SELECT "idPoliticaComercialAplicada" FROM "PEDIDO" WHERE "idPedido"=15741
                """,Long.class));
        assertEquals(0,new BigDecimal("90.00").compareTo(jdbc.queryForObject("""
                SELECT "totalOriginal" FROM "PEDIDO" WHERE "idPedido"=15741
                """,BigDecimal.class)));
        assertEquals("DESCUENTO",jdbc.queryForObject("""
                SELECT "tipoAjuste" FROM "PEDIDO" WHERE "idPedido"=15741
                """,String.class));
    }
}
