package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC43 online: asignación e historial en una transacción compatible con UC18/UC55. */
@Service
public class AsignarRepartoService {
    private static final Set<String> VENDEDORES=Set.of("VENDEDOR");
    private final JdbcTemplate jdbc;
    private final AutorizacionSesionInterna autorizacion;
    public AsignarRepartoService(JdbcTemplate jdbc,AutorizacionSesionInterna autorizacion) {
        this.jdbc=jdbc;this.autorizacion=autorizacion;
    }
    public record DatosAsignacion(long idPedido,long idUsuarioRepartidor,String motivoReasignacion) {}
    public record AsignacionRegistrada(long idPedido,int numeroAsignacion,long idUsuarioRepartidor,
            OffsetDateTime fechaHoraInicio,boolean fueReasignacion) {}
    private record Vigente(int numero,long repartidor) {}

    @Transactional
    public AsignacionRegistrada asignarOReasignar(UUID sesion,DatosAsignacion datos) {
        autorizacion.exigirRoles(sesion,VENDEDORES);
        validar(datos);
        jdbc.execute("SELECT pg_advisory_xact_lock(9172001)");
        jdbc.execute("SELECT pg_advisory_xact_lock(60505,7)");
        autorizacion.exigirRoles(sesion,VENDEDORES);
        exigirRepartidor(datos.idUsuarioRepartidor());
        exigirPedido(datos.idPedido());
        var filas=jdbc.query("""
                SELECT "numeroAsignacion","idUsuarioRepartidor" FROM "ASIGNACION_REPARTO"
                WHERE "idPedido"=? AND "fechaHoraFin" IS NULL FOR UPDATE
                """,(rs,n)->new Vigente(rs.getInt(1),rs.getLong(2)),datos.idPedido());
        Vigente previa=filas.isEmpty()?null:filas.getFirst();
        String motivo=motivo(datos,previa);
        OffsetDateTime ahora=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);
        if (previa!=null) cerrar(datos.idPedido(),previa.numero(),ahora);
        int siguiente=jdbc.queryForObject("""
                SELECT COALESCE(MAX("numeroAsignacion"),0)+1 FROM "ASIGNACION_REPARTO" WHERE "idPedido"=?
                """,Integer.class,datos.idPedido());
        long vendedor=jdbc.queryForObject("SELECT \"idUsuario\" FROM \"SESION_USUARIO\" WHERE \"idSesionUsuario\"=?",Long.class,sesion);
        jdbc.update("""
                INSERT INTO "ASIGNACION_REPARTO" ("idPedido","numeroAsignacion","idUsuarioRepartidor",
                    "idUsuarioVendedor","fechaHoraInicio","fechaHoraFin","motivoReasignacion")
                VALUES (?,?,?,?,?,NULL,?)
                """,datos.idPedido(),siguiente,datos.idUsuarioRepartidor(),vendedor,ahora,motivo);
        return new AsignacionRegistrada(datos.idPedido(),siguiente,datos.idUsuarioRepartidor(),ahora,previa!=null);
    }
    private static void validar(DatosAsignacion datos) {
        if (datos==null || datos.idPedido()<=0 || datos.idUsuarioRepartidor()<=0)
            throw new IllegalArgumentException("Solicitud invalida");
        String motivo=datos.motivoReasignacion();
        if (motivo!=null && (motivo.length()>250 || motivo.indexOf('\0')>=0))
            throw new IllegalArgumentException("Solicitud invalida");
    }
    private void exigirRepartidor(long id) {
        var usuarios=jdbc.queryForList("""
                SELECT u."estado",EXISTS(SELECT 1 FROM "USUARIO_ROL" r
                    WHERE r."idUsuario"=u."idUsuario" AND r."codigoRol"='REPARTIDOR') AS rol
                FROM "USUARIO" u WHERE u."idUsuario"=? FOR SHARE
                """,id);
        if (usuarios.isEmpty()) throw new IllegalArgumentException("Repartidor inexistente");
        var usuario=usuarios.getFirst();
        if (!"HABILITADO".equals(usuario.get("estado"))) throw new IllegalArgumentException("Repartidor no habilitado");
        if (!Boolean.TRUE.equals(usuario.get("rol"))) throw new IllegalArgumentException("Usuario sin rol REPARTIDOR");
    }
    private void exigirPedido(long id) {
        var estados=jdbc.queryForList("SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=? FOR UPDATE",String.class,id);
        if (estados.isEmpty()) throw new IllegalArgumentException("Pedido inexistente");
        if (!"PENDIENTE".equals(estados.getFirst())) throw new IllegalArgumentException("Pedido no está PENDIENTE");
    }
    private static String motivo(DatosAsignacion datos,Vigente previa) {
        if (previa==null) {
            if (datos.motivoReasignacion()!=null)
                throw new IllegalArgumentException("Motivo de reasignación no aplica en primera asignación");
            return null;
        }
        if (previa.repartidor()==datos.idUsuarioRepartidor())
            throw new IllegalArgumentException("Sin cambio: mismo repartidor ya asignado");
        if (datos.motivoReasignacion()==null || datos.motivoReasignacion().isBlank())
            throw new IllegalArgumentException("Motivo de reasignación requerido");
        return datos.motivoReasignacion().trim();
    }
    private void cerrar(long pedido,int numero,OffsetDateTime ahora) {
        int afectadas=jdbc.update("""
                UPDATE "ASIGNACION_REPARTO" SET "fechaHoraFin"=?
                WHERE "idPedido"=? AND "numeroAsignacion"=? AND "fechaHoraFin" IS NULL
                """,ahora,pedido,numero);
        if (afectadas!=1) throw new IllegalStateException("La asignacion cambio durante la operacion");
    }
}
