package ar.edu.unam.alemilanesas.finanzas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC16: resultado interno para congelar en UC03/UC21 en la misma transaccion.
 * No crea Pedido ni expone endpoint; no decide promociones ni precio unitario.
 */
@Service
public class AplicarAjusteComercialService {
    private final NamedParameterJdbcTemplate sql;
    private final EvaluadorCategoriaComercialInterno categoria;

    public AplicarAjusteComercialService(NamedParameterJdbcTemplate sql,
                                        EvaluadorCategoriaComercialInterno categoria) {
        this.sql=sql;
        this.categoria=categoria;
    }
    public record AjusteAplicado(long idPoliticaComercial,String categoriaComercialAplicada,
                                 String tipoAjuste,BigDecimal porcentajeAjuste,
                                 BigDecimal importeAjuste,BigDecimal totalOriginal) {}

    @Transactional
    public AjusteAplicado aplicarAjusteComercialSegunCategoria(
            long idCliente,BigDecimal subtotalBaseLuegoPromocion) {
        if(idCliente<=0 || subtotalBaseLuegoPromocion==null
                || subtotalBaseLuegoPromocion.signum()<0
                || subtotalBaseLuegoPromocion.scale()>4 || subtotalBaseLuegoPromocion.precision()>18)
            throw new IllegalArgumentException("Cliente o subtotal comercial invalido");
        var clientes=sql.queryForList("""
                SELECT "estado" FROM "CLIENTE" WHERE "idCliente"=:id FOR UPDATE
                """,Map.of("id",idCliente));
        if(clientes.size()!=1||!"ACTIVO".equals(clientes.getFirst().get("estado")))
            throw new IllegalArgumentException("Cliente no activo o inexistente");

        OffsetDateTime ahora=sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()",
                (rs,i)->rs.getObject(1,OffsetDateTime.class));
        if(ahora==null)throw new IllegalStateException("Sin reloj PostgreSQL");
        String categoriaActual=categoria.reevaluar(idCliente,ahora);
        var politicas=sql.queryForList("""
                SELECT "idPoliticaComercial" FROM "POLITICA_CATEGORIA_COMERCIAL"
                 WHERE "vigenteDesde"<=:ahora
                   AND ("vigenteHasta" IS NULL OR "vigenteHasta">:ahora)
                 FOR SHARE
                """,Map.of("ahora",ahora));
        if(politicas.size()!=1)
            throw new IllegalStateException("Politica vigente ausente o ambigua");
        long politica=((Number)politicas.getFirst().get("idPoliticaComercial")).longValue();
        var reglas=sql.queryForList("""
                SELECT "tipoAjuste","porcentajeAjuste" FROM "AJUSTE_POLITICA_CATEGORIA"
                 WHERE "idPoliticaComercial"=:id AND "categoriaComercial"=:categoria
                 FOR SHARE
                """,Map.of("id",politica,"categoria",categoriaActual));
        if(reglas.size()!=1)throw new IllegalStateException("Ajuste de categoria inexistente");
        var regla=reglas.getFirst();
        String tipo=(String)regla.get("tipoAjuste");
        BigDecimal porcentaje=(BigDecimal)regla.get("porcentajeAjuste");
        boolean sin="SIN_AJUSTE".equals(tipo);
        if(porcentaje==null||porcentaje.signum()<0||porcentaje.compareTo(
                new BigDecimal("100"))>0 || (sin&&porcentaje.signum()!=0)
                || (!sin&&porcentaje.signum()==0)
                || (!sin&&!"DESCUENTO".equals(tipo)&&!"RECARGO".equals(tipo)))
            throw new IllegalStateException("Regla de politica inconsistente");

        BigDecimal base=subtotalBaseLuegoPromocion.setScale(2,RoundingMode.HALF_UP);
        BigDecimal ajuste=sin?BigDecimal.ZERO.setScale(2):
                base.multiply(porcentaje).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP);
        BigDecimal total=switch(tipo){
            case "SIN_AJUSTE" -> base;
            case "DESCUENTO" -> base.subtract(ajuste);
            case "RECARGO" -> base.add(ajuste);
            default -> throw new IllegalStateException("Tipo de ajuste desconocido");
        };
        if(total.signum()<0||total.precision()>18)
            throw new IllegalArgumentException("Total original fuera de rango");
        return new AjusteAplicado(politica,categoriaActual,tipo,porcentaje,
                ajuste,total);
    }
}
