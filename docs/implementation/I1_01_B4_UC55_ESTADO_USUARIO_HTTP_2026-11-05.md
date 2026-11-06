# I1-01 / B4 — UC-55 Cambiar Estado de Usuario HTTP

**Fecha:** 05/11/2026. **Estado:** implementación en PR en borrador, pendiente de CI y revisión de Lean.
**Issue:** [#60](https://github.com/leandrito369/ale-milanesas-app/issues/60).
**Rama:** `feat/i1-01-b4-uc55-estado-usuario-http-2026-11-05`. **Base:** `main` @ `7598d854e38b850634658304df7aeec7bb6290db`.
**Despliegue:** PAUSADO por Issue #28. No se solicita activación productiva.

## 1. Fuente contractual y decisiones

- **`source_commit` G0:** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`.
- **`governance_commit` G0:** `27738b089afdd2698cdae915f75c0e8089a6248a`.
- **Contrato:** `docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/contratos/CONTRATO_UC55_REV02.md`.
- **DS/DSS:** `docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/DS_REV02_SEGURIDAD_CORREGIDO.drawio.xml` y `DSS_REV02_SEGURIDAD_CORREGIDO.drawio.xml`, página de UC-55; ficha Real/Extendida/Alto de UC-55 en Fase4. `UC-55` incluye `UC-56` para localizar al Usuario.
- **RD vinculantes:** `RD-RP009-001` y `RD-RP002-001` para la precedencia de reasignación de Pedidos por UC-43; F07 para protección del último Administrador. Sin nuevas decisiones de negocio.
- **RBAC:** `ADMINISTRADOR (D)`; demás roles/canales `NR`. La respuesta BC07 contiene solamente identidad de objeto y estado resultante; A4 informa IDs de Pedidos pendientes expresamente exigidos por contrato.
- **Persistencia MR5/F01:** `USUARIO`, `USUARIO_ROL`, `USUARIO_PROVEEDOR`, `SESION_USUARIO`, `PEDIDO` y `ASIGNACION_REPARTO`. **No hay DDL, Flyway ni cambio en `source-lock.md`**. `PARTIDA_INSUMO`/MR6 sigue su propia RP.

## 2. Contrato HTTP, autorización y alternativas

`PATCH /api/v1/usuarios/{idUsuario}/estado`, con cuerpo JSON de campo único:

```json
{"nuevoEstado":"DESHABILITADO"}
```

Solo se permiten `HABILITADO` y `DESHABILITADO`, sin campos extra, repetidos ni conversiones. Se exige sesión Bearer ADMINISTRADOR válida y se revalida en B3 después de tomar las cerraduras transaccionales `9172001`, `60505,7` y `USUARIO FOR UPDATE`. La selección en UC-56 se realiza en el flujo de interfaz; el endpoint recibe el identificador seleccionado y no introduce otra API de selección.

| Caso | Respuesta y efecto |
|---|---|
| Transición válida | `200`: `idUsuario`, `estado`, `huboCambio=true`. Actualiza `fechaHoraDeshabilitacion` y, al deshabilitar, revoca sesiones mediante trigger MR5. |
| A1 — Estado ya vigente | `200`, `huboCambio=false`; sin UPDATE, marca temporal ni revocación nueva. |
| A1 — Usuario inexistente | `404 USUARIO_NO_ENCONTRADO`; sin efecto. |
| A2 — Último ADMIN habilitado | `409 ESTADO_USUARIO_BLOQUEADO`; no deshabilita. |
| A3 — PROVEEDOR con otra cuenta habilitada | `409 ESTADO_USUARIO_BLOQUEADO`; no habilita. |
| A4 — REPARTIDOR con Pedidos `PENDIENTE` asignados | `409 REPARTIDOR_CON_PEDIDOS_PENDIENTES`, lista `pedidosPendientes` de IDs. Se debe reasignar mediante UC-43 antes; no se realiza reasignación implícita. |
| Entrada inválida / sin autorización | `400 SOLICITUD_INVALIDA`, `401 SESION_INVALIDA` o `403 OPERACION_PROHIBIDA`. |
| Fallo de persistencia | `503 PERSISTENCIA_NO_DISPONIBLE`, sin detalles SQL; reversión transaccional. |

Se conservan Roles, vínculos comerciales, Pedidos y Asignaciones históricas. La habilitación no reabre sesiones revocadas. Las respuestas incluyen `Cache-Control: no-store`; se excluyen credenciales, hashes, tokens y datos de cuenta ajenos al contrato.

## 3. Matriz CU → implementación → prueba

| Capa | Archivo / responsabilidad |
|---|---|
| B3 | `CambiarEstadoUsuarioService` y resultado `CambioEstadoUsuario`, preexistentes en `main`; solo se actualiza comentario de integración B4. |
| B4 | `EstadoUsuarioHttpController`: entrada JSON estricta, salida mínima, estado y detalle A4 autorizado. |
| Seguridad | `SeguridadHttpInterna`: `PATCH /api/v1/usuarios/*/estado` solo ADMIN bajo flag `ale.http.writes.usuarios-uc55-enabled=false` por defecto. |
| Arranque prod | `@Profile("!prod")` y veto en `PrecondicionesDespliegue` si se fuerza el flag. |
| Integración | `EstadoUsuarioHttpExternoTest`: puerto HTTP real y PostgreSQL 17.11/Testcontainers; 10 pruebas propuestas de estado, sesión, F07, PROVEEDOR, RD-RP009-001, RBAC, formatos inválidos, reversión y concurrencia. |
| Regresión | `PrecondicionesDespliegueTest`: bandera prohibida en prod y controlador ausente incluso al solicitarla. |

## 4. Validación, dependencia y reservas

- `mvn test-compile`: **NO EJECUTADO localmente**, sin Maven ni compilador. GitHub Actions asume esa validación por autorización general de Lean para I1.
- PostgreSQL/Testcontainers: **NO EJECUTADOS localmente**, sin Docker. Se ejecutarán en GitHub Actions.
- Publicar antes de compilación local es una excepción ratificada el 05/11/2026. Ante fallos se suman commits nuevos, sin rebase/amend/force-push.
- CI B1/B2/B4: **PENDIENTE al redactar el documento**. La evidencia real se incorpora al PR.
- Revisión del diff y aprobación explícita de Lean: **PENDIENTES**. PR **draft**, sin merge ni despliegue; #28 y E2E humanos siguen pendientes.
- El contrato UC55 incluye la búsqueda/selección UC56, cuyo PR de interfaz está en revisión independiente. La prueba de este adaptador **no acredita el flujo visual completo**.
- La prueba A4 simula con datos sintéticos el cierre de una asignación, pero **UC43 aún no comparte un protocolo de bloqueo implementado**. No se afirma resuelta la carrera con escritores externos de UC43 ni se acredita E2E de reasignación real.
