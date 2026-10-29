# ADR-IMP-002 — Baseline de migraciones PostgreSQL MR-5/F01

**Fecha:** 29/10/2026  
**Estado:** PROPUESTA IMPLEMENTADA EN PR, pendiente de revisión y pruebas CI.  
**Alcance:** B2 / I0-03 sobre **base nueva y vacía**, sin datos productivos.  
**Fuentes normativas:** [source-lock G0](../traceability/source-lock.md), MR-5 DDL y F01 aprobados, MR-6 pruebas 17.11.

## Decisión

1. **PostgreSQL 17.11**, esquema de aplicación `ale_gestion`, separado de `public`; Flyway administra dos migraciones SQL.
2. `V1__baseline_mr5.sql`: contenido derivado **únicamente** de `docs/05-modelo-relacional/sql/MR5_DDL_NO_EJECUTADO.sql` del commit fuente `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`. Se eliminan exclusivamente dos líneas delimitadoras `BEGIN;` y `COMMIT;`, para dejar a Flyway el control de la transacción.
3. `V2__f01_cobro_estados.sql`: contenido derivado **únicamente** de `docs/05-modelo-relacional/sql/f01/F01_PATCH_POST_MR5.sql` del mismo commit. Se eliminan exclusivamente `\set ON_ERROR_STOP on` (directiva propia de psql) y `BEGIN;`/`COMMIT;`; se conserva `SET LOCAL lock_timeout = '10s'`, el precheck y todos los triggers/checks.
4. Configuración `spring.flyway.enabled=true` con `default-schema=ale_gestion`; Hibernate no crea objetos y `spring.sql.init.mode=never`. `flyway_schema_history` se crea además de las 60 tablas de negocio, **no** es la tabla funcional número 61.
5. La ruta histórica `DDL_59_TABLAS...→MR3_MIGRACION→MR5_PATCH→F01` **se comprueba exclusivamente en una base de laboratorio** contra la instalación directa de MR5+F01 y contra V1/V2; **no** se integra como baseline automática para una instalación existente con información.
6. Cambios de modelo posteriores se incorporan mediante **V3+ y CR/RD en el repositorio documental** cuando correspondan. No `baselineOnMigrate=true`, no `repair` de checksum sin revisión ni uso de `clean` con datos.

## Proveniencia inmutable

| Migración | SQL de origen en repo documental | Blob Git origen (G0) | Cambio permitido |
|---|---|---|---|
| V1 | `docs/05-modelo-relacional/sql/MR5_DDL_NO_EJECUTADO.sql` | `3693f0df3d2527bd85f5d1efed0d50be4be80037` | Quitar delimitadores de transacción |
| V2 | `docs/05-modelo-relacional/sql/f01/F01_PATCH_POST_MR5.sql` | `da20319996bfd63aa1c6dda5a1b20c8918350a40` | Quitar `\set` y delimitadores de transacción |

El script `scripts/b2/check_sql_provenance.py` recupera ambas fuentes G0 en CI y comprueba que las migraciones terminan en el texto original filtrado, sin cambiar reglas funcionales.

## Ensayo de equivalencia MR6

- Arranque en Testcontainers verifica **60 tablas de negocio, 453 columnas, 125 FK**, restricciones y trigger F01, dos entradas SQL exitosas en `flyway_schema_history` y **segundo `migrate()` sin nuevas migraciones**.
- Workflow `b2-migrations.yml` construye la aplicación, ejecuta Flyway en base descartable y, por separado, compila **MR5+F01 directa** y **59→MR3→MR5→F01** desde el repositorio documental anclado. Adapta únicamente el nombre de schema y excluye el metadato de Flyway de la consulta original de huella MR6; exige `diff` idéntico en columnas, constraints, índices, triggers y funciones.
- Rechazos de F01 contra datos sintéticos se prueban en el esquema de referencia mediante la batería `F01_PRUEBAS_POSTGRESQL.sql`, dentro de `ROLLBACK`.
- Se adjuntan logs aun ante fallo; **nunca** se atribuye éxito si no lo confirma Actions.

## Qué queda fuera y requiere tratamiento posterior

- **Migración de una DB existente con datos:** requiere inventario, backup/restauración, precheck de duplicados/estados, validación de impacto transaccional y plan de reversión; este PR **no** ejecuta actualización sobre datos reales.
- **F04/S01:** CR-B0-F04-001 pendiente. No se crean tablas ni columnas de inbox/cola/snapshot o pagos en tránsito.
- MR6 solo certifica estructura e invariantes SQL incluidas; **BC06 0/18 servicios aún sin implementar**.
- La habilitación de Flyway exige revisión del integrador de este PR y CI verde; no se habilita en `main` antes de ese merge.

**Criterio de aprobación:** tres caminos equivalentes, dos migraciones aplicadas una sola vez, catálogo correcto, pruebas F01 con rechazos, y ninguna diferencia de huella MR6 salvo `flyway_schema_history`.
