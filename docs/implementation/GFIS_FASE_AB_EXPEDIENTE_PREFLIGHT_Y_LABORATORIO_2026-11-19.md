# G-FIS (Fase A + Fase B — Corrección final R08.6 de los 5 bloqueantes) — Expediente de preflight, prototipo SQL candidato, campaña C3 y dictamen de aptitud

**Fecha inicial:** 19/11/2026 · **Corrección final R08.6 (5 bloqueantes):** 20/11/2026 (UTC−03)  
**Estado:** **FASE A + FASE B EN LABORATORIO AISLADO (PR `DRAFT #101` / NO MERGE)** · **PROMOCIÓN `V4` (`G-FIS` Fase C) Y `G-DEV / R09.1` NO AUTORIZADAS**.  
**Invariantes preservadas:**
- **`0` cambios en `src/main/**`** (`V1–V3`, servicios Java, `application.yml`, Demo y `source-lock.md` 100 % intactos).
- **`S10 = NO ACREDITADO`** (tratamiento autónomo de G-ARQ Nivel 2).
- **`A1`, `FA-1`, `FA-2` y `B1` son PROPUESTAS en validación técnica, NO RATIFICADAS todavía.**
- Excluidos expresamente: `UC-07` físico (`#47/#48`), `PE-3` offline, recupero post-cierre (`E`), medios distintos de efectivo (`D1`), Demo e infraestructura/despliegue (`Issue #28` pausada).

---

## 1. Snapshot congelado y trazabilidad normativa

| Elemento | Repositorio / ruta | SHA / referencia | Estado |
|---|---|---|---|
| **Gobierno `main`** | `leandrito369/ale-milanesas` (`#67→#66→#65→#70→#71→#72→#73`) | `e3f4ee5a752b29329117c7bb097e2a851ea60ca5` | **Integrado** (documental; 0 PR cerradas) |
| **Aplicación `main` base** | `leandrito369/ale-milanesas-app` (`#96→#88→#91→#87`) | `21db1f536d0d7153e5d4abb721115778dadf14b3` | **Integrado** |
| **G0 `source-lock`** | `RD-B0-G0-001` (64 CU / 67 operaciones / RBAC 67×8 / BC07 15 filas) | `source_commit=1263ffc1` / `governance_commit=27738b08` | **Invariable** |
| **Salvedad `S10`** | G-ARQ Nivel 1 | **NO ACREDITADO** | Conservada explícitamente |

---

## 2. Resolución técnica de los 5 bloqueantes de integridad (`R08.6`)

| # | Bloqueante señalado | Corrección física implementada en laboratorio (`V4_CANDIDATE`) | Evidencia SQL / Testcontainers PG 17.11 |
|---|---|---|---|
| **1** | **`T1-C2` (`G-ARQ-N2-01 §8`):** `PENDIENTE_CONCILIACION` podía coexistir con Cobros/Caja/aplicaciones definitivas | Separación estricta: `PENDIENTE_CONCILIACION` fuerza `importeImputadoConfirmado = 0`, `claveIdempotenciaCierre IS NULL`, `reciboHistoricoJson IS NULL` y planifica en `PROPUESTA_APLICACION_PENDIENTE` (no financiera). Los triggers de `COBRO_RENDICION_K2` y `APLICACION_RENDICION_K2` **rechazan cualquier `INSERT`** si `estadoCierre = 'PENDIENTE_CONCILIACION'`. La confirmación definitiva se ejecuta en **un único `COMMIT` `T1-C2`** (`PENDIENTE_CONCILIACION → CERRADO_CON_DIFERENCIA_RESUELTA`) que crea de modo indivisible Cobros `K2`, Cuenta, Caja, aplicaciones, extinción parcial de `V_e` y `reciboHistoricoJson` inmutable (conservando `V_e^{vig}` remanente sin doble contabilización). | `GfisPreflightEIntegridadV4LabIntegrationTest.pendienteConciliacionNoGeneraEfectosFinancierosYConfirmacionT1C2ConCustodiaE04EsAtomica` (escenario exacto `G-ARQ-N2-01 §8`: `A1=70, A2=50, B1=60 → 170+10+10`) + `seguridadRbac...` (`0` apps). |
| **2** | **Integridad de Recorrido (`fn_gfis_validar_recorrido_entrega`, `FA-1`, `FA-2`)** | `fn_gfis_validar_recorrido_entrega` **exige** `RECORRIDO_ASIGNACION(ACTIVA)` previa para `(idPedido, idRecorrido)` **y** `ASIGNACION_REPARTO` vigente (`fechaHoraFin IS NULL`) con el mismo Repartidor. **`FA-1` (propuesta):** `uq_gfis_unico_recorrido_abierto_repartidor` impide dos `Recorrido` `ABIERTO` simultáneos del mismo Repartidor. **`FA-2` (propuesta):** `fn_gfis_validar_admin_y_cierre` bloquea cualquier cierre/conciliación si existen Pedidos `PENDIENTE` en `RECORRIDO_ASIGNACION(ACTIVA)`. Sin modificar G0 ni contratos comerciales. | `GfisPreflightEIntegridadV4LabIntegrationTest.fronteraA_IntegridadRecorridoFa1Fa2ReasignacionUc43YEntregaSinPertenenciaRechazada`. |
| **3** | **Unicidad y máquina de estados (cierre terminal vs conciliación pendiente concurrentes)** | `CIERRE_RECORRIDO_PE2` impone restricción incondicional **`CONSTRAINT uq_gfis_unico_cierre_por_recorrido UNIQUE ("idRecorrido")`**, imposibilitando a nivel B-tree/MVCC cualquier coexistencia entre `PENDIENTE_CONCILIACION` y un cierre terminal sin importar orden ni concurrencia. Estados terminales (`CONFIRMADO`, `CERRADO_CON_DIFERENCIA_RESUELTA`) son 100 % inmutables; la resolución administrativa no puede reescribir evidencia histórica ni habilitar segundo cierre financiero; `C1-a` replay valida `ADMINISTRADOR`, `idRecorrido` y huella. | `GfisConcurrenciaLocksGeneradoresLabIntegrationTest.concurrenciaCierreTerminalVsPendienteConciliacionMismoRecorridoNuncaCoexisten` (carrera real 2 hilos + ambos órdenes secuenciales → siempre `count=1` y `23505`). |
| **4** | **Custodia y diferencias (`E-04`):** devolución física exige evidencia auditable con recibo y egreso | Nueva tabla append-only **`EGRESO_CUSTODIA_RENDICION`** (`numeroReciboDevolucion UNIQUE`, `receptorIdentificacion`, `importeEgreso`, `motivo`, `idUsuarioAdmin` `ADMINISTRADOR HABILITADO`, conservación `SUM(egresos) <= custodia`, sin `MOVIMIENTO_CAJA` de venta). Constraint trigger diferido `ct_gfis_cierre_total_k2` (`E04-CUSTODIA`) **rechaza al `COMMIT`** cualquier `CUSTODIA_DEVUELTA_FISICAMENTE` o `MIXTA_FALTANTE_Y_CUSTODIA` sin `EGRESO_CUSTODIA_RENDICION` por el importe exacto de custodia. Coherencia `CHECK` impide usar `FALTANTE_ASENTADO_SIN_RECUPERO_AUTOMATICO` cuando `importeCustodiaNoImputada > 0`. | `GfisPreflightEIntegridadV4LabIntegrationTest.pendienteConciliacionNoGeneraEfectosFinancierosYConfirmacionT1C2ConCustodiaE04EsAtomica` (pruebas negativas sin recibo / exceso / no-ADMIN y prueba positiva T1-C2 + recibo único + inmutabilidad). |
| **5** | **`C-04` (`ART-GCON-D1`) y `L02`:** soporte físico de idempotencia de anulación + 64-bit seguro | Mantiene `L02` 64-bit sin cast `BIGINT -> INTEGER` (`hashtext(id::text)`) y estrategia de cutover atómico `B2` en `R09.1`. Añade tabla append-only **`ANULACION_COBRO_IDEMPOTENCIA`** (`claveIdempotenciaAnulacion PK`, `idCobro UNIQUE`, FKs a `COBRO`, `MOVIMIENTO_CUENTA` y `MOVIMIENTO_CAJA` contraasientos, `huellaCanonica`, `motivoNormalizado`, `reciboHistoricoJson` inmutable) + trigger `tg_gfis_validar_anulacion_idemp` que verifica `ADMINISTRADOR`, `COBRO='ANULADO'` y contraasientos reales. | `GfisL02AnulacionCandidataLabIntegrationTest` (`n2T10a` V1, `n2T10b` L02, `n2T10c` `BIGINT > 2^31-1`, **`n2T10d` C-04 replay / `403` / `409` / `A1` nueva clave / rollback sin consumir clave / inmutabilidad**). |

---

## 3. Artefactos de laboratorio en PR `#101` (`12` tablas candidatas, `28` tests ejecutables, `0` cambios en `src/main/**`)

1. **`src/test/resources/gfis/V4_CANDIDATE__pe1_pe2_imp01_lab.sql`** (`12` tablas + vista `VW_IMP_A_DISPONIBILIDAD_COBRO` + `fn_gfis_advisory_lock_key` + secuencias `B2` + triggers de integridad).
2. **`src/test/resources/gfis/V4_CANDIDATE_L02_ANULACION_LAB.sql`** (`fn_mr3_anular_cobro_manual` en orden `A2(3202, hashtext) → CLIENTE U → COBRO U` en contenedor aislado).
3. **`src/test/java/ar/edu/unam/alemilanesas/gfis/GfisLabSupport.java`** (coordinador de laboratorio para cierre limpio `T1-C2`, planificación `PENDIENTE_CONCILIACION`, confirmación atómica `T1-C2` con egreso de custodia `E-04`, y anulación idempotente `C-04`).
4. **`GfisPreflightEIntegridadV4LabIntegrationTest.java`** (`7` tests).
5. **`GfisConcurrenciaLocksGeneradoresLabIntegrationTest.java`** (`8` tests).
6. **`GfisCampanaC3AmpliadaLabIntegrationTest.java`** (`9` tests).
7. **`GfisL02AnulacionCandidataLabIntegrationTest.java`** (`4` tests en contenedor aislado).

---

## 4. Matriz de cobertura actualizada (`N2-T01..T20` — `28` tests de laboratorio PG 17.11)

| ID | Escenario y controles verificados | Nivel | Cobertura en PR `#101` | Estado | Pendiente / hito destino |
|---|---|---|---|---|---|
| **N2-T01 / T02** | `K2` (1 Cobro/Cliente/Cierre, multi-Entrega y multi-Cliente) y conservación `A_e + V_e^{vig} = V_e^0` | P | `pendienteConciliacionNoGenera...` (`§8`: `110+60=170`, `V_{A2}^{vig}=10`) + `controlesNegativosK2...` | **PASS PARCIAL** *(SQL/P)* | Servicio productivo `UC-29 rendición` (`R09.3`). |
| **N2-T03** | Rollback atómico `T1` ante error previo a `COMMIT` (`422`, `K2-CONSERVACION`, `E04-CUSTODIA`) | P | `cierreLimpioRollbackAtomico...` + `pendienteConciliacionNoGenera...` + `controlesNegativosK2...` | **PASS PARCIAL** *(SQL/P)* | Verificar en `@Transactional` de `R09.3`. |
| **N2-T04 / T05** | Replay `C1-a` (en `CONFIRMADO` y en `CERRADO_CON_DIFERENCIA_RESUELTA`), `409` huella/ámbito, `403` no-ADMIN, nueva UUID sobre cerrado (`23505`) | P | `replayIdempotenteC1a...`, `pendienteConciliacionNoGenera...`, `n2T05_T14...` | **PASS PARCIAL** *(SQL/P)* | Sesión HTTP real + mapeo `RECORRIDO_YA_CERRADO` (`R09.3`). |
| **N2-T06** | Dos `ADMINISTRADOR` simultáneos + carrera concurrente `CONFIRMADO` vs `PENDIENTE_CONCILIACION` (y ambos órdenes secuenciales) | P | `dosAdministradoresSimultaneos...` + `concurrenciaCierreTerminalVsPendienteConciliacionMismoRecorridoNuncaCoexisten` | **PASS PARCIAL** *(SQL/P)* | Validar con servicio HTTP/sesión real (`R09.3`). |
| **N2-T07** | `PENDIENTE_CONCILIACION` con 0 efectos financieros (`§8`), `0` aplicaciones positivas, confirmación posterior en un único `COMMIT` `T1-C2`, egreso de custodia con recibo (`E-04`) | P | `pendienteConciliacionNoGenera...` + `seguridadRbac...` | **PASS PARCIAL** *(SQL/P)* | Ratificación humana de `B1`; implementación `R09.3`. |
| **N2-T08 / T09** | `C3-03` (`UC-43` vs `UC-05/PE-1`) y `C3-08` (`UC-29` + `UC-22` vs `UC-05/PE-1/IMP-B`) + integridad Recorrido `FA-1`/`FA-2`/pertenencia `ACTIVA` | R + P | `n2T08a/b`, `n2T09a/b`, `fronteraA_IntegridadRecorridoFa1Fa2...` | **PASS PARCIAL** *(R+P)* | Ratificación `A1/FA-1/FA-2`; servicios `UC-05`/`UC-43` en `R09.1–R09.2`. |
| **N2-T10a/b/c/d** | `L02` línea base `V1`, corrección `CLIENTE→COBRO`, **`BIGINT > Integer.MAX_VALUE`** sin `22003` y **modelo físico `C-04`** (`ANULACION_COBRO_IDEMPOTENCIA`) | R + P | `GfisL02AnulacionCandidataLabIntegrationTest` (`4/4` en contenedor aislado) | **PASS PARCIAL** *(aislado; `V1` intacta)* | Promoción `V4` (`G-FIS`); adaptación `CobroManualService` (`R09.1`). |
| **N2-T13 / B2** | Multi-Cliente `[A,B]` vs `[B,A]`, secuencias `B2` y estrategia de cutover atómico desde `MAX+1`/`2929001` | R + P | `n2T13...`, `generadoresB2Hibrido...`, `compatibilidadTransicionLegacy...` | **PASS PARCIAL** *(R+P)* | Cutover atómico de `CobroManualService`/`UC-22` en `R09.1`. |
| **N2-T14..T19** | `D1`, `IMP-C`, `4×8` + `40P01` retry, 5 señales `RCG-06`, `422` + diagnóstico, custodia segregada + egreso con recibo `E-04` | R + P | `n2T16`, `n2T17`, `n2T18`, `n2T05_T14`, `pendienteConciliacionNoGenera...` | **PASS PARCIAL** *(lab)* | Arqueo en 3 vistas + reconciliación `UC-15`/`UC-03` (`R09.1/R09.3`). |
| **N2-T11 / T12 / T20** | `UC-07` (`CR-C`/`CR-F`), `PE-3` offline, E2E global | D | Excluidos estrictamente del corte `G-FIS` Pedidos originales | **DIFERIDO JUSTIFICADO** | `R09.4` / `R09.5` / `I7`. |

---

## 5. Dictamen de aptitud `G-FIS` (cierre de correcciones `R08.6`)

- **Aptitud técnica del esquema físico candidato (`V4_CANDIDATE` + `L02` + `C-04`):** **APTO EN LABORATORIO** tras corregir los 5 bloqueantes de integridad (`T1-C2`, pertenencia `ACTIVA` + `FA-1`/`FA-2`, unicidad incondicional de cierre, egreso auditable de custodia `E-04`, y soporte físico `C-04` + `BIGINT` 64-bit).
- **Dictamen de puerta `G-FIS` (promoción de `V4` a `src/main/resources/db/migration/`):** **`GO CONDICIONADO A RATIFICACIÓN EXPRESA DE FRONTERAS` (`NO-GO` automático mientras falten decisiones humanas):**
  1. Ratificación expresa de **`A1` + `FA-1` + `FA-2`** (Recorrido explícito, 1 `ABIERTO` por Repartidor, 0 `PENDIENTE` activos antes del cierre, sin alterar G0 ni RBAC `UC-43` exclusivo `VENDEDOR`).
  2. Ratificación expresa de **`B1` corregida bajo `T1-C2`** (`PENDIENTE_CONCILIACION` sin efectos financieros, confirmación definitiva en un único `COMMIT` `T1-C2`, conservación de `V_e^{vig}` remanente, y egreso de custodia con recibo `E-04`).
  3. Autorización física expresa para promover `V4` (que incluirá las `12` tablas aditivas, la vista `VW_IMP_A_DISPONIBILIDAD_COBRO`, el reemplazo `L02` 64-bit y el soporte `C-04`, sin tocar `V1–V3`).
- **Puerta `G-DEV / R09.1`:** permanece **cerrada, independiente y posterior a `G-FIS`**. PR `#101` continúa `OPEN / DRAFT / NO MERGE / NO DESPLEGAR`. Demo intacta; Issue `#28` pausada.
