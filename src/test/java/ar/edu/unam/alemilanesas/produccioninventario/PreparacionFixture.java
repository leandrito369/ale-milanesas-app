package ar.edu.unam.alemilanesas.produccioninventario;

import ar.edu.unam.alemilanesas.ventas.CargarPedidoService;
import ar.edu.unam.alemilanesas.ventas.PedidoFixture;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Datos confirmados en PostgreSQL dedicado; receta vigente y stock conciliado con sus partidas. */
public final class PreparacionFixture {
    public final PedidoFixture actores;
    public final long producto;
    public final long insumo;
    public final long receta;
    public final long lote;
    public final long pedido;
    public final OffsetDateTime fecha;
    public final JdbcTemplate j;
    public PreparacionFixture(JdbcTemplate j, TransactionTemplate tx, CargarPedidoService pedidos) {
        this.j = j;
        actores = tx.execute(s -> new PedidoFixture(j));
        producto = actores.base; insumo = actores.base + 20; receta = actores.base + 40; lote = actores.base + 50;
        fecha = j.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);
        pedido = tx.execute(s -> {
            for (int n = 0; n < 2; n++) {
                j.update("""
                        INSERT INTO "INSUMO" ("idInsumo","nombre","codigoUnidadMedida","stockActual","umbralMinimo",
                            "nivelObjetivoReposicion","automatizacionCompraActiva","activo") VALUES (?,?,'KG',10,2,20,true,true)
                        """,insumo+n,"UC28 insumo "+(insumo+n));
                j.update("""
                        INSERT INTO "COSTO_INSUMO_HISTORICO" ("idInsumo","vigenteDesde","costoUnitario","fuenteCosto")
                        VALUES (?,?,100,'SALDO_INICIAL')
                        """,insumo+n,fecha.minusDays(3));
                partida(lote+n,insumo+n,new BigDecimal("10"),null,fecha.minusDays(2),null);
            }
            j.update("INSERT INTO \"RECETA\" VALUES (?,?)",receta,producto);
            j.update("""
                    INSERT INTO "VERSION_RECETA" ("idReceta","numeroVersion","vigenteDesde","cantidadRendimientoBase","codigoUnidadRendimiento")
                    VALUES (?,1,?,1,'UN')
                    """,receta,fecha.minusDays(2));
            j.update("INSERT INTO \"DETALLE_VERSION_RECETA\" VALUES (?,1,?,0.2,'KG'),(?,1,?,0.5,'KG')",receta,insumo,receta,insumo+1);
            var d = actores.datos();
            return pedidos.cargarPedido(actores.vendedor(),new CargarPedidoService.DatosPedido(d.idCliente(),d.fechaReparto(),
                    List.of(new CargarPedidoService.ItemPedido(producto,new BigDecimal("10"))),d.direccionEntrega(),UUID.randomUUID())).idPedido();
        });
    }
    public UUID produccion() { return actores.sesiones.get("RESPONSABLE_PRODUCCION"); }
    public DatosPreparacion datos(String cantidad) { return new DatosPreparacion(pedido,producto,new BigDecimal(cantidad),null); }
    public DatosPreparacion manual(String cantidad,List<PartidaAplicada> partidas) {
        return new DatosPreparacion(pedido,producto,new BigDecimal(cantidad),partidas);
    }
    public List<PartidaAplicada> override() {
        return List.of(new PartidaAplicada(insumo,lote,new BigDecimal("1")),new PartidaAplicada(insumo+1,lote+1,new BigDecimal("2.5")));
    }
    public void partida(long id,long insumoId,BigDecimal cantidad,LocalDate vencimiento,OffsetDateTime registro,OffsetDateTime apertura) {
        j.update("""
                INSERT INTO "PARTIDA_INSUMO" ("idPartidaInsumo","idInsumo","codigoLote","fechaVencimiento","fechaHoraRegistro",
                    "fechaHoraApertura","cantidadInicial","cantidadDisponible") VALUES (?,?,'UC28',?,?,?,?,?)
                """,id,insumoId,vencimiento,registro,apertura,cantidad,cantidad);
    }
    public BigDecimal disponible(long id) {
        return j.queryForObject("SELECT \"cantidadDisponible\" FROM \"PARTIDA_INSUMO\" WHERE \"idPartidaInsumo\"=?",BigDecimal.class,id);
    }
    public BigDecimal stock(long id) { return j.queryForObject("SELECT \"stockActual\" FROM \"INSUMO\" WHERE \"idInsumo\"=?",BigDecimal.class,id); }
    public int preparaciones() { return j.queryForObject("SELECT count(*) FROM \"PREPARACION\" WHERE \"idPedido\"=?",Integer.class,pedido); }
    public Map<String,String> snapshot() {
        var resultado = new HashMap<String,String>();
        for (String tabla : List.of("PEDIDO","DETALLE_PEDIDO","PREPARACION","INSUMO","PARTIDA_INSUMO","MOVIMIENTO_INSUMO",
                "DETALLE_MOVIMIENTO_PARTIDA","EPISODIO_FALTANTE","COSTO_INSUMO_HISTORICO","SOLICITUD_COTIZACION"))
            resultado.put(tabla,j.queryForObject("SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]')) FROM \""+tabla+"\" t",String.class));
        return resultado;
    }
}
