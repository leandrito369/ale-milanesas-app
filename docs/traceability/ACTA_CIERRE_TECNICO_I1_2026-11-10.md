# Acta de Cierre Técnico — I1 (10/11/2026)

**Estado:** I1 CERRADO TÉCNICAMENTE. NO es aceptación integral.
**Precedentes:** `ACTA_CONSOLIDACION_I1_B4_2026-11-06.md` (`e7dbcd9`) + `ESTADO_I1_ACTUALIZACION_2026-11-10.md` (`1d060b9`).
**Autor:** Victor Leandro Machado.
**Metodología:** UP — Fase de Construcción.

## 1. Resumen ejecutivo y alcance

I1 técnicamente completo: los 6 paquetes `I1-01` a `I1-06` ejecutados + 3 cortes adelantados justificados (UC-08 INSUMO, UC-08 REVENTA, UC-42 INSUMO) por `RD-MR-PARTIDAS-001` (4ª devolución) + `RD-UC50-001`.

**Aceptación integral:** NO declarable. Issue #28 (pausa de despliegue) sigue abierta. Sin validación humana ni E2E.

## 2. Cortes B4 de I1 en `main`

| Grupo | Cortes | UCs | Merge |
|---|---|---|---|
| B4-01 | Consultas seguras | UC-33, UC-36 | `d36bba9` |
| B4-02A-E | Auth + hardening | UC-01, UC-53, UC-54 | `420236c`, `23cb0b1`, `109fd71`, `5be1943`, `0a92842`, `3d34392` |
| B4-03-01..07 | Clientes + Productos | UC-02/32/38/30/35/47/48 | `7cc0673`, `fdc47a3`, `5b0fdbd`, `b19c950`, `30415e3`, `feadba4`, `7598d85` |
| I1-01/B4 | Usuarios | UC-17/18/55/56 | `ed4897d`, `4165e45`, `49d0c8b`, `4ae552f` |
| B4-04 | UC-08 INSUMO | UC-08 | `d8a88f2` |
| B4-05 | UC-08 REVENTA | UC-08 | `02bddfa` |
| B4-06 | UC-42 INSUMO | UC-42 | `b3a76f3` |
| I1-04 | Precios | UC-19, UC-20 | `15f4b6c` |
| I1-05 | Categorías | UC-57 | `12e30c5` |
| I1-06 | Insumos | UC-50, UC-51, UC-52 | `f4a89db` |

## 3. Trazabilidad canónica

**source_commit (G0):** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` (inmutable).

**RDs mergeadas (en `ale-milanesas`):**
- RD-B0-G0-001, RD-RP018-001, RD-RP022-001, RD-RP016-001, RD-F04-003/006/007, RD-RP023-001
- RD-UC19-001, RD-UC19-002, RD-UC50-001
- RD-MR-PARTIDAS-001, RD-UC08-001, RD-UC08-002, RD-UC42-001

**Adendas:** `ADENDA_MR6_IMPACTO_8_UC_2026-11-07.md`, `ESTADO_I1_ACTUALIZACION_2026-11-10.md`.

## 4. MR-6 (PARTIDA_INSUMO) — estado

**Integración técnica:** completa (V3 aplicada, `ab648c0`).
**Uso por código real:** 3 UCs de los 8 afectados (UC-08 INSUMO, UC-08 REVENTA, UC-42 INSUMO).
**Pendientes de implementación funcional:** UC-09, UC-28 (I3); UC-31, UC-64 (I4-I7).
**Verificaciones post-MR6 en UCs de I1:** UC-36 (B4-01, sin cambios); UC-51 (I1-06, refactor de `dependencias()` para incluir `PARTIDA_INSUMO`).
**Aclaración:** tener tabla/migración ≠ 8 UCs implementados. Solo 3 UCs usan el modelo en producción + 2 verificados en I1 (UC-36 lectura, UC-51 bloqueo).

## 5. Desviaciones reconocidas del Plan Rector

**Cortes adelantados (no arbitrarios):**
- **UC-08 INSUMO + REVENTA** → adelantado de I5 a B4-04/B4-05. Causa: `RD-MR-PARTIDAS-001` (4ª devolución exigió validar en código real el ingreso de partidas).
- **UC-42 INSUMO** → adelantado de I4-I7 a B4-06. Causa: `RD-UC50-001` (prohíbe stock inicial en UC-50, delega a UC-42) + `RD-MR-PARTIDAS-001`.
- **Justificación:** decisión de mitigación de riesgo arquitectónico, no desvío arbitrario. Aprobada por el responsable.

## 6. Pendientes post-I1

**Bloqueantes de aceptación integral:**
- Issue #28 (pausa de despliegue) → sin activación productiva de los ~19 endpoints nuevos.
- Sin validación humana ni E2E de ninguno.

**Fuera de I1 (no bloquean cierre técnico):**
- UC-29 (Cobro manual) → I3-03
- UC-28 (Preparación), UC-09 (Consultar Stock) → I3-01
- UC-31, UC-64 → I4-I7
- `RD-UC42-002` (idempotencia) → ciclo independiente

## 7. Bloqueo operativo

Issue #28 ABIERTA. Todos los endpoints de escritura nuevos tienen triple gate no-prod (`@Profile("!prod")` + `@ConditionalOnProperty(false)` + veto en `PrecondicionesDespliegue`). Los GET de solo lectura tampoco están expuestos en prod por la pausa.

## 8. Próximo paso del Plan Rector

**I2 — Pedidos**: UC-03 (Cargar Pedido), UC-34 (Buscar Pedido), UC-43 (Pedido Online). Después I3 con Recetas, Preparación, Entrega.

## Firma

**Alumno:** Victor Leandro Machado.
**Fecha:** 10/11/2026.
