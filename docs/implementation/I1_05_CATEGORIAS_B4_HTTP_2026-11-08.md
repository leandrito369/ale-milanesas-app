# I1-05 — B4 HTTP de UC57: política de categoría comercial

Fecha del corte: 2026-11-08. Base verificada: origin/main `15f4b6c`, merge I1-04.
UC57 registra política y dos ajustes por POST bajo bandera no-prod, exclusivamente
para ADMINISTRADOR. UC15/16 siguen como operaciones internas, sin endpoints.
Issue #28: sin despliegue, entrega por PR draft, sin merge automático.

## Fuentes

- G0 source_commit `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, governance_commit
  `27738b089afdd2698cdae915f75c0e8089a6248a`, preservados según source-lock.
- Contrato canónico UC57: `docs/08-diagramas/revision/REV06B_CLIENTES_RECETAS_INVENTARIO/propuestos/CONTRATOS_REV06B_TRECE_CORREGIDOS.md` §5.
- DSS/DS de G0: `docs/08-diagramas/revision/REV06B1_CLIENTES_CATEGORIA/propuestos/`:
  `DSS_REV06B1_CLIENTES_CATEGORIA_UC02_15_16_32_33_38_57_CORREGIDO.drawio.xml` y
  `DS_REV06B1_CLIENTES_CATEGORIA_UC02_15_16_32_33_38_57_CORREGIDO.drawio.xml`.
- Ficha Real UC57: `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`.
- RBAC: B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv: UC57 ADMIN D;
  UC15/16 AUTOMATISMO I, sin permiso de invocación humana directa.
- RD-F04-003: separación de pago en tránsito y saldo confirmado, sin autorización
  de nuevas estructuras físicas por esta implementación.
- RD-F04-006: evaluación de deuda separada de categoría comercial.
- RD-F04-007: ajuste EN_MORA continúa hasta reevaluación formal; un pago provisional
  no reclasifica al cliente ni suspende automáticamente el recargo.
- RD-RP023-001: condiciones comerciales históricas conservadas; no redefine categoría.
- Servicios B3 ya integrados por `fd4bd6d9dd89e43b2fdec29c49a553a3f1b15d93`, intactos.

| CU / operación | B3 existente | Persistencia | HTTP / test |
|---|---|---|---|
| UC57 configurarPoliticaCategoriaComercial | ConfigurarPoliticaCategoriaComercialService | POLITICA_CATEGORIA_COMERCIAL y exactamente dos AJUSTE_POLITICA_CATEGORIA | POST /api/v1/categorias-comerciales/politicas; ConfigurarPoliticaCategoriaHttpExternoTest |
| UC15 evaluarYActualizarCategoriaComercial | EvaluadorCategoriaComercialInterno | Conserva reglas existentes de evaluación | Sin endpoint; regresiones B3 |
| UC16 aplicarAjusteComercialSegunCategoria | AplicarAjusteComercialService | Cálculo interno existente, sin reescribir Pedidos históricos | Sin endpoint; regresiones B3 |

## Decisiones de implementación

| # | Decisión | Justificación |
|---|---|---|
| D1 | POST /api/v1/categorias-comerciales/politicas | El contrato §5 no fija ruta; recurso de nivel superior, consistente con B4 de inventario. |
| D2 | vigenteDesde/vigenteHasta ISO-8601 con offset | Tipos OffsetDateTime de B3; fin opcional y posterior al inicio. |
| D3 | ajusteCumplidor/ajusteEnMora anidados con tipoAjuste y porcentajeAjuste | Representan directamente AjusteCategoria de B3; no crean tablas lógicas nuevas. |
| D4 | ADMINISTRADOR exclusivo | Matriz RBAC; matcher POST combina bandera con autoridad ADMIN, sin hasAnyAuthority. |
| D5 | politicaCerrada no nula en respuesta 201 | Cierre prospectivo de política actualmente vigente es postcondición exitosa de B3. |

## Validación y propagación B3

El controller valida whitelist de los cinco campos de raíz y dos campos por ajuste.
Todos son obligatorios excepto vigenteHasta, omitible o NULL. Dias se interpreta
como entero dentro de Integer, sin conversión desde string/decimal ni truncamiento.
Fechas deben incluir offset. Tipos, claves duplicadas, tokens sobrantes y campos
extra se rechazan como 400. Se conserva BigDecimal exacto, sin pasar por double.

Rango [0,100], escala <=2 de NUMERIC(5,2), SIN_AJUSTE=0, porcentaje positivo para
DESCUENTO/RECARGO y compatibilidad CUMPLIDOR/EN_MORA se delegan a B3 intacto.
B3 prohíbe RECARGO para CUMPLIDOR y DESCUENTO para EN_MORA. Contrato §5, V1 y B3
incluyen el límite 100; el literal residual descuento <100 del DSS no se traslada
como una restricción nueva frente al contrato y al alcance expreso del corte.

| Condición | Respuesta |
|---|---|
| Política y dos ajustes creados; cierre previo permitido | 201, PoliticaCreada de B3 incluyendo politicaCerrada nullable |
| Politica futura solapada | 409 SOLAPE_VIGENCIA |
| No truncar politica futura programada | 409 VIGENCIA_FUTURA_NO_TRUNCADA |
| Retroactividad, días, fechas, cuerpo o ajuste inválidos | 400 SOLICITUD_INVALIDA |
| Token ausente/revocado o cookie sin Bearer, según filtro existente | 401 SESION_INVALIDA |
| Rol distinto de ADMIN o SecurityException de B3 | 403 OPERACION_PROHIBIDA |
| DataAccessException | 503 PERSISTENCIA_NO_DISPONIBLE |

A diferencia de UC19, UC57 no acorta una política futura ya programada. Conserva
el rechazo de B3. Sí acota el extremo futuro de una política actualmente vigente,
sin modificar sus dos ajustes originales ni las condiciones históricas de Pedidos.
No ejecuta SQL de negocio desde el controller. Advice exclusivo de este controller;
éxitos y errores llevan Cache-Control: no-store, sin SQL ni mensajes internos.

## Seguridad y gate no-prod

`ale.http.writes.categorias-uc57-enabled=false` por defecto, controller !prod y
condición de bandera. Matcher POST después de precios, exclusivo ADMIN; veto de
arranque prod si se solicita la bandera. Se usa inyección de parámetro de bean
como el resto de gates del repositorio. No se modifican gates anteriores.

HTTP real prueba bandera desactivada ->403 y ausencia del controller. El test de
precondiciones cubre el veto prod; un contexto de controllers verifica exclusión
del perfil prod incluso con bandera true. No se certifica despliegue productivo.

## UC15 y UC16: servicios internos

No se agregan rutas para reevaluar categorías ni aplicar ajustes comerciales.
La matriz identifica AUTOMATISMO=I y el contrato conserva la invocación interna.
Configurar UC57 no recategoriza Clientes ni recalcula Pedidos existentes.
Las RD-F04 no autorizan crear físicamente pagos en tránsito en este corte.

El test enumera mappings MVC: bajo /api/v1/categorias-comerciales existe solo POST
/politicas y su handler es ConfigurarPoliticaCategoriaHttpController; no hay handlers
con las operaciones internas UC15/16.

El grep amplio pedido encuentra además categoriaComercial en DTOs de respuestas
preexistentes de RegistroClienteHttpController/ModificacionClienteHttpController,
y la ruta en SeguridadHttpInterna como matcher. Son campos de UC02/32 y una regla
de autorización, no endpoints UC15/16. Se conservan intactos esos controllers.

## Pruebas y resultados

PostgreSQL real postgres:17.11-alpine con Flyway. Se reutiliza IngresoReventaFixture
para identidades/sesiones de cinco roles sin modificarla. La política es global:
cada caso limpia únicamente las políticas del contenedor aislado de esta suite.
El fallo del segundo ajuste se fuerza con trigger sintético de test retirado al
finalizar: valida 503 y rollback de cabecera y primer ajuste, sin deshabilitar
constraints ni triggers de las migraciones.

| Suite | Resultado |
|---|---|
| ConfigurarPoliticaCategoriaHttpExternoTest | 10/10 |
| CategoriaPoliticaServiceIntegrationTest | 13/13 |
| PoliticaCategoriaConcurrenciaIntegrationTest | 1/1 |
| EvaluadorCategoriaComercialInternoTest | 5/5 |
| CobroYCategoriaConciliacionIntegrationTest | 1/1 |
| PrecondicionesDespliegueTest | 21/21 |
| AutenticacionHttpExternaTest | 11/11 |
| Total focalizado | 62/62, sin errores ni omisiones |

El árbol actual tiene 20 tests B3 en las cuatro suites (13+1+5+1), frente a los 19
estimados en el encargo. El workflow incluye las cuatro para comprobar el total;
las dos suites nombradas inicialmente en su comando cubren 14 de esos 20.

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=ConfigurarPoliticaCategoriaHttpExternoTest test
mvn -B -ntp -Dtest=CategoriaPoliticaServiceIntegrationTest,PoliticaCategoriaConcurrenciaIntegrationTest,EvaluadorCategoriaComercialInternoTest,CobroYCategoriaConciliacionIntegrationTest test
mvn -B -ntp -Dtest=PrecondicionesDespliegueTest,AutenticacionHttpExternaTest test
```

Compilación local: BUILD SUCCESS. Se usa temporalmente
JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1 en el entorno por incidencias previas del
JDK local; no se incorpora al proyecto ni a CI. El workflow
`i1-05-categorias-http-focused.yml` usa Java21 estándar y publica los reportes en
artifact i1-05-categorias-http. Reportes locales: target/surefire-reports.

## Invariantes

V1/V2/V3, B3 y sus tests, source-lock, gobierno y controllers UC19/20/08/42 intactos.
Se agregan controller/advice y suite UC57; cambios existentes acotados a seguridad,
veto, configuración, test del veto y README. Issue #28 bloquea despliegue.
Pruebas sintéticas no constituyen aceptación humana ni certificación productiva.
