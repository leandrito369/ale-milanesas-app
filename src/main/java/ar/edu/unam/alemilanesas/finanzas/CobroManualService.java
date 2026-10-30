package ar.edu.unam.alemilanesas.finanzas;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC29 / BC06-01..06: Cobro MANUAL general en B3 (no rendicion F04).
 * Origen fisico MR5 ADMINISTRACION corresponde a alta manual general.
 * No exponer UUID de sesion como autenticacion HTTP; B4 pendiente.
 */
@Service
public class CobroManualService {
    private static final Set<String> REGISTRADORES = Set.of("ADMINISTRADOR", "VENDEDOR");
    private static final Set<String> ADMIN = Set.of("ADMINISTRADOR");
    private static final Set<String> MEDIOS = Set.of("EFECTIVO", "TRANSFERENCIA", "OTRO");
    private final NamedParameterJdbcTemplate sql;
    private final JdbcTemplate jdbc;
    private final AutorizacionSesionInterna auth;
    private final EvaluadorCategoriaComercialInterno categoria;

    public CobroManualService(NamedParameterJdbcTemplate sql,
                              AutorizacionSesionInterna auth,
                              EvaluadorCategoriaComercialInterno categoria) {
        this.sql = sql;
        this.jdbc = sql.getJdbcTemplate();
        this.auth = auth;
        this.categoria = categoria;
    }

    public record Resultado(long idCobro, String estado, BigDecimal saldoConfirmado,
                            String categoriaComercial) {}

    /**
     * La clave técnica idempotente proviene del contexto interno de operación;
     * no implica entrada HTTP ni altera firma funcional G0.
     * Esta operación NO incorpora efectivo en tránsito de UC05/F04.
     */
    @Transactional
    public Resultado registrarCobro(UUID sesion, long idCliente, Long idPedido,
                                    BigDecimal importe, String medio,
                                    UUID claveOperacion) {
        auth.exigirRoles(sesion, REGISTRADORES);
        validarAlta(idCliente, idPedido, importe, medio, claveOperacion);
        bloquearIdentificadores();
        auth.exigirRoles(sesion, REGISTRADORES);
        long registrador = idUsuario(sesion);

        // Orden estable: cliente -> historico cuenta, para serializar numeroSecuencia.
        var clientes = sql.queryForList("""
                SELECT "idCliente" FROM "CLIENTE" WHERE "idCliente"=:id FOR UPDATE
                """, Map.of("id", idCliente));
        if (clientes.size() != 1) throw new IllegalArgumentException("Cliente inexistente");

        var anteriores = sql.queryForList("""
                SELECT "idCobro","idCliente","idPedido","importe","medioPago",
                       "origen","estadoCobro"
                  FROM "COBRO" WHERE "claveIdempotenciaOrigen"=:clave
                """, Map.of("clave", claveOperacion));
        if (!anteriores.isEmpty()) {
            var anterior = anteriores.getFirst();
            if (((Number)anterior.get("idCliente")).longValue() != idCliente
                    || !igualId((Number)anterior.get("idPedido"), idPedido)
                    || ((BigDecimal)anterior.get("importe")).compareTo(importe) != 0
                    || !medio.equals(anterior.get("medioPago"))
                    || !"ADMINISTRACION".equals(anterior.get("origen"))
                    || !"CONFIRMADO".equals(anterior.get("estadoCobro"))) {
                throw new IllegalArgumentException("Clave de operacion ya utilizada con otros datos");
            }
            return new Resultado(((Number)anterior.get("idCobro")).longValue(),
                    "CONFIRMADO", saldo(idCliente), categoriaActual(idCliente));
        }
        if (idPedido != null) {
            var pedidos = sql.queryForList("""
                    SELECT "idCliente","estado" FROM "PEDIDO" WHERE "idPedido"=:id FOR SHARE
                    """, Map.of("id", idPedido));
            if (pedidos.size()!=1
                    || ((Number)pedidos.getFirst().get("idCliente")).longValue()!=idCliente
                    || "CANCELADO".equals(pedidos.getFirst().get("estado"))) {
                throw new IllegalArgumentException("Pedido no elegible para Cobro");
            }
        }
        long cobro = siguiente("COBRO","idCobro");
        long cuenta = siguiente("MOVIMIENTO_CUENTA","idMovimientoCuenta");
        long caja = siguiente("MOVIMIENTO_CAJA","idMovimientoCaja");
        long secuencia = siguienteSecuencia(idCliente);
        BigDecimal saldoFinal = saldo(idCliente).subtract(importe);
        sql.update("""
                INSERT INTO "COBRO" ("idCobro","idCliente","idPedido","idUsuarioRegistrador",
                    "fechaHoraCobro","importe","medioPago","origen","claveIdempotenciaOrigen",
                    "estadoCobro")
                VALUES (:id,:cliente,:pedido,:actor,clock_timestamp(),:importe,:medio,
                    'ADMINISTRACION',:clave,'CONFIRMADO')
                """, new MapSqlParameterSource()
                .addValue("id",cobro).addValue("cliente",idCliente)
                .addValue("pedido",idPedido).addValue("actor",registrador)
                .addValue("importe",importe).addValue("medio",medio).addValue("clave",claveOperacion));
        sql.update("""
                INSERT INTO "MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCliente",
                    "numeroSecuenciaCliente","tipoMovimiento","importe",
                    "fechaHoraMovimiento","saldoResultante")
                VALUES (:id,:cliente,:secuencia,'PAGO',:importe,clock_timestamp(),:saldo)
                """, new MapSqlParameterSource().addValue("id",cuenta).addValue("cliente",idCliente)
                .addValue("secuencia",secuencia).addValue("importe",importe)
                .addValue("saldo",saldoFinal));
        sql.update("""
                INSERT INTO "ORIGEN_MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCobro")
                VALUES (:cuenta,:cobro)
                """, Map.of("cuenta",cuenta,"cobro",cobro));
        sql.update("""
                INSERT INTO "MOVIMIENTO_CAJA" ("idMovimientoCaja","tipoMovimiento",
                    "importe","fechaHoraMovimiento","origen","concepto",
                    "idUsuarioRegistro","idCobro")
                VALUES (:id,'INGRESO',:importe,clock_timestamp(),'COBRO',
                    'Cobro manual general',:actor,:cobro)
                """, Map.of("id",caja,"importe",importe,"actor",registrador,"cobro",cobro));
        String categoriaFinal = categoria.reevaluar(idCliente);
        return new Resultado(cobro,"CONFIRMADO",saldoFinal,categoriaFinal);
    }

    /**
     * Segunda operación independiente: solo Administrador. Ejecuta funcion
     * canonica MR5/F01, que aplica contraasientos y transicion atomica.
     */
    @Transactional
    public Resultado anularCobroManual(UUID sesionAdmin, long idCobro, String motivo) {
        auth.exigirRoles(sesionAdmin, ADMIN);
        if (idCobro<=0 || motivo==null || motivo.isBlank()) {
            throw new IllegalArgumentException("Cobro y motivo de anulacion obligatorios");
        }
        bloquearIdentificadores();
        auth.exigirRoles(sesionAdmin, ADMIN);
        long actor = idUsuario(sesionAdmin);
        var rows = sql.queryForList("""
                SELECT "idCliente","estadoCobro","origen","medioPago"
                  FROM "COBRO" WHERE "idCobro"=:id FOR UPDATE
                """, Map.of("id",idCobro));
        if (rows.size()!=1 || !"CONFIRMADO".equals(rows.getFirst().get("estadoCobro"))
                || !"ADMINISTRACION".equals(rows.getFirst().get("origen"))
                || "MERCADOPAGO".equals(rows.getFirst().get("medioPago"))) {
            throw new IllegalArgumentException("Cobro manual confirmado requerido");
        }
        long cliente = ((Number)rows.getFirst().get("idCliente")).longValue();
        long movCuenta = siguiente("MOVIMIENTO_CUENTA","idMovimientoCuenta");
        long movCaja = siguiente("MOVIMIENTO_CAJA","idMovimientoCaja");
        jdbc.queryForList("SELECT \"fn_mr3_anular_cobro_manual\"(?,?,?,?,?)",
                idCobro,actor,movCuenta,movCaja,motivo.strip());
        String categoriaFinal = categoria.reevaluar(cliente);
        return new Resultado(idCobro,"ANULADO",saldo(cliente),categoriaFinal);
    }

    private static boolean igualId(Number original, Long solicitado) {
        return original == null ? solicitado == null
                : solicitado != null && original.longValue()==solicitado;
    }

    private static void validarAlta(long cliente, Long pedido, BigDecimal importe,
                                   String medio, UUID clave) {
        if (cliente <= 0 || (pedido != null && pedido <= 0) || clave == null
                || importe == null || importe.signum() <= 0 || importe.scale() > 2
                || importe.precision() > 18 || medio == null || !MEDIOS.contains(medio)) {
            throw new IllegalArgumentException("Cobro manual invalido");
        }
    }

    private void bloquearIdentificadores() {
        jdbc.execute("SELECT pg_advisory_xact_lock(2929001)");
    }

    private long idUsuario(UUID sesion) {
        Long id = sql.queryForObject("""
                SELECT "idUsuario" FROM "SESION_USUARIO"
                 WHERE "idSesionUsuario"=:sesion AND "fechaHoraRevocacion" IS NULL
                   AND "fechaHoraInicio"<=clock_timestamp()
                   AND "fechaHoraExpiracion">clock_timestamp()
                """, Map.of("sesion",sesion), Long.class);
        if (id==null) throw new SecurityException("Sesion no autorizada");
        return id;
    }

    private long siguiente(String tabla, String columna) {
        // Identificadores de esquema sin secuencias; literales solo locales,
        // no acepta identificadores provistos por el actor ni SQL arbitrario.
        if (!Set.of("COBRO","MOVIMIENTO_CUENTA","MOVIMIENTO_CAJA").contains(tabla)
                || !Set.of("idCobro","idMovimientoCuenta","idMovimientoCaja").contains(columna)) {
            throw new IllegalArgumentException("Identificador no permitido");
        }
        Long siguiente = jdbc.queryForObject(
                "SELECT COALESCE(MAX(\""+columna+"\"),0)+1 FROM \""+tabla+"\"",
                Long.class);
        if (siguiente == null || siguiente <=0) throw new IllegalStateException("Sin ID financiero");
        return siguiente;
    }

    private long siguienteSecuencia(long idCliente) {
        Long n = sql.queryForObject("""
                SELECT COALESCE(MAX("numeroSecuenciaCliente"),0)+1
                  FROM "MOVIMIENTO_CUENTA" WHERE "idCliente"=:cliente
                """,Map.of("cliente",idCliente),Long.class);
        if(n == null || n<=0) throw new IllegalStateException("Sin secuencia Cuenta");
        return n;
    }

    private BigDecimal saldo(long idCliente) {
        var rows = sql.queryForList("""
                SELECT "saldoResultante" FROM "MOVIMIENTO_CUENTA"
                 WHERE "idCliente"=:cliente ORDER BY "numeroSecuenciaCliente" DESC LIMIT 1
                """,Map.of("cliente",idCliente),BigDecimal.class);
        return rows.isEmpty()?BigDecimal.ZERO:rows.getFirst();
    }

    private String categoriaActual(long idCliente) {
        return sql.queryForObject("""
                SELECT "categoriaComercial" FROM "CLIENTE" WHERE "idCliente"=:id
                """,Map.of("id",idCliente),String.class);
    }
}
