# B4-03-05 / UC-35 — Buscar Producto HTTP (solo lectura, Admin y Vendedor)

**Issue:** #46
**Rama:** `feat/b4-03-05-uc35-producto-http-2026-11-03`
**Base:** `main` @ `2e16a65` (acta PR #45 mergeada)
**source_commit (G0):** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` (inmutable)
**governance_commit:** `<hash del commit de este documento>`
**Estado:** implementado; pendiente de activación por pausa de despliegue.

## 1. Alcance y fuentes normativas

- **Contrato canónico:** `docs/08-diagramas/consolidacion-b0/CONTRATOS_REV06A_NUEVE_OPERACIONES_B0_ACTUALIZADO.md` §1 (`buscarProducto`)
- **Ficha Fase 4:** `UC-35 — Buscar Producto` (Alto Nivel, Extendido y Real)
- **Decisión RBAC:** `RD-RP007-002` — solo `ADMINISTRADOR` y `VENDEDOR`
- **Proyección segura:** `BC-07` + `RD-UC30-001` (nunca `stockActualReventa`, `costoPromedioActualReventa` ni `nombreNormalizado`)
- **Esquema:** tabla `"PRODUCTO"` del baseline `MR-5` / `F01` — **sin cambios**
- **Línea base:** `G0` — **sin modificar**

## 2. Contrato HTTP

`GET /api/v1/productos`

| Aspecto | Detalle |
|---|---|
| Autenticación | `Bearer` vigente, `cambioCredencialPendiente = false` |
| Roles | `ADMINISTRADOR`, `VENDEDOR` |
| Parámetros (whitelist) | `idProducto` (Long > 0), `nombre` (String), `tipoProducto` (`ELABORADO`\|`REVENTA`), `activo` (`true`\|`false`) |
| Respuesta 200 | Array de 6 campos: `idProducto, nombre, descripcion, tipoProducto, codigoUnidadVenta, activo` |
| Respuesta 400 | `{"codigo":"SOLICITUD_INVALIDA"}` |
| Respuesta 401 | Sin token, cookie `__Host-ale_session`, UUID crudo o sesión revocada |
| Respuesta 403 | Rol no autorizado o cambio pendiente |
| Respuesta 503 | `{"codigo":"PERSISTENCIA_NO_DISPONIBLE"}` |
| Cache | `Cache-Control: no-store` en todas las respuestas |

## 3. Matriz CU → Servicio → DB → API → Test

| Capa | Elemento | Archivo |
|---|---|---|
| CU | UC-35 Buscar Producto | ficha Fase 4 |
| Servicio | `BuscarProductoService.buscarProducto(UUID, CriterioProducto)` | `maestros/BuscarProductoService.java:34` |
| DB | `SELECT ... FROM "PRODUCTO" p WHERE 1=1 ...` (sin cambios de esquema) | `BuscarProductoService.java:44` |
| API | `ConsultaProductoHttpController.buscar(...)` | `web/ConsultaProductoHttpController.java` |
| Seguridad | matcher `GET /api/v1/productos` | `web/SeguridadHttpInterna.java:105` |
| Test HTTP | `ConsultaProductoHttpExternoTest` | `web/ConsultaProductoHttpExternoTest.java` |
| Regresión UC-30 | `RegistroProductoHttpExternoTest` | `web/RegistroProductoHttpExternoTest.java` |

## 4. Invariantes preservados

- No expone `stockActualReventa`, `costoPromedioActualReventa` ni `nombreNormalizado`.
- No habilita `PUT` / `PATCH` / `DELETE` de Productos (`UC-47`, `UC-48`).
- No activa `UC-30` en producción (gate apagado por defecto).
- `Cache-Control: no-store` en todas las respuestas.

## 5. Bloqueo de despliegue

Entorno **PAUSADO** por el responsable (02/11/2026). Issue #28 permanece **ABIERTA**.

## 6. Dependencias

- **PR #44 / Issue #43** (UC-30): mergeado en `main` como `b19c950`.
- **PR #45** (acta de revisión): mergeado en `main` como `2e16a65`.
- **Issue #46** (esta implementación): PR #47.
