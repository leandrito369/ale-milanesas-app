package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** UC55 B4: HTTP real, roles, estado, sesiones, RD-RP009-001 y F07. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="ale.http.writes.usuarios-uc55-enabled=true")
@Testcontainers(disabledWithoutDocker=true)
class EstadoUsuarioHttpExternoTest {
    @Container
    static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url",db::getJdbcUrl);
        registro.add("spring.datasource.username",db::getUsername);
        registro.add("spring.datasource.password",db::getPassword);
    }

    private static final AtomicLong SECUENCIA=new AtomicLong(999000);
    private static final String HABILITAR="{\"nuevoEstado\":\"HABILITADO\"}";
    private static final String DESHABILITAR="{\"nuevoEstado\":\"DESHABILITADO\"}";
    private static final String RUTA="/api/v1/usuarios";

    @Value("${local.server.port}") int puerto;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private long base;
    private UUID sesionVendedor, sesionRepartidor;
    private String adminA,adminB,vendedor,repartidor,proveedor,pendiente;

    @BeforeEach
    void preparar() {
        base=SECUENCIA.addAndGet(30);
        // PostgreSQL Testcontainers exclusivo de esta clase: no arrastrar
        // administradores entre pruebas de F07.
        jdbc.execute("TRUNCATE TABLE \"USUARIO\" CASCADE");
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "ROL"("codigoRol","nombreMostrado") VALUES
                    ('ADMINISTRADOR','Administrador UC55 HTTP'),
                    ('VENDEDOR','Vendedor UC55 HTTP'),
                    ('REPARTIDOR','Repartidor UC55 HTTP'),
                    ('PROVEEDOR','Proveedor UC55 HTTP')
                    ON CONFLICT("codigoRol") DO NOTHING
                    """);
            String[] roles={"ADMINISTRADOR","ADMINISTRADOR","VENDEDOR",
                    "REPARTIDOR","PROVEEDOR","PROVEEDOR","VENDEDOR","VENDEDOR"};
            for (int i=0;i<roles.length;i++) {
                boolean deshabilitado=i==5 || i==6;
                jdbc.update("""
                        INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                            "estado","cambioCredencialPendiente","fechaHoraAlta",
                            "fechaHoraDeshabilitacion")
                        VALUES(?,?,'HASH_SINTETICO',?,?,clock_timestamp(),
                            CASE WHEN ? THEN clock_timestamp()-interval '1 day' ELSE NULL END)
                        """,base+i,"usuario_uc55_http_"+(base+i),
                        deshabilitado?"DESHABILITADO":"HABILITADO",i==7,deshabilitado);
                jdbc.update("""
                        INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                        VALUES(?,?,clock_timestamp())
                        """,base+i,roles[i]);
            }
            jdbc.update("""
                    INSERT INTO "PROVEEDOR"("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                    VALUES(?,?,'Proveedor sintetico UC55','ACTIVO',clock_timestamp())
                    """,base+20,String.format("%011d",32000000000L+base));
            jdbc.update("""
                    INSERT INTO "USUARIO_PROVEEDOR"("idUsuario","idProveedor","fechaHoraVinculacion")
                    VALUES(?,?,clock_timestamp()),(?,?,clock_timestamp())
                    """,base+4,base+20,base+5,base+20);
            adminA=sesion(base,UUID.randomUUID(),false);
            adminB=sesion(base+1,UUID.randomUUID(),false);
            sesionVendedor=UUID.randomUUID();
            vendedor=sesion(base+2,sesionVendedor,false);
            sesionRepartidor=UUID.randomUUID();
            repartidor=sesion(base+3,sesionRepartidor,false);
            proveedor=sesion(base+4,UUID.randomUUID(),false);
            // Cuenta inhabilitada: la sesión antigua se conserva revocada
            // para verificar que la habilitación no la reabra.
            sesion(base+6,UUID.randomUUID(),true);
            pendiente=sesion(base+7,UUID.randomUUID(),false);
        });
    }

    private String sesion(long idUsuario,UUID idSesion,boolean revocada) {
        String token=TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                    "fechaHoraInicio","fechaHoraExpiracion","fechaHoraRevocacion")
                VALUES(?,?,?,clock_timestamp()-interval '1 minute',
                           clock_timestamp()+interval '29 minutes',
                           CASE WHEN ? THEN clock_timestamp()-interval '30 seconds' ELSE NULL END)
                """,idSesion,idUsuario,TokenSesionInterna.huella(token).orElseThrow(),revocada);
        return token;
    }

    private HttpResponse<String> patch(String token,long usuario,String cuerpo) throws Exception {
        var pedido=HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:"+puerto+RUTA+"/"+usuario+"/estado"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString(cuerpo));
        if (token!=null) pedido.header("Authorization","Bearer "+token);
        return http.send(pedido.build(),HttpResponse.BodyHandlers.ofString());
    }

    private String estado(long usuario) {
        return jdbc.queryForObject("""
                SELECT "estado" FROM "USUARIO" WHERE "idUsuario"=?
                """,String.class,usuario);
    }

    private Set<String> roles(long usuario) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT "codigoRol" FROM "USUARIO_ROL" WHERE "idUsuario"=?
                """,String.class,usuario));
    }

    private Object revocacion(UUID idSesion) {
        return jdbc.queryForObject("""
                SELECT "fechaHoraRevocacion" FROM "SESION_USUARIO"
                 WHERE "idSesionUsuario"=?
                """,Object.class,idSesion);
    }

    @Test
    void bajaLogicaRevocaSesionPreservaRolYRestringeProyeccion() throws Exception {
        var r=patch(adminA,base+2,DESHABILITAR);
        assertEquals(200,r.statusCode(),r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var salida=json.readTree(r.body());
        assertEquals(3,salida.size(),r.body());
        assertEquals(base+2,salida.get("idUsuario").asLong());
        assertEquals("DESHABILITADO",salida.get("estado").asText());
        assertTrue(salida.get("huboCambio").asBoolean());
        assertEquals("DESHABILITADO",estado(base+2));
        assertEquals(Set.of("VENDEDOR"),roles(base+2));
        assertNotNull(revocacion(sesionVendedor));
        assertNotNull(jdbc.queryForObject("""
                SELECT "fechaHoraDeshabilitacion" FROM "USUARIO"
                 WHERE "idUsuario"=?
                """,Object.class,base+2));
        assertFalse(r.body().contains("credencialProtegida"));
        assertFalse(r.body().contains("tokenProtegido"));
    }

    @Test
    void habilitacionLimpiaMarcaSinReabrirSesiones() throws Exception {
        var r=patch(adminA,base+6,HABILITAR);
        assertEquals(200,r.statusCode(),r.body());
        assertEquals("HABILITADO",estado(base+6));
        assertNull(jdbc.queryForObject("""
                SELECT "fechaHoraDeshabilitacion" FROM "USUARIO"
                 WHERE "idUsuario"=?
                """,Object.class,base+6));
        assertEquals(Set.of("VENDEDOR"),roles(base+6));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                 WHERE "idUsuario"=? AND "fechaHoraRevocacion" IS NOT NULL
                """,Integer.class,base+6));
    }

    @Test
    void a1NoOpNoRevocaSesionNiAlteraDatos() throws Exception {
        var r=patch(adminA,base+2,HABILITAR);
        assertEquals(200,r.statusCode(),r.body());
        assertFalse(json.readTree(r.body()).get("huboCambio").asBoolean());
        assertNull(revocacion(sesionVendedor));
        assertEquals(Set.of("VENDEDOR"),roles(base+2));
        assertNull(jdbc.queryForObject("""
                SELECT "fechaHoraDeshabilitacion" FROM "USUARIO"
                 WHERE "idUsuario"=?
                """,Object.class,base+2));
    }

    @Test
    void f07ImpideDeshabilitarUltimoAdmin() throws Exception {
        assertEquals(200,patch(adminA,base+1,DESHABILITAR).statusCode());
        var r=patch(adminA,base,DESHABILITAR);
        assertEquals(409,r.statusCode(),r.body());
        assertEquals("ESTADO_USUARIO_BLOQUEADO",
                json.readTree(r.body()).get("codigo").asText());
        assertEquals("HABILITADO",estado(base));
        assertEquals("DESHABILITADO",estado(base+1));
        assertEquals(Set.of("ADMINISTRADOR"),roles(base));
    }

    @Test
    void proveedorDuplicadoSeBloqueaPeroPuedeHabilitarseLuego() throws Exception {
        var r=patch(adminA,base+5,HABILITAR);
        assertEquals(409,r.statusCode(),r.body());
        assertEquals("DESHABILITADO",estado(base+5));
        assertEquals(200,patch(adminA,base+4,DESHABILITAR).statusCode());
        assertEquals(200,patch(adminA,base+5,HABILITAR).statusCode());
        assertEquals("HABILITADO",estado(base+5));
        assertEquals("DESHABILITADO",estado(base+4));
        assertEquals(2,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO_PROVEEDOR" WHERE "idProveedor"=?
                """,Integer.class,base+20));
        assertEquals(Set.of("PROVEEDOR"),roles(base+5));
    }

    @Test
    void a4RepartidorPendienteInformaIdsSinDeshabilitarNiReasignar() throws Exception {
        prepararPedidoPendiente();
        var r=patch(adminA,base+3,DESHABILITAR);
        assertEquals(409,r.statusCode(),r.body());
        var salida=json.readTree(r.body());
        assertEquals("REPARTIDOR_CON_PEDIDOS_PENDIENTES",salida.get("codigo").asText());
        assertEquals(1,salida.get("pedidosPendientes").size());
        assertEquals(base+110,salida.get("pedidosPendientes").get(0).asLong());
        assertEquals("HABILITADO",estado(base+3));
        assertNull(revocacion(sesionRepartidor));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "ASIGNACION_REPARTO"
                 WHERE "idPedido"=? AND "fechaHoraFin" IS NULL
                """,Integer.class,base+110));

        // Simula que UC43 resolvió la asignación; sin servicio UC43 implementado
        // todavía, el cierre se hace por SQL dentro de esta prueba sintética.
        jdbc.update("""
                UPDATE "ASIGNACION_REPARTO" SET "fechaHoraFin"=clock_timestamp()
                 WHERE "idPedido"=?
                """,base+110);
        assertEquals(200,patch(adminA,base+3,DESHABILITAR).statusCode());
        assertEquals("DESHABILITADO",estado(base+3));
        assertNotNull(revocacion(sesionRepartidor));
        assertEquals("PENDIENTE",jdbc.queryForObject("""
                SELECT "estado" FROM "PEDIDO" WHERE "idPedido"=?
                """,String.class,base+110));
    }

    private void prepararPedidoPendiente() {
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO "CLIENTE"("idCliente","nombres","telefonoPrincipal",
                        "estado","categoriaComercial","fechaHoraAlta")
                    VALUES(?,'Cliente sintetico UC55','3764999111','ACTIVO',
                        'CUMPLIDOR',clock_timestamp())
                    """,base+100);
            jdbc.update("""
                    INSERT INTO "POLITICA_CATEGORIA_COMERCIAL"
                        ("idPoliticaComercial","diasToleranciaMora",
                         "vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                    VALUES(?,7,clock_timestamp()-interval '1 day',
                           clock_timestamp(),?)
                    """,base+101,base);
            jdbc.update("""
                    INSERT INTO "PEDIDO"
                        ("idPedido","idCliente","idUsuarioCreador","fechaHoraCarga",
                         "fechaReparto","canalOrigen","estado","categoriaComercialAplicada",
                         "idPoliticaComercialAplicada","tipoAjuste","porcentajeAjuste",
                         "totalOriginal","direccionEntregaCalle","direccionEntregaLocalidad")
                    VALUES(?,?,?,clock_timestamp(),current_date,'INTERNO','PENDIENTE',
                        'CUMPLIDOR',?,'SIN_AJUSTE',0,100.00,'Calle sintetica','Posadas')
                    """,base+110,base+100,base+2,base+101);
            jdbc.update("""
                    INSERT INTO "ASIGNACION_REPARTO"
                        ("idPedido","numeroAsignacion","idUsuarioRepartidor",
                         "idUsuarioVendedor","fechaHoraInicio")
                    VALUES(?,1,?,?,clock_timestamp()-interval '1 minute')
                    """,base+110,base+3,base+2);
        });
    }

    @Test
    void cuerpoInvalidoYOtroUsuarioInexistenteNoEscriben() throws Exception {
        for (String cuerpo:List.of(
                "{}","[]","null","{",
                "{\"nuevoEstado\":true}",
                "{\"nuevoEstado\":null}",
                "{\"nuevoEstado\":\"ACTIVO\"}",
                "{\"nuevoEstado\":\"DESHABILITADO\",\"idProveedor\":123}",
                "{\"nuevoEstado\":\"DESHABILITADO\",\"nuevoEstado\":\"HABILITADO\"}")) {
            var r=patch(adminA,base+2,cuerpo);
            assertEquals(400,r.statusCode(),cuerpo+" / "+r.body());
            assertEquals("SOLICITUD_INVALIDA",
                    json.readTree(r.body()).get("codigo").asText(),cuerpo);
        }
        assertEquals(400,patch(adminA,-1,DESHABILITAR).statusCode());
        var inexistente=patch(adminA,base+999,DESHABILITAR);
        assertEquals(404,inexistente.statusCode(),inexistente.body());
        assertEquals("USUARIO_NO_ENCONTRADO",
                json.readTree(inexistente.body()).get("codigo").asText());
        assertEquals("HABILITADO",estado(base+2));
        assertNull(revocacion(sesionVendedor));
    }

    @Test
    void accesoAdminBearerNoAdmiteCookiesNiCuentaRestringida() throws Exception {
        assertEquals(401,patch(null,base+2,DESHABILITAR).statusCode());
        assertEquals(401,patch(UUID.randomUUID().toString(),base+2,DESHABILITAR)
                .statusCode());
        for (String token:List.of(vendedor,repartidor,proveedor,pendiente)) {
            assertEquals(403,patch(token,base+2,DESHABILITAR).statusCode());
        }
        var pedido=HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:"+puerto+RUTA+"/"+(base+2)+"/estado"))
                .header("Content-Type","application/json")
                .header("Cookie","__Host-ale_session="+adminA)
                .method("PATCH",HttpRequest.BodyPublishers.ofString(DESHABILITAR)).build();
        assertEquals(401,http.send(pedido,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("HABILITADO",estado(base+2));
        assertNull(revocacion(sesionVendedor));
    }

    @Test
    void dosAdministradoresConcurrentesNoSeDeshabilitanAmbos() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var inicio=new CountDownLatch(1);
            var uno=pool.submit(()->{
                if(!inicio.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Inicio A");
                return patch(adminA,base+1,DESHABILITAR).statusCode();
            });
            var dos=pool.submit(()->{
                if(!inicio.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Inicio B");
                return patch(adminB,base,DESHABILITAR).statusCode();
            });
            inicio.countDown();
            int a=uno.get(45,TimeUnit.SECONDS),b=dos.get(45,TimeUnit.SECONDS);
            assertEquals(1,List.of(a,b).stream().filter(x->x==200).count(),a+" / "+b);
            assertTrue(a==200 || a==401 || a==403 || a==409,a+" / "+b);
            assertTrue(b==200 || b==401 || b==403 || b==409,a+" / "+b);
        }
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO" u JOIN "USUARIO_ROL" ur
                  ON ur."idUsuario"=u."idUsuario"
                 WHERE u."estado"='HABILITADO'
                   AND ur."codigoRol"='ADMINISTRADOR'
                   AND u."idUsuario" IN (?,?)
                """,Integer.class,base,base+1));
    }

    @Test
    void errorAlGuardarEstadoRevierteLaTransaccionSinDetalles() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION "fn_uc55_http_error"() RETURNS trigger LANGUAGE plpgsql
                AS $$ BEGIN RAISE EXCEPTION 'Detalle privado UC55'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER "tg_uc55_http_error"
                BEFORE UPDATE ON "USUARIO"
                FOR EACH ROW EXECUTE FUNCTION "fn_uc55_http_error"()
                """);
        try {
            var r=patch(adminA,base+2,DESHABILITAR);
            assertEquals(503,r.statusCode(),r.body());
            assertFalse(r.body().contains("Detalle privado"),r.body());
            assertEquals("HABILITADO",estado(base+2));
            assertNull(revocacion(sesionVendedor));
        } finally {
            jdbc.execute("DROP TRIGGER \"tg_uc55_http_error\" ON \"USUARIO\"");
            jdbc.execute("DROP FUNCTION \"fn_uc55_http_error\"()");
        }
    }
}
