# I1-01 / B4 — UC-18 Gestionar Asignaciones de Roles HTTP

**Fecha:** 05/11/2026. **Estado:** implementación propuesta en PR en borrador; pendiente de CI y revisión humana.
**Issue:** [#58](https://github.com/leandrito369/ale-milanesas-app/issues/58).
**Rama:** `feat/i1-01-b4-uc18-gestionar-roles-http-2026-11-05`. **Base:** `main` @ `7598d854e38b850634658304df7aeec7bb6290db`.
**Entorno:** despliegue PAUSADO por Issue #28. No hay autorización de fusión ni exposición productiva.

## 1. Contrato y autoridad

- **G0 / `source_commit`:** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`.
- **Acta G0 / `governance_commit`:** `27738b089afdd2698cdae915f75c0e8089a6248a`.
- **Adenda funcional posterior:** `RD-UC18-001` e `IC-UC18-MR5-001` integradas en `leandrito369/ale-milanesas` mediante PR #29, commit `3cf4b19078f02a374229012cbf8d278b4bb58196`. Prevalecen los eventos `ASIGNACION/REVOCACION`.
- **Contrato:** `docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/contratos/CONTRATO_UC18_REV02.md`.
- **DSS/DS:** `docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/DSS_REV02_SEGURIDAD_CORREGIDO.drawio.xml` y `DS_REV02_SEGURIDAD_CORREGIDO.drawio.xml`; ficha UC-18 Real/Extendida/Alto en Fase4.
- **RBAC:** UC-18 = `ADMINISTRADOR (D)`; restantes roles/canales `NR`. **BC07:** solo conjunto de Roles vigentes, sin credenciales, tokens, datos personales, proveedor ni auditoría detallada en la respuesta.
- **MR5/F01:** `USUARIO`, `ROL`, `USUARIO_ROL`, `EVENTO_ASIGNACION_ROL`, `SESION_USUARIO` y `USUARIO_PROVEEDOR` existentes. Sin modificación de DDL, Flyway ni `source-lock.md`. La propuesta académica de `PARTIDA_INSUMO`/MR6 sigue otro circuito de gobernanza.

## 2. API y semántica

`PATCH /api/v1/usuarios/{idUsuario}/roles`, JSON:

```json
{"rolesAgregar":["REPARTIDOR"],"rolesQuitar":["VENDEDOR"]}
```

Ambas listas son opcionales; el cuerpo vacío `{}` representa ausencia de cambio. Se rechazan claves ajenas, valores que no sean listas de textos, Rol vacío/nulo, repetidos en una lista, solapamiento de agregados/revocados y códigos inexistentes. `idProveedor` está fuera del contrato: no se fabrican ni eliminan vínculos comerciales. El servicio B3 conserva el criterio de cambio de PROVEEDOR y su exclusividad.

**Salida `200` mínima:** `idUsuario`, `rolesVigentes` y `huboCambio`. Se omiten identificadores de sesión, credenciales, eventos, datos ajenos a los Roles y datos de PROVEEDOR. `Cache-Control: no-store` en todas las respuestas.

| Caso | Postcondición / HTTP |
|---|---|
| Agregar asociación | `USUARIO_ROL` + evento `ASIGNACION` real en la misma transacción. |
| Revocar asociación | Elimina `USUARIO_ROL` + evento `REVOCACION`; dispara revocación de sesiones MR5. |
| A2: último Rol o último Administrador habilitado (F07) | `400 SOLICITUD_INVALIDA`; no se confirma ninguna mutación. |
| A3: Rol ya vigente / ausente al quitar | `200`, `huboCambio=false`, sin evento ni revocación de sesión. |
| A4: falla transaccional de SQL/auditoría | `503 PERSISTENCIA_NO_DISPONIBLE`; reversión completa, sin detalle SQL. |
| A5: no autenticado / no autorizado | `401 SESION_INVALIDA` / `403 OPERACION_PROHIBIDA`. |

El control transaccional de B3 toma las cerraduras de UC17/UC18 en orden (`9172001` y `60505,7`), revalida el Administrador dentro de la transacción y conserva F07. El adaptador usa únicamente el principal obtenido por Bearer HTTP; no recibe ni confía en un UUID de sesión del cuerpo.

## 3. Puertas y matriz de implementación

| Capa | Responsabilidad |
|---|---|
| B3 | `GestionarAsignacionesRolesService` + `AsignacionesRolesActualizadas` ya integrados. Sin cambios funcionales. |
| B4 | `GestionRolesHttpController`: validación de campos, autorización por sesión y proyección mínima. |
| Seguridad | `SeguridadHttpInterna`: `PATCH /api/v1/usuarios/*/roles` solo ADMIN bajo `ale.http.writes.usuarios-uc18-enabled=false` por defecto. |
| Producción | `@Profile("!prod")` en controlador y `PrecondicionesDespliegue` rechaza flag activo. |
| Pruebas | `GestionRolesHttpExternoTest`: PostgreSQL 17.11/Testcontainers, puerto real, delta, eventos, sesiones, no-op, F07, exclusividad, entradas inválidas, RBAC, concurrencia y rollback. |
| Regresión prod | `PrecondicionesDespliegueTest`: rechazo de la bandera y ausencia del controlador con perfil prod. |

La incorporación de `UC-18` es independiente de los PR de `UC-56` y `UC-17` aún en revisión. Una eventual integración posterior debe resolver posibles conflictos de configuración sin reescribir la historia.

## 4. Estado de validación y criterios de cierre

- `mvn test-compile`: **NO EJECUTADO localmente** (Maven/compilador no disponibles). Delegado a GitHub Actions por autorización expresa de Lean para I1.
- Testcontainers PostgreSQL 17.11: **NO EJECUTADOS localmente** (Docker no disponible). Delegados a GitHub Actions.
- Los cambios se publican antes de compilación local bajo la excepción general aprobada; se corrigen fallos de CI con commits adicionales, sin rebase, amend ni force-push.
- CI B1/B2/B4 del SHA publicado: **pendiente de ejecución al redactar este documento**. El estado efectivo se informará en el PR.
- Revisión humana de Lean y aprobación explícita: **PENDIENTES**. El PR permanece en **draft**, sin fusión ni despliegue.
- No se acreditan E2E humanos ni certificación del entorno, ni se adelanta MR6/`PARTIDA_INSUMO`.
