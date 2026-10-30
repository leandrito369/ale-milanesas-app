# I3 / UC-29 — Cobro manual general y contraasiento (B3, revisión)

**Fecha:** 30/10/2026. **Estado:** propuesta técnica no fusionada; CI y revisión pendientes hasta observar resultados del SHA final. **BC06 sigue 0/18 pruebas E2E aceptadas**: este incremento ejercita servicios internos PostgreSQL, no endpoints B4.

## Autoridad y alcance

- Fuente G0: `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`; contrato B0 REV-05 `docs/08-diagramas/consolidacion-b0/CONTRATOS_REV05_CORREGIDOS_B0_ACTUALIZADO.md` (operaciones 2 y 3); `RD-B0-BC03`, `RD-RP007-001`, `RD-F01`, `BC09`; `BC06-01..06`.
- Registro **solo MANUAL general** por Admin/Vendedor con sesión interna revalidada; Repartidor rechazado. No se acepta MERCADOPAGO desde la vía manual; ni ONLINE ni efectivo declarado UC05 ni rendiciones F04.
- La columna física MR5 `COBRO.origen` admite `ADMINISTRACION|REPARTO|ONLINE`, no el literal funcional `MANUAL` del contrato: el servicio mapea **manual general a ADMINISTRACION**. No agrega valor ni migración, y NO interpreta REPARTO como rendición ya validada.
- Un Cobro confirmado genera exactamente un `MOVIMIENTO_CUENTA.PAGO`, su `ORIGEN_MOVIMIENTO_CUENTA.idCobro` y un `MOVIMIENTO_CAJA.INGRESO` vinculado. `claveIdempotenciaOrigen` existente se exige para el comando interno; coincidencia exacta retorna id anterior, clave reutilizada con otros datos se rechaza.
- Anulación solo Administrador de Cobro confirmado con origen ADMINISTRACION, motivo obligatorio. Invoca la función PostgreSQL **preexistente** `fn_mr3_anular_cobro_manual`, que genera `ANULACION_COBRO`, `EGRESO` y transición `COBRO.estadoCobro=ANULADO`. Se rechaza segunda anulación, Cobro ONLINE o pago MercadoPago.
- UC15 se invoca **internamente en la misma transacción**. Requiere exactamente una política comercial vigente; sin ella rechaza y revierte Cobro y movimientos. Se imputan créditos confirmados FIFO a cargos; `ANULACION_COBRO` deshace crédito, no reinterpreta el contraasiento como nueva venta. `fechaHoraEntrega` del cargo fija antigüedad; otros débitos usan la fecha de movimiento. No se añaden endpoints UC15.
- Los IDs BIGINT de MR5 no tienen secuencias: bloqueo transaccional asesor 2929001 + `MAX+1` para escritores de este servicio, sin prometer interoperabilidad concurrente con otros escritores externos todavía no implementados. Saldo se secuencia bajo lock de CLIENTE y se almacena por movimiento. Si falla alguna operación o categoría, `@Transactional` debe revertir todo.

## Pruebas de integración propuestas

`CobroManualServiceIntegrationTest` sobre Spring Boot + Flyway V1/V2 + Testcontainers PostgreSQL 17.11, sin datos reales:

| ID | Resultado que se comprueba en B3 |
|---|---|
| BC06-01 | Repartidor denegado; ninguna fila financiera |
| BC06-02 | Vendedor confirma único PAGO y único INGRESO |
| BC06-03 | Admin confirma; reintento con misma clave no duplica; clave con distinto importe rechazada |
| BC06-04 | Vendedor no anula ni crea contraasiento |
| BC06-05 | Administrador anula manual: estado ANULADO + contraasiento Cuenta + egreso Caja; segundo intento rechazado |
| BC06-06 | Cobro ONLINE no admite anulación manual |
| Extras | Importes inválidos, medio de pago electrónico en canal manual, Pedido inexistente, motivo vacío y sesión revocada |

**Interpretación:** si CI pasa, se demuestra **B3 de la operación manual bajo datos sintéticos**; la matriz de 18 BC06 de G0 permanece intacta. No marcar BC06-01..06 como aceptación E2E completa mientras B4 no materialice autenticación real y permisos, ni mientras no exista prueba independiente de concurrencia entre servicios.

## Limitaciones de salida / pendientes

1. Contrato `registrarCobro` contempla `referenciaExterna` y registro confirmado ONLINE por flujo verificado UC21; **no implementados** aquí. El módulo manual no ofrece parámetro externo de referencia porque no expone ese canal. Requiere ajuste técnico previo a considerar la cobertura completa de la firma.
2. **F04/CR-B0-F04-001 sigue abierta**; esta PR no crea ni retira pago en tránsito ni registra rendiciones. No implementar BC06-07..09 por inferencia.
3. El evaluador FIFO aplicado exige revisión semántica específica de política temporal: el concepto `diasToleranciaMora` se compara con períodos de 24 horas en el código propuesto; validar contra la interpretación de calendario de la fuente antes de aceptación, especialmente con ajustes debitados sin Entrega. No asumir esta opción como decisión nueva aprobada.
4. Se usa el origen físico `ADMINISTRACION` para manual general; revisar que todos los productores y futuros reportes respeten la correspondencia sin reescribir G0/MR5.
5. La idempotencia exige clave interna UUID; falta B4 que emita/valide contexto de operación y sesión auténtica. No recibir UUID no autenticados por HTTP como prueba de identidad.
6. La cerradura `pg_advisory_xact_lock(2929001)` sirve solo a estos comandos; no da garantías frente a escritores concurrentes que no compartan protocolo. Requiere prueba multihilo adicional y eventual generador central cuando se implementen UC05/26/49.
7. Pruebas automatizadas no equivalen a aceptación humana, despliegue productivo ni cierre de los cuatro E2E UC54 de [issue #10](https://github.com/leandrito369/ale-milanesas-app/issues/10).
8. **Sin migraciones nuevas**. MR5 y F01 se mantienen inmutables. Toda diferencia con Fase4/RD requiere nueva RP/CR en repositorio documental, no resolución silenciosa.

**Puerta técnica:** B1/B2 verdes para el SHA final, reporte de tests real, trazabilidad con G0, revisión del algoritmo FIFO y de asientos/casos de error, aceptación humana antes de merge. Seguimiento general [BC06 issue #13](https://github.com/leandrito369/ale-milanesas-app/issues/13).

## Revisión técnica posterior del 30/10/2026 — correcciones dirigidas al gate

Se incorpora el dictamen recibido sobre **UC15 y concurrencia** sin cambiar los contratos de G0, MR5/F01 ni extender F04 o B4.

### Política temporal (implementada en código y bajo prueba)

- La categoría EN_MORA exige `antiguedadDeudaMasAntigua > diasToleranciaMora`. La antigüedad pasa a calcularse como `ChronoUnit.DAYS.between(fechaDeuda, instanteDb)`: **días COMPLETOS transcurridos**, con comparación estricta. Por tanto, tolerancia 0 no genera mora con apenas segundos/horas, mientras que a las 24 horas completas pasa a 1 día y puede generar mora. Con tolerancia 7, 7 días y 1 segundo sigue contando 7; recién con 8 días completos corresponde mora.
- `instanteDb` proviene de **un único `SELECT clock_timestamp()` de PostgreSQL**, obtenido como `OffsetDateTime` (`TIMESTAMPTZ`) y se reutiliza tanto para vigencia de `POLITICA_CATEGORIA_COMERCIAL` como para el cálculo de antigüedad; se elimina `Instant.now()`. Los INSERT siguen usando reloj BD; su evaluación toma una instantánea temporal por invocación.
- Esta definición significa días **transcurridos de 24 h**, NO diferencia entre fechas de calendario local. Si Fase 4 o el responsable exige contar días calendario, se requiere precisión normativa adicional; no implementar silenciosamente `current_date - fecha::date` ni introducir zona horaria implícita.
- Se añaden **5 tests unitarios de frontera** (0 días, igualdad a 7, instante futuro, parámetros inválidos) y **un test de integración PostgreSQL** que modifica fechas efectivas, verifica tolerancias 0 y 7 y observa las dos categorías, incluso con sesión PostgreSQL en zona `America/Argentina/Cordoba` distinta de UTC.
- **Reserva UC26/UC49:** al implementar reembolsos, un `AJUSTE_DEBITO` que efectivamente revierta un `PAGO` electrónico (correlacionado por `idReembolso/idCobro`) deberá reducir el pool de créditos en vez de convertirse automáticamente en nueva deuda con fecha reciente. No cambiar **todos** los `AJUSTE_DEBITO` aquí: existen ajustes de débito genuinos y requiere discriminación verificada del origen. Exigir pruebas de FIFO/deuda antigua y reversión de crédito en el futuro incremento financiero.

### Concurrencia y garantía transaccional (implementadas bajo prueba)

- `anularCobroManual` bloquea explícitamente `CLIENTE FOR UPDATE` **después** de localizar/bloquear `COBRO` y **antes** de ejecutar `fn_mr3_anular_cobro_manual` y `UC15`. Cumple la precondición de bloqueo del evaluador, además de la cerradura financiera compartida de este servicio.
- Se amplía el test **sin `@Transactional` en JUnit**, con dos hilos de transacciones reales que inician en paralelo un `registrarCobro` con la **misma** clave técnica. Ambos deben recibir el mismo `idCobro` y producir solo un Cobro, un PAGO y un INGRESO; continúa verificando rollback real por falta de política y commit de anulación con contraasientos.
- El `pg_advisory_xact_lock(2929001)` y los IDs `MAX+1` constituyen **protocolo interno del módulo**, no protección para otros escritores independientes. Al implementar UC05/22/26/49, centralizar generadores/locks y acordar **un orden total de locks por tabla/objeto** para evitar interbloqueos (`COBRO` → `CLIENTE` de esta anulación frente a `CLIENTE` → `COBRO` de otros flujos). Evaluar la necesidad de identificadores centralizados antes de escritura concurrente entre módulos.

### Puerta de integración

La PR queda en borrador hasta verificar **B1 + B2 SUCCESS en el SHA final**, resultados cuantitativos, logs de carrera/rollback y revisión del orden de bloqueo. El dictamen de esta revisión **no autoriza fusionar automáticamente**. Continuar la iteración 1 con servicios B3 y posponer B4/aceptación E2E hasta finalizar I1, como instrucción explícita del responsable.

### Próxima secuencia según backlog G0-D03 — sin adelantar B4

El orden rector ratificado sigue **I1-01 → I1-02 → I1-03 → I1-04 → I1-05 → I1-06** antes del recorrido vertical I2/I3 completo; esta implementación parcial de UC29 es una excepción acotada a los ensayos BC06. El próximo trabajo de implementación deberá recuperar lo pendiente de I1 (particularmente operaciones de seguridad/maestros todavía sin servicio B3), revisar dependencias y pruebas por CU, y no abrir UI/endpoints HTTP B4 hasta finalizar iteración 1. Los **18 BC06** y los **cuatro E2E UC54** seguirán sin acreditación E2E mientras no exista B4, independientemente de los PASS de las pruebas B3.
