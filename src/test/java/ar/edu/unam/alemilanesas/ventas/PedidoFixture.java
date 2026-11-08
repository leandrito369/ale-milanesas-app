package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/** Datos sintéticos para UC03; el llamador elige la transacción de aislamiento. */
public final class PedidoFixture {
    private static final AtomicLong IDS = new AtomicLong(9030000);
    public final long base = IDS.addAndGet(100);
    public final Map<String,UUID> sesiones = new HashMap<>();
    public final Map<String,String> tokens = new HashMap<>();
    public final long politica;
    public final LocalDate hoy;

    public PedidoFixture(JdbcTemplate j) {
        hoy = j.queryForObject("SELECT (clock_timestamp() AT TIME ZONE 'America/Argentina/Buenos_Aires')::date",LocalDate.class);
        j.update("INSERT INTO \"UNIDAD_MEDIDA\" VALUES ('UN','Unidad'),('KG','Kilogramo') ON CONFLICT DO NOTHING");
        String[] roles = {"VENDEDOR","ADMINISTRADOR","RESPONSABLE_PRODUCCION","REPARTIDOR","PROVEEDOR","VENDEDOR"};
        j.update("""
                INSERT INTO "PROVEEDOR" ("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                VALUES (?,?,'Proveedor UC03','ACTIVO',now())
                """,base, String.format("%011d",base));

        for (int n=0;n<roles.length;n++) actor(j,roles[n],n);
        for (int n=0;n<3;n++) {
            j.update("""
                    INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal","estado","categoriaComercial","fechaHoraAlta")
                    VALUES (?,?,'3764000300',?,'CUMPLIDOR',now())
                    """,base+n,"Cliente UC03 "+(base+n),n==2?"INACTIVO":"ACTIVO");
        }
        j.update("""
                INSERT INTO "MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCliente","numeroSecuenciaCliente",
                    "tipoMovimiento","importe","fechaHoraMovimiento","saldoResultante")
                VALUES (?,?,1,'AJUSTE_DEBITO',1000,clock_timestamp()-interval '10 days',1000)
                """,base,base+1);
        for (int n=0;n<5;n++) {
            j.update("""
                    INSERT INTO "PRODUCTO" ("idProducto","nombre","nombreNormalizado","tipoProducto","codigoUnidadVenta","activo")
                    VALUES (?,?,?,'ELABORADO',?,?)
                    """,base+n,"Producto UC03 "+(base+n),"producto uc03 "+(base+n),n==1?"KG":"UN",n!=4);
            if (n!=3) j.update("""
                    INSERT INTO "PRECIO_PRODUCTO" ("idPrecio","idProducto","precioBase","vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                    VALUES (?,?,?,clock_timestamp()-interval '1 day',now(),?)
                    """,base+n,base+n,new BigDecimal(n==1?"200.0000":"100.0000"),base+1);
        }
        var existentes = j.queryForList("""
                SELECT "idPoliticaComercial" FROM "POLITICA_CATEGORIA_COMERCIAL"
                WHERE "vigenteDesde"<=clock_timestamp() AND ("vigenteHasta" IS NULL OR "vigenteHasta">clock_timestamp())
                """,Long.class);
        politica = existentes.isEmpty()?base:existentes.getFirst();
        if (existentes.isEmpty()) {
            j.update("""
                    INSERT INTO "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial","diasToleranciaMora","vigenteDesde","fechaHoraRegistro","idUsuarioRegistro")
                    VALUES (?,5,clock_timestamp()-interval '20 days',now(),?)
                    """,politica,base+1);
            j.update("""
                    INSERT INTO "AJUSTE_POLITICA_CATEGORIA" VALUES (?,'CUMPLIDOR','DESCUENTO',10),(?,'EN_MORA','RECARGO',20)
                    """,politica,politica);
        }
    }
    private void actor(JdbcTemplate j,String rol,int numero) {
        j.update("INSERT INTO \"ROL\" VALUES (?,?) ON CONFLICT DO NOTHING",rol,rol);
        j.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES (?,?,'HASH_TEST','HABILITADO',false,now())
                """,base+numero,"uc03_"+(base+numero));
        j.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,?,now())",base+numero,rol);
        if (numero==4) j.update("INSERT INTO \"USUARIO_PROVEEDOR\" VALUES (?,?,now())",base+numero,base);
        UUID sesion=UUID.randomUUID();String token=TokenSesionInterna.nuevo();
        j.update("""
                INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                VALUES (?,?,?,now()-interval '1 minute',now()+interval '1 hour')
                """,sesion,base+numero,TokenSesionInterna.huella(token).orElseThrow());
        String clave=numero==5?"VENDEDOR_2":rol;
        sesiones.put(clave,sesion);tokens.put(clave,token);
    }
    public UUID vendedor() {return sesiones.get("VENDEDOR");}
    public CargarPedidoService.DatosPedido datos() {
        return new CargarPedidoService.DatosPedido(base,hoy.plusDays(2),List.of(
                new CargarPedidoService.ItemPedido(base,new BigDecimal("2.000")),
                new CargarPedidoService.ItemPedido(base+1,new BigDecimal("1.500"))),
                new CargarPedidoService.DireccionEntrega("Calle UC03","123",null,null,"Posadas","Misiones",null,null),UUID.randomUUID());
    }
    public Map<String,Object> body() {
        return new HashMap<>(Map.of("idCliente",base,"fechaReparto",hoy.plusDays(2).toString(),
                "items",List.of(Map.of("idProducto",base,"cantidadSolicitada",2),Map.of("idProducto",base+1,"cantidadSolicitada",1.5)),
                "direccionEntrega",Map.of("calle","Calle UC03","numero","123","localidad","Posadas","provincia","Misiones"),
                "claveIdempotencia",UUID.randomUUID().toString()));
    }
}
