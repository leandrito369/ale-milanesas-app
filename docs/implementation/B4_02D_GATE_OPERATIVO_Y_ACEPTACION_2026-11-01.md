# B4-02D — Gate operativo de autenticación y aceptación previa a B4-03

**Fecha:** 01/11/2026. **Estado:** implementación y pruebas propuestas en PR independiente; el gate de despliegue **NO se declara superado sin evidencia de entorno y revisión humana**.  
**WBS:** B4-02D, posterior a [B4-02C PR31](https://github.com/leandrito369/ale-milanesas-app/pull/31) y B4-02B PR30, referencia [Issue app#28](https://github.com/leandrito369/ale-milanesas-app/issues/28).  
**G0 fijado:** `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, `governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`. Adendas I1 UC53/UC54 y RD-UC53-002 prevalecen según source-lock. MR5/F01 inmutables, RBAC 67×8 y 15 proyecciones BC07 sin cambios.

## 1. Alcance real del gate

Separar dos puertas que no son equivalentes:

1. **Gate técnico para desarrollar B4-03 en entorno controlado:** B4-01/02A/02B/02C revisados e integrados en orden, SonarCloud+B1+B2+HTTP real verificables en SHA de merge, seguridad de desarrollo fail-closed para escrituras de negocio, revisión humana de diff. **No exige que los servicios F04 o B6 inexistentes ejecuten BC06 completo.**
2. **Gate para publicar o habilitar mutaciones HTTP reales fuera de test:** además de lo anterior, HTTPS/certificado/DNS/HSTS/firewall y proxy de instancia única probados en infraestructura real, tasa/anti brute-force observable, prueba de credenciales UC54 con actor humano y canal seguro de entrega acordado, auditoría de secretos y plan de respuesta/revocación. Esta PR no aporta certificados ni desplegó Nginx en un host productivo; **sigue ABIERTO**.

Las APIs de negocio POST/PUT/PATCH/DELETE permanecen en deny-all. No se liberan automáticamente al aprobar este PR.

## 2. Decisión operativa propuesta (sin MR5 adicional)

Se selecciona para el primer despliegue **UN SOLO proceso de aplicación por PostgreSQL** y **UN SOLO ingreso Nginx en el mismo host**, sin Kubernetes ni balanceador multinstancia hasta una RD/ADR de infraestructura posterior. El límite por usuario/IP dentro de la JVM de B4-02B es aplicable únicamente bajo esta restricción y se complementa con la zona compartida de tasa **por IP en Nginx**.

**Defensa técnica comprobable:**
- `UnicaInstanciaPostgres`: perfil `prod` exige `ale.security.singleton-db-guard=true`; obtiene `pg_try_advisory_lock(4402,2026)` a **nivel de sesión PostgreSQL**, reteniendo una conexión dedicada hasta apagar. Un segundo proceso que comparte la misma base no debe empezar. Se invoca `isValid` en esa conexión antes de cada ruta `/api/v1/**` o `/web/**` y la pérdida de la sesión bloquea el proceso hasta reinicio. **La aplicación no recupera silenciosamente el lock perdido**. No usar esta llave para reglas del dominio B3.
- `application-prod.yml`: `server.address=127.0.0.1`; `server.forward-headers-strategy=native` y `internal-proxies` solo loopback. `ALE_PUBLIC_ORIGIN` debe ser URL HTTPS sin path/query; `PrecondicionesDespliegue` rechaza arranque inseguro. El `RemoteIpValve` confía en cabeceras `X-Forwarded-For/Proto` **solo** desde loopback; Nginx sobrescribe las cabeceras aportadas por el cliente, nunca usa `$proxy_add_x_forwarded_for`. `request.getRemoteAddr()` se usa para limitar intentos en servidor.
- `deploy/nginx/ale-milanesas.nginx.conf.template`: TLS 1.2/1.3 y HSTS, host canónico, no acceso HTTP claro, upstream `127.0.0.1:8080`. Un `limit_req_zone` de memoria compartida entre workers limita POST de login por IP; exceso responde **429**. Para cuentas se mantiene límite de 5 fallos/15 min en la instancia única. GET /web/login no consume cupo del proxy.
- `scripts/security/verify_perimeter.sh`: levanta Nginx con certificado autofirmado de **test**, upstream falso, valida sintaxis, host, TLS y respuesta 429. Esta prueba **NO comprueba** el certificado público ni el firewall reales. Los certificados y claves privadas **nunca** se incluyen en el repo.
- Pruebas `UnicaInstanciaPostgresIntegrationTest` (dos conexiones compiten por advisory lock) y `PrecondicionesDespliegueTest` (rechazo a HTTP, origen no canónico, backend público y guard desactivado).

### Límite de arquitectura

**No** desplegar la aplicación en 2+ JVM ni detrás de dos Nginx independientes con esta solución: la cuota por cuenta en JVM y la cuota por IP en Nginx dejarían de ser compartidas. La implementación rechaza una segunda JVM conectada al **mismo PostgreSQL**; se requiere coordinación administrativa adicional si se despliegan bases independientes. El lock consume una conexión PostgreSQL a lo largo de la vida de la instancia. Un ataque que sature o desconecte PostgreSQL deja el login en fail-closed: disponibilidad menor preferible a bypass.

**Seguridad residual:** tasa en Nginx es por IP, no identidad comercial; usuarios detrás de NAT pueden compartir cuota y un atacante con muchas IP puede consumir intentos. Aplicar vigilancia, firewall y límites por cuenta del backend. Los umbrales se prueban en el entorno final y se ajustan operativamente. No interpretar 429 como prueba de resiliencia contra DDoS.

## 3. Runbook ejecutable en host único de aceptación (requiere operador)

**Previo:** JDK21, Docker/Nginx disponibles, DNS y certificado válidos gestionados externamente; DB MR5/F01 migrada y acceso PostgreSQL; ninguna contraseña/certificado en logs ni Git. El propietario del despliegue aporta FQDN, certificado y secretos fuera de control de versiones.

1. Configurar `ALE_PUBLIC_ORIGIN=https://<fqdn>`, `SPRING_PROFILES_ACTIVE=prod` y variables `ALE_DB_*` desde gestor de secretos o archivo protegido fuera de Git. Verificar que el backend escucha **solo en 127.0.0.1:8080**, no en interfaces externas, y que no tiene acceso público directo.
2. Copiar la plantilla Nginx reemplazando exclusivamente `@ALE_HOST@` por el FQDN. Montar `/etc/ale/tls/fullchain.pem` y `/etc/ale/tls/privkey.pem` con permisos limitados; no incluir ni exportar la clave privada a artefactos CI. Ejecutar `nginx -t` y aplicar reload controlado.
3. Confirmar con `curl --proto '=https' --tlsv1.2 --fail-with-body https://<fqdn>/actuator/health` que el certificado **real** valida CA, SAN y FQDN; repetir con `curl -I http://<fqdn>/` para comprobar que no se ofrece login en claro. Verificar HSTS, `Secure/HttpOnly/SameSite` de cookies web, CSRF, `Cache-Control: no-store`, ausencia de secretos en logs y que usuario/rol deshabilitado no accede.
4. Con datos sintéticos y sin credenciales reales, comprobar 429 tras una ráfaga controlada de POST de login desde IP de prueba, que otra IP no hereda la cuota y que credenciales válidas no resetean el cupo de IP. Verificar respuesta 503 y ausencia de escritura si se interrumpe PostgreSQL, en ventana de mantenimiento.
5. Arrancar un segundo proceso contra la **misma** BD: debe fallar al tomar lock. Desconectar la sesión lock del proceso primario: todas las rutas auth deben devolver 503, no admitir reconexión invisible; reinicio manual controlado. Revisar consola/health del servicio y registrar timestamp, SHA, FQDN, versión de Nginx, evidencia HTTP saneada, resultado y responsable.
6. Ejecutar checklists de aceptación UC54 con Administrador real y **Proveedor comercialmente verificado por el Administrador**, pero únicamente en entorno de aceptación aislado: sin WhatsApp, correo ni pago productivo desde la prueba. Registrar quién verificó, no el secreto temporal ni su hash. Definir y probar entrega **fuera de respuesta API** sin añadir canal no aprobado.
7. Concluir gate humano con acta **APROBADO/RECHAZADO**, SHA exacto revisado y responsables; solo entonces el responsable decide desbloquear las escrituras **caso por caso**, nunca masivamente.

### Checklist de evidencia (no fingir ejecución)

| Control | Evidencia mínima | Estado al crear PR |
|---|---|---|
| Sonar/B1/B2 B4-02C | GitHub SHA `7466b86151cc683fc23203b8a2a8e161bee8823e`: Sonar SUCCESS, 164/164 tests B1, B2 SUCCESS, 10/10 HTTP real | **EJECUTADO** en PR31 (no merge) |
| Singleton PostgreSQL (esta PR) | Testcontainers en CI, segundo proceso bloqueado, release tras cierre | **PENDIENTE CI** |
| Nginx TLS y límite por IP local (esta PR) | `nginx -t`, `curl` TLS validado con CA efímera, spoof Host 421, ráfaga POST 429 | **PENDIENTE CI** |
| TLS público/FQDN/Cert CA/Firewall/Host | Runbook con pruebas desde estación externa y logs saneados | **NO EJECUTADO (sin host ni certificado)** |
| UC54 – A1 | Cuenta no localizable/booleano false: rechazo y cero cambio | Escenario automatizado parcial; humano **NO EJECUTADO** |
| UC54 – A2 | No Proveedor exclusivo/vínculo inconsistente: rechazo | Escenario automatizado parcial; humano **NO EJECUTADO** |
| UC54 – A3 | Cancelación/fallo persistencia: rollback completo | **NO EJECUTADO E2E de aceptación** |
| UC54 – éxito | Administrador verificador, hash cambiado, Roles/vínculo/estado iguales, sesiones revocadas y UC53 obligatorio | Escenario automatizado parcial; entrega humana **NO EJECUTADO** |
| BC06 – 18 casos | 18 filas canónicas, cada una con servicio F04/B6 y before/after de datos | **0/18 E2E**, no son gate de autenticación |

**UC54 fuente:** [RD-UC54-001](https://github.com/leandrito369/ale-milanesas/blob/main/docs/01-gobierno/i1/RD_UC54_001_VERIFICACION_PROVEEDOR_2026-10-29.md), [Contrato UC54 REV02](https://github.com/leandrito369/ale-milanesas/blob/main/docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/contratos/CONTRATO_UC54_REV02.md). El Boolean significa la confirmación **del Administrador**; no es verificación autónoma del backend. No inventar un procedimiento de comprobación o canal de entrega.
**BC06 fuente:** [18 casos](https://github.com/leandrito369/ale-milanesas/blob/main/docs/01-gobierno/b0/BC06_CASOS_PRUEBA_IMPLEMENTACION_18.csv) y [informe de bloqueo](https://github.com/leandrito369/ale-milanesas/blob/main/docs/01-gobierno/b0/BC06_INFORME_EJECUCION_Y_BLOQUEO_2026-10-29.md). Incluye UC05, UC15/16, UC21, UC26/49 y UC29. Hay servicios B3 parciales pero **no hay aún todas las rutas F04/B6 necesarias**. Ejecución obligatoria cuando esas dependencias existan, sin falsos PASS.

## 4. Handoff a B4-03 y prohibiciones

Solo se declara **LISTO PARA REVISAR LA IMPLEMENTACIÓN DE B4-03** una vez revisada la cadena de PR y CI del merge SHA. **No** significa autorización para desplegar escritura real. B4-03 debe comenzar de a una operación, con autorización B3 en transacción/ámbito, contrato y BC07, y gate repetido sobre el SHA final. Retener **NO APROBADO PARA PRODUCCIÓN** hasta completar la evidencia humana del runbook. No cambiar G0, MR5/F01 ni los 18 BC06 canónicos desde el repositorio de aplicación.
