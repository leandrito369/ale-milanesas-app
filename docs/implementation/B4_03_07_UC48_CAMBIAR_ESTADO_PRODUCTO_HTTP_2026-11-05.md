# B4-03-07 / UC-48 — Cambiar Estado Producto HTTP (ADMIN, gate no-prod)

**Issue:** #52  
**Rama:** `feat/b4-03-07-uc48-producto-patch-2026-11-05`  
**Base:** `main` @ `feadba4` (PR #50 integrado)  
**source_commit (G0):** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` (inmutable)  
**RD específica:** `RD-UC30-001` @ `4971625a215d94a8b9f6439d9b0836e0925ea1ad`  
**governance_commit:** `27738b089afdd2698cdae915f75c0e8089a6248a` (acta RD-B0-G0-001)  
**Estado:** cambio HTTP propuesto; pendiente de CI y revisión humana. Despliegue PAUSADO.

## 1. Objetivo y fuentes normativas

- **Operación:** UC-48 — Cambiar Estado de Producto (`cambiarEstadoProducto`).
- **Contrato:** `docs/08-diagramas/consolidacion-b0/CONTRATOS_REV06A_NUEVE_OPERACIONES_B0_ACTUALIZADO.md` §4 (fuente de gobierno).
- **Ficha Fase 4:** UC-48 «include» UC-35; DSS `DSS_REV06A_CORREGIDO.drawio.xml`, DS `DS_REV06A_COMPLETO_CORREGIDO.drawio.xml` (REV06A, commit G0).
- **RD:** RD-UC30-001 — identidad estable de Producto, normalización y conservación de historial; no nueva auditoría de eventos.
- **RBAC:** exclusivamente ADMINISTRADOR autorizado, con Bearer vigente y sin `cambioCredencialPendiente`; siete canales restantes sin permiso.
- **Esquema:** `PRODUCTO.activo` y disparadores F09 de MR-5/F01; **sin cambios de tablas, columnas ni migraciones**.

## 2. Contrato HTTP

`PATCH /api/v1/productos/{idProducto}/estado`

| Condición | Respuesta |
|---|---|
| Cuerpo JSON estricto | Solo `{"activo": true}` o `{"activo": false}`; duplicados y campos adicionales rechazados |
| Transición válida | `200 {"idProducto":N,"activo":boolean,"huboCambio":true}` |
| Mismo estado solicitado | `200`, `huboCambio=false` (sin escritura de estado) |
| ID no positivo / JSON inválido / tipo incorrecto | `400 SOLICITUD_INVALIDA` |
| Sin Bearer, cookie en vez de Bearer o sesión inválida | `401 SESION_INVALIDA` |
| Rol no ADMIN o cambio de credencial pendiente | `403 OPERACION_PROHIBIDA` |
| Producto inexistente | `404 PRODUCTO_NO_ENCONTRADO` |
| Ficha o unidad actual inconsistente al reactivar | `409 PRODUCTO_FICHA_INVALIDA` (corregir vía UC-47) |
| Error de persistencia | `503 PERSISTENCIA_NO_DISPONIBLE` |
| Todas las respuestas | `Cache-Control: no-store` |

La respuesta solo expone `idProducto`, `activo` y `huboCambio`. No publica nombre normalizado, stock, costos ni relaciones históricas.

## 3. Matriz CU → Servicio → DB → API → Test

| Capa | Elemento |
|---|---|
| CU | UC-48 — Cambiar Estado de Producto |
| Servicio B3 | `GestionProductoService.cambiarEstadoProducto(UUID, long, Boolean)` |
| DB | `UPDATE "PRODUCTO" SET "activo"=...` únicamente si cambia el estado; lectura bloqueada y validación de ficha para reactivar |
| Integridad | F09 impide nuevos vínculos comerciales con Producto inactivo; conserva `DETALLE_PEDIDO` y `PEDIDO PENDIENTE` históricos |
| API B4 | `EstadoProductoHttpController.cambiarEstado(...)` |
| Seguridad | Matcher PATCH `"/api/v1/productos/*/estado"`, ADMIN y gate independiente |
| Test HTTP | `EstadoProductoHttpExternoTest`, puerto real y PostgreSQL 17.11 (si Docker disponible) |
| Regresión B3 | `GestionProductoServiceIntegrationTest`, `ProductoConcurrenciaIntegrationTest` y suites HTTP de Productos |

## 4. Invariantes

- La baja lógica no cancela Pedidos PENDIENTE ni elimina Recetas, precios, Órdenes, inventario o referencias históricas.
- La reactivación comprueba ficha y unidad vigentes, **no exige precio**: ACTIVO ≠ comercializable sin UC-19.
- Estado igual al solicitado es no-op. Revalidación de autorización y del Producto ocurre en B3 dentro de la transacción.
- No modifica ficha o unidad (UC-47), no crea precios (UC-19), recetas (UC-10) ni tabla de auditoría de estados.
- F09 vigente en V1 controla selección inicial y cambios de Producto en `DETALLE_PEDIDO`.

## 5. Triple gate no-prod

1. Controlador con `@Profile("!prod")` y `@ConditionalOnProperty(productos-uc48-enabled=true)`.
2. `SeguridadHttpInterna` exige `productos-uc48-enabled` (false por defecto) y ADMINISTRADOR.
3. `PrecondicionesDespliegue` ya veta el flag `productos-uc48-enabled=true` bajo perfil productivo; no se modifica.

Ni UC-30 ni UC-47 habilitan implícitamente UC-48. `application.yml` y `application-prod.yml` permanecen intactos.

## 6. Bloqueo de despliegue

El entorno continúa **PAUSADO** por decisión del responsable, con **Issue #28 ABIERTA**. Este corte ofrece únicamente exposición de desarrollo controlado y **no autoriza ningún despliegue ni cambio de gate**.

## 7. Dependencias

- PR #44 / Issue #43 (UC-30): `b19c950`.
- PR #47 / Issue #46 (UC-35): `30415e3`.
- PR #50 / Issue #49 (UC-47): `feadba4`.
- PR #51: documento de conclusiones sobre MR; **independiente y sin impacto en UC-48**.
- Issue #52: ejecución B4-03-07/UC-48; el PR de este corte debe vincularla mediante `Closes #52`, pero **no fusionarla automáticamente**.

## 8. Evidencia y condición de cierre

Las pruebas deben registrar el resultado **real** de `mvn test-compile`, HTTP focalizada, regresiones y seguridad; una ejecución omitida por ausencia de Docker **no demuestra** que F09 o el endpoint funcionen en PostgreSQL. CI debe quedar verde antes de solicitar revisión humana. Se conserva el veto de despliegue hasta autorización expresa del responsable.
