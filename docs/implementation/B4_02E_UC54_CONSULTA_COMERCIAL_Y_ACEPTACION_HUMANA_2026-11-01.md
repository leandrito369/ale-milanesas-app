# B4-02E — Identificación comercial visible de Proveedor y protocolo de aceptación UC-54

**WBS:** B4-02E / UC-56 (Buscar Usuario) + UC-37 (Buscar Proveedor) contextual a UC-54 (Restablecer Acceso de Usuario).  
**Estado:** corte propuesto de API GET, **no equivale a ejecución humana E2E, no acredita host TLS real ni autoriza despliegue comercial**.  
**Fuentes G0:** `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, `governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`. Adendas RD-UC54-001 commit `3cf4b19078f02a374229012cbf8d278b4bb58196` y RD-UC53-002 posteriores.  
**Issue origen:** [#10](https://github.com/leandrito369/ale-milanesas-app/issues/10); [gate de aceptación #28](https://github.com/leandrito369/ale-milanesas-app/issues/28).

## 1. Hallazgo de modelado y decisión técnica

El servicio B3 de UC-56 `BuscarUsuarioService.buscarUsuario` entrega únicamente `idProveedor` y datos administrativos, **sin CUIT ni razón social**. No existe GET HTTP de UC-56/37 que permita al Administrador ver ambos datos antes de UC-54.

En **MR5/F01 real** la tabla `PROVEEDOR` tiene `idProveedor, cuit CHAR(11), razonSocial, telefonoContacto, emailContacto, calle, numeroPuerta, localidad, provincia, estado, fechaHoraAlta`. **NO existe `nombreComercial`**, aunque aparezca en la firma lógica del contrato REV03B de UC37; **se omite** de la proyección y no se inventa columna ni valor. Si el requisito académico exige su persistencia, debe abrirse RP/CR en el repositorio documental y ratificarse antes de cambiar MR5.

La salida de consulta se restringe a las identidades comerciales autorizadas por [BC07 UC37](https://github.com/leandrito369/ale-milanesas/blob/1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e/docs/01-gobierno/b0/BC07_PROYECCIONES_MINIMAS_RATIFICADAS_2026-10-29.csv): idUsuario, nombreUsuario, estadoUsuario, cambioCredencialPendiente, idProveedor, cuit, razonSocial, telefonoContacto, emailContacto y estadoProveedor. **Nunca** `credencialProtegida`, hashes de sesión, tokens, contraseñas temporales ni datos de otras cuentas. En esta consulta no se determina automáticamente `identidadComercialVerificada`.

## 2. Adaptador HTTP y trazabilidad

`GET /api/v1/usuarios/proveedores?idUsuario=<id>` (o `?cuit=<11 dígitos>`, o `?nombreUsuario=<3-80 caracteres>`) → JSON lista hasta 25 resultados. **Exige al menos un criterio**: sin filtros se rechaza con 400 para evitar enumeración masiva. Búsqueda de nombre como fragmento literal; entrada enlazada mediante parámetros JDBC, sin SQL dinámico. Orden determinístico por idUsuario.

La cadena `SeguridadHttpInterna` exige Bearer válido con autoridad `ADMINISTRADOR`, **no** Vendedor/Proveedor/Cliente ONLINE; `ConsultaComercialProveedorService` revalida con `AutorizacionSesionInterna.exigirRoles` en transacción read-only. `Cache-Control: no-store`, 401 Bearer inválido/ausente, 403 sin permiso, 400 filtro inválido, 503 indisponibilidad PostgreSQL. Sin cookies como autenticación API y sin campos adicionales.

El SQL consulta `USUARIO → USUARIO_PROVEEDOR → PROVEEDOR` y exige que el Usuario tenga rol `PROVEEDOR`; **no altera**, habilita ni restablece nada. La exclusividad del Rol PROVEEDOR, estado y vínculo se **revalidan en `RestablecerAccesoUsuarioService` al confirmar UC54**. La fila visible puede corresponder a `PROVEEDOR.INACTIVO`; la lectura no concede capacidad de reset: UC54 seguirá rechazando un objetivo no elegible. Esta GET **no sustituye el protocolo humano de cotejo comercial**.

Traza: UC56 y UC37 → RBAC y BC07 → MR5 `USUARIO`, `USUARIO_ROL`, `USUARIO_PROVEEDOR`, `PROVEEDOR` → `ConsultaComercialProveedorService` → `ConsultaComercialProveedorHttpController` → pruebas HTTP `AutenticacionHttpExternaTest` → UC54 POST existente.

## 3. E2E-UC54: protocolo humano de aceptación (0/4 hasta ejecutar)

**Prerequisitos:** host y certificado CA válidos (o entorno de aceptación aislado igualmente autenticado y con TLS real), PostgreSQL MR5/F01, una cuenta ADMINISTRADOR habilitada, una cuenta PROVEEDOR ficticia/consentida y con vínculo válido, y otra cuenta de rol no PROVEEDOR. Acordar y comprobar **fuera de la API** el canal de entrega confidencial de credencial temporal a quien acreditó identidad. Capturas y evidencias con números de CUIT, teléfonos, usuario, tokens, credenciales y hash **anonimizados**. Ningún resultado es PASS sin persona responsable, fecha/hora, host y SHA de aplicación.

| Caso | Ejecución HUMANA y resultado esperado | Evidencia mínima | Estado |
| --- | --- | --- | --- |
| E2E-UC54-A1 | Login ADMIN UC01, GET comercial UC56/37, cancelación/identidadComercialVerificada=false; POST UC54 debe rechazar (400). Si idUsuario no existe: servicio actual responde **409**, no 404. | Estado hash/flag y sesiones antes-después iguales; status, no-store, operador | **NO EJECUTADO** |
| E2E-UC54-A2 | ADMIN consulta cuenta no PROVEEDOR o vínculo/rol inconsistente; POST UC54 debe rechazar (409 por servicio actual). | No hay UPDATE, Roles/vínculo/estado intactos, sesiones previas | **NO EJECUTADO** |
| E2E-UC54-A3 | ADMIN abandona antes de confirmar: no envía POST, por tanto no hay mutación. Complementar **en entorno aislado de testing, NUNCA producción** con fallo SQL inducido: HTTP 503, rollback total sin revocar sesiones. | Traza de no mutación en cancelación, y CI específico de persistencia fallida | **NO EJECUTADO** |
| E2E-UC54-OK | ADMIN realiza GET y coteja CUIT/razón social con evidencia comercial por procedimiento previamente aprobado; confirma UC54 true; entrega temporal fuera de logs y API a Proveedor verificado; sesión anterior queda revocada; Proveedor UC01 temporal → UC53_RESTRINGIDO (otras rutas 403) → UC53 cambia clave y revoca sesiones → UC01 definitiva operativo. | Status 200/204/401/403, estado y flag antes/después, sesiones revocadas, entrega verificada sin secreto, operador | **NO EJECUTADO** |

**Seguridad de entrega:** la API UC54 recibe `credencialTemporal` en JSON sobre HTTPS y responde **204 sin secreto**. La aplicación no automatiza entrega ni remite el secreto en otro canal; el Administrador debe utilizar un canal humano verificado y autorizado, evitando correo plano, logs, tickets y capturas. **No se declara un canal elegido ni una identidad verificada sin evidencia**.

**Cotejo en BD sin exponer secretos:** verificar por consultas agregadas que `USUARIO.estado` y las filas `USUARIO_ROL` / `USUARIO_PROVEEDOR` no cambiaron; `cambioCredencialPendiente` alterna según UC54/UC53; comparar hash previo/nuevo **localmente sin imprimir ni guardar el hash**; contar sesiones `SESION_USUARIO` activas/revocadas por idUsuario antes/después. No adjuntar `credencialProtegida`, `tokenProtegido` ni cuerpo JSON con credencial.

## 4. Gate de infraestructura real — Issue #28

| Control en host destino | Procedimiento/requisito | Estado |
| --- | --- | --- |
| Certificado/DNS | Desde estación externa: `curl --proto '=https' -Iv https://<dominio>/actuator/health`, inspección CA/cadena/SAN/FQDN y fechas, `openssl s_client -connect <dominio>:443 -servername <dominio> -tls1_2` y `-tls1_3` | **NO EJECUTADO** |
| Nginx TLS, HSTS y HTTP claro | `nginx -t`, `curl -I` por HTTPS con HSTS, `curl -I http://<dominio>/`, verificar que no admite credenciales por HTTP | **NO EJECUTADO** |
| Backend/firewall | En host `ss -tulpn`; proceso Java solo `127.0.0.1:8080`, conexión externa a 8080 rechazada, 443 Nginx accesible | **NO EJECUTADO** |
| Rate limit / cabeceras proxy | En configuración viva `proxy_set_header X-Forwarded-For $remote_addr`; ráfaga controlada desde IP sintética recibe 429 sin confiar en cabecera falsificada; comprobar cuota cuenta/IP en instancia única | **NO EJECUTADO** |
| Singleton PG | Inspeccionar `pg_locks` para advisory lock (classid 4402, objid 2026), segunda instancia falla sin servir, caída de lock provoca fail-closed y requiere reinicio controlado | **NO EJECUTADO** |
| UC54/credenciales/CSRF | Revisor valida GET visible, operador acredita comercialmente, 4 E2E humanos, uso de TLS real, no-store, CSRF web, ausencia de secretos | **NO EJECUTADO** |

**Acta de salida del gate:** fecha y FQDN, versión de Nginx/PostgreSQL y SHA de aplicación, nombre/rol de responsables, evidencias saneadas, resultado de cada caso y firma. Cerrar Issue #10 cuando exista acceso visible validado **y** 4 E2E humanos en evidencia; cerrar Issue #28 solo si el bloque humano y toda la matriz de host tienen verificación real. Integrar código read-only no implica aprobación de seguridad productiva.

## 5. Pruebas automatizadas, separadas de aceptación

Las pruebas HTTP en `AutenticacionHttpExternaTest` con `RANDOM_PORT` + PostgreSQL Testcontainers cubren proyección sin secretos, Bearer y Admin exclusivo, filtros inválidos, ausencia de resultados y ciclo técnico UC54 → UC01 restringido → UC53 → UC01 nuevo con actores sintéticos. La suite original B4-02D ya cubre errores de persistencia/rollback. **Ninguna equivale a evidencia humana ni certificado público**. Sonar/B1/B2 y CI del SHA definitivo se registrarán en PR; no inventar resultados antes de ejecutarlos.
