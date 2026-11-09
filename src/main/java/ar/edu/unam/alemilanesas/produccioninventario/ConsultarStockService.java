package ar.edu.unam.alemilanesas.produccioninventario;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** UC09 / MR6: fotografía de existencias y partidas, sin movimientos ni reevaluar episodios. */
@Service
public class ConsultarStockService {
    private static final String INSUMO="INSUMO";
    private static final String REVENTA="REVENTA";
    private static final String ID_INSUMO="idInsumo";
    private static final Set<String> TIPOS=Set.of(INSUMO,REVENTA);
    private static final Set<String> PRODUCCION=Set.of("RESPONSABLE_PRODUCCION");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    public ConsultarStockService(NamedParameterJdbcTemplate sql,AutorizacionSesionInterna autorizacion) {
        this.sql=sql;this.autorizacion=autorizacion;
    }
    public record CriterioStock(String tipoInventario,Long idElemento,Boolean soloBajoUmbral) {}
    public record PartidaResumen(long idPartidaInsumo,String codigoLote,LocalDate fechaVencimiento,
            BigDecimal cantidadDisponible,OffsetDateTime fechaHoraRegistro,OffsetDateTime fechaHoraApertura,boolean vencida) {}
    public record StockInsumo(long idInsumo,String nombre,String codigoUnidadMedida,BigDecimal stockActual,
            BigDecimal stockUtilizable,BigDecimal umbralMinimo,BigDecimal nivelObjetivoReposicion,
            boolean tieneEpisodioAbierto,List<PartidaResumen> partidas) {}
    public record StockReventa(long idProducto,String nombre,String codigoUnidadVenta,BigDecimal stockActualReventa) {}
    public record ResultadoStock(List<StockInsumo> insumos,List<StockReventa> reventa) {}
    private record PartidaDeInsumo(long insumo,PartidaResumen partida) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ResultadoStock consultarStock(UUID sesion,CriterioStock criterio) {
        autorizacion.exigirRoles(sesion,PRODUCCION);
        var c=criterio==null?new CriterioStock(null,null,null):criterio;
        validar(c);
        var p=new MapSqlParameterSource().addValue("id",c.idElemento(),java.sql.Types.BIGINT)
                .addValue("bajo",Boolean.TRUE.equals(c.soloBajoUmbral()),java.sql.Types.BOOLEAN);
        List<StockInsumo> insumos=REVENTA.equals(c.tipoInventario())?List.of():insumos(p);
        List<StockReventa> reventa=INSUMO.equals(c.tipoInventario())?List.of():reventa(p);
        return new ResultadoStock(List.copyOf(insumos),List.copyOf(reventa));
    }
    private static void validar(CriterioStock c) {
        if (c.tipoInventario()!=null && !TIPOS.contains(c.tipoInventario()))
            throw new IllegalArgumentException("Criterio invalido");
        if (c.idElemento()!=null && c.idElemento()<=0) throw new IllegalArgumentException("Criterio invalido");
        if (REVENTA.equals(c.tipoInventario()) && Boolean.TRUE.equals(c.soloBajoUmbral()))
            throw new IllegalArgumentException("SOLO_BAJO_UMBRAL_NO_APLICA_REVENTA");
    }
    private List<StockInsumo> insumos(MapSqlParameterSource p) {
        var partidas=partidas(p);
        return sql.query("""
                SELECT i."idInsumo",i."nombre",i."codigoUnidadMedida",i."stockActual",i."umbralMinimo",i."nivelObjetivoReposicion",
                    COALESCE((SELECT SUM(l."cantidadDisponible") FROM "PARTIDA_INSUMO" l
                        WHERE l."idInsumo"=i."idInsumo" AND l."cantidadDisponible">0
                            AND (l."fechaVencimiento" IS NULL OR l."fechaVencimiento">=CURRENT_DATE)),0) AS utilizable,
                    EXISTS(SELECT 1 FROM "EPISODIO_FALTANTE" e WHERE e."idInsumo"=i."idInsumo" AND e."fechaHoraCierre" IS NULL) AS episodio
                FROM "INSUMO" i WHERE (CAST(:id AS BIGINT) IS NULL OR i."idInsumo"=:id)
                    AND (NOT :bajo OR i."stockActual"<i."umbralMinimo") ORDER BY i."idInsumo"
                """,p,(rs,n)->new StockInsumo(rs.getLong(ID_INSUMO),rs.getString("nombre"),rs.getString("codigoUnidadMedida"),
                        rs.getBigDecimal("stockActual"),rs.getBigDecimal("utilizable"),rs.getBigDecimal("umbralMinimo"),
                        rs.getBigDecimal("nivelObjetivoReposicion"),rs.getBoolean("episodio"),
                        List.copyOf(partidas.getOrDefault(rs.getLong(ID_INSUMO),List.of()))));
    }
    private Map<Long,List<PartidaResumen>> partidas(MapSqlParameterSource p) {
        var filas=sql.query("""
                SELECT l."idInsumo",l."idPartidaInsumo",l."codigoLote",l."fechaVencimiento",l."cantidadDisponible",
                    l."fechaHoraRegistro",l."fechaHoraApertura",COALESCE(l."fechaVencimiento"<CURRENT_DATE,false) AS vencida
                FROM "PARTIDA_INSUMO" l JOIN "INSUMO" i ON i."idInsumo"=l."idInsumo"
                WHERE l."cantidadDisponible">0 AND (CAST(:id AS BIGINT) IS NULL OR i."idInsumo"=:id)
                    AND (NOT :bajo OR i."stockActual"<i."umbralMinimo")
                ORDER BY l."idInsumo",l."fechaHoraApertura" ASC NULLS LAST,l."fechaVencimiento" ASC NULLS LAST,
                    l."fechaHoraRegistro" ASC,l."idPartidaInsumo" ASC
                """,p,(rs,n)->new PartidaDeInsumo(rs.getLong(ID_INSUMO),new PartidaResumen(rs.getLong("idPartidaInsumo"),
                        rs.getString("codigoLote"),rs.getObject("fechaVencimiento",LocalDate.class),rs.getBigDecimal("cantidadDisponible"),
                        rs.getObject("fechaHoraRegistro",OffsetDateTime.class),rs.getObject("fechaHoraApertura",OffsetDateTime.class),rs.getBoolean("vencida"))));
        var agrupadas=new HashMap<Long,List<PartidaResumen>>();
        for (var fila:filas) agrupadas.computeIfAbsent(fila.insumo(),id->new ArrayList<>()).add(fila.partida());
        return agrupadas;
    }
    private List<StockReventa> reventa(MapSqlParameterSource p) {
        return sql.query("""
                SELECT "idProducto","nombre","codigoUnidadVenta","stockActualReventa" FROM "PRODUCTO"
                WHERE "tipoProducto"='REVENTA' AND (CAST(:id AS BIGINT) IS NULL OR "idProducto"=:id)
                ORDER BY "idProducto"
                """,p,(rs,n)->new StockReventa(rs.getLong("idProducto"),rs.getString("nombre"),
                        rs.getString("codigoUnidadVenta"),rs.getBigDecimal("stockActualReventa")));
    }
}
