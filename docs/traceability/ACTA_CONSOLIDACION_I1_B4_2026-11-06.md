# Acta de consolidación funcional I1/B4 — Productos y Usuarios

**Fecha de corte:** 2026-11-06

**Repositorio de implementación:** `leandrito369/ale-milanesas-app`

**Línea base examinada:** `main` @ `4ae552fd7528700ba6efe875132d632b2416e0da`

**Estado:** consolidación documental de dos bloques HTTP; **no es acta de cierre integral de I1**, ni autorización de despliegue.
**Control de cambios:** PR documental en borrador, con revisión y aprobación del responsable pendientes.

## 1. Resumen ejecutivo y alcance

Se consolidan **ocho casos de uso con adaptador HTTP integrado**:

- **B4-03 / Productos:** UC-30 (alta), UC-35 (búsqueda), UC-47 (modificación), UC-48 (cambio de estado).
- **I1-01 / Usuarios:** UC-17 (alta), UC-18 (gestión de roles), UC-55 (cambio de estado), UC-56 (búsqueda).

Los ocho PR funcionales constan **fusionados en `main`**. Los controles de GitHub Actions sobre sus HEAD definitivos fueron exitosos, con cantidad creciente de workflows según el momento de incorporación: **#44: 7/7, #47: 8/8, #50: 9/9, #53: 10/10, #55: 10/10, #57: 10/10, #59: 10/10, #61: 10/10**. Se consultaron las corridas de Actions vinculadas a cada SHA; **no se pudo acreditar la afirmación uniforme de 11/11 para cada PR** con esa fuente. SonarCloud aprobó en #57, #59 y #61 según el seguimiento del responsable; se trata de una evidencia distinta del conteo de workflows de Actions.

**Precisión de seguridad:** la **triple barrera no-productiva rige para los seis endpoints de escritura** de esta tabla. UC-35 y UC-56 son consultas GET con control Bearer/RBAC y `no-store`, **sin** flag de escritura, `@Profile("!prod")` ni veto específico en `PrecondicionesDespliegue`. Por lo tanto, no corresponde afirmar que *los ocho* tienen triple gate ni que esos dos GET están técnicamente excluidos del perfil `prod`. El **despliegue del entorno sigue pausado** por decisión del responsable (Issue #28).

La consolidación de Productos y Usuarios **no cierra I1 completo**: quedan los cortes funcionales enumerados en la sección 7. El cambio de Modelo Relacional por partidas permanece en una vía de gobernanza independiente.

## 2. Operaciones HTTP integradas

| UC | Método y ruta HTTP | Rol autorizado | PR de integración | Merge commit | Documento de implementación |
| --- | --- | --- | --- | --- | --- |
| UC-30 | `POST /api/v1/productos` | `ADMINISTRADOR` | [#44](https://github.com/leandrito369/ale-milanesas-app/pull/44) | `b19c950` | [`B4_03_04_UC30_...`](../implementation/B4_03_04_UC30_ALTA_PRODUCTO_HTTP_GATED_2026-11-02.md) |
| UC-35 | `GET /api/v1/productos` | `ADMINISTRADOR`, `VENDEDOR` | [#47](https://github.com/leandrito369/ale-milanesas-app/pull/47) | `30415e3` | [`B4_03_05_UC35_...`](../implementation/B4_03_05_UC35_BUSCAR_PRODUCTO_HTTP_2026-11-03.md) |
| UC-47 | `PUT /api/v1/productos/{idProducto}` | `ADMINISTRADOR` | [#50](https://github.com/leandrito369/ale-milanesas-app/pull/50) | `feadba4` | [`B4_03_06_UC47_...`](../implementation/B4_03_06_UC47_MODIFICAR_PRODUCTO_HTTP_2026-11-05.md) |
| UC-48 | `PATCH /api/v1/productos/{idProducto}/estado` | `ADMINISTRADOR` | [#53](https://github.com/leandrito369/ale-milanesas-app/pull/53) | `7598d85` | [`B4_03_07_UC48_...`](../implementation/B4_03_07_UC48_CAMBIAR_ESTADO_PRODUCTO_HTTP_2026-11-05.md) |
| UC-17 | `POST /api/v1/usuarios` | `ADMINISTRADOR` | [#57](https://github.com/leandrito369/ale-milanesas-app/pull/57) | `4165e45` | [`I1_01_B4_UC17_...`](../implementation/I1_01_B4_UC17_CREAR_USUARIO_HTTP_2026-11-05.md) |
| UC-18 | `PATCH /api/v1/usuarios/{idUsuario}/roles` | `ADMINISTRADOR` | [#59](https://github.com/leandrito369/ale-milanesas-app/pull/59) | `49d0c8b` | [`I1_01_B4_UC18_...`](../implementation/I1_01_B4_UC18_GESTION_ROLES_HTTP_2026-11-05.md) |
| UC-55 | `PATCH /api/v1/usuarios/{idUsuario}/estado` | `ADMINISTRADOR` | [#61](https://github.com/leandrito369/ale-milanesas-app/pull/61) | `4ae552f` | [`I1_01_B4_UC55_...`](../implementation/I1_01_B4_UC55_ESTADO_USUARIO_HTTP_2026-11-05.md) |
| UC-56 | `GET /api/v1/usuarios` | `ADMINISTRADOR` | [#55](https://github.com/leandrito369/ale-milanesas-app/pull/55) | `ed4897d` | [`I1_01_B4_UC56_...`](../implementation/I1_01_B4_UC56_BUSCAR_USUARIO_HTTP_2026-11-05.md) |

**Corrección de contrato:** UC-18 utiliza `PATCH`, no `POST`. Los identificadores `{idProducto}` y `{idUsuario}` corresponden a los parámetros declarados en los controladores; equivalen al `{id}` genérico de la planificación.

## 3. Trazabilidad canónica y decisiones posteriores

| Referencia | Commit / estado | Criterio |
| --- | --- | --- |
| `source_commit` (G0) | `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` | Fuente contractual congelada. |
| `governance_commit` | `27738b089afdd2698cdae915f75c0e8089a6248a` | Acta de gobierno G0. |
| `RD-UC30-001` | `4971625a215d94a8b9f6439d9b0836e0925ea1ad` | Decisión posterior referenciada sin sustituir G0. |
| `RD-UC56-001` | Pendiente de integrar en el repositorio de gobierno; [PR documental #33](https://github.com/leandrito369/ale-milanesas/pull/33) | No atribuir aprobación definitiva a su estado de propuesta. |

La referencia operativa es [`source-lock.md`](source-lock.md). Se preservan **MR-5/F01**, las migraciones existentes y la precedencia documental. Una decisión pendiente exige su propia aprobación documental; la integración HTTP no equivale a homologar un cambio de gobierno.

## 4. Protección de exposición HTTP y alcance del triple gate

| Operaciones | Control de activación | Perfil | Veto de arranque en `prod` | Seguridad de ruta |
| --- | --- | --- | --- | --- |
| **Escrituras:** UC-30, UC-47, UC-48, UC-17, UC-18, UC-55 | `ale.http.writes.*-enabled`, valor por defecto `false` en `SeguridadHttpInterna` y `@ConditionalOnProperty` | Controladores con `@Profile("!prod")` | Sí, para las seis flags en `PrecondicionesDespliegue` | Bearer + rol `ADMINISTRADOR` + flag |
| **Lecturas:** UC-35, UC-56 | Sin flag específica | Controladores **sin** `@Profile("!prod")` | **No** hay veto específico | Bearer + RBAC; UC-35 Admin/Vendedor, UC-56 Admin |

**Ubicación efectiva de flags:** `src/main/resources/application.yml` no declara esos indicadores; los valores `false` por defecto están expresados en la configuración Java. Por eso, consignar «flags en application.yml» sería inexacto. Las lecturas requieren la autorización correspondiente y sus respuestas llevan `Cache-Control: no-store`, pero **no están apagadas por un triple gate**. Esta constatación es de alcance documental y no modifica el código.

## 5. Deuda técnica y trazabilidad abierta

- [Issue #35](https://github.com/leandrito369/ale-milanesas-app/issues/35): seguimiento de cobertura/JaCoCo y deuda de calidad; persiste la duplicación de *advice* HTTP. El responsable informó SonarCloud verde en #57/#59/#61. El refactor de *advice* común se atiende en paralelo, fuera de este PR y sin alterar esta acta.
- **Excepciones tipadas en B3:** oportunidad de mejora **no bloqueante**; requiere Issue técnica propia antes de planificar cambios de código. Esta acta no da por creada la Issue.
- **UC-30:** queda diferida la normalización fina de metadatos de trazabilidad canónica, sin cambiar las referencias fijadas.
- **PR histórico [#9](https://github.com/leandrito369/ale-milanesas-app/pull/9):** documentación, pruebas y *source-lock* sujetos a revisión selectiva; no integrarlos por arrastre.
- **PR histórico [#11](https://github.com/leandrito369/ale-milanesas-app/pull/11):** scripts/evidencia de CI y verificación de *pinning* SHA de Actions como mejora de seguridad de cadena de suministro.
- **PR histórico [#12](https://github.com/leandrito369/ale-milanesas-app/pull/12):** base técnica de los escenarios de BC06, vinculada a [Issue #13](https://github.com/leandrito369/ale-milanesas-app/issues/13).
- **Triage:** #16 (UC-55 B3), #17 (Clientes B3) y #18 (Productos B3) cerrados como históricos; #20, #22 y #24 conservados por decisión del responsable.

## 6. Bloqueo operativo y aceptación humana

- [Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28): **ABIERTA**. Entorno de despliegue **PAUSADO** por el responsable desde el 2026-11-02; no hay autorización de puesta en producción.
- [Issue #10](https://github.com/leandrito369/ale-milanesas-app/issues/10): **ABIERTA**. UC-54: **0/4 E2E humanos acreditados**.
- [Issue #13](https://github.com/leandrito369/ale-milanesas-app/issues/13): **ABIERTA**. BC06: **0/18 escenarios reales acreditados**.
- [Issue #15](https://github.com/leandrito369/ale-milanesas-app/issues/15) y [#62](https://github.com/leandrito369/ale-milanesas-app/issues/62): **CERRADAS** tras la consolidación de I1-01.

El CI en verde prueba ejecución automática de controles, **no** sustituye aceptación humana ni certificación TLS/infraestructura. Aunque el gate impide activar las seis escrituras experimentales en producción, las dos rutas GET conservan alcance de lectura con RBAC: **la pausa de despliegue sigue siendo la prohibición operativa**.

## 7. Pendientes funcionales para completar I1

| Frente | Casos de uso / estado | Próximo entregable |
| --- | --- | --- |
| **Precios** | UC-19 / UC-20; B3 conservado en [PR #20](https://github.com/leandrito369/ale-milanesas-app/pull/20), **NO CERRAR** | B4 HTTP separado, con Issue y PR propios. |
| **Categorías** | UC-15 / UC-16 / UC-57; B3 en [PR #22](https://github.com/leandrito369/ale-milanesas-app/pull/22), **NO CERRAR** | Completar línea base B3 y B4 en corte posterior. |
| **Insumos** | UC-50 / UC-51 / UC-52 / UC-36; B3 en [PR #24](https://github.com/leandrito369/ale-milanesas-app/pull/24), **NO CERRAR** | B4 en corte posterior, sin adelantar MR-6. |
| **Cobro manual** | UC-29: **ya existe `CobroManualService` en B3** (`BC06-01..06`); **no existe adaptador B4 HTTP** para esta operación | Diseñar/exponer B4 y mantener pendiente aceptación BC06. |

**Clientes:** sus cortes B4 previos (#42 y relacionados) no se repiten en la tabla de ocho endpoints de Productos/Usuarios. El cierre documental de estos dos bloques **no debe confundirse con cierre integral de I1**.

## 8. Próximo paso del plan rector: RP de Modelo Relacional

La **ventana propuesta es 2026-11-09 a 2026-11-11** para tramitar en el repositorio de gobierno la **RP/CR sobre `PARTIDA_INSUMO`**, basada en [`CONCLUSIONES_CAMBIO_MR_PARTIDA_INSUMO_2026-11-05.md`](CONCLUSIONES_CAMBIO_MR_PARTIDA_INSUMO_2026-11-05.md), integrada mediante **PR #51**. La propuesta contempla `PARTIDA_INSUMO` y `DETALLE_MOVIMIENTO_PARTIDA`, sin alterar la línea base actual.

**Secuencia de autoridad:** presentar RP en `leandrito369/ale-milanesas` → **aprobación formal del responsable** → eventual acta RD y nuevo MR-6 → migración nueva y pruebas mediante PR independiente en la aplicación. Hasta esa aprobación no se edita MR-5/F01, ni `V1__baseline_mr5.sql`, ni se despliega. El avance funcional I1 permanece separado del cambio relacional.

---

**Resolución del acta:** se reconoce **B4-03 Productos** e **I1-01 Usuarios** como integrados en código y validados por Actions según evidencia disponible; se deja **I1 general abierto**, se identifican las salvedades de seguridad y cobertura de CI y se ratifica la **pausa de despliegue**, sin otorgar aprobación de producción ni de MR-6.
