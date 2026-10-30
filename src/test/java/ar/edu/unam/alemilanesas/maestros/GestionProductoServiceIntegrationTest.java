package ar.edu.unam.alemilanesas.maestros;

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

/** I1-03: B3 sobre Flyway MR5/F01 reales, nunca E2E B4. */
@SpringBootTest
@Testcontainers
@Transactional
class GestionProductoServiceIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired GestionProductoService servicio;
    @Autowired BuscarProductoService buscador;
    static final UUID ADMIN=UUID.fromString("30300000-0000-4000-8000-000000000001");
    static final UUID VENDEDOR=UUID.fromString("30300000-0000-4000-8000-000000000002");
    static final UUID PRODUCCION=UUID.fromString("30300000-0000-4000-8000-000000000003");

    @BeforeEach void semilla(){
        jdbc.update("""
            INSERT INTO "UNIDAD_MEDIDA" ("codigoUnidad","nombre")
            VALUES ('UN','Unidad'),('KG','Kilogramo')
            """);
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol","nombreMostrado")
            VALUES ('ADMINISTRADOR','Admin UC30'),('VENDEDOR','Vendedor UC30'),
                   ('RESPONSABLE_PRODUCCION','Produccion UC30')
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                "estado","cambioCredencialPendiente","fechaHoraAlta")
            VALUES (30301,'admin_uc30','NO_ES_HASH_REAL','HABILITADO',false,now()),
                   (30302,'vendedor_uc30','NO_ES_HASH_REAL','HABILITADO',false,now()),
                   (30303,'produccion_uc30','NO_ES_HASH_REAL','HABILITADO',false,now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
            VALUES (30301,'ADMINISTRADOR',now()),(30302,'VENDEDOR',now()),
                   (30303,'RESPONSABLE_PRODUCCION',now())
            """);
        jdbc.update("""
            INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                "fechaHoraInicio","fechaHoraExpiracion")
            VALUES (?,30301,decode('303001','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,30302,decode('303002','hex'),now()-interval '1 minute',now()+interval '1 hour'),
                   (?,30303,decode('303003','hex'),now()-interval '1 minute',now()+interval '1 hour')
            """,ADMIN,VENDEDOR,PRODUCCION);
    }
    private long alta(String nombre,String tipo){
        return servicio.gestionarProducto(ADMIN,nombre,"Ficha sintética",tipo,"UN").idProducto();
    }
    private int contar(String tabla){
        return jdbc.queryForObject("SELECT count(*) FROM \""+tabla+"\"",Integer.class);
    }

    @Test void uc30AltaElaboradoActivoSinPrecioRecetaNiStockInventado(){
        var res=servicio.gestionarProducto(ADMIN,"Milanesa napolitana",null,"ELABORADO","UN");
        assertTrue(res.activo());
        assertEquals("milanesa napolitana",jdbc.queryForObject(
            "SELECT \"nombreNormalizado\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",
            String.class,res.idProducto()));
        assertNull(jdbc.queryForObject("SELECT \"stockActualReventa\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",
            java.math.BigDecimal.class,res.idProducto()));
        assertEquals(0,contar("RECETA"));
        assertEquals(0,contar("PRECIO_PRODUCTO"));
        assertEquals(0,contar("MOVIMIENTO_STOCK_REVENTA"));
    }

    @Test void uc30AltaReventaSinCostoFicticio(){
        long id=alta("Agua mineral","REVENTA");
        assertEquals("REVENTA",jdbc.queryForObject(
            "SELECT \"tipoProducto\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",String.class,id));
        assertNull(jdbc.queryForObject(
            "SELECT \"costoPromedioActualReventa\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",
            java.math.BigDecimal.class,id));
        assertEquals(0,contar("PRECIO_PRODUCTO"));
    }

    @Test void uc30DuplicadoNormalizadoIncluidoInactivoRechazado(){
        long id=alta(" Milanesa   especial ","ELABORADO");
        servicio.cambiarEstadoProducto(ADMIN,id,false);
        assertThrows(IllegalArgumentException.class,()->servicio.gestionarProducto(
            ADMIN,"milanesa especial",null,"REVENTA","UN"));
        assertEquals(1,contar("PRODUCTO"));
    }

    @Test void uc30SoloAdminYValidacionTipoUnidad(){
        assertThrows(SecurityException.class,()->servicio.gestionarProducto(
            VENDEDOR,"Agua",null,"REVENTA","UN"));
        assertThrows(SecurityException.class,()->servicio.gestionarProducto(
            PRODUCCION,"Agua",null,"REVENTA","UN"));
        assertThrows(IllegalArgumentException.class,()->servicio.gestionarProducto(
            ADMIN,"  ",null,"REVENTA","UN"));
        assertThrows(IllegalArgumentException.class,()->servicio.gestionarProducto(
            ADMIN,"Agua",null,"MIXTO","UN"));
        assertThrows(IllegalArgumentException.class,()->servicio.gestionarProducto(
            ADMIN,"Agua",null,"REVENTA","SIN_UNIDAD"));
        assertEquals(0,contar("PRODUCTO"));
    }

    @Test void uc35BusquedaVendedorYAdminSinCosto(){
        long a=alta("Milanesa completa","ELABORADO");
        alta("Jugo de naranja","REVENTA");
        var encontrados=buscador.buscarProducto(VENDEDOR,
            new BuscarProductoService.CriterioProducto(null,"milanesa","ELABORADO",true));
        assertEquals(1,encontrados.size());
        assertEquals(a,encontrados.getFirst().idProducto());
        assertEquals(6,BuscarProductoService.ProductoConsulta.class.getRecordComponents().length);
        assertEquals(2,buscador.buscarProducto(ADMIN,
            new BuscarProductoService.CriterioProducto(null,null,null,null)).size());
        assertEquals(2,contar("PRODUCTO"));
    }

    @Test void uc35ProduccionDenegadaYFiltrosInvalidos(){
        alta("Agua mineral","REVENTA");
        assertThrows(SecurityException.class,()->buscador.buscarProducto(PRODUCCION,
            new BuscarProductoService.CriterioProducto(null,null,null,null)));
        assertThrows(IllegalArgumentException.class,()->buscador.buscarProducto(ADMIN,
            new BuscarProductoService.CriterioProducto(-1L,null,null,null)));
        assertThrows(IllegalArgumentException.class,()->buscador.buscarProducto(ADMIN,
            new BuscarProductoService.CriterioProducto(null,null,"MIXTO",null)));
        assertEquals(0,buscador.buscarProducto(ADMIN,
            new BuscarProductoService.CriterioProducto(null,"No existe",null,null)).size());
    }

    @Test void uc47ModificarDatosSinHistoriaYNoOp(){
        long id=alta("Milanesa simple","ELABORADO");
        var mod=servicio.modificarProducto(ADMIN,id,"Milanesa premium","Nueva ficha","REVENTA","KG");
        assertTrue(mod.huboCambio());
        assertEquals("REVENTA",jdbc.queryForObject(
            "SELECT \"tipoProducto\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",String.class,id));
        assertFalse(servicio.modificarProducto(ADMIN,id,"Milanesa premium",
                "Nueva ficha","REVENTA","KG").huboCambio());
        assertEquals(0,contar("RECETA"));
        assertEquals(0,contar("PRECIO_PRODUCTO"));
    }

    @Test void uc47ProductoConPrecioNoPuedeCambiarTipoUnidad(){
        long id=alta("Milanesa combo","ELABORADO");
        jdbc.update("""
            INSERT INTO "PRECIO_PRODUCTO" ("idPrecio","idProducto","precioBase",
                "vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
            VALUES (30401,?,11000.00,now()+interval '1 day',now(),30301)
            """,id);
        assertThrows(IllegalArgumentException.class,()->servicio.modificarProducto(
            ADMIN,id,"Milanesa combo",null,"REVENTA","KG"));
        assertTrue(servicio.modificarProducto(ADMIN,id,
            "Milanesa combo familiar",null,"ELABORADO","UN").huboCambio());
        assertEquals(1,contar("PRECIO_PRODUCTO"));
    }

    @Test void uc47ProductoConRecetaNoPuedeCambiarTipo(){
        long id=alta("Milanesa receta","ELABORADO");
        jdbc.update("INSERT INTO \"RECETA\" (\"idReceta\",\"idProducto\") VALUES (30501,?)",id);
        assertThrows(IllegalArgumentException.class,()->servicio.modificarProducto(
            ADMIN,id,"Milanesa receta","Cambiar a reventa","REVENTA","UN"));
        assertEquals(1,contar("RECETA"));
    }

    @Test void uc47DuplicadosYVendedorDenegado(){
        long a=alta("Combo A","ELABORADO");
        long c=alta("Combo B","REVENTA");
        assertThrows(SecurityException.class,()->servicio.modificarProducto(
            VENDEDOR,a,"No autorizado",null,"ELABORADO","UN"));
        servicio.cambiarEstadoProducto(ADMIN,a,false);
        assertThrows(IllegalArgumentException.class,()->servicio.modificarProducto(
            ADMIN,c,"combo a",null,"REVENTA","UN"));
        assertEquals("Combo B",jdbc.queryForObject(
            "SELECT \"nombre\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",String.class,c));
    }

    @Test void uc48BajaAltaYNoOpNoCreanPrecios(){
        long id=alta("Agua gasificada","REVENTA");
        assertFalse(servicio.cambiarEstadoProducto(ADMIN,id,true).huboCambio());
        assertTrue(servicio.cambiarEstadoProducto(ADMIN,id,false).huboCambio());
        assertEquals(0,buscador.buscarProducto(VENDEDOR,
            new BuscarProductoService.CriterioProducto(null,null,null,true)).size());
        assertTrue(servicio.cambiarEstadoProducto(ADMIN,id,true).huboCambio());
        assertEquals(0,contar("PRECIO_PRODUCTO"));
    }

    @Test void uc48ReactivarFichaInvalidaExigeUC47(){
        long id=alta("Milanesa al plato","ELABORADO");
        servicio.cambiarEstadoProducto(ADMIN,id,false);
        jdbc.update("UPDATE \"PRODUCTO\" SET \"nombre\"='  ' WHERE \"idProducto\"=?",id);
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoProducto(
            ADMIN,id,true));
        assertFalse(jdbc.queryForObject(
            "SELECT \"activo\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",Boolean.class,id));
    }

    @Test void uc48VendedorDenegadoEInexistenteNoEscribe(){
        long id=alta("Jugo natural","REVENTA");
        assertThrows(SecurityException.class,()->servicio.cambiarEstadoProducto(VENDEDOR,id,false));
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoProducto(ADMIN,99_999L,false));
        assertThrows(IllegalArgumentException.class,()->servicio.cambiarEstadoProducto(ADMIN,id,null));
        assertEquals(1,contar("PRODUCTO"));
    }

    @Test void uc48DesactivacionConservaDetallePedidoPendienteYF09DeniegaNuevo(){
        long id=alta("Milanesa para pedido","ELABORADO");
        jdbc.update("""
            INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal",
                "estado","categoriaComercial","fechaHoraAlta")
            VALUES (30601,'Cliente producto','3764003001','ACTIVO','CUMPLIDOR',now())
            """);
        jdbc.update("""
            INSERT INTO "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial","diasToleranciaMora",
                "vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
            VALUES (30602,7,now()-interval '1 day',now(),30301)
            """);
        jdbc.update("""
            INSERT INTO "PEDIDO" ("idPedido","idCliente","idUsuarioCreador",
                "fechaHoraCarga","fechaReparto","canalOrigen","estado",
                "categoriaComercialAplicada","idPoliticaComercialAplicada","tipoAjuste",
                "porcentajeAjuste","totalOriginal","direccionEntregaCalle","direccionEntregaLocalidad")
            VALUES (30603,30601,30302,now(),current_date,'INTERNO','PENDIENTE',
                'CUMPLIDOR',30602,'SIN_AJUSTE',0,100.00,'Calle ficticia','Apóstoles')
            """);
        jdbc.update("""
            INSERT INTO "DETALLE_PEDIDO" ("idPedido","numeroDetalle","idProducto",
                "codigoUnidadVenta","cantidadOriginal","precioUnitarioOriginal",
                "porcentajePromocionOriginal")
            VALUES (30603,1,?,'UN',1,100.00,0)
            """,id);
        // Segundo Pedido sin ese detalle: evita que UNIQUE(idPedido,idProducto)
        // oculte la regla F09 que debe denegar un PRODUCTO inactivo.
        jdbc.update("""
            INSERT INTO "PEDIDO" ("idPedido","idCliente","idUsuarioCreador",
                "fechaHoraCarga","fechaReparto","canalOrigen","estado",
                "categoriaComercialAplicada","idPoliticaComercialAplicada","tipoAjuste",
                "porcentajeAjuste","totalOriginal","direccionEntregaCalle","direccionEntregaLocalidad")
            VALUES (30604,30601,30302,now(),current_date,'INTERNO','PENDIENTE',
                'CUMPLIDOR',30602,'SIN_AJUSTE',0,100.00,'Calle ficticia','Apóstoles')
            """);
        servicio.cambiarEstadoProducto(ADMIN,id,false);
        assertEquals(1,contar("DETALLE_PEDIDO"));
        assertEquals("PENDIENTE",jdbc.queryForObject(
            "SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=30603",String.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("""
            INSERT INTO "DETALLE_PEDIDO" ("idPedido","numeroDetalle","idProducto",
                "codigoUnidadVenta","cantidadOriginal","precioUnitarioOriginal",
                "porcentajePromocionOriginal")
            VALUES (30604,1,?,'UN',1,100.00,0)
            """,id));
    }
}
