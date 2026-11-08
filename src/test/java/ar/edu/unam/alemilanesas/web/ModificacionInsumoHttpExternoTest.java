package ar.edu.unam.alemilanesas.web;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"ale.http.writes.insumos-uc50-enabled=true","ale.http.writes.insumos-uc51-enabled=true","ale.http.writes.insumos-uc52-enabled=true"})
@Testcontainers
class ModificacionInsumoHttpExternoTest extends InsumosHttpSoporte {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);
    }
    @Test void putNombreRetorna200() throws Exception {
        var r=enviar("PUT",RAIZ+"/"+f.base,modificar("KG"));assertRespuesta(200,null,r);
        var d=json.readTree(r.body());assertEquals("Renombrado "+f.base,d.get("nombre").asString());
        assertEquals(10,d.get("stockActual").asDouble());assertTrue(d.get("huboCambio").asBoolean());
        assertRespuesta(200,null,enviar("PUT",RAIZ+"/"+f.base,modificar("KG")));
    }
    @Test void putPermiteCambioUnidadSinHistoria() throws Exception {
        var r=enviar("PUT",ruta(),modificar("G"));assertRespuesta(200,null,r);
        assertEquals("G",json.readTree(r.body()).get("codigoUnidadMedida").asString());
        assertEquals(0,json.readTree(r.body()).get("stockActual").asDouble());
        assertEquals(new BigDecimal("5.000"),j.queryForObject("SELECT \"umbralMinimo\" FROM \"INSUMO\" WHERE \"idInsumo\"=?",BigDecimal.class,f.base+1));
    }
    @Test void putBloqueaCambioUnidadConPartidas() throws Exception {
        partidaAgotada();assertRespuesta(409,"CAMBIO_UNIDAD_BLOQUEADO",enviar("PUT",ruta(),modificar("G")));
        assertEquals("KG",j.queryForObject("SELECT \"codigoUnidadMedida\" FROM \"INSUMO\" WHERE \"idInsumo\"=?",String.class,f.base+1));
        for(String tabla:List.of("MOVIMIENTO_INSUMO","COSTO_INSUMO_HISTORICO"))
            assertEquals(0,j.queryForObject("SELECT count(*) FROM \""+tabla+"\" WHERE \"idInsumo\"=?",Integer.class,f.base+1));
        assertRespuesta(200,null,enviar("PUT",ruta(),modificar("KG")));
    }
    @Test void putRechazaStockActualEnBody() throws Exception {
        for(String campo:List.of("stockActual","umbralMinimo","activo","automatizacionCompraActiva","extra")) {
            var b=modificar("KG");b.put(campo,0);assertRespuesta(400,"SOLICITUD_INVALIDA",enviar("PUT",ruta(),b));
        }
        assertRespuesta(400,"UNIDAD_INEXISTENTE",enviar("PUT",ruta(),modificar("INEXISTENTE")));
        assertRespuesta(404,"INSUMO_NO_ENCONTRADO",enviar("PUT",RAIZ+"/9223372036854775807",modificar("KG")));
        assertRespuesta(400,"SOLICITUD_INVALIDA",enviar("PUT",RAIZ+"/no-id",modificar("KG")));
        for(String raw:List.of("{\"nombre\":\"Nuevo\"}","{\"nombre\":false,\"codigoUnidadMedida\":\"KG\"}",
                "{\"nombre\":\"Uno\",\"nombre\":\"Dos\",\"codigoUnidadMedida\":\"KG\"}"))
            assertRespuesta(400,"SOLICITUD_INVALIDA",request(port,"PUT",ruta(),admin(),raw,null));
    }
    @Test void putSinTokenRetorna401() throws Exception {assertSinToken("PUT",ruta(),modificar("KG"));}
    @Test void putConRolVendedorRetorna403() throws Exception {assertRoles("PUT",ruta(),modificar("KG"));}
    @Test void putConGateDesactivadoRetorna403() throws Exception {
        try(var ctx=contexto(Set.of("50","52"))) {
            int p=puerto(ctx);assertTrue(ctx.getBeansOfType(ModificacionInsumoHttpController.class).isEmpty());
            assertRespuesta(403,"OPERACION_PROHIBIDA",request(p,"PUT",ruta(),admin(),json.writeValueAsString(modificar("KG")),null));
            assertRespuesta(201,null,request(p,"POST",RAIZ,admin(),json.writeValueAsString(alta()),null));
            assertRespuesta(200,null,request(p,"PATCH",ruta()+"/estado",admin(),"{\"activo\":false}",null));
        }
    }
}
