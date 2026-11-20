-- =============================================================================
-- G-FIS (Fase B — Laboratorio aislado): Prototipo de migracion candidata V4
-- Alcance estricto: PE-1, PE-2 (T1-C2 + K2), IMP-01 (IMP-B/IMP-A/IMP-C),
-- Recorrido/Cierre (C1-EXPLICITO + FA-1/FA-2 propuestas), custodia y egreso
-- auditable (E-04), idempotencia de anulacion (C-04 / ART-GCON-D1) y guard
-- SQL D7-A / DC05-07-A.
--
-- IMPORTANTE:
-- - Este archivo reside bajo src/test/resources/gfis/ y se carga EXCLUSIVAMENTE
--   en PostgreSQL 17.11 descartable (Testcontainers) desde src/test/**.
-- - NO esta promovido a src/main/resources/db/migration/ ni lo ejecuta la app.
-- - NO modifica V1, V2 ni V3.
-- - EXCLUYE expresamente: UC-07 fisico (CR-C/CR-F/#47/#48), PE-3 offline,
--   recupero post-cierre (E), medios de pago distintos de EFECTIVO en PE-2 (D1)
--   y cualquier cambio en Demo o despliegue.
-- - Base normativa: ale-milanesas/main@e3f4ee5a752b (ACTA_G_COM_001 RCG-01..08,
--   RD-F04-002/005, #65/#66/#67/#70/#71/#72/#73; S10=NO ACREDITADO con
--   tratamiento autonomo de G-ARQ Nivel 2). A1, FA-1, FA-2 y B1 son PROPUESTAS
--   en validacion tecnica, NO RATIFICADAS todavia.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- -----------------------------------------------------------------------------
-- BLOQUE 0: Preflight de compatibilidad V1-V3 y RCG-06 (sin backfill heuristico)
-- -----------------------------------------------------------------------------
DO $preflight$
BEGIN
    IF to_regclass('"COBRO"') IS NULL
       OR to_regclass('"ENTREGA"') IS NULL
       OR to_regclass('"PEDIDO"') IS NULL
       OR to_regclass('"ASIGNACION_REPARTO"') IS NULL
       OR to_regclass('"MOVIMIENTO_CUENTA"') IS NULL
       OR to_regclass('"MOVIMIENTO_CAJA"') IS NULL
       OR to_regclass('"REEMBOLSO"') IS NULL
       OR to_regclass('"PARTIDA_INSUMO"') IS NULL THEN
        RAISE EXCEPTION 'GFIS-PREFLIGHT: faltan tablas base V1-V3 esperadas';
    END IF;

    IF EXISTS (SELECT 1 FROM "COBRO" WHERE "estadoCobro" NOT IN ('CONFIRMADO', 'ANULADO')) THEN
        RAISE EXCEPTION 'GFIS-PREFLIGHT-F01: existen COBRO con estadoCobro fuera de catalogo';
    END IF;

    IF EXISTS (SELECT 1 FROM "COBRO" WHERE "idCobro" <= 0)
       OR EXISTS (SELECT 1 FROM "MOVIMIENTO_CUENTA" WHERE "idMovimientoCuenta" <= 0)
       OR EXISTS (SELECT 1 FROM "MOVIMIENTO_CAJA" WHERE "idMovimientoCaja" <= 0) THEN
        RAISE EXCEPTION 'GFIS-PREFLIGHT-B2: existen PKs financieras no positivas';
    END IF;
END
$preflight$;

-- Constraint UNIQUE aditivo en COBRO para FK compuesta (idCobro, idCliente),
-- garantizando a nivel relacional que K2, IMP-B y C-04 nunca mezclen Clientes.
DO $uq_cobro$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_gfis_cobro_cliente'
          AND conrelid = '"COBRO"'::regclass
    ) THEN
        ALTER TABLE "COBRO"
            ADD CONSTRAINT "uq_gfis_cobro_cliente" UNIQUE ("idCobro", "idCliente");
    END IF;
END
$uq_cobro$;

-- -----------------------------------------------------------------------------
-- BLOQUE 1: B2-HIBRIDO — Secuencias para PKs tecnicas nuevas y puente seguro
-- para PKs globales financieras (ordinales de negocio permanecen por agregado),
-- mas proyeccion segura BIGINT -> int4 sin desbordamiento 22003 para A2(ns, k).
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "fn_gfis_advisory_lock_key"(p_id BIGINT)
RETURNS INTEGER LANGUAGE sql IMMUTABLE STRICT AS $alk$
    SELECT hashtext(p_id::text);
$alk$;

CREATE SEQUENCE IF NOT EXISTS "seq_gfis_recorrido" START 1;
CREATE SEQUENCE IF NOT EXISTS "seq_gfis_recorrido_asignacion" START 1;
CREATE SEQUENCE IF NOT EXISTS "seq_gfis_cierre_recorrido" START 1;
CREATE SEQUENCE IF NOT EXISTS "seq_gfis_revision_conciliacion" START 1;
CREATE SEQUENCE IF NOT EXISTS "seq_gfis_propuesta_app" START 1;
CREATE SEQUENCE IF NOT EXISTS "seq_gfis_aplicacion_k2" START 1;
CREATE SEQUENCE IF NOT EXISTS "seq_gfis_evento_imp" START 1;
CREATE SEQUENCE IF NOT EXISTS "seq_gfis_custodia_diferencia" START 1;
CREATE SEQUENCE IF NOT EXISTS "seq_gfis_egreso_custodia" START 1;

CREATE SEQUENCE IF NOT EXISTS "seq_gfis_cobro_id" START 1;
CREATE SEQUENCE IF NOT EXISTS "seq_gfis_mov_cuenta_id" START 1;
CREATE SEQUENCE IF NOT EXISTS "seq_gfis_mov_caja_id" START 1;

-- Seed dinamico seguro: garantiza nextval() estrictamente mayor que MAX(id) existente
-- sin backfill de datos historicos ni alteracion de filas previas.
SELECT setval(
    '"seq_gfis_cobro_id"',
    GREATEST((SELECT COALESCE(MAX("idCobro"), 0) FROM "COBRO"), 1),
    (SELECT EXISTS (SELECT 1 FROM "COBRO"))
);
SELECT setval(
    '"seq_gfis_mov_cuenta_id"',
    GREATEST((SELECT COALESCE(MAX("idMovimientoCuenta"), 0) FROM "MOVIMIENTO_CUENTA"), 1),
    (SELECT EXISTS (SELECT 1 FROM "MOVIMIENTO_CUENTA"))
);
SELECT setval(
    '"seq_gfis_mov_caja_id"',
    GREATEST((SELECT COALESCE(MAX("idMovimientoCaja"), 0) FROM "MOVIMIENTO_CAJA"), 1),
    (SELECT EXISTS (SELECT 1 FROM "MOVIMIENTO_CAJA"))
);

-- -----------------------------------------------------------------------------
-- BLOQUE 2: PE-1 — Snapshot financiero inmutable de Entrega y reconocimiento V_e
-- Formula R08.4 / RCG-03:
--   K_e = P_e^{exp} + F_e^{FIFO} (0 <= K_e <= C_e)
--   E_e = max(0, C_e - K_e)
--   V_e^0 = min(D_e, E_e)   [en IMP_C: V_e^0 = 0 para impedir invencion]
--   Conservacion verificada al COMMIT: A_e + V_e^{vig} = V_e^0
-- -----------------------------------------------------------------------------
CREATE TABLE "RECONOCIMIENTO_ENTREGA_PE1" (
    "idPedido" BIGINT NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "claveIdempotenciaEntrega" UUID NOT NULL,
    "cargoRealCe" NUMERIC(18,2) NOT NULL,
    "anticipoExplicitoPe" NUMERIC(18,2) NOT NULL,
    "fifoVinculanteFe" NUMERIC(18,2) NOT NULL,
    "coberturaTotalKe" NUMERIC(18,2) NOT NULL,
    "exigibleEe" NUMERIC(18,2) NOT NULL,
    "declaradoRepartidorDe" NUMERIC(18,2) NOT NULL,
    "reconocimientoInicialV0" NUMERIC(18,2) NOT NULL,
    "reconocimientoVigenteVvig" NUMERIC(18,2) NOT NULL,
    "modoEvidencia" VARCHAR(10) NOT NULL,
    "estadoContencionUc07" VARCHAR(30) NOT NULL DEFAULT 'SOLO_ORIGINALES',
    "fechaHoraRegistro" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_gfis_reconocimiento_pe1" PRIMARY KEY ("idPedido"),
    CONSTRAINT "uq_gfis_reconocimiento_clave" UNIQUE ("claveIdempotenciaEntrega"),
    CONSTRAINT "uq_gfis_reconocimiento_pedido_cliente" UNIQUE ("idPedido", "idCliente"),
    CONSTRAINT "fk_gfis_pe1_entrega" FOREIGN KEY ("idPedido")
        REFERENCES "ENTREGA" ("idPedido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_pe1_pedido_cliente" FOREIGN KEY ("idPedido", "idCliente")
        REFERENCES "PEDIDO" ("idPedido", "idCliente") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_pe1_no_negativos" CHECK (
        "cargoRealCe" >= 0
        AND "anticipoExplicitoPe" >= 0
        AND "fifoVinculanteFe" >= 0
        AND "declaradoRepartidorDe" >= 0
        AND "reconocimientoInicialV0" >= 0
        AND "reconocimientoVigenteVvig" >= 0
    ),
    CONSTRAINT "ck_gfis_pe1_formula_r084" CHECK (
        "coberturaTotalKe" = "anticipoExplicitoPe" + "fifoVinculanteFe"
        AND "coberturaTotalKe" <= "cargoRealCe"
        AND "exigibleEe" = "cargoRealCe" - "coberturaTotalKe"
    ),
    CONSTRAINT "ck_gfis_pe1_modo_evidencia" CHECK (
        ("modoEvidencia" = 'IMP_B'
            AND "reconocimientoInicialV0" = LEAST("declaradoRepartidorDe", "exigibleEe"))
        OR ("modoEvidencia" = 'IMP_C'
            AND "reconocimientoInicialV0" = 0)
    ),
    CONSTRAINT "ck_gfis_pe1_vigente_acotado" CHECK (
        "reconocimientoVigenteVvig" <= "reconocimientoInicialV0"
    ),
    CONSTRAINT "ck_gfis_pe1_sin_uc07" CHECK (
        "estadoContencionUc07" = 'SOLO_ORIGINALES'
    )
);

CREATE OR REPLACE FUNCTION "fn_gfis_proteger_pe1"()
RETURNS trigger LANGUAGE plpgsql AS $pe1$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'PE-1: el snapshot financiero de entrega es inmutable y no se elimina';
    END IF;
    IF ROW(NEW."idPedido", NEW."idCliente", NEW."claveIdempotenciaEntrega",
           NEW."cargoRealCe", NEW."anticipoExplicitoPe", NEW."fifoVinculanteFe",
           NEW."coberturaTotalKe", NEW."exigibleEe", NEW."declaradoRepartidorDe",
           NEW."reconocimientoInicialV0", NEW."modoEvidencia",
           NEW."estadoContencionUc07", NEW."fechaHoraRegistro")
       IS DISTINCT FROM
       ROW(OLD."idPedido", OLD."idCliente", OLD."claveIdempotenciaEntrega",
           OLD."cargoRealCe", OLD."anticipoExplicitoPe", OLD."fifoVinculanteFe",
           OLD."coberturaTotalKe", OLD."exigibleEe", OLD."declaradoRepartidorDe",
           OLD."reconocimientoInicialV0", OLD."modoEvidencia",
           OLD."estadoContencionUc07", OLD."fechaHoraRegistro") THEN
        RAISE EXCEPTION 'PE-1: solo reconocimientoVigenteVvig puede decrecer por aplicacion K2';
    END IF;
    IF NEW."reconocimientoVigenteVvig" > OLD."reconocimientoVigenteVvig" THEN
        RAISE EXCEPTION 'PE-1: prohibido incrementar reconocimientoVigenteVvig (D7-B diferida)';
    END IF;
    RETURN NEW;
END
$pe1$;

CREATE TRIGGER "tg_gfis_proteger_pe1"
    BEFORE UPDATE OR DELETE ON "RECONOCIMIENTO_ENTREGA_PE1"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_proteger_pe1"();

-- -----------------------------------------------------------------------------
-- BLOQUE 3: C1-EXPLICITO — Identidad durable de Recorrido, unicidad FA-1,
-- pertenencia pre-ENTREGA (RECORRIDO_ASIGNACION), validacion estricta en
-- RECORRIDO_ENTREGA y control FA-2 de Pedidos PENDIENTE antes del cierre.
-- NOTA: A1, FA-1 y FA-2 son PROPUESTAS en laboratorio, NO RATIFICADAS todavia.
-- -----------------------------------------------------------------------------
CREATE TABLE "RECORRIDO_REPARTO" (
    "idRecorrido" BIGINT NOT NULL DEFAULT nextval('"seq_gfis_recorrido"'),
    "idUsuarioRepartidor" BIGINT NOT NULL,
    "fechaHoraCreacion" TIMESTAMPTZ NOT NULL,
    "estadoOperativo" VARCHAR(25) NOT NULL DEFAULT 'ABIERTO',
    "referenciaOperativa" VARCHAR(120),
    CONSTRAINT "pk_gfis_recorrido" PRIMARY KEY ("idRecorrido"),
    CONSTRAINT "fk_gfis_recorrido_repartidor" FOREIGN KEY ("idUsuarioRepartidor")
        REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_recorrido_estado" CHECK (
        "estadoOperativo" IN ('ABIERTO', 'PENDIENTE_CONCILIACION', 'CERRADO')
    )
);

-- FA-1 (PROPUESTA, NO RATIFICADA): a lo sumo un Recorrido ABIERTO por Repartidor,
-- permitiendo vincular asignaciones UC-43 sin agregar parametros a la firma G0.
CREATE UNIQUE INDEX "uq_gfis_unico_recorrido_abierto_repartidor"
    ON "RECORRIDO_REPARTO" ("idUsuarioRepartidor")
    WHERE "estadoOperativo" = 'ABIERTO';

-- Pertenencia auditable de Pedidos PENDIENTE al Recorrido ANTES de existir ENTREGA,
-- enlazada a ASIGNACION_REPARTO(idPedido, numeroAsignacion) para preservar historial UC-43.
CREATE TABLE "RECORRIDO_ASIGNACION" (
    "idRecorridoAsignacion" BIGINT NOT NULL DEFAULT nextval('"seq_gfis_recorrido_asignacion"'),
    "idRecorrido" BIGINT NOT NULL,
    "idPedido" BIGINT NOT NULL,
    "numeroAsignacion" INTEGER NOT NULL,
    "estadoPertenencia" VARCHAR(40) NOT NULL DEFAULT 'ACTIVA',
    "fechaHoraVinculacion" TIMESTAMPTZ NOT NULL,
    "fechaHoraDesvinculacion" TIMESTAMPTZ,
    "motivoDesvinculacion" TEXT,
    CONSTRAINT "pk_gfis_recorrido_asignacion" PRIMARY KEY ("idRecorridoAsignacion"),
    CONSTRAINT "uq_gfis_recorrido_asig_ref" UNIQUE ("idPedido", "numeroAsignacion"),
    CONSTRAINT "fk_gfis_recorrido_asig_rec" FOREIGN KEY ("idRecorrido")
        REFERENCES "RECORRIDO_REPARTO" ("idRecorrido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_recorrido_asig_ar" FOREIGN KEY ("idPedido", "numeroAsignacion")
        REFERENCES "ASIGNACION_REPARTO" ("idPedido", "numeroAsignacion") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_recorrido_asig_estado" CHECK (
        "estadoPertenencia" IN (
            'ACTIVA',
            'DESVINCULADA_POR_REASIGNACION',
            'DESVINCULADA_POR_REPROGRAMACION',
            'DESVINCULADA_POR_CANCELACION',
            'ENTREGADA'
        )
    ),
    CONSTRAINT "ck_gfis_recorrido_asig_desv" CHECK (
        ("estadoPertenencia" IN ('ACTIVA', 'ENTREGADA') AND "fechaHoraDesvinculacion" IS NULL)
        OR ("estadoPertenencia" IN (
                'DESVINCULADA_POR_REASIGNACION',
                'DESVINCULADA_POR_REPROGRAMACION',
                'DESVINCULADA_POR_CANCELACION'
            ) AND "fechaHoraDesvinculacion" IS NOT NULL
              AND length(btrim(coalesce("motivoDesvinculacion", ''))) > 0)
    )
);

-- Un Pedido PENDIENTE pertenece a lo sumo a un Recorrido activo a la vez.
CREATE UNIQUE INDEX "uq_gfis_recorrido_asig_activa_pedido"
    ON "RECORRIDO_ASIGNACION" ("idPedido")
    WHERE "estadoPertenencia" = 'ACTIVA';

CREATE OR REPLACE FUNCTION "fn_gfis_validar_recorrido_asignacion"()
RETURNS trigger LANGUAGE plpgsql AS $ra$
DECLARE
    v_rec_rep BIGINT;
    v_rec_est VARCHAR(25);
    v_ar_rep BIGINT;
    v_ar_fin TIMESTAMPTZ;
    v_ped_est VARCHAR(20);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'FRONTERA-A: historial de pertenencia RECORRIDO_ASIGNACION no se elimina';
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT "idUsuarioRepartidor", "estadoOperativo"
          INTO v_rec_rep, v_rec_est
          FROM "RECORRIDO_REPARTO"
         WHERE "idRecorrido" = NEW."idRecorrido";

        IF v_rec_est <> 'ABIERTO' THEN
            RAISE EXCEPTION 'FRONTERA-A: solo un Recorrido ABIERTO admite nuevas vinculaciones de Pedido';
        END IF;

        SELECT "idUsuarioRepartidor", "fechaHoraFin"
          INTO v_ar_rep, v_ar_fin
          FROM "ASIGNACION_REPARTO"
         WHERE "idPedido" = NEW."idPedido"
           AND "numeroAsignacion" = NEW."numeroAsignacion";

        IF v_ar_rep IS DISTINCT FROM v_rec_rep THEN
            RAISE EXCEPTION 'FRONTERA-A: Repartidor de ASIGNACION_REPARTO (%) no coincide con RECORRIDO_REPARTO (%)',
                v_ar_rep, v_rec_rep;
        END IF;

        IF NEW."estadoPertenencia" = 'ACTIVA' THEN
            IF v_ar_fin IS NOT NULL THEN
                RAISE EXCEPTION 'FRONTERA-A: no se puede vincular como ACTIVA una asignacion ya cerrada';
            END IF;
            SELECT "estado" INTO v_ped_est FROM "PEDIDO" WHERE "idPedido" = NEW."idPedido";
            IF v_ped_est <> 'PENDIENTE' THEN
                RAISE EXCEPTION 'FRONTERA-A: solo Pedidos PENDIENTE se vinculan como ACTIVA al Recorrido';
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    IF OLD."estadoPertenencia" <> 'ACTIVA' THEN
        RAISE EXCEPTION 'FRONTERA-A: pertenencia en estado % es inmutable', OLD."estadoPertenencia";
    END IF;
    IF ROW(NEW."idRecorridoAsignacion", NEW."idRecorrido", NEW."idPedido",
           NEW."numeroAsignacion", NEW."fechaHoraVinculacion")
       IS DISTINCT FROM
       ROW(OLD."idRecorridoAsignacion", OLD."idRecorrido", OLD."idPedido",
           OLD."numeroAsignacion", OLD."fechaHoraVinculacion") THEN
        RAISE EXCEPTION 'FRONTERA-A: prohibido mutar identidad o Recorrido de una pertenencia existente';
    END IF;
    IF NEW."estadoPertenencia" = 'ACTIVA' THEN
        RAISE EXCEPTION 'FRONTERA-A: actualizacion redundante sin cambio de estado de pertenencia';
    END IF;
    RETURN NEW;
END
$ra$;

CREATE TRIGGER "tg_gfis_validar_recorrido_asignacion"
    BEFORE INSERT OR UPDATE OR DELETE ON "RECORRIDO_ASIGNACION"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_validar_recorrido_asignacion"();

CREATE TABLE "RECORRIDO_ENTREGA" (
    "idRecorrido" BIGINT NOT NULL,
    "idPedido" BIGINT NOT NULL,
    "fechaHoraVinculacion" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_gfis_recorrido_entrega" PRIMARY KEY ("idRecorrido", "idPedido"),
    CONSTRAINT "uq_gfis_recorrido_entrega_pedido" UNIQUE ("idPedido"),
    CONSTRAINT "fk_gfis_recorrido_entrega_rec" FOREIGN KEY ("idRecorrido")
        REFERENCES "RECORRIDO_REPARTO" ("idRecorrido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_recorrido_entrega_ent" FOREIGN KEY ("idPedido")
        REFERENCES "ENTREGA" ("idPedido") ON UPDATE RESTRICT ON DELETE RESTRICT
);

-- Correccion Bloqueante 2: exige pertenencia ACTIVA en RECORRIDO_ASIGNACION y
-- ASIGNACION_REPARTO vigente (fechaHoraFin IS NULL) compatibles con ENTREGA y Recorrido.
CREATE OR REPLACE FUNCTION "fn_gfis_validar_recorrido_entrega"()
RETURNS trigger LANGUAGE plpgsql AS $re$
DECLARE
    v_rec_rep BIGINT;
    v_rec_est VARCHAR(25);
    v_ent_rep BIGINT;
    v_asig_num INTEGER;
    v_ar_rep BIGINT;
    v_ar_fin TIMESTAMPTZ;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'FRONTERA-A: el vinculo RECORRIDO_ENTREGA es inmutable una vez registrado';
    END IF;

    SELECT "idUsuarioRepartidor", "estadoOperativo"
      INTO v_rec_rep, v_rec_est
      FROM "RECORRIDO_REPARTO"
     WHERE "idRecorrido" = NEW."idRecorrido";

    IF v_rec_est <> 'ABIERTO' THEN
        RAISE EXCEPTION 'FRONTERA-A: no se puede vincular Entrega a Recorrido en estado %', v_rec_est;
    END IF;

    SELECT "idUsuarioRepartidor" INTO v_ent_rep
      FROM "ENTREGA"
     WHERE "idPedido" = NEW."idPedido";

    IF v_ent_rep IS DISTINCT FROM v_rec_rep THEN
        RAISE EXCEPTION 'FRONTERA-A: Repartidor de ENTREGA (%) no coincide con RECORRIDO_REPARTO (%)',
            v_ent_rep, v_rec_rep;
    END IF;

    SELECT "numeroAsignacion" INTO v_asig_num
      FROM "RECORRIDO_ASIGNACION"
     WHERE "idPedido" = NEW."idPedido"
       AND "idRecorrido" = NEW."idRecorrido"
       AND "estadoPertenencia" = 'ACTIVA';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'FRONTERA-A: Entrega de Pedido % exige pertenencia ACTIVA previa en RECORRIDO_ASIGNACION para Recorrido %',
            NEW."idPedido", NEW."idRecorrido";
    END IF;

    SELECT "idUsuarioRepartidor", "fechaHoraFin"
      INTO v_ar_rep, v_ar_fin
      FROM "ASIGNACION_REPARTO"
     WHERE "idPedido" = NEW."idPedido"
       AND "numeroAsignacion" = v_asig_num;

    IF NOT FOUND OR v_ar_fin IS NOT NULL OR v_ar_rep IS DISTINCT FROM v_ent_rep THEN
        RAISE EXCEPTION 'FRONTERA-A: ASIGNACION_REPARTO (%, %) no esta vigente o no coincide con Repartidor de ENTREGA',
            NEW."idPedido", v_asig_num;
    END IF;

    RETURN NEW;
END
$re$;

CREATE TRIGGER "tg_gfis_validar_recorrido_entrega"
    BEFORE INSERT OR UPDATE OR DELETE ON "RECORRIDO_ENTREGA"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_validar_recorrido_entrega"();

-- -----------------------------------------------------------------------------
-- BLOQUE 4: PE-2 — Cierre unico incondicional por Recorrido (Bloqueante 3),
-- separacion estricta entre planificacion PENDIENTE_CONCILIACION (0 efectos
-- financieros) y confirmacion T1-C2 definitiva (Bloqueante 1 / G-ARQ-N2-01 s8),
-- Cobros K2, Aplicaciones, Custodia y Egreso auditable con recibo (Bloqueante 4).
-- -----------------------------------------------------------------------------
CREATE TABLE "CIERRE_RECORRIDO_PE2" (
    "idCierreRecorrido" BIGINT NOT NULL DEFAULT nextval('"seq_gfis_cierre_recorrido"'),
    "idRecorrido" BIGINT NOT NULL,
    "idRevisionVigente" BIGINT,
    "claveIdempotenciaPropuesta" UUID,
    "claveIdempotenciaCierre" UUID,
    "versionHuella" VARCHAR(20),
    "huellaCanonica" VARCHAR(128),
    "idUsuarioAdministrador" BIGINT NOT NULL,
    "fechaHoraRegistro" TIMESTAMPTZ NOT NULL,
    "estadoCierre" VARCHAR(40) NOT NULL,
    "medioPagoPermitido" VARCHAR(20) NOT NULL DEFAULT 'EFECTIVO',
    "importeEsperado" NUMERIC(18,2) NOT NULL,
    "importeEfectivoRecibido" NUMERIC(18,2) NOT NULL,
    "importeImputadoPropuesto" NUMERIC(18,2) NOT NULL,
    "importeImputadoConfirmado" NUMERIC(18,2) NOT NULL,
    "importeDiferenciaFaltante" NUMERIC(18,2) NOT NULL,
    "importeCustodiaNoImputada" NUMERIC(18,2) NOT NULL,
    "motivoDiferencia" TEXT,
    "idUsuarioResolucionAdmin" BIGINT,
    "tipoResolucionAdmin" VARCHAR(50),
    "motivoResolucionAdmin" TEXT,
    "fechaHoraResolucion" TIMESTAMPTZ,
    "reciboHistoricoJson" JSONB,
    CONSTRAINT "pk_gfis_cierre_pe2" PRIMARY KEY ("idCierreRecorrido"),
    -- Bloqueante 3: unicidad incondicional por idRecorrido impide coexistencia de
    -- PENDIENTE_CONCILIACION y cierre terminal bajo cualquier orden o concurrencia.
    CONSTRAINT "uq_gfis_unico_cierre_por_recorrido" UNIQUE ("idRecorrido"),
    CONSTRAINT "uq_gfis_cierre_clave_propuesta" UNIQUE ("claveIdempotenciaPropuesta"),
    CONSTRAINT "uq_gfis_cierre_clave_idemp" UNIQUE ("claveIdempotenciaCierre"),
    CONSTRAINT "fk_gfis_cierre_recorrido" FOREIGN KEY ("idRecorrido")
        REFERENCES "RECORRIDO_REPARTO" ("idRecorrido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_cierre_admin" FOREIGN KEY ("idUsuarioAdministrador")
        REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_cierre_admin_res" FOREIGN KEY ("idUsuarioResolucionAdmin")
        REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_cierre_estado" CHECK (
        "estadoCierre" IN (
            'PENDIENTE_CONCILIACION',
            'CONFIRMADO',
            'CERRADO_CON_DIFERENCIA_RESUELTA'
        )
    ),
    CONSTRAINT "ck_gfis_cierre_tipo_resolucion" CHECK (
        "tipoResolucionAdmin" IS NULL
        OR "tipoResolucionAdmin" IN (
            'FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO',
            'CUSTODIA_DEVUELTA_FISICAMENTE',
            'MIXTA_FALTANTE_Y_CUSTODIA'
        )
    ),
    CONSTRAINT "ck_gfis_cierre_d1_solo_efectivo" CHECK (
        "medioPagoPermitido" = 'EFECTIVO'
    ),
    CONSTRAINT "ck_gfis_cierre_montos_no_negativos" CHECK (
        "importeEsperado" >= 0
        AND "importeEfectivoRecibido" >= 0
        AND "importeImputadoPropuesto" >= 0
        AND "importeImputadoConfirmado" >= 0
        AND "importeDiferenciaFaltante" >= 0
        AND "importeCustodiaNoImputada" >= 0
        AND "importeImputadoPropuesto" + "importeCustodiaNoImputada" = "importeEfectivoRecibido"
    ),
    -- Bloqueante 1 (T1-C2) + Bloqueante 4 + Alternativa A (revisiones versionadas):
    -- En PENDIENTE_CONCILIACION: importeImputadoConfirmado=0, sin clave/huella/recibo definitivo T1.
    -- En CERRADO_CON_DIFERENCIA_RESUELTA: requiere idRevisionVigente (confirmada en T1-C2).
    CONSTRAINT "ck_gfis_cierre_coherencia_estado_y_diferencias" CHECK (
        (
            "estadoCierre" = 'CONFIRMADO'
            AND "idRevisionVigente" IS NULL
            AND "claveIdempotenciaCierre" IS NOT NULL
            AND "versionHuella" IS NOT NULL
            AND "huellaCanonica" IS NOT NULL
            AND "reciboHistoricoJson" IS NOT NULL
            AND "importeImputadoConfirmado" = "importeImputadoPropuesto"
            AND "importeDiferenciaFaltante" = 0
            AND "importeCustodiaNoImputada" = 0
            AND "idUsuarioResolucionAdmin" IS NULL
            AND "tipoResolucionAdmin" IS NULL
            AND "motivoResolucionAdmin" IS NULL
            AND "fechaHoraResolucion" IS NULL
        )
        OR (
            "estadoCierre" = 'PENDIENTE_CONCILIACION'
            AND "claveIdempotenciaPropuesta" IS NOT NULL
            AND "claveIdempotenciaCierre" IS NULL
            AND "versionHuella" IS NULL
            AND "huellaCanonica" IS NULL
            AND "reciboHistoricoJson" IS NULL
            AND "importeImputadoConfirmado" = 0
            AND ("importeDiferenciaFaltante" > 0 OR "importeCustodiaNoImputada" > 0)
            AND length(btrim(coalesce("motivoDiferencia", ''))) > 0
            AND "idUsuarioResolucionAdmin" IS NULL
            AND "tipoResolucionAdmin" IS NULL
            AND "motivoResolucionAdmin" IS NULL
            AND "fechaHoraResolucion" IS NULL
        )
        OR (
            "estadoCierre" = 'CERRADO_CON_DIFERENCIA_RESUELTA'
            AND "idRevisionVigente" IS NOT NULL
            AND "claveIdempotenciaCierre" IS NOT NULL
            AND "versionHuella" IS NOT NULL
            AND "huellaCanonica" IS NOT NULL
            AND "reciboHistoricoJson" IS NOT NULL
            AND "importeImputadoConfirmado" = "importeImputadoPropuesto"
            AND ("importeDiferenciaFaltante" > 0 OR "importeCustodiaNoImputada" > 0)
            AND length(btrim(coalesce("motivoDiferencia", ''))) > 0
            AND "idUsuarioResolucionAdmin" IS NOT NULL
            AND "tipoResolucionAdmin" IS NOT NULL
            AND length(btrim(coalesce("motivoResolucionAdmin", ''))) > 0
            AND "fechaHoraResolucion" IS NOT NULL
            AND (
                ("tipoResolucionAdmin" = 'FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO'
                    AND "importeDiferenciaFaltante" > 0 AND "importeCustodiaNoImputada" = 0)
                OR ("tipoResolucionAdmin" = 'CUSTODIA_DEVUELTA_FISICAMENTE'
                    AND "importeCustodiaNoImputada" > 0 AND "importeDiferenciaFaltante" = 0)
                OR ("tipoResolucionAdmin" = 'MIXTA_FALTANTE_Y_CUSTODIA'
                    AND "importeDiferenciaFaltante" > 0 AND "importeCustodiaNoImputada" > 0)
            )
        )
    )
);

-- Alternativa A (CANDIDATA, NO RATIFICADA): historial append-only de revisiones
-- de conciliacion pendiente sobre una unica cabecera CIERRE_RECORRIDO_PE2.
-- Permite rectificar errores materiales (A-D), reintentos idempotentes (F) e
-- invalidacion sin borrado historico (H), bloqueando rectificaciones post-cierre (G)
-- y preservando 0 efectos financieros antes del COMMIT T1-C2.
CREATE TABLE "REVISION_CONCILIACION_PE2" (
    "idRevisionConciliacion" BIGINT NOT NULL DEFAULT nextval('"seq_gfis_revision_conciliacion"'),
    "idCierreRecorrido" BIGINT NOT NULL,
    "numeroRevision" INTEGER NOT NULL,
    "idRevisionAnterior" BIGINT,
    "estadoRevision" VARCHAR(25) NOT NULL DEFAULT 'VIGENTE',
    "claveIdempotenciaRevision" UUID NOT NULL,
    "versionHuella" VARCHAR(20) NOT NULL,
    "huellaCanonica" VARCHAR(128) NOT NULL,
    "idUsuarioAdmin" BIGINT NOT NULL,
    "fechaHoraRevision" TIMESTAMPTZ NOT NULL,
    "importeEsperado" NUMERIC(18,2) NOT NULL,
    "importeEfectivoRecibido" NUMERIC(18,2) NOT NULL,
    "importeImputadoPropuesto" NUMERIC(18,2) NOT NULL,
    "importeDiferenciaFaltante" NUMERIC(18,2) NOT NULL,
    "importeCustodiaNoImputada" NUMERIC(18,2) NOT NULL,
    "motivoRevision" TEXT NOT NULL,
    "idUsuarioInvalidacion" BIGINT,
    "motivoInvalidacion" TEXT,
    "fechaHoraInvalidacion" TIMESTAMPTZ,
    CONSTRAINT "pk_gfis_revision_conciliacion" PRIMARY KEY ("idRevisionConciliacion"),
    CONSTRAINT "uq_gfis_rev_cierre_numero" UNIQUE ("idCierreRecorrido", "numeroRevision"),
    CONSTRAINT "uq_gfis_rev_clave_idemp" UNIQUE ("claveIdempotenciaRevision"),
    CONSTRAINT "fk_gfis_rev_cierre" FOREIGN KEY ("idCierreRecorrido")
        REFERENCES "CIERRE_RECORRIDO_PE2" ("idCierreRecorrido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_rev_anterior" FOREIGN KEY ("idRevisionAnterior")
        REFERENCES "REVISION_CONCILIACION_PE2" ("idRevisionConciliacion") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_rev_admin" FOREIGN KEY ("idUsuarioAdmin")
        REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_rev_admin_inval" FOREIGN KEY ("idUsuarioInvalidacion")
        REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_rev_numero_pos" CHECK ("numeroRevision" > 0),
    CONSTRAINT "ck_gfis_rev_cadena" CHECK (
        ("numeroRevision" = 1 AND "idRevisionAnterior" IS NULL)
        OR ("numeroRevision" > 1 AND "idRevisionAnterior" IS NOT NULL)
    ),
    CONSTRAINT "ck_gfis_rev_estado" CHECK (
        "estadoRevision" IN ('VIGENTE', 'SUPERADA', 'INVALIDADA', 'CONFIRMADA_T1')
    ),
    CONSTRAINT "ck_gfis_rev_montos" CHECK (
        "importeEsperado" >= 0
        AND "importeEfectivoRecibido" >= 0
        AND "importeImputadoPropuesto" >= 0
        AND "importeDiferenciaFaltante" >= 0
        AND "importeCustodiaNoImputada" >= 0
        AND "importeImputadoPropuesto" + "importeCustodiaNoImputada" = "importeEfectivoRecibido"
        AND ("importeDiferenciaFaltante" > 0 OR "importeCustodiaNoImputada" > 0)
    ),
    CONSTRAINT "ck_gfis_rev_motivo" CHECK (length(btrim("motivoRevision")) > 0),
    CONSTRAINT "ck_gfis_rev_huella" CHECK (length(btrim("huellaCanonica")) > 0),
    CONSTRAINT "ck_gfis_rev_inval" CHECK (
        ("estadoRevision" <> 'INVALIDADA'
            AND "idUsuarioInvalidacion" IS NULL
            AND "motivoInvalidacion" IS NULL
            AND "fechaHoraInvalidacion" IS NULL)
        OR ("estadoRevision" = 'INVALIDADA'
            AND "idUsuarioInvalidacion" IS NOT NULL
            AND length(btrim(coalesce("motivoInvalidacion", ''))) > 0
            AND "fechaHoraInvalidacion" IS NOT NULL)
    )
);

-- A lo sumo 1 revision VIGENTE y a lo sumo 1 revision CONFIRMADA_T1 por cierre.
CREATE UNIQUE INDEX "uq_gfis_rev_unica_vigente"
    ON "REVISION_CONCILIACION_PE2" ("idCierreRecorrido")
    WHERE "estadoRevision" = 'VIGENTE';

CREATE UNIQUE INDEX "uq_gfis_rev_unica_confirmada"
    ON "REVISION_CONCILIACION_PE2" ("idCierreRecorrido")
    WHERE "estadoRevision" = 'CONFIRMADA_T1';

ALTER TABLE "CIERRE_RECORRIDO_PE2"
    ADD CONSTRAINT "fk_gfis_cierre_rev_vigente"
    FOREIGN KEY ("idRevisionVigente")
    REFERENCES "REVISION_CONCILIACION_PE2" ("idRevisionConciliacion")
    ON UPDATE RESTRICT ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION "fn_gfis_validar_revision_conciliacion"()
RETURNS trigger LANGUAGE plpgsql AS $rev$
DECLARE
    v_estado_cierre VARCHAR(40);
    v_prev record;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'T1-C2/REV: el historial de revisiones de conciliacion es inmutable y no se elimina';
    END IF;

    SELECT "estadoCierre" INTO v_estado_cierre
      FROM "CIERRE_RECORRIDO_PE2"
     WHERE "idCierreRecorrido" = NEW."idCierreRecorrido";

    IF TG_OP = 'INSERT' THEN
        IF v_estado_cierre <> 'PENDIENTE_CONCILIACION' THEN
            RAISE EXCEPTION 'T1-C2/REV: prohibido registrar revisiones sobre cierre en estado %', v_estado_cierre;
        END IF;
        IF NEW."estadoRevision" <> 'VIGENTE' THEN
            RAISE EXCEPTION 'T1-C2/REV: toda nueva revision debe nacer en estado VIGENTE';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM "USUARIO" u JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
             WHERE u."idUsuario" = NEW."idUsuarioAdmin"
               AND u."estado" = 'HABILITADO'
               AND ur."codigoRol" = 'ADMINISTRADOR'
        ) THEN
            RAISE EXCEPTION 'T1-C2/REV/RBAC: solo ADMINISTRADOR habilitado puede emitir revisiones de conciliacion';
        END IF;
        IF NEW."numeroRevision" > 1 THEN
            SELECT "idCierreRecorrido", "numeroRevision", "estadoRevision"
              INTO v_prev
              FROM "REVISION_CONCILIACION_PE2"
             WHERE "idRevisionConciliacion" = NEW."idRevisionAnterior";
            IF NOT FOUND
               OR v_prev."idCierreRecorrido" <> NEW."idCierreRecorrido"
               OR v_prev."numeroRevision" <> NEW."numeroRevision" - 1
               OR v_prev."estadoRevision" NOT IN ('SUPERADA', 'INVALIDADA') THEN
                RAISE EXCEPTION 'T1-C2/REV: idRevisionAnterior invalida o no superada/invalidada para revision %', NEW."numeroRevision";
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    -- UPDATE: solo transiciones desde VIGENTE hacia SUPERADA / INVALIDADA / CONFIRMADA_T1
    IF OLD."estadoRevision" <> 'VIGENTE' THEN
        RAISE EXCEPTION 'T1-C2/REV: revision en estado % es inmutable', OLD."estadoRevision";
    END IF;
    IF NEW."estadoRevision" = 'VIGENTE' THEN
        RAISE EXCEPTION 'T1-C2/REV: actualizacion redundante sin cambio de estado de revision';
    END IF;
    IF ROW(NEW."idRevisionConciliacion", NEW."idCierreRecorrido", NEW."numeroRevision",
           NEW."idRevisionAnterior", NEW."claveIdempotenciaRevision", NEW."versionHuella",
           NEW."huellaCanonica", NEW."idUsuarioAdmin", NEW."fechaHoraRevision",
           NEW."importeEsperado", NEW."importeEfectivoRecibido", NEW."importeImputadoPropuesto",
           NEW."importeDiferenciaFaltante", NEW."importeCustodiaNoImputada", NEW."motivoRevision")
       IS DISTINCT FROM
       ROW(OLD."idRevisionConciliacion", OLD."idCierreRecorrido", OLD."numeroRevision",
           OLD."idRevisionAnterior", OLD."claveIdempotenciaRevision", OLD."versionHuella",
           OLD."huellaCanonica", OLD."idUsuarioAdmin", OLD."fechaHoraRevision",
           OLD."importeEsperado", OLD."importeEfectivoRecibido", OLD."importeImputadoPropuesto",
           OLD."importeDiferenciaFaltante", OLD."importeCustodiaNoImputada", OLD."motivoRevision") THEN
        RAISE EXCEPTION 'T1-C2/REV: prohibido reescribir datos historicos de una revision existente';
    END IF;
    IF NEW."estadoRevision" IN ('SUPERADA', 'INVALIDADA') AND v_estado_cierre <> 'PENDIENTE_CONCILIACION' THEN
        RAISE EXCEPTION 'T1-C2/REV: no se puede superar ni invalidar revision tras cierre terminal';
    END IF;
    IF NEW."estadoRevision" = 'INVALIDADA' AND NOT EXISTS (
        SELECT 1 FROM "USUARIO" u JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
         WHERE u."idUsuario" = NEW."idUsuarioInvalidacion"
           AND u."estado" = 'HABILITADO'
           AND ur."codigoRol" = 'ADMINISTRADOR'
    ) THEN
        RAISE EXCEPTION 'T1-C2/REV/RBAC: solo ADMINISTRADOR habilitado puede invalidar una revision';
    END IF;

    RETURN NEW;
END
$rev$;

CREATE TRIGGER "tg_gfis_validar_revision_conciliacion"
    BEFORE INSERT OR UPDATE OR DELETE ON "REVISION_CONCILIACION_PE2"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_validar_revision_conciliacion"();

CREATE OR REPLACE FUNCTION "fn_gfis_validar_admin_y_cierre"()
RETURNS trigger LANGUAGE plpgsql AS $cierre$
DECLARE
    v_rev record;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'PE-2: cierre de recorrido es evidencia auditable y no se elimina';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM "USUARIO" u
        JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
        WHERE u."idUsuario" = NEW."idUsuarioAdministrador"
          AND u."estado" = 'HABILITADO'
          AND ur."codigoRol" = 'ADMINISTRADOR'
    ) THEN
        RAISE EXCEPTION 'PE-2/RBAC: solo ADMINISTRADOR habilitado puede registrar cierre de rendicion';
    END IF;

    IF NEW."idUsuarioResolucionAdmin" IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM "USUARIO" u
        JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
        WHERE u."idUsuario" = NEW."idUsuarioResolucionAdmin"
          AND u."estado" = 'HABILITADO'
          AND ur."codigoRol" = 'ADMINISTRADOR'
    ) THEN
        RAISE EXCEPTION 'PE-2/RBAC: solo ADMINISTRADOR habilitado puede resolver diferencias';
    END IF;

    -- FA-2 (PROPUESTA, NO RATIFICADA): no cerrar ni iniciar conciliacion con Pedidos PENDIENTE activos
    IF EXISTS (
        SELECT 1
          FROM "RECORRIDO_ASIGNACION" ra
         WHERE ra."idRecorrido" = NEW."idRecorrido"
           AND ra."estadoPertenencia" = 'ACTIVA'
    ) THEN
        RAISE EXCEPTION 'FRONTERA-A/FA-2: existen Pedidos PENDIENTE activos en el Recorrido %; deben resolverse antes del cierre',
            NEW."idRecorrido";
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW."estadoCierre" = 'CERRADO_CON_DIFERENCIA_RESUELTA' THEN
            RAISE EXCEPTION 'PE-2/T1-C2: cierre con diferencia debe registrarse primero en PENDIENTE_CONCILIACION antes de la confirmacion T1 autorizada';
        END IF;
        RETURN NEW;
    END IF;

    -- TG_OP = 'UPDATE': inmutabilidad estricta de estados terminales
    IF OLD."estadoCierre" IN ('CONFIRMADO', 'CERRADO_CON_DIFERENCIA_RESUELTA') THEN
        RAISE EXCEPTION 'PE-2/C1-a: cierre terminal (%) y su recibo historico son inmutables', OLD."estadoCierre";
    END IF;

    -- Desde PENDIENTE_CONCILIACION: prohibido pasar a CONFIRMADO sin decision comercial expresa
    IF OLD."estadoCierre" = 'PENDIENTE_CONCILIACION' AND NEW."estadoCierre" = 'CONFIRMADO' THEN
        RAISE EXCEPTION 'PE-2/T1-C2: prohibido blanquear PENDIENTE_CONCILIACION hacia CONFIRMADO';
    END IF;

    -- Identidad y origen de la cabecera jamas se alteran
    IF ROW(NEW."idCierreRecorrido", NEW."idRecorrido", NEW."claveIdempotenciaPropuesta",
           NEW."idUsuarioAdministrador", NEW."fechaHoraRegistro", NEW."medioPagoPermitido")
       IS DISTINCT FROM
       ROW(OLD."idCierreRecorrido", OLD."idRecorrido", OLD."claveIdempotenciaPropuesta",
           OLD."idUsuarioAdministrador", OLD."fechaHoraRegistro", OLD."medioPagoPermitido") THEN
        RAISE EXCEPTION 'PE-2/T1-C2: prohibido mutar identidad u origen de la cabecera de cierre';
    END IF;

    -- Caso A: actualizacion de cabecera en PENDIENTE_CONCILIACION (solo respaldada por REVISION_CONCILIACION_PE2)
    IF OLD."estadoCierre" = 'PENDIENTE_CONCILIACION' AND NEW."estadoCierre" = 'PENDIENTE_CONCILIACION' THEN
        IF NEW."idRevisionVigente" IS NULL THEN
            -- Solo valido al invalidar la revision vigente (Escenario H), sin reescribir montos
            IF OLD."idRevisionVigente" IS NULL
               OR NOT EXISTS (
                   SELECT 1 FROM "REVISION_CONCILIACION_PE2"
                    WHERE "idRevisionConciliacion" = OLD."idRevisionVigente"
                      AND "estadoRevision" = 'INVALIDADA'
               ) THEN
                RAISE EXCEPTION 'PE-2/REV: solo se puede desvincular idRevisionVigente tras invalidar la revision vigente';
            END IF;
            IF ROW(NEW."importeEsperado", NEW."importeEfectivoRecibido", NEW."importeImputadoPropuesto",
                   NEW."importeDiferenciaFaltante", NEW."importeCustodiaNoImputada", NEW."motivoDiferencia")
               IS DISTINCT FROM
               ROW(OLD."importeEsperado", OLD."importeEfectivoRecibido", OLD."importeImputadoPropuesto",
                   OLD."importeDiferenciaFaltante", OLD."importeCustodiaNoImputada", OLD."motivoDiferencia") THEN
                RAISE EXCEPTION 'PE-2/REV: invalidar no puede reescribir silenciosamente montos de cabecera';
            END IF;
            RETURN NEW;
        END IF;

        -- Nueva revision VIGENTE: exige coincidencia exacta con REVISION_CONCILIACION_PE2 (cero mutacion silenciosa)
        SELECT * INTO v_rev
          FROM "REVISION_CONCILIACION_PE2"
         WHERE "idRevisionConciliacion" = NEW."idRevisionVigente";

        IF NOT FOUND
           OR v_rev."idCierreRecorrido" <> OLD."idCierreRecorrido"
           OR v_rev."estadoRevision" <> 'VIGENTE' THEN
            RAISE EXCEPTION 'PE-2/REV: idRevisionVigente debe apuntar a una revision VIGENTE de este cierre';
        END IF;

        IF ROW(NEW."importeEsperado", NEW."importeEfectivoRecibido", NEW."importeImputadoPropuesto",
               NEW."importeDiferenciaFaltante", NEW."importeCustodiaNoImputada", NEW."motivoDiferencia")
           IS DISTINCT FROM
           ROW(v_rev."importeEsperado", v_rev."importeEfectivoRecibido", v_rev."importeImputadoPropuesto",
               v_rev."importeDiferenciaFaltante", v_rev."importeCustodiaNoImputada", v_rev."motivoRevision") THEN
            RAISE EXCEPTION 'PE-2/REV: prohibido reescribir montos o motivo de cabecera sin una revision auditada coincidente';
        END IF;
        RETURN NEW;
    END IF;

    -- Caso B: transicion definitiva PENDIENTE_CONCILIACION -> CERRADO_CON_DIFERENCIA_RESUELTA (T1-C2)
    IF OLD."idRevisionVigente" IS NULL OR NEW."idRevisionVigente" IS DISTINCT FROM OLD."idRevisionVigente" THEN
        RAISE EXCEPTION 'PE-2/T1-C2: confirmacion definitiva exige una revision VIGENTE activa (no invalidada)';
    END IF;

    IF ROW(NEW."importeEsperado", NEW."importeEfectivoRecibido",
           NEW."importeImputadoPropuesto", NEW."importeDiferenciaFaltante",
           NEW."importeCustodiaNoImputada", NEW."motivoDiferencia")
       IS DISTINCT FROM
       ROW(OLD."importeEsperado", OLD."importeEfectivoRecibido",
           OLD."importeImputadoPropuesto", OLD."importeDiferenciaFaltante",
           OLD."importeCustodiaNoImputada", OLD."motivoDiferencia") THEN
        RAISE EXCEPTION 'PE-2/T1-C2: la confirmacion definitiva no puede alterar montos ni motivo de la revision vigente';
    END IF;

    RETURN NEW;
END
$cierre$;

CREATE TRIGGER "tg_gfis_validar_admin_y_cierre"
    BEFORE INSERT OR UPDATE OR DELETE ON "CIERRE_RECORRIDO_PE2"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_validar_admin_y_cierre"();

-- Planificacion NO financiera de aplicaciones vinculada a cada revision de conciliacion
-- (G-ARQ-N2-01 s8: A_e propuesto es planificacion sin Cobro/PAGO/Caja/extincion V_e).
CREATE TABLE "PROPUESTA_APLICACION_PENDIENTE" (
    "idPropuestaApp" BIGINT NOT NULL DEFAULT nextval('"seq_gfis_propuesta_app"'),
    "idCierreRecorrido" BIGINT NOT NULL,
    "idRevisionConciliacion" BIGINT NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "idPedido" BIGINT NOT NULL,
    "importePropuesto" NUMERIC(18,2) NOT NULL,
    "fechaHoraRegistro" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_gfis_propuesta_app" PRIMARY KEY ("idPropuestaApp"),
    CONSTRAINT "uq_gfis_propuesta_rev_pedido" UNIQUE ("idRevisionConciliacion", "idPedido"),
    CONSTRAINT "fk_gfis_propuesta_cierre" FOREIGN KEY ("idCierreRecorrido")
        REFERENCES "CIERRE_RECORRIDO_PE2" ("idCierreRecorrido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_propuesta_revision" FOREIGN KEY ("idRevisionConciliacion")
        REFERENCES "REVISION_CONCILIACION_PE2" ("idRevisionConciliacion") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_propuesta_pe1_cliente" FOREIGN KEY ("idPedido", "idCliente")
        REFERENCES "RECONOCIMIENTO_ENTREGA_PE1" ("idPedido", "idCliente") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_propuesta_positiva" CHECK ("importePropuesto" > 0)
);

CREATE OR REPLACE FUNCTION "fn_gfis_validar_propuesta_pendiente"()
RETURNS trigger LANGUAGE plpgsql AS $prop$
DECLARE
    v_estado VARCHAR(40);
    v_rev record;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'T1-C2: las propuestas de aplicacion pendientes son evidencia historica inmutable';
    END IF;

    SELECT "estadoCierre" INTO v_estado
      FROM "CIERRE_RECORRIDO_PE2"
     WHERE "idCierreRecorrido" = NEW."idCierreRecorrido";

    IF v_estado <> 'PENDIENTE_CONCILIACION' THEN
        RAISE EXCEPTION 'T1-C2: solo se registran propuestas de planificacion sobre un cierre en PENDIENTE_CONCILIACION';
    END IF;

    SELECT "idCierreRecorrido", "estadoRevision" INTO v_rev
      FROM "REVISION_CONCILIACION_PE2"
     WHERE "idRevisionConciliacion" = NEW."idRevisionConciliacion";

    IF NOT FOUND OR v_rev."idCierreRecorrido" <> NEW."idCierreRecorrido" OR v_rev."estadoRevision" <> 'VIGENTE' THEN
        RAISE EXCEPTION 'T1-C2/REV: la propuesta debe pertenecer a la revision VIGENTE del cierre';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM "CIERRE_RECORRIDO_PE2" cr
        JOIN "RECORRIDO_ENTREGA" re ON re."idRecorrido" = cr."idRecorrido"
        WHERE cr."idCierreRecorrido" = NEW."idCierreRecorrido"
          AND re."idPedido" = NEW."idPedido"
    ) THEN
        RAISE EXCEPTION 'C1/T1-C2: la Entrega propuesta no pertenece al Recorrido';
    END IF;

    RETURN NEW;
END
$prop$;

CREATE TRIGGER "tg_gfis_validar_propuesta_pendiente"
    BEFORE INSERT OR UPDATE OR DELETE ON "PROPUESTA_APLICACION_PENDIENTE"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_validar_propuesta_pendiente"();

-- K2: a lo sumo 1 COBRO REPARTO (EFECTIVO, CONFIRMADO, >0) por Cliente y Cierre.
-- PROHIBIDO insertar mientras CIERRE_RECORRIDO_PE2 este en PENDIENTE_CONCILIACION!
CREATE TABLE "COBRO_RENDICION_K2" (
    "idCierreRecorrido" BIGINT NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "idCobro" BIGINT NOT NULL,
    CONSTRAINT "pk_gfis_cobro_k2" PRIMARY KEY ("idCierreRecorrido", "idCliente"),
    CONSTRAINT "uq_gfis_cobro_k2_cobro" UNIQUE ("idCobro"),
    CONSTRAINT "fk_gfis_cobro_k2_cierre" FOREIGN KEY ("idCierreRecorrido")
        REFERENCES "CIERRE_RECORRIDO_PE2" ("idCierreRecorrido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_cobro_k2_cobro_cliente" FOREIGN KEY ("idCobro", "idCliente")
        REFERENCES "COBRO" ("idCobro", "idCliente") ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE OR REPLACE FUNCTION "fn_gfis_validar_cobro_k2"()
RETURNS trigger LANGUAGE plpgsql AS $k2$
DECLARE
    v_cobro record;
    v_cierre record;
    v_suma_previa NUMERIC(18,2);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'K2: el vinculo Cierre-Cobro es inmutable';
    END IF;

    SELECT "estadoCierre", "importeImputadoConfirmado"
      INTO v_cierre
      FROM "CIERRE_RECORRIDO_PE2"
     WHERE "idCierreRecorrido" = NEW."idCierreRecorrido";

    IF v_cierre."estadoCierre" = 'PENDIENTE_CONCILIACION' THEN
        RAISE EXCEPTION 'T1-C2: prohibido emitir COBRO_RENDICION_K2 en estado PENDIENTE_CONCILIACION (solo en COMMIT T1 autorizado)';
    END IF;

    SELECT "origen", "medioPago", "estadoCobro", "importe"
      INTO v_cobro
      FROM "COBRO"
     WHERE "idCobro" = NEW."idCobro";

    IF NOT FOUND THEN
        RAISE EXCEPTION 'K2: Cobro inexistente';
    END IF;
    IF v_cobro."origen" <> 'REPARTO' THEN
        RAISE EXCEPTION 'K2: el Cobro de rendicion debe tener origen REPARTO';
    END IF;
    IF v_cobro."medioPago" <> 'EFECTIVO' THEN
        RAISE EXCEPTION 'D1: el primer circuito de rendicion admite exclusivamente EFECTIVO';
    END IF;
    IF v_cobro."estadoCobro" <> 'CONFIRMADO' THEN
        RAISE EXCEPTION 'F01/K2: el Cobro de rendicion debe nacer CONFIRMADO';
    END IF;

    SELECT COALESCE(SUM(c."importe"), 0) INTO v_suma_previa
      FROM "COBRO_RENDICION_K2" ck
      JOIN "COBRO" c ON c."idCobro" = ck."idCobro"
     WHERE ck."idCierreRecorrido" = NEW."idCierreRecorrido";

    IF v_suma_previa + v_cobro."importe" > v_cierre."importeImputadoConfirmado" THEN
        RAISE EXCEPTION 'T1-C2/K2: suma de Cobros (% + %) excede importeImputadoConfirmado (%) del cierre',
            v_suma_previa, v_cobro."importe", v_cierre."importeImputadoConfirmado";
    END IF;

    RETURN NEW;
END
$k2$;

CREATE TRIGGER "tg_gfis_validar_cobro_k2"
    BEFORE INSERT OR UPDATE OR DELETE ON "COBRO_RENDICION_K2"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_validar_cobro_k2"();

-- Aplicaciones K2 por Entrega (1..N por cada COBRO_RENDICION_K2 del mismo Cliente).
-- PROHIBIDO insertar mientras CIERRE_RECORRIDO_PE2 este en PENDIENTE_CONCILIACION!
CREATE TABLE "APLICACION_RENDICION_K2" (
    "idAplicacionK2" BIGINT NOT NULL DEFAULT nextval('"seq_gfis_aplicacion_k2"'),
    "idCierreRecorrido" BIGINT NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "idPedido" BIGINT NOT NULL,
    "idCobro" BIGINT NOT NULL,
    "importeAplicado" NUMERIC(18,2) NOT NULL,
    "fechaHoraAplicacion" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_gfis_aplicacion_k2" PRIMARY KEY ("idAplicacionK2"),
    CONSTRAINT "uq_gfis_aplicacion_cierre_pedido" UNIQUE ("idCierreRecorrido", "idPedido"),
    CONSTRAINT "fk_gfis_aplicacion_cobro_k2" FOREIGN KEY ("idCierreRecorrido", "idCliente")
        REFERENCES "COBRO_RENDICION_K2" ("idCierreRecorrido", "idCliente") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_aplicacion_cobro_cliente" FOREIGN KEY ("idCobro", "idCliente")
        REFERENCES "COBRO" ("idCobro", "idCliente") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_aplicacion_pe1_cliente" FOREIGN KEY ("idPedido", "idCliente")
        REFERENCES "RECONOCIMIENTO_ENTREGA_PE1" ("idPedido", "idCliente") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_aplicacion_positiva" CHECK ("importeAplicado" > 0)
);

CREATE OR REPLACE FUNCTION "fn_gfis_aplicacion_k2_inmutable"()
RETURNS trigger LANGUAGE plpgsql AS $app_inm$
DECLARE
    v_cobro_k2 BIGINT;
    v_estado_cierre VARCHAR(40);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'K2: las aplicaciones de rendicion son inmutables (D7-B diferida)';
    END IF;

    SELECT "estadoCierre" INTO v_estado_cierre
      FROM "CIERRE_RECORRIDO_PE2"
     WHERE "idCierreRecorrido" = NEW."idCierreRecorrido";

    IF v_estado_cierre = 'PENDIENTE_CONCILIACION' THEN
        RAISE EXCEPTION 'T1-C2: prohibido registrar aplicaciones financieras definitivas en PENDIENTE_CONCILIACION';
    END IF;

    SELECT "idCobro" INTO v_cobro_k2
      FROM "COBRO_RENDICION_K2"
     WHERE "idCierreRecorrido" = NEW."idCierreRecorrido"
       AND "idCliente" = NEW."idCliente";

    IF v_cobro_k2 IS DISTINCT FROM NEW."idCobro" THEN
        RAISE EXCEPTION 'K2: idCobro de aplicacion no coincide con COBRO_RENDICION_K2 del Cliente';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM "CIERRE_RECORRIDO_PE2" cr
        JOIN "RECORRIDO_ENTREGA" re ON re."idRecorrido" = cr."idRecorrido"
        WHERE cr."idCierreRecorrido" = NEW."idCierreRecorrido"
          AND re."idPedido" = NEW."idPedido"
    ) THEN
        RAISE EXCEPTION 'C1/K2: la Entrega no pertenece al Recorrido del cierre';
    END IF;

    RETURN NEW;
END
$app_inm$;

CREATE TRIGGER "tg_gfis_aplicacion_k2_inmutable"
    BEFORE INSERT OR UPDATE OR DELETE ON "APLICACION_RENDICION_K2"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_aplicacion_k2_inmutable"();

-- Custodia y diferencias segregadas de COBRO y MOVIMIENTO_CAJA de ventas (S08, RD-F04-002/005)
CREATE TABLE "CUSTODIA_DIFERENCIA_RENDICION" (
    "idEventoCustodia" BIGINT NOT NULL DEFAULT nextval('"seq_gfis_custodia_diferencia"'),
    "idCierreRecorrido" BIGINT NOT NULL,
    "idRevisionConciliacion" BIGINT,
    "idPedido" BIGINT,
    "idCliente" BIGINT,
    "tipoEvento" VARCHAR(30) NOT NULL,
    "importe" NUMERIC(18,2) NOT NULL,
    "ubicacionFisica" VARCHAR(80) NOT NULL DEFAULT 'CAJA_CUSTODIA_ADMIN',
    "motivo" TEXT NOT NULL,
    "idUsuarioAdmin" BIGINT NOT NULL,
    "fechaHoraRegistro" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_gfis_custodia" PRIMARY KEY ("idEventoCustodia"),
    CONSTRAINT "fk_gfis_custodia_cierre" FOREIGN KEY ("idCierreRecorrido")
        REFERENCES "CIERRE_RECORRIDO_PE2" ("idCierreRecorrido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_custodia_revision" FOREIGN KEY ("idRevisionConciliacion")
        REFERENCES "REVISION_CONCILIACION_PE2" ("idRevisionConciliacion") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_custodia_entrega" FOREIGN KEY ("idPedido")
        REFERENCES "ENTREGA" ("idPedido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_custodia_cliente" FOREIGN KEY ("idCliente")
        REFERENCES "CLIENTE" ("idCliente") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_custodia_admin" FOREIGN KEY ("idUsuarioAdmin")
        REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_custodia_tipo" CHECK (
        "tipoEvento" IN (
            'FALTANTE_EFECTIVO',
            'CUSTODIA_NO_IMPUTADA',
            'SOBRANTE_DECLARADO_EX01'
        )
    ),
    CONSTRAINT "ck_gfis_custodia_importe" CHECK ("importe" > 0),
    CONSTRAINT "ck_gfis_custodia_motivo" CHECK (length(btrim("motivo")) > 0),
    CONSTRAINT "ck_gfis_custodia_ubicacion" CHECK (length(btrim("ubicacionFisica")) > 0)
);

CREATE OR REPLACE FUNCTION "fn_gfis_validar_custodia"()
RETURNS trigger LANGUAGE plpgsql AS $cust$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'CUSTODIA: eventos de custodia y diferencia son inmutables';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM "USUARIO" u
        JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
        WHERE u."idUsuario" = NEW."idUsuarioAdmin"
          AND u."estado" = 'HABILITADO'
          AND ur."codigoRol" = 'ADMINISTRADOR'
    ) THEN
        RAISE EXCEPTION 'CUSTODIA/RBAC: solo ADMINISTRADOR habilitado puede registrar custodia/diferencias';
    END IF;
    RETURN NEW;
END
$cust$;

CREATE TRIGGER "tg_gfis_validar_custodia"
    BEFORE INSERT OR UPDATE OR DELETE ON "CUSTODIA_DIFERENCIA_RENDICION"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_validar_custodia"();

-- Bloqueante 4 (E-04): egreso fisico de custodia con recibo auditable y receptor;
-- nunca genera ingreso duplicado en MOVIMIENTO_CAJA ni recupero automatico.
CREATE TABLE "EGRESO_CUSTODIA_RENDICION" (
    "idEgresoCustodia" BIGINT NOT NULL DEFAULT nextval('"seq_gfis_egreso_custodia"'),
    "idEventoCustodia" BIGINT NOT NULL,
    "idCierreRecorrido" BIGINT NOT NULL,
    "tipoEgreso" VARCHAR(40) NOT NULL,
    "importeEgreso" NUMERIC(18,2) NOT NULL,
    "numeroReciboDevolucion" VARCHAR(80) NOT NULL,
    "receptorIdentificacion" VARCHAR(120) NOT NULL,
    "motivo" TEXT NOT NULL,
    "idUsuarioAdmin" BIGINT NOT NULL,
    "fechaHoraEgreso" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_gfis_egreso_custodia" PRIMARY KEY ("idEgresoCustodia"),
    CONSTRAINT "uq_gfis_egreso_recibo" UNIQUE ("numeroReciboDevolucion"),
    CONSTRAINT "fk_gfis_egreso_evento" FOREIGN KEY ("idEventoCustodia")
        REFERENCES "CUSTODIA_DIFERENCIA_RENDICION" ("idEventoCustodia") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_egreso_cierre" FOREIGN KEY ("idCierreRecorrido")
        REFERENCES "CIERRE_RECORRIDO_PE2" ("idCierreRecorrido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_egreso_admin" FOREIGN KEY ("idUsuarioAdmin")
        REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_egreso_tipo" CHECK (
        "tipoEgreso" IN ('DEVOLUCION_FISICA_CON_RECIBO')
    ),
    CONSTRAINT "ck_gfis_egreso_importe" CHECK ("importeEgreso" > 0),
    CONSTRAINT "ck_gfis_egreso_recibo_no_vacio" CHECK (length(btrim("numeroReciboDevolucion")) > 0),
    CONSTRAINT "ck_gfis_egreso_receptor_no_vacio" CHECK (length(btrim("receptorIdentificacion")) > 0),
    CONSTRAINT "ck_gfis_egreso_motivo_no_vacio" CHECK (length(btrim("motivo")) > 0)
);

CREATE OR REPLACE FUNCTION "fn_gfis_validar_egreso_custodia"()
RETURNS trigger LANGUAGE plpgsql AS $ec$
DECLARE
    v_ev record;
    v_rev_vigente BIGINT;
    v_egresado_prev NUMERIC(18,2);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'E04-CUSTODIA: los egresos fisicos de custodia son append-only inmutables';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM "USUARIO" u
        JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
        WHERE u."idUsuario" = NEW."idUsuarioAdmin"
          AND u."estado" = 'HABILITADO'
          AND ur."codigoRol" = 'ADMINISTRADOR'
    ) THEN
        RAISE EXCEPTION 'E04-CUSTODIA/RBAC: solo ADMINISTRADOR habilitado puede registrar egreso de custodia';
    END IF;

    SELECT "idCierreRecorrido", "idRevisionConciliacion", "tipoEvento", "importe"
      INTO v_ev
      FROM "CUSTODIA_DIFERENCIA_RENDICION"
     WHERE "idEventoCustodia" = NEW."idEventoCustodia"
     FOR UPDATE;

    IF NOT FOUND OR v_ev."tipoEvento" <> 'CUSTODIA_NO_IMPUTADA' THEN
        RAISE EXCEPTION 'E04-CUSTODIA: el egreso debe referenciar un evento CUSTODIA_NO_IMPUTADA valido';
    END IF;

    IF v_ev."idCierreRecorrido" IS DISTINCT FROM NEW."idCierreRecorrido" THEN
        RAISE EXCEPTION 'E04-CUSTODIA: idCierreRecorrido del egreso no coincide con el evento de custodia';
    END IF;

    SELECT "idRevisionVigente" INTO v_rev_vigente
      FROM "CIERRE_RECORRIDO_PE2"
     WHERE "idCierreRecorrido" = NEW."idCierreRecorrido";

    IF v_ev."idRevisionConciliacion" IS NOT NULL
       AND v_ev."idRevisionConciliacion" IS DISTINCT FROM v_rev_vigente THEN
        RAISE EXCEPTION 'E04-CUSTODIA/REV: no se puede registrar egreso contra un evento de custodia de revision superada o invalidada';
    END IF;

    SELECT COALESCE(SUM("importeEgreso"), 0) INTO v_egresado_prev
      FROM "EGRESO_CUSTODIA_RENDICION"
     WHERE "idEventoCustodia" = NEW."idEventoCustodia";

    IF v_egresado_prev + NEW."importeEgreso" > v_ev."importe" THEN
        RAISE EXCEPTION 'E04-CUSTODIA: el egreso (% + %) supera la custodia registrada (%)',
            v_egresado_prev, NEW."importeEgreso", v_ev."importe";
    END IF;

    RETURN NEW;
END
$ec$;

CREATE TRIGGER "tg_gfis_validar_egreso_custodia"
    BEFORE INSERT OR UPDATE OR DELETE ON "EGRESO_CUSTODIA_RENDICION"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_validar_egreso_custodia"();

-- Constraint trigger diferido (al COMMIT de T1):
-- 1) Cobro.importe = SUM(aplicaciones del mismo Cierre y Cliente) con >= 1 aplicacion
-- 2) Conservacion exacta A_e + V_e^{vig} = V_e^0 para cada Entrega afectada
-- 3) Suma total confirmada en Cierre = SUM(COBRO.importe) del Cierre (0 en PENDIENTE)
-- 4) En PENDIENTE_CONCILIACION: suma de PROPUESTA_APLICACION_PENDIENTE = importeImputadoPropuesto
-- 5) En CERRADO_CON_DIFERENCIA_RESUELTA con devolucion de custodia (E-04):
--    exige EGRESO_CUSTODIA_RENDICION auditable por exactamente importeCustodiaNoImputada
CREATE OR REPLACE FUNCTION "fn_gfis_verificar_k2_y_conservacion"()
RETURNS trigger LANGUAGE plpgsql AS $chk_k2$
DECLARE
    v_importe_cobro NUMERIC(18,2);
    v_suma_apps NUMERIC(18,2);
    v_cnt_apps BIGINT;
    v_v0 NUMERIC(18,2);
    v_vvig NUMERIC(18,2);
    v_total_cierre NUMERIC(18,2);
    v_suma_cobros NUMERIC(18,2);
    v_suma_propuestas NUMERIC(18,2);
    v_suma_egresos_custodia NUMERIC(18,2);
BEGIN
    IF TG_TABLE_NAME = 'COBRO_RENDICION_K2' THEN
        SELECT "importe" INTO v_importe_cobro FROM "COBRO" WHERE "idCobro" = NEW."idCobro";
        SELECT count(*), COALESCE(SUM("importeAplicado"), 0)
          INTO v_cnt_apps, v_suma_apps
          FROM "APLICACION_RENDICION_K2"
         WHERE "idCierreRecorrido" = NEW."idCierreRecorrido"
           AND "idCliente" = NEW."idCliente";

        IF v_cnt_apps < 1 OR v_suma_apps <> v_importe_cobro THEN
            RAISE EXCEPTION 'K2-CONSERVACION: Cobro % (importe %) no coincide con suma de % aplicaciones (%)',
                NEW."idCobro", v_importe_cobro, v_cnt_apps, v_suma_apps;
        END IF;

        SELECT "importeImputadoConfirmado" INTO v_total_cierre
          FROM "CIERRE_RECORRIDO_PE2"
         WHERE "idCierreRecorrido" = NEW."idCierreRecorrido";
        SELECT COALESCE(SUM(c."importe"), 0) INTO v_suma_cobros
          FROM "COBRO_RENDICION_K2" ck
          JOIN "COBRO" c ON c."idCobro" = ck."idCobro"
         WHERE ck."idCierreRecorrido" = NEW."idCierreRecorrido";
        IF v_suma_cobros <> v_total_cierre THEN
            RAISE EXCEPTION 'T1-CONSERVACION: importeImputadoConfirmado (%) != suma de Cobros K2 (%)',
                v_total_cierre, v_suma_cobros;
        END IF;
    ELSIF TG_TABLE_NAME IN ('APLICACION_RENDICION_K2', 'RECONOCIMIENTO_ENTREGA_PE1') THEN
        SELECT "reconocimientoInicialV0", "reconocimientoVigenteVvig"
          INTO v_v0, v_vvig
          FROM "RECONOCIMIENTO_ENTREGA_PE1"
         WHERE "idPedido" = NEW."idPedido";

        SELECT COALESCE(SUM("importeAplicado"), 0)
          INTO v_suma_apps
          FROM "APLICACION_RENDICION_K2"
         WHERE "idPedido" = NEW."idPedido";

        IF v_suma_apps + v_vvig <> v_v0 THEN
            RAISE EXCEPTION 'PE1-CONSERVACION: pedido % viola A_e (%) + V_e^vig (%) = V_e^0 (%)',
                NEW."idPedido", v_suma_apps, v_vvig, v_v0;
        END IF;
    ELSIF TG_TABLE_NAME = 'CIERRE_RECORRIDO_PE2' THEN
        SELECT COALESCE(SUM(c."importe"), 0) INTO v_suma_cobros
          FROM "COBRO_RENDICION_K2" ck
          JOIN "COBRO" c ON c."idCobro" = ck."idCobro"
         WHERE ck."idCierreRecorrido" = NEW."idCierreRecorrido";
        IF v_suma_cobros <> NEW."importeImputadoConfirmado" THEN
            RAISE EXCEPTION 'T1-CIERRE: importeImputadoConfirmado (%) != suma Cobros K2 (%)',
                NEW."importeImputadoConfirmado", v_suma_cobros;
        END IF;

        IF NEW."estadoCierre" = 'PENDIENTE_CONCILIACION' THEN
            IF NEW."idRevisionVigente" IS NOT NULL THEN
                SELECT COALESCE(SUM("importePropuesto"), 0) INTO v_suma_propuestas
                  FROM "PROPUESTA_APLICACION_PENDIENTE"
                 WHERE "idRevisionConciliacion" = NEW."idRevisionVigente";
                IF v_suma_propuestas <> NEW."importeImputadoPropuesto" THEN
                    RAISE EXCEPTION 'T1-PENDIENTE: importeImputadoPropuesto (%) != suma de propuestas de la revision vigente (%)',
                        NEW."importeImputadoPropuesto", v_suma_propuestas;
                END IF;
            ELSE
                IF NOT EXISTS (
                    SELECT 1 FROM "REVISION_CONCILIACION_PE2"
                     WHERE "idCierreRecorrido" = NEW."idCierreRecorrido"
                       AND "estadoRevision" = 'INVALIDADA'
                ) THEN
                    RAISE EXCEPTION 'T1-PENDIENTE/REV: cierre en PENDIENTE_CONCILIACION requiere revision VIGENTE o evidencia de revision INVALIDADA';
                END IF;
            END IF;
        END IF;

        IF NEW."estadoCierre" = 'CERRADO_CON_DIFERENCIA_RESUELTA' THEN
            IF NOT EXISTS (
                SELECT 1 FROM "REVISION_CONCILIACION_PE2"
                 WHERE "idRevisionConciliacion" = NEW."idRevisionVigente"
                   AND "idCierreRecorrido" = NEW."idCierreRecorrido"
                   AND "estadoRevision" = 'CONFIRMADA_T1'
            ) THEN
                RAISE EXCEPTION 'T1-C2/REV: cierre resuelto debe referenciar una revision en estado CONFIRMADA_T1';
            END IF;

            IF NEW."tipoResolucionAdmin" IN ('CUSTODIA_DEVUELTA_FISICAMENTE', 'MIXTA_FALTANTE_Y_CUSTODIA') THEN
                SELECT COALESCE(SUM(eg."importeEgreso"), 0) INTO v_suma_egresos_custodia
                  FROM "EGRESO_CUSTODIA_RENDICION" eg
                  JOIN "CUSTODIA_DIFERENCIA_RENDICION" cu ON cu."idEventoCustodia" = eg."idEventoCustodia"
                 WHERE eg."idCierreRecorrido" = NEW."idCierreRecorrido"
                   AND eg."tipoEgreso" = 'DEVOLUCION_FISICA_CON_RECIBO'
                   AND (cu."idRevisionConciliacion" IS NULL OR cu."idRevisionConciliacion" = NEW."idRevisionVigente");
                IF v_suma_egresos_custodia <> NEW."importeCustodiaNoImputada" THEN
                    RAISE EXCEPTION 'E04-CUSTODIA: devolucion fisica exige EGRESO_CUSTODIA_RENDICION con recibo por importe exacto % de la revision confirmada (registrado: %)',
                        NEW."importeCustodiaNoImputada", v_suma_egresos_custodia;
                END IF;
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END
$chk_k2$;

CREATE CONSTRAINT TRIGGER "ct_gfis_k2_cobro_suma"
    AFTER INSERT ON "COBRO_RENDICION_K2"
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_verificar_k2_y_conservacion"();

CREATE CONSTRAINT TRIGGER "ct_gfis_k2_aplicacion_conservacion"
    AFTER INSERT ON "APLICACION_RENDICION_K2"
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_verificar_k2_y_conservacion"();

CREATE CONSTRAINT TRIGGER "ct_gfis_pe1_conservacion"
    AFTER INSERT OR UPDATE OF "reconocimientoVigenteVvig" ON "RECONOCIMIENTO_ENTREGA_PE1"
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_verificar_k2_y_conservacion"();

CREATE CONSTRAINT TRIGGER "ct_gfis_cierre_total_k2"
    AFTER INSERT OR UPDATE ON "CIERRE_RECORRIDO_PE2"
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_verificar_k2_y_conservacion"();

-- -----------------------------------------------------------------------------
-- BLOQUE 5: IMP-01 — Libro canonico append-only IMP-B y proyeccion derivada IMP-A
-- -----------------------------------------------------------------------------
CREATE TABLE "EVENTO_IMPUTACION_FINANCIERA" (
    "idEventoImputacion" BIGINT NOT NULL DEFAULT nextval('"seq_gfis_evento_imp"'),
    "idCliente" BIGINT NOT NULL,
    "idCobro" BIGINT NOT NULL,
    "idPedido" BIGINT NOT NULL,
    "idCierreRecorrido" BIGINT,
    "tipoEvento" VARCHAR(30) NOT NULL,
    "importe" NUMERIC(18,2) NOT NULL,
    "idUsuarioOperador" BIGINT NOT NULL,
    "fechaHoraEvento" TIMESTAMPTZ NOT NULL,
    "observacion" TEXT,
    CONSTRAINT "pk_gfis_imp_b" PRIMARY KEY ("idEventoImputacion"),
    CONSTRAINT "fk_gfis_impb_cobro_cliente" FOREIGN KEY ("idCobro", "idCliente")
        REFERENCES "COBRO" ("idCobro", "idCliente") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_impb_pedido_cliente" FOREIGN KEY ("idPedido", "idCliente")
        REFERENCES "PEDIDO" ("idPedido", "idCliente") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_impb_cierre" FOREIGN KEY ("idCierreRecorrido")
        REFERENCES "CIERRE_RECORRIDO_PE2" ("idCierreRecorrido") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_impb_usuario" FOREIGN KEY ("idUsuarioOperador")
        REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_impb_tipo" CHECK (
        "tipoEvento" IN (
            'RESERVA_ANTICIPO',
            'CONSOLIDACION_ANTICIPO',
            'LIBERACION_ANTICIPO',
            'APLICACION_FIFO_LIBRE',
            'APLICACION_RENDICION_K2'
        )
    ),
    CONSTRAINT "ck_gfis_impb_importe" CHECK ("importe" > 0),
    CONSTRAINT "ck_gfis_impb_cierre_req" CHECK (
        ("tipoEvento" = 'APLICACION_RENDICION_K2' AND "idCierreRecorrido" IS NOT NULL)
        OR ("tipoEvento" <> 'APLICACION_RENDICION_K2' AND "idCierreRecorrido" IS NULL)
    )
);

CREATE OR REPLACE FUNCTION "fn_gfis_impb_guardar"()
RETURNS trigger LANGUAGE plpgsql AS $impb$
DECLARE
    v_estado_cobro VARCHAR(20);
    v_importe_cobro NUMERIC(18,2);
    v_reembolso_bloqueante NUMERIC(18,2);
    v_reservado_pedido NUMERIC(18,2);
    v_reservado_total_cobro NUMERIC(18,2);
    v_aplicado_total_cobro NUMERIC(18,2);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'IMP-B: el libro canonico de imputaciones es append-only inmutable';
    END IF;

    SELECT "estadoCobro", "importe"
      INTO v_estado_cobro, v_importe_cobro
      FROM "COBRO"
     WHERE "idCobro" = NEW."idCobro"
     FOR UPDATE;

    IF v_estado_cobro <> 'CONFIRMADO' THEN
        RAISE EXCEPTION 'IMP-B: solo Cobros CONFIRMADOS admiten eventos de imputacion';
    END IF;

    -- RCG-01: un REEMBOLSO RECHAZADO NO veta disponibilidad; PENDIENTE/PROCESADO si descuenta.
    SELECT COALESCE(SUM("importeReembolso"), 0)
      INTO v_reembolso_bloqueante
      FROM "REEMBOLSO"
     WHERE "idCobro" = NEW."idCobro"
       AND "estado" IN ('PENDIENTE', 'PROCESADO');

    SELECT COALESCE(SUM(
        CASE "tipoEvento"
            WHEN 'RESERVA_ANTICIPO' THEN "importe"
            WHEN 'CONSOLIDACION_ANTICIPO' THEN -"importe"
            WHEN 'LIBERACION_ANTICIPO' THEN -"importe"
            ELSE 0
        END
    ), 0)
      INTO v_reservado_pedido
      FROM "EVENTO_IMPUTACION_FINANCIERA"
     WHERE "idCobro" = NEW."idCobro"
       AND "idPedido" = NEW."idPedido";

    IF NEW."tipoEvento" IN ('CONSOLIDACION_ANTICIPO', 'LIBERACION_ANTICIPO')
       AND NEW."importe" > v_reservado_pedido THEN
        RAISE EXCEPTION 'IMP-B: no se puede consolidar/liberar (%) mas que la reserva activa (%) del Pedido %',
            NEW."importe", v_reservado_pedido, NEW."idPedido";
    END IF;

    SELECT
        COALESCE(SUM(CASE "tipoEvento"
            WHEN 'RESERVA_ANTICIPO' THEN "importe"
            WHEN 'CONSOLIDACION_ANTICIPO' THEN -"importe"
            WHEN 'LIBERACION_ANTICIPO' THEN -"importe"
            ELSE 0 END), 0),
        COALESCE(SUM(CASE "tipoEvento"
            WHEN 'CONSOLIDACION_ANTICIPO' THEN "importe"
            WHEN 'APLICACION_FIFO_LIBRE' THEN "importe"
            WHEN 'APLICACION_RENDICION_K2' THEN "importe"
            ELSE 0 END), 0)
      INTO v_reservado_total_cobro, v_aplicado_total_cobro
      FROM "EVENTO_IMPUTACION_FINANCIERA"
     WHERE "idCobro" = NEW."idCobro";

    IF NEW."tipoEvento" = 'RESERVA_ANTICIPO' THEN
        v_reservado_total_cobro := v_reservado_total_cobro + NEW."importe";
    ELSIF NEW."tipoEvento" = 'CONSOLIDACION_ANTICIPO' THEN
        v_reservado_total_cobro := v_reservado_total_cobro - NEW."importe";
        v_aplicado_total_cobro := v_aplicado_total_cobro + NEW."importe";
    ELSIF NEW."tipoEvento" = 'LIBERACION_ANTICIPO' THEN
        v_reservado_total_cobro := v_reservado_total_cobro - NEW."importe";
    ELSIF NEW."tipoEvento" IN ('APLICACION_FIFO_LIBRE', 'APLICACION_RENDICION_K2') THEN
        v_aplicado_total_cobro := v_aplicado_total_cobro + NEW."importe";
    END IF;

    IF v_reservado_total_cobro + v_aplicado_total_cobro > v_importe_cobro - v_reembolso_bloqueante THEN
        RAISE EXCEPTION 'IMP-B: sobreuso del Cobro % (comprometido % + % > elegible %)',
            NEW."idCobro", v_reservado_total_cobro, v_aplicado_total_cobro,
            (v_importe_cobro - v_reembolso_bloqueante);
    END IF;

    RETURN NEW;
END
$impb$;

CREATE TRIGGER "tg_gfis_impb_guardar"
    BEFORE INSERT OR UPDATE OR DELETE ON "EVENTO_IMPUTACION_FINANCIERA"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_impb_guardar"();

-- IMP-A: proyeccion derivada (NO canonica) de disponibilidad de Cobro
CREATE OR REPLACE VIEW "VW_IMP_A_DISPONIBILIDAD_COBRO" AS
SELECT
    c."idCobro",
    c."idCliente",
    c."estadoCobro",
    c."importe" AS "importeCobro",
    COALESCE((
        SELECT SUM(r."importeReembolso")
        FROM "REEMBOLSO" r
        WHERE r."idCobro" = c."idCobro"
          AND r."estado" IN ('PENDIENTE', 'PROCESADO')
    ), 0)::NUMERIC(18,2) AS "importeReembolsoBloqueante",
    COALESCE((
        SELECT SUM(CASE e."tipoEvento"
            WHEN 'RESERVA_ANTICIPO' THEN e."importe"
            WHEN 'CONSOLIDACION_ANTICIPO' THEN -e."importe"
            WHEN 'LIBERACION_ANTICIPO' THEN -e."importe"
            ELSE 0 END)
        FROM "EVENTO_IMPUTACION_FINANCIERA" e
        WHERE e."idCobro" = c."idCobro"
    ), 0)::NUMERIC(18,2) AS "importeReservadoActivo",
    COALESCE((
        SELECT SUM(CASE e."tipoEvento"
            WHEN 'CONSOLIDACION_ANTICIPO' THEN e."importe"
            WHEN 'APLICACION_FIFO_LIBRE' THEN e."importe"
            WHEN 'APLICACION_RENDICION_K2' THEN e."importe"
            ELSE 0 END)
        FROM "EVENTO_IMPUTACION_FINANCIERA" e
        WHERE e."idCobro" = c."idCobro"
    ), 0)::NUMERIC(18,2) AS "importeImputadoDefinitivo",
    (CASE WHEN c."estadoCobro" = 'CONFIRMADO' THEN
        c."importe"
        - COALESCE((SELECT SUM(r."importeReembolso") FROM "REEMBOLSO" r
                    WHERE r."idCobro" = c."idCobro" AND r."estado" IN ('PENDIENTE', 'PROCESADO')), 0)
        - COALESCE((SELECT SUM(CASE e."tipoEvento"
                    WHEN 'RESERVA_ANTICIPO' THEN e."importe"
                    WHEN 'APLICACION_FIFO_LIBRE' THEN e."importe"
                    WHEN 'APLICACION_RENDICION_K2' THEN e."importe"
                    WHEN 'LIBERACION_ANTICIPO' THEN -e."importe"
                    ELSE 0 END)
                    FROM "EVENTO_IMPUTACION_FINANCIERA" e WHERE e."idCobro" = c."idCobro"), 0)
     ELSE 0 END)::NUMERIC(18,2) AS "importeLibreDisponible"
FROM "COBRO" c;

-- -----------------------------------------------------------------------------
-- BLOQUE 6: Guard SQL D7-A / DC05-07-A sobre COBRO y soporte fisico C-04
-- (ART-GCON-D1: idempotencia durable de anulacion con recibo y contraasientos)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "fn_gfis_guard_d7a_y_reservas_cobro"()
RETURNS trigger LANGUAGE plpgsql AS $d7a$
DECLARE
    v_comprometido NUMERIC(18,2);
BEGIN
    IF OLD."estadoCobro" = 'CONFIRMADO' AND NEW."estadoCobro" = 'ANULADO' THEN
        IF OLD."origen" = 'REPARTO'
           OR EXISTS (SELECT 1 FROM "COBRO_RENDICION_K2" WHERE "idCobro" = OLD."idCobro") THEN
            RAISE EXCEPTION 'D7-A: Cobro de rendicion de reparto (%) no admite anulacion manual', OLD."idCobro";
        END IF;

        SELECT COALESCE(SUM(CASE "tipoEvento"
            WHEN 'RESERVA_ANTICIPO' THEN "importe"
            WHEN 'APLICACION_FIFO_LIBRE' THEN "importe"
            WHEN 'APLICACION_RENDICION_K2' THEN "importe"
            WHEN 'LIBERACION_ANTICIPO' THEN -"importe"
            ELSE 0 END), 0)
          INTO v_comprometido
          FROM "EVENTO_IMPUTACION_FINANCIERA"
         WHERE "idCobro" = OLD."idCobro";

        IF v_comprometido > 0 THEN
            RAISE EXCEPTION 'DC05-07-A: Cobro % posee reservas o imputaciones activas (%) y no puede anularse',
                OLD."idCobro", v_comprometido;
        END IF;
    END IF;
    RETURN NEW;
END
$d7a$;

CREATE TRIGGER "tg_gfis_guard_d7a_y_reservas_cobro"
    BEFORE UPDATE OF "estadoCobro" ON "COBRO"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_guard_d7a_y_reservas_cobro"();

-- Bloqueante 5 (C-04 / ART-GCON-D1): soporte fisico para claveIdempotenciaAnulacion,
-- huella de payload, recibo historico inmutable y vinculo verificable a contraasientos.
CREATE TABLE "ANULACION_COBRO_IDEMPOTENCIA" (
    "claveIdempotenciaAnulacion" UUID NOT NULL,
    "idCobro" BIGINT NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "idUsuarioAdministrador" BIGINT NOT NULL,
    "versionHuella" VARCHAR(20) NOT NULL,
    "huellaCanonica" VARCHAR(128) NOT NULL,
    "motivoNormalizado" TEXT NOT NULL,
    "idMovimientoCuentaContraasiento" BIGINT NOT NULL,
    "idMovimientoCajaContraasiento" BIGINT NOT NULL,
    "fechaHoraAnulacion" TIMESTAMPTZ NOT NULL,
    "reciboHistoricoJson" JSONB NOT NULL,
    CONSTRAINT "pk_gfis_anulacion_idemp" PRIMARY KEY ("claveIdempotenciaAnulacion"),
    CONSTRAINT "uq_gfis_anulacion_idemp_cobro" UNIQUE ("idCobro"),
    CONSTRAINT "uq_gfis_anulacion_idemp_mov_cuenta" UNIQUE ("idMovimientoCuentaContraasiento"),
    CONSTRAINT "uq_gfis_anulacion_idemp_mov_caja" UNIQUE ("idMovimientoCajaContraasiento"),
    CONSTRAINT "fk_gfis_anul_idemp_cobro_cliente" FOREIGN KEY ("idCobro", "idCliente")
        REFERENCES "COBRO" ("idCobro", "idCliente") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_anul_idemp_admin" FOREIGN KEY ("idUsuarioAdministrador")
        REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_anul_idemp_mov_cuenta" FOREIGN KEY ("idMovimientoCuentaContraasiento")
        REFERENCES "MOVIMIENTO_CUENTA" ("idMovimientoCuenta") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_gfis_anul_idemp_mov_caja" FOREIGN KEY ("idMovimientoCajaContraasiento")
        REFERENCES "MOVIMIENTO_CAJA" ("idMovimientoCaja") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "ck_gfis_anul_idemp_motivo" CHECK (length(btrim("motivoNormalizado")) > 0),
    CONSTRAINT "ck_gfis_anul_idemp_huella" CHECK (length(btrim("huellaCanonica")) > 0)
);

CREATE OR REPLACE FUNCTION "fn_gfis_validar_anulacion_idemp"()
RETURNS trigger LANGUAGE plpgsql AS $ai$
DECLARE
    v_estado_cobro VARCHAR(20);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'C04-IDEMP: el registro historico de anulacion idempotente es inmutable';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM "USUARIO" u
        JOIN "USUARIO_ROL" ur ON ur."idUsuario" = u."idUsuario"
        WHERE u."idUsuario" = NEW."idUsuarioAdministrador"
          AND u."estado" = 'HABILITADO'
          AND ur."codigoRol" = 'ADMINISTRADOR'
    ) THEN
        RAISE EXCEPTION 'C04-IDEMP/RBAC: solo ADMINISTRADOR habilitado puede registrar anulacion idempotente';
    END IF;

    SELECT "estadoCobro" INTO v_estado_cobro
      FROM "COBRO"
     WHERE "idCobro" = NEW."idCobro";
    IF v_estado_cobro <> 'ANULADO' THEN
        RAISE EXCEPTION 'C04-IDEMP: el Cobro % debe encontrarse ANULADO al persistir su recibo historico', NEW."idCobro";
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM "MOVIMIENTO_CUENTA" mc
          JOIN "ORIGEN_MOVIMIENTO_CUENTA" omc ON omc."idMovimientoCuenta" = mc."idMovimientoCuenta"
         WHERE mc."idMovimientoCuenta" = NEW."idMovimientoCuentaContraasiento"
           AND mc."idCliente" = NEW."idCliente"
           AND mc."tipoMovimiento" = 'ANULACION_COBRO'
           AND omc."idCobroAnulado" = NEW."idCobro"
    ) THEN
        RAISE EXCEPTION 'C04-IDEMP: contraasiento de cuenta corriente (%) no corresponde al Cobro %',
            NEW."idMovimientoCuentaContraasiento", NEW."idCobro";
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM "MOVIMIENTO_CAJA" mj
         WHERE mj."idMovimientoCaja" = NEW."idMovimientoCajaContraasiento"
           AND mj."origen" = 'ANULACION_COBRO'
           AND mj."tipoMovimiento" = 'EGRESO'
           AND mj."idCobroAnulado" = NEW."idCobro"
    ) THEN
        RAISE EXCEPTION 'C04-IDEMP: contraasiento de caja (%) no corresponde al Cobro %',
            NEW."idMovimientoCajaContraasiento", NEW."idCobro";
    END IF;

    RETURN NEW;
END
$ai$;

CREATE TRIGGER "tg_gfis_validar_anulacion_idemp"
    BEFORE INSERT OR UPDATE OR DELETE ON "ANULACION_COBRO_IDEMPOTENCIA"
    FOR EACH ROW EXECUTE FUNCTION "fn_gfis_validar_anulacion_idemp"();
