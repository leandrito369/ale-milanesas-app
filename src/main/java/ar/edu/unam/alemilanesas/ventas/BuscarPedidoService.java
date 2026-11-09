package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC34: selección de filas y de campos bajo el ámbito efectivo del actor, sin escrituras. */
@Service
public class BuscarPedidoService {
    private static final String ID_PEDIDO="idPedido";
    private static final Set<String> ROLES=Set.of("ADMINISTRADOR","VENDEDOR","REPARTIDOR","RESPONSABLE_PRODUCCION");
    private static final Set<String> ESTADOS=Set.of("PENDIENTE","ENTREGADO","CANCELADO");
    private static final Set<String> CANALES=Set.of("INTERNO","ONLINE");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    public BuscarPedidoService(NamedParameterJdbcTemplate sql,AutorizacionSesionInterna autorizacion) {
        this.sql=sql;this.autorizacion=autorizacion;
    }
    public record CriterioBusquedaPedido(Long idPedido,Long idCliente,LocalDate fechaReparto,String estado,String canalOrigen) {}
    public sealed interface PedidoResumen permits PedidoAdministrativo,PedidoComercial,PedidoRepartidor,PedidoProduccion {
        long idPedido();long idCliente();LocalDate fechaReparto();String estado();String canalOrigen();
    }
    public record PedidoAdministrativo(long idPedido,long idCliente,LocalDate fechaReparto,String estado,
            String canalOrigen,OffsetDateTime fechaHoraCarga) implements PedidoResumen {}
    public record PedidoComercial(long idPedido,long idCliente,LocalDate fechaReparto,String estado,String canalOrigen,
            String categoriaComercialAplicada,BigDecimal totalOriginal,OffsetDateTime fechaHoraCarga) implements PedidoResumen {}
    public record PedidoRepartidor(long idPedido,long idCliente,LocalDate fechaReparto,String estado,
            String canalOrigen) implements PedidoResumen {}

    public record PedidoProduccion(long idPedido,long idCliente,LocalDate fechaReparto,String estado,
            String canalOrigen) implements PedidoResumen {}
    public static final class FueraDeContextoException extends SecurityException {
        private static final long serialVersionUID = 1L;
        public FueraDeContextoException() { super("FUERA_DE_CONTEXTO"); }
    }

    @Transactional(readOnly=true)
    public List<PedidoResumen> buscarPedido(UUID sesion,CriterioBusquedaPedido criterio) {
        autorizacion.exigirRoles(sesion,ROLES);
        var c=criterio==null?new CriterioBusquedaPedido(null,null,null,null,null):criterio;
        validar(c);
        var parametros=new MapSqlParameterSource("sesion",sesion)
                .addValue(ID_PEDIDO,c.idPedido(),java.sql.Types.BIGINT)
                .addValue("idCliente",c.idCliente(),java.sql.Types.BIGINT)
                .addValue("fecha",c.fechaReparto(),java.sql.Types.DATE)
                .addValue("estado",c.estado(),java.sql.Types.VARCHAR)
                .addValue("canal",c.canalOrigen(),java.sql.Types.VARCHAR);
        // Actor, roles, ámbito y campos se leen en el mismo snapshot de la sentencia.
        // LEFT JOIN preserva un actor válido sin coincidencias: sesión revocada no equivale a lista vacía.
        String consulta="""
                WITH actor AS (
                    SELECT s."idUsuario",
                        EXISTS(SELECT 1 FROM "USUARIO_ROL" r WHERE r."idUsuario"=u."idUsuario" AND r."codigoRol"='ADMINISTRADOR') AS admin,
                        EXISTS(SELECT 1 FROM "USUARIO_ROL" r WHERE r."idUsuario"=u."idUsuario" AND r."codigoRol"='VENDEDOR') AS vendedor,
                        EXISTS(SELECT 1 FROM "USUARIO_ROL" r WHERE r."idUsuario"=u."idUsuario" AND r."codigoRol"='REPARTIDOR') AS repartidor,
                        EXISTS(SELECT 1 FROM "USUARIO_ROL" r WHERE r."idUsuario"=u."idUsuario" AND r."codigoRol"='RESPONSABLE_PRODUCCION') AS produccion
                    FROM "SESION_USUARIO" s JOIN "USUARIO" u ON u."idUsuario"=s."idUsuario"
                    WHERE s."idSesionUsuario"=:sesion AND u."estado"='HABILITADO' AND NOT u."cambioCredencialPendiente"
                        AND s."fechaHoraRevocacion" IS NULL AND s."fechaHoraInicio"<=clock_timestamp()
                        AND s."fechaHoraExpiracion">clock_timestamp() AND s."fechaHoraInicio"+interval '8 hours'>clock_timestamp()
                ), preparables AS (
                    SELECT p."idPedido" FROM "PEDIDO" p WHERE p."estado"='PENDIENTE' AND EXISTS (
                        SELECT 1 FROM "DETALLE_PEDIDO" d JOIN "PRODUCTO" pr ON pr."idProducto"=d."idProducto"
                        WHERE d."idPedido"=p."idPedido" AND pr."tipoProducto"='ELABORADO'
                        AND d."cantidadOriginal">COALESCE((SELECT SUM(x."cantidadPreparada") FROM "PREPARACION" x
                            WHERE x."idPedido"=d."idPedido" AND x."numeroDetalle"=d."numeroDetalle"),0))
                )
                SELECT a.admin,a.vendedor,a.repartidor,
                    (a.produccion AND NOT(a.admin OR a.vendedor OR a.repartidor)) AS produccion,
                    (a.admin OR a.vendedor OR CAST(:idPedido AS BIGINT) IS NULL OR (a.repartidor AND EXISTS (
                        SELECT 1 FROM "ASIGNACION_REPARTO" r WHERE r."idPedido"=:idPedido
                            AND r."idUsuarioRepartidor"=a."idUsuario" AND r."fechaHoraFin" IS NULL))
                        OR (a.produccion AND NOT a.repartidor AND EXISTS (
                            SELECT 1 FROM preparables z WHERE z."idPedido"=:idPedido))) AS permitido,
                    p."idPedido",p."idCliente",p."fechaReparto",p."estado",p."canalOrigen",
                    CASE WHEN a.vendedor THEN p."categoriaComercialAplicada" END AS categoria,
                    CASE WHEN a.vendedor THEN p."totalOriginal" END AS total,
                    CASE WHEN a.admin OR a.vendedor THEN p."fechaHoraCarga" END AS carga
                FROM actor a LEFT JOIN LATERAL (
                    SELECT p."idPedido",p."idCliente",p."fechaReparto",p."estado",p."canalOrigen",
                        p."categoriaComercialAplicada",p."totalOriginal",p."fechaHoraCarga"
                    FROM "PEDIDO" p WHERE (a.admin OR a.vendedor OR (a.repartidor AND EXISTS (
                        SELECT 1 FROM "ASIGNACION_REPARTO" r WHERE r."idPedido"=p."idPedido"
                            AND r."idUsuarioRepartidor"=a."idUsuario" AND r."fechaHoraFin" IS NULL))
                        OR (a.produccion AND NOT a.repartidor AND EXISTS (
                            SELECT 1 FROM preparables z WHERE z."idPedido"=p."idPedido")))
                        AND (CAST(:idPedido AS BIGINT) IS NULL OR p."idPedido"=:idPedido)
                        AND (CAST(:idCliente AS BIGINT) IS NULL OR p."idCliente"=:idCliente)
                        AND (CAST(:fecha AS DATE) IS NULL OR p."fechaReparto"=:fecha)
                        AND (CAST(:estado AS VARCHAR) IS NULL OR p."estado"=:estado)
                        AND (CAST(:canal AS VARCHAR) IS NULL OR p."canalOrigen"=:canal)
                ) p ON true WHERE a.admin OR a.vendedor OR a.repartidor OR a.produccion
                ORDER BY p."fechaHoraCarga" DESC,p."idPedido" DESC
                """;
        List<Optional<PedidoResumen>> filas=sql.query(consulta,parametros,(rs,n)->{
            if (!rs.getBoolean("permitido")) {
                if (rs.getBoolean("produccion")) throw new FueraDeContextoException();
                throw new SecurityException("Pedido fuera del ambito autorizado");
            }
            if (rs.getObject(ID_PEDIDO)==null) return Optional.empty();
            return Optional.of(proyectar(rs));
        });
        if (filas.isEmpty()) throw new SecurityException("Sesion o ambito no autorizado");
        return filas.stream().flatMap(Optional::stream).toList();
    }
    private static PedidoResumen proyectar(ResultSet rs) throws SQLException {
        long pedido=rs.getLong(ID_PEDIDO);
        long cliente=rs.getLong("idCliente");
        LocalDate fecha=rs.getObject("fechaReparto",LocalDate.class);
        String estado=rs.getString("estado");
        String canal=rs.getString("canalOrigen");
        if (rs.getBoolean("vendedor")) return new PedidoComercial(pedido,cliente,fecha,estado,canal,
                rs.getString("categoria"),rs.getBigDecimal("total"),rs.getObject("carga",OffsetDateTime.class));
        if (rs.getBoolean("admin")) return new PedidoAdministrativo(pedido,cliente,fecha,estado,canal,rs.getObject("carga",OffsetDateTime.class));
        if (rs.getBoolean("produccion")) return new PedidoProduccion(pedido,cliente,fecha,estado,canal);
        return new PedidoRepartidor(pedido,cliente,fecha,estado,canal);
    }
    private static void validar(CriterioBusquedaPedido c) {
        if (c.idPedido()!=null && c.idPedido()<=0 || c.idCliente()!=null && c.idCliente()<=0)
            throw new IllegalArgumentException("Criterio invalido");
        if (c.estado()!=null && !ESTADOS.contains(c.estado()) || c.canalOrigen()!=null && !CANALES.contains(c.canalOrigen()))
            throw new IllegalArgumentException("Criterio invalido");
    }
}
