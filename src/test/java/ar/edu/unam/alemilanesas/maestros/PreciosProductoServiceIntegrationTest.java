package ar.edu.unam.alemilanesas.maestros;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

@SpringBootTest
@Testcontainers
@Transactional
class PreciosProductoServiceIntegrationTest {
    @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ActualizarPrecioVigenteService actualizar;
    @Autowired ConsultarHistorialPreciosService consultar;

    private static final UUID ADMIN=UUID.fromString("19000000-0000-4000-8000-000000000001");
    private static final UUID SELLER=UUID.fromString("19000000-0000-4000-8000-000000000002");

    @BeforeEach void semilla() {
        jdbc.update("""
           INSERT INTO "ROL" ("codigoRol","nombreMostrado")
           VALUES ('ADMINISTRADOR','Admin UC19'),('VENDEDOR','Seller UC19')
           """);
        jdbc.update("""
           INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
               "estado","cambioCredencialPendiente","fechaHoraAlta")
           VALUES (19001,'admin_uc19','TEST_HASH','HABILITADO',false,now()),
                  (19002,'seller_uc19','TEST_HASH','HABILITADO',false,now())
           """);
        jdbc.update("""
           INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
           VALUES (19001,'ADMINISTRADOR',now()),(19002,'VENDEDOR',now())
           """);
        jdbc.update("""
           INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
               "fechaHoraInicio","fechaHoraExpiracion")
           VALUES (?,19001,decode('190001','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                  (?,19002,decode('190002','hex'),now()-interval '1 minute',now()+interval '1 hour')
           """,ADMIN,SELLER);
        jdbc.update("INSERT INTO \"UNIDAD_MEDIDA\" (\"codigoUnidad\",\"nombre\") VALUES ('UN','Unidad')");
        jdbc.update("""
           INSERT INTO "PRODUCTO"
              ("idProducto","nombre","nombreNormalizado","tipoProducto","codigoUnidadVenta","activo")
           VALUES (19101,'Milanesa test','milanesa test','ELABORADO','UN',true),
                  (19102,'Jugo test','jugo test','REVENTA','UN',true),
                  (19103,'Producto inactivo','producto inactivo','REVENTA','UN',false)
           """);
    }
    private OffsetDateTime futuro(int dias) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(dias).withNano(0);
    }
    private int contar(String tabla) {
        return jdbc.queryForObject("SELECT count(*) FROM \""+tabla+"\"",Integer.class);
    }
    private void precioHistorico(int id,long producto,String precio,int diasPasados) {
        jdbc.update("""
            INSERT INTO "PRECIO_PRODUCTO" ("idPrecio","idProducto","precioBase",
                "vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
            VALUES (?,?,?,clock_timestamp()-(?::integer * interval '1 day'),now(),19001)
            """,id,producto,new BigDecimal(precio),diasPasados);
    }

    @Test void uc19PrimerPrecioFuturoEsProgramadoYAuditado() {
        var res=actualizar.actualizarPrecioVigente(ADMIN,19101,
                new BigDecimal("1500.1250"),futuro(2),null);
        assertNull(res.idPrecioCerrado());
        assertEquals(1,contar("PRECIO_PRODUCTO"));
        assertEquals(1,contar("AUDITORIA_PRECIO"));
        assertEquals("MODIFICACION_PROGRAMADA",jdbc.queryForObject("""
            SELECT "tipoEvento" FROM "AUDITORIA_PRECIO" WHERE "idPrecio"=?
            """,String.class,res.idPrecio()));
        assertNull(jdbc.queryForObject("""
            SELECT "precioAnterior" FROM "AUDITORIA_PRECIO" WHERE "idPrecio"=?
            """,BigDecimal.class,res.idPrecio()));
        assertEquals(19001L,jdbc.queryForObject("""
            SELECT "idUsuarioRegistro" FROM "PRECIO_PRODUCTO" WHERE "idPrecio"=?
            """,Long.class,res.idPrecio()));
        var h=consultar.consultarHistorialPrecios(ADMIN,19101,null,null);
        assertNull(h.idPrecioActual());
        assertEquals("FUTURO",h.precios().getFirst().clasificacion());
        assertEquals(1,h.auditorias().size());
    }

    @Test void uc19CierraPeriodoActualYSumaAuditoriasConDatosAntesDespues() {
        precioHistorico(19020,19101,"700.00",2);
        OffsetDateTime desde=futuro(2);
        var res=actualizar.actualizarPrecioVigente(ADMIN,19101,
                new BigDecimal("900.00"),desde,null);
        assertEquals(19020L,res.idPrecioCerrado());
        assertEquals(2,contar("PRECIO_PRODUCTO"));
        assertEquals(2,contar("AUDITORIA_PRECIO"));
        OffsetDateTime cierre=jdbc.queryForObject("""
            SELECT "vigenteHasta" FROM "PRECIO_PRODUCTO" WHERE "idPrecio"=19020
            """,(rs,i)->rs.getObject(1,OffsetDateTime.class));
        assertTrue(cierre.isEqual(desde));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "AUDITORIA_PRECIO"
             WHERE "idPrecio"=19020 AND "tipoEvento"='CIERRE_VIGENCIA'
               AND "vigenteHastaAnterior" IS NULL AND "vigenteHastaNuevo"=?
            """,Integer.class,desde));
        var h=consultar.consultarHistorialPrecios(ADMIN,19101,null,null);
        assertEquals(19020L,h.idPrecioActual());
        assertEquals("ACTUAL",h.precios().getFirst().clasificacion());
        assertEquals("FUTURO",h.precios().get(1).clasificacion());
    }

    @Test void uc19IntervaloTemporalFinitoYProgramadoNoSolapado() {
        var ini=futuro(3);
        var fin=futuro(5);
        actualizar.actualizarPrecioVigente(ADMIN,19101,
                new BigDecimal("100.00"),ini,fin);
        actualizar.actualizarPrecioVigente(ADMIN,19101,
                new BigDecimal("110.00"),futuro(6),null);
        assertEquals(2,contar("PRECIO_PRODUCTO"));
        assertEquals(2,contar("AUDITORIA_PRECIO"));
    }

    @Test void uc19RechazaSolapeSinCerrarPrecioExistente() {
        precioHistorico(19020,19101,"700.00",2);
        actualizar.actualizarPrecioVigente(ADMIN,19101,
                new BigDecimal("800.00"),futuro(4),null);
        assertThrows(IllegalArgumentException.class,()->actualizar.actualizarPrecioVigente(
                ADMIN,19101,new BigDecimal("850.00"),futuro(3),null));
        assertEquals(2,contar("PRECIO_PRODUCTO"));
        assertEquals(2,contar("AUDITORIA_PRECIO"));
        assertTrue(jdbc.queryForObject("""
            SELECT "vigenteHasta" FROM "PRECIO_PRODUCTO" WHERE "idPrecio"=19020
            """,(rs,i)->rs.getObject(1,OffsetDateTime.class)).isEqual(futuro(4)));
    }

    @Test void uc19DosInstantesDistintosEnNanosDelMismoMicrosegundoSonSolape() {
        OffsetDateTime inicio=OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
                .truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime primero=inicio.plusNanos(100);
        OffsetDateTime segundo=inicio.plusNanos(800);

        var creado=actualizar.actualizarPrecioVigente(
                ADMIN,19101,new BigDecimal("100.00"),primero,null);
        assertTrue(creado.vigenteDesde().isEqual(inicio));
        assertEquals(1,contar("PRECIO_PRODUCTO"));

        // Sin normalización PostgreSQL conserva ambos inicios como un mismo
        // TIMESTAMPTZ y el servicio intentaba cerrar el precio a su propio
        // instante inicial, violando ck_precio_producto_1.
        assertThrows(IllegalArgumentException.class,()->actualizar.actualizarPrecioVigente(
                ADMIN,19101,new BigDecimal("120.00"),segundo,null));
        assertEquals(1,contar("PRECIO_PRODUCTO"));
        assertEquals(1,contar("AUDITORIA_PRECIO"));
        var guardado=jdbc.queryForObject("""
                SELECT "vigenteDesde" FROM "PRECIO_PRODUCTO" WHERE "idPrecio"=?
                """,(rs,i)->rs.getObject(1,OffsetDateTime.class),creado.idPrecio());
        assertTrue(guardado.isEqual(inicio));

        assertThrows(IllegalArgumentException.class,()->actualizar.actualizarPrecioVigente(
                ADMIN,19102,new BigDecimal("5.00"),primero,segundo));
        assertEquals(1,contar("PRECIO_PRODUCTO"));
    }

    @Test void uc19RechazosNoCreanPrecioNiAuditoria() {
        assertThrows(IllegalArgumentException.class,()->actualizar.actualizarPrecioVigente(
                ADMIN,19101,BigDecimal.ZERO,futuro(1),null));
        assertThrows(IllegalArgumentException.class,()->actualizar.actualizarPrecioVigente(
                ADMIN,19101,new BigDecimal("1.00001"),futuro(1),null));
        assertThrows(IllegalArgumentException.class,()->actualizar.actualizarPrecioVigente(
                ADMIN,19101,new BigDecimal("1.00"),futuro(-1),null));
        assertThrows(IllegalArgumentException.class,()->actualizar.actualizarPrecioVigente(
                ADMIN,19101,new BigDecimal("1.00"),futuro(2),futuro(1)));
        assertThrows(IllegalArgumentException.class,()->actualizar.actualizarPrecioVigente(
                ADMIN,19103,new BigDecimal("1.00"),futuro(2),null));
        assertThrows(IllegalArgumentException.class,()->actualizar.actualizarPrecioVigente(
                ADMIN,99999,new BigDecimal("1.00"),futuro(2),null));
        assertEquals(0,contar("PRECIO_PRODUCTO"));
        assertEquals(0,contar("AUDITORIA_PRECIO"));
    }

    @Test void uc19Uc20RequierenAdministradorNoVendedor() {
        assertThrows(SecurityException.class,()->actualizar.actualizarPrecioVigente(
                SELLER,19101,new BigDecimal("1.00"),futuro(1),null));
        assertThrows(SecurityException.class,()->consultar.consultarHistorialPrecios(
                SELLER,19101,null,null));
        assertThrows(SecurityException.class,()->actualizar.actualizarPrecioVigente(
                UUID.randomUUID(),19101,new BigDecimal("1.00"),futuro(1),null));
        assertEquals(0,contar("PRECIO_PRODUCTO"));
    }

    @Test void uc20ConsultaSinPreciosSinInventarPrecioVigente() {
        var historial=consultar.consultarHistorialPrecios(ADMIN,19101,null,null);
        assertTrue(historial.precios().isEmpty());
        assertTrue(historial.auditorias().isEmpty());
        assertNull(historial.idPrecioActual());
    }

    @Test void uc20FiltradoPorFechasSinModificarAuditoria() {
        actualizar.actualizarPrecioVigente(ADMIN,19101,
                new BigDecimal("90.00"),futuro(3),futuro(5));
        actualizar.actualizarPrecioVigente(ADMIN,19101,
                new BigDecimal("120.00"),futuro(10),null);
        var h=consultar.consultarHistorialPrecios(ADMIN,19101,
                LocalDate.now().plusDays(3),LocalDate.now().plusDays(4));
        assertEquals(1,h.precios().size());
        assertEquals(1,h.auditorias().size());
        assertEquals(2,contar("AUDITORIA_PRECIO"));
        assertThrows(IllegalArgumentException.class,()->consultar.consultarHistorialPrecios(
                ADMIN,19101,LocalDate.now().plusDays(9),LocalDate.now().plusDays(1)));
        assertThrows(IllegalArgumentException.class,()->consultar.consultarHistorialPrecios(
                ADMIN,99999,null,null));
    }

    @Test void uc19ConservaPrecioCongeladoDeDetallePedidoAnterior() {
        precioHistorico(19020,19101,"700.00",2);
        jdbc.update("""
            INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal","estado",
                "categoriaComercial","fechaHoraAlta")
            VALUES (19130,'Cliente Precio','3764001913','ACTIVO','CUMPLIDOR',now())
            """);
        jdbc.update("""
            INSERT INTO "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial",
                "diasToleranciaMora","vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
            VALUES (19131,7,now()-interval '4 days',now(),19001)
            """);
        jdbc.update("""
            INSERT INTO "PEDIDO" ("idPedido","idCliente","idUsuarioCreador",
                "fechaHoraCarga","fechaReparto","canalOrigen","estado",
                "categoriaComercialAplicada","idPoliticaComercialAplicada","tipoAjuste",
                "porcentajeAjuste","totalOriginal","direccionEntregaCalle","direccionEntregaLocalidad")
            VALUES (19132,19130,19002,now(),current_date,'INTERNO','PENDIENTE',
                'CUMPLIDOR',19131,'SIN_AJUSTE',0,700.00,'Calle demo','Posadas')
            """);
        jdbc.update("""
            INSERT INTO "DETALLE_PEDIDO" ("idPedido","numeroDetalle","idProducto",
                "codigoUnidadVenta","cantidadOriginal","precioUnitarioOriginal",
                "porcentajePromocionOriginal")
            VALUES (19132,1,19101,'UN',1,700.00,0)
            """);
        actualizar.actualizarPrecioVigente(ADMIN,19101,
                new BigDecimal("800.00"),futuro(2),null);
        assertEquals(0,new BigDecimal("700.0000").compareTo(jdbc.queryForObject("""
            SELECT "precioUnitarioOriginal" FROM "DETALLE_PEDIDO"
             WHERE "idPedido"=19132 AND "numeroDetalle"=1
            """,BigDecimal.class)));
        assertEquals("PENDIENTE",jdbc.queryForObject("""
            SELECT "estado" FROM "PEDIDO" WHERE "idPedido"=19132
            """,String.class));
    }

    @Test void uc19RechazaSesionRevocadaAntesDeEscribir() {
        jdbc.update("""
            UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=clock_timestamp()
             WHERE "idSesionUsuario"=?
            """,ADMIN);
        assertThrows(SecurityException.class,()->actualizar.actualizarPrecioVigente(
                ADMIN,19101,new BigDecimal("50.00"),futuro(2),null));
        assertEquals(0,contar("PRECIO_PRODUCTO"));
    }
}
