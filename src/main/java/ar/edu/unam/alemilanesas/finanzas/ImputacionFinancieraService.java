package ar.edu.unam.alemilanesas.finanzas;

import ar.edu.unam.alemilanesas.acceso.AutorizacionSesionInterna;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * IMP-B / IMP-A / IMP-C y evaluacion PE-1 de cobertura de Entregas.
 * Opera exclusivamente con evidencia explicita en EVENTO_IMPUTACION_FINANCIERA
 * y VW_IMP_A_DISPONIBILIDAD_COBRO; nunca interpreta COBRO.idPedido como reserva.
 */
@Service
public class ImputacionFinancieraService {
    public static final String MODO_IMP_B = "IMP_B";
    public static final String MODO_IMP_C = "IMP_C";
    private static final String INVALIDA = "SOLICITUD_INVALIDA";
    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final NamedParameterJdbcTemplate sql;
    private final AutorizacionSesionInterna autorizacion;
    private final ProtocoloIdentificadoresFinancieros identificadores;

    public ImputacionFinancieraService(
            NamedParameterJdbcTemplate sql,
            AutorizacionSesionInterna autorizacion,
            ProtocoloIdentificadoresFinancieros identificadores) {
        this.sql = sql;
        this.autorizacion = autorizacion;
        this.identificadores = identificadores;
    }

    public record EventoImpRegistrado(
            long idEventoImputacion,
            long idCobro,
            long idPedido,
            String tipoEvento,
            BigDecimal importe) {}

    public record CoberturaEntrega(
            BigDecimal anticipoExplicitoPe,
            BigDecimal fifoVinculanteFe,
            BigDecimal coberturaTotalKe,
            BigDecimal exigibleEe,
            BigDecimal reconocimientoInicialV0,
            String modoEvidencia,
            String motivoImpC,
            List<EventoImpRegistrado> eventos) {}

    @Transactional
    public long registrarReservaAnticipo(
            UUID sesion,
            long idCobro,
            long idPedido,
            BigDecimal importe,
            String observacion) {
        autorizacion.exigirRoles(sesion, Set.of("ADMINISTRADOR", "VENDEDOR"));
        if (idCobro <= 0 || idPedido <= 0 || importe == null
                || importe.signum() <= 0 || importe.scale() > 2 || importe.precision() - importe.scale() > 16) {
            throw new IllegalArgumentException(INVALIDA);
        }
        String obs = observacion == null || observacion.isBlank() ? "Reserva explicita IMP-B" : observacion.trim();
        if (obs.indexOf('\0') >= 0) throw new IllegalArgumentException(INVALIDA);

        var cobroRows = sql.queryForList("""
                SELECT "idCliente","estadoCobro"
                  FROM "COBRO"
                 WHERE "idCobro" = :cobro
                """, Map.of("cobro", idCobro));
        if (cobroRows.isEmpty()) throw new IllegalArgumentException("COBRO_NO_ENCONTRADO");
        long idCliente = ((Number) cobroRows.getFirst().get("idCliente")).longValue();

        identificadores.bloquear();
        identificadores.bloquearClienteA2(idCliente);
        autorizacion.exigirRoles(sesion, Set.of("ADMINISTRADOR", "VENDEDOR"));

        sql.queryForObject("SELECT \"idCliente\" FROM \"CLIENTE\" WHERE \"idCliente\"=:c FOR UPDATE",
                Map.of("c", idCliente), Long.class);

        var pedRows = sql.queryForList("""
                SELECT "idCliente","estado"
                  FROM "PEDIDO"
                 WHERE "idPedido" = :pedido
                 FOR UPDATE
                """, Map.of("pedido", idPedido));
        if (pedRows.isEmpty()) throw new IllegalArgumentException("PEDIDO_NO_ENCONTRADO");
        if (((Number) pedRows.getFirst().get("idCliente")).longValue() != idCliente) {
            throw new IllegalArgumentException("CLIENTE_COBRO_PEDIDO_NO_COINCIDE");
        }
        if (!"PENDIENTE".equals(pedRows.getFirst().get("estado"))) {
            throw new IllegalStateException("PEDIDO_NO_PENDIENTE");
        }

        var dispRows = sql.queryForList("""
                SELECT v."estadoCobro", v."importeLibreDisponible"
                  FROM "VW_IMP_A_DISPONIBILIDAD_COBRO" v
                 WHERE v."idCobro" = :cobro
                """, Map.of("cobro", idCobro));
        if (dispRows.isEmpty() || !"CONFIRMADO".equals(dispRows.getFirst().get("estadoCobro"))) {
            throw new IllegalStateException("COBRO_NO_CONFIRMADO");
        }
        Integer reembPend = sql.queryForObject("""
                SELECT count(*) FROM "REEMBOLSO" WHERE "idCobro"=:cobro AND "estado"='PENDIENTE'
                """, Map.of("cobro", idCobro), Integer.class);
        if (reembPend != null && reembPend > 0) {
            throw new IllegalStateException("REEMBOLSO_PENDIENTE_BLOQUEA_RESERVA");
        }
        BigDecimal disponible = ((BigDecimal) dispRows.getFirst().get("importeLibreDisponible")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal importeNorm = importe.setScale(2, RoundingMode.HALF_UP);
        if (disponible.compareTo(importeNorm) < 0) {
            throw new IllegalStateException("SALDO_LIBRE_INSUFICIENTE");
        }

        long actor = Objects.requireNonNull(sql.queryForObject(
                "SELECT \"idUsuario\" FROM \"SESION_USUARIO\" WHERE \"idSesionUsuario\"=:s",
                Map.of("s", sesion), Long.class));
        long idEvento = identificadores.siguienteEventoImp();
        sql.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idEventoImputacion","idCliente","idCobro","idPedido","idCierreRecorrido",
                    "tipoEvento","importe","idUsuarioOperador","fechaHoraEvento","observacion")
                VALUES (:id,:cliente,:cobro,:pedido,NULL,'RESERVA_ANTICIPO',:imp,:actor,clock_timestamp(),:obs)
                """, new MapSqlParameterSource()
                .addValue("id", idEvento)
                .addValue("cliente", idCliente)
                .addValue("cobro", idCobro)
                .addValue("pedido", idPedido)
                .addValue("imp", importeNorm)
                .addValue("actor", actor)
                .addValue("obs", obs));
        return idEvento;
    }

    /**
     * Evalua y registra cobertura IMP-B (P_e^{exp} + F_e^{FIFO}) dentro de la TX de UC-05.
     * Precondicion obligatoria: caller ya bloqueo A1(2929001) -> A2(3202, idCliente) -> CLIENTE U -> PEDIDO U.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CoberturaEntrega liquidarCoberturaEntrega(
            long idCliente,
            long idPedido,
            long idUsuarioOperador,
            BigDecimal cargoRealCe,
            BigDecimal declaradoRepartidorDe,
            OffsetDateTime ahora) {
        BigDecimal ce = cargoRealCe.setScale(2, RoundingMode.HALF_UP);
        BigDecimal de = declaradoRepartidorDe == null ? CERO : declaradoRepartidorDe.setScale(2, RoundingMode.HALF_UP);

        String motivoAmbiguedad = detectarAmbiguedadCliente(idCliente, idPedido);
        if (motivoAmbiguedad != null) {
            return new CoberturaEntrega(CERO, CERO, CERO, ce, CERO, MODO_IMP_C, motivoAmbiguedad, List.of());
        }

        List<EventoImpRegistrado> eventos = new ArrayList<>();
        BigDecimal anticipoPe = CERO;
        BigDecimal restantePorCubrir = ce;

        // 1) Consolidacion de reservas explicitas del Pedido en orden FIFO por Cobro
        var reservasPedido = sql.query("""
                SELECT c."idCobro",
                       COALESCE(SUM(CASE e."tipoEvento"
                           WHEN 'RESERVA_ANTICIPO' THEN e."importe"
                           WHEN 'CONSOLIDACION_ANTICIPO' THEN -e."importe"
                           WHEN 'LIBERACION_ANTICIPO' THEN -e."importe"
                           ELSE 0 END), 0)::NUMERIC(18,2) AS reserva_activa
                  FROM "EVENTO_IMPUTACION_FINANCIERA" e
                  JOIN "COBRO" c ON c."idCobro" = e."idCobro"
                 WHERE e."idCliente" = :cliente
                   AND e."idPedido" = :pedido
                   AND c."estadoCobro" = 'CONFIRMADO'
                 GROUP BY c."idCobro", c."fechaHoraCobro"
                HAVING COALESCE(SUM(CASE e."tipoEvento"
                           WHEN 'RESERVA_ANTICIPO' THEN e."importe"
                           WHEN 'CONSOLIDACION_ANTICIPO' THEN -e."importe"
                           WHEN 'LIBERACION_ANTICIPO' THEN -e."importe"
                           ELSE 0 END), 0) > 0
                 ORDER BY c."fechaHoraCobro" ASC, c."idCobro" ASC
                """, Map.of("cliente", idCliente, "pedido", idPedido),
                (rs, n) -> new ReservaCobro(rs.getLong(1), rs.getBigDecimal(2).setScale(2, RoundingMode.HALF_UP)));

        for (ReservaCobro r : reservasPedido) {
            BigDecimal aConsolidar = r.reservaActiva().min(restantePorCubrir);
            if (aConsolidar.signum() > 0) {
                long idEv = insertarEvento(idCliente, r.idCobro(), idPedido, "CONSOLIDACION_ANTICIPO",
                        aConsolidar, idUsuarioOperador, ahora, "Consolidacion anticipo en UC-05");
                eventos.add(new EventoImpRegistrado(idEv, r.idCobro(), idPedido, "CONSOLIDACION_ANTICIPO", aConsolidar));
                anticipoPe = anticipoPe.add(aConsolidar);
                restantePorCubrir = restantePorCubrir.subtract(aConsolidar);
            }
            BigDecimal aLiberar = r.reservaActiva().subtract(aConsolidar);
            if (aLiberar.signum() > 0) {
                long idEv = insertarEvento(idCliente, r.idCobro(), idPedido, "LIBERACION_ANTICIPO",
                        aLiberar, idUsuarioOperador, ahora, "Liberacion remanente reserva en UC-05");
                eventos.add(new EventoImpRegistrado(idEv, r.idCobro(), idPedido, "LIBERACION_ANTICIPO", aLiberar));
            }
        }

        // 2) Aplicacion FIFO sobre credito confirmado verdaderamente libre (F_e^{FIFO})
        BigDecimal fifoFe = CERO;
        if (restantePorCubrir.signum() > 0) {
            BigDecimal creditoLibreReal = calcularCreditoLibreConfirmadoReal(idCliente);
            BigDecimal objetivoFifo = restantePorCubrir.min(creditoLibreReal);
            if (objetivoFifo.signum() > 0) {
                var cobrosLibres = sql.query("""
                        SELECT v."idCobro", v."importeLibreDisponible"
                          FROM "VW_IMP_A_DISPONIBILIDAD_COBRO" v
                          JOIN "COBRO" c ON c."idCobro" = v."idCobro"
                         WHERE v."idCliente" = :cliente
                           AND v."estadoCobro" = 'CONFIRMADO'
                           AND c."idPedido" IS NULL
                           AND v."importeLibreDisponible" > 0
                         ORDER BY c."fechaHoraCobro" ASC, v."idCobro" ASC
                        """, Map.of("cliente", idCliente),
                        (rs, n) -> new ReservaCobro(rs.getLong(1), rs.getBigDecimal(2).setScale(2, RoundingMode.HALF_UP)));
                BigDecimal porAplicar = objetivoFifo;
                for (ReservaCobro c : cobrosLibres) {
                    if (porAplicar.signum() <= 0) break;
                    BigDecimal aplicar = c.reservaActiva().min(porAplicar);
                    if (aplicar.signum() > 0) {
                        long idEv = insertarEvento(idCliente, c.idCobro(), idPedido, "APLICACION_FIFO_LIBRE",
                                aplicar, idUsuarioOperador, ahora, "Aplicacion FIFO credito libre en UC-05");
                        eventos.add(new EventoImpRegistrado(idEv, c.idCobro(), idPedido, "APLICACION_FIFO_LIBRE", aplicar));
                        fifoFe = fifoFe.add(aplicar);
                        porAplicar = porAplicar.subtract(aplicar);
                    }
                }
            }
        }

        BigDecimal ke = anticipoPe.add(fifoFe);
        BigDecimal ee = ce.subtract(ke).max(CERO);
        BigDecimal v0 = de.min(ee);
        return new CoberturaEntrega(anticipoPe, fifoFe, ke, ee, v0, MODO_IMP_B, null, List.copyOf(eventos));
    }

    private String detectarAmbiguedadCliente(long idCliente, long idPedido) {
        // Senal A / legado sin reserva: COBRO CONFIRMADO con idPedido pendiente sin eventos IMP-B
        Integer cobroIdPedidoSinReserva = sql.queryForObject("""
                SELECT count(*)
                  FROM "COBRO" c
                  JOIN "PEDIDO" p ON p."idPedido" = c."idPedido"
                 WHERE c."idCliente" = :cliente
                   AND c."estadoCobro" = 'CONFIRMADO'
                   AND c."idPedido" IS NOT NULL
                   AND (p."idPedido" = :pedido OR p."estado" = 'PENDIENTE')
                   AND NOT EXISTS (
                       SELECT 1 FROM "EVENTO_IMPUTACION_FINANCIERA" e
                        WHERE e."idCobro" = c."idCobro"
                          AND e."tipoEvento" IN ('RESERVA_ANTICIPO','CONSOLIDACION_ANTICIPO','APLICACION_FIFO_LIBRE')
                   )
                """, Map.of("cliente", idCliente, "pedido", idPedido), Integer.class);
        if (cobroIdPedidoSinReserva != null && cobroIdPedidoSinReserva > 0) {
            return "COBRO_ID_PEDIDO_SIN_RESERVA_IMP_B";
        }

        // Senal C: REEMBOLSO PENDIENTE sin conciliar en algun cobro del Cliente
        Integer reembolsoPendiente = sql.queryForObject("""
                SELECT count(*)
                  FROM "REEMBOLSO" r
                  JOIN "COBRO" c ON c."idCobro" = r."idCobro"
                 WHERE c."idCliente" = :cliente
                   AND r."estado" = 'PENDIENTE'
                """, Map.of("cliente", idCliente), Integer.class);
        if (reembolsoPendiente != null && reembolsoPendiente > 0) {
            return "REEMBOLSO_PENDIENTE_SIN_CONCILIAR";
        }

        // Senal D / pre-V4 o IMP-C previo con credito libre sin conciliar
        Integer entregasSinPe1ConCredito = sql.queryForObject("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM "ENTREGA" ent
                    JOIN "PEDIDO" ped ON ped."idPedido" = ent."idPedido"
                    WHERE ped."idCliente" = :cliente
                      AND NOT EXISTS (
                          SELECT 1 FROM "RECONOCIMIENTO_ENTREGA_PE1" pe WHERE pe."idPedido" = ent."idPedido"
                      )
                ) AND EXISTS (
                    SELECT 1 FROM "VW_IMP_A_DISPONIBILIDAD_COBRO" v
                    WHERE v."idCliente" = :cliente AND v."importeLibreDisponible" > 0
                ) THEN 1 ELSE 0 END
                """, Map.of("cliente", idCliente), Integer.class);
        if (entregasSinPe1ConCredito != null && entregasSinPe1ConCredito > 0) {
            return "ENTREGAS_PRE_GFIS_SIN_CONCILIAR_CON_CREDITO";
        }

        Integer pe1ImpCPrevioConCredito = sql.queryForObject("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM "RECONOCIMIENTO_ENTREGA_PE1" pe
                    WHERE pe."idCliente" = :cliente
                      AND pe."modoEvidencia" = 'IMP_C'
                      AND pe."declaradoRepartidorDe" > 0
                ) AND EXISTS (
                    SELECT 1 FROM "VW_IMP_A_DISPONIBILIDAD_COBRO" v
                    WHERE v."idCliente" = :cliente AND v."importeLibreDisponible" > 0
                ) THEN 1 ELSE 0 END
                """, Map.of("cliente", idCliente), Integer.class);
        if (pe1ImpCPrevioConCredito != null && pe1ImpCPrevioConCredito > 0) {
            return "IMP_C_PREVIO_PENDIENTE_DE_CONCILIACION";
        }

        return null;
    }

    private BigDecimal calcularCreditoLibreConfirmadoReal(long idCliente) {
        var saldos = sql.queryForList("""
                SELECT "saldoResultante"
                  FROM "MOVIMIENTO_CUENTA"
                 WHERE "idCliente" = :cliente
                 ORDER BY "numeroSecuenciaCliente" DESC
                 LIMIT 1
                """, Map.of("cliente", idCliente));
        if (saldos.isEmpty()) return CERO;
        BigDecimal saldoConfirmado = ((BigDecimal) saldos.getFirst().get("saldoResultante")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal favorBruto = saldoConfirmado.negate().max(CERO);
        if (favorBruto.signum() <= 0) return CERO;

        BigDecimal reservasActivasTotales = sql.queryForObject("""
                SELECT COALESCE(SUM(CASE e."tipoEvento"
                    WHEN 'RESERVA_ANTICIPO' THEN e."importe"
                    WHEN 'CONSOLIDACION_ANTICIPO' THEN -e."importe"
                    WHEN 'LIBERACION_ANTICIPO' THEN -e."importe"
                    ELSE 0 END), 0)::NUMERIC(18,2)
                  FROM "EVENTO_IMPUTACION_FINANCIERA" e
                  JOIN "COBRO" c ON c."idCobro" = e."idCobro"
                 WHERE e."idCliente" = :cliente
                   AND c."estadoCobro" = 'CONFIRMADO'
                """, Map.of("cliente", idCliente), BigDecimal.class);
        BigDecimal reservado = reservasActivasTotales == null ? CERO : reservasActivasTotales.setScale(2, RoundingMode.HALF_UP).max(CERO);
        return favorBruto.subtract(reservado).max(CERO);
    }

    private long insertarEvento(
            long idCliente,
            long idCobro,
            long idPedido,
            String tipoEvento,
            BigDecimal importe,
            long idUsuarioOperador,
            OffsetDateTime ahora,
            String observacion) {
        long idEvento = identificadores.siguienteEventoImp();
        sql.update("""
                INSERT INTO "EVENTO_IMPUTACION_FINANCIERA" (
                    "idEventoImputacion","idCliente","idCobro","idPedido","idCierreRecorrido",
                    "tipoEvento","importe","idUsuarioOperador","fechaHoraEvento","observacion")
                VALUES (:id,:cliente,:cobro,:pedido,NULL,:tipo,:imp,:actor,:fecha,:obs)
                """, new MapSqlParameterSource()
                .addValue("id", idEvento)
                .addValue("cliente", idCliente)
                .addValue("cobro", idCobro)
                .addValue("pedido", idPedido)
                .addValue("tipo", tipoEvento)
                .addValue("imp", importe.setScale(2, RoundingMode.HALF_UP))
                .addValue("actor", idUsuarioOperador)
                .addValue("fecha", ahora)
                .addValue("obs", observacion));
        return idEvento;
    }

    private record ReservaCobro(long idCobro, BigDecimal reservaActiva) {}
}
