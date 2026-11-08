# B4-06 — UC42 INSUMO: registrar ajuste de inventario

Corte exclusivo de RESPONSABLE_PRODUCCION para INSUMO: SALDO_INICIAL,
AJUSTE_POSITIVO y AJUSTE_NEGATIVO. Cantidades en la unidad base del insumo.
Endpoint `POST /api/v1/inventario/ajustes`, apagado por defecto mediante
`ale.http.writes.compras-uc42-enabled=false`, controller excluido en prod y veto
de arranque prod si se habilita la bandera. No admite REVENTA ni conversiones.

## Fuentes y decisión aplicada

G0 `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`,
`governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`, inmutables.
Contrato de gobierno `docs/08-diagramas/consolidacion-b0/CONTRATO_UC42_REV01_B0_ACTUALIZADO.md`;
DSS/DS en `docs/08-diagramas/revision/REV01_UC42/propuestos/`:
`DSS_PRODUCCION_UC42_REV01.drawio.xml` / `DS_UC42_REV01.drawio.xml`.
Ficha Real: `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`.
La etiqueta histórica Admin del DS no concede permisos frente a RBAC ratificado.

RD complementaria [RD-UC42-001](https://github.com/leandrito369/ale-milanesas/blob/d7762f64a2914d983e3f77bb69a999da15fcb592/docs/01-gobierno/b0/RD_UC42_001_DECISIONES_PENDIENTES_AJUSTE_INVENTARIO_2026-11-07.md),
merge `d7762f64a2914d983e3f77bb69a999da15fcb592`: G1/G2/G3/G5 cerrados;
idempotencia de G4 PENDIENTE. Prevalece sobre la firma propuesta anterior.
MR-6: RD-MR-PARTIDAS-001, `cd603d25addb4312d9bee62ec03514760f7e2894`.

| Decisión | Respaldo |
|---|---|
| Firma restringida más lista obligatoria de partidas | RD-UC42-001 G1/G2 y contrato UC42 |
| Solo RESPONSABLE_PRODUCCION | B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv, UC42 |
| SALDO_INICIAL con costo positivo; sin movimientos anteriores, secuencia 1, origen APERTURA_INVENTARIO | RD-UC42-001 G1/G3; V1 F03 e índice parcial |
| Ajuste positivo con costo crea vigencia; sin costo usa vigente | RD-UC42-001 G3, RD-RP022-001 |
| Ajuste negativo ignora costo recibido y conserva historia | RD-UC42-001 G3 |
| Suma exacta, misma identidad de insumo, disponibilidad, descarte de vencidas y apertura explícita | RD-UC42-001 G2, MR-6 D1-D3 |
| Partida nueva con disponible 0; trigger acredita/reduce al insertar detalles | V3 y RD-UC42-001 G2 |
| Unidad base sin campos de recepción/factor | RD-UC42-001 G5 |
| Serialización, sin deduplicación general | MR-6 D2 y RD-UC42-001 G4 |
| Cierre condicionado y máximo un episodio abierto | PRA-R02/R10 y coordinación G4 |

| CU | B3 | Tablas escritas | API | Pruebas |
|---|---|---|---|---|
| UC42 INSUMO | RegistrarAjusteInventarioService + EpisodioFaltanteService | COSTO_INSUMO_HISTORICO, MOVIMIENTO_INSUMO, PARTIDA_INSUMO, DETALLE_MOVIMIENTO_PARTIDA, INSUMO, EPISODIO_FALTANTE | POST /api/v1/inventario/ajustes | RegistrarAjusteInventarioServiceIntegrationTest / RegistrarAjusteInventarioHttpExternoTest / EpisodioFaltanteServiceIntegrationTest |

## Transacción, trigger y coordinación

Se autoriza y revalida sesión/rol en DB, se toma el lock global `(808,0)` común
con UC08 para los IDs MAX+1, luego el lock por insumo y el bloqueo de sus filas.
El actor persistido es el idUsuario BIGINT de SESION_USUARIO; no su UUID de sesión.
Las partidas negativas se bloquean y deben pertenecer al mismo insumo; se rechaza
repetir una identidad en la lista para evitar descuentos ambiguos por duplicación.
No se selecciona por lote ni se impone FEFO de UC28.

Costo nuevo antes del movimiento (H01), con un único timestamp DB posterior al
lock y superior a vigencias anteriores, usado exactamente en la FK temporal.
Origen ordinario: `AJUSTE`; inicial: `APERTURA_INVENTARIO`.
Los detalles concilian cantidad total y cantidad por partida. No hay UPDATE Java
a cantidadDisponible: las partidas nuevas nacen en cero y V3 las acredita.
Solo `marcarApertura=true` registra apertura, sin reescribir una fecha previa.

Ajuste del orden del pseudocódigo: el UPDATE de stock físico ocurre después del
costo y antes del movimiento dentro de la misma transacción, para que un saldo
físico negativo sea rechazado específicamente por ck_insumo_0, antes del CHECK
de stockResultante de MOVIMIENTO_INSUMO. Cualquier fallo revierte todos los efectos.

La extracción de UC08 conserva literalmente su SQL y timestamp de cierre.
El overload interno de cierre recibe ese timestamp para preservar el comportamiento;
la evaluación nueva admite stock/umbral. La apertura recibe además idMovimientoInsumo
para registrar idMovimientoApertura, ausente en la firma de tres argumentos del
pseudocódigo. No crea otro episodio si ya hay uno abierto.
Cierre: stock físico >= umbral y ninguna solicitud en curso u orden pendiente.
No emite rondas automáticas ni órdenes; no declara cumplida toda PRA-01.

El controller conserva números como BigDecimal/BigInteger, valida whitelist de
raíz y partidas, tipos, fechas ISO, NUL, claves repetidas y tokens sobrantes.
Rechaza precisión excedida sin truncar: NUMERIC(18,3) y NUMERIC(18,4).
Errores sin SQL ni información sensible; Cache-Control no-store, Bearer sin cookies.

## Limitaciones conocidas

- Sin idempotencia de aplicación: G4 pendiente para RD-UC42-002. No hay clave
  estable en la firma ni deduplicación por motivo/tipo/timestamp.
- SALDO_INICIAL protegido por UNIQUE parcial y F03: segundo intento devuelve 409
  SALDO_INICIAL_DUPLICADO, sin otro efecto. El trigger BEFORE de F03 puede fallar
  antes del UNIQUE: por eso se valida el duplicado bajo lock y se mantiene además
  el mapeo defensivo de DataIntegrityViolationException para el índice específico.
- Reintentos de AJUSTE_POSITIVO/AJUSTE_NEGATIVO pueden duplicar un efecto si ambos
  siguen siendo válidos. El lock protege consistencia, no reconoce la operación.
- Mitigaciones: confirmación UI y bloqueo de envíos simultáneos (UI fuera de este PR),
  audit trail mediante usuario, motivo, fecha, movimiento, costo, detalles y stock.
  No reintentar automáticamente una escritura de resultado incierto.
- REVENTA, conversiones, emisión de rondas y la RD-UC42-002 quedan fuera del corte.
- V1/V2/V3, gobierno, source-lock y servicios de maestros intactos. Único cambio
  UC08: extracción de cierre preservando lógica. Issue #28; sin despliegue.

## Pruebas reproducibles

Requiere JDK21, Maven y Docker; PostgreSQL `postgres:17.11-alpine` con Flyway real.

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=RegistrarAjusteInventarioServiceIntegrationTest,EpisodioFaltanteServiceIntegrationTest,RegistrarAjusteInventarioHttpExternoTest,RegistrarIngresoStockInsumoServiceIntegrationTest,RegistrarIngresoStockReventaServiceIntegrationTest,PrecondicionesDespliegueTest,AutenticacionHttpExternaTest test
```

Resultado local real: BUILD SUCCESS, sin omisiones.

| Suite | Resultado |
|---|---|
| RegistrarAjusteInventarioServiceIntegrationTest | 14/14 |
| RegistrarAjusteInventarioHttpExternoTest | 8/8 |
| EpisodioFaltanteServiceIntegrationTest | 4/4 |
| RegistrarIngresoStockInsumoServiceIntegrationTest (regresión) | 15/15 |
| RegistrarIngresoStockReventaServiceIntegrationTest (regresión) | 11/11 |
| PrecondicionesDespliegueTest | 19/19 |
| AutenticacionHttpExternaTest | 11/11 |
| Total focalizado | 82/82 |

`test-compile`: BUILD SUCCESS. En este entorno se limitó temporalmente la JVM
con `JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1` para ejecutar las pruebas tras
observar procesos detenidos del JDK local. El workflow usa Java21 estándar;
no se agregó esta opción al proyecto. Docker estuvo disponible y se ejecutaron
las pruebas contra PostgreSQL real, sin skips.

Reportes en target/surefire-reports y workflow b4-06-uc42-http-focused.yml.
La cobertura incluye falla en segundo detalle con rollback completo, costo/FK
exacta, trigger, saldo inicial secuencial/concurrente y riesgo residual de reintento.
Las pruebas sintéticas no certifican operación productiva ni revisión humana.

## Diagnóstico previo

Base verificada: origin/main `02bddfae7cd86dd05b0f410db84531f8e99b72e9`
(PR #69 MERGED), contiene `d8a88f2`. Últimos tres commits al comenzar:

```text
02bddfa feat(B4-05): UC-08 REVENTA — ingreso de stock por compra (reventa)
a049d3f docs(b4-05): documentar fuentes y verificacion de UC08 REVENTA
a53008b ci(b4-05): workflow focalizado UC08 REVENTA
```

- V1:1025: UNIQUE parcial uq_movimiento_insumo_saldo_inicial por idInsumo,
  WHERE tipoMovimiento='SALDO_INICIAL'; F03 exige primer movimiento/secuencia1.
- UC08: secuencia COALESCE(MAX(numeroSecuenciaInsumo),0)+1 por insumo.
- Cierre UC08: stock >= umbral, sin solicitudes RECEPCION_ABIERTA/PENDIENTE_DECISION
  ni órdenes PENDIENTE/PARCIALMENTE_RECIBIDA. SQL extraído sin cambios.
- EPISODIO_FALTANTE: PK idEpisodio; idInsumo, fechaHoraInicio,
  fechaHoraCierre nullable, idMovimientoApertura nullable/UNIQUE/FK.
- Búsqueda de implementación UC42: solo comentario de GestionInsumosService,
  que declara no insertar SALDO_INICIAL ni movimientos/costos. Servicio nuevo.
