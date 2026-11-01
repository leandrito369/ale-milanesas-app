package ar.edu.unam.alemilanesas.finanzas;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC57 — una política temporal y dos reglas por categoría, sin columnas ficticias. */
@Service
public class ConfigurarPoliticaCategoriaComercialService {
    private static final Set<String> ADMIN=Set.of("ADMINISTRADOR");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public ConfigurarPoliticaCategoriaComercialService(NamedParameterJdbcTemplate sql,
                                                       AutorizacionSesionInterna autorizacion) {
        this.sql=sql;
        this.autorizacion=autorizacion;
    }

    public record AjusteCategoria(String tipoAjuste, BigDecimal porcentajeAjuste) {}
    public record PoliticaCreada(long idPoliticaComercial, int diasToleranciaMora,
                                OffsetDateTime vigenteDesde,OffsetDateTime vigenteHasta,
                                Long politicaCerrada) {}
    private record Periodo(long id, OffsetDateTime inicio, OffsetDateTime fin) {}

    @Transactional
    public PoliticaCreada configurarPoliticaCategoriaComercial(
            UUID sesion,int diasToleranciaMora,OffsetDateTime vigenteDesde,
            OffsetDateTime vigenteHasta,AjusteCategoria cumplidor,AjusteCategoria enMora) {
        autorizacion.exigirRoles(sesion,ADMIN);
        validar(diasToleranciaMora,vigenteDesde,vigenteHasta,cumplidor,enMora);

        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(5715001)");
        autorizacion.exigirRoles(sesion,ADMIN);
        OffsetDateTime ahora=sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()",
                (rs,i)->rs.getObject(1,OffsetDateTime.class));
        if(ahora==null || vigenteDesde.isBefore(ahora))
            throw new IllegalArgumentException("No se permite politica retroactiva");

        List<Periodo> existentes=sql.query("""
                SELECT "idPoliticaComercial","vigenteDesde","vigenteHasta"
                  FROM "POLITICA_CATEGORIA_COMERCIAL"
                 ORDER BY "vigenteDesde","idPoliticaComercial" FOR UPDATE
                """,Map.of(),(rs,i)->new Periodo(rs.getLong(1),
                rs.getObject(2,OffsetDateTime.class),rs.getObject(3,OffsetDateTime.class)));
        Periodo cerrar=null;
        for(Periodo p:existentes){
            if(!p.inicio().isBefore(vigenteDesde)){
                if(vigenteHasta==null || p.inicio().isBefore(vigenteHasta))
                    throw new IllegalArgumentException("Politica futura solapada");
            } else if(p.fin()==null || p.fin().isAfter(vigenteDesde)){
                if(cerrar!=null)throw new IllegalStateException("Vigencias anteriores ambiguas");
                cerrar=p;
            }
        }
        Long id=sql.getJdbcTemplate().queryForObject("""
                SELECT COALESCE(MAX("idPoliticaComercial"),0)+1
                  FROM "POLITICA_CATEGORIA_COMERCIAL"
                """,Long.class);
        if(id==null||id<=0)throw new IllegalStateException("Sin ID de politica");
        Long actor=sql.queryForObject("""
                SELECT "idUsuario" FROM "SESION_USUARIO" WHERE "idSesionUsuario"=:sesion
                """,Map.of("sesion",sesion),Long.class);
        if(actor==null)throw new SecurityException("Sesion inexistente");
        if(cerrar!=null) {
            sql.update("""
                    UPDATE "POLITICA_CATEGORIA_COMERCIAL" SET "vigenteHasta"=:hasta
                     WHERE "idPoliticaComercial"=:id
                    """,Map.of("id",cerrar.id(),"hasta",vigenteDesde));
        }
        sql.update("""
                INSERT INTO "POLITICA_CATEGORIA_COMERCIAL"
                    ("idPoliticaComercial","diasToleranciaMora","vigenteDesde",
                     "vigenteHasta","fechaHoraRegistro","idUsuarioRegistro")
                VALUES (:id,:dias,:inicio,:fin,:ahora,:actor)
                """,new MapSqlParameterSource().addValue("id",id)
                .addValue("dias",diasToleranciaMora).addValue("inicio",vigenteDesde)
                .addValue("fin",vigenteHasta).addValue("ahora",ahora).addValue("actor",actor));
        grabarAjuste(id,"CUMPLIDOR",cumplidor);
        grabarAjuste(id,"EN_MORA",enMora);
        return new PoliticaCreada(id,diasToleranciaMora,vigenteDesde,vigenteHasta,
                cerrar==null?null:cerrar.id());
    }

    private void grabarAjuste(long id,String categoria,AjusteCategoria ajuste){
        sql.update("""
                INSERT INTO "AJUSTE_POLITICA_CATEGORIA"
                    ("idPoliticaComercial","categoriaComercial","tipoAjuste","porcentajeAjuste")
                VALUES (:id,:categoria,:tipo,:porcentaje)
                """,Map.of("id",id,"categoria",categoria,
                        "tipo",ajuste.tipoAjuste(),"porcentaje",ajuste.porcentajeAjuste()));
    }

    private static void validar(int dias,OffsetDateTime inicio,OffsetDateTime fin,
                                AjusteCategoria cumplidor,AjusteCategoria enMora){
        if(dias<0||inicio==null||(fin!=null&&!fin.isAfter(inicio)))
            throw new IllegalArgumentException("Tolerancia o periodo invalidos");
        validarRegla(cumplidor,"CUMPLIDOR");
        validarRegla(enMora,"EN_MORA");
    }
    private static void validarRegla(AjusteCategoria a,String categoria){
        if(a==null||a.tipoAjuste()==null||a.porcentajeAjuste()==null
                || a.porcentajeAjuste().scale()>2
                || a.porcentajeAjuste().compareTo(BigDecimal.ZERO)<0
                || a.porcentajeAjuste().compareTo(new BigDecimal("100"))>0)
            throw new IllegalArgumentException("Ajuste faltante o porcentaje invalido: "+categoria);
        boolean sin="SIN_AJUSTE".equals(a.tipoAjuste());
        if(!(sin || "DESCUENTO".equals(a.tipoAjuste()) || "RECARGO".equals(a.tipoAjuste()))
           || (sin && a.porcentajeAjuste().signum()!=0)
           || (!sin && a.porcentajeAjuste().signum()==0)
           || ("CUMPLIDOR".equals(categoria) && "RECARGO".equals(a.tipoAjuste()))
           || ("EN_MORA".equals(categoria) && "DESCUENTO".equals(a.tipoAjuste())))
            throw new IllegalArgumentException("Tipo y porcentaje incompatibles: "+categoria);
    }
}
