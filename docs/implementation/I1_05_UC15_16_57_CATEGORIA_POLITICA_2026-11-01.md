# I1-05 — Categoría y política comercial interna (UC-15, UC-16, UC-57)

**Fecha:** 01/11/2026 · **Estado:** Propuesta B3, sujeta a CI y revisión humana. **Sin endpoints B4 ni escenarios E2E acreditados.**

## Fuentes normativas G0

- Backlog rector `B0_BACKLOG_I0_I1_PRIMER_VERTICAL_2026-10-29.md`, commit G0 `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`: **I1-05 = UC-15/16/57**. No corresponde implementar Recetas ni Promociones en esta iteración (UC10 es I3-01, UC24 queda fuera de I1).
- Contratos `docs/08-diagramas/revision/REV06B_CLIENTES_RECETAS_INVENTARIO/propuestos/CONTRATOS_REV06B_TRECE_CORREGIDOS.md`, operaciones `evaluarYActualizarCategoriaComercial` (UC15), `aplicarAjusteComercialSegunCategoria` (UC16), `configurarPoliticaCategoriaComercial` (UC57). MR5/F01, Fase4/RD-F04-003/006/007, RD-RP023-001.
- Implementación inicial de evaluador de UC15 revisada y probada en [PR #14](https://github.com/leandrito369/ale-milanesas-app/pull/14), **aún no fusionada**; el evaluador se porta a esta rama independiente como código provisional y debe conciliase en un merge futuro. No contabilizar pruebas de ramas independientes como suite integrada.

## Alcance UC57 — versionado temporal de reglas

`ConfigurarPoliticaCategoriaComercialService`: solo Administrador interno revalidado después de lock `pg_advisory_xact_lock(5715001)`; `POLITICA_CATEGORIA_COMERCIAL` nueva y **exactamente dos hijos** `AJUSTE_POLITICA_CATEGORIA` para CUMPLIDOR/EN_MORA. `tipoAjuste` físico de MR5; porcentajes `NUMERIC(5,2)` de 0 a 100; SIN_AJUSTE implica porcentaje 0; descuento/recargo requieren positivo. No existen porcentajes incrustados en tabla política.

`vigenteDesde` debe ser futuro respecto del mismo reloj PostgreSQL; `vigenteHasta` es opcional y exclusivo. Si la política anterior abierta cruza el nuevo inicio, se acota su fin **prospectivamente** sin alterar inicio/tolerancia/ajustes históricos. Si una programación futura se solapa, se rechaza antes de cualquier UPDATE, incluso cuando su comienzo antecede al nuevo inicio: no se trunca una vigencia todavía no iniciada por inferencia. Exclusión GiST MR5 es barrera física definitiva. No se inventa un umbral por defecto ni se altera PEDIDO histórico. IDs BIGINT se generan con MAX+1 bajo lock común solo para escritores cooperantes.

## Alcance UC15 — reevaluación de categoría

`EvaluadorCategoriaComercialInterno` usa `CLIENTE FOR UPDATE` y bloquea la política vigente `FOR SHARE`; devuelve CUMPLIDOR/EN_MORA, actualiza columna solo ante diferencia. La fecha usada para vigencia y mora proviene de **una sola marca TIMESTAMPTZ de PostgreSQL**, nunca del reloj JVM. Imputa créditos confirmados FIFO sobre deudas; CARGO_POR_VENTA toma `ENTREGA.fechaHoraEntrega`, los demás débitos genuinos toman fecha del movimiento. `ANULACION_COBRO` devuelve crédito al pool de deudas al reducir el crédito acumulado, no crea deuda nueva. No se suma efectivo declarado en tránsito.

Antigüedad: **días completos de 24 h**, `ChronoUnit.DAYS.between(origen, instanteDb)>diasToleranciaMora`; igualdad no es mora. Es interpretación ya discutida en PR #14, distinta de fecha calendario local, reservada para ratificación si Fase4 exige otra lectura. Si falta política vigente, excepción y rollback, sin tolerancia por defecto. **Reserva UC26/49:** al implementar devolución electrónica identificar explícitamente los AJUSTE_DEBITO que reversan pagos y descontarlos de créditos, en lugar de imputarlos como nueva deuda temporal. No tratar todos los AJUSTE_DEBITO como reembolsos.

## Alcance UC16 — resumen congelable del nuevo Pedido

`AplicarAjusteComercialService`: servicio interno sin controlador HTTP. Requiere Cliente ACTIVO y subtotal base no negativo. Incluye UC15, consulta la **misma política vigente con igual instante de BD** y su regla hija exacta, y retorna `idPoliticaComercial`, `categoriaComercialAplicada`, `tipoAjuste`, `porcentajeAjuste`, `importeAjuste` y `totalOriginal`, redondeando importes finales a 2 decimales (`NUMERIC(18,2)` de PEDIDO). SIN_AJUSTE deja base sin cambio. Este servicio **no inserta PEDIDO ni detalles**: el futuro UC03/UC21 debe utilizar el resultado en **una misma transacción de creación de Pedido**, conservar precio congelado y determinar correctamente la base de cada canal. No se selecciona promoción ni precio vigente en este servicio.

## Pruebas previstas

- `CategoriaPoliticaServiceIntegrationTest`: **12 escenarios** con PostgreSQL Testcontainers: crear política y dos reglas, cierre de período, solape, validación/roles, mora por días completos, créditos FIFO, ausencia de política/Cliente, descuento/recargo/sin ajuste, errores y conservación de total y política en Pedido existente.
- `PoliticaCategoriaConcurrenciaIntegrationTest`: **1 carrera real de dos comandos** mismo inicio, sin `@Transactional` de JUnit; a lo sumo un commit de política y exactamente dos reglas.
- B1 y B2 deben pasar para el mismo HEAD. MR5/F01 inmutables, revisión de logs, artefactos y SHA. La confirmación de integridad no equivale a aceptación E2E.

## Reservas y gate

1. **Conciliación con PR #14:** el evaluador portado añade `CLIENTE FOR UPDATE` y sobrecarga con instante DB común; resolver solapamiento de archivo y correr suite completa tras integración. Las ramas actuales parten de main, no de PR #14.
2. UC15 no es endpoint de uso general ni autoriza una acción remota del Cliente. UC16 requiere invocación desde UC03/UC21 y transacción unificada; no hay Pedido nuevo creado en I1-05.
3. `pg_advisory_xact_lock(5715001)` protege solo escritores cooperantes de política y `CLIENTE FOR UPDATE` protege movimientos por cliente; futuros escritores deben adoptar jerarquía estable de locks. `FOR SHARE` política antes de escribir se coordina con UC57; requiere pruebas multitabla integradas al introducir UC03/UC21.
4. El algoritmo de redondeo monetario de UC16 está explicitado como HALF_UP a centavos (totalOriginal NUMERIC(18,2)), pendiente de ratificación si existe regla normativa más detallada de redondeo de subtotales.
5. Las cuatro pruebas E2E UC54 siguen documentadas sin acreditación, BC06 0/18 E2E. B4 sólo al finalizar I1; siguiente incremento rector I1-06 UC50/51/52/36 Insumos y Unidades. No merge automático.

