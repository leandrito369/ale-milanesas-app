package ar.edu.unam.alemilanesas.produccioninventario;

import ar.edu.unam.alemilanesas.abastecimiento.IngresoReventaFixture;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Solo datos sintéticos en PostgreSQL aislado de UC09. No altera fixtures ni servicios existentes. */
public final class StockFixture {
    public final IngresoReventaFixture actores;
    public final long base;
    public final LocalDate hoy;
    public final OffsetDateTime ahora;
    public StockFixture(JdbcTemplate j,TransactionTemplate tx) {
        actores=new IngresoReventaFixture(j,tx);base=actores.base;
        hoy=j.queryForObject("SELECT CURRENT_DATE",LocalDate.class);
        ahora=j.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);
        j.update("INSERT INTO \"UNIDAD_MEDIDA\" VALUES ('KG','Kilogramo') ON CONFLICT DO NOTHING");
        for(int n=0;n<3;n++) {
            j.update("""
                    INSERT INTO "INSUMO" ("idInsumo","nombre","codigoUnidadMedida","stockActual","umbralMinimo",
                        "nivelObjetivoReposicion","automatizacionCompraActiva","activo")
                    VALUES (?,?,'KG',?,5,20,false,?)
                    """,base+n,"Insumo UC09 "+(base+n),new BigDecimal(n==0?"10":n==1?"0":"5"),n!=2);
        }
        partida(j,base+20,base,"5",null,ahora.minusDays(4),ahora.minusDays(2));
        partida(j,base+21,base,"3",hoy,ahora.minusDays(3),null);
        partida(j,base+22,base,"2",hoy.minusDays(1),ahora.minusDays(5),null);
        partida(j,base+23,base,"0",hoy.minusDays(1),ahora.minusDays(5),null);
        partida(j,base+24,base+2,"5",hoy.plusDays(10),ahora.minusDays(5),null);
        j.update("""
                INSERT INTO "EPISODIO_FALTANTE" ("idEpisodio","idInsumo","fechaHoraInicio","fechaHoraCierre")
                VALUES (?,?,now()-interval '2 days',NULL),(?,?,now()-interval '2 days',now()-interval '1 day')
                """,base,base+1,base+1,base);
        j.update("""
                INSERT INTO "PRODUCTO" ("idProducto","nombre","nombreNormalizado","tipoProducto","codigoUnidadVenta",
                    "activo","stockActualReventa","costoPromedioActualReventa")
                VALUES (?,?,?,'REVENTA','UN',false,0,NULL),(?,?,?,'REVENTA','UN',true,NULL,NULL)
                """,base+2,"Reventa cero "+base,"reventa cero "+base,base+3,"Reventa no inicializada "+base,"reventa no inicializada "+base);
    }
    public UUID produccion() {return actores.produccion();}
    public static void partida(JdbcTemplate j,long id,long insumo,String cantidad,LocalDate vencimiento,
            OffsetDateTime registro,OffsetDateTime apertura) {
        var disponible=new BigDecimal(cantidad);
        j.update("""
                INSERT INTO "PARTIDA_INSUMO" ("idPartidaInsumo","idInsumo","codigoLote","fechaVencimiento",
                    "fechaHoraRegistro","fechaHoraApertura","cantidadInicial","cantidadDisponible") VALUES (?,?,?,?,?,?,?,?)
                """,id,insumo,"LOTE-"+id,vencimiento,registro,apertura,disponible.max(BigDecimal.ONE),disponible);
    }
    public static void limpiar(JdbcTemplate j) {
        // Exclusivamente los datos de stock del contenedor dedicado de esta suite.
        j.update("DELETE FROM \"PARTIDA_INSUMO\"");j.update("DELETE FROM \"EPISODIO_FALTANTE\"");
        j.update("DELETE FROM \"INSUMO\"");j.update("DELETE FROM \"PRODUCTO\"");
    }
}
