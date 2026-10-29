# B2 — Ensayo PostgreSQL MR-5/MR-6 y parche F01

**Estado:** preparada para ejecución y revisión en PR. No desplegar contra una DB existente; solo base **vacía y descartable**.  
**Fuentes:** [G0 source-lock](../traceability/source-lock.md) y [ADR-IMP-002](../architecture/ADR-IMP-002_BASELINE_MR5_F01.md).

## Ruta de base nueva

Flyway ejecuta, en orden:
- `V1__baseline_mr5.sql` → MR-5 consolidado: **60 tablas de negocio, 453 columnas, 125 FK**, funciones, triggers e índices.
- `V2__f01_cobro_estados.sql` → F01: catálogo `CONFIRMADO/ANULADO`, constraint y trigger de transición.

**Importante:** `flyway_schema_history` es una tabla de metadatos adicional; no contarlo como entidad del dominio. `ale_gestion` es el schema operativo; el schema histórico `mr5_validacion` se usó solo en MR-6.

## Reproducir en desarrollo nuevo (no usar DB con información)

```bash
cp .env.example .env
# Cambiar ALE_DB_PASSWORD por contraseña local.
docker compose up -d postgres
set -a; . ./.env; set +a
mvn -B -ntp clean verify
mvn -B -ntp spring-boot:run
```

Al iniciar contra una base nueva, Flyway crea el schema `ale_gestion` y registra las versiones `1` y `2`. Una segunda ejecución normal no repite los cambios; un cambio de checksum se rechaza en lugar de repararlo silenciosamente.

**No ejecutar `docker compose down -v` en una instalación con información que deba conservarse.** No limpiar/alterar la tabla `flyway_schema_history` para sortear el control de versión.

## Evidencia de laboratorio CI (triple instalación)

La workflow [B2](../../.github/workflows/b2-migrations.yml) recupera de GitHub `leandrito369/ale-milanesas` por el commit G0 inmóvil y compara:
1. DDL MR5 directo → F01;
2. DDL histórico 59 → MR3 migración → MR5 parche → F01;
3. Flyway V1/V2 ejecutadas en la aplicación sobre PostgreSQL real.

El informe compara recuentos y **huella MR6** tras excluir únicamente el metadata de Flyway. El test unitario/integración B2 controla catálogo, F01 y no repetición de migraciones. Se usa PostgreSQL 17.11, bases descartables, variables de laboratorio.

Para validar la fidelidad textual: `python3 scripts/b2/check_sql_provenance.py` requiere el checkout de fuentes G0 bajo `g0-source/` (la workflow lo gestiona). **No usar `main` móvil como origen de migraciones.**

## Límites de aceptación

La equivalencia SQL no comprueba el tratamiento completo de 67 operaciones, seguridad HTTP RBAC, compra, contabilidad de caja ni sincronización offline. El esquema está listo para nuevas implementaciones solo en el sentido estructural ensayado. Las 18 pruebas BC-06 siguen pendientes. Para una base heredada con datos, preparar una **CR/plan de migración de datos** antes de usar V1/V2.

## Ensayo adicional de backup/restauración (base descartable)

La workflow B2 también prueba un respaldo **completo de la base descartable** mediante `pg_dump -Fc` (incluye extensiones como `btree_gist`) con un dato **sintético** de `UNIDAD_MEDIDA`, restaurándolo en una **cuarta** base nueva con `pg_restore`. La **comparación estricta MR6** sigue rigiendo para las tres rutas de instalación. En la restauración, además de preservar el dato sintético y las dos migraciones, se exige identidad de objetos, columnas, índices, triggers y funciones y se admiten **solo** cambios de representación textual de los `CHECK ... ANY(ARRAY...)` que el deparser PostgreSQL reescribe al restaurar. El comparador falla ante cualquier otra diferencia.

Esto verifica una restauración **de laboratorio**, no el diseño de recuperación operativa, continuidad de servicio ni migración de datos reales. La evidencia queda en el run de GitHub Actions (no atribuir éxito hasta CI verde).

**Incidencia del primer ensayo de recuperación:** el respaldo filtrado con `pg_dump -n ale_gestion` no incluyó todos los objetos de extensión que requiere la reconstrucción GiST de índices de exclusión. Se conserva el registro del fallo de CI como evidencia; se corrigió **el alcance del backup de prueba**, no las reglas ni el SQL del modelo. El ensayo completo debe superar su nueva CI antes de aprobar B2.

**Incidencia del segundo ensayo de recuperación:** el respaldo completo se restauró y conservó los **1.225 objetos del catálogo**. No obstante, el `diff` textual de la huella detectó **38 representaciones reescritas** de constraints `CHECK ... ANY(ARRAY...)` (`pg_get_constraintdef`), mientras que **453 columnas, 126 índices, 212 funciones y 37 triggers** permanecieron idénticos. Se corrigió **el comparador de pruebas**, restringiendo la normalización a esas diferencias de paréntesis/casts y verificando la batería F01 en la base restaurada. No se modificó V1, V2, MR5 ni F01. La aprobación requiere CI verde de esta nueva comprobación.
