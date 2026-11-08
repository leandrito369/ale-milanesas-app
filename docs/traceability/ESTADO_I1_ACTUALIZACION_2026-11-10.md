# Actualización de estado — I1 (10/11/2026)

**Tipo:** status update (no acta de cierre)
**Referencia:** `docs/traceability/ACTA_CONSOLIDACION_I1_B4_2026-11-06.md` (merge `e7dbcd9`)
**Autor:** Victor Leandro Machado
**Metodología:** UP — Fase de Construcción

## 1. Motivo de esta actualización

El acta del 06/11/2026 documentó I1 hasta el cierre de Productos (UC-30/35/47/48) y Usuarios (UC-17/18/55/56). Entre el 06/11 y el 10/11 se ejecutaron tres cortes adicionales que responden a la 4.ª devolución de cátedra (`RD-MR-PARTIDAS-001`) y a `RD-UC50-001`, no a un desvío arbitrario del Plan Rector. Esta actualización deja constancia breve sin duplicar el acta previa.

## 2. Cortes ejecutados desde el acta del 06/11

| Corte | UC | PR | Merge | Estado |
|---|---|---|---|---|
| MR-6 — `PARTIDA_INSUMO` + `DETALLE_MOVIMIENTO_PARTIDA` | — | (V3) | `ab648c0` | ✅ En `main` |
| UC-08 INSUMO — Ingreso de stock por compra | UC-08 | #68 | `d8a88f2` | ✅ En `main` |
| UC-08 REVENTA — Ingreso de stock por compra (reventa) | UC-08 | #69 | `02bddfa` | ✅ En `main` |
| UC-42 INSUMO — Ajuste de inventario | UC-42 | #70 | `b3a76f3` | ✅ En `main` |

## 3. RDs mergeadas (en `ale-milanesas`)

| RD | Función | Merge |
|---|---|---|
| `RD-MR-PARTIDAS-001` | Autoriza MR-6 (62 tablas) | `cd603d2` |
| `RD-UC08-001` | Ratifica 4 decisiones de UC-08 INSUMO | `ee555ba` |
| `RD-UC08-002` | Ratifica 5 decisiones de UC-08 REVENTA | `ad8d39e` |
| `RD-UC42-001` | Ratifica 4/5 gaps de UC-42 (G4 pendiente → `RD-UC42-002`) | `d7762f6` |
| `RP-022` actualizado | Estado: parcialmente resuelto (INSUMO) | `bf14b2b` |

## 4. Justificación de los adelantos (no son desvíos arbitrarios)

- **UC-08 y UC-42** responden a `RD-MR-PARTIDAS-001` (4.ª devolución), que exigió validar en código real el ingreso de bolsas/partidas antes de continuar.
- **UC-42** además responde a `RD-UC50-001`, que prohíbe cargar stock inicial en UC-50 y lo delega explícitamente a UC-42.
- **MR-6** está implementado técnicamente (migración V3 aplicada) y usado por código real (UC-08 + UC-42). Distinto es la aceptación funcional completa de los 8 UCs afectados: solo 3 están implementados (UC-08 INSUMO, UC-08 REVENTA, UC-42 INSUMO). UC-09/28/31/36/51/64 siguen sin implementación funcional.

## 5. Pendientes para cerrar I1 (técnico)

Según el Plan Rector y el acta del 06/11, faltan 3 cortes B4 de I1:

| Corte | UC | Alcance pendiente |
|---|---|---|
| **I1-04 Precios** | UC-19, UC-20 | UC-19 = escritura (POST/PUT) bajo gate ADMIN; UC-20 = GET historial |
| **I1-05 Categorías** | UC-57 (UC-15/16 internos) | Solo endpoint B4 de UC-57; UC-15/16 no llevan endpoint público |
| **I1-06 Insumos** | UC-50, UC-51, UC-52 | UC-36 GET ya en B4-01; faltan las escrituras administrativas (UC-51 con validación MR-6 sobre partidas con historia) |

**Fuera de I1 (no bloquean su cierre):**
- UC-29 Cobro manual → I3-03 (`RD-UC29` parcial en B3, HTTP diferido)
- UC-28 Consumo, UC-09 Consultar Stock → I3-01
- UC-31, UC-64 → I4-I7
- `RD-UC42-002` (idempotencia) → ciclo independiente

## 6. Estado del cierre de I1

**I1 técnico:** en curso. Faltan los 3 cortes B4 arriba listados.
**I1 aceptación integral:** no declarable. Issue #28 (pausa de despliegue) sigue abierta; no hay validación humana ni E2E de los endpoints nuevos.

Los 3 cortes pendientes (I1-04, I1-05, I1-06) se ejecutarán en secuencia; al cierre se emitirá el acta final de I1.

## 7. Referencias

- Acta previa: `docs/traceability/ACTA_CONSOLIDACION_I1_B4_2026-11-06.md` (PR #64, merge `e7dbcd9`)
- Plan Rector: `ale-milanesas/docs/01-gobierno/PLAN_RECTOR_IMPLEMENTACION_2026-10-29.md`
- Backlog I1: `ale-milanesas/docs/01-gobierno/b0/B0_BACKLOG_I0_I1_PRIMER_VERTICAL_2026-10-29.md`
- RDs mergeadas: ver tabla en sección 3
