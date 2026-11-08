# B4-05 — UC08 REVENTA: ingreso de stock por compra

Corte no-prod exclusivo de `RESPONSABLE_PRODUCCION`. Endpoint apagado por defecto:
`POST /api/v1/compras/recepciones-reventa`. No admite Orden de Compra; requiere
proveedor directo existente y ACTIVO. No crea partidas, detalles de partidas,
costos históricos de insumos ni episodios de faltante.

## Fuentes y trazabilidad

- G0 `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, acta `27738b089afdd2698cdae915f75c0e8089a6248a`.
- En `leandrito369/ale-milanesas`: contrato
  `docs/08-diagramas/revision/REV03_COMPRAS/propuestos/CONTRATOS_REV03B_CORREGIDOS.md` §7,
  DSS/DS `DSS_REV03B_CORREGIDO.drawio.xml` y `DS_REV03B_CORREGIDO.drawio.xml` del mismo directorio;
  ficha UC08 en `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`.
- RD-RP022-001: promedio ponderado y valoración real, sin revaluación retroactiva.
- RD-UC08-002 A-E, merge documental `ad8d39e6351ae71e274747fbf5d8e3c979db2564`:
  inicialización conservadora, secuencia por producto, proveedor obligatorio y precisión.
- RD-UC08-001 D3 (`ee555ba9d020aaf66f1f95e1e4e73d99a9235448`): unidad base y factor 1,
  expresamente extendidos a REVENTA por RD-UC08-002.
- RBAC UC08: solo Producción; permisos de consulta no conceden escritura ni acceso a costos.

| CU | B3 | Tablas escritas | API | Pruebas |
|---|---|---|---|---|
| UC08 REVENTA | RegistrarIngresoStockReventaService | RECEPCION_COMPRA, PRODUCTO, MOVIMIENTO_STOCK_REVENTA | POST /api/v1/compras/recepciones-reventa | RegistrarIngresoStockReventaServiceIntegrationTest / RegistrarIngresoStockReventaHttpExternoTest |

## Flujo, precisión y concurrencia

El servicio autoriza y revalida sesión/rol en DB. Usa el mismo lock global `(808,0)`
que UC08 INSUMO antes del lock por producto y de sus filas, porque comparten
`RECEPCION_COMPRA` y su ID MAX+1. También protege los IDs globales de movimientos
REVENTA y la secuencia MAX+1 filtrada por producto; esta última tiene además UNIQUE.
No altera el código de INSUMO ni los servicios del maestro de Productos.

Stock NULL se trata como cantidad anterior cero para la inicialización autorizada.
Con stock cero, se inicializa el costo al de la compra real; no se inventa costo cero.
Con stock positivo y costo NULL/no positivo se rechaza con 400
`VALUACION_EXTRAORDINARIA_REQUERIDA`, sin efectos.

```text
(stockAnterior * costoAnterior + cantidadNueva * costoCompra) / stockResultante
```

Se utiliza BigDecimal exacto para productos y suma, y solo la división final se
redondea a escala 4 con HALF_UP. Entradas fuera de NUMERIC(18,3)/NUMERIC(18,4),
stock resultante fuera de capacidad y costos no positivos se rechazan sin truncar.
El costo unitario de la respuesta conserva cuatro decimales: no es un importe
monetario final al que aplicar dos decimales.

El controller parsea localmente los números JSON como BigDecimal/BigInteger,
sin pasar por double/float ni cambiar el parser de INSUMO. Rechaza claves
repetidas, tokens sobrantes, campos desconocidos, tipos incorrectos y NUL.
Comprobante obligatorio, máximo 200 caracteres de referenciaComercial.
`idOrdenCompra` presente, incluso NULL, produce `ORDEN_NO_APLICA_REVENTA`.
Proveedor ausente/no positivo produce `PROVEEDOR_DIRECTO_REQUERIDO`.

Se crea la recepción con factor 1 y unidad base, se actualizan stock/promedio del
producto y después se registra su instantánea en el movimiento (H06). Esta
precedencia ajusta el pseudocódigo al contrato §7: H01/vigenteDesdeCosto pertenecen
a INSUMO, no a REVENTA. Todos los efectos son atómicos. La prueba de rollback
comprueba que el producto ya tiene la nueva valoración antes del INSERT del
movimiento y que una falla en ese punto revierte también recepción y producto.
No se modifica la historia de ventas ni movimientos anteriores.

## Gates y validación

`compras-uc08-reventa-enabled=false` por defecto; controller `!prod`; veto de
arranque prod con flag activo. Bearer exclusivamente, sin autenticación por cookie.
Respuestas 201/400/401/403/404/503 con Cache-Control no-store y sin detalles SQL.

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=RegistrarIngresoStockReventaServiceIntegrationTest test
mvn -B -ntp -Dtest=RegistrarIngresoStockReventaHttpExternoTest test
mvn -B -ntp -Dtest=RegistrarIngresoStockReventaServiceIntegrationTest,RegistrarIngresoStockReventaHttpExternoTest,PrecondicionesDespliegueTest,AutenticacionHttpExternaTest test
```

Requiere JDK 21, Maven 3.9+ y Docker; Testcontainers usa postgres:17.11-alpine.
Reportes en target/surefire-reports, también conservados por el workflow focalizado.
La suite B3 contiene once tests (el listado original numeraba once, no diez), con
concurrencia entre productos y contra una recepción INSUMO. La HTTP contiene siete,
con exactitud decimal, gate apagado y exclusión del controller en prod.

No se garantiza deduplicación de reintentos por los locks. Son pruebas sintéticas,
no aceptación humana ni certificación productiva. Issue #28: sin despliegue; PR draft.
V1/V2/V3 y gobierno permanecen intactos.
