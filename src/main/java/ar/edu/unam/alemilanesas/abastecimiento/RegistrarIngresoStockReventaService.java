package ar.edu.unam.alemilanesas.abastecimiento;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC08 REVENTA, REV03B §7/H06 y RD-UC08-002 A-E. Sin partidas ni costos de INSUMO. */
@Service
public class RegistrarIngresoStockReventaService {
    private static final Set<String> PRODUCCION = Set.of("RESPONSABLE_PRODUCCION");
    private static final String STOCK = "stockActualReventa";
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;

    public RegistrarIngresoStockReventaService(NamedParameterJdbcTemplate sql,
            AutorizacionSesionInterna autorizacion) {
        this.sql=sql;
        this.autorizacion=autorizacion;
    }
    public record DatosIngresoReventa(long idProducto, long idProveedorDirecto, BigDecimal cantidadRecibida,
            String codigoUnidadMedidaRecibida, BigDecimal costoUnitarioCompra, String comprobante) {}
    public record IngresoReventaRegistrado(long idRecepcionCompra, long idMovimientoStockReventa,
            BigDecimal nuevoCostoPromedio) {}

    @Transactional
    public IngresoReventaRegistrado registrarIngresoStockPorCompra(UUID sesion, DatosIngresoReventa datos) {
        autorizacion.exigirRoles(sesion,PRODUCCION);
        validar(datos);
        // Compartido con UC08 INSUMO: RECEPCION_COMPRA tiene IDs globales MAX+1.
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(808, 0)");
        sql.getJdbcTemplate().queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,datos.idProducto());
        autorizacion.exigirRoles(sesion,PRODUCCION);
        var p=new MapSqlParameterSource("producto",datos.idProducto())
                .addValue("proveedor",datos.idProveedorDirecto()).addValue("cantidad",datos.cantidadRecibida())
                .addValue("unidad",datos.codigoUnidadMedidaRecibida()).addValue("costo",datos.costoUnitarioCompra())
                .addValue("comprobante",datos.comprobante());
        var productos=sql.queryForList("""
                SELECT "tipoProducto","activo","codigoUnidadVenta","stockActualReventa","costoPromedioActualReventa"
                FROM "PRODUCTO" WHERE "idProducto"=:producto FOR UPDATE
                """,p);
        if(productos.isEmpty())throw new IllegalArgumentException("Producto inexistente");
        var producto=productos.getFirst();
        if(!"REVENTA".equals(producto.get("tipoProducto")))throw new IllegalArgumentException("Producto no es REVENTA");
        if(!Boolean.TRUE.equals(producto.get("activo")))throw new IllegalArgumentException("Producto inactivo");
        var proveedores=sql.queryForList("""
                SELECT "estado" FROM "PROVEEDOR" WHERE "idProveedor"=:proveedor FOR UPDATE
                """,p);
        if(proveedores.isEmpty())throw new IllegalArgumentException("Proveedor inexistente");
        if(!"ACTIVO".equals(proveedores.getFirst().get("estado")))throw new IllegalArgumentException("Proveedor inactivo");
        if(!datos.codigoUnidadMedidaRecibida().equals(producto.get("codigoUnidadVenta")))
            throw new IllegalArgumentException("Unidad no coincide");
        BigDecimal stock=producto.get(STOCK)==null?BigDecimal.ZERO:(BigDecimal)producto.get(STOCK);
        BigDecimal stockResultante=stock.add(datos.cantidadRecibida());
        if(!positivo(stockResultante,3,15))throw new IllegalArgumentException("Solicitud invalida");
        BigDecimal nuevoCosto=promedio(stock,(BigDecimal)producto.get("costoPromedioActualReventa"),datos);
        if(!positivo(nuevoCosto,4,14))throw new IllegalArgumentException("Solicitud invalida");
        Long usuario=sql.queryForObject("""
                SELECT "idUsuario" FROM "SESION_USUARIO" WHERE "idSesionUsuario"=:sesion
                """,Map.of("sesion",sesion),Long.class);
        OffsetDateTime fecha=sql.getJdbcTemplate().queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);
        long recepcion=idValido(sql.getJdbcTemplate().queryForObject(
                "SELECT COALESCE(MAX(\"idRecepcionCompra\"),0)+1 FROM \"RECEPCION_COMPRA\"",Long.class));
        long movimiento=idValido(sql.getJdbcTemplate().queryForObject(
                "SELECT COALESCE(MAX(\"idMovimientoStockReventa\"),0)+1 FROM \"MOVIMIENTO_STOCK_REVENTA\"",Long.class));
        Long secuencia=sql.queryForObject("""
                SELECT COALESCE(MAX("numeroSecuenciaProducto"),0)+1 FROM "MOVIMIENTO_STOCK_REVENTA"
                WHERE "idProducto"=:producto
                """,p,Long.class);
        p.addValue("usuario",usuario).addValue("fecha",fecha).addValue("recepcion",recepcion)
                .addValue("movimiento",movimiento).addValue("secuencia",secuencia)
                .addValue("stock",stockResultante).addValue("promedio",nuevoCosto);
        sql.update("""
                INSERT INTO "RECEPCION_COMPRA" ("idRecepcionCompra","idOrdenCompra","idProveedorDirecto",
                "idInsumoRecibido","idProductoReventa","idUsuarioResponsable","fechaHoraRecepcion",
                "cantidadRecibida","codigoUnidadMedida","costoUnitarioReal","factorConversionAplicado","referenciaComercial")
                VALUES (:recepcion,NULL,:proveedor,NULL,:producto,:usuario,:fecha,:cantidad,:unidad,:costo,1,:comprobante)
                """,p);
        // H06: actualizar valoración y stock antes de registrar su instantánea histórica.
        sql.update("""
                UPDATE "PRODUCTO" SET "stockActualReventa"=:stock,"costoPromedioActualReventa"=:promedio
                WHERE "idProducto"=:producto
                """,p);
        sql.update("""
                INSERT INTO "MOVIMIENTO_STOCK_REVENTA" ("idMovimientoStockReventa","idProducto",
                "numeroSecuenciaProducto","idRecepcionCompra","idUsuarioResponsable","tipoMovimiento",
                "cantidad","fechaHoraMovimiento","stockResultante","costoPromedioResultante")
                VALUES (:movimiento,:producto,:secuencia,:recepcion,:usuario,'INGRESO_COMPRA',:cantidad,:fecha,:stock,:promedio)
                """,p);
        return new IngresoReventaRegistrado(recepcion,movimiento,nuevoCosto);
    }

    private static BigDecimal promedio(BigDecimal stock, BigDecimal costo, DatosIngresoReventa d) {
        // No se asigna costo cero: sin existencias se inicializa al costo de la compra real.
        if(stock.signum()==0)return d.costoUnitarioCompra().setScale(4,RoundingMode.HALF_UP);
        if(costo==null || costo.signum()<=0)throw new IllegalArgumentException("Valuación extraordinaria requerida");
        return stock.multiply(costo).add(d.cantidadRecibida().multiply(d.costoUnitarioCompra()))
                .divide(stock.add(d.cantidadRecibida()),4,RoundingMode.HALF_UP);
    }
    private static long idValido(Long id) {
        if(id==null || id<=0)throw new IllegalStateException("Sin identificador");
        return id;
    }
    private static boolean positivo(BigDecimal n,int escala,int enteros) {
        return n!=null && n.signum()>0 && n.scale()<=escala && n.precision()-n.scale()<=enteros;
    }
    private static void validar(DatosIngresoReventa d) {
        if(d==null)throw new IllegalArgumentException("Solicitud invalida");
        if(d.idProveedorDirecto()<=0)throw new IllegalArgumentException("Proveedor directo requerido");
        if(d.idProducto()<=0 || !positivo(d.cantidadRecibida(),3,15) || !positivo(d.costoUnitarioCompra(),4,14)
                || d.codigoUnidadMedidaRecibida()==null || d.codigoUnidadMedidaRecibida().isBlank()
                || d.codigoUnidadMedidaRecibida().length()>20 || d.codigoUnidadMedidaRecibida().indexOf('\0')>=0
                || d.comprobante()==null || d.comprobante().isBlank() || d.comprobante().length()>200
                || d.comprobante().indexOf('\0')>=0)throw new IllegalArgumentException("Solicitud invalida");
    }
}
