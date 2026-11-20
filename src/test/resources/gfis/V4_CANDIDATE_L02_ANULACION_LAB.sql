-- =============================================================================
-- G-FIS (Fase B — Laboratorio aislado N2-T10): Correccion SQL candidata L02
--
-- IMPORTANTE:
-- - Este archivo reside bajo src/test/resources/gfis/ y se carga EXCLUSIVAMENTE
--   en el contenedor PostgreSQL 17.11 aislado de GfisL02AnulacionCandidataLabIntegrationTest.
-- - NO modifica V1__baseline_mr5.sql ni contamina otros tests o contenedores.
-- - NO esta promovido a src/main/resources/db/migration/.
--
-- Requisitos de L02 satisfechos:
--   1) Identificar idCliente del Cobro SIN adquirir lock contradictorio (lectura sin FOR UPDATE).
--   2) Adquirir bloqueos en orden global compatible:
--        A2(NS_CLIENTE=3202, idCliente) -> CLIENTE FOR UPDATE -> COBRO FOR UPDATE.
--   3) Revalidar Cobro, pertenencia al Cliente, rol ADMIN habilitado, estado CONFIRMADO,
--      ausencia de REEMBOLSO y asientos originales DESPUES de la espera.
--   4) Preservar D7-A (prohibido anular Cobro REPARTO/K2) y DC05-07-A (reservas/imputaciones activas).
--   5) Respetar triggers V1/V2/MR-5 y constraint triggers diferidos de contraasiento.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

CREATE OR REPLACE FUNCTION "fn_mr3_anular_cobro_manual"(
    p_id_cobro BIGINT,
    p_id_usuario_admin BIGINT,
    p_id_mov_cuenta BIGINT,
    p_id_mov_caja BIGINT,
    p_motivo TEXT
) RETURNS void LANGUAGE plpgsql AS $gfis_l02$
DECLARE
    v_id_cliente BIGINT;
    c record;
    s NUMERIC(18,2);
    n BIGINT;
    v_comprometido NUMERIC(18,2);
BEGIN
    IF length(btrim(coalesce(p_motivo, ''))) = 0 THEN
        RAISE EXCEPTION 'Motivo requerido';
    END IF;

    -- Paso 1: identificar Cliente sin lock FOR UPDATE sobre COBRO para evitar ciclo COBRO -> CLIENTE
    SELECT "idCliente" INTO v_id_cliente
      FROM "COBRO"
     WHERE "idCobro" = p_id_cobro;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Cobro no encontrado';
    END IF;

    -- Paso 2: precedencia global A2-GLOBAL compatible: A2(NS_CLIENTE, idCliente) -> CLIENTE U -> COBRO U
    PERFORM pg_advisory_xact_lock(3202, v_id_cliente::integer);
    PERFORM 1 FROM "CLIENTE" WHERE "idCliente" = v_id_cliente FOR UPDATE;

    SELECT * INTO c
      FROM "COBRO"
     WHERE "idCobro" = p_id_cobro
     FOR UPDATE;

    -- Paso 3: revalidacion completa post-espera
    IF NOT FOUND OR c."idCliente" <> v_id_cliente THEN
        RAISE EXCEPTION 'Cobro no encontrado o inconsistente tras espera';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM "USUARIO" u
          JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
         WHERE u."idUsuario" = p_id_usuario_admin
           AND u."estado" = 'HABILITADO'
           AND ur."codigoRol" = 'ADMINISTRADOR'
    ) THEN
        RAISE EXCEPTION 'Solo Administrador habilitado puede anular cobro';
    END IF;

    IF c."origen" = 'ONLINE' OR c."medioPago" = 'MERCADOPAGO' THEN
        RAISE EXCEPTION 'La anulacion local no reemplaza reembolso electronico';
    END IF;

    -- Paso 4: preservar D7-A y DC05-07-A explicitamente (ademas del trigger tg_gfis_guard_d7a_y_reservas_cobro)
    IF c."origen" = 'REPARTO'
       OR EXISTS (SELECT 1 FROM "COBRO_RENDICION_K2" WHERE "idCobro" = p_id_cobro) THEN
        RAISE EXCEPTION 'D7-A: Cobro de rendicion de reparto (%) no admite anulacion manual', p_id_cobro;
    END IF;

    SELECT COALESCE(SUM(CASE "tipoEvento"
        WHEN 'RESERVA_ANTICIPO' THEN "importe"
        WHEN 'APLICACION_FIFO_LIBRE' THEN "importe"
        WHEN 'APLICACION_RENDICION_K2' THEN "importe"
        WHEN 'LIBERACION_ANTICIPO' THEN -"importe"
        ELSE 0 END), 0)
      INTO v_comprometido
      FROM "EVENTO_IMPUTACION_FINANCIERA"
     WHERE "idCobro" = p_id_cobro;

    IF v_comprometido > 0 THEN
        RAISE EXCEPTION 'DC05-07-A: Cobro % posee reservas o imputaciones activas (%) y no puede anularse',
            p_id_cobro, v_comprometido;
    END IF;

    IF c."estadoCobro" = 'ANULADO'
       OR EXISTS (SELECT 1 FROM "REEMBOLSO" r WHERE r."idCobro" = p_id_cobro)
       OR NOT EXISTS (
           SELECT 1 FROM "MOVIMIENTO_CAJA" mc
            WHERE mc."idCobro" = p_id_cobro
              AND mc."origen" = 'COBRO'
              AND mc."tipoMovimiento" = 'INGRESO'
              AND mc."importe" = c."importe"
       ) THEN
        RAISE EXCEPTION 'Cobro ya anulado, reembolsado o sin asiento original valido';
    END IF;

    SELECT mc."saldoResultante", mc."numeroSecuenciaCliente"
      INTO s, n
      FROM "MOVIMIENTO_CUENTA" mc
     WHERE mc."idCliente" = c."idCliente"
     ORDER BY mc."numeroSecuenciaCliente" DESC
     LIMIT 1;
    IF n IS NULL THEN
        RAISE EXCEPTION 'Sin cuenta corriente de referencia';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM "ORIGEN_MOVIMIENTO_CUENTA" o
          JOIN "MOVIMIENTO_CUENTA" m ON m."idMovimientoCuenta" = o."idMovimientoCuenta"
         WHERE o."idCobro" = p_id_cobro
           AND m."tipoMovimiento" = 'PAGO'
           AND m."importe" = c."importe"
           AND m."idCliente" = c."idCliente"
    ) THEN
        RAISE EXCEPTION 'Sin pago original conciliable';
    END IF;

    -- Paso 5: contraasientos atomicos compatibles con MR-3, MR-5 y F01
    INSERT INTO "MOVIMIENTO_CUENTA" (
        "idMovimientoCuenta","idCliente","numeroSecuenciaCliente","tipoMovimiento",
        "importe","fechaHoraMovimiento","saldoResultante","idUsuarioAjuste","observacion"
    ) VALUES (
        p_id_mov_cuenta, c."idCliente", n + 1, 'ANULACION_COBRO',
        c."importe", now(), s + c."importe", p_id_usuario_admin, p_motivo
    );

    INSERT INTO "ORIGEN_MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCobroAnulado")
    VALUES (p_id_mov_cuenta, p_id_cobro);

    INSERT INTO "MOVIMIENTO_CAJA" (
        "idMovimientoCaja","tipoMovimiento","importe","fechaHoraMovimiento","origen",
        "concepto","referencia","observacion","idUsuarioRegistro","idCobroAnulado"
    ) VALUES (
        p_id_mov_caja, 'EGRESO', c."importe", now(), 'ANULACION_COBRO',
        'Anulacion de cobro manual', 'COBRO:' || p_id_cobro, p_motivo, p_id_usuario_admin, p_id_cobro
    );

    UPDATE "COBRO"
       SET "estadoCobro" = 'ANULADO'
     WHERE "idCobro" = p_id_cobro;
END;
$gfis_l02$;
