# B4-04 / UC08 INSUMO — ingreso por compra

Corte exclusivo de `RESPONSABLE_PRODUCCION`. No habilita despliegue (Issue #28),
REVENTA, UC28 ni UC42. Una orden aprobada es obligatoria y debe corresponder al
insumo; unidad recibida idéntica a la base, factor congelado 1. No se implementan
recepciones extraordinarias ni conversiones entre unidades.

## Fuentes y trazabilidad

- G0: `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`; acta G0: `27738b089afdd2698cdae915f75c0e8089a6248a`.
- Operación: `registrarIngresoStockPorCompra`, UC08, REV03B §7:
  `docs/08-diagramas/revision/REV03_COMPRAS/propuestos/CONTRATOS_REV03B_CORREGIDOS.md`.
- DSS y DS: mismo directorio, `DSS_REV03B_CORREGIDO.drawio.xml` y `DS_REV03B_CORREGIDO.drawio.xml`.
- Ficha: `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`, UC08.
- RD-RP022-001, H01/H02, R3-02 y RD-MR-PARTIDAS-001 (`cd603d25addb4312d9bee62ec03514760f7e2894`).
  Fuentes del repositorio documental `leandrito369/ale-milanesas`; G0 no se modifica.
- MR-6: V3 integrada por `ab648c0`; V1/V2/V3 permanecen intactas.

| CU | B3 | Tablas escritas | API | Pruebas |
|---|---|---|---|---|
| UC08 INSUMO | RegistrarIngresoStockInsumoService | RECEPCION_COMPRA, COSTO_INSUMO_HISTORICO, MOVIMIENTO_INSUMO, PARTIDA_INSUMO, DETALLE_MOVIMIENTO_PARTIDA, INSUMO, ORDEN_COMPRA, EPISODIO_FALTANTE | POST /api/v1/compras/recepciones-insumo | RegistrarIngresoStockInsumoServiceIntegrationTest / RegistrarIngresoStockInsumoHttpExternoTest |

## Comportamiento y límites

El servicio revalida sesión y rol en DB y serializa escritores UC08 para proteger
los IDs globales MAX+1, además del lock por insumo y filas de insumo/orden.
Se crea primero la recepción, luego el costo y finalmente el movimiento: la FK
inmediata comprueba H01 y utiliza exactamente la misma fecha con precisión DB.
El reloj se consulta después de adquirir los locks para evitar retroceder la vigencia
ante ingresos concurrentes. `now()` de PostgreSQL es constante dentro de una
transacción; no se utiliza el reloj local Java para construir la FK temporal.

Una partida representa la cantidad total recibida; se inicializa con disponible 0.
El detalle de ingreso activa el trigger V3 y acredita la cantidad, de modo que el
saldo final es igual a cantidadInicial. No hay UPDATE manual de ese saldo.
Se verifica SUM(cantidadAplicada) = cantidad del movimiento antes de confirmar.
Al completar la orden se cierra el episodio si ya cubre el umbral y no quedan
solicitudes u órdenes pendientes, según el contrato §7.

El comprobante es obligatorio y admite hasta 100 caracteres, límite compartido
por las referencias históricas. Cantidades NUMERIC(18,3) y costos NUMERIC(18,4)
se validan antes de persistir, sin redondeo silencioso ni NUL.
La respuesta solo expone tres IDs y el estado de la orden (sin costos ni stock).
El controller aplica una whitelist de seis campos y las respuestas tienen no-store.

## Desarrollo y verificación

Flag `ale.http.writes.compras-uc08-insumo-enabled=false` por defecto;
controller `!prod`; producción rechaza el flag activo. Solo activar el flag
explícitamente en un laboratorio no-prod. La API acepta exclusivamente Bearer.

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=RegistrarIngresoStockInsumoServiceIntegrationTest test
mvn -B -ntp -Dtest=RegistrarIngresoStockInsumoHttpExternoTest test
mvn -B -ntp -Dtest=RegistrarIngresoStockInsumoServiceIntegrationTest,RegistrarIngresoStockInsumoHttpExternoTest,PrecondicionesDespliegueTest,AutenticacionHttpExternaTest test
```

Requiere JDK 21, Maven 3.9+ y Docker (Testcontainers PostgreSQL 17.11-alpine).
Reportes reales en target/surefire-reports. El workflow focalizado conserva esos
reportes incluso ante fallos. Las fixtures son sintéticas; no prueban recepción
humana ni aceptación productiva, y no certifican los escenarios BC06 pendientes.
