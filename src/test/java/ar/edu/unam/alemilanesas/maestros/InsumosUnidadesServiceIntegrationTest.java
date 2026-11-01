package ar.edu.unam.alemilanesas.maestros;

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

/** UC50/51/52/36 B3 contra MR5/F01 real, sin activar UC42 ni UC10. */
@SpringBootTest
@Testcontainers
@Transactional
class InsumosUnidadesServiceIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource static void props(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",db::getJdbcUrl);
        registry.add("spring.datasource.username",db::getUsername);
        registry.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired GestionInsumosService gestion;
    @Autowired ConsultarInsumosUnidadesService consulta;

    static final UUID ADMIN=UUID.fromString("50510000-0000-4000-8000-000000000001");
    static final UUID PROD=UUID.fromString("50510000-0000-4000-8000-000000000002");
    static final UUID SELLER=UUID.fromString("50510000-0000-4000-8000-000000000003");

    @BeforeEach void sembrar(){
        jdbc.update("""
            INSERT INTO "UNIDAD_MEDIDA" ("codigoUnidad","nombre")
            VALUES ('KG','Kilogramo'),('UN','Unidad'),('G','Gramo')
            """);
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol","nombreMostrado")
            VALUES ('ADMINISTRADOR','Admin Insumo'),
                   ('RESPONSABLE_PRODUCCION','Produccion Insumo'),
                   ('VENDEDOR','Vendedor Insumo')
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                "estado","cambioCredencialPendiente","fechaHoraAlta")
            VALUES (50511,'admin_i106','HASH_TEST','HABILITADO',false,now()),
                   (50512,'produccion_i106','HASH_TEST','HABILITADO',false,now()),
                   (50513,'vendedor_i106','HASH_TEST','HABILITADO',false,now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
            VALUES (50511,'ADMINISTRADOR',now()),(50512,'RESPONSABLE_PRODUCCION',now()),
                   (50513,'VENDEDOR',now())
            """);
        jdbc.update("""
            INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                "fechaHoraInicio","fechaHoraExpiracion")
            VALUES (?,50511,decode('505101','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,50512,decode('505102','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,50513,decode('505103','hex'),now()-interval '1 minute',now()+interval '1 hour')
            """,ADMIN,PROD,SELLER);
    }
    private long alta(String nombre){
        return gestion.registrarInsumo(ADMIN,nombre,"KG",
                new BigDecimal("2.000"),new BigDecimal("5.000")).idInsumo();
    }
    private int contar(String tabla){
        return jdbc.queryForObject("SELECT count(*) FROM \""+tabla+"\"",Integer.class);
    }
    private void historialReceta(long idInsumo) {
        jdbc.update("""
            INSERT INTO "PRODUCTO" ("idProducto","nombre","nombreNormalizado",
                "tipoProducto","codigoUnidadVenta","activo")
            VALUES (50521,'Producto con receta','producto con receta','ELABORADO','UN',true)
            """);
        jdbc.update("INSERT INTO \"RECETA\" (\"idReceta\",\"idProducto\") VALUES (50522,50521)");
        jdbc.update("""
            INSERT INTO "VERSION_RECETA" ("idReceta","numeroVersion","vigenteDesde",
                 "cantidadRendimientoBase","codigoUnidadRendimiento")
            VALUES (50522,1,now()-interval '1 day',10,'UN')
            """);
        jdbc.update("""
            INSERT INTO "DETALLE_VERSION_RECETA" ("idReceta","numeroVersion",
                "idInsumo","cantidadNecesaria","codigoUnidadCantidad")
            VALUES (50522,1,?,1.500,'KG')
            """,idInsumo);
    }

    @Test void uc50AltaStockCeroYSinSaldoInicial(){
        var a=gestion.registrarInsumo(ADMIN,"Carne vacuna","KG",
                new BigDecimal("2.000"),new BigDecimal("5.000"));
        assertTrue(a.activo());
        assertEquals(0,a.stockActual().compareTo(BigDecimal.ZERO));
        assertEquals(1,contar("INSUMO"));
        assertEquals(0,contar("MOVIMIENTO_INSUMO"));
        assertEquals(0,contar("COSTO_INSUMO_HISTORICO"));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM "INSUMO"
             WHERE "idInsumo"=? AND NOT "automatizacionCompraActiva"
               AND "stockActual"=0 AND "umbralMinimo"=2.000
               AND "nivelObjetivoReposicion"=5.000
            """,Integer.class,a.idInsumo()));
    }

    @Test void uc50NoPermitirDuplicadoDeNombreActivoNiInactivo(){
        long id=alta("  Carne    Molida  ");
        assertThrows(IllegalArgumentException.class,()->gestion.registrarInsumo(
            ADMIN,"carne molida","KG",BigDecimal.ZERO,new BigDecimal("1")));
        gestion.cambiarEstadoInsumo(ADMIN,id,false);
        assertThrows(IllegalArgumentException.class,()->gestion.registrarInsumo(
            ADMIN,"CARNE MOLIDA","UN",BigDecimal.ZERO,new BigDecimal("1")));
        assertEquals(1,contar("INSUMO"));
    }

    @Test void uc50ValidaUnidadUmbralObjetivoYAutorizacion(){
        assertThrows(SecurityException.class,()->gestion.registrarInsumo(
            PROD,"Carne","KG",BigDecimal.ZERO,new BigDecimal("1")));
        assertThrows(SecurityException.class,()->gestion.registrarInsumo(
            SELLER,"Carne","KG",BigDecimal.ZERO,new BigDecimal("1")));
        assertThrows(IllegalArgumentException.class,()->gestion.registrarInsumo(
            ADMIN,"Carne","SIN_UNIDAD",BigDecimal.ZERO,new BigDecimal("1")));
        assertThrows(IllegalArgumentException.class,()->gestion.registrarInsumo(
            ADMIN,"Carne","KG",new BigDecimal("1.000"),new BigDecimal("1.000")));
        assertThrows(IllegalArgumentException.class,()->gestion.registrarInsumo(
            ADMIN,"Carne","KG",new BigDecimal("-1"),new BigDecimal("2")));
        assertThrows(IllegalArgumentException.class,()->gestion.registrarInsumo(
            ADMIN,"Carne","KG",new BigDecimal("1.0001"),new BigDecimal("2")));
        assertEquals(0,contar("INSUMO"));
        assertEquals(0,contar("MOVIMIENTO_INSUMO"));
    }

    @Test void uc50NoInventaParametrosInicialesAnteEntradaFaltante(){
        assertThrows(IllegalArgumentException.class,()->gestion.registrarInsumo(
            ADMIN,"Perejil","KG",null,new BigDecimal("3.000")));
        assertThrows(IllegalArgumentException.class,()->gestion.registrarInsumo(
            ADMIN,"Perejil","KG",BigDecimal.ZERO,null));
        assertThrows(IllegalArgumentException.class,()->gestion.registrarInsumo(
            ADMIN,"  ","KG",BigDecimal.ZERO,new BigDecimal("3.000")));
        assertEquals(0,contar("INSUMO"));
        assertEquals(0,contar("MOVIMIENTO_INSUMO"));
        assertEquals(0,contar("COSTO_INSUMO_HISTORICO"));
    }

    @Test void uc36AdminYProduccionVenActivosEInactivosSinMutar(){
        long carne=alta("Carne de res");
        long pan=alta("Pan rallado");
        gestion.cambiarEstadoInsumo(ADMIN,pan,false);
        var activos=consulta.buscarInsumo(PROD,
            new ConsultarInsumosUnidadesService.FiltroInsumo(null,"carne",true));
        assertEquals(1,activos.size());
        assertEquals(carne,activos.getFirst().idInsumo());
        assertEquals(0,BigDecimal.ZERO.compareTo(activos.getFirst().stockActual()));
        var inactivos=consulta.buscarInsumo(ADMIN,
            new ConsultarInsumosUnidadesService.FiltroInsumo(null,null,false));
        assertEquals(1,inactivos.size());
        assertEquals(pan,inactivos.getFirst().idInsumo());
        assertEquals(0,contar("MOVIMIENTO_INSUMO"));
    }

    @Test void uc36VendedorDenegadoYFiltrosInvalidosNoEscriben(){
        alta("Aceite");
        assertThrows(SecurityException.class,()->consulta.buscarInsumo(SELLER,
            new ConsultarInsumosUnidadesService.FiltroInsumo(null,null,null)));
        assertThrows(IllegalArgumentException.class,()->consulta.buscarInsumo(ADMIN,
            new ConsultarInsumosUnidadesService.FiltroInsumo(-1L,null,null)));
        assertThrows(IllegalArgumentException.class,()->consulta.buscarInsumo(ADMIN,null));
        assertTrue(consulta.buscarInsumo(ADMIN,
            new ConsultarInsumosUnidadesService.FiltroInsumo(null,"Inexistente",null)).isEmpty());
        assertEquals(1,contar("INSUMO"));
    }

    @Test void unidadesCatalogoSoloLecturaSinAltaInventada(){
        assertEquals(3,consulta.listarUnidades(ADMIN).size());
        assertEquals("G",consulta.listarUnidades(PROD).getFirst().codigoUnidad());
        assertThrows(SecurityException.class,()->consulta.listarUnidades(SELLER));
        assertEquals(3,contar("UNIDAD_MEDIDA"));
    }

    @Test void uc51ModificaNombreYUnidadSinHistoriaPreservandoStock(){
        long id=alta("Papas");
        var mod=gestion.modificarInsumo(ADMIN,id,"Papa pelada","G");
        assertTrue(mod.huboCambio());
        assertEquals("G",mod.codigoUnidadMedida());
        assertEquals(0,BigDecimal.ZERO.compareTo(mod.stockActual()));
        assertFalse(gestion.modificarInsumo(ADMIN,id,"Papa pelada","G").huboCambio());
        assertEquals(0,contar("MOVIMIENTO_INSUMO"));
        assertEquals(2,jdbc.queryForObject("""
            SELECT "umbralMinimo" FROM "INSUMO" WHERE "idInsumo"=?
            """,BigDecimal.class,id).intValue());
    }

    @Test void uc51ConRecetaHistoricaProhibeCambiarUnidadPeroPermiteRenombrar(){
        long id=alta("Harina");
        historialReceta(id);
        assertThrows(IllegalArgumentException.class,()->gestion.modificarInsumo(
            ADMIN,id,"Harina para rebozar","G"));
        assertEquals("KG",jdbc.queryForObject(
            "SELECT \"codigoUnidadMedida\" FROM \"INSUMO\" WHERE \"idInsumo\"=?",
            String.class,id));
        assertTrue(gestion.modificarInsumo(ADMIN,id,"Harina para rebozar","KG").huboCambio());
        assertEquals(1,contar("DETALLE_VERSION_RECETA"));
        assertEquals(0,contar("MOVIMIENTO_INSUMO"));
    }

    @Test void uc51NoPermitirNombreDuplicadoNiActorProduccion(){
        long a=alta("Harina de trigo");
        long b=alta("Harina de maíz");
        assertThrows(SecurityException.class,()->gestion.modificarInsumo(
            PROD,a,"Otra harina","KG"));
        assertThrows(IllegalArgumentException.class,()->gestion.modificarInsumo(
            ADMIN,b,"HARINA DE TRIGO","KG"));
        assertThrows(IllegalArgumentException.class,()->gestion.modificarInsumo(
            ADMIN,99999,"Sin insumo","KG"));
        assertEquals(2,contar("INSUMO"));
    }

    @Test void uc52InactivarConRecetaNoCancelaHistoriaNiGeneraStock(){
        long id=alta("Cebolla");
        historialReceta(id);
        var cambiado=gestion.cambiarEstadoInsumo(ADMIN,id,false);
        assertTrue(cambiado.huboCambio());
        assertFalse(cambiado.activo());
        assertEquals(1,cambiado.dependencias().recetas());
        assertEquals(1,contar("DETALLE_VERSION_RECETA"));
        assertEquals(0,contar("MOVIMIENTO_INSUMO"));
        assertEquals(0,jdbc.queryForObject("""
            SELECT "stockActual" FROM "INSUMO" WHERE "idInsumo"=?
            """,BigDecimal.class,id).compareTo(BigDecimal.ZERO));
        assertFalse(gestion.cambiarEstadoInsumo(ADMIN,id,false).huboCambio());
        assertTrue(gestion.cambiarEstadoInsumo(ADMIN,id,true).huboCambio());
        assertEquals(1,contar("DETALLE_VERSION_RECETA"));
    }

    @Test void uc52SoloAdminEstadoValidoEInsumoExistente(){
        long id=alta("Tomate");
        assertThrows(SecurityException.class,()->gestion.cambiarEstadoInsumo(PROD,id,false));
        assertThrows(IllegalArgumentException.class,()->gestion.cambiarEstadoInsumo(ADMIN,id,null));
        assertThrows(IllegalArgumentException.class,()->gestion.cambiarEstadoInsumo(ADMIN,999999,false));
        assertEquals(1,contar("INSUMO"));
    }
}
