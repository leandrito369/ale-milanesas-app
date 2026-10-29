# G0 Source Lock — Versión inmutable de requisitos y diseño

**Proyecto:** Sistema de Gestión para Ale Milanesas (UNaM-FCEQyN)  
**Estado:** **APROBADO Y FIJADO** para el inicio de B1; no implica software existente.  
**Fecha de aprobación:** 29/10/2026.

| Campo | Valor |
|---|---|
| `source_repo` | `https://github.com/leandrito369/ale-milanesas` |
| `source_commit` | `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` — commit canónico que integra la totalidad de las fuentes #9, #10–#19 y #20–#26 |
| `governance_commit` | `27738b089afdd2698cdae915f75c0e8089a6248a` — commit posterior de acta RD-B0-G0-001 y manifiesto definitivo, fusionado con PR #27 |
| `g0_acta` | `docs/01-gobierno/b0/RD_B0_G0_001_CIERRE_LINEA_BASE_2026-10-29.md` |
| `g0_manifest` | `docs/01-gobierno/b0/G0_MANIFIESTO_CANONICO_67_OPERACIONES_2026-10-29.csv` |
| `g0_manifest_git_blob` | `aa6c81192bdeffb8a286128fd8333be0ae51141d` |
| `g0_ci` | `https://github.com/leandrito369/ale-milanesas/actions/runs/40937059157` — 21/21 tests documentales OK |
| `source_coverage` | 64 CU / 67 operaciones / 40 contratos B0 consolidados y 27 contratos REV integrados |
| `source_integrity` | 402/402 asociaciones SHA Git verificadas |
| `rbac` | Matriz de 67 operaciones × 8 canales = 536 celdas; 0 pendientes `P` |
| `data_visibility` | BC-07 — 15 proyecciones ratificadas; se deniegan campos no autorizados |
| `implementation_repo` | `https://github.com/leandrito369/ale-milanesas-app` |
| `unresolved_architecture` | ADR-B1-05, CR-B0-F04-001: **sincronización/pago en tránsito/rendición no autorizados físicamente** |
| `bc06_tests` | 18 casos definidos, **0 ejecutados** contra servicios (no existe código todavía) |

## Autoridad de lectura

1. [Acta RD-B0-G0-001](https://github.com/leandrito369/ale-milanesas/blob/27738b089afdd2698cdae915f75c0e8089a6248a/docs/01-gobierno/b0/RD_B0_G0_001_CIERRE_LINEA_BASE_2026-10-29.md).
2. [Manifiesto definitivo de cada operación](https://github.com/leandrito369/ale-milanesas/blob/27738b089afdd2698cdae915f75c0e8089a6248a/docs/01-gobierno/b0/G0_MANIFIESTO_CANONICO_67_OPERACIONES_2026-10-29.csv).
3. [AGENTS de análisis/diseño](https://github.com/leandrito369/ale-milanesas/blob/27738b089afdd2698cdae915f75c0e8089a6248a/AGENTS.md), [matriz RBAC](https://github.com/leandrito369/ale-milanesas/blob/1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e/docs/01-gobierno/b0/B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv), [proyecciones BC07](https://github.com/leandrito369/ale-milanesas/blob/1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e/docs/01-gobierno/b0/BC07_PROYECCIONES_MINIMAS_RATIFICADAS_2026-10-29.csv).
4. Ficha de cada CU en Fase4 (Alto/Extendido/Real), DSS, DS y contrato preferente: **rutas exactas, commit y blobs del manifiesto**, no supuestos por título o CU.
5. ADR-B1-01/02/03/04/06 aprobadas; CR-B0-F04-001 pendiente, MR5/MR6 y RD de alcance.

**Precedencia:** Fase4 + RD funcional ratificada más reciente; contrato B0 consolidado para sus 40 operaciones, contrato REV integrado para las otras 27; `originales/` como evidencia histórica. En discrepancia, **no editar silenciosamente la fuente ni inventar una decisión**: abrir RP/CR en `leandrito369/ale-milanesas` y restringir la operación afectada.

## Registro obligatorio por PR de implementación

Referenciar `source_commit`, `governance_commit`, `CU`, `operacion`, `contrato_path`, `dss_path`, `ds_path`, `RD/CR`, tablas y columnas MR5/MR6 reales, autorización RBAC y BC07, test y resultado. Si se cambia la línea base, aprobar la nueva versión en repositorio documental y actualizar este archivo mediante PR independiente.

**No se autoriza generar SQL F04, incluir el efectivo declarado del Repartidor como Cobro definitivo, acreditar un Reembolso antes de PROCESADO, ni confundir una prueba documental con una prueba de servidor.**

## Adenda de precedencia posterior a G0 — I1 (29/10/2026)

La línea base G0 `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` **permanece inmutable**. Posteriormente el responsable aprobó los PATCH de **RD-UC17-001, RD-UC18-001, RD-UC53-001, RD-UC54-001 e IC-UC18-MR5-001**, integrados en el repo documental mediante [PR #29](https://github.com/leandrito369/ale-milanesas/pull/29), **commit `3cf4b19078f02a374229012cbf8d278b4bb58196`**.

En **UC-17/18/53/54** prevalecen esos PATCH sobre las firmas y literales REV02 del corte G0. Contratos y DSS/DS vigentes: `docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/` **en el commit posterior indicado**, no en el blob de G0. Fuente de las RD: `docs/01-gobierno/i1/`. La tabla `EVENTO_ASIGNACION_ROL.tipoEvento` continúa con `ASIGNACION/REVOCACION` de MR5; **el modelo físico MR5/F01 de G0 no fue modificado**.

Esta adenda solo aplica a esos cuatro CU y no cambia las referencias canónicas del resto de las 67 operaciones. Cualquier futuro RD debe registrarse con su nuevo commit y PR; está prohibido reemplazar el `source_commit` de G0 por la punta móvil de `main`.
