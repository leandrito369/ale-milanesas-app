# B4-03-06 / UC-47 — Modificar Producto HTTP (ADMIN, gate no-prod)

**Issue:** #49
**Rama:** `feat/b4-03-06-uc47-producto-put-2026-11-05`
**Base:** `main` @ `d25ec9c` (PR #48 mergeado)
**source_commit (G0):** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` (inmutable)
**governance_commit:** `62c11a73053edcc91499a1ab1438b1945a9ad547`
**Estado:** implementado; pendiente de activación por pausa de despliegue.

## 1. Alcance y fuentes normativas
- **Contrato canónico:** `CONTRATOS_REV06A_NUEVE_OPERACIONES_B0_ACTUALIZADO.md` §3 (`modificarProducto`)
- **Ficha Fase 4:** UC-47 — Modificar Producto («include» UC-35)
- **RBAC:** exclusivamente `ADMINISTRADOR`
- **Esquema:** `"PRODUCTO"` (MR-5) — sin cambios
- **Línea base:** `G0` — sin modificar
- **Trazabilidad:** `RD-UC30-001` (Issue #19 cerrada)

## 2. Contrato HTTP
`PUT /api/v1/productos/{idProducto}`

| Aspecto | Detalle |
|---|---|
| Autenticación | Bearer vigente, `cambioCredencialPendiente = false` |
| Roles | Solo `ADMINISTRADOR` |
| Body (whitelist) | `nombre`, `descripcion`, `tipoProducto` (`ELABORADO`\|`REVENTA`), `codigoUnidadVenta` |
| Respuesta 200 | `{ idProducto, activo, huboCambio }` |
| Respuesta 400 | `SOLICITUD_INVALIDA` |
| Respuesta 401 | Sin token, cookie o sesión revocada |
| Respuesta 403 | Rol no autorizado o cambio pendiente |
| Respuesta 404 | `PRODUCTO_NO_ENCONTRADO` |
| Respuesta 409 | `PRODUCTO_DUPLICADO` (incluye inactivos) o `PRODUCTO_CON_DEPENDENCIAS` |
| Respuesta 503 | `PERSISTENCIA_NO_DISPONIBLE` |
| Cache | `Cache-Control: no-store` en todas |

## 3. Matriz CU → Servicio → DB → API → Test
| Capa | Elemento |
|---|---|
| CU | UC-47 Modificar Producto |
| Servicio | `GestionProductoService.modificarProducto(UUID, long, String, String, String, String)` |
| DB | `UPDATE "PRODUCTO" SET nombre, nombreNormalizado, descripcion, tipoProducto, codigoUnidadVenta` |
| Dependencias | `tieneHistoria()` → 7 `EXISTS` sobre `DETALLE_PEDIDO`, `PRECIO_PRODUCTO`, `RECETA`, `PROMOCION_PRODUCTO`, `RECEPCION_COMPRA`, `MOVIMIENTO_STOCK_REVENTA`, `VALORACION_EXTRAORDINARIA_REVENTA` |
| API | `ModificacionProductoHttpController.modificar(...)` |
| Seguridad | matcher `PUT /api/v1/productos/*` con gate `productos-uc47-enabled` |
| Test HTTP | `ModificacionProductoHttpExternoTest` |
| Test B3 | `GestionProductoServiceIntegrationTest` (existente) |

## 4. Invariantes preservados
- No modifica `activo` (corresponde a UC-48).
- No crea ni altera `PRECIO_PRODUCTO` / `AUDITORIA_PRECIO` (UC-19).
- No modifica `RECETA` (UC-10).
- No expone stock, costos ni `nombreNormalizado`.

## 5. Triple gate no-prod
1. `@Profile("!prod")` + `@ConditionalOnProperty(productos-uc47-enabled=true)`.
2. Flag `habilitarModificarProducto` en `SeguridadHttpInterna` (default false).
3. Veto en `PrecondicionesDespliegue` si `productos-uc47-enabled=true` bajo perfil prod.

## 6. Bloqueo de despliegue
Entorno PAUSADO por el responsable (02/11/2026). Issue #28 ABIERTA.

## 7. Dependencias
- PR #44 / Issue #43 (UC-30): `b19c950`.
- PR #45 (acta): `2e16a65`.
- PR #47 / Issue #46 (UC-35): `30415e3`.
- PR #48 (nota trigger MR-5): `d25ec9c`.
