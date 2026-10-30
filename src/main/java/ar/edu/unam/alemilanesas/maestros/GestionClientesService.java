package ar.edu.unam.alemilanesas.maestros;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I1-02 B3: UC02 alta, UC32 modificación, UC38 cambio de estado.
 * Invocación INTERNA, sin endpoints ni autenticación remota B4.
 * Un mismo advisory lock ordena IDs y comparación de identidad para evitar
 * carrera entre altas/modificaciones; MR5 conserva F09 de pedidos.
 */
@Service
public class GestionClientesService {
    private static final Set<String> ALTA = Set.of("ADMINISTRADOR","VENDEDOR");
    private static final Set<String> MODIFICACION = Set.of("VENDEDOR");
    private static final Set<String> ADMIN = Set.of("ADMINISTRADOR");
    private static final Set<String> ESTADOS = Set.of("ACTIVO","INACTIVO");
    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna auth;

    public GestionClientesService(NamedParameterJdbcTemplate sql,
                                  AutorizacionSesionInterna auth) {
        this.sql = sql;
        this.auth = auth;
    }

    /**
     * Nombre lógico de Fase 4 descompuesto a nombres + apellidos de MR5.
     * No admite estado/categoría ni expone DTO de Cuenta Corriente.
     */
    public record DatosCliente(String nombres, String apellidos, String telefonoPrincipal,
             String calle, String numeroPuerta, String piso, String unidadFuncional,
             String localidad, String provincia, String referenciaDomicilio,
             String zona, Short diaHabitualReparto) {}
    public record ResultadoCliente(long idCliente, String estado, String categoriaComercial,
                                   boolean huboCambio, List<Long> advertenciasParciales) {}
    public record EstadoCliente(long idCliente, String estado, boolean huboCambio,
                                Integer numeroCambio) {}
    private record Normalizados(String nombre, String telefono) {}

    @Transactional
    public ResultadoCliente registrarCliente(UUID sesion, DatosCliente datos) {
        auth.exigirRoles(sesion, ALTA);
        DatosCliente d = validar(datos);
        bloquear();
        auth.exigirRoles(sesion, ALTA);
        Normalizados n = normalizados(d);
        asegurarSinDuplicado(n, null);
        List<Long> advertencias = coincidenciasParciales(n, null);
        Long siguiente = sql.getJdbcTemplate().queryForObject(
                "SELECT COALESCE(MAX(\"idCliente\"),0)+1 FROM \"CLIENTE\"", Long.class);
        if (siguiente == null || siguiente <= 0) {
            throw new IllegalStateException("No es posible generar idCliente");
        }
        var args = parametros(d).addValue("id", siguiente);
        sql.update("""
            INSERT INTO "CLIENTE" ("idCliente","nombres","apellidos",
                "telefonoPrincipal","calle","numeroPuerta","piso","unidadFuncional",
                "localidad","provincia","referenciaDomicilio","zona","diaHabitualReparto",
                "estado","categoriaComercial","fechaHoraAlta")
            VALUES (:id,:nombres,:apellidos,:telefono,:calle,:numero,:piso,:unidad,
                :localidad,:provincia,:referencia,:zona,:dia,'ACTIVO','CUMPLIDOR',
                clock_timestamp())
            """,args);
        return new ResultadoCliente(siguiente,"ACTIVO","CUMPLIDOR",true,advertencias);
    }

    /** UC32 exclusivamente VENDEDOR; actualizar sólo identidad/domicilio. */
    @Transactional
    public ResultadoCliente modificarCliente(UUID sesion, long idCliente, DatosCliente datos) {
        auth.exigirRoles(sesion, MODIFICACION);
        if (idCliente <= 0) throw new IllegalArgumentException("idCliente inválido");
        DatosCliente d = validar(datos);
        bloquear();
        auth.exigirRoles(sesion, MODIFICACION);
        var previos = sql.queryForList("""
            SELECT * FROM "CLIENTE" WHERE "idCliente"=:id FOR UPDATE
            """, Map.of("id",idCliente));
        if (previos.size() != 1) throw new IllegalArgumentException("Cliente inexistente");
        Map<String,Object> anterior = previos.getFirst();
        Normalizados n = normalizados(d);
        asegurarSinDuplicado(n, idCliente);
        List<Long> advertencias = coincidenciasParciales(n,idCliente);

        boolean distinto =
               !Objects.equals(anterior.get("nombres"),d.nombres())
            || !Objects.equals(anterior.get("apellidos"),d.apellidos())
            || !Objects.equals(anterior.get("telefonoPrincipal"),d.telefonoPrincipal())
            || !Objects.equals(anterior.get("calle"),d.calle())
            || !Objects.equals(anterior.get("numeroPuerta"),d.numeroPuerta())
            || !Objects.equals(anterior.get("piso"),d.piso())
            || !Objects.equals(anterior.get("unidadFuncional"),d.unidadFuncional())
            || !Objects.equals(anterior.get("localidad"),d.localidad())
            || !Objects.equals(anterior.get("provincia"),d.provincia())
            || !Objects.equals(anterior.get("referenciaDomicilio"),d.referenciaDomicilio())
            || !Objects.equals(anterior.get("zona"),d.zona())
            || !Objects.equals(diaEnShort(anterior.get("diaHabitualReparto")),d.diaHabitualReparto());
        if (!distinto) return new ResultadoCliente(idCliente,(String)anterior.get("estado"),
                (String)anterior.get("categoriaComercial"),false,advertencias);

        sql.update("""
            UPDATE "CLIENTE"
               SET "nombres"=:nombres,"apellidos"=:apellidos,
                   "telefonoPrincipal"=:telefono,"calle"=:calle,"numeroPuerta"=:numero,
                   "piso"=:piso,"unidadFuncional"=:unidad,"localidad"=:localidad,
                   "provincia"=:provincia,"referenciaDomicilio"=:referencia,
                   "zona"=:zona,"diaHabitualReparto"=:dia
             WHERE "idCliente"=:id
            """,parametros(d).addValue("id",idCliente));
        if (!Objects.equals(anterior.get("telefonoPrincipal"), d.telefonoPrincipal())) {
            invalidarOtpPendientes(idCliente);
        }
        return new ResultadoCliente(idCliente,(String)anterior.get("estado"),
                (String)anterior.get("categoriaComercial"),true,advertencias);
    }

    /** UC38 ADMINISTRADOR: no se cancelan Pedidos existentes ni se borra Cuenta. */
    @Transactional
    public EstadoCliente cambiarEstadoCliente(UUID sesion, long idCliente,
                                               String nuevoEstado, String motivo) {
        auth.exigirRoles(sesion, ADMIN);
        if (idCliente <= 0 || nuevoEstado == null || !ESTADOS.contains(nuevoEstado)) {
            throw new IllegalArgumentException("Cliente o estado inválido");
        }
        if (motivo != null && motivo.length() > 2000) {
            throw new IllegalArgumentException("Motivo demasiado extenso");
        }
        bloquear();
        auth.exigirRoles(sesion, ADMIN);
        var filas=sql.queryForList("""
            SELECT * FROM "CLIENTE" WHERE "idCliente"=:id FOR UPDATE
            """,Map.of("id",idCliente));
        if(filas.size()!=1)throw new IllegalArgumentException("Cliente inexistente");
        var previo=filas.getFirst();
        String estadoPrevio=(String)previo.get("estado");
        if(estadoPrevio.equals(nuevoEstado)) {
            return new EstadoCliente(idCliente,estadoPrevio,false,null);
        }
        if("ACTIVO".equals(nuevoEstado)) {
            // Validación de reactivación del contrato UC38 A3.
            validar(new DatosCliente((String)previo.get("nombres"),
                    (String)previo.get("apellidos"),(String)previo.get("telefonoPrincipal"),
                    (String)previo.get("calle"),(String)previo.get("numeroPuerta"),
                    (String)previo.get("piso"),(String)previo.get("unidadFuncional"),
                    (String)previo.get("localidad"),(String)previo.get("provincia"),
                    (String)previo.get("referenciaDomicilio"),(String)previo.get("zona"),
                    diaEnShort(previo.get("diaHabitualReparto"))));
        }
        Long admin=sql.queryForObject("""
            SELECT "idUsuario" FROM "SESION_USUARIO"
             WHERE "idSesionUsuario"=:sesion
            """,Map.of("sesion",sesion),Long.class);
        if(admin==null)throw new SecurityException("Sesión inválida");
        Integer siguiente=sql.queryForObject("""
            SELECT COALESCE(MAX("numeroCambio"),0)+1
              FROM "HISTORIAL_ESTADO_CLIENTE" WHERE "idCliente"=:id
            """,Map.of("id",idCliente),Integer.class);
        if(siguiente==null || siguiente<=0)throw new IllegalStateException("Historial sin secuencia");
        sql.update("""
            UPDATE "CLIENTE" SET "estado"=:estado WHERE "idCliente"=:id
            """,Map.of("id",idCliente,"estado",nuevoEstado));
        var args=new MapSqlParameterSource()
                .addValue("id",idCliente).addValue("seq",siguiente)
                .addValue("previo",estadoPrevio).addValue("nuevo",nuevoEstado)
                .addValue("motivo",motivo==null?null:motivo.strip())
                .addValue("admin",admin);
        sql.update("""
            INSERT INTO "HISTORIAL_ESTADO_CLIENTE"
                ("idCliente","numeroCambio","estadoAnterior","estadoNuevo",
                 "fechaHoraCambio","motivo","idUsuarioAdministrador")
            VALUES (:id,:seq,:previo,:nuevo,clock_timestamp(),:motivo,:admin)
            """,args);
        if("INACTIVO".equals(nuevoEstado)) {
            invalidarOtpPendientes(idCliente);
            sql.update("""
                UPDATE "SESION_CLIENTE_ONLINE" s
                   SET "fechaHoraRevocacion"=clock_timestamp()
                 WHERE s."fechaHoraRevocacion" IS NULL
                   AND EXISTS (SELECT 1 FROM "DESAFIO_AUTENTICACION_CLIENTE" d
                               WHERE d."idDesafio"=s."idDesafio"
                                 AND d."idCliente"=:id)
                """, Map.of("id",idCliente));
        }
        return new EstadoCliente(idCliente,nuevoEstado,true,siguiente);
    }

    /** PostgreSQL smallint puede materializarse como Integer en Map JDBC. */
    private static Short diaEnShort(Object valor) {
        return valor == null ? null : ((Number)valor).shortValue();
    }

    private void invalidarOtpPendientes(long cliente) {
        // No revocar sesiones ONLINE ya creadas por UC21 cuando sólo cambia teléfono.
        sql.update("""
            UPDATE "DESAFIO_AUTENTICACION_CLIENTE"
               SET "fechaHoraBloqueo"=clock_timestamp()
             WHERE "idCliente"=:id AND "fechaHoraValidacion" IS NULL
               AND "fechaHoraBloqueo" IS NULL
               AND "fechaHoraExpiracion">clock_timestamp()
            """,Map.of("id",cliente));
    }

    private void bloquear() {
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(3202001)");
    }

    private void asegurarSinDuplicado(Normalizados n, Long excluirId) {
        var args=new MapSqlParameterSource().addValue("nombre",n.nombre())
            .addValue("tel",n.telefono()).addValue("excluir",excluirId==null?0L:excluirId);
        List<Long> encontrados=sql.queryForList("""
            SELECT "idCliente" FROM "CLIENTE"
             WHERE (:excluir=0 OR "idCliente"<>:excluir)
               AND regexp_replace(lower(btrim(concat_ws(' ',"nombres","apellidos"))),
                   '[[:space:]]+', ' ', 'g')=:nombre
               AND regexp_replace("telefonoPrincipal",'[^0-9]','','g')=:tel
            """,args,Long.class);
        if(!encontrados.isEmpty()) {
            throw new IllegalArgumentException(
                "Cliente ya registrado con nombre y teléfono exactos (activo o inactivo)");
        }
    }
    private List<Long> coincidenciasParciales(Normalizados n, Long excluirId) {
        return List.copyOf(sql.queryForList("""
            SELECT "idCliente" FROM "CLIENTE"
             WHERE (:excluir=0 OR "idCliente"<>:excluir)
               AND (regexp_replace(lower(btrim(concat_ws(' ',"nombres","apellidos"))),
                   '[[:space:]]+', ' ', 'g')=:nombre
               OR regexp_replace("telefonoPrincipal",'[^0-9]','','g')=:tel)
             ORDER BY "idCliente"
            """,new MapSqlParameterSource().addValue("excluir",excluirId==null?0L:excluirId)
                .addValue("nombre",n.nombre()).addValue("tel",n.telefono()),Long.class));
    }
    private static Normalizados normalizados(DatosCliente d) {
        return new Normalizados(
                (d.nombres()+" "+Objects.toString(d.apellidos(),"")).strip()
                    .replaceAll("\\s+"," ").toLowerCase(Locale.ROOT),
                d.telefonoPrincipal().replaceAll("[^0-9]",""));
    }
    private static MapSqlParameterSource parametros(DatosCliente d) {
        return new MapSqlParameterSource()
            .addValue("nombres",d.nombres()).addValue("apellidos",d.apellidos())
            .addValue("telefono",d.telefonoPrincipal()).addValue("calle",d.calle())
            .addValue("numero",d.numeroPuerta()).addValue("piso",d.piso())
            .addValue("unidad",d.unidadFuncional()).addValue("localidad",d.localidad())
            .addValue("provincia",d.provincia()).addValue("referencia",d.referenciaDomicilio())
            .addValue("zona",d.zona()).addValue("dia",d.diaHabitualReparto());
    }
    private static DatosCliente validar(DatosCliente d) {
        if(d==null)throw new IllegalArgumentException("Datos de Cliente requeridos");
        String nombres=obligatorio(d.nombres(),120,"nombres");
        String telefono=obligatorio(d.telefonoPrincipal(),20,"teléfono");
        if(!telefono.matches("[+0-9 ()-]+")
                || telefono.replaceAll("[^0-9]","").length()<6) {
            throw new IllegalArgumentException("Teléfono inválido");
        }
        String calle=obligatorio(d.calle(),150,"calle");
        String localidad=obligatorio(d.localidad(),100,"localidad");
        Short dia=d.diaHabitualReparto();
        if(dia!=null && (dia<1 || dia>7)) {
            throw new IllegalArgumentException("Día habitual fuera de rango");
        }
        return new DatosCliente(nombres,opcional(d.apellidos(),120),
                telefono,calle,opcional(d.numeroPuerta(),20),opcional(d.piso(),10),
                opcional(d.unidadFuncional(),20),localidad,
                opcional(d.provincia(),100),opcional(d.referenciaDomicilio(),250),
                opcional(d.zona(),100),dia);
    }
    private static String obligatorio(String s,int max,String campo) {
        if(s==null || s.isBlank() || s.strip().length()>max || s.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Campo obligatorio inválido: "+campo);
        return s.strip().replaceAll("\\s+"," ");
    }
    private static String opcional(String s,int max) {
        if(s==null || s.isBlank())return null;
        if(s.strip().length()>max || s.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Dato opcional inválido");
        return s.strip().replaceAll("\\s+"," ");
    }
}
