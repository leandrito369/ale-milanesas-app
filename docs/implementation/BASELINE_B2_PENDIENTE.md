# B1 — Estado de integración PostgreSQL / Flyway (sin DDL)

**Estado:** PREPARADO SIN EJECUTAR ESQUEMA, 29/10/2026.

En B1 la aplicación incluye JDBC PostgreSQL y los artefactos de Flyway. **`spring.flyway.enabled=false` por diseño:** no existe aún carpeta de scripts SQL versionados ejecutables ni se declaró que MR-5 + F01 sean una baseline migratoria reproducible.

## Requisitos de B2 antes de habilitar Flyway

1. Comprobar `MR3_ESQUEMA_MAESTRO.md`, MR5, restricciones y F01 desde [G0 source-lock](../traceability/source-lock.md); distinguir schema consolidado y ruta migratoria 59→60.
2. Proponer **V1__baseline.sql** y migraciones sucesivas a partir de DDL real cotejado; verificar versión e integridad de índices, triggers, FK, locks y vistas. No copiar el DDL histórico preliminar de 59 tablas.
3. Ejecutar en PostgreSQL efímero, desde cero y migrando baseline de ensayo, con Testcontainers, comparando huella estructural documentada MR6. Sin tablas F04 hasta CR-B0-F04-001 aprobada.
4. Ensayar reejecución, recuperación, backups/restores, rollback compensatorio y permisos DB; no activar `ddl-auto` para generar tablas.
5. Recién entonces habilitar Flyway por ADR/PR de B2 con evidencia de test físico.

**Ningún CU se declara implementado en B1.**

---

## Actualización posterior: B2 ya tiene migraciones versionadas en PR (29/10/2026)

El título y estado anterior de este archivo eran correctos durante B1. En la rama de **B2** existe ahora [ADR-IMP-002](../architecture/ADR-IMP-002_BASELINE_MR5_F01.md) con V1 y V2 derivados del G0 y una workflow de ensayo. **Flyway está habilitado solo en la rama B2 mientras se valida CI**. Sigue prohibido usar una DB con datos o una baseline histórica sin precheck/CR. La equivalencia de huella MR6 y la aprobación de PR determinan si se integra en main.
