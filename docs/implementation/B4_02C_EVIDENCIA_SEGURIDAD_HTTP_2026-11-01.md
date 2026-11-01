# B4-02C — Auditoría SonarCloud y pruebas de seguridad (01/11/2026)

**Estado:** PROPUESTO en PR apilada, **no aprobado ni habilitado para despliegue** hasta completar pruebas, calidad y gate humano.  
**Autoridad:** B4-02A PR #29 (ratificado, no integrado) y B4-02B PR #30, B4-01 PR #27.  
**Fuente G0:** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`; `governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`; adendas RD-UC53-001/-002, RD-UC54-001 en fuente posterior.

## Hallazgos SonarCloud: diagnóstico reproducible

El 01/11/2026, [Quality Gate PR #30](https://sonarcloud.io/dashboard?id=leandrito369_ale-milanesas-app&pullRequest=30) falló con **Security Rating E**. Para evitar inferencias se consultó `/api/issues/search` en runner de GitHub Actions: [ejecución 41336229402](https://github.com/leandrito369/ale-milanesas-app/actions/runs/41336229402). Respuesta HTTP 200; cuatro hallazgos:

| Regla | Nivel | Archivo/línea reportada | Causa | Resolución |
| --- | --- | --- | --- | --- |
| `java:S6437` | BLOCKER/VULNERABILITY | `SesionesUsuarioService.java:29` | Contraseña literal fija usada para construir el hash ficticio | Generar dummy con `TokenSesionInterna.nuevo()` CSPRNG antes del BCrypt |
| `java:S1192` | CRITICAL/CODE_SMELL | `SesionesUsuarioService.java:61` | Clave de parámetro `digest` duplicada | Constante `PARAM_DIGEST` |
| `java:S1192` | CRITICAL/CODE_SMELL | `SeguridadHttpInterna.java:58` | Rol `ADMINISTRADOR` duplicado | Constante `ROL_ADMINISTRADOR` |
| `java:S1192` | CRITICAL/CODE_SMELL | `AuthInternaController.java:120` | Campo de respuesta `codigo` duplicado | Constante `CODIGO` |

Los cuatro cambios están en **PR #30**, no en este PR B4-02C, para preservar responsabilidad de la corrección. **Un diff no equivale a Quality Gate PASS**: verificar análisis Sonar del nuevo SHA y registrar resultado.

## Pruebas propuestas de B4-02C

| ID B4-02A | Evidencia / control | Estado |
| --- | --- | --- |
| AUTH-T01/T03 | Login API real: CSPRNG, token 43, huella SHA256, lectura autorizada/UUID no aceptado | Pendiente de CI |
| AUTH-T05/T06 | Cambio obligatorio desde token restringido y revocación de todas las sesiones | Pendiente de CI |
| AUTH-T08/T09 | UC54 solo proveedor/Admin verificado, rechazo de cuenta no elegible | Pendiente de CI |
| AUTH-T10/T11 | Dos renovaciones HTTP concurrentes; una sola responde 200, token anterior 401 y UUID de sesión igual | Pendiente de CI |
| AUTH-T12/T13 | Expiración absoluta y logout HTTP real (no revive token) | Pendiente de CI |
| AUTH-T15/T16 | `/web/**` Thymeleaf: token CSRF distinto del Bearer, cookie `__Host-ale_session` Secure HttpOnly Strict, POST sin CSRF 403, Origin ajeno 403, logout revoca | Pendiente de CI |
| AUTH-T18 | POST comercial denegado, aunque haya sesión válida | Pendiente de CI |
| AUTH-T19 | B1/B2 y tests HTTP externo contra servidor `RANDOM_PORT` y PostgreSQL Testcontainers | Pendiente de CI |

**Importante:** el E2E es una llamada de red HTTP a un servidor local escuchando en puerto aleatorio, con PostgreSQL Testcontainers, no MockMvc. No es E2E de despliegue TLS público, Android, proxy, balanceador o entorno productivo. Es evidencia de integración funcional HTTP externa al DispatcherServlet de prueba.

## Componentes nuevos y limitaciones deliberadas

- `SeguridadWebInterna`: segunda cadena de seguridad `/web/**` con autenticación cookie de la **misma** sesión MR5 y CSRF de HttpSession (no autoridad persistida en HttpSession), control de Origin y cabeceras CSP/nosniff. La cadena JSON `/api/v1/**` no acepta cookies.
- `AccesoWebController`: UC01/cambio UC53/renovar/cerrar sesión, solo formularios autenticados; **sin escrituras de negocio**.
- Tres plantillas Thymeleaf con CSRF escondido y **sin mostrar Bearer**.
- Configuración de cookie de HttpSession para CSRF: Secure + HttpOnly + SameSite Strict; cookie de autenticación `__Host-` sin Domain, Secure, HttpOnly, SameSite Strict, Path=/.
- `AutenticacionHttpExternaTest` verifica transacciones confirmadas y no usa `@Transactional` para evitar que el servidor vea datos sin COMMIT.

**Pendientes de seguridad que no deben silenciarse:**
1. Limitador de intentos actualmente por **JVM** en B4-02B. En producción se necesita un limitador distribuido verificable o imposición documentada y forzada de instancia única + control ingress. Sin eso, puerta de despliegue expuesto permanece cerrada.
2. Validar **TLS real, HSTS, reverse proxy confiable y host/origin canónico** en entorno de despliegue. Pruebas loopback HTTP no sustituyen TLS.
3. El flujo de recuperación UC54 sigue requiriendo confirmación humana real y entrega comercial segura de contraseña temporal; los tests solo simulan el booleano autorizado del Administrador, **no verifican identidad real**.
4. Faltan escenarios de ataques de alta carga, rate limit distribuido, fallos de BD y compatibilidad Android; UC54 0/4 y BC06 0/18 de sus baterías **E2E externas originales** permanecen no certificados.
5. Hacer revisión de restricciones de rol/ámbito concurrentes y coherencia de cronología en PostgreSQL; no habilitar mutaciones comerciales.

## Orden de integración y cierre

B4-01 PR27 → B4-02A PR29 → B4-02B PR30 (Sonar PASS+B1/B2) → B4-02C (HTTP externo, web CSRF, pruebas y revisión humana). Mantener la [Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) abierta si hay algún gate sin acreditar. Ninguna migración MR5/F01 ni bypass de seguridad en `main` sin revisión.
