# I3-01c — UC-28 registrarPreparacionPedido y búsqueda contextual de Producción

Fecha de implementación: 08/11/2026. Base `main`: `df9b2e2` (merge PR #81, UC-10).

## Alcance y fuentes

B3 `RegistrarPreparacionService.registrarPreparacionPedido(UUID, DatosPreparacion)`,
B4 `POST /api/v1/pedidos/{idPedido}/preparaciones` y extensión de
`BuscarPedidoService` (UC-34) para RESPONSABLE_PRODUCCION contextual.
Solo Producción puede confirmar preparaciones. No implementa Entrega, compras,
excedentes ni nuevas tablas. Preserva V1/V2/V3, los servicios protegidos y sus
pruebas, y todos los controllers existentes.

G0 permanece aprobado según [source-lock](../traceability/source-lock.md):
`source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`,
`governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`.
Fuentes exactas cotejadas en `leandrito369/ale-milanesas`:

- `docs/08-diagramas/consolidacion-b0/CONTRATOS_REV04B_CORREGIDOS_B0_ACTUALIZADO.md`, §1, firma de tres parámetros, A1–A4, cantidad propia original y costos históricos.
- `docs/08-diagramas/revision/REV04_PEDIDOS_ENTREGAS/propuestos/DS_REV04B_CORREGIDO.drawio.xml` y `DSS_REV04B_CORREGIDO.drawio.xml`, mensajes UC-28 del manifiesto G0.
- `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`, ficha Real UC-28.
- `docs/04-casos-uso/ADENDA_MR6_IMPACTO_8_UC_2026-11-07.md`, §UC-28, D2/D4, stock utilizable y desglose de consumos.
- `docs/01-gobierno/b0/RD_MR_PARTIDAS_001_AMPLIACION_MR_PARTIDA_INSUMO_2026-11-06.md`, D1–D4 (`cd603d25addb4312d9bee62ec03514760f7e2894`).
- `docs/01-gobierno/b0/RD_I301_001_DECISIONES_I3_01_RECETAS_PREPARACION_2026-11-11.md`, §§3–8, G1/G2/G4/G5/G6 y contexto UC-34 (`1f6a4c7`).
- `docs/01-gobierno/b0/B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv`, UC-28; `BC07_PROYECCIONES_MINIMAS_RATIFICADAS_2026-10-29.csv`, UC-34 PRODUCCION.
- `docs/01-gobierno/RD_RP022_001_COSTEO_CONVERSIONES_REDONDEOS_2026-10-27.md`, decimal exacto y unidades base.
- `docs/01-gobierno/b0/RD_UC42_001_DECISIONES_PENDIENTES_AJUSTE_INVENTARIO_2026-11-07.md`, G4, precedente de idempotencia pendiente.

No se modifica el repo de gobierno ni source-lock ni se crea una RD nueva.

## Diagnóstico previo

PREPARACION referencia el detalle y la versión de receta; cantidades NUMERIC(18,3).
DETALLE_PEDIDO tiene UNIQUE(idPedido,idProducto), por lo que la firma canónica
identifica un único numeroDetalle. `uq_mov_insumo_preparacion` limita un consumo
por pareja preparación/insumo. V3 mantiene cantidadDisponible por trigger y no
abre envases. EpisodioFaltanteService expone evaluarApertura(insumo,stock,umbral,
movimiento); se invoca sin modificarlo. UC-42 toma (808,0), luego advisory por
insumo y fila INSUMO: la coordinación se prueba con dos hilos reales.

BuscarPedidoService ya tenía proyecciones selladas por rol y una única sentencia
con actor/ámbito/filtros. Se amplía esa estructura, preservando la precedencia de
VENDEDOR/ADMINISTRADOR y REPARTIDOR. La suite B3 contenía 15 tests, no los 8 del
encargo: quedan 17 con los dos nuevos. Se actualizan exclusivamente las antiguas
expectativas de denegación total a PRODUCCION, reemplazadas por RD-I301-001 §8.
La suite HTTP de búsqueda conserva sus diez pruebas y verifica el contexto nuevo.

## Decisiones de implementación

| ID | Decisión | Respaldo |
|---|---|---|
| D1 | POST /api/v1/pedidos/{idPedido}/preparaciones | Recurso anidado; confirmación crea otra preparación. Contrato sin ruta HTTP. |
| D2 | Body idProducto, cantidadPreparada, partidas opcional | Firma G0 y extensión técnica G1. ID de pedido únicamente en path. |
| D3 | Override cubre exactamente todos los insumos; SUM por insumo = consumo calculado | RD-I301-001 §3; IDs de partida no repetidos. |
| D4 | Selección automática de partidas positivas no vencidas por apertura, vencimiento, registro, ID | MR-6 D4. NULLS LAST en apertura/vencimiento. |
| D5 | Apertura implícita y marcarApertura opcional | GREATEST(fechaHoraPreparacion,fechaHoraRegistro) solo si apertura es NULL; preserva siempre la primera apertura. |
| D6 | cantidadNecesaria × cantidadPreparada ÷ rendimiento; HALF_UP escala 3 | G2, NUMERIC(18,3); cero redondeado rechazado. |
| D7 | SUM de cantidades disponibles no vencidas >= consumo | G2 y adenda UC-28; el físico suficiente no habilita partidas vencidas. |
| D8 | CONSUMO_PRODUCCION, origen PRODUCCION, idPreparacion no NULL | G4. Recepción/referencia/motivo/observación NULL. Actor interno en idUsuarioResponsable. |
| D9 | Reutilizar evaluarApertura tras cada movimiento | G5. Sin SQL de episodios duplicado ni SOLICITUD_COTIZACION. |
| D10 | PEDIDO → DETALLE_PEDIDO → (808,0) → insumos ASC con advisory/fila/partidas | G6, con locks adicionales de producto/receta después de (808,0). |
| D11 | Idempotencia de aplicación diferida | G6 / RD-UC42-001 G4. Solo límite de cantidad propia acumulada. |
| D12 | PRODUCCION solo pedidos PENDIENTE con ELABORADO propio pendiente | RD-I301-001 §8; proyección de cinco campos sin costos ni ajustes. |
| D13 | Un clock_timestamp de BD para preparación/movimientos/apertura | Referencia común; apertura usa GREATEST para respetar el registro del envase. |

## G1 — Override manual y apertura implícita

Omitir partidas, enviarla null o enviar `partidas: []` activa selección automática
D4: alineado con RD-I301-001 §3, que dice literalmente «o es nulo/vacío».
La lista vacía se normaliza a null antes de validar cobertura y planificar consumos.

### `marcarApertura` explícito (D2)

Cada partida admite cuatro campos: idInsumo, idPartidaInsumo, cantidadAplicada y
el Boolean opcional marcarApertura. Se rechazan tipos distintos de boolean/null.
Con true/false/null/omitido solo se abre si fechaHoraApertura es NULL:
GREATEST(fechaHoraPreparacion,fechaHoraRegistro). Una apertura previa siempre se
preserva, equivalente a COALESCE según RD-I301-001 §3. false no impide la apertura
implícita de una partida consumida cerrada. El constructor de tres argumentos
sigue disponible y equivale a marcarApertura=null.

El override valida IDs/cantidades positivos, precisión, ausencia de duplicados,
pertenencia al insumo, disponibilidad, vencimiento y cobertura exacta de la receta.
No permite insumos adicionales ni omitir uno. Selección y suma usan **las mismas
filas ya bloqueadas** FOR UPDATE, ordenadas por D4: se evita separar un SUM sin
lock de una selección posterior. El override puede elegir otro envase utilizable,
pero no eludir las comprobaciones. No se consume de partidas agotadas o vencidas.
El vencimiento de hoy aún es válido según CURRENT_DATE de PostgreSQL.

Por cada insumo se inserta un movimiento y su desglose; la suma de detalles es
exactamente la cantidad del movimiento. **Solo V3** descuenta cantidadDisponible.
El servicio modifica exclusivamente fechaHoraApertura de las partidas consumidas
con GREATEST según D2; conserva siempre aperturas anteriores.
Nunca descuenta dos veces.

## G2 — Fórmula BigDecimal y unidades

Se multiplica primero y luego divide con escala 3 y RoundingMode.HALF_UP:

```java
cantidadNecesaria.multiply(cantidadPreparada)
    .divide(cantidadRendimientoBase, 3, RoundingMode.HALF_UP);
```

Pruebas: 0.200 × 5.000 / 1.000 = 1.000; 0.500 × 5 / 1 = 2.500;
0.001 × 1 / 2 = 0.0005 → 0.001. Un consumo que redondee a 0.000 se rechaza.
El código HTTP pedido es CANTIDAD_CONSUMIDA_INSUFICIENTE (400), alias técnico del
CANTIDAD_INSUFICIENTE mencionado en la RD; la regla de rechazo es la misma.

Rendimiento debe coincidir con PRODUCTO.codigoUnidadVenta y los detalles con
INSUMO.codigoUnidadMedida. No se convierten unidades. Las cantidades de entrada
son BigDecimal positivas con escala <= 3 y hasta 15 dígitos enteros, sin truncar
precisión excedida. El consumo calculado también debe caber en NUMERIC(18,3).

Se congela la clave vigenteDesdeCosto de la fuente histórica aplicable al único
timestamp de preparación, nunca un costo cero. Sin receta, detalles de insumos,
insumo activo, unidad compatible, stock utilizable o fuente de costo válida, se
rechaza toda la operación. No se crean vigencias de costo ni se modifican recetas.

## G6 — Orden de locks y transacción

1. PEDIDO FOR UPDATE, después DETALLE_PEDIDO FOR UPDATE.
2. Advisory transaccional (808,0), compartido con UC-08/UC-42/UC-10.
3. Producto y receta aplicable FOR SHARE, después de (808,0), compatible con UC-10.
4. Por cada insumo en ID ASC: advisory(idInsumo), INSUMO FOR UPDATE, partidas
   utilizables FOR UPDATE por D4. Se conservan todos los locks hasta commit.

Se revalida la autorización tras esperar locks. El bloqueo del pedido protege
SUM de PREPARACION por (idPedido,numeroDetalle) + cantidad nueva <= cantidadOriginal.
No se restan adicionales ni se vuelven a consumir excedentes de UC-07.

Todos los consumos y fuentes de costo se validan antes de escribir. Se crea la
PREPARACION y, para cada insumo, se actualiza stockActual **antes** del INSERT del
movimiento, tal como la nota transaccional final del encargo y UC-42. Esto hace
fallar temprano ck_insumo_0 en datos físicos inconsistentes. Luego se insertan
movimiento/detalles, se abre si corresponde y se invoca evaluarApertura. Cualquier
fallo revierte preparación, stocks, movimientos, detalles, aperturas y episodios.

Se prueban dos preparaciones concurrentes de 7 para un original de 10: una pasa,
la otra falla por el límite, sin deadlock. Preparación simultánea con ajuste
negativo UC-42: ambos consumos se conservan, stock y partidas quedan conciliados,
con secuencias distintas. Los IDs MAX+1 de preparación, movimientos y episodios
quedan serializados por (808,0).

## Idempotencia diferida (D11)

No hay clave de operación, deduplicación ni respuesta que afirme idempotencia.
RD-UC28-002 queda para un ciclo futuro; no se crea en este corte.
Un reintento parcial puede registrar otra preparación y consumir nuevamente si
la suma aún cabe dentro del original. El lock serializa y el límite impide
sobrepreparar; **no deduplican** una operación. El test de límite también registra
un segundo 4 sobre original 10 y verifica dos preparaciones, acumulado 8.
Riesgo residual: double-submit/red puede duplicar un parcial. Confirmación en UI
es mitigación futura, fuera del PR; queda trazabilidad en PREPARACION y movimientos.

## Actualización de BuscarPedidoService para PRODUCCION (D12)

Se agrega PedidoProduccion con **exactamente** idPedido, idCliente, fechaReparto,
estado y canalOrigen. Sin totalOriginal, categoría, costos o datos personales.
El rol de Producción puro se limita mediante EXISTS a:

- PEDIDO.estado=PENDIENTE.
- Algún detalle de PRODUCTO.tipoProducto=ELABORADO.
- cantidadOriginal > SUM(cantidadPreparada) del detalle (cero si no hay preparación).

Sin coincidencias en una búsqueda general/filtrada → 200 []. Un idPedido explícito
fuera de contexto, incluido inexistente, → 403 FUERA_DE_CONTEXTO. Un ID elegible
con otros filtros contradictorios → 200 [], sin confundir filtros con ámbito.
Tras completar todos los detalles elaborados, el pedido deja de ser visible.

Se mantienen ámbito y proyección de ADMIN/VENDEDOR y de REPARTIDOR. Los roles
combinados conservan la precedencia previa: VENDEDOR/ADMIN tienen permiso directo;
REPARTIDOR sin ellos mantiene sus asignaciones, aunque también tenga Producción.
El actor, los roles, el ámbito y los filtros se resuelven en una sentencia.

No se edita BuscarPedidoHttpController. Un advice **nuevo**, de máxima precedencia
y acotado a ese controller, captura solo FueraDeContextoException. Los demás
errores mantienen sus handlers existentes. El matcher GET admite Producción sin
gate; funciona aun con la escritura UC-28 desactivada. Tests HTTP comprueban
proyección, 403 específico, listas vacías y no-store. Cambiar roles revoca sesiones
por trigger existente: la prueba de roles combinados abre nuevas sesiones.

## HTTP, gate y errores

Whitelist de tres campos de body y cuatro de partida, números decimales exactos,
IDs Long enteros y rechazo de campos desconocidos, claves duplicadas, tokens
JSON extra y tipos inválidos. 201 devuelve los siete campos de PreparacionRegistrada.

Errores: 404 PEDIDO_NO_ENCONTRADO; 409 PEDIDO_NO_PENDIENTE,
STOCK_UTILIZABLE_INSUFICIENTE o COSTO_HISTORICO_REQUERIDO; 400 para
PRODUCTO_NO_EN_PEDIDO, CANTIDAD_EXCEDE_ORIGINAL, SIN_RECETA_VIGENTE,
RECETA_SIN_INSUMOS, INSUMO_INACTIVO, UNIDAD_NO_COINCIDE,
CANTIDAD_CONSUMIDA_INSUFICIENTE o SOLICITUD_INVALIDA; 403 OPERACION_PROHIBIDA;
503 PERSISTENCIA_NO_DISPONIBLE sin texto SQL. Sin Bearer, cookie sola o sesión
revocada → 401. Todas las respuestas usan Cache-Control: no-store.

Exceso de cantidad usa 400 según el advice solicitado, aunque el nombre propuesto
del test HTTP decía 409. Pedido no PENDIENTE usa 409 según el advice, aunque el
flujo B3 anotaba 400. Se documentan ambos ajustes de representación HTTP.

Triple gate no-prod: preparaciones-uc28-enabled=false por defecto, controller con
Profile(!prod) y ConditionalOnProperty, más veto en PrecondicionesDespliegue.
POST exclusivo RESPONSABLE_PRODUCCION; ADMIN/VENDEDOR/REPARTIDOR/PROVEEDOR → 403.
No se amplían permisos de escritura de otros endpoints.

## Matriz CU → servicio → DB → API/UI → test

| CU | Servicio | DB | API/UI | Tests |
|---|---|---|---|---|
| UC-28 | RegistrarPreparacionService | PREPARACION, INSUMO, MOVIMIENTO_INSUMO, DETALLE_MOVIMIENTO_PARTIDA, apertura en PARTIDA_INSUMO; EpisodioFaltanteService para episodio | POST /api/v1/pedidos/{idPedido}/preparaciones; sin UI nueva | B3 16, concurrencia 2, HTTP 9 |
| UC-34 contextual | BuscarPedidoService | SELECT PEDIDO, DETALLE_PEDIDO, PRODUCTO, PREPARACION y sesión/roles | GET /api/v1/pedidos existente, sin editar controller | B3 17 (15 previos + 2 nuevos), HTTP 10 |
| Regresiones protegidas | Servicios existentes intactos | Sin cambios de esquema | UC-09/UC-10/I2/UC-42/auth/gates | Suites focalizadas del workflow |

## Resultados reproducibles

Java 21, Maven, Testcontainers PostgreSQL postgres:17.11-alpine real, sin skips.
Fixture nuevo reutiliza PedidoFixture y CargarPedidoService sin modificarlos.
Datos confirmados: cada llamada B3 abre su transacción, también en los dos hilos.

Rollback real: trigger sintético falla al insertar el segundo movimiento, después
de que el primero modificó stock, partida, apertura y episodio. Snapshot canónico
antes/después comprueba reversión íntegra. También se prueba costo ausente y stock
físico inconsistente que dispara ck_insumo_0. Trigger sintético y renombrado temporal
de tabla para 503 HTTP se restringen al contenedor de test y se restauran.

| Verificación | Resultado |
|---|---|
| mvn -B -ntp test-compile | BUILD SUCCESS |
| RegistrarPreparacionServiceIntegrationTest | 16/16 |
| RegistrarPreparacionConcurrenciaIntegrationTest | 2/2 |
| RegistrarPreparacionHttpExternoTest | 9/9 |
| BuscarPedidoServiceIntegrationTest | 17/17 (15 previos + 2 nuevos) |
| BuscarPedidoHttpExternoTest | 10/10 |
| UC-10 / UC-09 / UC-03 / UC-43 / UC-42 | 13/13, 10/10, 18/18, 16/16, 14/14; intactos |
| Autenticación y precondiciones | 11/11 y 26/26; intactos |
| Total focalizado | 162/162; cero fallos, errores o skips |

Workflow: `.github/workflows/i3-01c-preparaciones-http-focused.yml`.

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=RegistrarPreparacionServiceIntegrationTest,RegistrarPreparacionConcurrenciaIntegrationTest,RegistrarPreparacionHttpExternoTest,ConfigurarRecetaServiceIntegrationTest,ConsultarStockServiceIntegrationTest,BuscarPedidoServiceIntegrationTest,BuscarPedidoHttpExternoTest,CargarPedidoServiceIntegrationTest,AsignarRepartoServiceIntegrationTest,RegistrarAjusteInventarioServiceIntegrationTest,AutenticacionHttpExternaTest,PrecondicionesDespliegueTest test
```

## Bloqueo y aceptación

[Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) permanece
abierta. PR draft para revisión humana, sin merge ni despliegue. No acredita
aceptación humana BC-06. Idempotencia de aplicación pendiente para RD-UC28-002.

## Fix UC-28 — 09/11/2026

Base comprobada: `20ce82d`; RD-I301-001: `1f6a4c7`.
Diagnóstico: PartidaAplicada tenía tres componentes; validar rechazaba isEmpty()
con SOLICITUD_INVALIDA. La RD §3 menciona literalmente «nulo/vacío».
Se mantienen V1/V2/V3 y los demás servicios/controladores sin cambios.
La sección de idempotencia diferida anterior continúa vigente.

Validación real con Temurin JDK 21.0.9, Maven 3.9.11 y PostgreSQL 17.11-alpine
mediante Testcontainers, sin omisiones:

| Verificación del fix | Resultado |
|---|---|
| `mvn -B -ntp test-compile` | BUILD SUCCESS |
| RegistrarPreparacionServiceIntegrationTest | 21/21 (16 existentes con expectativa de [] corregida + 5 nuevos) |
| RegistrarPreparacionHttpExternoTest | 11/11 (9 existentes ajustados + 2 nuevos) |
| RegistrarPreparacionConcurrenciaIntegrationTest | 2/2, sin modificaciones |
| Total focalizado del fix | 34/34, cero fallos/errores/skips; BUILD SUCCESS |

Verdes: partidasVacioActivaSeleccionAutomatica,
marcarAperturaTrueFuerzaApertura, marcarAperturaNullUsaAperturaImplicita,
marcarAperturaFalseConservaAperturaImplicita y
marcarAperturaTrueRespetaFechaRegistroFutura (actualizado en la corrección de PR #83).
HTTP verifica [] → 201 y acepta true/false/null; el test previo sin el campo
sigue pasando. No se reejecutaron las demás suites de la tabla histórica.

Comandos del fix:

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=RegistrarPreparacionServiceIntegrationTest,RegistrarPreparacionConcurrenciaIntegrationTest,RegistrarPreparacionHttpExternoTest test
```

En el entorno cloud se usó un settings Maven local fuera del checkout para
el proxy, un repositorio Maven en /tmp y el JDK completo descargado con checksum
verificado. No se alteraron pom.xml ni dependencias para resolver el entorno.

### Corrección de PR #83 — preservar primera apertura

El WHERE exige siempre fechaHoraApertura IS NULL, incluso con marcarApertura=true,
por lo que se preserva T1 en partidas ya abiertas (RD-I301-001 §3, COALESCE).
Se reemplaza el test que esperaba sobrescribir por un caso de partida cerrada
con registro futuro, y se agrega marcarAperturaTrueNoSobrescribeAperturaPrevia.
Validación de esta corrección: test-compile BUILD SUCCESS y suite
RegistrarPreparacionServiceIntegrationTest 22/22, cero fallos/errores/skips,
BUILD SUCCESS. Las suites HTTP y concurrencia no se reejecutaron en este ajuste.
