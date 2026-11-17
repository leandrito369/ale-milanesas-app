# B8-DEMO-G4-R01 — Preservación del rechazo web 401

Base de diagnóstico: main@80f6564ca84fb8b904b52cbd1898a14d396e8781.
Rama correctiva independiente: fix/b8-g4-r01-estado-sesion-web-2026-11-17.
Fuente G0 inmutable: 1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e; gobierno: 27738b089afdd2698cdae915f75c0e8089a6248a.
Sin cambios de contrato, RBAC, BC-07, datos normativos ni despliegue.

## Diagnóstico demostrado antes de editar producción

Las evidencias originales G4 mostraron rechazo final 403 después de logout y UC-53. El diagnóstico nuevo usa un servidor servlet real, PostgreSQL 17.11 efímero, Accept text/html y un filtro exclusivo del test que observa REQUEST y ERROR. El wrapper de diagnóstico delega las respuestas sin cambiarlas.

| Paso observado | Método y URL | Cadena | Estado |
|---|---|---|---|
| Cookie anterior realmente presentada y revalidada | GET /web/demo/clientes tras logout; GET /web/inicio tras UC-53 | seguridadWeb | cookiePresent=true, sameOldCookie=true |
| Rechazo inicial del filtro de cookie | Mismo GET, dispatcher REQUEST | seguridadWeb | sendError(401), estado interno 401 |
| Despacho del contenedor | GET /error, dispatcher ERROR, error.status_code=401 | securityFilterChain global | entra con 401 |
| Rechazo global | GET /error, dispatcher ERROR | fallback denyAll | sendError(403) |
| Respuesta única vista por el cliente | GET original | servidor HTTP | 403 |

No hay dos respuestas de red: el 401 es el estado interno antes del despacho y el cliente recibe un único 403 final. El test compara la cookie en memoria y solo registra booleanos; no publica valores, identificadores de sesión ni secretos.

Los dos tests de revocación quedaron rojos en la base (expected 401, actual 403); el test de CSRF/Origin/RBAC/error directo ya devolvía 403 correctamente. La revocación en PostgreSQL fue real. Los GET afectados no dependen de CSRF. Los POST sin CSRF y con Origin externo son controles negativos independientes.

Conclusión: defecto de código en el uso de sendError para terminar rechazos de autenticación web, combinado con el despacho ERROR hacia una cadena global cerrada. La matriz QA esperaba correctamente 401 o redirección. Un test HTTPS histórico aceptaba el conjunto 401/403 y por eso no detectaba esta diferencia; se endurece a 401.

## Corrección mínima

En SeguridadWebInterna se cambia únicamente sendError(401) por setStatus(401) en:
- AuthenticationEntryPoint.
- Rama de AccessDeniedHandler para ausencia de autenticación.
- SesionCookieFilter cuando la cookie presentada está revocada, expirada o no es válida.

El handler/filtro termina la respuesta sin llamar a una operación protegida ni abrir un ERROR. La revalidación por request permanece intacta. Los 403 legítimos de CSRF, Origin y roles insuficientes no se cambian. Se conservan CSP y no-store.

SecurityConfiguration no se modifica. El denyAll global permanece y /error sigue cerrado, incluso en los ensayos explícitos de dispatcher ERROR. No se agrega permitAll ni excepción de rutas. La API Bearer, login, rotación, logout, UC-53 y B3 no se modifican. Tampoco PrecondicionesDespliegue ni PR #96.

## Pruebas reproducibles

EstadoSesionWebHttpTest: servidor real en loopback + PostgreSQL 17.11 con fixtures sintéticas de usuarios y sesiones sobre las migraciones existentes. Cookie explícita en cliente HTTP local para inspeccionar el protocolo; no acredita cookies de navegador TLS por sí sola. Registra ruta, método, dispatcher, cadena, estado, presencia de cookie y coincidencia con la anterior, sin valores.

EstadoSesionWebMvcTest: cadenas Spring Security reales en demo y prod, sin puertos ni registro del guard prod. Comprueba 401, sesión válida, 403 de controles y cierre de /error. Este slice no acredita arranque productivo, que queda fuera de este corte y de la PR #96.

ContinuidadSesionHttpsTest: cliente real HTTPS, confianza en certificado de ensayo, cookies y HTML/CSRF. La aserción posterior a logout pasa de aceptar 401/403 a exigir 401.

Comando focalizado:

    mvn -B -ntp -Dtest=EstadoSesionWebHttpTest,EstadoSesionWebMvcTest,ContinuidadSesionHttpsTest,AutenticacionHttpExternaTest,AutenticacionHttpIntegrationTest,DemoSeguridadTest,InicioModeloMvcTest,PrecondicionesDespliegueTest verify

El SHA final, resultado agregado, verificación Chromium y checks CI se registran en la descripción de la PR y el informe de ejecución, sin atribuir resultados de otro SHA.

## Límites

El cambio evita el despacho de error solo en rechazos web 401. No transforma globalmente todos los errores ni todos los 403. Los 403 legítimos pueden seguir usando el despacho existente y una página nativa de error en navegador. No se amplía este corte a UX de errores, otros estados o funcionalidades.

Tras integrar y revisar la corrección, repetir WEB-QA-02 y WEB-QA-03 en navegador: se espera rechazo final 401 con cookie anterior, login nuevo funcional y ningún acceso por sesión revocada. La comprobación dirigida no constituye por sí sola G4-GO. WEB-QA-16 sigue parcial; prod no se inicia ni se acepta aquí. G3-HUM, UC-54, BC-06 e Issue #28 permanecen independientes.
