# Evidencia técnica HTTPS privada — B5/B9-DEMO-01

Fecha: 16/11/2026. Código Codex probado: `3a8bd5ef40f6636280961a375bfae6da52821ef6` (PR draft #94). Vistas candidatas Antigravity: `773feb08529f7feb65c112e91c3954247e33b29a` (PR draft #93). Ambas ramas SIN MERGE. Main permanece en aa68b7843296aafcb6c9ead510f0cbad265ab45b.

## Método y alcance real

Servidor Spring Boot real desde target/classes y classpath Maven, JDK21 completo, perfil demo, SSL directo en 127.0.0.1:8443 con SAN localhost/127.0.0.1. Cliente Python urllib con CookieJar y SSLContext de verificación activa, confiando explícitamente en el certificado temporal mediante cafile. Sin -k ni desactivar validación de hostname, Secure, CSRF u Origin. No se instaló una CA en el sistema/navegador. No se usó navegador.

`bash scripts/demo/entorno.sh db` creó PostgreSQL17.11 en proyecto ale-b5-demo, DB ale_milanesas_demo, puerto loopback55439 y tmpfs. El arranque aplicó Flyway V1–V3; la historia incluye también la fila de creación del esquema (4 entradas exitosas en total, NO cuatro migraciones versionadas). `java --class-path <classpath Maven> scripts/demo/Bootstrap.java` pobló maestros sintéticos vacíos, BCrypt12 y credenciales aleatorias solo en entorno. No se publican cuerpos de login, cookies, hashes, passwords, claves ni logs crudos.

Las vistas de #93 se leyeron de GitHub a un directorio temporal, junto con copias de las vistas históricas, y se habilitaron mediante SPRING_THYMELEAF_PREFIX=file:/tmp/... y SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/tmp/.... No se escribieron archivos de templates/static en el checkout ni se integraron ramas. Es una comprobación de compatibilidad candidata, no una integración autorizada G4. Contrato alineado en Codex: filtro/form, dirección plana, lista de 3 ítems y roles del servidor.

Comandos base reproducibles: `mvn dependency:build-classpath -Dmdep.outputFile=<archivo temporal>`; `java -cp target/classes:<classpath> ar.edu.unam.alemilanesas.AleMilanesasApplication` con SSL/DB/perfil/origen/flags equivalentes a scripts/demo/entorno.sh; `java --class-path <classpath> scripts/demo/Bootstrap.java`. Para JAR empaquetado, el comando del operador está en el runbook B5_B9_DEMO_01. El ensayo descrito NO acredita todavía el comando JAR ni mkcert -install.

## Resultados observados

| Comprobación | Resultado real |
|---|---|
| Health HTTPS con verificación de certificado/hostname | 200 |
| Login VENDEDOR por formulario histórico y CSRF actual | 302; cookie __Host-ale_session con Secure, HttpOnly y SameSite=Strict |
| GET clientes/productos/nuevo pedido/pedidos y CSS de #93 | 200 con HTML/CSS renderizado en servidor |
| Precarga idCliente=92001 en formulario | Conservada |
| UC03 por formulario HTML, 1 ítem de 3 y dirección manual | 303 a consulta de Pedido |
| Hidden CSRF explícito + automático del lado de plantilla | Parser de negocio no lo trata como entrada; filtro de seguridad conserva validación; envío HTTP con valores CSRF repetidos aceptado |
| UC34 como VENDEDOR y GET formulario UC43 | 200 |
| UC43 ID sintético 91003 | 303; asignación real persistida |
| VENDEDOR en Mis entregas | 403 |
| Logout y acceso residual a inicio | Logout302 y acceso residual denegado (401/403 según despacho de error existente) |
| ADMINISTRADOR consulta UC34 | 200; sin campos comerciales de VENDEDOR |
| Texto `<script>alert(1)</script>` en filtro UC33 | Render escapado `&lt;script&gt;`, sin etiqueta script ejecutable |
| REPARTIDOR en Mis entregas | 200; “Módulo en preparación”, sin enlace UC34, sin datos/contador |
| REPARTIDOR en UC34 web | 403 |

Verificación seleccionada de DB (sin credenciales): Pedido `PENDIENTE`, totalOriginal `200.00`; Detalle precioUnitarioOriginal `100.0000`, cantidadOriginal `2.000`; AsignacionReparto idUsuarioRepartidor `91003`. La prueba automática HTTP+DB adicional verifica reintento UC03 sin duplicados y preservación de fecha/categoría/importe/detalle al asignar.

JVM detenida, proyecto Docker de demo eliminado con `teardown`, certificados/claves temporales retirados al terminar. No queda un servicio de demo público ni se tocó producción.

## Lo que esta evidencia no acredita

Navegador real, instalación de CA en navegador, responsive/teclado/accesibilidad, video, aceptación humana, integración/merge, precio UC35 pendiente, corrección contextual de API UC34, UC04, UC05, UC54 humana y BC06: NO EJECUTADO o fuera de alcance. Mis entregas sigue ilustrativa. Issue #28 OPEN. NO-GO para video/merge hasta controles y decisión humana requeridos por G1.
