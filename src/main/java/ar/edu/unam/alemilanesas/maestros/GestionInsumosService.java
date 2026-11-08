package ar.edu.unam.alemilanesas.maestros;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I1-06 B3: UC50 alta, UC51 modificación de identidad/unidad y UC52 estado.
 * UC31 administra los parametros de reposicion; UC42 los movimientos fisicos.
 * No inserta SALDO_INICIAL, MOVIMIENTO_INSUMO ni COSTO_INSUMO_HISTORICO.
 */
@Service
public class GestionInsumosService {
    private static final Set<String> ADMIN = Set.of("ADMINISTRADOR");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna auth;

    public GestionInsumosService(NamedParameterJdbcTemplate sql, AutorizacionSesionInterna auth) {
        this.sql=sql;
        this.auth=auth;
    }

    public record InsumoResultado(long idInsumo, String nombre, String codigoUnidadMedida,
                                 BigDecimal stockActual, boolean activo, boolean huboCambio) {}
    public record Dependencias(int recetas, int movimientos, int recepciones, int costos,
                               int proveedores, int episodios, int partidas) {
        public boolean tieneHistoria() {
            return recetas>0 || movimientos>0 || recepciones>0 || costos>0
                    || proveedores>0 || episodios>0 || partidas>0;
        }
    }
    public record EstadoInsumo(long idInsumo, boolean activo, boolean huboCambio,
                               Dependencias dependencias) {}

    /**
     * UC50: umbral y nivel son datos explícitos de alta, nunca stocks reales
     * ni saldos inferidos. Automatización arranca inactiva hasta UC31.
     */
    @Transactional
    public InsumoResultado registrarInsumo(UUID sesion,String nombre,String codigoUnidadMedida,
                                           BigDecimal umbralMinimo,
                                           BigDecimal nivelObjetivoReposicion) {
        auth.exigirRoles(sesion,ADMIN);
        String n=validarNombre(nombre);
        String unidad=validarCodigoUnidad(codigoUnidadMedida);
        validarReposicion(umbralMinimo,nivelObjetivoReposicion);
        bloquearMaestro();
        auth.exigirRoles(sesion,ADMIN);
        exigirUnidad(unidad);
        asegurarNombreDisponible(n,null);
        Long id=sql.getJdbcTemplate().queryForObject("""
                SELECT COALESCE(MAX("idInsumo"),0)+1 FROM "INSUMO"
                """,Long.class);
        if(id==null || id<=0)throw new IllegalStateException("No se pudo generar idInsumo");
        sql.update("""
                INSERT INTO "INSUMO" ("idInsumo","nombre","codigoUnidadMedida",
                    "stockActual","umbralMinimo","nivelObjetivoReposicion",
                    "automatizacionCompraActiva","activo")
                VALUES (:id,:nombre,:unidad,0,:umbral,:objetivo,false,true)
                """,new MapSqlParameterSource().addValue("id",id)
                .addValue("nombre",n).addValue("unidad",unidad)
                .addValue("umbral",umbralMinimo).addValue("objetivo",nivelObjetivoReposicion));
        return new InsumoResultado(id,n,unidad,new BigDecimal("0.000"),true,true);
    }

    /**
     * UC51: no editar stock, reposicion (UC31), costos ni estado (UC52).
     * El cambio de unidad se bloquea con cualquier dependencia histórica.
     */
    @Transactional
    public InsumoResultado modificarInsumo(UUID sesion,long idInsumo,String nombre,
                                           String codigoUnidadMedida) {
        auth.exigirRoles(sesion,ADMIN);
        if(idInsumo<=0)throw new IllegalArgumentException("idInsumo inválido");
        String n=validarNombre(nombre);
        String unidad=validarCodigoUnidad(codigoUnidadMedida);
        bloquearMaestro();
        auth.exigirRoles(sesion,ADMIN);
        var previo=obtenerBloqueado(idInsumo);
        exigirUnidad(unidad);
        asegurarNombreDisponible(n,idInsumo);
        boolean cambiaUnidad=!unidad.equals(previo.get("codigoUnidadMedida"));
        if(cambiaUnidad && dependencias(idInsumo).tieneHistoria()) {
            throw new IllegalArgumentException(
                    "Insumo con dependencias históricas: no cambiar unidad de medida");
        }
        boolean hayCambios=cambiaUnidad||!n.equals(previo.get("nombre"));
        if(!hayCambios)return resultado(idInsumo,previo,false);
        sql.update("""
                UPDATE "INSUMO" SET "nombre"=:nombre,"codigoUnidadMedida"=:unidad
                 WHERE "idInsumo"=:id
                """,Map.of("nombre",n,"unidad",unidad,"id",idInsumo));
        return new InsumoResultado(idInsumo,n,unidad,(BigDecimal)previo.get("stockActual"),
                (Boolean)previo.get("activo"),true);
    }

    /** UC52: no altera historicos, stock ni los parametros de compra automática. */
    @Transactional
    public EstadoInsumo cambiarEstadoInsumo(UUID sesion,long idInsumo,Boolean nuevoActivo) {
        auth.exigirRoles(sesion,ADMIN);
        if(idInsumo<=0 || nuevoActivo==null)
            throw new IllegalArgumentException("Insumo y estado requeridos");
        bloquearMaestro();
        auth.exigirRoles(sesion,ADMIN);
        var previo=obtenerBloqueado(idInsumo);
        Dependencias deps=dependencias(idInsumo);
        boolean anterior=(Boolean)previo.get("activo");
        if(anterior==nuevoActivo)return new EstadoInsumo(idInsumo,anterior,false,deps);
        if(nuevoActivo) {
            validarNombre((String)previo.get("nombre"));
            exigirUnidad((String)previo.get("codigoUnidadMedida"));
        }
        sql.update("""
                UPDATE "INSUMO" SET "activo"=:activo WHERE "idInsumo"=:id
                """,Map.of("activo",nuevoActivo,"id",idInsumo));
        return new EstadoInsumo(idInsumo,nuevoActivo,true,deps);
    }

    private Map<String,Object> obtenerBloqueado(long id){
        List<Map<String,Object>> filas=sql.queryForList("""
                SELECT "idInsumo","nombre","codigoUnidadMedida","stockActual",
                       "umbralMinimo","nivelObjetivoReposicion",
                       "automatizacionCompraActiva","activo"
                  FROM "INSUMO" WHERE "idInsumo"=:id FOR UPDATE
                """,Map.of("id",id));
        if(filas.size()!=1)throw new IllegalArgumentException("Insumo inexistente");
        return filas.getFirst();
    }
    private InsumoResultado resultado(long id,Map<String,Object> previo,boolean cambio) {
        return new InsumoResultado(id,(String)previo.get("nombre"),
                (String)previo.get("codigoUnidadMedida"),
                (BigDecimal)previo.get("stockActual"),(Boolean)previo.get("activo"),cambio);
    }

    private Dependencias dependencias(long id) {
        return sql.queryForObject("""
                SELECT
                 (SELECT count(*) FROM "DETALLE_VERSION_RECETA" WHERE "idInsumo"=:id) AS recetas,
                 (SELECT count(*) FROM "MOVIMIENTO_INSUMO" WHERE "idInsumo"=:id) AS movimientos,
                 (SELECT count(*) FROM "RECEPCION_COMPRA" WHERE "idInsumoRecibido"=:id) AS recepciones,
                 (SELECT count(*) FROM "COSTO_INSUMO_HISTORICO" WHERE "idInsumo"=:id) AS costos,
                 (SELECT count(*) FROM "PROVEEDOR_INSUMO" WHERE "idInsumo"=:id) AS proveedores,
                 (SELECT count(*) FROM "EPISODIO_FALTANTE" WHERE "idInsumo"=:id) AS episodios,
                 (SELECT count(*) FROM "PARTIDA_INSUMO" WHERE "idInsumo"=:id) AS partidas
                """,Map.of("id",id),(rs,i)->new Dependencias(rs.getInt("recetas"),
                rs.getInt("movimientos"),rs.getInt("recepciones"),
                rs.getInt("costos"),rs.getInt("proveedores"),rs.getInt("episodios"),rs.getInt("partidas")));
    }
    private void asegurarNombreDisponible(String nombre,Long excluir) {
        String norm=normalizar(nombre);
        List<Long> ids=sql.queryForList("""
                SELECT "idInsumo" FROM "INSUMO"
                 WHERE "idInsumo"<>:excluir
                   AND lower(regexp_replace(btrim("nombre"),'[[:space:]]+',' ','g'))=:norm
                """,new MapSqlParameterSource().addValue("excluir",excluir==null?-1L:excluir)
                .addValue("norm",norm),Long.class);
        if(!ids.isEmpty())throw new IllegalArgumentException(
                "Nombre de Insumo ya registrado, incluso inactivo");
    }

    private void exigirUnidad(String codigo) {
        var unidades=sql.queryForList("""
                SELECT "codigoUnidad" FROM "UNIDAD_MEDIDA"
                 WHERE "codigoUnidad"=:codigo FOR SHARE
                """,Map.of("codigo",codigo));
        if(unidades.size()!=1)throw new IllegalArgumentException("Unidad inexistente");
    }
    private void bloquearMaestro() {
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(5051052)");
    }
    private static String normalizar(String nombre) {
        return nombre.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }
    private static String validarNombre(String nombre) {
        if(nombre==null || nombre.isBlank() || nombre.strip().length()>150
                || nombre.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Nombre de Insumo inválido");
        return nombre.strip().replaceAll("\\s+"," ");
    }
    private static String validarCodigoUnidad(String codigo) {
        if(codigo==null || codigo.isBlank() || codigo.strip().length()>20)
            throw new IllegalArgumentException("Código de unidad inválido");
        return codigo.strip();
    }
    private static void validarReposicion(BigDecimal umbral,BigDecimal objetivo) {
        if(umbral==null || objetivo==null || umbral.signum()<0
                || objetivo.compareTo(umbral)<=0
                || umbral.scale()>3 || objetivo.scale()>3
                || umbral.precision()>18 || objetivo.precision()>18)
            throw new IllegalArgumentException("Parámetros iniciales de reposición inválidos");
    }
}
