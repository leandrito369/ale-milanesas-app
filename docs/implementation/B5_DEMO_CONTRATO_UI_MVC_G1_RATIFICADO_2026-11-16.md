# B5_DEMO_CONTRATO_UI_MVC — G1 RATIFICADO POR EL RESPONSABLE

**Proyecto:** Sistema de Gestión para Ale Milanesas (UNaM-FCEQyN)  
**Fecha de decisión:** 16/11/2026, mediante conversación con el responsable.  
**Estado de diseño:** **G1 APROBADO en la conversación, con observaciones UC34/UC35 y secuencia F condicionada**.  
**Estado repositorios:** **PENDIENTE DE INCORPORACIÓN FORMAL MEDIANTE PR**; este archivo local no constituye commit, merge, CI ni aprobación de despliegue.  
**Puerta siguiente:** G2 autorizado **solo mediante ramas + PR draft separadas**. No se autoriza merge por agentes, publicación pública ni cambio de G0.

## 1. Referencias y límites

- App: `leandrito369/ale-milanesas-app`; corte auditado `aa68b7843296aafcb6c9ead510f0cbad265ab45b`.
- Gobierno: `leandrito369/ale-milanesas`; corte auditado `9076570ff3329864fe85c990bcc6f4ff7a63d6e5`.
- Source-lock G0 inmutable `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`; governance G0 `27738b089afdd2698cdae915f75c0e8089a6248a`, más RD posteriores pertinentes.
- Fuentes normativas: `RD_RP007_002_PERMISOS_BUSQUEDAS_2026-10-27.md`, `RD_B0_BC07_2026-10-29.md`, matriz `BC07_PROYECCIONES_MINIMAS_RATIFICADAS_2026-10-29.csv`, G0 contratos REV04A UC34/UC04 y REV06A UC35, AGENTS.md y source-lock. Verificar en la fuente canónica antes de codificar.
- Antecedentes: `B5_DEMO_CONTRATO_UI_MVC_G1_PROPUESTA_2026-11-16.md` y `RESOLUCION_PROPUESTA_UC34_UC35_PRE_G1_ALE_2026-11-16.md` (reemplazados por las decisiones presentes en cuanto entren en conflicto).
- Plazo académico **17/11/2026 23:59** Argentina; el trabajo de los cuatro entregables tiene prioridad sobre mejoras de UI.

## 2. Acta exacta de ratificación

| ID | Ratificación del responsable | Alcance/condición |
|---|---|---|
| D-UC35-BC07-001 | **RATIFICADA** | `VENDEDOR` podrá ver precio base vigente de venta en UC35; sin UC20, costo, margen, historial ni stock no permitido. La implementación requiere corte propio y pruebas. |
| D-UC34-CTX-001 | **RATIFICADA** | Buscar Pedido UC34 directo para ADMIN/VENDEDOR; REPARTIDOR y PRODUCCIÓN solo en contextos contractuales. Corrección técnica pendiente. |
| D-UC34-CTX-001-A (observación) | **RATIFICADA** | Mostrar **«Mis entregas»** vacía, explícitamente ilustrativa, sin consultar UC34/UC04 ni presentar datos de pedidos. No equivale a UC04 implementado. |
| G1-A | **APROBADA** | P0 real transaccional limitado a ADMIN/VENDEDOR; «Mis entregas» solamente ilustrativa. Precio vendedor, aunque ratificado normativamente, NO se afirma disponible hasta PR específica implementada y probada. |
| G1-B | **APROBADA** | Thymeleaf/MVC fino, sin JS obligatorio, CSS local; conservar CSP segura. |
| G1-C | **APROBADA** | 3 renglones de ítems (uno obligatorio, dos opcionales), dirección manual estructurada, UUID de idempotencia conservado. |
| G1-D | **APROBADA** | Entorno local/privado HTTPS con certificados confiables, PostgreSQL 17.11 aislado, Flyway V1–V3 y datos sintéticos. |
| G1-E | **APROBADA** | Codex y Antigravity pueden crear ramas/PR **draft** independientes, con revisión y CI. NO merge por agentes. |
| G1-F | **APROBACIÓN CONDICIONADA** | Antigravity solo podrá editar `src/main/resources/templates/web-inicio.html` **después del merge de la PR de Codex** y desde la base integrada vigente, en corte/PR posterior. Antes, archivo vedado. |

No se infiere de estas decisiones autorización para modificar contratos normativos en `main`, DDL, roles, servicios monetarios, cookies, producción o Issue #28. Las dos ratificaciones UC34/UC35 resuelven diseño, **no acreditan implementación**.

## 3. Semántica estricta del alcance P0 web

**Recorrido real exigible:** login web UC01 → consultar Clientes UC33 → consultar Productos UC35 (seis campos actuales, salvo precio si existe PR UC35 previamente integrada/probada) → crear Pedido UC03 como VENDEDOR → asignar Repartidor UC43 por ID sintético autorizado → consultar Pedido UC34 como VENDEDOR y ADMINISTRADOR con proyecciones distintas → logout y rechazo auténtico de acceso no autorizado. Persistencia real en DB sintética comprobada.

**Vista ilustrativa añadida (NO CU04):** `/web/demo/mis-entregas`, solo para REPARTIDOR autenticado y bajo perfil de demo no-prod; texto: **«Mis entregas — Módulo en preparación. La consulta de pedidos asignados estará disponible cuando UC-04 esté implementado y probado»**. Un estado vacío ilustrativo no significa «sin pedidos asignados»: no mostrar contador 0, lista de pedidos, simulación de descarga ni CTA que ejecute otra operación. No invocar UC34, UC04 o servicios/SQL en esta ruta. Acceso por otros roles: denegado en servidor; solo ocultar menú no basta. Si no se implementa ruta segura, presentar únicamente un indicador no interactivo contextual, sin afirmar navegación funcional.

**No incluido:** UC04 real, UC05/07/22/29, I3 completo, pagos, offline, Android, selector general de repartidores, interfaz completa de todos los CU, despliegue productivo, 4/4 E2E humanos UC54, 18/18 BC06.

## 4. Separación de responsabilidades y rutas

| Vista / ruta | Propiedad técnica | Roles y controles |
|---|---|---|
| `/web/login`, `/web/cambiar-clave`, `/web/renew`, `/web/logout` | Existente; Codex audita regresiones | Cookie `__Host-ale_session`, CSRF, sesiones actuales. No alterar en este corte salvo fix de seguridad explícito acotado. |
| `/web/inicio` | Existente; **NO EDITABLE por Antigravity antes del merge de Codex** | La posterior navegación por rol será corte F independiente. |
| `/web/demo/clientes` GET | Codex MVC + Antigravity plantilla | ADMIN/VENDEDOR; UC33 B3; sin deuda financiera. |
| `/web/demo/productos` GET | Codex MVC + Antigravity plantilla | ADMIN/VENDEDOR; UC35 B3; DTO actual sin precio hasta PR separada; sin costo/historial. |
| `/web/demo/pedidos/nuevo` GET | Codex MVC + Antigravity plantilla | VENDEDOR; `@Profile("!prod")` y mismo flag UC03, fail-closed. |
| `/web/demo/pedidos` POST | Codex MVC + Antigravity plantilla | VENDEDOR; flag UC03, CSRF, POST/Redirect/GET, validación B3. |
| `/web/demo/pedidos` GET | Codex MVC + Antigravity plantilla | **Solo ADMIN/VENDEDOR en demo P0**; UC34 B3, proyecciones 6/8 campos. |
| `/web/demo/pedidos/{idPedido}/asignar` GET/POST | Codex MVC + Antigravity plantilla | VENDEDOR; mismo flag UC43, perfil !prod, CSRF; sin GET mutante. |
| `/web/demo/mis-entregas` GET | **Codex controla ruta/seguridad**, Antigravity plantilla | REPARTIDOR autenticado únicamente; informativa, sin datos ni invocaciones B3. |
| `/demo/demo.css` GET | Codex regla de recurso/CSP, Antigravity CSS | Solo hoja de estilos local; sin scripts/CDN/unsafe-inline. |

### Contrato de implementación

- MVC reutiliza **directamente B3** con `PrincipalInterno.idSesion()` de Authentication; cada servicio revalida roles y ámbito. Prohibidos SQL de negocio en controlador/plantilla y HTTP loopback con bearer.
- Para UC03/UC43, misma bandera existente `ale.http.writes.pedidos-uc03-enabled` y `ale.http.writes.pedidos-uc43-enabled` apagada por defecto, `@Profile("!prod")`, veto `PrecondicionesDespliegue` y RBAC VENDEDOR. **Ambos GET de formulario y POST** no deben habilitarse con bandera apagada. GET de consulta no se utiliza para escribir.
- CSS: `src/main/resources/static/demo/demo.css`, servir solo ruta autorizada; CSP añadir `style-src 'self'` conservando `default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'`. No `unsafe-inline`, JS, estilos inline ni recursos remotos.
- Mantener cookies `Secure`/`HttpOnly`/`SameSite=Strict`, CSRF, Origin, `Cache-Control: no-store`, XSS escape de Thymeleaf. Pruebas Origin permitido/externo/`null`/ausente; no atribuir al filtro una obligación de header que no implementa.
- UC03: 3 renglones, mínimo uno válido; UUID generado y preservado en corrección/reintento, no reutilizar con payload intencionalmente distinto. Dirección: `calle`, `numero`, `piso`, `unidadFuncional`, `localidad`, `provincia`, `referencia`, `zona`. No dar precio desde navegador como autoridad.
- UC43: entrada `idUsuarioRepartidor` de fixture sintética; sin lista por UC56. Asignación inicial con motivo nulo; reasignación no se promete como P0 sin consultar asignación vigente.
- Errores 400/401/403/404/409/503, valores seguros y estados verdaderos. Éxito PRG 303, pruebas HTTP/Navegador; no mostrar secretos/SQL/stacktraces.

## 5. Resolución de UC35, corrección de UC34 y compatibilidad con G1

### UC-35: D-UC35-BC07-001

Ratificado: el catálogo para VENDEDOR debe incluir **precio base vigente aplicable a venta**. B3 actual `BuscarProductoService.ProductoConsulta` solo tiene seis campos; contrato G0 `buscarProducto` enumera esos campos, pero matriz BC07 ratificada incluye precio vendedor. La ampliación precisa **conciliación documental (gobierno) y PR independiente (app)**. No ampliar permisos de UC20, no poner costos/historial en HTML, no consultar SQL desde MVC. Considerar vigencia `[desde,hasta)`, cancelación, ausencia, concurrencia, compatibilidad API y proyección de ADMIN. No afirmar que el P0 ya muestra precios. El precio final del Pedido lo determina UC03 en servidor, con snapshot.

### UC-34: D-UC34-CTX-001

Ratificado: búsquedas directas ADMIN/VENDEDOR; REPARTIDOR y PRODUCCIÓN **solo contextuales**. El `BuscarPedidoService` actual ya restringe filas/proyecciones, pero el endpoint genérico `GET /api/v1/pedidos` todavía permite al Repartidor una búsqueda general acotada por filas; esto **no prueba el contexto contractual**. Abrir issue/PR técnica propia para separar acceso directo y contextual, manteniendo regresión UC28 y decisiones RD-RP007-002. No fingir 403 en el endpoint actual. La sección ilustrativa «Mis entregas» **NO debe usar ese endpoint**, ni implementa UC04.

Ambos frentes pueden tramitarse después de la entrega, sin bloquear **la aprobación documental G1** ni el P0 acotado si las limitaciones quedan visibles y no se afirman como corregidas.

## 6. Secuencia de PR y política de integración

1. **Codex PR draft B5-DEMO-01/B9-DEMO-01**: Java MVC, tests seguridad, ruta informativa REPARTIDOR, CSS allowlist/CSP, fixtures/scripts HTTPS privados y documentación técnica; **no editar templates ni CSS de Antigravity**, tampoco `web-inicio.html`. NO merge por el agente.
2. **Antigravity PR draft B5-DEMO-02**: nuevos templates en `templates/demo/**`, hoja `static/demo/demo.css`, UX y checklist navegador; **no editar `web-inicio.html` ni otra plantilla histórica**. Basarse en SHA común o commit aprobado, sin branch viejo ni force-push. NO merge por agente.
3. **Revisión cruzada y CI**: verificar rutas/variables/modelos, CSRF, flags, roles, CSS, demo local HTTPS y regresiones. Solo responsable autoriza merge; **Codex se integra primero**. Integrar luego la PR de Antigravity sin rebase ni force push (si requiere ajuste, commits nuevos sobre su rama).
4. **F posterior**: solamente **después del merge de Codex**; habilitar a Antigravity una rama/PR distinta desde la base integrada vigente para editar **exclusivamente `web-inicio.html`** y enlazar la navegación a las páginas reales ya integradas. Para enlazar «Mis entregas», respetar rol y existencia efectiva de la ruta informativa. No incluir F en la PR original anticipadamente.
5. **UC35 y UC34**: dos cortes técnicos independientes y posteriores (o documentales en paralelo) con sus propios tests; no meterlos en la PR B5 del circuito.

## 7. Criterios GO/NO-GO y estado académico

- **GO G2:** autorización conversacional A–E ratificada, F condicionado; ambas auditorías G0 completas. Agentes pueden crear PR draft como aquí delimitadas.
- **NO-GO merge:** hasta revisión humana, CI y verificación de incumplimientos de gate, proyecciones, cookies, CSRF, CSS y entorno.
- **NO-GO video web:** hasta arranque real HTTPS, autenticación válida, flujo UC03→UC43→UC34 ejecutado, persistencia verificada y tests críticos de seguridad. MockMvc o HTML por sí solos no equivalen a demo navegable.
- **Plan B académico de emergencia:** si no está ejecutable con seguridad, usar alternativa A de API/Postman y reconocer las limitaciones; no dejar sin preparar documentación, manual, nota y video del primer estado de avance.
- Issue #28 de despliegue sigue OPEN; no publicar un perfil no-prod en Internet ni cerrar aceptación humana UC54/BC06 por esta demo.

## 8. Trazabilidad de la autorización

Decisión proporcionada por el responsable en la conversación del 16/11/2026: «Ratifico ambas decisiones ... vamos a mostrar “Mis entregas” ... vacía ... ilustrativo», seguida de la aprobación G1-A/B/C/D/E y G1-F exclusivamente «post-integración de Codex». Esta fuente conversacional documenta intención; falta incorporarla al repositorio por el proceso de cambios del proyecto.
