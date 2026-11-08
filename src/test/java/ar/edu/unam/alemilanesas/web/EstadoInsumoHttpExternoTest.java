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
class EstadoInsumoHttpExternoTest extends InsumosHttpSoporte {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);
    }
    @Test void patchValidoRetorna200() throws Exception {
        var r=enviar("PATCH",ruta()+"/estado",Map.of("activo",false));assertRespuesta(200,null,r);
        var d=json.readTree(r.body());assertEquals(4,d.size());assertFalse(d.get("activo").asBoolean());assertTrue(d.get("huboCambio").asBoolean());
        assertRespuesta(200,null,enviar("PATCH",ruta()+"/estado",Map.of("activo",true)));
        for(String raw:List.of("{}","null","[]","{\"activo\":\"false\"}","{\"activo\":null}","{\"activo\":0}",
                "{\"activo\":true,\"extra\":1}","{\"activo\":true,\"activo\":false}","{\"activo\":true} true"))
            assertRespuesta(400,"SOLICITUD_INVALIDA",request(port,"PATCH",ruta()+"/estado",admin(),raw,null));
        assertRespuesta(404,"INSUMO_NO_ENCONTRADO",enviar("PATCH",RAIZ+"/9223372036854775807/estado",Map.of("activo",false)));
        assertRespuesta(400,"SOLICITUD_INVALIDA",enviar("PATCH",RAIZ+"/no-id/estado",Map.of("activo",false)));
    }
    @Test void patchMismoEstadoRetorna409() throws Exception {
        assertRespuesta(409,"SIN_CAMBIO",enviar("PATCH",ruta()+"/estado",Map.of("activo",true)));
        assertTrue(j.queryForObject("SELECT \"activo\" FROM \"INSUMO\" WHERE \"idInsumo\"=?",Boolean.class,f.base+1));
    }
    @Test void patchRetornaDependenciasCompletas() throws Exception {
        var r=enviar("PATCH",RAIZ+"/"+f.base+"/estado",Map.of("activo",false));assertRespuesta(200,null,r);
        var d=json.readTree(r.body()).get("dependencias");assertEquals(7,d.size());
        for(String campo:List.of("recetas","movimientos","recepciones","costos","proveedores","episodios","partidas"))assertNotNull(d.get(campo));
        assertEquals(1,d.get("partidas").asInt());assertEquals(1,d.get("costos").asInt());assertEquals(1,d.get("proveedores").asInt());
        assertEquals(new BigDecimal("10.000"),j.queryForObject("SELECT \"stockActual\" FROM \"INSUMO\" WHERE \"idInsumo\"=?",BigDecimal.class,f.base));
        assertEquals("PENDIENTE",j.queryForObject("SELECT \"estado\" FROM \"ORDEN_COMPRA\" WHERE \"idOrdenCompra\"=?",String.class,f.base));
    }
    @Test void patchSinTokenRetorna401() throws Exception {assertSinToken("PATCH",ruta()+"/estado",Map.of("activo",false));}
    @Test void patchConRolVendedorRetorna403() throws Exception {assertRoles("PATCH",ruta()+"/estado",Map.of("activo",false));}
    @Test void patchConGateDesactivadoRetorna403() throws Exception {
        try(var ctx=contexto(Set.of("50","51"))) {
            int p=puerto(ctx);assertTrue(ctx.getBeansOfType(EstadoInsumoHttpController.class).isEmpty());
            assertRespuesta(403,"OPERACION_PROHIBIDA",request(p,"PATCH",ruta()+"/estado",admin(),"{\"activo\":false}",null));
            assertRespuesta(201,null,request(p,"POST",RAIZ,admin(),json.writeValueAsString(alta()),null));
            assertRespuesta(200,null,request(p,"PUT",ruta(),admin(),json.writeValueAsString(modificar("KG")),null));
        }
    }
}
