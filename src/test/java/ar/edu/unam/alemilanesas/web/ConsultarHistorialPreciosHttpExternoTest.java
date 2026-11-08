package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.ActualizarPrecioVigenteService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConsultarHistorialPreciosHttpExternoTest extends PreciosHttpSoporte {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired ActualizarPrecioVigenteService actualizar;
    @BeforeEach void historial() {
        tx.executeWithoutResult(status->{
            j.update("""
                    INSERT INTO "PRECIO_PRODUCTO" ("idPrecio","idProducto","precioBase","vigenteDesde",
                        "vigenteHasta","fechaHoraRegistro","idUsuarioRegistro") VALUES
                    (?,?,100,clock_timestamp()-interval '4 days',clock_timestamp()-interval '2 days',clock_timestamp()-interval '5 days',?),
                    (?,?,200,clock_timestamp()-interval '1 day',NULL,clock_timestamp()-interval '2 days',?)
                    """,f.base+20,f.base,f.base,f.base+21,f.base,f.base);
            j.update("""
                    INSERT INTO "AUDITORIA_PRECIO" ("idAuditoriaPrecio","idPrecio","idUsuario","fechaHoraEvento",
                        "tipoEvento","precioAnterior","precioNuevo","vigenteDesdeNuevo","vigenteHastaNuevo")
                    SELECT "idPrecio", "idPrecio", "idUsuarioRegistro", "fechaHoraRegistro", 'MODIFICACION_PROGRAMADA',
                        NULL,"precioBase","vigenteDesde","vigenteHasta"
                    FROM "PRECIO_PRODUCTO" WHERE "idProducto"=?
                    """,f.base);
        });
    }
    @Test void getRetorna200ConHistorialCompleto() throws Exception {
        var nuevo=actualizar.actualizarPrecioVigente(f.sesiones.get("ADMINISTRADOR"),f.base,new BigDecimal("300"),futuro(2),null);
        var r=get(admin(),"");respuesta(200,null,r);var d=json.readTree(r.body());
        assertEquals(f.base,d.get("idProducto").asLong());
        assertEquals(f.base+21,d.get("idPrecioActual").asLong());
        assertNotNull(OffsetDateTime.parse(d.get("fechaConsulta").asString()));
        assertEquals(3,d.get("precios").size());
        for(int i=0;i<3;i++)assertEquals(List.of("HISTORICO","ACTUAL","FUTURO").get(i),d.get("precios").get(i).get("clasificacion").asString());
        assertEquals(nuevo.idPrecio(),d.get("precios").get(2).get("idPrecio").asLong());
        assertEquals(4,d.get("auditorias").size());
        assertEquals(3,j.queryForObject("SELECT count(*) FROM \"PRECIO_PRODUCTO\" WHERE \"idProducto\"=?",Integer.class,f.base));
        assertEquals(4,j.queryForObject("SELECT count(*) FROM \"AUDITORIA_PRECIO\" a JOIN \"PRECIO_PRODUCTO\" p USING (\"idPrecio\") WHERE p.\"idProducto\"=?",Integer.class,f.base));
    }
    @Test void getConRangoValidoRetorna200() throws Exception {
        LocalDate hoy=LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires"));
        var r=get(admin(),"?desde="+hoy+"&hasta="+hoy);respuesta(200,null,r);
        var d=json.readTree(r.body());assertEquals(1,d.get("precios").size());assertEquals(1,d.get("auditorias").size());
        assertEquals(f.base+21,d.get("precios").get(0).get("idPrecio").asLong());
        respuesta(200,null,get(admin(),"?desde="+hoy));respuesta(200,null,get(admin(),"?hasta="+hoy));
    }
    @Test void getConRangoInvalidoRetorna400() throws Exception {
        respuesta(400,"RANGO_INVALIDO",get(admin(),"?desde=2026-12-10&hasta=2026-11-10"));
        for(String query:List.of("?desde=2026-02-30","?hasta=sin-fecha","?desde=","?desde=2026-11-10T00:00:00"))
            respuesta(400,"SOLICITUD_INVALIDA",get(admin(),query));
        respuesta(400,"SOLICITUD_INVALIDA",request(port,"GET","/api/v1/productos/no-es-id/precios",admin(),null,null));
        respuesta(400,"SOLICITUD_INVALIDA",request(port,"GET","/api/v1/productos/0/precios",admin(),null,null));
    }
    @Test void getConParametroDesconocidoRetorna400() throws Exception {
        respuesta(400,"SOLICITUD_INVALIDA",get(admin(),"?intruso=1"));
        respuesta(400,"SOLICITUD_INVALIDA",get(admin(),"?desde=2026-11-10&desde=2026-11-11"));
        respuesta(400,"SOLICITUD_INVALIDA",get(admin(),"?hasta=2026-11-10&hasta=2026-11-10"));
    }
    @Test void getSinTokenRetorna401() throws Exception {
        respuesta(401,"SESION_INVALIDA",get(null,""));
        respuesta(401,"SESION_INVALIDA",request(port,"GET",ruta(),null,null,"JSESSIONID="+admin()));
    }
    @Test void getConRolVendedorRetorna403() throws Exception {
        for(String rol:List.of("VENDEDOR","RESPONSABLE_PRODUCCION","REPARTIDOR","PROVEEDOR"))
            respuesta(403,"OPERACION_PROHIBIDA",get(f.tokens.get(rol),""));
    }
    @Test void cacheControlNoStoreEnTodasLasRespuestas() throws Exception {
        respuesta(200,null,get(admin(),""));respuesta(400,"RANGO_INVALIDO",get(admin(),"?desde=2026-12-10&hasta=2026-11-10"));
        respuesta(401,"SESION_INVALIDA",get(null,""));respuesta(403,"OPERACION_PROHIBIDA",get(f.tokens.get("VENDEDOR"),""));
        respuesta(400,"SOLICITUD_INVALIDA",request(port,"GET","/api/v1/productos/9223372036854775807/precios",admin(),null,null));
        j.execute("ALTER TABLE \"PRECIO_PRODUCTO\" RENAME TO \"PRECIO_PRODUCTO_TEST_OFFLINE\"");
        try {respuesta(503,"PERSISTENCIA_NO_DISPONIBLE",get(admin(),""));}
        finally {j.execute("ALTER TABLE \"PRECIO_PRODUCTO_TEST_OFFLINE\" RENAME TO \"PRECIO_PRODUCTO\"");}
        long vacio=f.base+1;
        var sinPrecio=request(port,"GET","/api/v1/productos/"+vacio+"/precios",admin(),null,null);respuesta(200,null,sinPrecio);
        var d=json.readTree(sinPrecio.body());assertTrue(d.get("idPrecioActual").isNull());
        assertEquals(0,d.get("precios").size());assertEquals(0,d.get("auditorias").size());
    }
}
