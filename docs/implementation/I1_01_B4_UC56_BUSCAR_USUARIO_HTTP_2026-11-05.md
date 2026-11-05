# I1-01 / B4 — UC-56 Buscar Usuario HTTP (ADMIN, solo lectura)

**Estado:** implementación en revisión; **compilación local no ejecutada**, CI y revisión humana pendientes, sin merge ni despliegue.
**Issue de seguimiento:** [#54](https://github.com/leandrito369/ale-milanesas-app/issues/54).
**Rama:** `feat/i1-01-uc56-buscar-usuario-http-2026-11-05`. **Base:** `main` @ `7598d854e38b850634658304df7aeec7bb6290db`.
**`source_commit` G0:** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` (inmutable).
**`governance_commit`:** `27738b089afdd2698cdae915f75c0e8089a6248a` (acta G0).
**RD posterior propuesta:** `RD-UC56-001` (criterios, proyección y selección), ratificada por Lean en conversación del 05/11/2026; **integración documental pendiente**.

## 1. Trazabilidad

- **CU:** UC-56 `buscarUsuario`, solo `ADMINISTRADOR (D)`; restantes roles y canales `NR`.
- **Contrato:** `docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/contratos/CONTRATO_UC56_REV02.md` @ G0.
- **DSS/DS:** `DSS_REV02_SEGURIDAD_CORREGIDO.drawio.xml` y `DS_REV02_SEGURIDAD_CORREGIDO.drawio.xml`, en `propuestos/` @ G0.
- **Fase4:** `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`, blob `d3fa32360e0b0b8184b1bb1a3eb9ce52bc0fea11`, ficha Real UC-56.
- **RBAC:** matriz G0 67 operaciones; proyección de siete campos del contrato REV02 ratificada expresamente por el responsable para UC-56, sujeta a integración de RD documental; no se reescribe BC07 de G0.
- **MR5/F01:** `USUARIO`, `USUARIO_ROL`, `USUARIO_PROVEEDOR`, `ROL`, `SESION_USUARIO`, `ASIGNACION_REPARTO`, `PEDIDO`, todas consultadas por B3. **Sin DDL ni Flyway nuevo**.

## 2. Contrato HTTP implementado localmente

`GET /api/v1/usuarios?nombreUsuario=...&estado=...&codigoRol=...`

| Aspecto | Regla |
|---|---|
| Identidad | Bearer opaco válido; la sesión y el Usuario se derivan en servidor, sin aceptar UUID/rol del cliente como identidad. |
| Autorización | `ADMINISTRADOR`, revalidación HTTP + B3 y credencial no pendiente. |
| Criterios | `nombreUsuario`, `estado`, `codigoRol`: **al menos uno**, sin parámetros desconocidos, duplicados ni NUL. |
| Éxito | `200` JSON con `idUsuario`, `nombreUsuario`, `estado`, `cambioCredencialPendiente`, `roles`, `idProveedor`, `pedidosPendientesAsignados`. |
| Sin coincidencias | `200 []`; corresponde mostrar mensaje de A2 en la interfaz. |
| Entrada inválida | `400 SOLICITUD_INVALIDA`, también sin filtros. |
| Falta de identidad | `401 SESION_INVALIDA`. |
| Acceso no autorizado | `403 OPERACION_PROHIBIDA`. |
| Persistencia indisponible | `503 PERSISTENCIA_NO_DISPONIBLE` sin detalles SQL. |
| Caché | `Cache-Control: no-store`; sin secretos, tokens, hashes ni datos de proveedor ajenos al contrato. |

`BuscarUsuarioService.buscarUsuario` (B3) también rechaza la consulta sin criterios, por la ficha Real. `seleccionarUsuario(idUsuario)` continúa como evento auxiliar en UI/flujo invocante, **sin endpoint nuevo**. Permanece intacto `/api/v1/usuarios/proveedores`, contextual UC-56/UC-37 para UC-54. No se agrega mutación ni se habilita producción.

## 3. Matriz de implementación

| Capa | Archivo / responsabilidad |
|---|---|
| B3 | `acceso/BuscarUsuarioService.java`, `CriterioBusquedaUsuario.java`, `UsuarioConsulta.java`. |
| B4 consulta | `web/ConsultaUsuarioHttpController.java`: validaciones, respuestas neutras y filtro. |
| B4 seguridad | `web/SeguridadHttpInterna.java`: matcher GET exclusivo de ADMINISTRADOR. |
| Prueba B3 | `acceso/BuscarUsuarioServiceIntegrationTest.java`: suma el rechazo sin criterios. |
| Prueba B4 | `web/ConsultaUsuarioHttpExternoTest.java`: HTTP real y PostgreSQL Testcontainers 17.11, roles, filtros, campos, revocación, cookies y regresión comercial. |
| Documentación | Este informe y el estado en `README.md`. |

## 4. Verificación y reservas

- `mvn test-compile`: **NO EJECUTADO** localmente (sin Maven ni caché de dependencias).
- Testcontainers: **NO EJECUTADOS** localmente (sin Docker).
- CI B1/B2 de esta rama: **NO EJECUTADO al corte local**; se delega a GitHub Actions tras publicación de la rama.
- Revisión humana del diff y aprobación del PR: **PENDIENTES**.
- RD-UC56-001 en repo de gobierno: **PREPARADA LOCALMENTE; NO INTEGRADA**.
- Entorno B9/Issue #28: **PAUSADO**; UC54 humana 0/4 y BC06 0/18 E2E no acreditados.

**Regla para publicación:** la excepción de compilación previa a push concedida a UC-48 fue puntual. Lean autorizó **otra excepción expresa limitada a UC-56**, delegando compilación y Testcontainers a CI. No mergear sin CI verde y revisión/aprobación humana; no reescribir historia.
