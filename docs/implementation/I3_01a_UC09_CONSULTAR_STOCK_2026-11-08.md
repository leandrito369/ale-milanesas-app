# I3-01a — UC-09 consultarStock: B3 y GET HTTP

Fecha: 08/11/2026. Base `main`: `ebbc398` (acta de cierre técnico de I2).

## Alcance y fuentes

`ConsultarStockService.consultarStock(UUID, CriterioStock)` y
`GET /api/v1/stock` exponen las existencias de INSUMO y REVENTA en listas
separadas. Operación exclusivamente de lectura para RESPONSABLE_PRODUCCION,
sin gate, `@Profile` ni `@ConditionalOnProperty`. No concede acceso implícito
al ADMINISTRADOR. No incorpora UC-10 ni UC-28.

Fuentes cotejadas en `leandrito369/ale-milanesas`:

- `docs/08-diagramas/revision/REV06B_CLIENTES_RECETAS_INVENTARIO/propuestos/CONTRATOS_REV06B_TRECE_CORREGIDOS.md`, §12, firma, A1–A5, salida y postcondición.
- `docs/08-diagramas/revision/REV06B2_RECETAS_INVENTARIO/propuestos/DS_REV06B2_RECETAS_INVENTARIO_UC09_10_31_36_52_64.drawio.xml` y `DSS_REV06B2_RECETAS_INVENTARIO_UC09_10_31_36_52_64.drawio.xml`, rutas del manifiesto G0 para UC-09.
- Ficha Real UC-09 en `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`.
- `docs/04-casos-uso/ADENDA_MR6_IMPACTO_8_UC_2026-11-07.md`, §UC-09: stockUtilizable y partidas activas.
- `docs/01-gobierno/b0/RD_MR_PARTIDAS_001_AMPLIACION_MR_PARTIDA_INSUMO_2026-11-06.md`, D3/D4, autorización MR-6 (`cd603d25addb4312d9bee62ec03514760f7e2894`).
- `docs/01-gobierno/b0/B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv`, fila UC-09.

Se conserva [source-lock](../traceability/source-lock.md), G0 aprobado:
`source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` y
`governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`.
No requiere ni crea una RD nueva.

## Diagnóstico previo

`produccioninventario/` contenía solo ModuleBoundary.java y no existía
`/api/v1/stock`. Las columnas de PARTIDA_INSUMO coinciden con V3. INSUMO
contiene stockActual, umbralMinimo y nivelObjetivoReposicion; PRODUCTO REVENTA
contiene stockActualReventa **nullable** y no tiene umbral. EPISODIO_FALTANTE
contiene fechaHoraCierre, utilizada para indicar si existe uno abierto.
No se modifican V1/V2/V3 ni servicios o controllers existentes.

## Decisiones de implementación

| ID | Decisión | Respaldo / justificación |
|---|---|---|
| D1 | GET `/api/v1/stock` | El contrato no especifica ruta; recurso raíz como otros GET de consulta. |
| D2 | tipoInventario, idElemento y soloBajoUmbral opcionales | Firma REV06B §12. Criterio B3 null equivale a los tres omitidos. |
| D3 | Solo INSUMO tiene filtro por umbral | REVENTA explícito + true → 400 SOLO_BAJO_UMBRAL_NO_APLICA_REVENTA. Tipo omitido + true filtra INSUMO y deja REVENTA sin filtro por umbral, conforme al flujo solicitado. |
| D4 | Apertura ASC NULLS LAST → vencimiento ASC NULLS LAST → registro ASC → ID ASC | MR-6 D4, adenda UC-09 y desempate determinista solicitado. |
| D5 | SUM de cantidades positivas no vencidas | Adenda MR-6 UC-09. La fecha de vencimiento igual a CURRENT_DATE todavía es utilizable. |
| D6 | Cache-Control: no-store | Datos privados, en respuestas 200/400/401/403/503. |

IDs positivos representables como Long; tipo INSUMO/REVENTA; booleano HTTP
estricto true/false. Se rechazan parámetros desconocidos, vacíos, repetidos,
IDs fuera de rango y valores inválidos. Los valores se bindean con tipos JDBC;
las tres sentencias SQL son fijas, sin formateo de valores externos.

## stockUtilizable y partidas por D4

Para cada INSUMO:

```sql
SUM("cantidadDisponible")
WHERE "cantidadDisponible" > 0
  AND ("fechaVencimiento" IS NULL OR "fechaVencimiento" >= CURRENT_DATE)
```

Sin partidas utilizables se devuelve 0. Se usa **SUM**, no el `count(*)` del
pseudocódigo del encargo: contar envases no calcula una cantidad de stock.
La fecha de referencia es CURRENT_DATE de PostgreSQL, no el reloj de la JVM.

Se muestran todas las partidas activas, incluidas las vencidas, con la marca
`vencida = fechaVencimiento < CURRENT_DATE`; una fecha NULL no está vencida.
Las agotadas (`cantidadDisponible=0`) no aparecen en el detalle. El lector no
abre envases, descarta vencidas ni modifica cantidadDisponible. El stock físico
se devuelve tal como está persistido; no se recalcula ni corrige desde partidas.

Dentro de cada insumo el orden es exactamente:

1. fechaHoraApertura ASC NULLS LAST.
2. fechaVencimiento ASC NULLS LAST.
3. fechaHoraRegistro ASC.
4. idPartidaInsumo ASC.

El test D4 inserta siete partidas en orden mezclado y verifica cada criterio,
incluidos empates de apertura, vencimiento, registro y desempate por ID. El test
de utilizable verifica físico 10, utilizable 8, dos unidades vencidas visibles,
una partida con vencimiento de hoy, una sin vencimiento y otra agotada oculta.

El filtro `soloBajoUmbral` usa **stockActual < umbralMinimo**. No cambia PRA-01
para usar stockUtilizable: MR-6 D3 reserva ese cambio a una RD separada. Se prueba
un insumo con físico 10, utilizable 8 y umbral 9: no se incluye en el filtro.
La igualdad al umbral tampoco es inferior. `tieneEpisodioAbierto` es solamente
EXISTS de EPISODIO_FALTANTE con fechaHoraCierre NULL; no abre ni cierra episodios.

## Snapshot, consultas y proyección

`@Transactional(readOnly=true, isolation=REPEATABLE_READ)` mantiene un único
snapshot de datos para autorización y lectura de partidas, resúmenes y REVENTA.
Evita mezclar un stock físico de antes de un ingreso con sus partidas de después.
CURRENT_DATE conserva la referencia de fecha de la transacción. No se toman
advisory locks ni locks de escritura.

Se cargan las partidas por lote y se agrupan por insumo, sin una consulta por
cada fila. Máximo tres consultas de datos (partidas, resumen INSUMO, REVENTA),
además de la autorización. Con un tipo específico se omite el otro inventario.
No hay escrituras de movimientos ni reevaluación de alertas. Los resultados
incluyen listas separadas y no suman existencias de tipos o unidades distintos.

La consulta de REVENTA selecciona solamente idProducto, nombre,
codigoUnidadVenta y stockActualReventa, sin costo promedio ni partidas.
ELABORADO queda excluido. Un 0 persistido se muestra explícitamente;
stockActualReventa NULL se conserva como NULL, sin convertir una existencia
no inicializada en un cero conocido. Este caso y el cero se prueban por separado.
No se agrega un filtro por activo que oculte existencias de elementos inactivos.

## RBAC y HTTP

La sesión se revalida en B3 mediante AutorizacionSesionInterna y únicamente el
rol RESPONSABLE_PRODUCCION. SeguridadHttpInterna usa GET `/api/v1/stock` con
`hasAuthority(ROL_PRODUCCION)`. Se centraliza el literal de ese rol en la misma
configuración y se reutiliza en sus reglas previas, conservando los mismos
valores de autoridad y sin cambiar sus políticas.

Sin Bearer o con cookie sola → 401. ADMINISTRADOR, VENDEDOR, REPARTIDOR y
PROVEEDOR sin rol de Producción → 403. No hay flag ni nuevo veto de producción;
application.yml y PrecondicionesDespliegue quedan intactos. Los demás servicios
y controllers también permanecen intactos.

Respuesta 200: `{ "insumos": [...], "reventa": [...] }`, incluidas listas vacías
si no hay coincidencias. Advice acotado a este controller: 400 SOLICITUD_INVALIDA,
400 SOLO_BAJO_UMBRAL_NO_APLICA_REVENTA, 403 OPERACION_PROHIBIDA y
503 PERSISTENCIA_NO_DISPONIBLE. No se expone el mensaje SQL. Se verifica
Cache-Control: no-store incluso ante error real de persistencia.

## Matriz CU → servicio → DB → API/UI → test

| CU / operación | Servicio | DB | API / UI | Pruebas |
|---|---|---|---|---|
| UC-09 consultarStock | ConsultarStockService | SELECT INSUMO, PRODUCTO, PARTIDA_INSUMO, EPISODIO_FALTANTE; autorización existente | GET /api/v1/stock; sin UI nueva | ConsultarStockServiceIntegrationTest (10), ConsultarStockHttpExternoTest (7) |
| Regresión MR-6 y auth | Sin cambios en servicios/tests existentes | V1/V2/V3 intactas | Seguridad HTTP existente | TrazabilidadPartidasMigrationTest (7), AutenticacionHttpExternaTest (11) |

## Resultados reproducibles

Java 21, Maven, Testcontainers PostgreSQL `postgres:17.11-alpine` real. El fixture
nuevo reutiliza IngresoReventaFixture sin modificarlo. Prepara datos sintéticos
en una transacción confirmada; las llamadas B3 abren sus transacciones de lectura
propias, sin una transacción de test que oculte el aislamiento configurado.
La limpieza de datos se restringe al contenedor dedicado de cada suite.

La prueba de solo lectura compara representaciones canónicas de INSUMO,
PRODUCTO, PARTIDA_INSUMO, EPISODIO_FALTANTE y los tres registros de movimientos
antes/después. La prueba HTTP de 503 renombra temporalmente PARTIDA_INSUMO en
su contenedor y restaura el nombre; no toca migraciones ni una base externa.

| Verificación | Resultado |
|---|---|
| `mvn -B -ntp test-compile` | BUILD SUCCESS |
| ConsultarStockServiceIntegrationTest | 10/10 |
| ConsultarStockHttpExternoTest | 7/7 |
| TrazabilidadPartidasMigrationTest, intacto | 7/7 |
| AutenticacionHttpExternaTest, intacto | 11/11 |
| Total focalizado | 35/35, cero fallos, errores o skips |

Workflow: `.github/workflows/i3-01a-stock-http-focused.yml`.

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=ConsultarStockServiceIntegrationTest,ConsultarStockHttpExternoTest,TrazabilidadPartidasMigrationTest,AutenticacionHttpExternaTest test
```

## Bloqueo y aceptación

[Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) sigue
abierta. PR draft para revisión humana; sin merge ni despliegue. Este corte no
implementa UC-10/UC-28 ni acredita aceptación humana BC-06.
