Closes #<ISSUE_UC35>

## Alcance
Exposición HTTP de solo lectura de `UC-35 — Buscar Producto` en `GET /api/v1/productos`,
delegando en `BuscarProductoService.buscarProducto` (B3). Sin cambios en esquema `MR-5`/`F01`,
sin activar `UC-30` en producción.

## Cambios
- [x] `ConsultaProductoHttpController` con lista blanca `idProducto|nombre|tipoProducto|activo`.
- [x] `SeguridadHttpInterna`: GET autorizado a `ADMINISTRADOR` y `VENDEDOR` (`RD-RP007-002`).
- [x] Ajuste del test de UC-30 (`RegistroProductoHttpExternoTest`) — ya no espera 403 en GET, verifica DELETE/PUT bloqueados.
- [x] Suite `ConsultaProductoHttpExternoTest` (RANDOM_PORT + PostgreSQL 17.11 Testcontainers).
- [x] Workflow focalizado `.github/workflows/b4-03-05-uc35-http-focused.yml`.
- [x] Trazabilidad en `docs/implementation/B4_03_05_UC35_BUSCAR_PRODUCTO_HTTP_2026-11-03.md`.

## Invariantes
- Nunca expone `stockActualReventa`, `costoPromedioActualReventa`, `nombreNormalizado`.
- `Cache-Control: no-store` en 200/400/403/503.
- No habilita PUT/PATCH/DELETE (UC-47/UC-48).

## Bloqueo de despliegue
Entorno **PAUSADO** por el responsable (02/11/2026). Issue #28 permanece **ABIERTA**.

## Dependencias
- **PR #44 / Issue #43** (UC-30, mergeado `b19c950` el 2026-11-03): provee `RegistroProductoHttpExternoTest` y `RegistroProductoHttpController`.
- **PR #45** (acta de revisión, `docs/traceability/ACTA_REVISION_PR44_2026-11-03.md`): autoriza el merge controlado bajo pausa de despliegue.
- **Issue #28** (pausa de despliegue): permanece ABIERTA.

## Checklist
- [ ] `mvn -Dtest=ConsultaProductoHttpExternoTest,RegistroProductoHttpExternoTest test` verde en local
- [ ] Workflow `b4-03-05-uc35-http-focused` verde en CI
- [ ] Revisión de código por pares
- [ ] Issue #<ISSUE_UC35> vinculada con `Closes #<ISSUE_UC35>`
