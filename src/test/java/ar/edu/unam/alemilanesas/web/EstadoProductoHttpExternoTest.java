package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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

/** B4-03-07 / UC48: puerto HTTP real, RBAC y PostgreSQL 17.11. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="ale.http.writes.productos-uc48-enabled=true")
@Testcontainers(disabledWithoutDocker=true)
class EstadoProductoHttpExternoTest {
    @Container
    static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url",db::getJdbcUrl);
        registro.add("spring.datasource.username",db::getUsername);
        registro.add("spring.datasource.password",db::getPassword);
    }

    private static final AtomicLong SEQ=new AtomicLong(980000);
    private static final String RUTA="/api/v1/productos";
    private static final String CAMBIO_TRUE="{\"activo\":true}";
    private static final String CAMBIO_FALSE="{\"activo\":false}";
    private static final String CODIGO="codigo";

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private long base;
    private long idActivo;
    private long idInactivo;
    private String admin,vendedor,produccion,repartidor,proveedor,pendiente;

    @BeforeEach
    void fixture() {
        base=SEQ.addAndGet(40);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "UNIDAD_MEDIDA"("codigoUnidad","nombre")
                    VALUES('UN','Unidad'),('KG','Kilogramo')
                    ON CONFLICT("codigoUnidad") DO NOTHING
                    """);
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Admin UC48 HTTP'),('VENDEDOR','Vendedor UC48 HTTP'),
                    ('RESPONSABLE_PRODUCCION','Produccion UC48 HTTP'),
                    ('REPARTIDOR','Repartidor UC48 HTTP'),('PROVEEDOR','Proveedor UC48 HTTP')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            String[] roles={"ADMINISTRADOR","VENDEDOR","RESPONSABLE_PRODUCCION",
                    "REPARTIDOR","PROVEEDOR","ADMINISTRADOR"};
            for (int i=0;i<roles.length;i++) {
                jdbc.update("""
                        INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta")
                        VALUES(?,?,'HASH_SINTETICO','HABILITADO',?,clock_timestamp())
                        """,base+i,"usuario_uc48_http_"+(base+i),i==5);
                jdbc.update("""
                        INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                        VALUES(?,?,clock_timestamp())
                        """,base+i,roles[i]);
            }
            jdbc.update("""
                    INSERT INTO "PROVEEDOR"("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                    VALUES(?,?,'Proveedor sintetico UC48','ACTIVO',clock_timestamp())
                    """,base+8,String.format("%011d",32000000000L+base));
            jdbc.update("""
                    INSERT INTO "USUARIO_PROVEEDOR"("idUsuario","idProveedor","fechaHoraVinculacion")
                    VALUES(?,?,clock_timestamp())
                    """,base+4,base+8);

            idActivo=base+10;
            idInactivo=base+11;
            jdbc.update("""
                    INSERT INTO "PRODUCTO"("idProducto","nombre","nombreNormalizado","descripcion",
                        "tipoProducto","codigoUnidadVenta","stockActualReventa",
                        "costoPromedioActualReventa","activo")
                    VALUES
                    (?,?,?,'Producto activo UC48','ELABORADO','KG',NULL,NULL,true),
                    (?,?,?,'Producto inactivo UC48','REVENTA','UN',0.000,NULL,false)
                    """,idActivo,"Producto Activo "+base,"producto activo "+base,
                    idInactivo,"Producto Inactivo "+base,"producto inactivo "+base);

            admin=sesion(base,UUID.randomUUID());
            vendedor=sesion(base+1,UUID.randomUUID());
            produccion=sesion(base+2,UUID.randomUUID());
            repartidor=sesion(base+3,UUID.randomUUID());
            proveedor=sesion(base+4,UUID.randomUUID());
            pendiente=sesion(base+5,UUID.randomUUID());
        });
    }

    private String sesion(long usuario,UUID id) {
        String token=TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion")
                VALUES(?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '29 minutes')
                """,id,usuario,TokenSesionInterna.huella(token).orElseThrow());
        return token;
    }

    private HttpResponse<String> patch(String token,long id,String cuerpo) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:"+port+RUTA+"/"+id+"/estado"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString(cuerpo));
        if (token!=null) b.header("Authorization","Bearer "+token);
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bajaLogicaEsExitosa() throws Exception {
        var r=patch(admin,idActivo,CAMBIO_FALSE);
        assertEquals(200,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var arbol=json.readTree(r.body());
        assertEquals(idActivo,arbol.get("idProducto").asLong());
        assertFalse(arbol.get("activo").asBoolean());
        assertTrue(arbol.get("huboCambio").asBoolean());
        assertEquals(Boolean.FALSE,jdbc.queryForObject(
                "SELECT activo FROM \"PRODUCTO\" WHERE \"idProducto\"=?",Boolean.class,idActivo));
    }

    @Test
    void reactivacionValidaSinPrecioEsExitosa() throws Exception {
        // No se crea PRECIO_PRODUCTO: ACTIVO no equivale a comercializable.
        var r=patch(admin,idInactivo,CAMBIO_TRUE);
        assertEquals(200,r.statusCode(),r.body());
        assertTrue(json.readTree(r.body()).get("huboCambio").asBoolean());
        assertEquals(Boolean.TRUE,jdbc.queryForObject(
                "SELECT activo FROM \"PRODUCTO\" WHERE \"idProducto\"=?",Boolean.class,idInactivo));
    }

    @Test
    void noOpMismoEstadoActivo() throws Exception {
        var r=patch(admin,idActivo,CAMBIO_TRUE);
        assertEquals(200,r.statusCode(),r.body());
        assertFalse(json.readTree(r.body()).get("huboCambio").asBoolean());
    }

    @Test
    void noOpMismoEstadoInactivo() throws Exception {
        var r=patch(admin,idInactivo,CAMBIO_FALSE);
        assertEquals(200,r.statusCode(),r.body());
        assertFalse(json.readTree(r.body()).get("huboCambio").asBoolean());
    }

    // F09 existe en V1: BEFORE INSERT y BEFORE UPDATE sobre DETALLE_PEDIDO.
    private long crearPedidoPendiente(long sufijo) {
        long idCliente=base+sufijo,idPolitica=base+sufijo+1,idPedido=base+sufijo+2;
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "CLIENTE"("idCliente","nombres","telefonoPrincipal",
                        "estado","categoriaComercial","fechaHoraAlta")
                    VALUES(?,?,'3764970001','ACTIVO','CUMPLIDOR',clock_timestamp())
                    """,idCliente,"Cliente UC48 "+idCliente);
            jdbc.update("""
                    INSERT INTO "POLITICA_CATEGORIA_COMERCIAL"("idPoliticaComercial",
                        "diasToleranciaMora","vigenteDesde","vigenteHasta",
                        "fechaHoraRegistro","idUsuarioRegistro")
                    VALUES(?,7,
                        TIMESTAMPTZ '2026-01-01T00:00:00Z' + (? * INTERVAL '1 second'),
                        TIMESTAMPTZ '2026-01-01T00:00:00Z' + ((? + 1) * INTERVAL '1 second'),
                        clock_timestamp(),?)
                    """,idPolitica,base,base,base);
            jdbc.update("""
                    INSERT INTO "PEDIDO"("idPedido","idCliente","idUsuarioCreador",
                        "fechaHoraCarga","fechaReparto","canalOrigen","estado",
                        "categoriaComercialAplicada","idPoliticaComercialAplicada",
                        "tipoAjuste","porcentajeAjuste","totalOriginal",
                        "direccionEntregaCalle","direccionEntregaLocalidad")
                    VALUES(?,?,?,clock_timestamp(),current_date,'INTERNO','PENDIENTE',
                        'CUMPLIDOR',?,'SIN_AJUSTE',0,100.00,'Calle 1','Posadas')
                    """,idPedido,idCliente,base,idPolitica);
        });
        return idPedido;
    }

    @Test
    void bajaPreservaPedidoPendiente() throws Exception {
        long idPedido=crearPedidoPendiente(50);
        jdbc.update("""
                INSERT INTO "DETALLE_PEDIDO"("idPedido","numeroDetalle","idProducto",
                    "codigoUnidadVenta","cantidadOriginal","precioUnitarioOriginal",
                    "porcentajePromocionOriginal")
                VALUES(?,1,?,'KG',1.000,100.00,0)
                """,idPedido,idActivo);
        var r=patch(admin,idActivo,CAMBIO_FALSE);
        assertEquals(200,r.statusCode(),r.body());
        assertEquals("PENDIENTE",jdbc.queryForObject(
                "SELECT estado FROM \"PEDIDO\" WHERE \"idPedido\"=?",String.class,idPedido));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "DETALLE_PEDIDO" WHERE "idPedido"=?
                """,Integer.class,idPedido));
    }

    @Test
    void f09RechazaNuevoDetallePedidoSobreProductoInactivo() {
        long idPedido=crearPedidoPendiente(60);
        assertThrows(DataAccessException.class,()->jdbc.update("""
                INSERT INTO "DETALLE_PEDIDO"("idPedido","numeroDetalle","idProducto",
                    "codigoUnidadVenta","cantidadOriginal","precioUnitarioOriginal",
                    "porcentajePromocionOriginal")
                VALUES(?,1,?,'UN',1.000,100.00,0)
                """,idPedido,idInactivo));
    }

    @Test
    void fichaInconsistenteEs409() throws Exception {
        jdbc.update("UPDATE \"PRODUCTO\" SET \"nombreNormalizado\"=? WHERE \"idProducto\"=?",
                "nombre distinto "+base,idInactivo);
        var r=patch(admin,idInactivo,CAMBIO_TRUE);
        assertEquals(409,r.statusCode(),r.body());
        assertEquals("PRODUCTO_FICHA_INVALIDA",json.readTree(r.body()).get(CODIGO).asText());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings={
            "{JSON_ROTO",
            "{}",
            "{\"activo\":\"true\"}",
            "{\"activo\":1}",
            "{\"activo\":null}",
            "{\"activo\":true,\"extra\":1}",
            "{\"activo\":true,\"activo\":false}",
            "{\"otro\":true}"
    })
    void bodyInvalidoEs400(String cuerpo) throws Exception {
        var r=patch(admin,idActivo,cuerpo);
        assertEquals(400,r.statusCode(),cuerpo+" -> "+r.body());
        assertEquals("SOLICITUD_INVALIDA",json.readTree(r.body()).get(CODIGO).asText());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    @Test
    void idNegativoEs400() throws Exception {
        var r=patch(admin,-1L,CAMBIO_TRUE);
        assertEquals(400,r.statusCode(),r.body());
    }

    @Test
    void productoInexistenteEs404() throws Exception {
        var r=patch(admin,999999999L,CAMBIO_TRUE);
        assertEquals(404,r.statusCode(),r.body());
        assertEquals("PRODUCTO_NO_ENCONTRADO",json.readTree(r.body()).get(CODIGO).asText());
    }

    @Test
    void sinTokenEs401() throws Exception {
        assertEquals(401,patch(null,idActivo,CAMBIO_TRUE).statusCode());
    }

    @Test
    void rolesNoAutorizadosEs403() throws Exception {
        for (String token:List.of(vendedor,produccion,repartidor,proveedor,pendiente)) {
            assertEquals(403,patch(token,idActivo,CAMBIO_TRUE).statusCode());
        }
        var advice=new ErroresEstadoProductoHttp();
        assertEquals(403,advice.prohibido().getStatusCode().value());
        assertEquals(503,advice.persistencia().getStatusCode().value());
        var env=new MockEnvironment()
                .withProperty("server.address","127.0.0.1")
                .withProperty("server.forward-headers-strategy","native")
                .withProperty("ale.security.singleton-db-guard","true")
                .withProperty("ale.web.allowed-origin","https://ale.example.invalid")
                .withProperty("ale.http.writes.productos-uc48-enabled","true");
        assertThrows(IllegalStateException.class,
                ()->new PrecondicionesDespliegue(env).postProcessBeanFactory(null));
    }

    @Test
    void cookieEnLugarDeBearerEs401() throws Exception {
        var request=HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:"+port+RUTA+"/"+idActivo+"/estado"))
                .header("Content-Type","application/json")
                .header("Cookie","__Host-ale_session="+admin)
                .method("PATCH",HttpRequest.BodyPublishers.ofString(CAMBIO_TRUE)).build();
        assertEquals(401,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
