# Acta de Cierre Técnico — I2 (11/11/2026)

**Estado:** I2 CERRADO TÉCNICAMENTE. NO es aceptación integral.
**Precedentes:** `ACTA_CIERRE_TECNICO_I1_2026-11-10.md` (`51f8ff5`) + `ESTADO_I1_ACTUALIZACION_2026-11-10.md` (`1d060b9`).
**Autor:** Victor Leandro Machado.
**Metodología:** UP — Fase de Construcción.

## 1. Resumen ejecutivo y alcance

I2 técnicamente completo: los 3 paquetes `I2-01`, `I2-02`, `I2-03` ejecutados con B3 + B4 nuevos (el paquete `ventas/` se creó en este bloque desde cero).

**Aceptación integral:** NO declarable. Issue #28 (pausa de despliegue) sigue abierta. Sin validación humana ni E2E.

## 2. Cortes B4 de I2 en `main`

| Grupo | Cortes | UCs | Merge |
|---|---|---|---|
| I2-01 | cargarPedido (B3 + B4) | UC-03 | `c62f899` |
| I2-03 | asignarOReasignarPedidoARepartidor (B3 + B4) | UC-43 | `f2ae45d` |
| I2-02 | buscarPedido (B3 + B4) | UC-34 | `5043066` |

## 3. Trazabilidad canónica

**source_commit (G0):** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` (inmutable).

**RDs que autorizan I2 (ya en `ale-milanesas`):**
- `RD-RP001/RP019` (fecha de reparto)
- `RD-RP018-001` (primer precio obligatorio)
- `RD-RP002-001` (6 reglas de elegibilidad UC-43)
- `RD-RP007-002` (permisos de búsquedas UC-34)
- `RD-RP009-001` (coordinación UC-43 ↔ UC-55)
- `RD-RP020-001` (reprogramación diferida)
- `RD-F04-001` (alcance offline)
- `RD-F04-006` (evaluación de deuda — parcialmente diferido a F04)
- `RD-F04-007` (ajuste EN_MORA con pago en tránsito)
- `RD-B0-BC02` (RBAC de reprogramación)
- `RD-B0-BC07` (proyecciones mínimas de UC-34)
- `RD-RP023-001` (promociones simultáneas)

**Servicios B3 existentes invocados (no modificados):**
- `EvaluadorCategoriaComercialInterno` (UC-15)
- `AplicarAjusteComercialService` (UC-16)
- `CambiarEstadoUsuarioService` (UC-55)

## 4. Bloque `ventas/` — nuevo paquete

Este es el primer bloque que crea el paquete `ventas/` desde cero (B3 + B4 completos). Servicios creados:
- `CargarPedidoService` (UC-03)
- `AsignarRepartoService` (UC-43)
- `BuscarPedidoService` (UC-34)

Controllers B4 nuevos:
- `CargarPedidoHttpController` (`POST /api/v1/pedidos`)
- `AsignarRepartoHttpController` (`POST /api/v1/pedidos/{idPedido}/asignacion-reparto`)
- `BuscarPedidoHttpController` (`GET /api/v1/pedidos`)

## 5. Desviaciones reconocidas

**DR1 — `RD-F04-006` (saldo exigible provisional):** ver `I2_01_PEDIDOS_B4_HTTP_2026-11-08.md §Desviaciones`. F04 no está construido todavía. Mitigación: UC-15/16 categoriza y aplica recargo por EN_MORA independientemente del saldo.

**DR2 — Invalidación de rutas sugeridas de UC-25 al reasignar:** ver `I2_03_ASIGNACION_REPARTO_B4_HTTP_2026-11-08.md §Desviaciones`. `RutaReparto` es conceptual en el contrato y no existe tabla física en MR-6. Mitigación: se invalida cuando UC-25 se implemente.

**Diferidos fuera de I2 (no bloqueantes):**
- **UC-21** (`Realizar Pedido Online` con OTP/MercadoPago/Promociones) → I6 según Plan Rector.
- **`reprogramarFechaRepartoPedido`** (2ª operación de UC-03) → diferida por brecha de persistencia (`CONTRATOS_REV04A:105`).
- **`PRODUCCION` contextual en UC-34** → 403 hasta que UC-28 exista (I3).

## 6. Pendientes post-I2

**Bloqueantes de aceptación integral:**
- Issue #28 (pausa de despliegue) → sin activación productiva de los 3 endpoints nuevos de I2.
- Sin validación humana ni E2E.

**Fuera de I2 (no bloquean cierre técnico):**
- UC-21 (pedido online) → I6
- `reprogramarFechaRepartoPedido` → pendiente de definición de modelo
- `PRODUCCION` contextual en UC-34 → depende de UC-28 (I3)

## 7. Bloqueo operativo

Issue #28 ABIERTA. Los 2 endpoints de escritura (`POST /api/v1/pedidos` y `POST /api/v1/pedidos/{idPedido}/asignacion-reparto`) tienen triple gate no-prod (`@Profile("!prod")` + `@ConditionalOnProperty(false)` + veto en `PrecondicionesDespliegue`). El GET (`GET /api/v1/pedidos`) no está expuesto en prod por la pausa global.

## 8. Próximo paso del Plan Rector

**I3 — Recetas, Preparación y Entrega**:
- `I3-01` = UC-10 (Recetas versionadas), UC-28 (Registrar Preparación), UC-09 (Consultar Stock)
- `I3-02` = UC-05 (Registrar Entrega), UC-07 (Excedentes), UC-41
- `I3-03` = UC-14 (Cancelaciones), UC-29 (Cobro manual), UC-22, UC-23

**Antes de I3-01:** requiere auditoría del bloque (UC-28 ya tiene 6 gaps identificados en el audit previo: `G1-G6`). Seguramente requiere `RD-UC28-001`.

## Firma

**Alumno:** Victor Leandro Machado.
**Fecha:** 11/11/2026.
