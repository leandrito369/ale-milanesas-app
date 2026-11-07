-- MR-6 / RD-MR-PARTIDAS-001: trazabilidad física de existencias de Insumos
-- Autorizado por: RD-MR-PARTIDAS-001 (merge cd603d25addb4312d9bee62ec03514760f7e2894)
-- Referencia: docs/traceability/source-lock.md

-- Sequence para idPartidaInsumo (si no existe en V1)
CREATE SEQUENCE IF NOT EXISTS "seq_partida_insumo" START 1;

-- Tabla #61: PARTIDA_INSUMO
CREATE TABLE "PARTIDA_INSUMO" (
    "idPartidaInsumo" BIGINT NOT NULL DEFAULT nextval('seq_partida_insumo'),
    "idInsumo" BIGINT NOT NULL,
    "idRecepcionCompra" BIGINT,
    "codigoLote" VARCHAR(80),
    "fechaFabricacion" DATE,
    "fechaVencimiento" DATE,
    "fechaHoraRegistro" TIMESTAMPTZ NOT NULL,
    "fechaHoraApertura" TIMESTAMPTZ,
    "cantidadInicial" NUMERIC(18,3) NOT NULL,
    "cantidadDisponible" NUMERIC(18,3) NOT NULL,
    CONSTRAINT "pk_partida_insumo" PRIMARY KEY ("idPartidaInsumo"),
    CONSTRAINT "uq_partida_insumo_insumo" UNIQUE ("idPartidaInsumo", "idInsumo"),
    CONSTRAINT "ck_partida_insumo_cantidades" CHECK (
        "cantidadInicial" > 0
        AND "cantidadDisponible" >= 0
        AND "cantidadDisponible" <= "cantidadInicial"
    ),
    CONSTRAINT "ck_partida_insumo_fechas" CHECK (
        "fechaFabricacion" IS NULL OR "fechaVencimiento" IS NULL
        OR "fechaVencimiento" >= "fechaFabricacion"
    ),
    CONSTRAINT "ck_partida_insumo_apertura" CHECK (
        "fechaHoraApertura" IS NULL OR "fechaHoraApertura" >= "fechaHoraRegistro"
    )
);
ALTER TABLE "PARTIDA_INSUMO"
    ADD CONSTRAINT "fk_partida_insumo_insumo"
    FOREIGN KEY ("idInsumo") REFERENCES "INSUMO" ("idInsumo")
    MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PARTIDA_INSUMO"
    ADD CONSTRAINT "fk_partida_insumo_recepcion"
    FOREIGN KEY ("idRecepcionCompra") REFERENCES "RECEPCION_COMPRA" ("idRecepcionCompra")
    MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;

-- UNIQUE compuesto para FKs
ALTER TABLE "MOVIMIENTO_INSUMO"
    ADD CONSTRAINT "uq_movimiento_insumo_insumo"
    UNIQUE ("idMovimientoInsumo", "idInsumo");

-- Tabla #62: DETALLE_MOVIMIENTO_PARTIDA
CREATE TABLE "DETALLE_MOVIMIENTO_PARTIDA" (
    "idMovimientoInsumo" BIGINT NOT NULL,
    "idPartidaInsumo" BIGINT NOT NULL,
    "idInsumo" BIGINT NOT NULL,
    "cantidadAplicada" NUMERIC(18,3) NOT NULL,
    CONSTRAINT "pk_detalle_movimiento_partida"
        PRIMARY KEY ("idMovimientoInsumo", "idPartidaInsumo"),
    CONSTRAINT "ck_detalle_movimiento_partida_cantidad" CHECK ("cantidadAplicada" > 0)
);
ALTER TABLE "DETALLE_MOVIMIENTO_PARTIDA"
    ADD CONSTRAINT "fk_detalle_mov_partida_movimiento"
    FOREIGN KEY ("idMovimientoInsumo", "idInsumo")
    REFERENCES "MOVIMIENTO_INSUMO" ("idMovimientoInsumo", "idInsumo")
    MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DETALLE_MOVIMIENTO_PARTIDA"
    ADD CONSTRAINT "fk_detalle_mov_partida_partida"
    FOREIGN KEY ("idPartidaInsumo", "idInsumo")
    REFERENCES "PARTIDA_INSUMO" ("idPartidaInsumo", "idInsumo")
    MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;

-- Backfill: crear partidas BACKFILL_V2 para insumos con stock > 0
INSERT INTO "PARTIDA_INSUMO" (
    "idPartidaInsumo", "idInsumo", "idRecepcionCompra", "codigoLote",
    "fechaHoraRegistro", "cantidadInicial", "cantidadDisponible"
)
SELECT
    nextval('seq_partida_insumo'),
    i."idInsumo",
    NULL,
    'BACKFILL_V2',
    clock_timestamp(),
    i."stockActual",
    i."stockActual"
FROM "INSUMO" i
WHERE i."stockActual" > 0;

-- Trigger de auditoría
CREATE OR REPLACE FUNCTION fn_actualizar_cantidad_disponible()
RETURNS TRIGGER AS $$
DECLARE
    v_tipo VARCHAR(50);
    v_delta NUMERIC(18,3);
BEGIN
    SELECT "tipoMovimiento" INTO v_tipo
      FROM "MOVIMIENTO_INSUMO"
     WHERE "idMovimientoInsumo" = COALESCE(NEW."idMovimientoInsumo", OLD."idMovimientoInsumo");

    IF v_tipo IN ('INGRESO_COMPRA', 'AJUSTE_POSITIVO', 'SALDO_INICIAL') THEN
        v_delta := COALESCE(NEW."cantidadAplicada", 0) - COALESCE(OLD."cantidadAplicada", 0);
    ELSE
        v_delta := -1 * (COALESCE(NEW."cantidadAplicada", 0) - COALESCE(OLD."cantidadAplicada", 0));
    END IF;

    UPDATE "PARTIDA_INSUMO"
       SET "cantidadDisponible" = "cantidadDisponible" + v_delta
     WHERE "idPartidaInsumo" = COALESCE(NEW."idPartidaInsumo", OLD."idPartidaInsumo");

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_detalle_movimiento_partida_actualiza_disponible
    AFTER INSERT OR UPDATE OR DELETE ON "DETALLE_MOVIMIENTO_PARTIDA"
    FOR EACH ROW
    EXECUTE FUNCTION fn_actualizar_cantidad_disponible();

