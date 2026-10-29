-- B2: Migracion Flyway derivada mecanicamente de fuente canónica G0.
-- source_repo: https://github.com/leandrito369/ale-milanesas
-- source_commit: 1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e
-- source_path: docs/05-modelo-relacional/sql/f01/F01_PATCH_POST_MR5.sql
-- transformation: remove standalone BEGIN/COMMIT delimiters and psql-specific \\set, if present.
-- No se han modificado reglas, estados, tablas, columnas ni funciones del SQL de origen.
-- El transaction boundary lo gobierna Flyway/PostgreSQL. Pruebas requeridas antes de merge.

-- F01 (APROBADA 26/10/2026): catálogo COBRO.CONFIRMADO / ANULADO.
-- Ejecutar DESPUES de MR5. Revisar datos históricos antes de aplicar.
-- En laboratorio se aplica solo en una base descartable.
SET LOCAL lock_timeout = '10s';
DO $guard$
BEGIN
  IF EXISTS (SELECT 1 FROM "COBRO" WHERE "estadoCobro" NOT IN ('CONFIRMADO','ANULADO')) THEN
    RAISE EXCEPTION 'F01: hay COBRO.estadoCobro desconocidos; conciliar antes de migrar';
  END IF;
END
$guard$;
ALTER TABLE "COBRO" ADD CONSTRAINT "ck_f01_cobro_estado"
  CHECK ("estadoCobro" IN ('CONFIRMADO','ANULADO'));
CREATE OR REPLACE FUNCTION "fn_f01_transicion_cobro"()
RETURNS trigger LANGUAGE plpgsql AS $f01$
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW."estadoCobro"<>'CONFIRMADO' THEN
      RAISE EXCEPTION 'F01: un COBRO nuevo debe nacer CONFIRMADO';
    END IF;
  ELSIF NEW."estadoCobro" IS DISTINCT FROM OLD."estadoCobro"
       AND (OLD."estadoCobro"<>'CONFIRMADO' OR NEW."estadoCobro"<>'ANULADO') THEN
    RAISE EXCEPTION 'F01: transición de COBRO no permitida';
  END IF;
  RETURN NEW;
END
$f01$;
CREATE TRIGGER "tg_f01_transicion_cobro"
  BEFORE INSERT OR UPDATE OF "estadoCobro" ON "COBRO"
  FOR EACH ROW EXECUTE FUNCTION "fn_f01_transicion_cobro"();
