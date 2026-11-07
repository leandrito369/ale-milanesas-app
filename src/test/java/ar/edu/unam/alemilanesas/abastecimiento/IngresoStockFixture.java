package ar.edu.unam.alemilanesas.abastecimiento;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Datos sintéticos compartidos por los tests B3 y HTTP UC08, con FKs reales. */
public final class IngresoStockFixture {
    private static final AtomicLong IDS = new AtomicLong(8080000);
    public final long base = IDS.addAndGet(100);
    public final Map<String,UUID> sesiones = new HashMap<>();
    public final Map<String,String> tokens = new HashMap<>();
    public IngresoStockFixture(JdbcTemplate j, TransactionTemplate tx) {
        tx.executeWithoutResult(status -> {
            j.update("INSERT INTO \"UNIDAD_MEDIDA\" VALUES ('KG','Kilogramo') ON CONFLICT DO NOTHING");
            String[] roles = {"ADMINISTRADOR","RESPONSABLE_PRODUCCION","VENDEDOR","REPARTIDOR","PROVEEDOR"};
            for (int n=0;n<roles.length;n++) {
                j.update("INSERT INTO \"ROL\" VALUES (?,?) ON CONFLICT DO NOTHING",roles[n],roles[n]);
                j.update("""
                        INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida","estado",
                        "cambioCredencialPendiente","fechaHoraAlta") VALUES (?,?,'HASH_TEST','HABILITADO',false,now())
                        """,base+n,"uc08_"+(base+n));
                j.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,?,now())",base+n,roles[n]);

            }
            j.update("""
                    INSERT INTO "PROVEEDOR" ("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                    VALUES (?,?,'Proveedor UC08','ACTIVO',now())
                    """,base,String.format("%011d",base));
            j.update("INSERT INTO \"USUARIO_PROVEEDOR\" VALUES (?,?,now())",base+4,base);
            // Vincular el proveedor puede revocar sesiones: emitirlas después de completar la identidad.
            for (int n=0;n<roles.length;n++) {
                String token=TokenSesionInterna.nuevo(); UUID sesion=UUID.randomUUID();
                j.update("""
                        INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                        "fechaHoraInicio","fechaHoraExpiracion") VALUES (?,?,?,now()-interval '1 minute',now()+interval '1 hour')
                        """,sesion,base+n,TokenSesionInterna.huella(token).orElseThrow());
                sesiones.put(roles[n],sesion); tokens.put(roles[n],token);
            }
            j.update("""
                    INSERT INTO "INSUMO" VALUES (?,?,'KG',10,5,100,false,true),(?,?,'KG',0,5,100,false,true)
                    """,base,"Insumo UC08 "+base,base+1,"Otro UC08 "+base);
            j.update("""
                    INSERT INTO "COSTO_INSUMO_HISTORICO"
                    ("idInsumo","vigenteDesde","costoUnitario","fuenteCosto") VALUES (?,now()-interval '1 day',2,'AJUSTE')
                    """,base);
            j.update("""
                    INSERT INTO "PARTIDA_INSUMO" ("idPartidaInsumo","idInsumo","fechaHoraRegistro",
                    "cantidadInicial","cantidadDisponible") VALUES (?,?,now()-interval '1 day',10,10)
                    """,base,base);
            j.update("INSERT INTO \"PROVEEDOR_INSUMO\" VALUES (?,?,now()-interval '3 days',NULL)",base,base);
            j.update("INSERT INTO \"EPISODIO_FALTANTE\" (\"idEpisodio\",\"idInsumo\",\"fechaHoraInicio\") VALUES (?,?,now()-interval '2 days')",base,base);
            j.update("""
                    INSERT INTO "SOLICITUD_COTIZACION" ("idSolicitud","idEpisodio","idUsuarioEmisor",
                    "cantidadSolicitada","fechaHoraEmision","fechaHoraVencimiento","modalidadInicio","estado")
                    VALUES (?,?,?,20,now()-interval '1 day',now()+interval '1 day','MANUAL','RECEPCION_ABIERTA')
                    """,base,base,base);
            j.update("INSERT INTO \"INVITACION_COTIZACION\" VALUES (?,?)",base,base);
            j.update("""
                    INSERT INTO "RESPUESTA_COTIZACION" ("idRespuesta","idSolicitud","idProveedor",
                    "idUsuarioRegistrador","cantidadOfrecida","precioUnitario","tiempoEntregaEstimado","fechaHoraRespuesta")
                    VALUES (?,?,?,?,20,3,interval '1 day',now())
                    """,base,base,base,base);
            j.update("UPDATE \"SOLICITUD_COTIZACION\" SET \"estado\"='RESUELTA_CON_COMPRA' WHERE \"idSolicitud\"=?",base);
            j.update("""
                    INSERT INTO "POLITICA_COMPRA" ("idPoliticaCompra","numeroVersion","vigenteDesde",
                    "vigenteHasta","fechaHoraRegistro","idUsuarioRegistro") VALUES (?,?,?, ?,now(),?)
                    """,base,(int)base, java.time.OffsetDateTime.parse("2020-01-01T00:00:00Z").plusSeconds(base*2),
                    java.time.OffsetDateTime.parse("2020-01-01T00:00:00Z").plusSeconds(base*2+1),base);
            j.update("""
                    INSERT INTO "DECISION_COMPRA" ("idDecisionCompra","idSolicitud","idPoliticaCompra",
                    "idUsuarioAdministrador","idRespuestaSeleccionada","fechaHoraDecision","resultado","justificacion")
                    VALUES (?,?,?,?,?,now(),'COMPRA','Aprobacion sintetica')
                    """,base,base,base,base,base);
            j.update("""
                    INSERT INTO "ORDEN_COMPRA" ("idOrdenCompra","idDecisionCompra","cantidadOrdenada",
                    "precioUnitarioAcordado","fechaHoraEmision","estado") VALUES (?,?,20,3,now(),'PENDIENTE')
                    """,base,base);
        });
    }
    public UUID produccion() { return sesiones.get("RESPONSABLE_PRODUCCION"); }
    public RegistrarIngresoStockInsumoService.DatosIngreso datos(String cantidad) {
        return new RegistrarIngresoStockInsumoService.DatosIngreso(base,base,new BigDecimal(cantidad),"KG",new BigDecimal("3.5000"),"FACT-UC08");
    }
    public Map<String,Object> body() {
        return Map.of("idInsumo",base,"idOrdenCompra",base,"cantidadRecibida",5,
                "codigoUnidadMedida","KG","costoUnitarioCompra",3.5,"comprobante","FACT-UC08");
    }
}
