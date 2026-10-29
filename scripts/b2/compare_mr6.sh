#!/usr/bin/env bash
# B2 en laboratorio GitHub Actions, NO ejecutar contra datos reales.
set -Eeuo pipefail
export PGHOST=127.0.0.1 PGPORT=5432 PGUSER=postgres PGPASSWORD=postgres PGCONNECT_TIMEOUT=10
mkdir -p b2-logs
psql -X -v ON_ERROR_STOP=1 -d postgres -c 'CREATE DATABASE ale_mr5_flyway_ci;'
psql -X -v ON_ERROR_STOP=1 -d postgres -c 'CREATE DATABASE ale_mr5_referencia_ci;'
psql -X -v ON_ERROR_STOP=1 -d postgres -c 'CREATE DATABASE ale_mr5_incremental_ci;'
for db in ale_mr5_referencia_ci ale_mr5_incremental_ci; do
  psql -X -v ON_ERROR_STOP=1 -d "$db" -c 'CREATE SCHEMA ale_gestion;'
done

# Referencia directa de MR5 + F01, sin modificar SQL normativo.
psql -X -v ON_ERROR_STOP=1 -d ale_mr5_referencia_ci \
  -c 'SET search_path TO ale_gestion, public;' \
  -f g0-source/docs/05-modelo-relacional/sql/MR5_DDL_NO_EJECUTADO.sql \
  > b2-logs/referencia-mr5.log 2>&1
psql -X -v ON_ERROR_STOP=1 -d ale_mr5_referencia_ci \
  -c 'SET search_path TO ale_gestion, public;' \
  -f g0-source/docs/05-modelo-relacional/sql/f01/F01_PATCH_POST_MR5.sql \
  > b2-logs/referencia-f01.log 2>&1
# La batería F01 original fija mr5_validacion; solo la copia de ensayo usa ale_gestion.
python3 scripts/b2/adapt_f01_test_schema.py \
  g0-source/docs/05-modelo-relacional/sql/f01/F01_PRUEBAS_POSTGRESQL.sql \
  b2-logs/f01-pruebas-schema-app.sql
psql -X -v ON_ERROR_STOP=1 -d ale_mr5_referencia_ci \
  -c 'SET search_path TO ale_gestion, public;' \
  -f b2-logs/f01-pruebas-schema-app.sql \
  > b2-logs/referencia-f01-pruebas.log 2>&1
grep -q 'F01 PASS' b2-logs/referencia-f01-pruebas.log

# Ruta heredada 59 -> MR3 -> MR5 -> F01, sobre otra DB descartable.
for rel in \
  historico/DDL_59_TABLAS_2026-10-23_NO_EJECUTADO.sql \
  sql/MR3_MIGRACION_NO_EJECUTADA.sql \
  sql/MR5_PATCH_NO_EJECUTADO.sql \
  sql/f01/F01_PATCH_POST_MR5.sql
do
  psql -X -v ON_ERROR_STOP=1 -d ale_mr5_incremental_ci \
    -c 'SET search_path TO ale_gestion, public;' \
    -f "g0-source/docs/05-modelo-relacional/$rel" \
    >> b2-logs/incremental.log 2>&1
done

# Ejecutar Flyway REAL mediante Spring Boot arrancado en la tercera base.
export ALE_DB_HOST=127.0.0.1
export ALE_DB_PORT=5432
export ALE_DB_NAME=ale_mr5_flyway_ci
export ALE_DB_USER=postgres
export ALE_DB_PASSWORD=postgres
java -jar target/ale-milanesas-app-0.1.0-SNAPSHOT.jar > b2-logs/flyway-app.log 2>&1 &
app_pid=$!
trap 'kill "$app_pid" 2>/dev/null || true' EXIT
ok=0
for i in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null; then ok=1; break; fi
  if ! kill -0 "$app_pid" 2>/dev/null; then
    echo "Spring Boot finalizó inesperadamente"; tail -n 80 b2-logs/flyway-app.log; exit 1
  fi
  sleep 1
done
if [[ "$ok" -ne 1 ]]; then echo "Timeout Spring Boot"; tail -n 100 b2-logs/flyway-app.log; exit 1; fi
kill "$app_pid" 2>/dev/null || true
wait "$app_pid" 2>/dev/null || true
trap - EXIT

# Verificar Flyway versión 1 y 2, datos del catálogo y restricción F01.
psql -X -v ON_ERROR_STOP=1 -d ale_mr5_flyway_ci -At -c "
 SELECT 'migraciones='||count(*) FROM ale_gestion.flyway_schema_history WHERE success = true AND type = 'SQL';
 SELECT 'tablas='||count(*) FROM information_schema.tables WHERE table_schema='ale_gestion' AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history';
 SELECT 'columnas='||count(*) FROM information_schema.columns WHERE table_schema='ale_gestion' AND table_name <> 'flyway_schema_history';
 SELECT 'fk='||count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='ale_gestion' AND c.contype='f';
" | tee b2-logs/flyway-catalogo.txt
for expected in "migraciones=2" "tablas=60" "columnas=453" "fk=125"; do grep -qx "$expected" b2-logs/flyway-catalogo.txt; done

# Huella MR6 exacta: trasladar schema filtro y EXCLUIR solamente metadata Flyway.
python3 scripts/b2/adapt_mr6_fingerprint.py \
  g0-source/docs/05-modelo-relacional/sql/mr6/huella-estructural.sql \
  b2-logs/huella-b2.sql
export PGOPTIONS="-c search_path=ale_gestion,public"
for db in ale_mr5_referencia_ci ale_mr5_incremental_ci ale_mr5_flyway_ci; do
  psql -X -v ON_ERROR_STOP=1 -At -d "$db" \
    -f b2-logs/huella-b2.sql > "b2-logs/${db}.huella"
done
diff -u b2-logs/ale_mr5_referencia_ci.huella b2-logs/ale_mr5_incremental_ci.huella \
  > b2-logs/diff-directa-incremental.txt
diff -u b2-logs/ale_mr5_referencia_ci.huella b2-logs/ale_mr5_flyway_ci.huella \
  > b2-logs/diff-directa-flyway.txt
echo "PASS B2: Flyway V1/V2 = MR5+F01 directa = 59→MR3→MR5+F01 (huella MR6)."

# Ensayo de backup/restore en cuarto DB DESCARTABLE; preserva un dato sintético.
psql -X -v ON_ERROR_STOP=1 -d ale_mr5_flyway_ci \
  -c "INSERT INTO ale_gestion.\"UNIDAD_MEDIDA\"(\"codigoUnidad\",\"nombre\") VALUES ('B2_PRUEBA','Unidad sintética de respaldo');"
pg_dump -Fc -d ale_mr5_flyway_ci -f b2-logs/ale_mr5_backup.dump
psql -X -v ON_ERROR_STOP=1 -d postgres -c 'CREATE DATABASE ale_mr5_restaurada_ci;'
pg_restore --no-owner --no-privileges -d ale_mr5_restaurada_ci \
  b2-logs/ale_mr5_backup.dump > b2-logs/restore.log 2>&1
psql -X -v ON_ERROR_STOP=1 -d ale_mr5_restaurada_ci -At -c \
  "SELECT count(*) FROM ale_gestion.\"UNIDAD_MEDIDA\" WHERE \"codigoUnidad\"='B2_PRUEBA' AND \"nombre\"='Unidad sintética de respaldo';" \
  | grep -qx '1'
psql -X -v ON_ERROR_STOP=1 -At -d ale_mr5_restaurada_ci \
  -f b2-logs/huella-b2.sql > b2-logs/ale_mr5_restaurada_ci.huella
# PostgreSQL reserializa algunos CHECK ANY(ARRAY) al restaurar: diff informativo.
diff -u b2-logs/ale_mr5_referencia_ci.huella b2-logs/ale_mr5_restaurada_ci.huella \
  > b2-logs/diff-directa-restaurada.txt || true
python3 scripts/b2/compare_restored_fingerprint.py \
  b2-logs/ale_mr5_referencia_ci.huella b2-logs/ale_mr5_restaurada_ci.huella \
  | tee b2-logs/restore-comparison.txt
# Validar reglas F01 sobre la base RESTAURADA, además de los datos de prueba.
psql -X -v ON_ERROR_STOP=1 -d ale_mr5_restaurada_ci \
  -f b2-logs/f01-pruebas-schema-app.sql \
  > b2-logs/restaurada-f01-pruebas.log 2>&1
grep -q 'F01 PASS' b2-logs/restaurada-f01-pruebas.log
psql -X -v ON_ERROR_STOP=1 -d ale_mr5_restaurada_ci -At -c \
  "SELECT count(*) FROM ale_gestion.flyway_schema_history WHERE success=true AND type='SQL';" \
  | grep -qx '2'
echo "PASS B2 backup/restore: objetos/columnas invariantes, CHECK normalizados, F01 y dato sintético preservados."

