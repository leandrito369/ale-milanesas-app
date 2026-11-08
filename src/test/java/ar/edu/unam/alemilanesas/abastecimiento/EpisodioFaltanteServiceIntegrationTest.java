package ar.edu.unam.alemilanesas.abastecimiento;

import ar.edu.unam.alemilanesas.abastecimiento.RegistrarAjusteInventarioService.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static ar.edu.unam.alemilanesas.abastecimiento.AjusteInventarioFixture.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class EpisodioFaltanteServiceIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate j;
    @Autowired TransactionTemplate tx;
    @Autowired RegistrarAjusteInventarioService ajustes;
    @Autowired RegistrarIngresoStockInsumoService compras;
    AjusteInventarioFixture f;
    @BeforeEach void fixture() {f=new AjusteInventarioFixture(j,tx);}
    AjusteRegistrado registrar(DatosAjuste d) {return ajustes.registrarAjusteInventario(f.produccion(),d);}
    int abiertos() {return j.queryForObject("SELECT count(*) FROM \"EPISODIO_FALTANTE\" WHERE \"idInsumo\"=? AND \"fechaHoraCierre\" IS NULL",Integer.class,f.insumo);}
    DatosAjuste negativo(String cantidad) {
        long id=j.queryForObject("SELECT MIN(\"idPartidaInsumo\") FROM \"PARTIDA_INSUMO\" WHERE \"idInsumo\"=?",Long.class,f.insumo);
        return f.datos("AJUSTE_NEGATIVO",cantidad,null,List.of(new PartidaAjuste(id,n(cantidad),null,null,false)));
    }
    @Test void saldoInicialCierraEpisodioSiCubreUmbral() {
        j.update("INSERT INTO \"EPISODIO_FALTANTE\" (\"idEpisodio\",\"idInsumo\",\"fechaHoraInicio\") VALUES (?,?,clock_timestamp()-interval '1 hour')",f.insumo,f.insumo);
        registrar(f.inicial());assertEquals(0,abiertos());
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"EPISODIO_FALTANTE\" WHERE \"idInsumo\"=? AND \"fechaHoraCierre\">\"fechaHoraInicio\"",Integer.class,f.insumo));
    }
    @Test void ajusteNegativoAbreEpisodioSiBajaDeUmbral() {
        registrar(f.datos("SALDO_INICIAL","10",n("2"),List.of(nueva("10"))));
        var r=registrar(negativo("6"));assertEquals(1,abiertos());
        assertEquals(r.idMovimientoInsumo(),j.queryForObject("SELECT \"idMovimientoApertura\" FROM \"EPISODIO_FALTANTE\" WHERE \"idInsumo\"=?",Long.class,f.insumo));
        assertEquals(0,j.queryForObject("SELECT count(*) FROM \"SOLICITUD_COTIZACION\" s JOIN \"EPISODIO_FALTANTE\" e USING (\"idEpisodio\") WHERE e.\"idInsumo\"=?",Integer.class,f.insumo));
    }
    @Test void ajusteNegativoNoDuplicaEpisodioExistente() {
        registrar(f.datos("SALDO_INICIAL","10",n("2"),List.of(nueva("10"))));
        var primero=registrar(negativo("6"));registrar(negativo("1"));
        assertEquals(1,abiertos());
        assertEquals(primero.idMovimientoInsumo(),j.queryForObject("SELECT \"idMovimientoApertura\" FROM \"EPISODIO_FALTANTE\" WHERE \"idInsumo\"=?",Long.class,f.insumo));
    }
    @Test void regresionUC08SigueCerrandoEpisodio() {
        compras.registrarIngresoStockPorCompra(f.produccion(),f.compra.datos("5"));
        assertEquals(1,j.queryForObject("SELECT count(*) FROM \"EPISODIO_FALTANTE\" WHERE \"idInsumo\"=? AND \"fechaHoraCierre\" IS NULL",Integer.class,f.compra.base));
        compras.registrarIngresoStockPorCompra(f.produccion(),f.compra.datos("15"));
        assertEquals(0,j.queryForObject("SELECT count(*) FROM \"EPISODIO_FALTANTE\" WHERE \"idInsumo\"=? AND \"fechaHoraCierre\" IS NULL",Integer.class,f.compra.base));
    }
}
