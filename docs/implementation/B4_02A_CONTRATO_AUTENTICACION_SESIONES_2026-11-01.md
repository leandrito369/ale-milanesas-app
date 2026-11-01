# B4-02A — Contrato ratificado de autenticación y sesiones HTTP internas

**Fecha de decisión:** 01/11/2026.  
**Estado de decisión:** **RATIFICADO POR EL RESPONSABLE** (adopción explícita de la propuesta B4-02A).  
**Estado de incorporación:** PR documental pendiente de revisión/merge; **NO IMPLEMENTADO NI PROBADO**.  
**WBS:** B4-02A / I1 — contrato técnico transversal; **Issue [app#28](https://github.com/leandrito369/ale-milanesas-app/issues/28)**.  
**Dependencia:** B4-01 [PR #27](https://github.com/leandrito369/ale-milanesas-app/pull/27) es puente provisional de *validación*, no emite credenciales.
**Repositorio:** implementación; este documento NO modifica el contrato funcional de Fase4 ni el esquema físico.

## 1. Autoridad, alcance y separación de UC

- **G0 inmutable:** `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, `governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`; [source-lock](../traceability/source-lock.md). 64 CU/67 operaciones, RBAC por operación y proyecciones BC07 siguen vigentes.
- **Adenda I1:** contratos [UC-01](https://github.com/leandrito369/ale-milanesas/blob/main/docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/contratos/CONTRATO_UC01_REV02.md), [UC-53](https://github.com/leandrito369/ale-milanesas/blob/main/docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/contratos/CONTRATO_UC53_REV02.md), [UC-54](https://github.com/leandrito369/ale-milanesas/blob/main/docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/contratos/CONTRATO_UC54_REV02.md), DSS y DS REV02, y [RD-UC53-001](https://github.com/leandrito369/ale-milanesas/blob/main/docs/01-gobierno/i1/RD_UC53_001_CREDENCIAL_ACTUAL_CONDICIONAL_2026-10-29.md), [RD-UC53-002](https://github.com/leandrito369/ale-milanesas/blob/main/docs/01-gobierno/i1/RD_UC53_002_REVOCAR_SESIONES_TRAS_CAMBIO_2026-10-29.md), [RD-UC54-001](https://github.com/leandrito369/ale-milanesas/blob/main/docs/01-gobierno/i1/RD_UC54_001_VERIFICACION_PROVEEDOR_2026-10-29.md). Ante contradicción prevalece la RD posterior; no alterar silenciosamente Fase4.
- **UC-01 `iniciarSesion`:** autentica Usuarios internos y cuentas PROVEEDOR, verifica contraseña/estado/roles, **emite y entrega** la sesión. No comprende Cliente ONLINE.
- **UC-53 `cambiarContrasena`:** gestiona cambio voluntario o obligatorio, revoca **todas** las sesiones previas tras éxito y obliga nuevo login.
- **UC-54 `restablecerAccesoUsuario`:** **NO** es login ni emisión de Bearer. Comando administrativo de restablecimiento asistido **solo para PROVEEDOR**; incluye UC-56 para identificar cuenta. No modifica por sí mismo la autenticación del Cliente ONLINE.
- **UC-56:** búsqueda previa del Usuario en UC-54.
- **Clientes ONLINE:** OTP y `SESION_CLIENTE_ONLINE` independientes; no aplicarles este Bearer ni crear Rol CLIENTE.

Este documento ratifica **decisiones técnicas de B4**, sin reemplazar firmas de contratos funcionales, inventar CU, asignar nuevos permisos RBAC o cambiar la línea base de 60 tablas MR5/F01. Los endpoints de sesión son adaptadores técnicos de UC existentes, no nuevos casos de uso.

## 2. Decisiones técnicas ratificadas

| Identificador | Parámetro | Decisión |
| --- | --- | --- |
| AUTH-01 | Secreto | **256 bits / 32 bytes** CSPRNG en servidor; nunca UUID, contraseña ni identificador predecible |
| AUTH-02 | Codificación | Base64URL **sin padding** canónico, **43 caracteres**. Rechazar otras longitudes, caracteres, padding y decodificación no canónica |
| AUTH-03 | Verificador almacenado | `SHA-256` aplicado a los **32 bytes originales** del token, exactamente 32 bytes `BYTEA` en `SESION_USUARIO.tokenProtegido` |
| AUTH-04 | Identificador interno | `SESION_USUARIO.idSesionUsuario` UUID se usa internamente, **nunca como credencial HTTP** ni como prueba de identidad |
| AUTH-05 | Expiración inicial | **30 minutos** desde `fechaHoraInicio` |
| AUTH-06 | Tope absoluto | **8 horas** desde `fechaHoraInicio` original; renovar no mueve esta marca |
| AUTH-07 | Renovación | Un token vigente puede rotarse atómicamente **en la misma fila**, sin refresh token independiente. Cada renovación fija `fechaHoraExpiracion = min(ahora + 30 min, fechaHoraInicio + 8 h)`; la sesión nunca vuelve a ser válida tras expirar |
| AUTH-08 | Revocación | Invalidación en PostgreSQL en el siguiente request y en la operación transaccional afectada. Nunca reactivar sesiones revocadas |
| AUTH-09 | Contraseña | `USUARIO.credencialProtegida` con verificación BCrypt; reutilizar `BCryptPasswordEncoder(12)` existente, sin degradarlo ni sustituirlo por SHA-256 |
| AUTH-10 | Política de autorización | Sesión vigente y no revocada, Usuario `HABILITADO`, `USUARIO_ROL` vigente y ámbito del objeto en servidor. **Sin caché de roles** entre requests |
| AUTH-11 | Transporte | TLS para todo intercambio y entrega de tokens; respuestas de autenticación con `Cache-Control: no-store` y `Pragma: no-cache`; no transportar secretos en URL ni logs |
| AUTH-12 | Gate | **Ninguna escritura HTTP de negocio** antes de B4-02B/C, pruebas y revisión de permisos; los comandos de autenticación son la excepción acotada |

**Precisiones:** el tope de 8 h es absoluto y no deslizante. Si una sesión expira a los 30 min sin renovarse, exige nuevo UC-01; `renew` no revive tokens expirados. Si la sesión se revoca por cambio de contraseña, rol, estado o vínculo comercial, tampoco puede renovarse.

## 3. Persistencia y atomicidad MR5/F01 (sin migraciones)

Tablas/columnas existentes: `USUARIO(idUsuario, nombreUsuario, credencialProtegida, estado, cambioCredencialPendiente)`, `ROL`, `USUARIO_ROL`, `USUARIO_PROVEEDOR`, `PROVEEDOR`, `SESION_USUARIO(idSesionUsuario, idUsuario, tokenProtegido, fechaHoraInicio, fechaHoraExpiracion, fechaHoraRevocacion)`.

**Emisión UC-01:** verificar credencial BCrypt y cuenta habilitada con 1..N Roles vigentes; comprobar exclusividad/relación PROVEEDOR cuando corresponda; generar 32 bytes CSPRNG, calcular SHA-256, insertar una nueva sesión con UUID interno independiente, inicio/expiración calculados y revocación NULL. Solo devolver secreto en la **respuesta HTTPS posterior al COMMIT**. Si se rechaza credencial o falla la transacción, no generar sesión efectiva. Reintentar generación ante hipotética colisión del índice UNIQUE de digest de forma acotada, nunca exponer errores SQL.

**Validación por solicitud:** decodificar Base64URL canónico y aplicar SHA-256 antes de buscar el digest; verificar `fechaHoraInicio <= ahora < fechaHoraExpiracion`, `fechaHoraRevocacion IS NULL`, `estado='HABILITADO'`, roles vigentes y `cambioCredencialPendiente`. Autenticar la petición con el sujeto recuperado de DB, **nunca** aceptar `idSesionUsuario`, `idUsuario` o `roles` aportados por el cliente. Validar permisos y ámbito en servicios B3 también, dentro de la transacción correspondiente.

**Rotación (renovación) con concurrencia:** transacción de BD; localizar digest y bloquear fila de sesión (`SELECT ... FOR UPDATE`), volver a comprobar que sigue vigente/no revocada, Usuario habilitado, roles y que NO tiene cambio obligatorio pendiente; exigir `ahora < fechaHoraInicio + 8 h`; generar nuevo secreto y reemplazar **en la misma fila** `tokenProtegido` y `fechaHoraExpiracion`. No modificar `fechaHoraInicio`, `idSesionUsuario` ni `fechaHoraRevocacion`. Si dos requests presentan el token viejo, **solo uno confirma**; el otro rechaza tras revalidar digest bajo lock. Revocaciones concurrentes ganan de forma segura: nunca resucitar una sesión. No entregar token nuevo antes del COMMIT.

**Pérdida de respuesta de renovación:** el token anterior ya es inválido; por contrato se exige nuevo UC-01. No permitir una ventana de dos tokens ni agregar familia de refresh tokens sin RD/CR futuro.

**Logout y otras revocaciones:** en una transacción marcar `fechaHoraRevocacion` de la sesión actual si aún está activa, sin borrar ni regenerar registros. MR5 ya contempla revocar sesiones ante cambio de hash/inhabilitación/roles; comprobar también el tratamiento de cambios de vínculo PROVEEDOR. Un logout repetido con token ya revocado produce 401 (no se considera sesión válida). Un cambio de rol/estado nunca conserva privilegios por una autoridad cacheada.

**Carreras de negocio:** los controles por request son necesarios pero no suficientes para mutaciones concurrentes. Cada comando B3 revalida identidad, ámbito, sesión y permisos en su transacción según su contrato/lock, sin confiar exclusivamente en el filtro HTTP.

## 4. Contrato HTTP/JSON para el canal API con Bearer

Se ratifican los siguientes **adaptadores técnicos propuestos**, que no sustituyen los métodos de contrato UC/DSS/DS. Prefijo `/api/v1`; `Content-Type: application/json` cuando corresponda.

| Método/ruta | Caso / acción | Autenticación y resultado |
| --- | --- | --- |
| `POST /api/v1/auth/login` | UC-01 | Anónimo con `nombreUsuario` y `credencial`; 200 con secreto **una única vez** y TTL |
| `POST /api/v1/auth/renew` | Renovación técnica de sesión | Bearer operativo vigente, no pendiente de UC-53; 200, token nuevo |
| `POST /api/v1/auth/logout` | Revocación explícita | Bearer vigente (operativo); 204 sin body |
| `POST /api/v1/auth/password/change` | UC-53 | Bearer vigente **operativo o restringido**, 204 sin body; revoca sesión invocante |
| `POST /api/v1/usuarios/{idUsuario}/restablecer-acceso` | UC-54 / UC-56 previo | Bearer operativo **ADMINISTRADOR**; 204 sin body y sin credencial devuelta |

**Request UC-01:**
```json
{"nombreUsuario":"operador", "credencial":"<secreto_ingresado_por_usuario>"}
```
**Response 200 UC-01:**
```json
{"accessToken":"<secreto_aleatorio_base64url>", "tokenType":"Bearer", "expiresIn":1800, "requiereCambioContrasena":false}
```
**Response 200 renovación:** mismo esquema sin `requiereCambioContrasena`, con `expiresIn` igual a los **segundos efectivos restantes** (nunca más de 1800 ni del límite de 8 horas). Ejemplo de request vacío: `POST /api/v1/auth/renew` con cabecera `Authorization: Bearer <secreto_actual>`. No incluir UUID interno ni Roles como prueba de autorización en respuestas. **`expiresIn` inicial** es 1800 para sesión creada, salvo limitación excepcional de política futura aprobada.

**Request UC-53:** `{"nuevaCredencial":"...","confirmacionNuevaCredencial":"...","credencialActual":"..."}`; `credencialActual` es opcional **solo** si `cambioCredencialPendiente=true`. Verificar nueva clave y confirmación, política de credenciales, que no reuse la credencial temporal y (en cambio voluntario) validar BCrypt de la actual. Éxito → actualizar hash e indicador a false, activar revocación de **todas** las sesiones incluidas la invocante y exigir nuevo login. No crear token nuevo automáticamente ni otorgar permisos en la sesión revocada.

**Request UC-54:** `{"identidadComercialVerificada":true,"credencialTemporal":"..."}`. Solo ADMINISTRADOR autenticado; la ruta contiene `idUsuario` objetivo de negocio, **no una identidad confiable de actor**. Validar cuenta identificada con UC-56, rol exclusivamente PROVEEDOR, vínculo con PROVEEDOR válido, condición comercial e inexistencia de segunda cuenta habilitada para ese Proveedor. La verificación comercial es una **confirmación personal del Administrador**, no inferible del booleano en aislamiento. Hash de `credencialTemporal`, activar cambio obligatorio y revocar sesiones; **no** habilitar cuenta deshabilitada. La credencial temporal la aporta el comando conforme a firma canónica; **no** inventar generación automática, entrega por WhatsApp ni método externo de verificación. No retornar la credencial ni su hash.

**Sesión restringida:** UC-01 con `cambioCredencialPendiente=true` **sí emite** un Bearer de sesión autenticada pero se limita a `POST /api/v1/auth/password/change`; no puede leer UC33/UC36, renovar, operar ni ejecutar UC54. Un token restringido que expira requiere iniciar sesión otra vez para UC-53. B4-01 hoy bloquea todos los tokens con indicador pendiente; su filtro debe **adaptarse en B4-02B**, no eliminar el control. No crear columna `accesoRestringido`: es estado derivado de USUARIO.

**Errores (sin filtrar identidad/SQL):**
| HTTP | Código de error JSON | Condición |
| --- | --- | --- |
| 400 | `SOLICITUD_INVALIDA` | JSON/parámetros faltantes, formato de credencial no permitido, confirmación incorrecta; mensaje seguro |
| 401 | `AUTENTICACION_RECHAZADA` | Login inválido o cuenta no habilitada; respuesta uniforme sin enumerar usuario |
| 401 | `SESION_INVALIDA` | Bearer ausente, malformado, desconocido, vencido o revocado; incluir `WWW-Authenticate: Bearer realm="ale-interno"` |
| 403 | `OPERACION_PROHIBIDA` | Sesión autenticada sin rol, fuera de ámbito o restringida a UC-53 |
| 409 | `CONFLICTO_ESTADO` | UC-54 cuenta no elegible o estado/vínculo cambiado concurrentemente, tras comprobar autorización |
| 429 | `INTENTOS_EXCEDIDOS` | Límite de autenticaciones excedido; cabecera `Retry-After` cuando corresponda |
| 503 | `AUTENTICACION_NO_DISPONIBLE` | Dependencia de seguridad indisponible: fail-closed, sin sesión |

Formato: `{"codigo":"...","mensaje":"..."}`, sin secreto, identificador de sesión, huella, pila o SQL. No devolver detalle sensible de si existe la cuenta; una respuesta 409 no puede revelar la existencia de un usuario a un actor no autorizado. Respuestas 401/403/429/503 también `Cache-Control: no-store`. Solo `POST /auth/login` y ruta health autorizada por separado son anónimas; **deny-all** para todo endpoint de negocio no habilitado.

## 5. Transporte, navegador y Android

- **API interna JSON (Android, otros clientes autorizados):** usar solo `Authorization: Bearer <token>` sobre TLS, no cookies ni parámetros. La API no acepta la cookie web como credencial implícita. Android guarda el token con cifrado y claves protegidas por **Android Keystore**, sin persistencia accesible en claro ni backup del secreto.
- **Web Thymeleaf:** un adaptador web de acceso (rutas UI a diseñar en B4-02B; **no** inventadas aquí) toma el secreto de la misma capa de sesión y lo emite como cookie `__Host-ale_session` con `Secure; HttpOnly; SameSite=Strict; Path=/` y **sin Domain**. El token jamás llega a plantilla, URL, JavaScript, `localStorage` ni `sessionStorage`. Cerrar sesión limpia la cookie además de revocar DB.
- **CSRF:** la cadena web por cookie mantiene protección CSRF de Spring Security para todas las acciones mutables **incluyendo login, logout, renovación y UC-53/UC-54 cuando se invocan desde el adaptador web**. Usar token CSRF independiente, no contenido del Bearer. La cadena API Bearer no autentica cookies, por lo que el CSRF de dicha cadena puede desactivarse **solo** después de verificar esta separación; no desactivar CSRF globalmente.
- **CORS:** denegar por defecto; nunca `*` con credenciales. Solo allowlist de orígenes expresamente autorizados si se introduce un frontend de origen distinto; no existe aprobación de SPA ni de CORS global.
- **Caché/log:** `Cache-Control: no-store`, `Pragma: no-cache`, cabeceras HTTPS de seguridad. No registrar `Authorization`, `Cookie`, `Set-Cookie`, cuerpos con contraseñas, digest, respuestas `accessToken`, datos personales ni trazas completas de autenticación.
- **Ataques de fuerza bruta:** exigir control de tasa por cuenta normalizada y origen, límites de tamaño de entrada, observabilidad sin secretos y rechazo uniforme. La implementación debe demostrar un limitador **válido para el despliegue real** (infraestructura compartida o control equivalente); un contador por JVM no protege varios nodos. **No** crear tablas MR5 adicionales por inferencia. Política exacta de umbrales operativos se fija/configura y prueba en B4-02B antes de exposición real; nunca permitir login sin protección.

## 6. Gate de aceptación B4-02B/C — TODO NO EJECUTADO

Pruebas automatizadas con Spring Security + PostgreSQL Testcontainers sobre código real, más prueba **HTTP externa** con servidor escuchando; MockMvc no acredita por sí solo E2E. Registrar SHA, comandos, entorno, resultados y logs saneados.

| ID | Escenario | Resultado esperado |
| --- | --- | --- |
| AUTH-T01 | Login correcto UC-01 | Token 43 caracteres, digest SHA-256(bytes), sesión MR5, TTL 30 min, no UUID/token en DB ni logs |
| AUTH-T02 | Credencial incorrecta, cuenta inexistente/deshabilitada, 0 Roles | Fallo sin sesión, sin enumeración, protección de tasa |
| AUTH-T03 | Bearer inválido, formato no canónico, UUID, revocado o vencido | 401; jamás autenticación con ID externo |
| AUTH-T04 | Roles/permisos cambiados entre requests | Revocación inmediata/403; sin caché de roles |
| AUTH-T05 | Usuario con `cambioCredencialPendiente=true` | Login restringido; **solo UC-53** accesible |
| AUTH-T06 | UC-53 voluntario/restringido exitoso | Hash actualizado, indicador false, roles conservados, **todas** las sesiones revocadas |
| AUTH-T07 | UC-53 inválido o persistencia fallida | Sin cambio parcial, sin revocación atribuida al intento fallido |
| AUTH-T08 | UC-54 Proveedor elegible con Administrador | Hash temporal protegido, indicador true, sesiones revocadas; estado/vínculo preservados |
| AUTH-T09 | UC-54 sin verificación, no proveedor, rol mixto o vínculo inconsistente | Rechazo atómico sin habilitar ni duplicar cuenta |
| AUTH-T10 | Renovación válida antes de 30 min | Nueva huella, mismo UUID interno, secreto anterior invalidado |
| AUTH-T11 | Dos renovaciones simultáneas con token previo | Exactamente una confirma; segunda 401/conflicto seguro |
| AUTH-T12 | Renovación tras vencimiento, revocación o a las 8 h | 401, nuevo login requerido |
| AUTH-T13 | Logout y uso posterior del Bearer | Revocación y posterior 401; sin reactivación |
| AUTH-T14 | Baja de Usuario/cambio de Roles o credencial durante renovación | No hay token posterior válido ni permisos obsoletos |
| AUTH-T15 | Cookie web: `HttpOnly`, `Secure`, `SameSite=Strict`, no JS | Cookie segura; ninguna credencial en DOM/logs/URL |
| AUTH-T16 | Mutación web sin CSRF / origen no autorizado | Rechazo, sin modificar datos |
| AUTH-T17 | Intentos de login masivos y problemas de BD | Límite 429; DB no disponible → fail-closed 503 |
| AUTH-T18 | POST/PUT/PATCH/DELETE de negocio antes del gate | Denegación expresa; no efectos de dominio |
| AUTH-T19 | B1/B2 en SHA final de PR; HTTP externo | Evidencia reproducible, sin confundir MockMvc con E2E |

**Dependencias externas conservadas:** 4 escenarios de UC-54 y BC06 18/18 definidos permanecen **NO EJECUTADOS E2E** hasta evidencia separada de ejecución; no convertir una revisión documental o MockMvc en éxito E2E. No se declara `PASS` para ningún AUTH-T todavía. MR5/F01 deben permanecer inmutables; si la implementación detecta una necesidad física real se abre CR/RD en el repositorio documental antes de SQL.

## 7. Secuencia y control de cambios

1. **B4-02A (esta PR):** ratificación técnica y trazabilidad, sin producción/código/DDL. Mantener [Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) abierta.
2. **B4-02B (PR futura):** adaptar la cadena B4-01 de [PR #27](https://github.com/leandrito369/ale-milanesas-app/pull/27), implementar UC-01, sesiones restringidas UC-53 y comandos de autenticación; aplicar límites de tasa, CSRF/CORS y persistencia MR5, sin abrir comandos de negocio.
3. **B4-02C (PR/evidencia futura):** pruebas de aceptación AUTH-T01..T19, CI B1/B2 del SHA real y HTTP externo. No afirmar aceptación académica final sin evidencia.
4. **B4-03 (posterior):** habilitar una escritura por operación con RBAC 67×8, ámbito, proyección BC07, DS/DSS/contrato, transacción, concurrencia e idempotencia. Continuar respetando restricciones F04/CR-B0-F04-001.

**No confundir:** APROBADO (decisión B4-02A) ≠ IMPLEMENTADO (B4-02B) ≠ PROBADO (B4-02C) ≠ INTEGRADO A `main` (merge revisado). Prohibido merge automático y habilitación temprana de escrituras.
