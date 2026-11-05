package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** B4-03-06 / UC47: modificacion HTTP con puerto real y PostgreSQL 17.11. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="ale.http.writes.productos-uc47-enabled=true")
@Testcontainers(disabledWithoutDocker = true)
class ModificacionProductoHttpExternoTest {
    @Container
    static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }

    private static final AtomicLong SEQ=new AtomicLong(970000);
    private static final String RUTA="/api/v1/productos";
    private static final String ELABORADO="ELABORADO";
    private static final String REVENTA="REVENTA";
    private static final String KG="KG";
    private static final String UN="UN";
    private static final String CODIGO="codigo";
    private static final String SOLICITUD_INVALIDA="SOLICITUD_INVALIDA";
    private static final String PRODUCTO_CON_DEPENDENCIAS="PRODUCTO_CON_DEPENDENCIAS";
    private static final String CACHE_CONTROL="Cache-Control";
    private static final String NO_STORE="no-store";

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private long base;
    private long idProducto;
    private long idProductoInactivo;
    private UUID sesionAdmin;
    private String admin,vendedor,produccion,repartidor,proveedor,pendiente;

    @BeforeEach
    void fixture() {
        base=SEQ.addAndGet(40);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "UNIDAD_MEDIDA"("codigoUnidad","nombre") VALUES('UN','Unidad'),('KG','Kilogramo')
                    ON CONFLICT("codigoUnidad") DO NOTHING
                    """);
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Admin UC47 HTTP'),('VENDEDOR','Vendedor UC47 HTTP'),
                    ('RESPONSABLE_PRODUCCION','Produccion UC47 HTTP'),
                    ('REPARTIDOR','Repartidor UC47 HTTP'),('PROVEEDOR','Proveedor UC47 HTTP')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            String[] roles={
                "ADMINISTRADOR","VENDEDOR","RESPONSABLE_PRODUCCION",
                "REPARTIDOR","PROVEEDOR","ADMINISTRADOR"
            };
            for (int i=0;i<roles.length;i++) {
                jdbc.update("""
                        INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta")
                        VALUES(?,?,'HASH_SINTETICO','HABILITADO',?,clock_timestamp())
                        """,base+i,"usuario_uc47_http_"+(base+i),i==5);
                jdbc.update("""
                        INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                        VALUES(?,?,clock_timestamp())
                        """,base+i,roles[i]);
            }
            jdbc.update("""
                    INSERT INTO "PROVEEDOR"("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                    VALUES(?,?,'Proveedor sintetico UC47','ACTIVO',clock_timestamp())
                    """,base+8,String.format("%011d",30500000000L+base));
            jdbc.update("""
                    INSERT INTO "USUARIO_PROVEEDOR"("idUsuario","idProveedor","fechaHoraVinculacion")
                    VALUES(?,?,clock_timestamp())
                    """,base+4,base+8);

            idProducto=base+10;
            idProductoInactivo=base+11;
            jdbc.update("""
                    INSERT INTO "PRODUCTO"("idProducto","nombre","nombreNormalizado","descripcion",
                        "tipoProducto","codigoUnidadVenta","stockActualReventa",
                        "costoPromedioActualReventa","activo")
                    VALUES
                    (?,?,?,'Descripcion inicial',?,?,NULL,NULL,true),
                    (?,?,?,'Inactivo existente',?,?,12.500,1400.00,false)
                    """,
                    idProducto,"Milanesa Base "+base,"milanesa base "+base,ELABORADO,KG,
                    idProductoInactivo,"Reventa Inactiva "+base,"reventa inactiva "+base,REVENTA,UN);

            sesionAdmin=UUID.randomUUID();
            admin=sesion(base,sesionAdmin);
            vendedor=sesion(base+1,UUID.randomUUID());
            produccion=sesion(base+2,UUID.randomUUID());
            repartidor=sesion(base+3,UUID.randomUUID());
            proveedor=sesion(base+4,UUID.randomUUID());
            pendiente=sesion(base+5,UUID.randomUUID());
        });
    }

    private String sesion(long usuario, UUID id) {
        String token=TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES(?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '29 minutes')
                """,id,usuario,TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private HttpResponse<String> put(String token, long id, String body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+RUTA+"/"+id))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (token!=null) {
            b.header("Authorization","Bearer "+token);
        }
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }

    private String body(String nombre, String descripcion, String tipo, String unidad) {
        if (descripcion==null) {
            return json.writeValueAsString(Map.of(
                    "nombre",nombre,"tipoProducto",tipo,"codigoUnidadVenta",unidad));
        }
        return json.writeValueAsString(Map.of(
                "nombre",nombre,"descripcion",descripcion,
                "tipoProducto",tipo,"codigoUnidadVenta",unidad));
    }

    @Test
    void modificaNombreYDescripcionSinDependencias() throws Exception {
        var r=put(admin,idProducto,body("  Milanesa   Renombrada "+base+"  ","Ficha actualizada",ELABORADO,KG));
        assertEquals(200,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue(CACHE_CONTROL).orElse("").contains(NO_STORE));
        var nodo=json.readTree(r.body());
        assertEquals(idProducto,nodo.get("idProducto").asLong());
        assertTrue(nodo.get("activo").asBoolean());
        assertTrue(nodo.get("huboCambio").asBoolean());
        assertEquals("Milanesa Renombrada "+base,jdbc.queryForObject(
                "SELECT \"nombre\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",String.class,idProducto));
        assertEquals("milanesa renombrada "+base,jdbc.queryForObject(
                "SELECT \"nombreNormalizado\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",String.class,idProducto));
    }

    @Test
    void cambioTipoYUnidadSinDependenciasEsExitoso() throws Exception {
        var r=put(admin,idProducto,body("Milanesa Base "+base,"Pasa a reventa",REVENTA,UN));
        assertEquals(200,r.statusCode(),r.body());
        var nodo=json.readTree(r.body());
        assertTrue(nodo.get("huboCambio").asBoolean());
        assertEquals(REVENTA,jdbc.queryForObject(
                "SELECT \"tipoProducto\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",String.class,idProducto));
        assertEquals(UN,jdbc.queryForObject(
                "SELECT \"codigoUnidadVenta\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",String.class,idProducto));
    }

    @Test
    void modificarSinCambiosEsNoOp() throws Exception {
        var r=put(admin,idProducto,body("  Milanesa   Base "+base+" ","Descripcion inicial",ELABORADO,KG));
        assertEquals(200,r.statusCode(),r.body());
        var nodo=json.readTree(r.body());
        assertEquals(idProducto,nodo.get("idProducto").asLong());
        assertFalse(nodo.get("huboCambio").asBoolean());
    }

    @Test
    void nombreDuplicadoContraProductoInactivoEs409() throws Exception {
        var r=put(admin,idProducto,body("  REVENTA   INACTIVA "+base+" ",null,ELABORADO,KG));
        assertEquals(409,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue(CACHE_CONTROL).orElse("").contains(NO_STORE));
        var nodo=json.readTree(r.body());
        assertEquals("PRODUCTO_DUPLICADO",nodo.get(CODIGO).asText());
        var coincidencia=nodo.get("coincidencia");
        assertEquals(6,coincidencia.size(),r.body());
        assertEquals(idProductoInactivo,coincidencia.get("idProducto").asLong());
        assertFalse(coincidencia.get("activo").asBoolean());
        assertFalse(r.body().contains("stockActualReventa"));
        assertFalse(r.body().contains("costoPromedioActualReventa"));
        assertFalse(r.body().contains("nombreNormalizado"));
    }

    @Test
    void dependenciaEnDetallePedidoBloqueaTipoYUnidad() throws Exception {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO "CLIENTE"("idCliente","nombres","telefonoPrincipal",
                        "estado","categoriaComercial","fechaHoraAlta")
                    VALUES(?,?,'3764970001','ACTIVO','CUMPLIDOR',clock_timestamp())
                    """,base+20,"Cliente UC47 "+base);
            jdbc.update("""
                    INSERT INTO "POLITICA_CATEGORIA_COMERCIAL"("idPoliticaComercial","diasToleranciaMora",
                        "vigenteDesde","vigenteHasta","fechaHoraRegistro","idUsuarioRegistro")
                    VALUES(970001,7,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z',clock_timestamp(),?)
                    ON CONFLICT("idPoliticaComercial") DO NOTHING
                    """,base);
            jdbc.update("""
                    INSERT INTO "PEDIDO"("idPedido","idCliente","idUsuarioCreador",
                        "fechaHoraCarga","fechaReparto","canalOrigen","estado",
                        "categoriaComercialAplicada","idPoliticaComercialAplicada","tipoAjuste",
                        "porcentajeAjuste","totalOriginal","direccionEntregaCalle","direccionEntregaLocalidad")
                    VALUES(?,?,?,clock_timestamp(),current_date,'INTERNO','PENDIENTE',
                        'CUMPLIDOR',970001,'SIN_AJUSTE',0,100.00,'Calle 1','Posadas')
                    """,base+21,base+20,base+1);
            jdbc.update("""
                    INSERT INTO "DETALLE_PEDIDO"("idPedido","numeroDetalle","idProducto",
                        "codigoUnidadVenta","cantidadOriginal","precioUnitarioOriginal","porcentajePromocionOriginal")
                    VALUES(?,1,?,'KG',1.000,100.00,0)
                    """,base+21,idProducto);
        });
        var r=put(admin,idProducto,body("Milanesa Base "+base,"Intento cambio",REVENTA,UN));
        assertEquals(409,r.statusCode(),r.body());
        assertEquals(PRODUCTO_CON_DEPENDENCIAS,json.readTree(r.body()).get(CODIGO).asText());
    }

    @Test
    void dependenciaEnPrecioProductoBloqueaTipoYUnidad() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "PRECIO_PRODUCTO"("idPrecio","idProducto","precioBase",
                    "vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                VALUES(?,?,2500.00,clock_timestamp()+interval '1 day',clock_timestamp(),?)
                """,base+22,idProducto,base));
        var r=put(admin,idProducto,body("Milanesa Base "+base,"Intento cambio",REVENTA,KG));
        assertEquals(409,r.statusCode(),r.body());
        assertEquals(PRODUCTO_CON_DEPENDENCIAS,json.readTree(r.body()).get(CODIGO).asText());
    }

    @Test
    void dependenciaEnRecetaBloqueaTipoYUnidad() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO \"RECETA\"(\"idReceta\",\"idProducto\") VALUES(?,?)",
                base+23,idProducto));
        var r=put(admin,idProducto,body("Milanesa Base "+base,"Intento cambio",ELABORADO,UN));
        assertEquals(409,r.statusCode(),r.body());
        assertEquals(PRODUCTO_CON_DEPENDENCIAS,json.readTree(r.body()).get(CODIGO).asText());
    }

    @Test
    void dependenciaEnPromocionProductoBloqueaTipoYUnidad() throws Exception {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO "PROMOCION"("idPromocion","descripcion","porcentajeDescuento",
                        "vigenteDesde","vigenteHasta","estado","fechaHoraCreacion",
                        "idUsuarioCreador","codigoAccesoOnline")
                    VALUES(?,'Promo UC47',15.00,clock_timestamp(),clock_timestamp()+interval '1 day',
                        'BORRADOR',clock_timestamp(),?,?)
                    """,base+24,base,UUID.randomUUID());
            jdbc.update("INSERT INTO \"PROMOCION_PRODUCTO\"(\"idPromocion\",\"idProducto\") VALUES(?,?)",
                    base+24,idProducto);
        });
        var r=put(admin,idProducto,body("Milanesa Base "+base,"Intento cambio",REVENTA,UN));
        assertEquals(409,r.statusCode(),r.body());
        assertEquals(PRODUCTO_CON_DEPENDENCIAS,json.readTree(r.body()).get(CODIGO).asText());
    }

    @Test
    void dependenciaEnRecepcionCompraBloqueaTipoYUnidad() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "RECEPCION_COMPRA"("idRecepcionCompra","idProductoReventa",
                    "idUsuarioResponsable","fechaHoraRecepcion","cantidadRecibida",
                    "codigoUnidadMedida","costoUnitarioReal","factorConversionAplicado")
                VALUES(?,?,?,clock_timestamp(),5.000,'KG',900.00,1.0)
                """,base+25,idProducto,base));
        var r=put(admin,idProducto,body("Milanesa Base "+base,"Intento cambio",REVENTA,UN));
        assertEquals(409,r.statusCode(),r.body());
        assertEquals(PRODUCTO_CON_DEPENDENCIAS,json.readTree(r.body()).get(CODIGO).asText());
    }

    @Test
    void dependenciaEnMovimientoStockReventaBloqueaTipoYUnidad() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "MOVIMIENTO_STOCK_REVENTA"("idMovimientoStockReventa","idProducto",
                    "numeroSecuenciaProducto","idUsuarioResponsable","tipoMovimiento",
                    "cantidad","fechaHoraMovimiento","stockResultante")
                VALUES(?,?,1,?,'AJUSTE_POSITIVO',3.000,clock_timestamp(),3.000)
                """,base+26,idProducto,base));
        var r=put(admin,idProducto,body("Milanesa Base "+base,"Intento cambio",REVENTA,UN));
        assertEquals(409,r.statusCode(),r.body());
        assertEquals(PRODUCTO_CON_DEPENDENCIAS,json.readTree(r.body()).get(CODIGO).asText());
    }

    @Test
    void dependenciaEnValoracionExtraordinariaReventaBloqueaTipoYUnidad() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "VALORACION_EXTRAORDINARIA_REVENTA"("idValoracionReventa","idProducto",
                    "tipoValoracion","costoUnitario","fechaHoraValoracion","motivo","idUsuarioResponsable")
                VALUES(?,?,'INICIAL',750.00,clock_timestamp(),'Valoracion inicial prueba',?)
                """,base+27,idProducto,base));
        var r=put(admin,idProducto,body("Milanesa Base "+base,"Intento cambio",REVENTA,UN));
        assertEquals(409,r.statusCode(),r.body());
        assertEquals(PRODUCTO_CON_DEPENDENCIAS,json.readTree(r.body()).get(CODIGO).asText());
    }

    @Test
    void modificarSoloNombreYDescripcionConDependenciasEsExitoso() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "PRECIO_PRODUCTO"("idPrecio","idProducto","precioBase",
                    "vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                VALUES(?,?,3100.00,clock_timestamp()+interval '2 days',clock_timestamp(),?)
                """,base+28,idProducto,base));
        var r=put(admin,idProducto,body("Milanesa Con Precio "+base,"Descripcion permitida",ELABORADO,KG));
        assertEquals(200,r.statusCode(),r.body());
        assertTrue(json.readTree(r.body()).get("huboCambio").asBoolean());
        assertEquals("Milanesa Con Precio "+base,jdbc.queryForObject(
                "SELECT \"nombre\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?",String.class,idProducto));
    }

    @Test
    void productoInexistenteEs404() throws Exception {
        var r=put(admin,999999999L,body("No existe "+base,null,ELABORADO,KG));
        assertEquals(404,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue(CACHE_CONTROL).orElse("").contains(NO_STORE));
        assertEquals("PRODUCTO_NO_ENCONTRADO",json.readTree(r.body()).get(CODIGO).asText());
    }

    @ParameterizedTest
    @ValueSource(strings={
            "{JSON_ROTO",
            "{}",
            "{\"nombre\":\"   \",\"tipoProducto\":\"ELABORADO\",\"codigoUnidadVenta\":\"KG\"}",
            "{\"nombre\":\"Valido\",\"tipoProducto\":\"MIXTO\",\"codigoUnidadVenta\":\"KG\"}",
            "{\"nombre\":\"Valido\",\"tipoProducto\":\"ELABORADO\",\"codigoUnidadVenta\":\"NO_EXISTE\"}",
            "{\"nombre\":\"Valido\",\"tipoProducto\":\"ELABORADO\",\"codigoUnidadVenta\":\"KG\",\"activo\":false}",
            "{\"nombre\":123,\"tipoProducto\":\"ELABORADO\",\"codigoUnidadVenta\":\"KG\"}",
            "{\"nombre\":\"Valido\",\"descripcion\":\"\\u0000\",\"tipoProducto\":\"ELABORADO\",\"codigoUnidadVenta\":\"KG\"}"
    })
    void cuerpoInvalidoEs400(String jsonInvalido) throws Exception {
        var r=put(admin,idProducto,jsonInvalido);
        assertEquals(400,r.statusCode(),jsonInvalido+" -> "+r.body());
        assertTrue(r.headers().firstValue(CACHE_CONTROL).orElse("").contains(NO_STORE));
        assertEquals(SOLICITUD_INVALIDA,json.readTree(r.body()).get(CODIGO).asText());
    }

    @Test
    void sinTokenEs401() throws Exception {
        String valido=body("Milanesa Base "+base,"Intento",ELABORADO,KG);
        assertEquals(401,put(null,idProducto,valido).statusCode());
        assertEquals(401,put(sesionAdmin.toString(),idProducto,valido).statusCode());
        assertEquals(400,put(admin,0L,valido).statusCode());
    }

    @Test
    void rolesNoAutorizadosEs403() throws Exception {
        String valido=body("Milanesa Base "+base,"Intento",ELABORADO,KG);
        for (String noAdmin : List.of(vendedor,produccion,repartidor,proveedor,pendiente)) {
            assertEquals(403,put(noAdmin,idProducto,valido).statusCode());
        }
        var advice=new ErroresModificacionProductoHttp();
        assertEquals(403,advice.prohibido().getStatusCode().value());
        assertEquals(503,advice.persistencia().getStatusCode().value());
        for (String flag : List.of("ale.http.writes.productos-uc47-enabled",
                "ale.http.writes.productos-uc48-enabled")) {
            var env=new MockEnvironment()
                    .withProperty("server.address","127.0.0.1")
                    .withProperty("server.forward-headers-strategy","native")
                    .withProperty("ale.security.singleton-db-guard","true")
                    .withProperty("ale.web.allowed-origin","https://ale.example.invalid")
                    .withProperty(flag,"true");
            var validador=new PrecondicionesDespliegue(env);
            assertThrows(IllegalStateException.class,
                    ()->validador.postProcessBeanFactory(null));
        }
    }

    @Test
    void cookieEnLugarDeBearerEs401() throws Exception {
        String valido=body("Milanesa Base "+base,"Intento",ELABORADO,KG);
        var cookie=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+RUTA+"/"+idProducto))
                .header("Cookie","__Host-ale_session="+admin)
                .header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(valido)).build();
        assertEquals(401,http.send(cookie,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
