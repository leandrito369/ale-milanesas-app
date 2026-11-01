# B4-03-02 — UC-32 Modificar Cliente vía HTTP bajo gate

**Fecha:** 01/11/2026. **Estado:** PR Draft no integrada ni publicada. **Issue [#37](https://github.com/leandrito369/ale-milanesas-app/issues/37)**.  
**Lock normativo G0:** `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, `governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`; MR5/F01 sin cambios.

## Contrato y permisos

[REV06B UC32, sección 3](https://github.com/leandrito369/ale-milanesas/blob/1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e/docs/08-diagramas/revision/REV06B_CLIENTES_RECETAS_INVENTARIO/propuestos/CONTRATOS_REV06B_TRECE_CORREGIDOS.md), DSS/DS REV06B1, I1-02 B3, F09, RD-RP007-002, RBAC. `UC33` sirve como búsqueda; el Administrador puede consultar pero **NO** ejecutar `modificarCliente`, exclusivo de Vendedor.

**Adaptador técnico propuesto:** `PUT /api/v1/clientes/{idCliente}` con representación **completa** de datos comerciales (no PATCH parcial); nombres, apellidos, teléfono, calle, número, piso, unidad funcional, localidad, provincia, referencia domicilio, zona y día habitual. La omisión de un campo opcional equivale a `null` en el PUT, no actualización parcial. No admite `estado`, `categoriaComercial`, `idUsuario`, `idSesionUsuario`, `roles`, IDs aportados por JSON ni otros atributos de autoridad.

Retorna `200` con `idCliente, estado, categoriaComercial, huboCambio, advertenciaCoincidenciaParcial`. Solo expone información del propio Cliente seleccionado; no retorna IDs/PII de posibles coincidencias. 400 solicitud inválida, 404 ID no existente, 409 duplicado exacto de otro Cliente, 401/403 autenticación/autorización, 503 base de datos no disponible. `Cache-Control:no-store`; errores sin SQL ni teléfono.

## Autorización y garantía de aislamiento

- Flag `ale.http.writes.clientes-uc32-enabled=false` por defecto en `SeguridadHttpInterna`; controla exclusivamente `PUT /api/v1/clientes/*` y exige `VENDEDOR`. Otros comandos `anyRequest().denyAll()`.
- `ModificacionClienteHttpController`: `@Profile("!prod")` + `@ConditionalOnProperty(havingValue="true")`; si no se activa flag, **no existe** controlador.
- `PrecondicionesDespliegue` rechaza arrancar `prod` con flag UC32 activo incluso si se cumple TLS y singleton. Flag UC02 es **separado**.
- El principal proviene exclusivamente de Bearer validado contra `SESION_USUARIO`; `GestionClientesService.modificarCliente` vuelve a exigir permisos en transacción, usa `pg_advisory_xact_lock(3202001)` para serializar datos/unicidad y `SELECT ... FOR UPDATE`.
- El B3 conserva `estado` y `categoriaComercial`; no cambia Pedidos históricos. Si cambia teléfono, bloquea desafíos OTP pendientes del número anterior, **sin revocar sesiones ONLINE activas**; no-op conserva ambos. Fallo en DB revierte todos los cambios.

## Pruebas propuestas, no suponer PASS

- `ModificacionClienteHttpExternoTest` usa Spring Boot en puerto real `RANDOM_PORT` y PostgreSQL Testcontainers con fixtures con COMMIT; flag UC32 **solo por anotación de prueba**.
- Nueve pruebas: Vendedor autorizado y proyección mínima; Admin/Repartidor/sesión pendiente/ausente prohibidos; POST UC02 sigue denegado; datos inválidos/id no existente; no-op; bloqueo de OTP al cambiar teléfono y sesiones ONLINE preservadas; duplicado; coincidencia parcial permitida sin IDs de tercero; fallo de persistencia con trigger y rollback; dos modificaciones convergentes y única identidad final.
- `PrecondicionesDespliegueTest`: arranque `prod` falla si se habilita UC32. Regresión UC02/CSRF/auth y B1/B2/SonarCloud se acreditan por **SHA exacto final**, no por resultados previos.
- Las pruebas de red loopback no representan certificado público/TLS, dispositivos Android ni identidad verificada de Proveedor. [Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) conserva ese gate externo.

## Trazabilidad y condición de cierre

**UC32 → contrato REV06B/DSS → RBAC VENDEDOR → `GestionClientesService.modificarCliente` → `CLIENTE` y `DESAFIO_AUTENTICACION_CLIENTE` MR5 → `PUT /api/v1/clientes/{idCliente}` gated → `ModificacionClienteHttpExternoTest`**.

B4-03-01 UC02 conservado y no activado. UC38 se planificará en otra Issue/PR; **no habilitar** sus mutaciones como efecto secundario. No fusionar PR sin revisión y autorización explícita del responsable. No declarar desplegado el sistema ni aprobar BC06 18 E2E.
