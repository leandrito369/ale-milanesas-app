package ar.edu.unam.alemilanesas.acceso;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-17 crearUsuario incluye las asignaciones iniciales UC-18.
 * Contratos REV02 + RD-UC17-001/RD-UC18-001 (29/10/2026).
 *
 * Solo B3 interno: UUID de sesión NO es un token de API, pendiente B4.
 * El sistema revalida autorización del Administrador en PostgreSQL.
 * Sin migraciones, secuencias ni columnas nuevas.
 */
@Service
public class CrearUsuarioService {
    private static final String PROVEEDOR = "PROVEEDOR";
    private static final Set<String> ADMIN = Set.of("ADMINISTRADOR");

    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    private final PasswordEncoder encoder;

    public CrearUsuarioService(NamedParameterJdbcTemplate sql,
                               AutorizacionSesionInterna autorizacion,
                               PasswordEncoder encoder) {
        this.sql = sql;
        this.autorizacion = autorizacion;
        this.encoder = encoder;
    }

    @Transactional
    public UsuarioCreado crearUsuario(UUID sesionAdministrador,
                                     String nombreUsuario,
                                     String credencialInicial,
                                     Set<String> rolesIniciales,
                                     Long idProveedor) {
        autorizacion.exigirRoles(sesionAdministrador, ADMIN);
        String nombre = validarNombre(nombreUsuario);
        validarCredencial(credencialInicial);

        if (rolesIniciales == null || rolesIniciales.isEmpty() || rolesIniciales.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Debe asignarse al menos un Rol válido");
        }
        Set<String> roles = Set.copyOf(rolesIniciales);
        boolean proveedor = roles.contains(PROVEEDOR);
        if (proveedor && (roles.size() != 1 || idProveedor == null || idProveedor <= 0)) {
            throw new IllegalArgumentException("PROVEEDOR debe ser exclusivo y tener vínculo comercial");
        }
        if (!proveedor && idProveedor != null) {
            throw new IllegalArgumentException("La vinculación comercial corresponde solo a PROVEEDOR");
        }

        // MR5 no contiene generadores automáticos para estas PK. Un bloqueo
        // transaccional acordado entre los comandos I1 evita asignaciones
        // concurrentes dentro de la aplicación; no autoriza escrituras manuales
        // fuera de esta puerta.
        sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(9172001)");

        Integer cantidadRoles = sql.queryForObject("""
                SELECT count(*) FROM "ROL" WHERE "codigoRol" IN (:roles)
                """, new MapSqlParameterSource("roles", roles), Integer.class);
        if (cantidadRoles == null || cantidadRoles != roles.size()) {
            throw new IllegalArgumentException("Uno o más Roles no existen en el catálogo");
        }

        Integer nombresCoincidentes = sql.queryForObject("""
                SELECT count(*) FROM "USUARIO"
                 WHERE lower(btrim("nombreUsuario")) = lower(:nombre)
                """, Map.of("nombre", nombre), Integer.class);
        if (nombresCoincidentes == null || nombresCoincidentes != 0) {
            throw new IllegalArgumentException("Nombre de Usuario duplicado");
        }

        if (proveedor) {
            Integer elegibles = sql.queryForObject("""
                    SELECT count(*) FROM "PROVEEDOR"
                     WHERE "idProveedor" = :proveedor AND "estado"='ACTIVO'
                    """, Map.of("proveedor", idProveedor), Integer.class);
            Integer habilitados = sql.queryForObject("""
                    SELECT count(*) FROM "USUARIO_PROVEEDOR" up
                      JOIN "USUARIO" u ON u."idUsuario"=up."idUsuario"
                     WHERE up."idProveedor"=:proveedor AND u."estado"='HABILITADO'
                    """, Map.of("proveedor", idProveedor), Integer.class);
            if (elegibles == null || elegibles != 1 || habilitados == null || habilitados != 0) {
                throw new IllegalArgumentException("Proveedor no elegible o con cuenta habilitada");
            }
        }

        Long administrador = sql.queryForObject("""
                SELECT "idUsuario" FROM "SESION_USUARIO"
                 WHERE "idSesionUsuario"=:sesion
                """, Map.of("sesion", sesionAdministrador), Long.class);
        if (administrador == null) {
            throw new SecurityException("Operación no autorizada");
        }

        Long nuevoId = sql.getJdbcTemplate().queryForObject(
                "SELECT COALESCE(MAX(\"idUsuario\"), 0) + 1 FROM \"USUARIO\"", Long.class);
        if (nuevoId == null || nuevoId <= 0) {
            throw new IllegalStateException("No es posible asignar identificador de Usuario");
        }
        String hash = encoder.encode(credencialInicial);
        sql.update("""
                INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                    "estado","cambioCredencialPendiente","fechaHoraAlta","fechaHoraDeshabilitacion")
                VALUES (:id,:nombre,:hash,'HABILITADO',:pendiente,clock_timestamp(),NULL)
                """, new MapSqlParameterSource()
                .addValue("id",nuevoId).addValue("nombre",nombre)
                .addValue("hash",hash).addValue("pendiente",proveedor));

        // UC-18 incluida en la misma transacción: asociación y evento por Rol.
        // Cada fila de EVENTO_ASIGNACION_ROL utiliza el literal físico MR5.
        for (String rol : new java.util.TreeSet<>(roles)) {
            sql.update("""
                    INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
                    VALUES (:id,:rol,clock_timestamp())
                    """, Map.of("id",nuevoId,"rol",rol));
            Long evento = sql.getJdbcTemplate().queryForObject(
                    "SELECT COALESCE(MAX(\"idEventoRol\"),0)+1 FROM \"EVENTO_ASIGNACION_ROL\"",
                    Long.class);
            if (evento == null || evento <= 0) {
                throw new IllegalStateException("No se pudo asignar ID de evento");
            }
            sql.update("""
                    INSERT INTO "EVENTO_ASIGNACION_ROL"
                        ("idEventoRol","idUsuario","codigoRol","tipoEvento",
                         "fechaHoraEvento","idUsuarioAdministrador")
                    VALUES (:evento,:id,:rol,'ASIGNACION',clock_timestamp(),:admin)
                    """, new MapSqlParameterSource()
                    .addValue("evento",evento).addValue("id",nuevoId)
                    .addValue("rol",rol).addValue("admin",administrador));
        }
        if (proveedor) {
            sql.update("""
                    INSERT INTO "USUARIO_PROVEEDOR"
                      ("idUsuario","idProveedor","fechaHoraVinculacion")
                    VALUES (:usuario,:proveedor,clock_timestamp())
                    """, Map.of("usuario",nuevoId,"proveedor",idProveedor));
        }

        return new UsuarioCreado(nuevoId, nombre, "HABILITADO", proveedor, roles);
    }

    private static String validarNombre(String nombre) {
        if (nombre == null) {
            throw new IllegalArgumentException("Nombre requerido");
        }
        String normalizado = nombre.trim();
        if (normalizado.isEmpty() || normalizado.length() > 80 || normalizado.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Nombre de Usuario inválido");
        }
        return normalizado;
    }

    private static void validarCredencial(String credencial) {
        if (credencial == null || credencial.isBlank()
                || credencial.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Credencial inicial inválida");
        }
    }
}
