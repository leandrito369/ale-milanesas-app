package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import ar.edu.unam.alemilanesas.finanzas.AplicarAjusteComercialService;
import ar.edu.unam.alemilanesas.finanzas.AplicarAjusteComercialService.AjusteAplicado;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC03: aceptación central de un Pedido INTERNO y sus snapshots, en una transacción. */
@Service
public class CargarPedidoService {
    private static final String INVALIDA = "Solicitud invalida";
    private static final String INTERNO = "INTERNO";
    private static final Set<String> VENDEDORES = Set.of("VENDEDOR");
    private final NamedParameterJdbcTemplate sql;
    private final JdbcTemplate jdbc;
    private final AutorizacionSesionInterna autorizacion;
    private final AplicarAjusteComercialService ajustes;

    public CargarPedidoService(NamedParameterJdbcTemplate sql,AutorizacionSesionInterna autorizacion,
            AplicarAjusteComercialService ajustes) {
        this.sql=sql;this.jdbc=sql.getJdbcTemplate();this.autorizacion=autorizacion;this.ajustes=ajustes;
    }
    public record ItemPedido(long idProducto,BigDecimal cantidadSolicitada) {}
    public record DireccionEntrega(String calle,String numero,String piso,String unidadFuncional,
            String localidad,String provincia,String referencia,String zona) {}
    public record DatosPedido(long idCliente,LocalDate fechaReparto,List<ItemPedido> items,
            DireccionEntrega direccionEntrega,UUID claveIdempotencia) {}
    /** creado distingue 201/200 dentro de la transacción; B4 no lo publica como campo JSON. */
    public record PedidoCreado(long idPedido,String estado,String canalOrigen,LocalDate fechaReparto,
            String categoriaComercialAplicada,BigDecimal totalOriginal,int cantidadItems,boolean creado) {}
    private record ItemCongelado(long idProducto,BigDecimal cantidad,String unidad,BigDecimal precio) {}

    @Transactional
    public PedidoCreado cargarPedido(UUID sesion,DatosPedido datos) {
        autorizacion.exigirRoles(sesion,VENDEDORES);
        validar(datos);
        // Espacio de dos enteros separado de los locks por entidad: MAX+1 global y UUID entre clientes.
        jdbc.execute("SELECT pg_advisory_xact_lock(3003,0)");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",(rs,n)->true,datos.idCliente());
        autorizacion.exigirRoles(sesion,VENDEDORES);
        var anterior=anterior(datos);
        if (anterior!=null) return anterior;
        exigirCliente(datos.idCliente());
        LocalDate hoy=jdbc.queryForObject("SELECT (clock_timestamp() AT TIME ZONE 'America/Argentina/Buenos_Aires')::date",LocalDate.class);
        if (datos.fechaReparto().isBefore(hoy)) throw new IllegalArgumentException("Fecha de reparto no puede ser retroactiva");
        var items=congelarItems(datos.items());
        BigDecimal subtotal=items.stream().map(i->i.cantidad().multiply(i.precio()))
                .reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2,RoundingMode.HALF_UP);
        // UC16 incluye UC15 y devuelve la política, categoría y total post-ajuste que se congelan.
        AjusteAplicado ajuste=aplicarAjuste(datos.idCliente(),subtotal);
        long id=jdbc.queryForObject("SELECT COALESCE(MAX(\"idPedido\"),0)+1 FROM \"PEDIDO\"",Long.class);
        long usuario=jdbc.queryForObject("SELECT \"idUsuario\" FROM \"SESION_USUARIO\" WHERE \"idSesionUsuario\"=?",Long.class,sesion);
        insertarPedido(id,usuario,datos,ajuste);
        insertarDetalles(id,items);
        return new PedidoCreado(id,"PENDIENTE",INTERNO,datos.fechaReparto(),ajuste.categoriaComercialAplicada(),
                ajuste.totalOriginal(),items.size(),true);
    }
    private PedidoCreado anterior(DatosPedido datos) {
        var pedidos=jdbc.query("""
                SELECT p.*, (SELECT count(*) FROM "DETALLE_PEDIDO" d WHERE d."idPedido"=p."idPedido") AS items
                FROM "PEDIDO" p WHERE "idOperacionOrigen"=?
                """,(rs,n)-> {
                    if (rs.getLong("idCliente")!=datos.idCliente() || !INTERNO.equals(rs.getString("canalOrigen")))
                        throw new IllegalArgumentException("Clave de operacion ya utilizada con otros datos");
                    return new PedidoCreado(rs.getLong("idPedido"),rs.getString("estado"),rs.getString("canalOrigen"),
                            rs.getObject("fechaReparto",LocalDate.class),rs.getString("categoriaComercialAplicada"),
                            rs.getBigDecimal("totalOriginal"),rs.getInt("items"),false);
                },datos.claveIdempotencia());
        return pedidos.isEmpty()?null:pedidos.getFirst();
    }
    private void exigirCliente(long id) {
        var estados=jdbc.queryForList("SELECT \"estado\" FROM \"CLIENTE\" WHERE \"idCliente\"=? FOR UPDATE",String.class,id);
        if (estados.size()!=1 || !"ACTIVO".equals(estados.getFirst()))
            throw new IllegalArgumentException("Cliente inexistente o inactivo");
    }
    private List<ItemCongelado> congelarItems(List<ItemPedido> items) {
        var unidades=new HashMap<Long,String>();
        // Orden único para pedidos multiproducto, compatible con los locks de actualización del maestro.
        for (long id:items.stream().map(ItemPedido::idProducto).sorted().toList()) {
            var productos=jdbc.queryForList("SELECT \"codigoUnidadVenta\",\"activo\" FROM \"PRODUCTO\" WHERE \"idProducto\"=? FOR SHARE",id);
            if (productos.size()!=1 || !Boolean.TRUE.equals(productos.getFirst().get("activo")))
                throw new IllegalArgumentException("Producto inexistente o inactivo");
            unidades.put(id,(String)productos.getFirst().get("codigoUnidadVenta"));
        }
        OffsetDateTime ahora=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);
        var congelados=new ArrayList<ItemCongelado>();
        for (var item:items) {
            var precios=jdbc.queryForList("""
                    SELECT "precioBase" FROM "PRECIO_PRODUCTO" WHERE "idProducto"=? AND "vigenteDesde"<=?
                    AND ("vigenteHasta" IS NULL OR "vigenteHasta">?) AND "fechaHoraCancelacion" IS NULL FOR SHARE
                    """,BigDecimal.class,item.idProducto(),ahora,ahora);
            if (precios.size()!=1) throw new IllegalArgumentException("Producto sin precio vigente");
            congelados.add(new ItemCongelado(item.idProducto(),item.cantidadSolicitada(),unidades.get(item.idProducto()),precios.getFirst()));
        }
        return congelados;
    }
    private AjusteAplicado aplicarAjuste(long cliente,BigDecimal subtotal) {
        try {return ajustes.aplicarAjusteComercialSegunCategoria(cliente,subtotal);}
        catch (IllegalStateException ex) {
            // A4 del contrato: rechazar sin inventar política ni porcentaje.
            if (Set.of("Se requiere una politica comercial vigente e inequivoca","Politica vigente ausente o ambigua",
                    "Ajuste de categoria inexistente","Regla de politica inconsistente").contains(ex.getMessage()))
                throw new IllegalArgumentException("Politica comercial no determinable",ex);
            throw ex;
        }
    }
    private void insertarPedido(long id,long usuario,DatosPedido datos,AjusteAplicado ajuste) {
        var d=datos.direccionEntrega();
        sql.update("""
                INSERT INTO "PEDIDO" ("idPedido","idCliente","idUsuarioCreador","idSesionOnline","fechaHoraCarga",
                    "fechaReparto","canalOrigen","estado","categoriaComercialAplicada","idPoliticaComercialAplicada",
                    "tipoAjuste","porcentajeAjuste","totalOriginal","idOperacionOrigen",
                    "direccionEntregaCalle","direccionEntregaNumero","direccionEntregaPiso","direccionEntregaUnidadFuncional",
                    "direccionEntregaLocalidad","direccionEntregaProvincia","direccionEntregaReferencia","direccionEntregaZona")
                VALUES (:id,:cliente,:usuario,NULL,clock_timestamp(),:fecha,'INTERNO','PENDIENTE',:categoria,:politica,
                    :tipo,:porcentaje,:total,:clave,:calle,:numero,:piso,:unidad,:localidad,:provincia,:referencia,:zona)
                """,new MapSqlParameterSource().addValue("id",id).addValue("cliente",datos.idCliente()).addValue("usuario",usuario)
                .addValue("fecha",datos.fechaReparto()).addValue("categoria",ajuste.categoriaComercialAplicada())
                .addValue("politica",ajuste.idPoliticaComercial()).addValue("tipo",ajuste.tipoAjuste())
                .addValue("porcentaje",ajuste.porcentajeAjuste()).addValue("total",ajuste.totalOriginal())
                .addValue("clave",datos.claveIdempotencia()).addValue("calle",d.calle()).addValue("numero",d.numero())
                .addValue("piso",d.piso()).addValue("unidad",d.unidadFuncional()).addValue("localidad",d.localidad())
                .addValue("provincia",d.provincia()).addValue("referencia",d.referencia()).addValue("zona",d.zona()));
    }
    private void insertarDetalles(long pedido,List<ItemCongelado> items) {
        int numero=0;
        for (var item:items) jdbc.update("""
                INSERT INTO "DETALLE_PEDIDO" ("idPedido","numeroDetalle","idProducto","codigoUnidadVenta",
                    "cantidadOriginal","precioUnitarioOriginal","porcentajePromocionOriginal","idPromocionAplicada")
                VALUES (?,?,?,?,?,?,0,NULL)
                """,pedido,++numero,item.idProducto(),item.unidad(),item.cantidad(),item.precio());
    }
    private static void validar(DatosPedido d) {
        if (d==null || d.idCliente()<=0 || d.fechaReparto()==null || d.claveIdempotencia()==null)
            throw new IllegalArgumentException(INVALIDA);
        if (d.items()==null || d.items().isEmpty()) throw new IllegalArgumentException(INVALIDA);
        var productos=new HashSet<Long>();
        for (var item:d.items()) {
            if (item==null || item.idProducto()<=0 || !cantidadValida(item.cantidadSolicitada()))
                throw new IllegalArgumentException(INVALIDA);
            if (!productos.add(item.idProducto())) throw new IllegalArgumentException(INVALIDA);
        }
        validarDireccion(d.direccionEntrega());
    }
    private static boolean cantidadValida(BigDecimal cantidad) {
        return cantidad!=null && cantidad.signum()>0 && cantidad.scale()<=3
                && cantidad.precision()-cantidad.scale()<=15;
    }
    private static void validarDireccion(DireccionEntrega d) {
        if (d==null) throw new IllegalArgumentException(INVALIDA);
        texto(d.calle(),150,true);texto(d.numero(),20,false);texto(d.piso(),10,false);texto(d.unidadFuncional(),20,false);
        texto(d.localidad(),100,true);texto(d.provincia(),100,false);texto(d.referencia(),250,false);texto(d.zona(),100,false);
    }
    private static void texto(String valor,int maximo,boolean requerido) {
        if (requerido && (valor==null || valor.isBlank())) throw new IllegalArgumentException(INVALIDA);
        if (valor!=null && (valor.length()>maximo || valor.indexOf('\0')>=0)) throw new IllegalArgumentException(INVALIDA);
    }
}
