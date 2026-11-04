package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Puerto real y commits PostgreSQL; sesiones sinteticas, no aceptacion humana. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="ale.http.writes.productos-uc30-enabled=true")
@Testcontainers
class RegistroProductoHttpExternoTest {
    @Container
    static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }

    private static final AtomicLong SEQ=new AtomicLong(930000);
    private static final String RUTA="/api/v1/productos";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private long base;
    private UUID sesionAdmin;
    private String admin,vendedor,produccion,repartidor,proveedor,pendiente;

    @BeforeEach
    void fixture() {
        base=SEQ.addAndGet(10);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "UNIDAD_MEDIDA"("codigoUnidad","nombre") VALUES('UN','Unidad')
                    ON CONFLICT("codigoUnidad") DO NOTHING
                    """);
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Admin UC30 HTTP'),('VENDEDOR','Vendedor UC30 HTTP'),
                    ('RESPONSABLE_PRODUCCION','Produccion UC30 HTTP'),
                    ('REPARTIDOR','Repartidor UC30 HTTP'),('PROVEEDOR','Proveedor UC30 HTTP')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            String[] roles={"ADMINISTRADOR","VENDEDOR","RESPONSABLE_PRODUCCION",
                    "REPARTIDOR","PROVEEDOR","ADMINISTRADOR","ADMINISTRADOR"};
            for(int i=0;i<roles.length;i++) {
                jdbc.update("""
                        INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta")
                        VALUES(?,?,'HASH_SINTETICO','HABILITADO',?,clock_timestamp())
                        """,base+i,"usuario_uc30_http_"+(base+i),i==5);
                jdbc.update("""
                        INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                        VALUES(?,?,clock_timestamp())
                        """,base+i,roles[i]);
            }
            jdbc.update("""
                    INSERT INTO "PROVEEDOR"("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                    VALUES(?,?,'Proveedor sintetico UC30','ACTIVO',clock_timestamp())
                    """,base+8,String.format("%011d",30000000000L+base));
            jdbc.update("""
                    INSERT INTO "USUARIO_PROVEEDOR"("idUsuario","idProveedor","fechaHoraVinculacion")
                    VALUES(?,?,clock_timestamp())
                    """,base+4,base+8);
            sesionAdmin=UUID.randomUUID();
            admin=sesion(base,sesionAdmin);
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

    private Map<String,Object> datos(String nombre,String tipo) {
        return Map.of("nombre",nombre,"tipoProducto",tipo,"codigoUnidadVenta","UN");
    }

    private HttpResponse<String> enviar(String token,Object body) throws Exception {
        return enviarTexto(token,json.writeValueAsString(body));
    }

    private HttpResponse<String> enviarTexto(String token,String body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+RUTA))
                .timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int productos() {
        return jdbc.queryForObject("SELECT count(*) FROM \"PRODUCTO\"",Integer.class);
    }

    private int dependencias() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM "PRECIO_PRODUCTO")+
                       (SELECT count(*) FROM "AUDITORIA_PRECIO")+
                       (SELECT count(*) FROM "RECETA")+
                       (SELECT count(*) FROM "MOVIMIENTO_STOCK_REVENTA")+
                       (SELECT count(*) FROM "VALORACION_EXTRAORDINARIA_REVENTA")
                """,Integer.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"ELABORADO","REVENTA"})
    void adminRegistraMaestroActivoSinInventarPrecioRecetaCostoOStock(String tipo) throws Exception {
        int antes=productos(),historia=dependencias();
        String nombre="  Producto   "+base;
        var resp=enviar(admin,Map.of("nombre",nombre,"descripcion"," Ficha sintetica ",
                "tipoProducto",tipo,"codigoUnidadVenta","UN"));
        assertEquals(201,resp.statusCode(),resp.body());
        assertTrue(resp.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var r=json.readTree(resp.body());
        assertEquals(2,r.size(),resp.body());
        long id=r.get("idProducto").asLong();
        assertTrue(id>0);
        assertTrue(r.get("activo").asBoolean());
        var p=jdbc.queryForMap("SELECT * FROM \"PRODUCTO\" WHERE \"idProducto\"=?",id);
        assertEquals("Producto "+base,p.get("nombre"));
        assertEquals(("producto "+base).toLowerCase(Locale.ROOT),p.get("nombreNormalizado"));
        assertEquals("Ficha sintetica",p.get("descripcion"));
        assertEquals(tipo,p.get("tipoProducto"));
        assertEquals("UN",p.get("codigoUnidadVenta"));
        assertNull(p.get("stockActualReventa"));
        assertNull(p.get("costoPromedioActualReventa"));
        assertEquals(antes+1,productos());
        assertEquals(historia,dependencias());
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void duplicadoNormalizadoDevuelveCoincidenciaMinimaActivaOInactiva(boolean activo) throws Exception {
        int antes=productos();
        var creado=enviar(admin,datos("Duplicado "+base,"REVENTA"));
        assertEquals(201,creado.statusCode(),creado.body());
        long id=json.readTree(creado.body()).get("idProducto").asLong();
        jdbc.update("UPDATE \"PRODUCTO\" SET \"activo\"=? WHERE \"idProducto\"=?",activo,id);
        var duplicado=enviar(admin,datos("  DUPLICADO   "+base+"  ","ELABORADO"));
        assertEquals(409,duplicado.statusCode(),duplicado.body());
        assertTrue(duplicado.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var r=json.readTree(duplicado.body());
        assertEquals("PRODUCTO_DUPLICADO",r.get("codigo").asText());
        var existente=r.get("coincidencia");
        assertEquals(6,existente.size(),duplicado.body());
        assertEquals(id,existente.get("idProducto").asLong());
        assertEquals(activo,existente.get("activo").asBoolean());
        assertEquals("REVENTA",existente.get("tipoProducto").asText());
        assertFalse(duplicado.body().contains("costo"));
        assertFalse(duplicado.body().contains("stock"));
        assertFalse(duplicado.body().contains("nombreNormalizado"));
        assertEquals(antes+1,productos());
    }

    @Test
    void rechazaCamposTiposYFormatoInvalidosSinCrearProductos() throws Exception {
        int antes=productos();
        for(Object payload:List.of(
                Map.of(),datos(" ","REVENTA"),datos("X".repeat(151),"REVENTA"),
                datos("X\nY","REVENTA"),
                datos("Invalido "+base,"MIXTO"),datos("Invalido "+base,"reventa"),
                Map.of("nombre",123,"tipoProducto","REVENTA","codigoUnidadVenta","UN"),
                Map.of("nombre","Invalido "+base,"tipoProducto","REVENTA","codigoUnidadVenta",true),
                Map.of("nombre","Invalido "+base,"tipoProducto","REVENTA","codigoUnidadVenta","NO_EXISTE"),
                Map.of("nombre","Invalido "+base,"tipoProducto","REVENTA","codigoUnidadVenta","UN","descripcion",23),
                Map.of("nombre","Invalido "+base,"tipoProducto","REVENTA","codigoUnidadVenta","UN","descripcion","X".repeat(10001)))) {
            assertEquals(400,enviar(admin,payload).statusCode(),payload.toString());
        }
        for(String extra:List.of("idProducto","idUsuario","activo","precio","stockActualReventa","costoPromedioActualReventa")) {
            var payload=new java.util.HashMap<>(datos("Invalido "+base,"REVENTA"));
            payload.put(extra,1);
            assertEquals(400,enviar(admin,payload).statusCode(),extra);
        }
        for(String invalido:List.of("{JSON","[]","null",
                "{\"nombre\":null,\"tipoProducto\":\"REVENTA\",\"codigoUnidadVenta\":\"UN\"}",
                "{\"nombre\":\"X\",\"tipoProducto\":\"REVENTA\",\"codigoUnidadVenta\":\"UN\",\"descripcion\":\"\\u0000\"}")) {
            var r=enviarTexto(admin,invalido);
            assertEquals(400,r.statusCode(),r.body());
            assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        }
        assertEquals(antes,productos());
    }

    @Test
    void soloAdminNoAceptaCookieUuidSesionNiHabilitaOtrasOperaciones() throws Exception {
        int antes=productos();
        var body=datos("No autorizado "+base,"REVENTA");
        assertEquals(401,enviar(null,body).statusCode());
        assertEquals(401,enviar(sesionAdmin.toString(),body).statusCode());
        for(String token:List.of(vendedor,produccion,repartidor,proveedor,pendiente)) {
            assertEquals(403,enviar(token,body).statusCode());
        }
        var cookie=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+RUTA))
                .header("Cookie","__Host-ale_session="+admin).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        assertEquals(401,http.send(cookie,HttpResponse.BodyHandlers.ofString()).statusCode());
        for(String ruta:List.of(RUTA,"/api/v1/productos/1","/api/v1/clientes")) {
            String metodo=ruta.equals(RUTA)?"DELETE":ruta.contains("productos")?"PUT":"POST";
            var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+ruta))
                    .header("Authorization","Bearer "+admin).header("Content-Type","application/json")
                    .method(metodo,HttpRequest.BodyPublishers.ofString("{}")).build();
            assertEquals(403,http.send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(antes,productos());
    }

    @Test
    void revalidaRevocacionEstadoYRolSinConfiarEnElTokenAnterior() throws Exception {
        int antes=productos();
        var body=datos("Revocado "+base,"REVENTA");
        jdbc.update("""
                UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=clock_timestamp()
                 WHERE "idSesionUsuario"=?
                """,sesionAdmin);
        assertEquals(401,enviar(admin,body).statusCode());
        String otro=tx.execute(status -> sesion(base,UUID.randomUUID()));
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                    VALUES(?,'VENDEDOR',clock_timestamp())
                    """,base);
            jdbc.update("DELETE FROM \"USUARIO_ROL\" WHERE \"idUsuario\"=? AND \"codigoRol\"='ADMINISTRADOR'",base);
        });
        assertTrue(List.of(401,403).contains(enviar(otro,body).statusCode()));
        String vendedorActual=tx.execute(status -> sesion(base,UUID.randomUUID()));
        assertEquals(403,enviar(vendedorActual,body).statusCode());
        jdbc.update("""
                UPDATE "USUARIO" SET "estado"='DESHABILITADO',"fechaHoraDeshabilitacion"=clock_timestamp()
                 WHERE "idUsuario"=?
                """,base);
        assertEquals(401,enviar(vendedorActual,body).statusCode());
        assertEquals(antes,productos());
    }

    @Test
    void errorDespuesDelInsertRevierteAltaYSinFiltrarSqlPermiteReintento() throws Exception {
        int antes=productos();
        jdbc.execute("""
                CREATE FUNCTION "fn_uc30_http_error"() RETURNS trigger LANGUAGE plpgsql
                AS $$ BEGIN RAISE EXCEPTION 'Detalle privado UC30'; END; $$
                """);
        try {
            jdbc.execute("""
                    CREATE TRIGGER "tg_uc30_http_error" AFTER INSERT ON "PRODUCTO"
                    FOR EACH ROW EXECUTE FUNCTION "fn_uc30_http_error"()
                    """);
            try {
                var r=enviar(admin,datos("Rollback "+base,"REVENTA"));
                assertEquals(503,r.statusCode(),r.body());
                assertEquals("PERSISTENCIA_NO_DISPONIBLE",json.readTree(r.body()).get("codigo").asText());
                assertFalse(r.body().contains("Detalle privado"));
                assertEquals(antes,productos());
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS \"tg_uc30_http_error\" ON \"PRODUCTO\"");
            }
        } finally {
            jdbc.execute("DROP FUNCTION IF EXISTS \"fn_uc30_http_error\"()");
        }
        assertEquals(201,enviar(admin,datos("Rollback "+base,"REVENTA")).statusCode());
        assertEquals(antes+1,productos());
    }

    @Test
    void dosPostConcurrentesConfirmanUnAltaYUnaCoincidenciaSinDuplicar() throws Exception {
        int antes=productos(),historia=dependencias();
        var inicio=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{inicio.await(10,TimeUnit.SECONDS);
                return enviar(admin,datos("Concurrente "+base,"REVENTA"));});
            var b=pool.submit(()->{inicio.await(10,TimeUnit.SECONDS);
                return enviar(admin,datos("  CONCURRENTE   "+base,"REVENTA"));});
            inicio.countDown();
            var ra=a.get(30,TimeUnit.SECONDS);
            var rb=b.get(30,TimeUnit.SECONDS);
            assertEquals(List.of(201,409),List.of(ra.statusCode(),rb.statusCode()).stream().sorted().toList());
            var alta=ra.statusCode()==201?ra:rb;
            var duplicado=ra.statusCode()==409?ra:rb;
            assertEquals(json.readTree(alta.body()).get("idProducto").asLong(),
                    json.readTree(duplicado.body()).get("coincidencia").get("idProducto").asLong());
        }
        assertEquals(antes+1,productos());
        assertEquals(historia,dependencias());
    }
}
