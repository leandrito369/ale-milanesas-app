# G-FIS (Fase A + Fase B ampliada) — Expediente de preflight, prototipo SQL candidato y campaña C3 en laboratorio PostgreSQL

**Fecha inicial:** 19/11/2026 · **Ampliación C3 / R08.6:** 20/11/2026 (UTC−03)  
**Estado:** **FASE A + FASE B EN LABORATORIO AISLADO (PR `DRAFT #101` / NO MERGE)** · **FASE C (`G-FIS` promoción `V4`) Y `G-DEV / R09.1` NO AUTORIZADAS**.  
**Autoridad habilitante:** Resolución expresa del responsable del **19/11/2026** + Instrucción de cierre controlado `R08.6` del **20/11/2026**:
- **`S10 = NO ACREDITADO`:** G-ARQ Nivel 2 (`#71` y `#72`, integradas en `ale-milanesas/main@57590500f3b6`) aceptado como fundamento autónomo para evaluar `G-FIS`, sin certificación retrospectiva de Nivel 1.
- **Restricciones estrictas cumplidas:** **`0` archivos modificados en `src/main/**`** (`V1–V3`, servicios Java, `application.yml`, Demo y `source-lock.md` 100 % intactos). Ninguna migración promovida a `src/main/resources/db/migration/`. Excluidos expresamente: UC-07 físico (`#47/#48`), `PE-3` offline, recupero post-cierre (`E`), medios distintos de efectivo en `PE-2` (`D1`), cambios sobre Demo e infraestructura/despliegue (Issue `#28` pausada).

---

## 1. Snapshot congelado y trazabilidad normativa (verificado 20/11/2026)

| Elemento | Repositorio / ruta | SHA / referencia | Estado |
|---|---|---|---|
| **Gobierno `main`** | `leandrito369/ale-milanesas` (`P1`: `#67 → #66 → #65 → #70 → #71 → #72`) | `57590500f3b64f521c8e36d3a62206f4619f6174` | Integrado |
| **Aplicación `main` base** | `leandrito369/ale-milanesas-app` (`P3`: `#96 → #88 → #91 → #87`) | `21db1f536d0d7153e5d4abb721115778dadf14b3` | Integrado |
| **G0 `source-lock`** | `RD-B0-G0-001` (64 CU / 67 operaciones) | `source_commit=1263ffc1` / `governance_commit=27738b08` | Invariable |
| **Consolidación `#55–#63`** | `leandrito369/ale-milanesas` PR `DRAFT #73` (`4b0ab50`) | Preserva byte a byte `RD-I302-003..011` + matriz de procedencia | `OPEN / DRAFT` (no mergeada ni cerradas `#55–#63`) |
| **Antecedente C3-A.3** | `leandrito369/ale-milanesas-app` PR `DRAFT #89` (`642abd65`) | C3-03, C3-04, C3-08 (R+P sobre V1–V3) y C3-05 (D) | `OPEN / DRAFT` |
| **UC-07 CR-C / CR-F** | `leandrito369/ale-milanesas` PR `#47` (`9c0a54e4`) y `#48` (`fda51c92`) | Bloqueadas (`#48` §15.12 choca con `RCG-02`; `H/RT-UC07-04` abierta) | **FUERA DE ALCANCE** |
| **Salvedad `S10`** | G-ARQ Nivel 1 | **NO ACREDITADO** | Conservada explícitamente |

---

## 2. Artefactos de laboratorio en PR `#101` (`0` cambios en `src/main/**`)

1. **`src/test/resources/gfis/V4_CANDIDATE__pe1_pe2_imp01_lab.sql`** — SQL candidato de laboratorio (`PE-1`, `PE-2`, `IMP-01`, `C1-EXPLÍCITO`, `K2`, custodia segregada, secuencias `B2-HÍBRIDO` y guard SQL `D7-A` / `DC05-07-A`).
2. **`src/test/resources/gfis/V4_CANDIDATE_L02_ANULACION_LAB.sql`** — corrección SQL candidata aislada de `L02` (`fn_mr3_anular_cobro_manual` en orden `A2(NS_CLIENTE) → CLIENTE FOR UPDATE → COBRO FOR UPDATE` con revalidación post-espera, `D7-A` y `DC05-07-A`), cargada **exclusivamente** en el contenedor PostgreSQL dedicado de `GfisL02AnulacionCandidataLabIntegrationTest` sin editar `V1` ni contaminar el resto de la suite.
3. **`src/test/java/ar/edu/unam/alemilanesas/gfis/GfisLabSupport.java`** — soporte de laboratorio con endurecimiento `C1-a` (validación previa de rol `ADMINISTRADOR HABILITADO` en BD y coincidencia estricta de `idRecorrido` antes de devolver replay histórico), rechazo contractual `422 COBERTURA_NO_ACREDITADA` y evaluador de las **5 señales literales `RCG-06`**.
4. **`src/test/java/ar/edu/unam/alemilanesas/gfis/GfisPreflightEIntegridadV4LabIntegrationTest.java`** (`5` tests).
5. **`src/test/java/ar/edu/unam/alemilanesas/gfis/GfisConcurrenciaLocksGeneradoresLabIntegrationTest.java`** (`6` tests).
6. **`src/test/java/ar/edu/unam/alemilanesas/gfis/GfisCampanaC3AmpliadaLabIntegrationTest.java`** (`9` tests — `N2-T08`, `N2-T09`, `N2-T13`, `N2-T16`, `N2-T17`, `N2-T18`, endurecimiento `N2-T05`/`N2-T14`).
7. **`src/test/java/ar/edu/unam/alemilanesas/gfis/GfisL02AnulacionCandidataLabIntegrationTest.java`** (`2` tests en contenedor aislado — `N2-T10a` línea base `V1` y `N2-T10b` corrección candidata `L02`).

**Total de tests de laboratorio `G-FIS`:** **22 tests ejecutables** sobre PostgreSQL 17.11 efímero (Testcontainers), sumados a la regresión completa existente de `ale-milanesas-app`.

---

## 3. Matriz de cobertura 1-a-1 `N2-T01..T20` (clasificación honesta R / P / D)

> **Convención de niveles:** **R** = servicio productivo ya en `main`; **P** = prototipo SQL/coordinador en laboratorio sobre `V4_CANDIDATE`; **D** = documental/diferido.  
> Ningún escenario con servicios de negocio aún inexistentes en `src/main/java/**` (`UC-05`, `UC-14`, `UC-29 rendición`, `UC-07`) se declara `PASS INTEGRAL` definitivo de aplicación; los estados reflejan el alcance real probado en laboratorio SQL/Testcontainers.

| ID | Escenario y requisito contractual | Nivel | Clase y método(s) de prueba | Resultado observable y aserciones efectivas | Estado | Riesgo pendiente y hito destino |
|---|---|---|---|---|---|---|
| **N2-T01** | Un Cliente, dos Entregas `K2` (`ART-GCON-C2`) | P | `GfisPreflightEIntegridadV4LabIntegrationTest.cierreT1K2MultiClienteMultiEntregaConservaInvariantesYRollbackEsAtomico` | 2 Entregas del Cliente A consolidadas en 1 único `COBRO REPARTO` (`170.00`), suma exacta = aplicaciones, `A_e + V_e^{vig} = V_e^0`. | **PASS PARCIAL** *(laboratorio SQL/P)* | Falta servicio productivo `UC-29 rendición` en `R09.3`. |
| **N2-T02** | Dos Clientes, un Recorrido `T1` (`ART-GCON-C2`) | P | `GfisPreflightEIntegridadV4LabIntegrationTest.cierreT1K2MultiClienteMultiEntregaConservaInvariantesYRollbackEsAtomico` + `controlesNegativosK2...` | 2 Cobros separados (`170.00` y `250.00`), rechazo FK de Cobro multicliente, sin doble Cuenta/Caja. | **PASS PARCIAL** *(laboratorio SQL/P)* | Falta servicio productivo `T1` en `R09.3`. |
| **N2-T03** | Fallo tras primer paso antes de `COMMIT` (`T1` ACID) | P | `GfisPreflightEIntegridadV4LabIntegrationTest.cierreT1K2MultiClienteMultiEntregaConservaInvariantesYRollbackEsAtomico` + `controlesNegativosK2...` | Exceso de aplicación y desajuste `K2-CONSERVACION` al `COMMIT` revierten el 100 % (`0` Cobro, `0` Caja, `0` Cierre). | **PASS PARCIAL** *(laboratorio SQL/P)* | Verificar en servicio Spring `@Transactional` de `R09.3`. |
| **N2-T04** | Pérdida de ACK tras `COMMIT` y replay histórico (`C1-a`) | P | `GfisConcurrenciaLocksGeneradoresLabIntegrationTest.replayIdempotenteC1aRetornaReciboHistoricoYDetectaConflictoDeHuella` | Reintento con misma UUID + mismo Recorrido + misma huella retorna `reciboHistoricoJson` idéntico (`replay=true`), `0` nuevo Cobro/Caja. | **PASS PARCIAL** *(laboratorio SQL/P)* | Simula pérdida de ACK a nivel Tx; falta prueba HTTP/red de `R09.3`. |
| **N2-T05** | Misma UUID/otra huella (`409`), otro Recorrido (`409`), actor no-ADMIN (`403`) y nueva UUID sobre cerrado | P | `GfisConcurrenciaLocksGeneradoresLabIntegrationTest.replayIdempotente...` + `GfisCampanaC3AmpliadaLabIntegrationTest.n2T05_T14_ReplayOtroRecorridoActorNoAdminYDosMediosMismoClienteRechazados` + `GfisPreflightEIntegridad...` | Rechaza huella distinta (`409`), otro Recorrido (`409`, sin fuga), actor `REPARTIDOR`/`VENDEDOR` con UUID válida (`403` en BD) y nueva UUID sobre cerrado (`23505`). | **PASS PARCIAL** *(laboratorio SQL/P)* | Validación en BD de `idAdmin` **no** sustituye sesión HTTP/Spring Security real (`R09.3`). |
| **N2-T06** | Dos `ADMINISTRADOR` simultáneos sobre mismo Recorrido | P | `GfisConcurrenciaLocksGeneradoresLabIntegrationTest.dosAdministradoresSimultaneosConClavesDistintasSoloUnoConfirmaYSeConservaPe1` | Barrera concurrente: exactamente `1` confirma; el 2º recibe `SQLSTATE 23505` en `uq_gfis_unico_cierre_confirmado_recorrido` (`0` `40P01`, `V_e^{vig}=0`). | **PASS PARCIAL** *(laboratorio SQL/P)* | Mapeo de `23505` SQL a respuesta funcional `RECORRIDO_YA_CERRADO` + sesión HTTP en `R09.3`. |
| **N2-T07** | Diferencias abiertas / faltante / custodia / `0` aplicaciones (`RD-F04-002/005`) | P | `GfisPreflightEIntegridadV4LabIntegrationTest.seguridadRbacSoloAdminD1SoloEfectivoDc0507AYCierreSinAplicacionesPositivas` + `cierreT1K2...` | Cierre `0` aplicaciones queda `PENDIENTE_CONCILIACION`, `0` Cobro/Caja ficticio, `V_e^{vig}` intacto; custodia segregada (`420` Caja vs `430` recibido). | **PASS PARCIAL** *(laboratorio SQL/P)* | Pendiente definición comercial expresa de Frontera B (Paso 2B) y servicio `R09.3`. |
| **N2-T08** | `C3-03`: `UC-43` real frente a `UC-05`/`PE-1` sobre `V4_CANDIDATE` | R + P | `GfisCampanaC3AmpliadaLabIntegrationTest.n2T08a_C303Inversion...` y `n2T08b_C303OrdenAlineado...` | `T08a` reproduce `40P01` real en inversión `ASIGNACION→PEDIDO`; `T08b` con orden `CLIENTE→PEDIDO→ASIGNACION→PE-1` serializa en `pg_locks` y `UC-43` revalida `ENTREGADO` sin `40P01`. | **PASS PARCIAL** *(R+P en laboratorio)* | Revalidar con servicio `UC-05` real en `R09.2`/`R09.5`. |
| **N2-T09** | `C3-08`: `UC-29` real frente a `UC-05`/`PE-1`/`IMP-B` y `UC-22` (`#91`) | R + P | `GfisCampanaC3AmpliadaLabIntegrationTest.n2T09a_C308Inversion...` y `n2T09b_C308OrdenAlineado...` | `T09a` reproduce `40P01` real en inversión `PEDIDO→CLIENTE`; `T09b` serializa `UC-05/PE-1/IMP-B` + `CobroManualService` + `RegistrarMovimientoCajaService` sin `40P01` ni `23505`, saldo `+100.00` e `IMP-A=20.00`. | **PASS PARCIAL** *(R+P en laboratorio)* | Migrar coordinadamente `CobroManualService` y `UC-22` a `A2`/`B2` en `R09.1`. |
| **N2-T10** | Anulación (`L02`), `D7-A`, `DC05-07-A` y cierre `T1`/`K2` | R + P | `GfisL02AnulacionCandidataLabIntegrationTest.n2T10a_LineaBaseV1...` y `n2T10b_CorreccionCandidataL02...` *(contenedor aislado)* | `T10a` reproduce `40P01` real de `V1` (`COBRO→CLIENTE`); `T10b` valida corrección candidata `CLIENTE→COBRO` con revalidación, regresiones `CobroManualService`, `D7-A`, `DC05-07-A` e intercalado `T1/K2` (`0` `40P01`, saldo `-200.00`). | **PASS PARCIAL** *(aislado; `V1` intacta)* | Requiere autorización `G-FIS` Fase C para promover reemplazo `L02` en `V4` y adaptar `CobroManualService` (`R09.1`). |
| **N2-T11** | `C3-05/06/07`: `UC-28`/`UC-07`/`UC-41` y stock/excedente | D | — *(excluido de `G-FIS` Pedidos originales)* | No ejecutado; `PE-1` fija `estadoContencionUc07='SOLO_ORIGINALES'`. | **DIFERIDO JUSTIFICADO** | Bloqueado por `CR-C #47`, `CR-F #48` (conflicto `RCG-02`) y `H/RT-UC07-04` → `R09.4`. |
| **N2-T12** | `C3-04`: `UC-19`/`UC-07` snapshot y espera advisory | D | Antecedente R+P en PR `#89` (`C304Uc19VsUc07ParteA...`); excluido de `G-FIS` originales | No reejecutado en `#101` por exclusión estricta de `UC-07`. | **DIFERIDO JUSTIFICADO** | Pertenece al gate físico de `UC-07` → `R09.4`. |
| **N2-T13** | Multi-Cliente con orden invertido `[A,B]` vs `[B,A]` y secuencias `B2` | P | `GfisCampanaC3AmpliadaLabIntegrationTest.n2T13_MultiClienteOrdenInvertidoABvsBASinCiclosNiDuplicacion` + `GfisConcurrencia...generadoresB2Hibrido...` | Dos cierres concurrentes `[A,B]` vs `[B,A]` sin `2929001` (B2 puro) serializan por `CLIENTE ASC` sin `40P01` ni `23505`; ordinales consecutivos; conservación exacta. | **PASS PARCIAL** *(laboratorio SQL/P)* | Requiere migración coordinada de escritores legacy `MAX+1` en `R09.1`. |
| **N2-T14** | Un Cliente con dos medios de pago en mismo cierre (`D1`) | P | `GfisPreflightEIntegridad...seguridadRbacSoloAdminD1...`, `controlesNegativosK2...` y `GfisCampanaC3...n2T05_T14...` | Rechaza `TRANSFERENCIA` en cierre y en `COBRO_RENDICION_K2`, y rechaza segundo Cobro (`EFECTIVO`+`TRANSFERENCIA`) para mismo Cliente/Cierre. | **PASS PARCIAL** *(solo alcance `D1` efectivo)* | Multimedio (`D2`) queda fuera de alcance aprobado; requeriría RD específica. |
| **N2-T15** | Revisión `UC-14`/`UC-15` y contención `IMP-C` | P / D | Contención `IMP-C` (`V_e^0=0`) e `IMP-B`/`IMP-A` probadas en `GfisPreflightEIntegridad...pe1FormulaR084...` y `n2T17...` | `IMP-C` impide inventar `V_e^0 > 0`; `REEMBOLSO RECHAZADO` no veta disponibilidad. | **PASS PARCIAL** *(controles físicos `IMP-C`)* | Reconciliación de `EvaluadorCategoriaComercialInterno` (`UC-15`) y servicio/RBAC `UC-14` corresponden a `R09.1`/`R09.3`. |
| **N2-T16** | Recorridos grandes (`4×8`), aborto `40P01` y recuperación | P | `GfisCampanaC3AmpliadaLabIntegrationTest.n2T16_RecorridoGrande4Clientes8EntregasYRecuperacionTras40P01Forzado` | Reproduce aborto real `40P01`, acredita rollback íntegro (`0` residuos), reintenta en nueva Tx con misma UUID (4 Clientes / 8 Entregas, latencia acotada, 4 Cobros `K2`, 8 aplicaciones). | **PASS PARCIAL** *(cardinalidad finita 4×8)* | No certifica aciclicidad global ni reemplaza pruebas de carga/retry productivo de `R09.3`/`R09.5`. |
| **N2-T17** | Cinco señales exactas `RCG-06` y contención `IMP-C` sin backfill | R + P | `GfisCampanaC3AmpliadaLabIntegrationTest.n2T17_CincoSenalesExactasRcg06YContencionSelectivaImpCSinBackfill` + `preflightVerifica...` | Prueba individualmente las 5 señales literales `A, B, C, D, E` de `ACTA_G_COM_001`/`E-07`, aplica `IMP-C` (`V_e^0=0`) sin backfill de `COBRO.idPedido` ni mutar cobros históricos. | **PASS PARCIAL** *(datos sintéticos de laboratorio)* | Ejecutar reporte de preflight `RCG-06` sobre cualquier BD destino real antes de migrar. |
| **N2-T18** | Cobertura insuficiente `422 COBERTURA_NO_ACREDITADA` + diagnóstico `A1` | P | `GfisCampanaC3AmpliadaLabIntegrationTest.n2T18_CoberturaInsuficiente422RollbackTotalDeIntactoYDiagnosticoSeparado` | Rechaza `120 > 100` con `422`, rollback total (`0` Cierre/Cobro/Caja/Cuenta), `D_e` y `V_e^{vig}` intactos y legibles en Tx de diagnóstico separada; diferenciado de `409` y `40P01`. | **PASS PARCIAL** *(laboratorio SQL/P)* | Mapear `422` y diagnóstico separado en capa de servicio/HTTP de `R09.3`. |
| **N2-T19** | Custodia segregada + arqueo en tres vistas (`RCG-04`) | P / D | `GfisPreflightEIntegridadV4LabIntegrationTest.cierreT1K2...` + `seguridadRbac...` | Acredita invariante físico: `CUSTODIA_DIFERENCIA_RENDICION` no duplica ingreso en `MOVIMIENTO_CAJA` (`420` Caja vs `430` recibido) y exige `ADMINISTRADOR HABILITADO`. | **PASS PARCIAL** *(invariantes físicos)* | Ecuación filtrada de arqueo en 3 vistas y endpoints/UI corresponden a `R09.3` / `B5`. |
| **N2-T20** | Regresión global, HTTP, RBAC, BC-06 y E2E del sistema final | D | — | Fuera de alcance de laboratorio SQL `G-FIS`. | **DIFERIDO JUSTIFICADO** | Corresponde a `R09.1–R09.5` y cierre de `I3` / `B8` (sin ampliar Demo). |

---

## 4. Riesgos de concurrencia y límites honestos de la evidencia

1. **Un conjunto finito de intercalados exitosos NO demuestra aciclicidad global:** quedan fuera servicios aún no implementados (`UC-05`, `UC-07`, `UC-06`, `UC-21`, `UC-29 rendición`) y escritores externos no auditados.
2. **Transición `MAX+1`/`2929001` → `B2-HÍBRIDO`:** `CobroManualService` y `RegistrarMovimientoCajaService` (`#91`) continúan usando `MAX+1` en `main`. Activar secuencias `seq_gfis_*` en producción sin migrar coordinadamente ambos servicios en `R09.1` causaría `23505`.
3. **Autorización de sesión vs. validación en BD:** `GfisLabSupport` valida `ADMINISTRADOR HABILITADO` en BD e impide replay de otro Recorrido, pero la autenticación por token/sesión, revocación y `BC-07` corresponden a la capa de servicio/HTTP de `R09.3`.
4. **Fronteras comerciales abiertas (NO resueltas en DDL):** ciclo de vida operativo de `Recorrido` (Frontera A) y cierre con `0` aplicaciones positivas / tratamiento de resolución de diferencias (Frontera B) permanecen sujetos a ratificación expresa antes de promover `V4` a `src/main/resources/db/migration/`.
5. **Incidencias SonarCloud:** verificar en el reporte final de CI tanto el Quality Gate como el recuento y severidad de issues nuevos sobre el HEAD definitivo.

---

## 5. Estado de salidas (`G-FIS` Fase C y `G-DEV / R09.1`)

- **PR `#101`:** `OPEN / DRAFT / NO MERGE / NO DESPLEGAR`.
- **Promoción `V4` (`G-FIS` Fase C):** **NO AUTORIZADA** — requiere decisión expresa tras revisar la corrida final de CI, la consolidación `#73` (`#55–#63`), las Fronteras A y B y el plan de transición `L02`/`B2-HÍBRIDO`.
- **`G-DEV / R09.1`:** **NO AUTORIZADO** — puerta posterior e independiente a `G-FIS`.
