# B4-03-03 — UC-38 Cambiar Estado de Cliente vía HTTP bajo gate no-prod

**Fecha:** 01/11/2026. **Estado:** implementación propuesta en rama; PR Draft pendiente de revisión, CI y decisión humana. [Issue #41](https://github.com/leandrito369/ale-milanesas-app/issues/41).

**G0:** `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`; `governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`. MR5/F01 y fuente G0 inmutables.

## Contrato, DSS/DS, permisos y visibilidad

- [REV06B §4 UC38](https://github.com/leandrito369/ale-milanesas/blob/1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e/docs/08-diagramas/revision/REV06B_CLIENTES_RECETAS_INVENTARIO/propuestos/CONTRATOS_REV06B_TRECE_CORREGIDOS.md) y [DSS/DS REV06B1](https://github.com/leandrito369/ale-milanesas/tree/1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e/docs/08-diagramas/revision/REV06B1_CLIENTES_CATEGORIA/propuestos), F09, RD-RP007-002, RF-10, matriz RBAC 67×8 y BC07. UC38 «include» UC33 para localizar Cliente, sin convertir esta mutación en una búsqueda nueva.
- Permisos canónicos: `ADMINISTRADOR=D`, `VENDEDOR=NR`; demás actores `NR`. Ser Administrador es requisito tanto de ruta HTTP como del servicio B3, que revalida sesión y roles en transacción. Ninguna autoridad se obtiene del body.
- Adaptador propuesto: `PATCH /api/v1/clientes/{idCliente}/estado`, `Content-Type: application/json`, body estricto `{ "nuevoEstado": "ACTIVO" | "INACTIVO", "motivo": "texto opcional" }`. `idCliente` positivo en la ruta, no en body. Solo esas dos claves JSON; `motivo` acepta ausencia/null y hasta 2000 caracteres. No aceptar `estado`, `categoriaComercial`, PII, IDs de actor, roles ni propiedades desconocidas.
- Respuesta `200` mínima `{idCliente,estado,huboCambio}` (incluyendo no-op). No incluye `numeroCambio`, motivo, datos personales ni historial de terceros. Errores `400` solicitud inválida/reactivación incompleta, `404` Cliente inexistente, `401` Bearer ausente/inválido, `403` sin rol, `503` falla DB. `Cache-Control: no-store`, códigos genéricos sin SQL ni PII.

## Separación de gates y atomicidad

- Feature flag exclusivo `ale.http.writes.clientes-uc38-enabled=false` por defecto en `SeguridadHttpInterna`; `@Profile("!prod")` y `@ConditionalOnProperty(havingValue="true")` impiden registrar el controlador fuera del gate. `PrecondicionesDespliegue` aborta arranque `prod` si el flag es `true`, incluso si su ruta no está registrada. El unit de aceptación mantiene UC02/UC32/**UC38** explícitamente apagados.
- Se preservan UC02 (POST, flag propio) y UC32 (PUT, flag propio), no se amplían otros verbos/rutas; `anyRequest().denyAll()` para el resto. La configuración del test focalizado usa UC38 `true` solo en el contexto no-prod.
- `GestionClientesService.cambiarEstadoCliente`: transacción, `pg_advisory_xact_lock(3202001)`, segundo control RBAC, `SELECT CLIENTE FOR UPDATE`; transición real escribe una fila de `HISTORIAL_ESTADO_CLIENTE` con secuencia, anterior/nuevo, motivo, timestamp y Administrador. A2/no-op no crea fila. Reactivación solo con datos obligatorios válidos (A3). Fallo de DB/conflicto revierte estado e historial (A4).
- INACTIVO bloquea OTP pendientes y revoca sesiones ONLINE; **no cancela Pedidos PENDIENTE, no borra Cuenta Corriente/histórico, y permite regularizar deuda**. Impedir nuevos Pedidos/ofertas/promociones corresponde a servicios que validan estado en la confirmación, no a borrar relaciones existentes. No se agregan columnas/DDL.

## Matriz de prueba reproducible

`EstadoClienteHttpExternoTest`: servidor en puerto HTTP real (loopback), PostgreSQL 17/Testcontainers, fixtures persistidos antes de peticiones, contexto UC38 habilitado exclusivamente en test.

1. Admin inactiva: 200/no-store, mínimo JSON, historial con actor, motivo y secuencia.
2. Vendedor, Repartidor y sesión Admin restringida 403; ausencia de Bearer y cookie web 401; PUT UC32 sigue 403.
3. Body desconocido, estado inválido, IDs forjados, motivo fuera de rango, JSON malformado e ID cero 400; inexistente 404; ninguno escribe.
4. No-op no crea historial. INACTIVO→ACTIVO valida datos; historia numerada 1/2 sin eventos artificiales.
5. F09: Pedido PENDIENTE y movimiento de Cuenta Corriente sobreviven a inactivación; OTP bloqueado y sesión ONLINE revocada.
6. Trigger de error sobre inserción de historial: HTTP 503 sin revelar SQL; rollback del estado y sin historial.
7. Dos PATCH concurrentes al mismo destino: una transición real y un no-op; una sola fila histórica.
8. `PrecondicionesDespliegueTest`: `prod` rechaza flag UC38 activo, aunque otros flags queden apagados.

**Gate de CI pendiente:** B1 Java21/Testcontainers/JaCoCo; B2 MR5/F01/MR6, SonarCloud y regresión UC02/UC32/UC53/CSRF sobre **SHA final de la PR**. No anticipar PASS. El HTTP de loopback no certifica TLS/DNS/Nginx/firewall/singleton/429 ni los **0/4 E2E humanos UC54**; [Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) debe seguir abierta. BC06 original 0/18 E2E; no se acredita. Prohibida activación productiva y merge sin revisión/autorización.

## Trazabilidad

UC38 → contrato REV06B §4 + DSS/DS REV06B1 + F09 → RBAC `ADMINISTRADOR` → `GestionClientesService.cambiarEstadoCliente` B3 → MR5 `CLIENTE`, `HISTORIAL_ESTADO_CLIENTE`, `DESAFIO_AUTENTICACION_CLIENTE`, `SESION_CLIENTE_ONLINE` (Pedidos y Cuenta Corriente preservados) → `EstadoClienteHttpController` PATCH gated → `EstadoClienteHttpExternoTest` + `PrecondicionesDespliegueTest`.
