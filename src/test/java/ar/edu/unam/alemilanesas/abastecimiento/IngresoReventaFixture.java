package ar.edu.unam.alemilanesas.abastecimiento;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Recepción REVENTA y actores sintéticos; no crea órdenes, partidas ni costos de insumos. */
public final class IngresoReventaFixture {
    private static final AtomicLong IDS=new AtomicLong(8090000);
    public final long base=IDS.addAndGet(100);
    public final Map<String,UUID> sesiones=new HashMap<>();
    public final Map<String,String> tokens=new HashMap<>();
    public IngresoReventaFixture(JdbcTemplate j,TransactionTemplate tx) {
        tx.executeWithoutResult(status -> {
            j.update("INSERT INTO \"UNIDAD_MEDIDA\" VALUES ('UN','Unidad') ON CONFLICT DO NOTHING");
            String[] roles={"ADMINISTRADOR","RESPONSABLE_PRODUCCION","VENDEDOR","REPARTIDOR","PROVEEDOR"};
            for(int n=0;n<roles.length;n++) {
                j.update("INSERT INTO \"ROL\" VALUES (?,?) ON CONFLICT DO NOTHING",roles[n],roles[n]);
                j.update("""
                        INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado",
                        "cambioCredencialPendiente","fechaHoraAlta") VALUES (?,?,'HASH_TEST','HABILITADO',false,now())
                        """,base+n,"uc08_reventa_"+(base+n));
                j.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,?,now())",base+n,roles[n]);
            }
            j.update("""
                    INSERT INTO "PROVEEDOR" ("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                    VALUES (?,?,'Proveedor REVENTA','ACTIVO',now())
                    """,base,String.format("%011d",base));
            j.update("INSERT INTO \"USUARIO_PROVEEDOR\" VALUES (?,?,now())",base+4,base);
            for(int n=0;n<roles.length;n++) {
                UUID sesion=UUID.randomUUID();String token=TokenSesionInterna.nuevo();
                j.update("""
                        INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                        "fechaHoraInicio","fechaHoraExpiracion") VALUES (?,?,?,now()-interval '1 minute',now()+interval '1 hour')
                        """,sesion,base+n,TokenSesionInterna.huella(token).orElseThrow());
                sesiones.put(roles[n],sesion);tokens.put(roles[n],token);
            }
            j.update("""
                    INSERT INTO "PRODUCTO" ("idProducto","nombre","nombreNormalizado","tipoProducto",
                    "codigoUnidadVenta","activo","stockActualReventa","costoPromedioActualReventa")
                    VALUES (?,?,?,'REVENTA','UN',true,10,100),(?,?,?,'ELABORADO','UN',true,NULL,NULL)
                    """,base,"Reventa "+base,"reventa "+base,base+1,"Elaborado "+base,"elaborado "+base);
        });
    }
    public UUID produccion() {return sesiones.get("RESPONSABLE_PRODUCCION");}
    public RegistrarIngresoStockReventaService.DatosIngresoReventa datos(String cantidad,String costo) {
        return new RegistrarIngresoStockReventaService.DatosIngresoReventa(base,base,new BigDecimal(cantidad),"UN",new BigDecimal(costo),"FACT-R-UC08");
    }
    public Map<String,Object> body() {
        return Map.of("idProducto",base,"idProveedorDirecto",base,"cantidadRecibida",10,
                "codigoUnidadMedida","UN","costoUnitarioCompra",200,"comprobante","FACT-R-UC08");
    }
}
