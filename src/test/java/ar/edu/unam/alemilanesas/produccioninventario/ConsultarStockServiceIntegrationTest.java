package ar.edu.unam.alemilanesas.produccioninventario;

import ar.edu.unam.alemilanesas.produccioninventario.ConsultarStockService.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class ConsultarStockServiceIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired ConsultarStockService servicio;
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    StockFixture f;
    UUID produccion;
    @BeforeEach void fixture() {
        f=tx.execute(s->{StockFixture.limpiar(j);return new StockFixture(j,tx);});produccion=f.produccion();
    }
    ResultadoStock consultar(CriterioStock c) {return servicio.consultarStock(produccion,c);}
    StockInsumo primero() {return consultar(new CriterioStock("INSUMO",f.base,null)).insumos().getFirst();}
    @Test void adminRechazado() {
        for(String rol:List.of("ADMINISTRADOR","VENDEDOR","REPARTIDOR","PROVEEDOR")) {
            UUID sesion=f.actores.sesiones.get(rol);assertThrows(SecurityException.class,()->servicio.consultarStock(sesion,null));
        }
        assertThrows(SecurityException.class,()->servicio.consultarStock(null,null));
    }
    @Test void produccionVeInsumosSinFiltros() {
        var antes=snapshot();var r=consultar(null);assertEquals(3,r.insumos().size());assertEquals(3,r.reventa().size());
        var b=r.insumos().get(1);assertEquals(f.base+1,b.idInsumo());assertEquals(0,b.stockActual().compareTo(BigDecimal.ZERO));
        assertEquals(0,b.stockUtilizable().compareTo(BigDecimal.ZERO));assertTrue(b.partidas().isEmpty());assertTrue(b.tieneEpisodioAbierto());
        assertFalse(r.insumos().getFirst().tieneEpisodioAbierto());assertFalse(r.insumos().getLast().tieneEpisodioAbierto());
        assertEquals(0,r.insumos().getLast().stockActual().compareTo(new BigDecimal("5"))); // Inactivo con existencias no se oculta.
        assertEquals(antes,snapshot());
    }
    @Test void produccionFiltraPorIdElemento() {
        var r=consultar(new CriterioStock(null,f.base,false));assertEquals(1,r.insumos().size());assertEquals(1,r.reventa().size());
        assertEquals(f.base,r.insumos().getFirst().idInsumo());assertEquals(f.base,r.reventa().getFirst().idProducto());
        assertTrue(consultar(new CriterioStock("INSUMO",f.base,null)).reventa().isEmpty());
        assertTrue(consultar(new CriterioStock("REVENTA",f.base,null)).insumos().isEmpty());
    }
    @Test void produccionFiltraSoloBajoUmbral() {
        j.update("UPDATE \"INSUMO\" SET \"umbralMinimo\"=9 WHERE \"idInsumo\"=?",f.base);
        var r=consultar(new CriterioStock("INSUMO",null,true));
        assertEquals(List.of(f.base+1),r.insumos().stream().map(StockInsumo::idInsumo).toList()); // Físico 10 >= 9 aunque utilizable 8 < 9.
        assertTrue(r.reventa().isEmpty());var ambos=consultar(new CriterioStock(null,null,true));
        assertEquals(1,ambos.insumos().size());assertEquals(3,ambos.reventa().size());
        assertEquals(3,consultar(new CriterioStock("INSUMO",null,false)).insumos().size());
    }
    @Test void soloBajoUmbralConReventaRechaza() {
        var c=new CriterioStock("REVENTA",null,true);
        var ex=assertThrows(IllegalArgumentException.class,()->servicio.consultarStock(produccion,c));
        assertEquals("SOLO_BAJO_UMBRAL_NO_APLICA_REVENTA",ex.getMessage());
        assertEquals(3,consultar(new CriterioStock("REVENTA",null,false)).reventa().size());
    }
    @Test void stockUtilizableExcluyeVencidas() {
        var r=primero();assertEquals(new BigDecimal("10.000"),r.stockActual());assertEquals(0,new BigDecimal("8").compareTo(r.stockUtilizable()));
        assertEquals(3,r.partidas().size());assertEquals(1,r.partidas().stream().filter(PartidaResumen::vencida).count());
        assertTrue(r.partidas().stream().anyMatch(p->f.hoy.equals(p.fechaVencimiento()) && !p.vencida()));
        assertTrue(r.partidas().stream().anyMatch(p->p.fechaVencimiento()==null && !p.vencida()));
        assertTrue(r.partidas().stream().allMatch(p->p.cantidadDisponible().signum()>0));
        assertEquals(0,new BigDecimal("10").compareTo(r.partidas().stream().map(PartidaResumen::cantidadDisponible).reduce(BigDecimal.ZERO,BigDecimal::add)));
    }
    @Test void partidasOrdenadasPorD4() {
        j.update("DELETE FROM \"PARTIDA_INSUMO\" WHERE \"idInsumo\"=?",f.base);
        // Inserción desordenada: abiertos primero, después FEFO y por último registro/ID.
        StockFixture.partida(j,f.base+35,f.base,"1",null,f.ahora.minusDays(8),null);
        StockFixture.partida(j,f.base+33,f.base,"1",f.hoy.plusDays(1),f.ahora.minusDays(7),null);
        StockFixture.partida(j,f.base+32,f.base,"1",f.hoy.plusDays(20),f.ahora.minusDays(10),f.ahora.minusDays(3));
        StockFixture.partida(j,f.base+31,f.base,"1",null,f.ahora.minusDays(10),f.ahora.minusDays(5));
        StockFixture.partida(j,f.base+34,f.base,"1",f.hoy.plusDays(1),f.ahora.minusDays(8),null);
        StockFixture.partida(j,f.base+36,f.base,"1",f.hoy.plusDays(1),f.ahora.minusDays(8),null);
        StockFixture.partida(j,f.base+37,f.base,"1",f.hoy.plusDays(1),f.ahora.minusDays(10),f.ahora.minusDays(3));
        j.update("UPDATE \"INSUMO\" SET \"stockActual\"=7 WHERE \"idInsumo\"=?",f.base);
        assertEquals(List.of(f.base+31,f.base+37,f.base+32,f.base+34,f.base+36,f.base+33,f.base+35),
                primero().partidas().stream().map(PartidaResumen::idPartidaInsumo).toList());
    }
    @Test void reventaVisibleSinPartidas() {
        var r=consultar(new CriterioStock("REVENTA",null,null));assertTrue(r.insumos().isEmpty());assertEquals(3,r.reventa().size());
        assertEquals(new BigDecimal("10.000"),r.reventa().getFirst().stockActualReventa());
        assertEquals(0,r.reventa().get(1).stockActualReventa().compareTo(BigDecimal.ZERO));
        assertNull(r.reventa().getLast().stockActualReventa());assertEquals(4,StockReventa.class.getRecordComponents().length);
    }
    @Test void sinCoincidenciasRetornaListasVacias() {
        var r=consultar(new CriterioStock(null,Long.MAX_VALUE,null));assertTrue(r.insumos().isEmpty());assertTrue(r.reventa().isEmpty());
        tx.executeWithoutResult(s->StockFixture.limpiar(j));var vacio=consultar(null);assertTrue(vacio.insumos().isEmpty());assertTrue(vacio.reventa().isEmpty());
    }
    @Test void criterioInvalidoRechaza() {
        for(var c:List.of(new CriterioStock("OTRO",null,null),new CriterioStock("",null,null),new CriterioStock(null,0L,null),new CriterioStock(null,-1L,null)))
            assertThrows(IllegalArgumentException.class,()->servicio.consultarStock(produccion,c));
    }
    private Map<String,String> snapshot() {
        var datos=new HashMap<String,String>();
        for(String tabla:List.of("INSUMO","PRODUCTO","PARTIDA_INSUMO","EPISODIO_FALTANTE","MOVIMIENTO_INSUMO","DETALLE_MOVIMIENTO_PARTIDA","MOVIMIENTO_STOCK_REVENTA"))
            datos.put(tabla,j.queryForObject("SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]')) FROM \""+tabla+"\" t",String.class));
        return datos;
    }
}
