package ar.edu.unam.alemilanesas.maestros;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC36 y catálogo de UNIDAD_MEDIDA: sólo consultas autorizadas, sin DDL. */
@Service
public class ConsultarInsumosUnidadesService {
    private static final Set<String> CONSULTA=Set.of("ADMINISTRADOR","RESPONSABLE_PRODUCCION");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna auth;

    public ConsultarInsumosUnidadesService(NamedParameterJdbcTemplate sql,
                                           AutorizacionSesionInterna auth) {
        this.sql=sql;
        this.auth=auth;
    }

    public record FiltroInsumo(Long idInsumo,String nombre,Boolean activo) {}
    public record InsumoConsultado(long idInsumo,String nombre,String codigoUnidadMedida,
                                   BigDecimal stockActual,BigDecimal umbralMinimo,
                                   BigDecimal nivelObjetivoReposicion,boolean activo) {}
    public record UnidadConsultada(String codigoUnidad,String nombre) {}

    @Transactional(readOnly=true)
    public List<InsumoConsultado> buscarInsumo(UUID sesion,FiltroInsumo filtro) {
        auth.exigirRoles(sesion,CONSULTA);
        if(filtro==null)throw new IllegalArgumentException("Criterios requeridos");
        var args=new MapSqlParameterSource();
        var consulta=new StringBuilder("""
                SELECT "idInsumo","nombre","codigoUnidadMedida","stockActual",
                       "umbralMinimo","nivelObjetivoReposicion","activo"
                  FROM "INSUMO" WHERE 1=1
                """);
        if(filtro.idInsumo()!=null) {
            if(filtro.idInsumo()<=0)throw new IllegalArgumentException("ID inválido");
            consulta.append(" AND \"idInsumo\"=:id ");
            args.addValue("id",filtro.idInsumo());
        }
        if(filtro.nombre()!=null) {
            String nombre=filtro.nombre().strip();
            if(nombre.isEmpty() || nombre.length()>150)
                throw new IllegalArgumentException("Nombre inválido");
            consulta.append(" AND position(:nombre IN lower(\"nombre\"))>0 ");
            args.addValue("nombre",nombre.toLowerCase(Locale.ROOT));
        }
        if(filtro.activo()!=null) {
            consulta.append(" AND \"activo\"=:activo ");
            args.addValue("activo",filtro.activo());
        }
        consulta.append(" ORDER BY lower(\"nombre\"),\"idInsumo\" ");
        return List.copyOf(sql.query(consulta.toString(),args,(rs,i)->new InsumoConsultado(
            rs.getLong("idInsumo"),rs.getString("nombre"),rs.getString("codigoUnidadMedida"),
            rs.getBigDecimal("stockActual"),rs.getBigDecimal("umbralMinimo"),
            rs.getBigDecimal("nivelObjetivoReposicion"),rs.getBoolean("activo"))));
    }

    @Transactional(readOnly=true)
    public List<UnidadConsultada> listarUnidades(UUID sesion) {
        auth.exigirRoles(sesion,CONSULTA);
        return List.copyOf(sql.query("""
                SELECT "codigoUnidad","nombre" FROM "UNIDAD_MEDIDA"
                 ORDER BY "codigoUnidad"
                """,new MapSqlParameterSource(),(rs,i)->new UnidadConsultada(
                rs.getString("codigoUnidad"),rs.getString("nombre"))));
    }
}
