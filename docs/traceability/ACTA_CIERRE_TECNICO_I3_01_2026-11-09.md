# Acta de Cierre Técnico — I3-01 (09/11/2026)

**Estado:** I3-01 CERRADO TÉCNICAMENTE. NO es aceptación integral. NO es cierre de I3 completo (faltan I3-02 e I3-03).
**Precedentes:** `ACTA_CIERRE_TECNICO_I1_2026-11-10.md` (`51f8ff5`) + `ACTA_CIERRE_TECNICO_I2_2026-11-11.md` (`ebbc398`).
**Autor:** Victor Leandro Machado.
**Metodología:** UP — Fase de Construcción.

## 1. Estados de cierre

| Dimensión | Estado |
|---|---|
| Integración técnica (B3 + B4) | ✅ Completada |
| CI + pruebas automatizadas | ✅ Aprobadas |
| Conformidad contractual plena | ✅ Alineado con `RD-I301-001` (post-fix #83) |
| Aceptación humana | ❌ No acreditada |
| Despliegue | ⏸️ Pausado (Issue #28) |
| Iteración I3 completa | ❌ Pendiente (faltan I3-02 e I3-03) |

**Alcance:** I3-01 = `UC-09` (Consultar Stock), `UC-10` (Configurar Receta de Producto), `UC-28` (Registrar Preparación de Pedido) y la actualización contextual de `BuscarPedidoService` (`UC-34`) para `RESPONSABLE_PRODUCCION`.

## 2. Cortes mergeados en `main`

| Sub-corte | UC | Merge | CI |
|---|---|---|---|
| I3-01a | UC-09 | `aae5c3c` | 21/21 |
| I3-01b | UC-10 | `df9b2e2` | 22/22 |
| I3-01c | UC-28 | `20ce82d` | 23/23 |
| Fix contractual | UC-28 | `62bc3b0` | 8/8 |

Los SHAs fueron verificados en `main` (`git log`) el 09/11/2026; HEAD de `main` al redactar: `62bc3b0`. Los conteos de CI se transcriben del encargo y no se re-ejecutaron al redactar esta acta.

## 3. Criterios de aceptación verificables

| Objetivo del bloque | Verificado | Evidencia |
|---|---|---|
| Consultar existencias físicas y utilizables | ✅ | `ConsultarStockServiceIntegrationTest` (`stockUtilizableExcluyeVencidas`, `partidasOrdenadasPorD4`) |
| Recetas versionadas sin solapamiento | ✅ | `ConfigurarRecetaServiceIntegrationTest` |
| Preparación respeta cantidad original | ✅ | `RegistrarPreparacionServiceIntegrationTest` |
| Consumo de partidas por D4 (FEFO) | ✅ | `preparacionSeleccionaPartidasPorD4` |
| Movimientos con `origen='PRODUCCION'` | ✅ | `preparacionRegistraOrigenProduccion` |
| Integridad costos históricos | ✅ | Caso `COSTO_HISTORICO_REQUERIDO` dentro de `rollbackSiFallaMitadNoPersisteNada` (`RegistrarPreparacionServiceIntegrationTest`, L219–222) |
| `BuscarPedidoService` con PRODUCCION contextual | ✅ | `produccionVeSoloPedidosConProductosElaborados` (`BuscarPedidoServiceIntegrationTest`) |
| `partidas: []` → automático | ✅ | post-fix `62bc3b0` |
| `marcarApertura` preserva apertura previa | ✅ | `marcarAperturaTrueNoSobrescribeAperturaPrevia` (post-fix) |
| Autorización por rol | ✅ | `rechazaRolNoAdmin` (UC-10), `rechazaRolNoProduccion` (UC-28), `adminRechazado` (UC-09) |

## 4. Desviaciones y reservas (2 reales, ambas diferidas)

| # | Discrepancia | Estado | Acción |
|---|---|---|---|
| 1 | Idempotencia UC-28 diferida | 🟡 Diferida | `RD-UC28-002` futuro. Riesgo residual: un reintento puede duplicar la preparación si cabe en el saldo (`cantidadPreparadaPropiaAcumulada + cantidadPreparada <= cantidadOriginal`). |
| 2 | Invalidación de rutas UC-25 | 🟡 Diferida | Deuda transversal. Se resuelve cuando UC-25 exista (`RutaReparto` es conceptual, sin tabla física en MR-6). |

Las 2 discrepancias menores (`partidas: []` y `marcarApertura` con `COALESCE`) quedaron resueltas en el fix #83 (`62bc3b0`).

## 5. Trazabilidad canónica

- `source_commit (G0)`: `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` (inmutable).
- `RD-I301-001`: `1f6a4c7` en `ale-milanesas` (autoriza 9 decisiones: D-U10-1/2, G1, G2, G4, G5, G6 y `BuscarPedidoService` contextual).
- Predecesoras: `RD-MR-PARTIDAS-001`, `RD-RP022-001`, `RD-UC19-002`, `RD-UC08-001`, `RD-UC42-001`.
- Servicio reutilizado sin modificar: `EpisodioFaltanteService` (`evaluarApertura`).

## 6. Próximo paso

- **I3-02:** `UC-05` (Registrar Entrega), `UC-07` (Excedentes), `UC-41`.
- **I3-03:** `UC-14` (Cancelaciones), `UC-29` (Cobro manual), `UC-22`, `UC-23`.
- Acta final de I3 al cerrar I3-03. Cada sub-bloque requiere auditoría documental previa y, de corresponder, RD.

## Firma

**Alumno:** Victor Leandro Machado.
**Fecha:** 09/11/2026.
