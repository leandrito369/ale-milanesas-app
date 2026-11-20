# G-FIS (Fase A + Fase B ampliada y refinada) — Expediente de preflight, prototipo SQL candidato, campaña C3 y dictamen `GO / NO-GO`

**Fecha inicial:** 19/11/2026 · **Ampliación C3 / R08.6:** 20/11/2026 · **Refinamiento Decisiones 1–3:** 20/11/2026 (UTC−03)  
**Estado:** **FASE A + FASE B EN LABORATORIO AISLADO (PR `DRAFT #101` / NO MERGE)** · **PROMOCIÓN `V4` (`G-FIS` Fase C) Y `G-DEV / R09.1` SIN AUTORIZACIÓN**.  
**Autoridad habilitante:** Resoluciones expresas del responsable del **19/11/2026** y **20/11/2026**:
- **Decisión 1 (`Gov PR #73`):** integrada exclusivamente de forma documental en `leandrito369/ale-milanesas` `main` (`e3f4ee5a752b29329117c7bb097e2a851ea60ca5`), preservando íntegras `RD-I302-003..011` (`#55–#63`, sin cerrar ninguna PR histórica).
- **Decisión 2 (Fronteras A y B):** corregidas como **PROPUESTAS, NO RATIFICADAS**, verificadas en el DDL candidato de laboratorio.
- **Decisión 3 (`V4`, `L02`, `R09.1`):** perfeccionamiento exclusivo dentro del laboratorio aislado de PR `#101` (**`0` cambios en `src/main/**`**), eliminando el cast inseguro `BIGINT -> INTEGER` en `L02`/`A2` y acreditando la estrategia de transición sin colisión `23505` frente a escritores legacy `MAX+1` / `2929001`.
- **Invariantes preservadas:** `S10 = NO ACREDITADO` (tratamiento autónomo de G-ARQ Nivel 2), `G0` y `source-lock.md` intactos, `V1–V3` intactas, `UC-07` físico (`#47/#48`) y `PE-3` offline excluidos, Demo intacta e Issue `#28` pausada.

---

## 1. Snapshot congelado y trazabilidad normativa (verificado 20/11/2026)

| Elemento | Repositorio / ruta | SHA / referencia | Estado |
|---|---|---|---|
| **Gobierno `main` actualizado** | `leandrito369/ale-milanesas` (`#67→#66→#65→#70→#71→#72` + **`#73`**) | `e3f4ee5a752b29329117c7bb097e2a851ea60ca5` | **Integrado** (10 archivos añadidos por `#73`, 0 PR cerradas) |
| **Aplicación `main` base** | `leandrito369/ale-milanesas-app` (`P3`: `#96 → #88 → #91 → #87`) | `21db1f536d0d7153e5d4abb721115778dadf14b3` | **Integrado** |
| **G0 `source-lock`** | `RD-B0-G0-001` (64 CU / 67 operaciones / RBAC 67×8 / BC07 15 filas) | `source_commit=1263ffc1` / `governance_commit=27738b08` | **Invariable** |
| **PR históricas `#55–#63`** | `leandrito369/ale-milanesas` | Preservadas byte a byte en `main@e3f4ee5a` | `OPEN` (sin cierre automático) |
| **Antecedente C3-A.3** | `leandrito369/ale-milanesas-app` PR `DRAFT #89` (`642abd65`) | C3-03, C3-04, C3-08 (R+P sobre V1–V3) y C3-05 (D) | `OPEN / DRAFT` |
| **UC-07 CR-C / CR-F** | `leandrito369/ale-milanesas` PR `#47` (`9c0a54e4`) y `#48` (`fda51c92`) | Bloqueadas (`#48` §15.12 choca con `RCG-02`; `H/RT-UC07-04` abierta) | **FUERA DE ALCANCE** |
| **Salvedad `S10`** | G-ARQ Nivel 1 | **NO ACREDITADO** | Conservada explícitamente |

---

## 2. Artefactos de laboratorio en PR `#101` (`0` cambios en `src/main/**`)

1. **`src/test/resources/gfis/V4_CANDIDATE__pe1_pe2_imp01_lab.sql`** — SQL candidato refinado (`9` tablas de laboratorio: `RECONOCIMIENTO_ENTREGA_PE1`, `RECORRIDO_REPARTO`, **`RECORRIDO_ASIGNACION`** pre-`ENTREGA`, `RECORRIDO_ENTREGA`, `CIERRE_RECORRIDO_PE2` con máquina de estados estricta `VARCHAR(40)` e índice único terminal multi-estado, `COBRO_RENDICION_K2`, `APLICACION_RENDICION_K2`, `CUSTODIA_DIFERENCIA_RENDICION`, `EVENTO_IMPUTACION_FINANCIERA`), más `fn_gfis_advisory_lock_key(BIGINT)` (`hashtext`), secuencias `B2-HÍBRIDO` y guard SQL `D7-A` / `DC05-07-A`.
2. **`src/test/resources/gfis/V4_CANDIDATE_L02_ANULACION_LAB.sql`** — corrección SQL candidata aislada de `L02` **sin cast `BIGINT -> INTEGER`**: usa `pg_advisory_xact_lock(3202, hashtext(v_id_cliente::text))` seguido de `CLIENTE FOR UPDATE → COBRO FOR UPDATE` y revalidación post-espera, cargada exclusivamente en el contenedor aislado de `GfisL02AnulacionCandidataLabIntegrationTest`.
3. **`src/test/java/ar/edu/unam/alemilanesas/gfis/GfisLabSupport.java`** — soporte de laboratorio con bloqueo `A2` en 2 fases sin desbordamiento 64-bit (`bloquearA2LoteOrdenadoPorHash` en `int4 ASC` → `CLIENTE FOR UPDATE` en `BIGINT ASC`), helpers de Frontera A (`RECORRIDO_ASIGNACION`) y Frontera B (`resolverDiferenciaAdministrativaEnTx`), endurecimiento `C1-a`, `422 COBERTURA_NO_ACREDITADA` y 5 señales `RCG-06`.
4. **`GfisPreflightEIntegridadV4LabIntegrationTest.java`** (`6` tests — añade Frontera A pre-`ENTREGA`/reasignación `UC-43` y máquina de estados estricta Frontera B).
5. **`GfisConcurrenciaLocksGeneradoresLabIntegrationTest.java`** (`7` tests — añade prueba explícita de riesgo `23505` por mezcla descoordinada `MAX+1` vs `nextval()` y cutover seguro `B2`).
6. **`GfisCampanaC3AmpliadaLabIntegrationTest.java`** (`9` tests — `N2-T08`, `N2-T09`, `N2-T13`, `N2-T16`, `N2-T17`, `N2-T18`, `N2-T05`/`N2-T14`).
7. **`GfisL02AnulacionCandidataLabIntegrationTest.java`** (`3` tests en contenedor aislado — `N2-T10a` V1, `N2-T10b` L02 corregida y **`N2-T10c` `idCliente = 3_000_000_000L > Integer.MAX_VALUE` sin `22003`**).

**Total de tests de laboratorio `G-FIS`:** **25 tests ejecutables** en PostgreSQL 17.11 efímero (Testcontainers), sumados a la regresión completa de `ale-milanesas-app`.

---

## 3. Propuestas corregidas para Frontera A y Frontera B (**PROPUESTAS — NO RATIFICADAS**)

### 3.1 Frontera A — Recorrido explícito y persistente (`A1` corregida, **NO RATIFICADA**)
- **Permisos reales G0 (verificados en `B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv` y `AsignarRepartoService.java`):** `UC-43` es **exclusivamente `VENDEDOR`** (`ADMINISTRADOR = NR`). No se otorga `UC-43` a `ADMINISTRADOR` ni se crea una operación `UC-65` nueva en G0.
- **Pertenencia pre-`ENTREGA` (`RECORRIDO_ASIGNACION`):** enlaza `RECORRIDO_REPARTO(idRecorrido)` con `ASIGNACION_REPARTO(idPedido, numeroAsignacion)` mediante `estadoPertenencia ∈ {ACTIVA, DESVINCULADA_POR_REASIGNACION, DESVINCULADA_POR_REPROGRAMACION, DESVINCULADA_POR_CANCELACION, ENTREGADA}` e índice único parcial `uq_gfis_recorrido_asig_activa_pedido ON (idPedido) WHERE estadoPertenencia = 'ACTIVA'`. Valida coincidencia exacta de `idUsuarioRepartidor` y `estadoOperativo = 'ABIERTO'`.
- **Reasignaciones (`UC-43`) y entregas (`UC-05`):** al reasignar en `UC-43`, `ASIGNACION_REPARTO` anterior conserva historial intacto (`fechaHoraFin`), su fila en `RECORRIDO_ASIGNACION` pasa a `DESVINCULADA_POR_REASIGNACION` (inmutable) y la nueva asignación se vincula como `ACTIVA` al Recorrido `ABIERTO` del nuevo Repartidor. Al entregar (`UC-05`), `RECORRIDO_ENTREGA` valida consistencia con `RECORRIDO_ASIGNACION(ACTIVA)` y la marca `ENTREGADA`.
- **Sub-reglas pendientes de ratificación humana:** (i) si cada Repartidor tiene a lo sumo 1 `Recorrido` `ABIERTO` simultáneo (evitando parámetros nuevos en la firma congelada G0 de `UC-43`) y (ii) si el cierre de Recorrido en `UC-29` exige 0 Pedidos `PENDIENTE` activos en `RECORRIDO_ASIGNACION` (recomendado) o permite desvinculación explícita.

### 3.2 Frontera B — Cierre con 0 aplicaciones positivas y resolución de diferencias (`B1` corregida, **NO RATIFICADA**)
- **Distinción normativa obligatoria:** **resolución administrativa ≠ extinción financiera**.
- **Máquina de estados SQL (`estadoCierre VARCHAR(40)`):**
  - `CONFIRMADO` *(terminal)*: **exige** `importeDiferenciaFaltante = 0 AND importeCustodiaNoImputada = 0` (prohibido marcar `CONFIRMADO` con diferencias abiertas). Admite `0` aplicaciones positivas únicamente cuando `importeEsperado = 0 AND importeEfectivoRecibido = 0`.
  - `PENDIENTE_CONCILIACION` *(no terminal, único activo por Recorrido via `uq_gfis_unico_pendiente_recorrido`)*: **exige** `(importeDiferenciaFaltante > 0 OR importeCustodiaNoImputada > 0)` y `motivoDiferencia`. Con `0` aplicaciones positivas genera `0` `COBRO`, `0` `MOVIMIENTO_CAJA` de venta y conserva `V_e^{vig}` intacto.
  - `CERRADO_CON_DIFERENCIA_RESUELTA` *(terminal)*: solo alcanzable vía transición única `PENDIENTE_CONCILIACION → CERRADO_CON_DIFERENCIA_RESUELTA` por `ADMINISTRADOR HABILITADO`, con `tipoResolucionAdmin` tipificado, `motivoResolucionAdmin` y `fechaHoraResolucion`, **sin mutar montos, identidad ni `reciboHistoricoJson`**, **sin crear `COBRO`/`MOVIMIENTO_CAJA`**, **sin extinguir `V_e^{vig}`**, **sin segundo cierre** y **sin recupero post-cierre (`E`)**.
- **Restricciones SQL verificadas en laboratorio:** `uq_gfis_unico_cierre_terminal_recorrido` cubre `('CONFIRMADO', 'CERRADO_CON_DIFERENCIA_RESUELTA')`; `CONFIRMADO` y `CERRADO_CON_DIFERENCIA_RESUELTA` son 100 % inmutables; `PENDIENTE_CONCILIACION → CONFIRMADO` está prohibida; abrir nueva conciliación sobre recorrido terminal está prohibido.

---

## 4. Compatibilidad `L02` (`BIGINT`) y estrategia de transición `A2/B2` frente a `MAX+1` / `2929001`

1. **Eliminación de cast `BIGINT -> INTEGER` (`L02` y `A2`):** se reemplazó `v_id_cliente::integer` y `Math.toIntExact(recursoId)` por `pg_advisory_xact_lock(ns_int4, hashtext(id_bigint::text))` + bloqueo en dos fases (`A2` ordenado por `int4 ASC`, luego `CLIENTE FOR UPDATE` en `BIGINT ASC`). Verificado en `N2-T10c` con `idCliente = 3_000_000_000L` (`> Integer.MAX_VALUE`): **`0` desbordamientos `22003`** y exclusión mutua intacta.
2. **Transición sin colisión `23505` desde `MAX+1`/`2929001`:**
   - **Riesgo acreditado empíricamente (`compatibilidadTransicionLegacy...`):** mezclar un escritor legacy `MAX+1` (`CobroManualService` / `RegistrarMovimientoCajaService` `#91`) con un escritor `nextval('seq_gfis_*')` sobre la misma tabla produce `SQLSTATE 23505`.
   - **Estrategia obligatoria en dos pasos:**
     - **Paso `G-FIS` (`V4`):** crea secuencias `seq_gfis_cobro_id`, `seq_gfis_mov_cuenta_id`, `seq_gfis_mov_caja_id` y las inicializa `> MAX(id)`. Ningún servicio nuevo puede emitir `nextval()` en paralelo con `MAX+1` en producción.
     - **Paso `R09.1` (cutover atómico en una sola entrega):** migra simultáneamente `ProtocoloIdentificadoresFinancieros`, `CobroManualService` (`registrarCobro` y `anularCobroManual`) y `RegistrarMovimientoCajaService` (`#91`) a `nextval('seq_gfis_*')` (resincronizando `setval > MAX` al aplicar el corte) y recién entonces retira el cuello global `2929001`.

---

## 5. Matriz de estado `DC-10 / C3`, `C-04` y `E-01..07` (Ratificado / Integrado / Probado / Pendiente)

| Elemento | Alcance normativo | Ratificado | Integrado en `main` | Probado | Pendiente |
|---|---|---|---|---|---|
| **`DC-10 / C3`** | Orden global de locks `A2-GLOBAL`, generadores `B2-HÍBRIDO`, matriz `N2-T01..T20` y mitigación `L02` | **Parcial (arquitectura)** — `T1+K2` ratificado (`#70`); G-ARQ N2 (`#71/#72`) aceptado como base autónoma (`S10=NO ACREDITADO`); **sin ratificación física `G-FIS` todavía** | **Sí (documental en Gobierno `e3f4ee5a`)**; laboratorio SQL en PR `DRAFT #101` (no en `app/main`) | **Parcial (laboratorio PG 17.11)** — `25` tests en `#101` (`N2-T01..T10c, T13..T19` en nivel P/R+P); `T11/T12/T20` diferidos | Promoción `V4` (`G-FIS`), cutover `A2/B2` en `CobroManualService`/`UC-22` (`R09.1`), validación con `UC-05`/`UC-29 rendición` reales (`R09.2–R09.5`) |
| **`C-04`** | Idempotencia de `anularCobroManual` (`ART-GCON-D1`: `claveIdempotenciaAnulacion`, retorno de contraasientos y normalización de motivo) | **Sí (conceptual)** — `ART-GCON-D1` en `#66` (`ACT-GCON-R08.6-001`) y `RCG-05` en `#64` | **Sí (documental en Gobierno `e3f4ee5a`)**; **`NO` en código `app/main`** (`CobroManualService.anularCobroManual` aún no tiene `claveIdempotenciaAnulacion`) | **No probado** (falta soporte de clave idempotente de anulación y tests de replay/conflicto) | Definir regla determinista de normalización del motivo, soporte físico/servicio `C-04` en `R09.1` y tests dedicados |
| **`E-01`** | `REV04B §2` — separación `UC-05` (declaración `REPARTIDOR`) vs `UC-29` rendición (`ADMIN` exclusivo); sin `UC-14` a `ADMIN` | **Sí (comercial `G-COM` + editorial `G-CON #65`)** | **Sí** (`ale-milanesas/main@57590500` / `e3f4ee5a`) | **Parcial (SQL/RBAC lab en `#101`)** — trigger rechaza `REPARTIDOR`/`VENDEDOR` en `CIERRE_RECORRIDO_PE2` | Implementación de `UC-05` (`R09.2`) y `UC-29 rendición` (`R09.3`) + pruebas HTTP/RBAC negativas |
| **`E-02`** | `REV04B §2, §§5–6` — cargo real `DC-01/02/04-A`, `PE-1` (`K_e, E_e, V_e^0`), `IMP-B`/`IMP-C`, `EX-01` y **gate bloqueado `UC-07`** | **Sí (comercial + editorial con reserva `DGCON-02`)** | **Sí** (`ale-milanesas/main@57590500` / `e3f4ee5a`) | **Parcial (tramo originales en lab `#101`)** — `PE-1`, `EX-01`, `IMP-B/C`, `N2-T08/T09/T17/T18`; **`UC-07` excluido/no probado** | Servicio `UC-05` originales (`R09.2`); resolución física independiente de `CR-C #47` / `CR-F #48` (`§15.12`) / `RT-UC07-04` (`R09.4`) |
| **`E-03`** | `REV04B §3` — `UC-06` (`VENDEDOR`/`REPARTIDOR`), liberación atómica de reservas elegibles en `IMP-B` | **Sí (comercial + editorial)** | **Sí** (`ale-milanesas/main@57590500` / `e3f4ee5a`) | **Parcial (SQL lab)** — evento `LIBERACION_ANTICIPO` y desbloqueo `DC05-07-A` probados en `#101`; **`UC-06` no existe aún en `app/main`** | Implementar servicio `UC-06` con liberación atómica de reservas, excedentes y desvinculación `RECORRIDO_ASIGNACION` (`R09.2+`) |
| **`E-04`** | `REV05 §2` — `UC-29` `registrarCobro` (`ADMINISTRACION`/`REPARTO`/`ONLINE`), `T1+K2`, custodia segregada y arqueo 3 vistas | **Sí (comercial + editorial + `T1/K2 #70`)** | **Sí** (`ale-milanesas/main@57590500` / `e3f4ee5a`) | **Parcial (SQL lab `#101`)** — `K2`, conservación `A_e + V_e^{vig} = V_e^0`, `D1`, custodia segregada, Frontera B máquina de estados | Ratificar Fronteras A y B; servicio `UC-29 rendición` + arqueo 3 vistas (`R09.3`); soporte reserva `IMP-B` en `CobroManualService` (`R09.1`) |
| **`E-05`** | `REV05 §3` — `UC-29` `anularCobroManual` (`D7-A`, `DC05-07-A`, contraasientos e idempotencia `C-04`) | **Sí (comercial + editorial + `ART-GCON-D1`)** | **Sí** (`ale-milanesas/main@57590500` / `e3f4ee5a`) | **Parcial (SQL lab `#101`)** — `D7-A`, `DC05-07-A`, `L02` `CLIENTE→COBRO` y `BIGINT > 2^31-1` probados; **`C-04` clave idempotente no probada** | Promover guardas + `L02` en `V4` (`G-FIS`), implementar `C-04` y replay tras `ANULADO` en `CobroManualService` (`R09.1`) |
| **`E-06`** | `REV05 §1` — `UC-14` (`VENDEDOR` exclusivo): `saldoConfirmado`, `importeTransitoValidado`, `saldoExigibleProvisional` | **Sí (comercial + editorial)** | **Sí** (`ale-milanesas/main@57590500` / `e3f4ee5a`) | **No probado a nivel servicio** (solo base física `PE-1`/`IMP-B` en `#101`; **`UC-14` no existe aún en `app/main`**) | Implementar `UC-14` (solo `VENDEDOR`, `ADMIN=X`) y verificar derivación sin doble efecto (`R09.3`) |
| **`E-07`** | Concordancia `RD-I302-001` (`#44`), `V1+V2+V3 = 62 tablas`, `PE-1+PE-2+IMP-01` online integrados, `RCG-06` 5 señales | **Sí (comercial + editorial)** | **Sí** (`ale-milanesas/main@57590500` / `e3f4ee5a`) + `RD-I302-003..011` en `e3f4ee5a` | **Parcial (lab `#101`)** — preflight `V1–V3` + 5 señales `RCG-06` + `IMP-C` sin backfill probados en sintéticos (`N2-T17`) | Ejecutar preflight `RCG-06` sobre cualquier BD destino real antes de aplicar `V4`; reconciliar `UC-15` y `UC-03` (`RD-F04-006`) en `R09.1` |

---

## 6. Matriz de cobertura `N2-T01..T20` (resumen actualizado con 25 tests de laboratorio)

| Grupo | Cobertura en PR `#101` (`25` tests PG 17.11) | Estado |
|---|---|---|
| **`N2-T01..T07`** (K2, T1 ACID, replay `C1-a`, 2 ADMIN, Frontera A/B, custodia) | `GfisPreflightEIntegridadV4LabIntegrationTest` (`6` tests) + `GfisConcurrenciaLocksGeneradoresLabIntegrationTest` (`7` tests) + `GfisCampanaC3AmpliadaLabIntegrationTest` (`n2T05_T14`) | **PASS PARCIAL** *(laboratorio SQL/P)* |
| **`N2-T08, N2-T09`** (`C3-03` `UC-43` vs `PE-1`; `C3-08` `UC-29` + `UC-22` vs `PE-1/IMP-B`) | `GfisCampanaC3AmpliadaLabIntegrationTest` (`4` tests) | **PASS PARCIAL** *(R+P en laboratorio)* |
| **`N2-T10a/b/c`** (`L02` V1 baseline, corrección `CLIENTE→COBRO`, **`BIGINT > Integer.MAX_VALUE`**) | `GfisL02AnulacionCandidataLabIntegrationTest` (`3` tests en contenedor aislado) | **PASS PARCIAL** *(aislado; `V1` intacta)* |
| **`N2-T13..T19`** (multi-Cliente `[A,B]` vs `[B,A]`, `D1`, `IMP-C`, `4×8` + `40P01` retry, 5 señales `RCG-06`, `422`, custodia, **transición `MAX+1` vs `B2`**) | `GfisCampanaC3AmpliadaLabIntegrationTest` + `GfisConcurrenciaLocksGeneradoresLabIntegrationTest` | **PASS PARCIAL** *(laboratorio SQL/P y R+P)* |
| **`N2-T11, N2-T12, N2-T20`** (`UC-07` / `CR-C` / `CR-F` / E2E global) | Excluidos expresamente del corte `G-FIS` Pedidos originales | **DIFERIDO JUSTIFICADO** (`R09.4` / `R09.5`) |

---

## 7. Dictamen verificable `GO / NO-GO` para `G-FIS` (cierre de revisión `R08.6`)

- **Dictamen actual recomendado para `G-FIS` (promoción de `V4` a `src/main/resources/db/migration/`):** **`GO CONDICIONADO (NO-GO automático hasta ratificación de Fronteras A y B)`**:
  - **Qué SÍ está acreditado técnicamente:** DDL candidato `V4` (`9` tablas) + `L02` segura para 64-bit `BIGINT` + `25/25` tests PG 17.11 en laboratorio + `0` modificaciones en `src/main/**` + preservación documental `#73` en `ale-milanesas/main@e3f4ee5a`.
  - **Condición única para pasar a `GO` de `G-FIS`:** ratificación explícita del responsable sobre las propuestas corregidas de **Frontera A** (`RECORRIDO_ASIGNACION`, 1 Recorrido `ABIERTO` por Repartidor, condición de Pedidos `PENDIENTE` al cierre) y **Frontera B** (máquina de estados de 3 valores con `CERRADO_CON_DIFERENCIA_RESUELTA` solo administrativo y conservación de `V_e^{vig}`).
- **Puerta `G-DEV / R09.1`:** **INDEPENDIENTE Y POSTERIOR A `G-FIS`** — requiere además autorización propia para modificar `src/main/java/**` (cutover atómico `A2/B2` en `CobroManualService`/`RegistrarMovimientoCajaService`, implementación de `C-04` e `IMP-B` en `CobroManualService`, y reconciliación `UC-15`/`UC-03`).
- **PR `#101`:** permanece **`OPEN / DRAFT / NO MERGE / NO DESPLEGAR`**. Issue `#28` permanece pausada. Demo intacta.
