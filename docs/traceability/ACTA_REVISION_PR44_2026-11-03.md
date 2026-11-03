### Dictamen de Revisión e Integración Controlada — B4-03-04 / UC-30 (`PR #44` / `Issue #43`)

**Fecha:** 03/11/2026  
**Rama evaluada:** `feat/b4-03-04-uc30-producto-http-2026-11-02` (base `main` `5b0fdbd224bb86f156330e96cdc200802b9c855f`)  
**Decisión:** **APROBADA PARA MERGE CONTROLADO BAJO GATE NO-PROD** — Cierra la **Issue #43**.

---

#### 1. Cumplimiento Normativo, Contractual y RBAC (`G0` / `REV-06A` §2 / `RD-UC30-001` / `Fase 4`)
* **Alcance estricto del maestro (`UC-30`):** `POST /api/v1/productos` registra únicamente la fila en `"PRODUCTO"` con `activo = true`, `stockActualReventa = NULL` y `costoPromedioActualReventa = NULL`, sin inventar precios (`UC-19`), recetas (`UC-10`), auditorías de precio ni movimientos/valoraciones de stock.
* **Trazabilidad (`RD-UC30-001`):** Se registra la referencia a `RD-UC30-001` (`4971625a215d94a8b9f6439d9b0836e0925ea1ad`) en `docs/traceability/source-lock.md`, manteniendo inmutables el `source_commit` de `G0` (`1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`) y el modelo físico `MR-5`/`F01`.
* **Control de acceso (`RBAC`) y sesiones:** Restringido exclusivamente al rol `ADMINISTRADOR` con sesión `Bearer` vigente y sin `cambioCredencialPendiente`. Rechaza con `HTTP 401`/`403` ausencias de token, cookies (`__Host-ale_session`), `UUID` de sesión aislado, sesiones revocadas/usuarios deshabilitados y los roles `VENDEDOR`, `RESPONSABLE_PRODUCCION`, `REPARTIDOR` y `PROVEEDOR`.
* **Unicidad y coincidencia contextual (`A2` + `UC-35` contextual + `BC-07`):** `GestionProductoService.asegurarNombreUnico` lanza `ProductoDuplicadoException` (subtipo compatible de `IllegalArgumentException` para `UC-47`) dentro de la misma transacción protegida por `pg_advisory_xact_lock(3030047)`, devolviendo `HTTP 409 CONFLICT` (`codigo = PRODUCTO_DUPLICADO`) con los 6 campos autorizados (`idProducto`, `nombre`, `descripcion`, `tipoProducto`, `codigoUnidadVenta`, `activo`) tanto para productos activos como inactivos, sin exponer costos, stock ni `nombreNormalizado`.
* **Higiene HTTP y manejo de errores:** Lista blanca estricta de campos JSON (`nombre`, `descripcion`, `tipoProducto`, `codigoUnidadVenta`), rechazo de caracteres `NUL` (`\0`) con `HTTP 400 SOLICITUD_INVALIDA`, respuestas con `Cache-Control: no-store` y reversión completa (`HTTP 503 PERSISTENCIA_NO_DISPONIBLE`) ante fallos posteriores al `INSERT` sin filtrar mensajes SQL internos.

#### 2. Evidencia de Gates Automáticos en Verde sobre el HEAD del PR #44
* **CI B1 (Unitarias + Integración Testcontainers PostgreSQL + JaCoCo):** `SUCCESS` — suite completa en verde sin regresiones.
* **CI B2 (Migraciones Flyway `MR-5`/`F01`, triple huella `MR-6` y backup/restore):** `SUCCESS` — 0 cambios de esquema.
* **Workflow HTTP Focalizado (`B4-03-04 - UC30 alta Producto HTTP`):** `SUCCESS` — `RegistroProductoHttpExternoTest` (altas `ELABORADO`/`REVENTA`, duplicados activos/inactivos, validaciones 400, RBAC 401/403, revocación/cambio de rol en caliente, rollback 503 con trigger en Testcontainers y carrera concurrente 201/409) + regresión `GestionProductoServiceIntegrationTest`, `ProductoConcurrenciaIntegrationTest`, `PrecondicionesDespliegueTest`, `ConsultasI1HttpIntegrationTest` y `AutenticacionHttpExternaTest`.
* **SonarCloud Quality Gate y Diagnóstico (`b4-03-04-sonar-diagnostico.yml`):** `SUCCESS` (`OK`) — `0` issues abiertos, `0` vulnerabilidades, Security Rating `A`.

#### 3. Condición de Integración y Límites Expresos (No Activación Productiva)
* Se integra a `main` con `ale.http.writes.productos-uc30-enabled=false` por defecto, `@Profile("!prod")` y `@ConditionalOnProperty` en `RegistroProductoHttpController`, veto *fail-fast* en `PrecondicionesDespliegue` y flag en `false` en `deploy/acceptance/ale-acceptance.service`.
* **Límites mantenidos:** Despliegue del entorno pausado por el responsable; la **Issue #28** permanece **ABIERTA**, los escenarios humanos E2E de `UC-54` siguen en `0/4` y `BC-06` en `0/18` E2E. Este corte no habilita `GET`/`PUT`/`PATCH` de Productos (`UC-35`, `UC-47`, `UC-48`).