# G3-BLOCKER-01 — Continuidad de sesión y CSRF

Base: main26996e82fbbe264a797565cb4c53933b4e9c39b9. Frontend candidato PR93@a069322c583243d2da01e1210e0eaebcf0429594. G0/source-lock, B3, perfiles/gates de escritura y plantillas intactos.

## Causa demostrada antes de editar seguridad

La secuencia HTTPS anterior HTML→CSS→POST recibía403 sin persistir. La nueva regresión sobre la cadena original comprueba que HttpSessionCsrfTokenRepository contiene un token al renderizar y devuelve null después del GETCSS autenticado. El test rojo falla precisamente en esa continuidad.

Un ensayo diagnóstico separado sobre main, sin el fix, habilitó DEBUG únicamente de CsrfFilter en el arnés y observó: “Invalid CSRF token found for http://localhost/web/demo/pedidos/1/asignar”, seguido de403. No se registraron tokens. Origin correcto y rol/flag válidos no evitan ese rechazo.

SessionManagementFilter consulta containsContext del SecurityContextRepository. NullSecurityContextRepository siempre devuelve false; la cookie vuelve a establecer Authentication tras validación PostgreSQL en cada petición. El procesamiento automático interpreta cada petición autenticada como una nueva autenticación y ejecuta CompositeSessionAuthenticationStrategy: cambia el ID de sesión y CsrfAuthenticationStrategy elimina el token del repositorio. CSS también atravesaba esa cadena. La rotación por sí sola no demuestra pérdida de atributos: se comprobó además la eliminación efectiva del token.

Thymeleaf agrega el hidden automático además del explícito: se observaron dos campos _csrf. Los dos siguen siendo aceptables después del fix; no se modifica ninguna plantilla ni se atribuye la causa a duplicados.

## Cambio acotado

SeguridadWebInterna pasa a requireExplicitAuthenticationStrategy(true), sin configurar la propiedad sessionCreationPolicy que en SpringSecurity7 fuerza la estrategia implícita y es incompatible con la opción explícita. HttpSession sigue disponible para CSRF; no se persisten identidad, roles ni Bearer en ella. NullSecurityContextRepository y la revalidación PostgreSQL por petición se conservan.

AccesoWebController aplica explícitamente al login exitoso las estrategias originales de Spring: ChangeSessionIdAuthenticationStrategy y CsrfAuthenticationStrategy. Así se mantiene la protección de fijación y se invalida CSRF anónimo al autenticar, una sola vez por login. Lecturas posteriores de cookie no vuelven a ejecutar la estrategia de login. Las autoridades transitorias usadas al invocar esa estrategia no se guardan como identidad de sesión.

No se deshabilitan CSRF, Origin, validación de sesión, CSP o flags; no se amplía ninguna autorización. Secure/HttpOnly/SameSite, gates !prod y defaultsfalse permanecen. No se cambia PrecondicionesDespliegue: su problema de instanciación de runtime detectado en G3 es un frente independiente.

## Pruebas

- DemoSeguridadTest incorpora continuidad del repositorio CSRF tras CSS: falla sobre main y debe pasar con el fix.
- ContinuidadSesionHttpsTest usa servidor HTTPS real, certificado SAN localhost confiado por el cliente, PostgreSQL17.11 y una CookieManager única. Login real, cambio de ID por fijación, rechazo del token anónimo previo, HTML Thymeleaf, dos hiddenCSRF, CSS200, continuidad de ID, POST303, Pedido/Detalle persistidos, mismaUUID sin duplicar, UC43/303, negativos sinCSRF/Originexterno, logout y rechazo de sesión posterior.
- Para CI independiente de PR93, esa clase crea recursos de vista mínimos en un directorio temporal, renderizados por Thymeleaf y servidos por los controladores reales. No edita recursos de la aplicación ni usa mocks de B3. El ensayo combinado con las ocho vistas reales de PR93 se registra por separado en la PR.
- Las pruebas existentes cubren roles ADMIN/REPARTIDOR, flagsfalse, ausencia de controllers prod y regresión de autenticación/UC53/B4. No se convierte el test de guard instanciado manualmente en acreditación de arranque productivo.

Resultados efectivos, SHA y CI: descripción y checks de la PR correctiva. Comando focalizado: mvn -B -ntp -Dtest=ContinuidadSesionHttpsTest,DemoSeguridadTest,DemoPedidoHttpIntegrationTest,PrecondicionesDespliegueTest,AutenticacionHttpExternaTest,AutenticacionHttpIntegrationTest,CargarPedidoHttpExternoTest,AsignarRepartoHttpExternoTest,BuscarPedidoHttpExternoTest,ConsultaProductoHttpExternoTest,ConsultasI1HttpIntegrationTest verify.

Sin merge, rebase, amend, force-push, cambios de PR93 o despliegue público. Issue28 permanece OPEN. La resolución de este blocker no equivale a aceptación humana, GO global G3 ni aprobación de producción.
