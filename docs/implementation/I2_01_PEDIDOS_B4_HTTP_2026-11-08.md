# I2-01 — UC-03 cargarPedido: B3 y HTTP

Fecha de implementación: 08/11/2026. Base: `main` / `51f8ff5` (cierre I1).

## Alcance y fuentes

Aceptación central de `cargarPedido` para VENDEDOR, con Pedido INTERNO/PENDIENTE,
dirección, precios, unidad de venta, categoría y ajuste congelados en la misma
transacción. `reprogramarFechaRepartoPedido` permanece diferida por la brecha de
persistencia documentada en REV04A §3; no se agrega endpoint ni historia nueva.

Se conserva [source-lock](../traceability/source-lock.md): G0 aprobado,
`source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` y
`governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`.
Fuentes consultadas en `leandrito369/ale-milanesas`:

- `docs/08-diagramas/consolidacion-b0/CONTRATOS_REV04A_CORREGIDOS_B0_ACTUALIZADO.md`, §2 (A1–A5, pre/postcondiciones) y §3 (reprogramación).
- `docs/08-diagramas/revision/REV04_PEDIDOS_ENTREGAS/propuestos/DSS_REV04A_CORREGIDO.drawio.xml` y `DS_REV04A_CORREGIDO.drawio.xml`.
- Ficha Real de UC-03 en `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`.
- `docs/01-gobierno/b0/B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv`, UC-03, exclusivo VENDEDOR.
- `RD_RP001_RP019_FECHA_REPARTO_2026-10-27.md`, `RD_RP018_001_PRIMER_PRECIO_PRODUCTO_2026-10-27.md` (precio vigente), `RD_F04_001_ALCANCE_OFFLINE_2026-10-26.md`, `RD_F04_006_EVALUACION_DEUDA_NUEVOS_PEDIDOS_2026-10-26.md`, `RD_F04_007_AJUSTE_EN_MORA_PAGO_TRANSITO_2026-10-26.md`, `RD_RP022_001_COSTEO_CONVERSIONES_REDONDEOS_2026-10-27.md`, `RD_RP023_001_PROMOCIONES_SIMULTANEAS_2026-10-27.md`, bajo `docs/01-gobierno/`.
- `docs/01-gobierno/b0/RD_B0_BC02_2026-10-29.md`: reprogramación autorizada al Vendedor, operación diferida en este corte.
- UC-16 `AplicarAjusteComercialService` y UC-15 `EvaluadorCategoriaComercialInterno` ya integrados (`fd4bd6d`), sin modificarlos.

## Diagnóstico y correspondencia con persistencia

`ventas/` contenía solamente `ModuleBoundary.java`; no había B3 de pedidos.
Existe un precedente de idempotencia en `CobroManualService`; este corte es el
primer uso de `PEDIDO.idOperacionOrigen`, no el primero de toda la aplicación.
REV04A nombra `ItemPedidoDTO` y `DireccionDTO` pero no enumera sus campos. Se
implementan los del encargo, compatibles con V1: producto/cantidad y ocho campos
de dirección. Se validan sus longitudes físicas y se rechaza NUL.

V1 tiene `PEDIDO.totalOriginal NUMERIC(18,2)` y no tiene `subtotal` ni `totalFinal`.
`DETALLE_PEDIDO` requiere cantidades NUMERIC(18,3), precios NUMERIC(18,4), número
positivo y un único producto por pedido. Se rechazan productos repetidos en la
solicitud. Los precios vigentes se seleccionan con intervalo semiabierto y sin
cancelación; el valor se congela, no una referencia recalculable al catálogo.
No se cambian V1/V2/V3, los triggers ni las estructuras F04.

## Decisiones de implementación

| ID | Decisión | Respaldo / justificación |
|---|---|---|
| D1 | `POST /api/v1/pedidos` | Recurso raíz; REV04A no especifica ruta HTTP. |
| D2 | UUID obligatorio; primera aceptación 201, reintento 200 con el Pedido guardado | REV04A A5 y `uq_pedido_0`. La decisión se toma en B3 dentro de la transacción. |
| D3 | Fecha de reparto no retroactiva; hoy es válido | RD-RP001/RP019. Fecha del reloj PostgreSQL en `America/Argentina/Buenos_Aires`, sin depender de la zona de la JVM. |
| D4 | Si UC-15/16 bloquea por deuda, propagar 409 `CLIENTE_CON_DEUDA` | RD-F04-006. Hoy estos servicios clasifican, no bloquean: la deuda vencida produce EN_MORA y el recargo configurado. |
| D4' | `totalOriginal` es el valor post-ajuste devuelto por UC-16 | Aclaración del Responsable en este encargo, UC-16 canónico y RD-RP023-001. Ver sección siguiente. |
| D5 | `MAX+1`, lock global de Pedidos y lock por cliente | Patrón B3; serializa IDs y reconocimiento de UUID aun entre clientes. No se confunde el lock con la deduplicación persistente. |
| D6 | `numeroDetalle` 1, 2, 3… en el orden recibido | PK de DETALLE_PEDIDO. Los productos se bloquean en orden de ID para evitar inversión de locks. |

### D4' — Semántica de `PEDIDO.totalOriginal`

**Decisión:** `PEDIDO.totalOriginal` se persiste con el valor retornado por
`AplicarAjusteComercialService.AjusteAplicado.totalOriginal` (UC-16), que es el
total del Pedido **después** de aplicar el ajuste comercial (descuento o recargo).

**Justificación:** UC-16 ya está en `main` (`fd4bd6d`) y es canónico para la
semántica del campo. La tabla `PEDIDO` de V1 no tiene columna `subtotal` ni
`totalFinal`; solo `totalOriginal`. Persistir el valor de UC-16 directamente evita
duplicar semántica y mantiene consistencia con el resto del modelo.

**Aclaración sobre el nombre:** “`totalOriginal`” significa “el total del Pedido
frozen al momento de la creación” (ya ajustado), no “el subtotal antes del ajuste”.
La relación algebraica con la base pre-ajuste es
`totalOriginal / (1 ± porcentajeAjuste/100)`, usando `tipoAjuste` guardado en la
misma fila. El test del 5% verifica el caso exacto `475.00 / 0.95 = 500.00`.
Esta inversión se usa solamente en ese test: no se ejecuta en el servicio.
El redondeo monetario puede impedir recuperar exactamente la base en otros
casos; un descuento del 100% no admite división. Las cantidades y precios
congelados de los detalles conservan los datos para reconstruir la base.

**No requiere RD nueva:** la semántica está establecida por UC-16 +
`RD-RP023-001`, según aclaración del Responsable. No se crea RD-UC03-001 ni V4.

Los importes se calculan con BigDecimal. Se suman los productos exactos de
cantidad por precio y se redondea la base monetaria a dos decimales HALF_UP,
como hace UC-16 antes de calcular el ajuste. UC-16 entrega política, categoría,
tipo, porcentaje e importe final; no se invierte su fórmula ni se reimplementa.
UC-16 incluye UC-15, por lo que no se invoca UC-15 una segunda vez.

## Idempotencia por `idOperacionOrigen`

La transacción toma un lock global de dos enteros `(3003,0)` para asignar IDs y
reconocer claves en forma serial, más `pg_advisory_xact_lock(idCliente)` y el
lock de fila del Cliente. Se revalida la sesión después de esperar los locks.
`uq_pedido_0` conserva la unicidad persistente como defensa adicional.

La clave identifica una operación ya aceptada: los reintentos retornan sus datos
persistidos y no recalculan precios, deuda, ajuste ni fecha. Cambios posteriores
del catálogo o del estado del cliente no impiden reconocerla. La solicitud aún
debe tener una estructura válida. Se rechaza reutilizar la clave con otro
cliente o canal; no se reasocia un Pedido a los nuevos datos recibidos.

El record B3 agrega la marca técnica `creado` para decidir 201/200 de manera
atómica. B4 proyecta exclusivamente los siete campos del JSON del encargo; la
marca no se expone ni se consulta antes de la transacción.

Dos pruebas con transacciones independientes verifican que solicitudes
simultáneas con la misma clave crean un solo Pedido y un solo juego de detalles,
y que solicitudes de clientes distintos obtienen IDs sin colisión.

## HTTP, seguridad y errores

Exclusivo VENDEDOR en B4 y B3. ADMINISTRADOR no hereda el permiso. Solo Bearer;
una cookie no autentica la API. Whitelists de cinco campos superiores, dos por
ítem y ocho de dirección, tipos estrictos, rechazo de claves JSON duplicadas,
contenido adicional, fechas/UUID inválidos y precisión fuera de NUMERIC(18,3).
No se aceptan del cliente precios, política, categoría, ajuste, creador ni estado.

Triple gate: `pedidos-uc03-enabled=false`, controller `@Profile("!prod")` más
`@ConditionalOnProperty`, y veto al iniciar prod si el flag está activo.
El gate apagado devuelve 403 a una sesión válida; sin Bearer se devuelve 401.
`Cache-Control: no-store` se verifica para 201, 200, 400, 401, 403 y 503.
Los errores de persistencia no exponen SQL. La ausencia de política/ajuste se
traduce a solicitud inválida (A4), sin porcentajes por defecto.

## Deuda, offline y límites

UC-15/16 usa actualmente deuda **confirmada**. RD-F04-006 contempla deuda
provisional pero no define un umbral que autorice inventar un bloqueo. Este
corte delega en B3 existente: cliente con deuda vencida → EN_MORA y recargo;
si esos servicios emiten una excepción de deuda, B4 devuelve 409. No se crean
estructuras de pago en tránsito ni inbox. RD-F04-007 mantiene EN_MORA hasta
reevaluación formal; no se presume pago confirmado por una declaración.

RD-F04-001 permite captura local offline y exige aceptación central validada;
este corte implementa el servidor, no la captura/sincronización de un cliente
offline. El UUID no incorpora un nuevo protocolo físico F04. Los detalles de
este corte, según el flujo del encargo, llevan promoción 0/NULL: no se implementa
un motor de promociones, cupos ni elegibilidad UC-24/59/60.
No hay movimientos de stock, preparación, entrega, cobro ni caja al cargar.

## Matriz CU → servicio → DB → API → test

| CU / operación | B3 | DB | API / UI | Pruebas |
|---|---|---|---|---|
| UC-03 cargarPedido | CargarPedidoService → UC-16 → UC-15 | INSERT PEDIDO/DETALLE_PEDIDO; evaluación CLIENTE; lectura CLIENTE/PRODUCTO/PRECIO_PRODUCTO/POLITICA_CATEGORIA_COMERCIAL/AJUSTE_POLITICA_CATEGORIA y cuenta confirmada | POST /api/v1/pedidos; sin UI nueva | CargarPedidoServiceIntegrationTest (18), CargarPedidoConcurrenciaIntegrationTest (2), CargarPedidoHttpExternoTest (9) |
| Gate prod y RBAC | AutorizacionSesionInterna + SeguridadHttpInterna + PrecondicionesDespliegue | SESION_USUARIO/USUARIO/USUARIO_ROL | Bearer VENDEDOR, gate independiente | PrecondicionesDespliegueTest, AutenticacionHttpExternaTest, pruebas HTTP de UC-03 |

## Resultados reproducibles

PostgreSQL real `postgres:17.11-alpine` con Testcontainers, Java 21 y Maven.
Aislamiento transaccional en B3; la prueba de rollback usa transacción real y un
trigger exclusivamente de prueba para fallar el segundo detalle, y comprueba
que tampoco se conserva el cambio de categoría hecho por UC-15. Los tests de
concurrencia y HTTP usan transacciones confirmadas independientes.

| Verificación | Resultado |
|---|---|
| `mvn -B -ntp test-compile` | BUILD SUCCESS |
| B3 CargarPedidoServiceIntegrationTest | 18/18; incluye los dos tests D4' |
| B3 CargarPedidoConcurrenciaIntegrationTest | 2/2 |
| B4 CargarPedidoHttpExternoTest | 9/9; incluye no-store en 503 y EN_MORA sin bloqueo |
| Categorías / política / conciliación (4 suites protegidas) | 20/20, sin cambios en código ni tests protegidos |
| Precios B3 (2 suites protegidas) | 12/12, sin cambios |
| Gates / autenticación | 25/25 + 11/11 |
| Total focalizado | 97/97, cero fallos, errores o skips |

El workflow `i2-01-pedidos-http-focused.yml` ejecuta los tres suites de UC-03,
los cuatro de categoría/conciliación, los dos de precios y auth/gates. Comando:

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=CargarPedidoServiceIntegrationTest,CargarPedidoConcurrenciaIntegrationTest,CargarPedidoHttpExternoTest,CategoriaPoliticaServiceIntegrationTest,PoliticaCategoriaConcurrenciaIntegrationTest,EvaluadorCategoriaComercialInternoTest,CobroYCategoriaConciliacionIntegrationTest,PreciosProductoServiceIntegrationTest,PrecioProductoConcurrenciaIntegrationTest,PrecondicionesDespliegueTest,AutenticacionHttpExternaTest test
```

## Bloqueo y aceptación

[Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) sigue
abierta. Entrega mediante PR draft, sin merge, despliegue ni habilitación del
gate. Estas pruebas técnicas no acreditan los escenarios humanos BC-06 ni
cambian su estado de aceptación.
