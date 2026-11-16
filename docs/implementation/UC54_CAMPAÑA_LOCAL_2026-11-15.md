# Acta de cierre técnico — Campaña local UC-54 con datos sintéticos (15/11/2026)

**Proyecto:** Sistema de Gestión para Ale Milanesas
**Repositorio de aplicación:** `leandrito369/ale-milanesas-app`
**Metodología:** UP — fase de Construcción; verificación funcional local y preparación de aceptación B4-02E/B8.
**Estado del corte:** **CERRADO TÉCNICAMENTE EN LABORATORIO LOCAL**, según resultados informados por el operador. **NO ES ACEPTACIÓN HUMANA E2E NI CERTIFICACIÓN DE DESPLIEGUE.**
**Dictamen:** **APROBADO LOCAL CON ALCANCE DELIMITADO**; publicación documental susceptible de revisión, sin cierre de los issues.
**Issues relacionados:** [#10 — UC54](https://github.com/leandrito369/ale-milanesas-app/issues/10) y [#28 — autenticación/TLS/aceptación](https://github.com/leandrito369/ale-milanesas-app/issues/28), ambos **ABIERTOS**.
**SHA de aplicación probado:** `4dbe38668fc5cbc08d04e10920e3bf165c71be11` (`main`).
**G0 `source_commit`:** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` (inmutable, repo `ale-milanesas`).
**G0 `governance_commit`:** `27738b089afdd2698cdae915f75c0e8089a6248a` (acta y manifiesto canónicos, repo `ale-milanesas`).
**Fecha:** 15/11/2026. Sesión principal ≈04:00–04:30 UTC y complementos ≈19:40–20:30 UTC, horarios aproximados declarados por el operador.
**Despliegue:** **PAUSADO** por decisión vigente del responsable.
**Responsable de ejecución y revisor:** identificación/firma pendientes de asentarse; este documento no suplanta aprobación humana.

## 1. Resumen ejecutivo y alcance

Se ejecutó una campaña **preparatoria, local y aislada** de UC-54 (restablecer acceso de usuario PROVEEDOR), UC-01 (iniciar sesión), UC-53 (cambiar contraseña) y la consulta comercial administrativa UC-56/UC-37. Se utilizaron cuentas y datos sintéticos, un backend local y PostgreSQL 17.11.

**Resultados informados:** A1, A2, A3 y OK **4/4 aprobados funcionalmente en localhost**. Se añadieron A2-D (proveedor INACTIVO), A3-T (fallo de persistencia y rollback en Testcontainers) y REV (verificación focalizada de la revocación del token vigente tras UC54). La campaña incluyó una ejecución `mvn -B -ntp clean verify` con **619 pruebas, 0 fallos, 0 errores y 0 omitidas**, según 74 informes XML locales declarados.

Los resultados HTTP y SQL proceden de la ejecución y del registro entregados por el operador. **Este documento no incorpora ni verifica independientemente los archivos crudos de la sesión local**: su conservación y eventual cotejo siguen bajo responsabilidad del operador/revisor. La conformidad del acta consiste en documentar exactamente esos resultados y sus límites, no en atribuir ejecuciones al revisor.

**Límites expresos:** no se acreditaron cuatro E2E humanos formales en entorno HTTPS autorizado, identidad comercial externa, entrega confidencial de una clave temporal a un destinatario humano verificado, ni certificación de TLS/DNS/Nginx/firewall/singleton/CSRF del Issue #28. Tampoco se probó una interfaz B5 completa. No se autorizan escrituras comerciales ni despliegue.

## 2. Fuentes, línea base y método

### 2.1. Fuentes de aceptación

- [Issue #10 — UC54](https://github.com/leandrito369/ale-milanesas-app/issues/10): A1, A2, A3 y OK con verificación/aceptación humana pendientes.
- [Issue #28 — B4-02](https://github.com/leandrito369/ale-milanesas-app/issues/28): gate de aceptación TLS, infraestructura y UC54.
- [`B4_02E_UC54_CONSULTA_COMERCIAL_Y_ACEPTACION_HUMANA_2026-11-01.md`](B4_02E_UC54_CONSULTA_COMERCIAL_Y_ACEPTACION_HUMANA_2026-11-01.md): protocolo y criterios.
- [`B4_02E_ACEPTACION_HUMANA_UC54_2026-11-01.md`](B4_02E_ACEPTACION_HUMANA_UC54_2026-11-01.md): **acta humana separada, todavía sin acreditación**.
- [`B9_01_ENTORNO_ACEPTACION_UC54_2026-11-01.md`](B9_01_ENTORNO_ACEPTACION_UC54_2026-11-01.md) y [`B9_01_ACTA_INFRAESTRUCTURA_2026-11-01.md`](B9_01_ACTA_INFRAESTRUCTURA_2026-11-01.md): requisitos operativos y certificación externa pendientes.
- [`source-lock.md`](../traceability/source-lock.md): línea base G0 y adendas de precedencia para UC53/UC54; RD-UC54-001 y RD-UC53-002 aplicables sin reescribir G0.
- Código del SHA identificado: `AuthInternaController`, `SeguridadHttpInterna`, `RestablecerAccesoUsuarioService`, `SesionesUsuarioService`, `AutenticacionHttpExternaTest` y migraciones Flyway V1–V3.

### 2.2. Procedimiento de control

1. Preparar fixtures sintéticas y verificar base/esquema/Flyway.
2. Obtener respuestas HTTP en localhost mediante `curl`, bajo tokens Bearer de ensayo.
3. Tomar snapshots antes y después de cada intento, distinguiendo estado, indicador de cambio pendiente, conjuntos de roles y vínculos, comparación local de credencial protegida y conteos de sesiones.
4. Comparar los resultados reales con los esperados por el contrato; preservar intentos inicialmente fallidos y sus causas.
5. Repetir de forma focalizada la revocación cuestionada, sin reemplazar retrospectivamente los resultados originales.
6. Ejecutar el test de rollback en PostgreSQL descartable y la suite automatizada; registrar su evidencia sin equipararlos a E2E humanos.

**Criterio de aprobación local:** resultado HTTP esperado más ausencia de mutación indebida o transición SQL coherente, según escenario. La sola coincidencia de un código HTTP no constituye evidencia suficiente.

## 3. Entorno de ejecución y datos

### 3.1. Plataforma declarada

| Componente | Versión / configuración |
|---|---|
| Sistema anfitrión | Windows 11 |
| Subsistema Linux | WSL2 — Ubuntu 26.04 |
| Contenedores | Docker Desktop 29.4.3 |
| Java | Temurin JDK 21.0.13 |
| Maven | 3.9.9 |
| Aplicación | Spring Boot 4.1.2; backend local puerto `8082` |
| PostgreSQL | 17.11-alpine en Docker Compose; puerto publicado en loopback `127.0.0.1:5433` |
| Base de laboratorio | `ale_milanesas_uc54`, separada de `ale_milanesas_dev` y `ale_milanesas_l05` |
| Flyway | V1 (MR5), V2 (F01), V3 (partidas de insumo); **sin V4** |
| Escrituras comerciales | Flags desactivados durante la campaña, según registro del operador |

### 3.2. Fixtures sintéticas

| Identificador | Función de prueba | Estado relevante |
|---|---|---|
| Usuario `91005001` — `lab_admin_uc54` | Administrador ejecutor | `ADMINISTRADOR`, `HABILITADO` |
| Usuario `91005002` — `lab_proveedor_uc54` | Proveedor elegible, flujo principal | Solo `PROVEEDOR`, `HABILITADO`, vinculado a proveedor `91005010` ACTIVO |
| Usuario `91005003` — `lab_noelegible_uc54` | A2-A | Solo `VENDEDOR`, sin vínculo PROVEEDOR |
| Usuario `91005004` — `lab_proveedor_inactivo_uc54` | A2-D | Solo `PROVEEDOR`, `HABILITADO`, vinculado a proveedor `91005011` INACTIVO |
| Usuario `91005005` — `lab_revocacion_uc54` | Prueba focalizada REV | Solo `PROVEEDOR`, `HABILITADO`, vinculado a proveedor `91005012` ACTIVO |
| Usuario `999999999` | A1 inexistente | No insertado |

Los CUIT y razones sociales utilizados fueron declarados **sintéticos** y se contrastaron dentro de las fixtures. No es necesario publicarlos para demostrar la secuencia técnica. Los secretos de prueba y los archivos de preparación (`credentials.env`, `seed.sql`) permanecieron, según el operador, fuera del repositorio en `~/uc54/` con permisos `0600`. No se incorporan contraseñas, tokens, UUID de sesiones ni valores de hashes al acta.

## 4. Matriz consolidada de resultados locales

| ID | Objetivo | Evidencia reportada | Dictamen del corte local |
|---|---|---|---|
| A1 | Negativa sin identidad confirmada y usuario inexistente | HTTP `400`/`409`, snapshots sin cambios | **APROBADO LOCAL** |
| A2-A | Rol VENDEDOR no elegible | HTTP `409`, sin mutación | **APROBADO LOCAL** |
| A2-D | Proveedor INACTIVO | GET `200`, UC54 `409`, sin mutación | **APROBADO LOCAL** (ampliación) |
| A2-B / A2-C | Rol mixto o proveedor sin vínculo | No ejecutados por HTTP; invariantes diferidas MR5 inspeccionadas | **NO EJECUTADOS**; fuera del conteo |
| A3-H | Consulta y cancelación antes del POST | GET `200`, no POST, estado estable | **APROBADO LOCAL** |
| A3-T | Error real de persistencia y rollback | Test focalizado: HTTP `503`, 1/1 aprobado | **APROBADO TÉCNICO AUTOMATIZADO** |
| OK | Reset → temporal → UC53 → nuevo login | Códigos esperados y cambios SQL consistentes | **APROBADO LOCAL**, con corrección causal REV |
| REV | Token vigente revocado como efecto de UC54 | T3 `403→401`, SQL activas `1→0`, revocadas `0→1` | **APROBADO LOCAL**, prueba focalizada |
| Suite | Regresión general | 619/0/0/0; 74 XML, según reportes declarados | **SUCCESS informado**, sujeto a cotejo de XML |

**Resumen:** **4/4 escenarios funcionales principales** aprobados en el laboratorio local. No equivale a **4/4 E2E humanos acreditados** para #10/#28.

## 5. Ejecución por escenario

### 5.1. A1 — Identidad no confirmada

**Objetivo:** UC54 rechaza solicitudes no confirmadas y objetivos inexistentes sin alterar datos.

| Paso | Operación o control | Esperado | Observado |
|---|---|---|---|
| A1.1 | Login de Administrador | `200` | `200` |
| A1.2 | `GET /api/v1/usuarios/proveedores?idUsuario=91005002` | `200` y proyección autorizada | `200`, sin secretos reportados |
| A1.3 | Snapshot previo `91005002` | `HABILITADO`, pendiente `false`, rol `PROVEEDOR`, vínculo `91005010`, sesiones activas `0` | Coincide |
| A1.4 | `POST /api/v1/usuarios/91005002/restablecer-acceso` con confirmación `false` | `400 SOLICITUD_INVALIDA` | Coincide |
| A1.5 | Snapshot posterior | Igual al previo | Comparación sin diferencias |
| A1.6 | Mismo POST con ID `999999999`, confirmación `true` | `409 CONFLICTO_ESTADO` | Coincide |
| A1.7–A1.8 | Snapshot y existencia de usuario inexistente | Cuenta objetivo preservada; `COUNT=0` para ID inexistente | Coincide |

**Dictamen A1:** **APROBADO LOCAL**. Las credenciales protegidas y las sesiones no cambiaron en las negativas, según los snapshots informados.

### 5.2. A2 — Rol o vínculo inválido

La migración V1 contiene una invariante diferida que impide confirmar usuarios PROVEEDOR con otros roles o sin vínculo a PROVEEDOR. **La inspección del DDL no equivale a haber ejecutado esas variantes por HTTP.** No se desactivaron restricciones para fabricar estados imposibles.

| Variante | Procedimiento | Resultado | Dictamen |
|---|---|---|---|
| **A2-A** | Usuario `91005003`, VENDEDOR sin rol PROVEEDOR; UC54 `true` | `409 CONFLICTO_ESTADO`; rol, vínculo, estado y sesiones iguales | **APROBADO LOCAL** |
| A2-B | PROVEEDOR + VENDEDOR | No realizado; combinación prohibida por invariante MR5 | **NO EJECUTADO** |
| A2-C | PROVEEDOR sin vínculo | No realizado; combinación prohibida por invariante MR5 | **NO EJECUTADO** |
| **A2-D** | Usuario `91005004`, PROVEEDOR vinculado a ficha `91005011` INACTIVA; GET comercial y POST UC54 | GET `200` con `estadoProveedor=INACTIVO`; POST `409`; snapshots iguales | **APROBADO LOCAL** |

**Dictamen A2:** **APROBADO LOCAL** por A2-A; A2-D agrega cobertura. No se adjudica éxito HTTP a A2-B/C.

### 5.3. A3 — Cancelación humana simulada y rollback técnico

**A3-H — Decisión de cancelar:** el operador consultó por GET la identidad comercial del usuario `91005002`, obtuvo `200` y decidió no enviar el POST de UC54. El snapshot previo y posterior fue idéntico (`HABILITADO`, pendiente `false`, rol y vínculo estables, cero sesiones activas). Se acredita **ausencia de escritura local**, no una aceptación humana externa firmada.

**A3-T — Persistencia fallida:** se ejecutó el test existente:

`ar.edu.unam.alemilanesas.web.AutenticacionHttpExternaTest#uc54FalloDePersistenciaHaceRollbackSinRevocarSesiones`

- **SHA:** `4dbe38668fc5cbc08d04e10920e3bf165c71be11`.
- **Motor:** PostgreSQL 17.11 efímero de Testcontainers, separado de la base local.
- **Ejecución puntual declarada:** `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, aproximadamente 42,719 s.
- **Método del test:** inyecta un error en `UPDATE "USUARIO"`, comprueba HTTP `503`, conserva la credencial previa y comprueba que la sesión previa no quedó revocada; retira los objetos de prueba al concluir.
- **Reporte declarado:** `TEST-ar.edu.unam.alemilanesas.web.AutenticacionHttpExternaTest.xml`, conservado fuera del repositorio por el operador.

**Dictamen A3:** **APROBADO LOCAL** para cancelación; **APROBADO TÉCNICO AUTOMATIZADO** para rollback. Son controles diferentes y no se suman como dos E2E humanos.

### 5.4. OK — Flujo principal de restablecimiento

**Fixture:** usuario PROVEEDOR `91005002`; prueba técnica desde localhost. Los campos `requiereCambioContrasena` y `cambioCredencialPendiente` designan el indicador del contrato y la base respectivamente.

| Paso | Acción | HTTP/SQL esperado | Resultado reportado |
|---|---|---|---|
| OK.1.1 | Snapshot inicial | Pendiente `false`, rol y vínculo correctos, sesiones activas `0` | Coincide |
| OK.1.2 | Login con credencial original | `200`, cambio no requerido | Coincide |
| OK.1.3 | Renovación del token | `200`, token nuevo | Coincide |
| OK.1.4 | Snapshot posterior a login | Sesiones activas `1` | Coincide |
| OK.2.1–2.2 | Login ADMIN y GET comercial | `200` / `200` | Coincide |
| OK.2.3 | UC54 con confirmación `true` | `204` | Coincide |
| OK.2.4 | Snapshot posterior a UC54 | Pendiente `true`, credencial protegida cambiada, activas `0`, revocadas `1`; rol/vínculo/estado iguales | Coincide |
| OK.3.1 | Intento con **token original previo al renew** | `401` | `401` **no concluyente para atribuir revocación a UC54**; ver §6 |
| OK.3.2 | Login con credencial temporal | `200`, cambio requerido | Coincide |
| OK.3.3 | GET `/api/v1/productos` con token restringido | `403` | Coincide; no aísla por sí solo el motivo de RBAC |
| OK.3.4 | Renovar sesión restringida | `403` | Coincide |
| OK.4.1 | UC53, cambio de contraseña | `204` | Coincide |
| OK.4.2 | Snapshot posterior a UC53 | Pendiente `false`, credencial protegida cambiada, activas `0`, sesiones revocadas aumentan | Coincide |
| OK.4.3 | Reutilización del token de UC53 | `401` | Coincide |
| OK.4.4 | Login con nueva contraseña | `200`, cambio no requerido | Coincide |
| OK.4.5 | Renovación con sesión operativa | `200`, nuevo Bearer | Coincide |
| OK.4.6 | Primer logout con Bearer anterior a la renovación | `401` | Incidente de uso; token ya invalidado |
| OK.4.6-fix | Logout con Bearer vigente | `204` | Coincide; sesión revocada |
| OK.4.7 | Snapshot final | Pendiente `false`, activas `0`, estado/roles/vínculos inalterados | Coincide |

**Dictamen OK:** **APROBADO LOCAL**, sustentado en el flujo principal y en la subsanación experimental independiente REV. No se realizó entrega humana certificada de la contraseña temporal.

## 6. REV — Subsanación causal de la revocación UC54

### 6.1. Observación original y criterio de subsanación

En OK.1.3, la operación `/auth/renew` invalida el token anterior. Por lo tanto, el `401` registrado en OK.3.1 contra el **token original** no demostraba que UC54 lo hubiera revocado: la invalidez ya existía antes del restablecimiento. Se conserva esta observación para no reescribir la historia de la campaña.

La prueba REV usó una **fixture independiente** (`91005005`), conservó el token vigente **T3** entre la última comprobación previa y la primera posterior a UC54, y comparó tanto su respuesta HTTP como el estado SQL de la sesión.

### 6.2. Secuencia focalizada informada

| Paso | Acción | Esperado | Observado |
|---|---|---|---|
| REV.1 | Snapshot inicial | Pendiente `false`, sesiones activas `0` | Coincide |
| REV.2 | Login del proveedor → T1 | `200`, `requiereCambioContrasena=false` | Coincide; token de 43 caracteres |
| REV.3 | Renovar T1 → T2 | `200` | Coincide |
| REV.4 | Intentar renovar T1 invalidado | `401` | `401 SESION_INVALIDA` |
| REV.5 | GET `/api/v1/productos` con T2 | `403` (Bearer reconocido; PROVEEDOR sin acceso a productos) | `403 OPERACION_PROHIBIDA` |
| REV.6 | Renovar T2 → T3 | `200` | Coincide |
| REV.7 | GET `/api/v1/productos` con **T3 antes de UC54** | `403` por autorización, no autenticación | `403 OPERACION_PROHIBIDA` |
| REV.8 | Login del Administrador | `200` | Coincide |
| REV.9 | Snapshot inmediatamente previo | Pendiente `false`, activas `1`, revocadas `0` | Coincide |
| REV.10 | UC54 con confirmación verdadera, objetivo `91005005` | `204` | `204` |
| REV.11 | Snapshot posterior | Pendiente `true`, activas `0`, revocadas `1`; credencial modificada; rol/vínculo/estado sin cambio | Coincide |
| REV.12a | GET `/api/v1/productos` con **el mismo T3 posterior a UC54** | `401` | `401 SESION_INVALIDA` |
| REV.12b | `/api/v1/auth/renew` con **T3** posterior a UC54 | `401` | `401 SESION_INVALIDA` |
| REV.14 | Consulta SQL de sesiones | Activas `0`, revocadas `1`, total `1` | Coincide |

Se conservan los identificadores de pasos proporcionados por el operador; **no se inventa un REV.13**.

### 6.3. Resultado y atribución

Entre REV.7 y REV.12a **no ocurrió renovación adicional del token T3**. El mismo T3 pasó de `403` (autenticación reconocida, operación denegada por RBAC) a `401` (sesión inválida) después de UC54. En paralelo, el operador informó la transición SQL de sesiones activas `1→0` y revocadas `0→1`, sin cambiar el número total de sesiones (`1`). El indicador de cambio pendiente pasó de `false` a `true`; la credencial protegida cambió, mientras que el rol `PROVEEDOR`, el vínculo `91005012` y el estado `HABILITADO` permanecieron iguales.

**Conclusión técnica local:** la revocación de la sesión vigente quedó acreditada por evidencias concordantes de HTTP y PostgreSQL y es consistente con el trigger MR5 `tg_mr5_revocar_sesiones_usuario`. Es más preciso hablar de **atribución causal sustentada por la secuencia y el código** que de prueba formal de identidad humana o certificación de infraestructura.

**Resultado REV:** **APROBADO LOCAL**, según resultados reportados. La observación crítica anterior queda **SUBSANADA** en la campaña local sin atribuir valor probatorio al 401 original de OK.3.1.

### 6.4. Incidencia de preparación de fixture

En el primer armado de `91005005` se reutilizó una credencial protegida de otra fixture que ya había pasado por UC53. El primer login devolvió `401 AUTENTICACION_RECHAZADA` por discrepancia entre la credencial de ensayo y el hash realmente preparado. **No fue un fallo de UC54 ni una prueba de revocación.**

El operador corrigió la fixture en la base **exclusiva del laboratorio**, con una credencial sintética nueva y `cambioCredencialPendiente=false`, antes de ejecutar la secuencia REV válida. La preparación manual de datos requirió configurar correctamente el esquema de resolución de los triggers MR5; este detalle es **operativo del laboratorio**, no una migración ni un cambio aprobado en producción. La primera ejecución fallida permanece documentada y no se computa como PASS.

## 7. Hallazgos de autenticación y autorización

### 7.1. Rotación de Bearer

`POST /api/v1/auth/renew` emite un token Bearer nuevo y **revoca la validez del token anterior por sustitución de su huella**, aun cuando la sesión lógica siga vigente. **No significa que cada solicitud consuma el Bearer.** Por ello, una operación posterior (incluido logout) debe utilizar el Bearer nuevo. El primer intento de logout con el token previo fue `401`; con el vigente fue `204`. El comportamiento corresponde al código verificado.

### 7.2. Restricción `UC53_RESTRINGIDO` y rol `PROVEEDOR`

La API `/api/v1/**` asigna autoridad `UC53_RESTRINGIDO` mientras está pendiente el cambio de credencial. En ese estado la sesión puede efectuar UC53, pero `/auth/renew` y `/auth/logout` están prohibidos. Finalizado UC53, debe realizarse un **nuevo login**, porque el cambio revoca también el token utilizado para cambiar la contraseña.

Un `PROVEEDOR` operativo tampoco tiene permiso para `GET /api/v1/productos` según el RBAC actual. En consecuencia, un `403` en dicha ruta **no distingue por sí solo** la sesión restringida de una sesión de proveedor plenamente autenticada. La comparación específica es `/auth/renew`: `403` durante el cambio pendiente y `200` tras un nuevo login operativo. Esta conclusión no se extiende automáticamente a la interfaz web ni a rutas futuras.

### 7.3. Separación entre verificación técnica y comercial

`identidadComercialVerificada=true` es una declaración explícita del Administrador en UC54; **no representa una verificación autónoma del backend**. La consulta administrativa de CUIT/razón social de fixtures no demuestra que un proveedor real haya sido identificado ni que una credencial temporal se haya entregado por canal confidencial. Ambos requisitos quedan pendientes de aceptación formal.

## 8. Evidencia automatizada y trazabilidad

### 8.1. Suite general informada

| Control | Valor declarado |
|---|---|
| SHA | `4dbe38668fc5cbc08d04e10920e3bf165c71be11` |
| Comando | `mvn -B -ntp clean verify` |
| Duración | Aproximadamente `9:28 min` |
| Informes XML | `74` |
| Tests | `619` |
| Fallos / errores / omitidos | `0 / 0 / 0` |
| BUILD | `SUCCESS` |

Los informes originales de Surefire/Failsafe se declararon conservados en `~/uc54/evidencias/surefire-verify-completo/`, **fuera de Git**. La suite incluye pruebas unitarias y pruebas de integración; solo aquellas que requieren PostgreSQL utilizan Testcontainers. **La cifra agregada es la informada por el operador y debe cotejarse con los XML originales antes de tratarla como auditada independientemente.**

El operador declaró 11 pruebas en `AutenticacionHttpExternaTest`, de las cuales 6 corresponden a UC54, todas aprobadas. El test de fallo de persistencia A3-T también se ejecutó de forma focalizada.

### 8.2. Inventario mínimo de evidencias

| Ref. | Alcance | Estado/documentación |
|---|---|---|
| EV-UC54-01 | A1 y A2-A: respuestas 400/409 y snapshots sin cambios | Resultados declarados; originales bajo custodia del operador |
| EV-UC54-02 | A2-D: proveedor INACTIVO, 200 de consulta/409 de UC54 y snapshots | Resultados declarados; originales bajo custodia del operador |
| EV-UC54-03 | A3-H: GET, no POST y snapshots | Resultados declarados; originales bajo custodia del operador |
| EV-UC54-04 | A3-T: test focalizado, 503 y rollback | Informe XML Surefire declarado, fuera del repositorio |
| EV-UC54-05 | OK: secuencia de estados/tokens y snapshots | Resultados declarados; originales bajo custodia del operador |
| EV-UC54-06 | REV: T3 `403→401`, SQL `1→0` y `0→1` | Resultados declarados; originales bajo custodia del operador |
| EV-UC54-07 | Suite 619/0/0/0, 74 XML | Reportes declarados, fuera del repositorio |

**Nota metodológica:** `EV-UC54-*` son identificadores de esta matriz, **no** rutas, hashes, archivos publicados ni pruebas revisadas externamente. Si se anexan copias saneadas, registrar después sus rutas reales y checksums verificados; no inventarlos en esta acta.

### 8.3. Política de resguardo

No publicar contraseñas, claves temporales, nombres de variables que contengan sus valores, cabeceras Authorization, tokens Bearer, cookies, UUID de sesión, `credencialProtegida`, `tokenProtegido`, hashes completos ni derivados de hashes, claves privadas TLS o datos comerciales reales. Solo conservar estados, transiciones, conteos y referencias saneadas. El cotejo del cambio de credencial se registra como **booleano**, sin publicar ningún digest parcial.

## 9. Gobierno y restricciones preservadas

- **G0 y contratos:** la campaña no modificó fuentes normativas, contratos ni `source-lock.md`.
- **Migraciones:** V1–V3 utilizadas; no se ejecutó migración V4 ni SQL candidato de CR-F.
- **CR-C y CR-F:** PR #47 y #48 permanecen **OPEN/DRAFT/no fusionadas** según el estado consultado en GitHub durante la revisión del acta; la campaña no las modificó.
- **UC-07:** no fue implementada ni ejercitada en esta campaña. F y G siguen sin ratificar; F8 y M01 pendientes, H / `RT-UC07-04` ABIERTA.
- **D/E:** ratificadas previamente por el responsable, sin actualización transversal por esta campaña; límites experimentales de E no se fijan mediante este acta.
- **Aplicación:** la campaña no autoriza modificaciones funcionales ni flags comerciales.
- **Issue #10:** la cobertura funcional local es **4/4**, pero la acreditación E2E humana formal sigue **0/4** hasta ejecutar y firmar el protocolo B4-02E.
- **Issue #28:** infraestructura real **NO CERTIFICADA**; el despliegue **continúa pausado**. Este documento no es solicitud de provisión ni autorización para levantar la pausa.
- **BC06:** 18 escenarios E2E financieros originales independientes; esta campaña no los ejecutó ni les atribuye PASS.

## 10. Anexo de reproducibilidad sin secretos

### 10.1. Comprobación de Flyway en el laboratorio

```bash
# Ejecutar solo contra la base sintética de laboratorio.
docker compose exec -T postgres psql \
  -U "$ALE_DB_USER" -d ale_milanesas_uc54 \
  -c 'SELECT version, description, success
      FROM ale_gestion.flyway_schema_history
      ORDER BY installed_rank;'
```

**Resultado declarado:** V1, V2 y V3 con `success=true`; sin V4. La comprobación no equivale a certificar migraciones en un host de aceptación público.

### 10.2. Comparación de estados

Los snapshots privados del operador cubren `USUARIO.estado`, `cambioCredencialPendiente`, la **composición** de los roles, los **IDs vinculados de proveedor**, sesiones activas/revocadas y una comparación privada del valor de credencial protegida. No se publican hashes ni sus derivados; el registro consolidado utiliza `credencial_cambiada=true/false` según corresponda.

Para futuras repeticiones, comparar estados inmediatamente antes y después, sin que un login adicional del operador se contabilice erróneamente como mutación del escenario.

### 10.3. Rutas HTTP verificadas

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/renew`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/password/change`
- `GET /api/v1/usuarios/proveedores?idUsuario=<id>`
- `POST /api/v1/usuarios/<id>/restablecer-acceso`
- `GET /api/v1/productos` (control RBAC, no como evidencia exclusiva de restricción)

Los cuerpos y tokens utilizados en la prueba no forman parte del documento. Las pruebas se realizaron por HTTP local; **no** se acredita TLS a partir de este tráfico.

## 11. Checklist de cierre del corte local

- [x] A1 — confirmación negativa (`400`) y usuario inexistente (`409`) sin mutación reportada.
- [x] A2-A — VENDEDOR no elegible (`409`) y estado estable.
- [x] A2-D — proveedor INACTIVO (`409`) y estado estable.
- [ ] A2-B/A2-C — **NO EJECUTADOS** por HTTP; estados prohibidos por invariante MR5 según DDL.
- [x] A3-H — cancelación local antes del POST, sin mutación.
- [x] A3-T — rollback de persistencia, test puntual reportado PASS.
- [x] OK — ciclo UC54 → UC01 restringido → UC53 → UC01 operativo, localmente aprobado.
- [x] REV — revocación del **token vigente T3** antes/después de UC54; observación causal subsanada.
- [x] Tokens rotados correctamente; primer logout fallido conservado y reintento con token vigente satisfactorio.
- [x] Estados, roles y vínculos comparados según reporte del operador.
- [x] Suite general informada `619/0/0/0`, 74 XML; originales pendientes de cotejo independiente.
- [x] Gobierno, migraciones y flags preservados según registro de campaña.
- [ ] Validación humana formal B4-02E; **no es parte del cierre local**.
- [ ] Certificación externa de infraestructura B9-01; **no es parte del cierre local**.
- [ ] Firmas de aceptación humana y de seguridad; **no inferidas**.

## 12. Pendientes posteriores — aceptación formal y certificación

1. **Designar** formalmente Administrador verificador, actor Proveedor, operador de infraestructura y revisor/testigo; registrar su intervención y disponibilidad.
2. Acordar el procedimiento de cotejo comercial independiente y el canal de entrega confidencial de una credencial temporal; demostrar recepción sin capturar el secreto.
3. **Autorizar expresamente** el entorno aislado de aceptación B9-01. La pausa de despliegue no se levanta mediante esta acta.
4. Certificar DNS, FQDN, certificado CA, TLS 1.2/1.3, Nginx/HSTS/HTTP claro, firewall IPv4/IPv6, proxy y rate limit, singleton PostgreSQL y controles CSRF/cookies/API en el host autorizado.
5. Repetir A1, A2, A3 y OK en ese entorno con personas responsables y evidencias saneadas; firmar el acta humana B4-02E. Conservar por separado el test de rollback A3-T.
6. Completar y firmar B9-01, verificar recuperación y limpieza de fixtures/sesiones y emitir dictamen operativo sobre el eventual cierre de #10 y #28.

Ninguno de estos pendientes se declara completo por la campaña local. Cerrar #28 tampoco activa automáticamente endpoints comerciales.

## 13. Dictamen, revisión y firmas

**Dictamen técnico local:** **APROBADO LOCAL / CIERRE TÉCNICO DE CAMPAÑA PREPARATORIA** sobre los resultados declarados para el SHA `4dbe38668fc5cbc08d04e10920e3bf165c71be11`.

**Observación crítica subsanada:** el control REV sobre el token T3 vigente separa la invalidación por rotación de la revocación tras UC54. El 401 del token original en OK.3.1 conserva carácter **no probatorio** para atribución causal.

**Estado de aceptación humana:** **NO ACREDITADA** (sin acta B4-02E firmada ni entrega humana verificada).
**Estado de certificación de infraestructura:** **NO CERTIFICADA** (Issue #28 abierto; despliegue pausado).
**Estado de publicación:** documento preparado para revisión documental; no acredita commit, push, PR ni merge.
**Autoría de la ejecución:** operador de laboratorio (identificación a incorporar en firma).
**Revisión independiente de artefactos crudos:** **PENDIENTE**, si se requiere para la firma documental.

| Firma/control | Responsable | Fecha | Estado |
|---|---|---|---|
| Ejecución técnica local | Por consignar | Por consignar | **PENDIENTE DE FIRMA** |
| Revisión del acta y evidencias originales | Por designar | Por consignar | **PENDIENTE** |
| Aceptación humana B4-02E | Por designar | — | **NO ACREDITADA** |
| Certificación B9-01 | Por designar | — | **NO CERTIFICADA** |

**Prohibiciones vigentes:** no cerrar los issues #10/#28, no modificar G0/migraciones/CR-C/CR-F, no habilitar escrituras comerciales, no reanudar despliegue y no atribuir a la campaña local una aceptación que no ocurrió.
