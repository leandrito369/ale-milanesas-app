package ar.edu.unam.alemilanesas.maestros;

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

/**
 * I1-04 / UC19: intervalos [vigenteDesde, vigenteHasta) de PRECIO_PRODUCTO,
 * cierre y registro AUDITORIA_PRECIO atomicos. B3 sin HTTP.
 *
 * El primer alta utiliza MODIFICACION_PROGRAMADA con precioAnterior NULL:
 * MR5 no admite un tipoEvento ALTA_PRECIO. Es un mapeo técnico explícito,
 * sujeto a la revisión del contrato, no un nuevo valor del dominio.
 */
@Service
public class ActualizarPrecioVigenteService {
    private static final Set<String> ADMIN=Set.of("ADMINISTRADOR");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public ActualizarPrecioVigenteService(NamedParameterJdbcTemplate sql,
                                          AutorizacionSesionInterna autorizacion) {
        this.sql=sql;
        this.autorizacion=autorizacion;
    }

    public record PrecioActualizado(long idPrecio, long idAuditoriaPrecio,
                                   Long idPrecioCerrado, OffsetDateTime vigenteDesde,
                                   OffsetDateTime vigenteHasta) {}
    private record Vigencia(long idPrecio, BigDecimal precio, OffsetDateTime desde,
                            OffsetDateTime hasta) {}

    @Transactional
    public PrecioActualizado actualizarPrecioVigente(UUID sesion, long idProducto,
            BigDecimal nuevoPrecioBase, OffsetDateTime vigenteDesde, OffsetDateTime vigenteHasta) {
        autorizacion.exigirRoles(sesion,ADMIN);
        if(idProducto<=0 || nuevoPrecioBase==null || nuevoPrecioBase.signum()<=0
                || nuevoPrecioBase.scale()>4 || nuevoPrecioBase.precision()>18
                || vigenteDesde==null
                || (vigenteHasta!=null && !vigenteHasta.isAfter(vigenteDesde))) {
            throw new IllegalArgumentException("Precio o vigencia invalida");
        }

        // Lock cooperativo de Producto ANTES de crear dependencias, orden
        // Producto -> Precio (lock asesor de ids); protege contra UC47/UC48.
        var producto=sql.queryForList("""
                SELECT "idProducto","activo" FROM "PRODUCTO"
                 WHERE "idProducto"=:id FOR UPDATE
                """,Map.of("id",idProducto));
        if(producto.size()!=1 || !Boolean.TRUE.equals(producto.getFirst().get("activo"))) {
            throw new IllegalArgumentException("Producto inexistente o inactivo");
        }
        // La sesión pudo ser revocada mientras se esperaba el row lock.
        autorizacion.exigirRoles(sesion,ADMIN);
        OffsetDateTime ahora=instanteBase();
        if(vigenteDesde.isBefore(ahora)) {
            throw new IllegalArgumentException("No se permiten precios retroactivos");
        }
        Long actor=sql.queryForObject("""
                SELECT "idUsuario" FROM "SESION_USUARIO" WHERE "idSesionUsuario"=:sesion
                """,Map.of("sesion",sesion),Long.class);
        if(actor==null)throw new SecurityException("Sesion interna inexistente");

        // IDs BIGINT MR5 carecen de secuencias. Todos los escritores de precio
        // y auditoria deben usar este lock global hasta un generador aprobado.
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(1919001)");
        List<Vigencia> periodos=sql.query("""
                SELECT "idPrecio","precioBase","vigenteDesde","vigenteHasta"
                  FROM "PRECIO_PRODUCTO"
                 WHERE "idProducto"=:id AND "fechaHoraCancelacion" IS NULL
                 ORDER BY "vigenteDesde","idPrecio" FOR UPDATE
                """,Map.of("id",idProducto),(rs,i)->new Vigencia(
                rs.getLong("idPrecio"),rs.getBigDecimal("precioBase"),
                rs.getObject("vigenteDesde",OffsetDateTime.class),
                rs.getObject("vigenteHasta",OffsetDateTime.class)));

        Vigencia cerrable=null;
        for(var p:periodos){
            // Rechazar cualquier nuevo precio cuya fecha inicial caiga dentro
            // de un precio ya programado: no modificar programaciones ajenas.
            if(!p.desde().isBefore(vigenteDesde)) {
                if(vigenteHasta==null || p.desde().isBefore(vigenteHasta)) {
                    throw new IllegalArgumentException("Intervalo solapado con precio programado");
                }
            } else if(p.hasta()==null || p.hasta().isAfter(vigenteDesde)) {
                if(cerrable!=null)throw new IllegalStateException("Periodos previos ambiguos");
                cerrable=p;
            }
        }
        long idPrecio=siguientePrecio();
        long idAuditoria=siguienteAuditoria();
        Long idCerrado=null;
        if(cerrable!=null) {
            idCerrado=cerrable.idPrecio();
            sql.update("""
                    UPDATE "PRECIO_PRODUCTO"
                       SET "vigenteHasta"=:hasta
                     WHERE "idPrecio"=:id
                    """,Map.of("hasta",vigenteDesde,"id",cerrable.idPrecio()));
            sql.update("""
                    INSERT INTO "AUDITORIA_PRECIO"
                      ("idAuditoriaPrecio","idPrecio","idUsuario","fechaHoraEvento",
                       "tipoEvento","precioAnterior","precioNuevo",
                       "vigenteDesdeAnterior","vigenteDesdeNuevo",
                       "vigenteHastaAnterior","vigenteHastaNuevo")
                    VALUES (:aud,:id,:actor,:ahora,'CIERRE_VIGENCIA',
                            :precio,:precio,:desde,:desde,:antes,:despues)
                    """,new MapSqlParameterSource().addValue("aud",idAuditoria)
                    .addValue("id",cerrable.idPrecio()).addValue("actor",actor)
                    .addValue("ahora",ahora).addValue("precio",cerrable.precio())
                    .addValue("desde",cerrable.desde())
                    .addValue("antes",cerrable.hasta()).addValue("despues",vigenteDesde));
            idAuditoria++;
        }
        sql.update("""
                INSERT INTO "PRECIO_PRODUCTO"
                 ("idPrecio","idProducto","precioBase","vigenteDesde",
                  "vigenteHasta","fechaHoraRegistro","idUsuarioRegistro")
                VALUES (:id,:producto,:precio,:desde,:hasta,:ahora,:actor)
                """,new MapSqlParameterSource().addValue("id",idPrecio)
                .addValue("producto",idProducto).addValue("precio",nuevoPrecioBase)
                .addValue("desde",vigenteDesde).addValue("hasta",vigenteHasta)
                .addValue("ahora",ahora).addValue("actor",actor));
        sql.update("""
                INSERT INTO "AUDITORIA_PRECIO"
                  ("idAuditoriaPrecio","idPrecio","idUsuario","fechaHoraEvento",
                   "tipoEvento","precioAnterior","precioNuevo",
                   "vigenteDesdeNuevo","vigenteHastaNuevo")
                VALUES (:aud,:id,:actor,:ahora,'MODIFICACION_PROGRAMADA',
                        :anterior,:nuevo,:desde,:hasta)
                """,new MapSqlParameterSource().addValue("aud",idAuditoria)
                .addValue("id",idPrecio).addValue("actor",actor).addValue("ahora",ahora)
                .addValue("anterior",cerrable==null?null:cerrable.precio())
                .addValue("nuevo",nuevoPrecioBase).addValue("desde",vigenteDesde)
                .addValue("hasta",vigenteHasta));
        return new PrecioActualizado(idPrecio,idAuditoria,idCerrado,vigenteDesde,vigenteHasta);
    }

    private OffsetDateTime instanteBase() {
        OffsetDateTime ahora=sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()",
                (rs,i)->rs.getObject(1,OffsetDateTime.class));
        if(ahora==null)throw new IllegalStateException("Reloj PostgreSQL no disponible");
        return ahora;
    }

    private long siguientePrecio() {
        Long valor=sql.getJdbcTemplate().queryForObject("""
                SELECT COALESCE(MAX("idPrecio"),0)+1 FROM "PRECIO_PRODUCTO"
                """,Long.class);
        if(valor==null || valor<=0)throw new IllegalStateException("Sin ID de Precio");
        return valor;
    }

    private long siguienteAuditoria() {
        Long valor=sql.getJdbcTemplate().queryForObject("""
                SELECT COALESCE(MAX("idAuditoriaPrecio"),0)+1 FROM "AUDITORIA_PRECIO"
                """,Long.class);
        if(valor==null || valor<=0)throw new IllegalStateException("Sin ID de Auditoria");
        return valor;
    }
}
