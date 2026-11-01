package ar.edu.unam.alemilanesas.maestros;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC20 B3: lectura autorizada sin mutar vigencias, precios ni auditoria. */
@Service
public class ConsultarHistorialPreciosService {
    private static final Set<String> ADMIN=Set.of("ADMINISTRADOR");
    private static final ZoneId ZONA_NEGOCIO=ZoneId.of("America/Argentina/Buenos_Aires");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public ConsultarHistorialPreciosService(NamedParameterJdbcTemplate sql,
                                             AutorizacionSesionInterna autorizacion) {
        this.sql=sql;
        this.autorizacion=autorizacion;
    }

    public record PrecioHistorico(long idPrecio, BigDecimal precioBase, OffsetDateTime vigenteDesde,
                                 OffsetDateTime vigenteHasta, OffsetDateTime fechaRegistro,
                                 long idUsuarioRegistro, boolean cancelado, String clasificacion) {}
    public record EventoPrecio(long idAuditoriaPrecio, long idPrecio, long idUsuario,
                               OffsetDateTime fechaHoraEvento, String tipoEvento,
                               BigDecimal precioAnterior, BigDecimal precioNuevo,
                               OffsetDateTime vigenteDesdeAnterior, OffsetDateTime vigenteDesdeNuevo,
                               OffsetDateTime vigenteHastaAnterior, OffsetDateTime vigenteHastaNuevo) {}
    public record HistorialPrecios(long idProducto, OffsetDateTime fechaConsulta,
                                  Long idPrecioActual, List<PrecioHistorico> precios,
                                  List<EventoPrecio> auditorias) {}

    @Transactional(readOnly=true)
    public HistorialPrecios consultarHistorialPrecios(UUID sesion,long idProducto,
                                                     LocalDate fechaDesde,LocalDate fechaHasta) {
        autorizacion.exigirRoles(sesion,ADMIN);
        if(idProducto<=0 || (fechaDesde!=null && fechaHasta!=null
                && fechaDesde.isAfter(fechaHasta))) {
            throw new IllegalArgumentException("Rango de consulta invalido");
        }
        Integer existe=sql.queryForObject("""
                SELECT count(*) FROM "PRODUCTO" WHERE "idProducto"=:id
                """,Map.of("id",idProducto),Integer.class);
        if(existe==null||existe!=1)throw new IllegalArgumentException("Producto inexistente");

        OffsetDateTime ahora=sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()",
                (rs,i)->rs.getObject(1,OffsetDateTime.class));
        if(ahora==null)throw new IllegalStateException("Reloj PostgreSQL no disponible");
        var args=new MapSqlParameterSource().addValue("id",idProducto);
        var consulta=new StringBuilder("""
                SELECT "idPrecio","precioBase","vigenteDesde","vigenteHasta",
                       "fechaHoraRegistro","idUsuarioRegistro","fechaHoraCancelacion"
                  FROM "PRECIO_PRODUCTO" WHERE "idProducto"=:id
                """);
        // Criterio temporal: intersectar [vigenteDesde,vigenteHasta)
        // con los dias del calendario comercial en Argentina. No confundir
        // fechas de vigencia con fechaHoraRegistro.
        if(fechaDesde!=null) {
            consulta.append(" AND (\"vigenteHasta\" IS NULL OR \"vigenteHasta\">:inicio) ");
            args.addValue("inicio",fechaDesde.atStartOfDay(ZONA_NEGOCIO).toOffsetDateTime());
        }
        if(fechaHasta!=null) {
            consulta.append(" AND \"vigenteDesde\"<:finExclusivo ");
            args.addValue("finExclusivo",fechaHasta.plusDays(1).atStartOfDay(ZONA_NEGOCIO).toOffsetDateTime());
        }
        consulta.append(" ORDER BY \"vigenteDesde\",\"idPrecio\" ");
        List<PrecioHistorico> precios=sql.query(consulta.toString(),args,(rs,i)->{
            OffsetDateTime desde=rs.getObject("vigenteDesde",OffsetDateTime.class);
            OffsetDateTime hasta=rs.getObject("vigenteHasta",OffsetDateTime.class);
            boolean cancelado=rs.getObject("fechaHoraCancelacion")!=null;
            String estado=cancelado?"CANCELADO":desde.isAfter(ahora)?"FUTURO":
                    (hasta==null || hasta.isAfter(ahora))?"ACTUAL":"HISTORICO";
            return new PrecioHistorico(rs.getLong("idPrecio"),rs.getBigDecimal("precioBase"),
                    desde,hasta,rs.getObject("fechaHoraRegistro",OffsetDateTime.class),
                    rs.getLong("idUsuarioRegistro"),cancelado,estado);
        });
        Long idPrecioActual=null;
        for(var precio:precios) {
            if("ACTUAL".equals(precio.clasificacion())) {
                if(idPrecioActual!=null)throw new IllegalStateException("Mas de un precio actual");
                idPrecioActual=precio.idPrecio();
            }
        }
        // Los eventos se consultan por el idPrecio del conjunto seleccionado,
        // sin inventar la auditoria de un Producto o una version.
        List<EventoPrecio> eventos=sql.query("""
                SELECT a."idAuditoriaPrecio",a."idPrecio",a."idUsuario",
                       a."fechaHoraEvento",a."tipoEvento",
                       a."precioAnterior",a."precioNuevo",
                       a."vigenteDesdeAnterior",a."vigenteDesdeNuevo",
                       a."vigenteHastaAnterior",a."vigenteHastaNuevo"
                  FROM "AUDITORIA_PRECIO" a
                  JOIN "PRECIO_PRODUCTO" p ON p."idPrecio"=a."idPrecio"
                 WHERE p."idProducto"=:id
                """ + (precios.isEmpty()?" AND false ":
                " AND a.\"idPrecio\" IN (:precios) ") +
                " ORDER BY a.\"fechaHoraEvento\",a.\"idAuditoriaPrecio\"",
                new MapSqlParameterSource().addValue("id",idProducto)
                        .addValue("precios",precios.isEmpty()?List.of(-1L):
                                precios.stream().map(PrecioHistorico::idPrecio).toList()),
                (rs,i)->new EventoPrecio(rs.getLong("idAuditoriaPrecio"),rs.getLong("idPrecio"),
                        rs.getLong("idUsuario"),
                        rs.getObject("fechaHoraEvento",OffsetDateTime.class),
                        rs.getString("tipoEvento"),
                        rs.getBigDecimal("precioAnterior"),rs.getBigDecimal("precioNuevo"),
                        rs.getObject("vigenteDesdeAnterior",OffsetDateTime.class),
                        rs.getObject("vigenteDesdeNuevo",OffsetDateTime.class),
                        rs.getObject("vigenteHastaAnterior",OffsetDateTime.class),
                        rs.getObject("vigenteHastaNuevo",OffsetDateTime.class)));
        return new HistorialPrecios(idProducto,ahora,idPrecioActual,
                List.copyOf(precios),List.copyOf(eventos));
    }
}
