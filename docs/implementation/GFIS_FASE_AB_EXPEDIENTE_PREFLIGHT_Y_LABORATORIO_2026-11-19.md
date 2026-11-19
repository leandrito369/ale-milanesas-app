# G-FIS (Fase A + Fase B) — Expediente de preflight, prototipo SQL candidato y laboratorio PostgreSQL

**Fecha:** 19/11/2026 (UTC−03)  
**Estado:** **FASE A + FASE B EN LABORATORIO AISLADO (PR `DRAFT` / NO MERGE)** · **FASE C (`G-FIS` salida / promoción `V4`) NO ACREDITADA**.  
**Autoridad habilitante:** Resolución expresa del responsable del **19/11/2026**:
- **Primero (`S10`):** acepta G-ARQ Nivel 2 (`#71` y `#72`, integradas en `ale-milanesas/main@57590500f3b6`) como fundamento autónomo para evaluar `G-FIS`, conservando **`S10 = NO ACREDITADO`** (sin certificación retrospectiva de Nivel 1).
- **Segundo (`G-FIS`):** habilita exclusivamente expediente, preflight, prototipo de migración candidata en `src/test/resources/gfis/` y pruebas SQL/PostgreSQL/Testcontainers en `src/test/**`, mediante PR `DRAFT` de `ale-milanesas-app`.
- **Prohibiciones vigentes:** `src/main/**` permanece **100 % intacto** (`V1–V3`, `src/main/java/**`, `application.yml`, Demo, `source-lock.md`). No se promueve `V4` a `src/main/resources/db/migration/`, no se implementan servicios `R09`, no se incluye UC-07 físico ni `PE-3` offline, y el despliegue sigue pausado (Issue `#28`).

---

## 1. Fase A — Snapshot congelado y preflight técnico

### 1.1. SHAs congelados y trazabilidad normativa

| Fuente | Repositorio / ruta | SHA / versión | Estado |
|---|---|---|---|
| **Gobierno `main`** | `leandrito369/ale-milanesas` (`P1`: `#67 → #66 → #65 → #70 → #71 → #72`) | `57590500f3b64f521c8e36d3a62206f4619f6174` | Integrado (`P1`) |
| **Aplicación `main` base** | `leandrito369/ale-milanesas-app` (`P3`: `#96 → #88 → #91 → #87`) | `21db1f536d0d7153e5d4abb721115778dadf14b3` | Integrado (`P3`) |
| **G0 `source-lock`** | `RD-B0-G0-001` (64 CU / 67 operaciones) | `source_commit=1263ffc1` / `governance_commit=27738b08` | Invariable |
| **Autoridad comercial vigente** | `ACTA_G_COM_001` (`RCG-01..08`), `RD-F04-002/005`, `PR #65/#66/#67/#70/#71/#72` | En `ale-milanesas/main@57590500f3b6` | Ratificada |
| **Resguardo `#55–#63`** | PRs `RD-I302-003..011` (`OPEN/DRAFT`) | No integradas en `main` | **No se presumen integradas**; las reglas usadas en el laboratorio se sustentan en `ACTA_G_COM_001` (`RCG-01..08`) y `PR #65/#70/#71/#72` |
| **Salvedad `S10`** | G-ARQ Nivel 1 | **NO ACREDITADO** | Conservada explícitamente |

### 1.2. Preflight sobre `V1–V3` y dependencias de `#91` (`UC-22`)

1. **Esquema base `V1–V3` (62 tablas) inalterado:**
   - `V1__baseline_mr5.sql`: incluye `PEDIDO`, `DETALLE_PEDIDO`, `ASIGNACION_REPARTO`, `ENTREGA`, `DETALLE_ENTREGA`, `COBRO`, `MOVIMIENTO_CUENTA`, `ORIGEN_MOVIMIENTO_CUENTA`, `MOVIMIENTO_CAJA`, `REEMBOLSO`, triggers de inmutabilidad `tg_mr5_*_inmutable`, `tg_mr5_guardar_cobro`, constraint triggers diferidos `ct_mr3_cobro_anulado` y función `fn_mr3_anular_cobro_manual`.
   - `V2__f01_cobro_estados.sql`: catálogo cerrado `COBRO.estadoCobro IN ('CONFIRMADO','ANULADO')` + `tg_f01_transicion_cobro`.
   - `V3__trazabilidad_partidas_insumo.sql`: `PARTIDA_INSUMO`, `DETALLE_MOVIMIENTO_PARTIDA` y `seq_partida_insumo`.
2. **Preflight `RCG-06`:** el SQL candidato verifica catálogo `F01`, PKs positivas y existencia de `V1–V3` con `SET LOCAL lock_timeout = '10s'`. **Prohibido todo backfill heurístico de `COBRO.idPedido`**.
3. **Dependencia con `#91` (`UC-22 Caja manual`, integrado en `28bc24e4022e`):**
   - `CobroManualService` y `RegistrarMovimientoCajaService` comparten `ProtocoloIdentificadoresFinancieros` usando `pg_advisory_xact_lock(2929001)` + `MAX("idMovimientoCaja")+1`.
   - **Hallazgo crítico de convivencia `B2-HÍBRIDO`:** mientras `CobroManualService` y `RegistrarMovimientoCajaService` sigan usando `MAX(id)+1`, cualquier escritor nuevo que inserte en `COBRO`, `MOVIMIENTO_CUENTA` o `MOVIMIENTO_CAJA` debe respetar el puente `2929001` y no mezclar `nextval()` sin migrar coordinadamente a esos escritores en `R09.1` (de lo contrario se produciría `23505`).
4. **Hallazgo crítico `L02` verificado en `V1` (`fn_mr3_anular_cobro_manual`):**
   - El cuerpo de `fn_mr3_anular_cobro_manual` en `V1` adquiere `COBRO FOR UPDATE` (L1218) **antes** de `CLIENTE FOR UPDATE` (L1230), confirmando inversión respecto del orden `CLIENTE → PEDIDO → COBRO`.
   - **Acción en laboratorio:** `V1` **no se modifica**. El test de preflight documenta y acredita este orden en `pg_proc.prosrc` para que su corrección controlada sea requisito explícito de Fase C / `R09.1`. Adicionalmente, el trigger candidato `tg_gfis_guard_d7a_y_reservas_cobro` impide a nivel SQL que `fn_mr3_anular_cobro_manual` anule un cobro de `REPARTO` (`D7-A`) o con reservas/imputaciones activas (`DC05-07-A`).

---

## 2. Fase B — Prototipo físico aislado (`src/test/resources/gfis/`)

**Archivo candidato (solo laboratorio Testcontainers):** [`V4_CANDIDATE__pe1_pe2_imp01_lab.sql`](../../src/test/resources/gfis/V4_CANDIDATE__pe1_pe2_imp01_lab.sql).

### 2.1. Entidades, secuencias y controles implementados en laboratorio

| Bloque | Objetos SQL candidatos | Invariantes exigidos por PostgreSQL |
|---|---|---|
| **Aditivo `COBRO`** | `UNIQUE ("idCobro", "idCliente")` (`uq_gfis_cobro_cliente`) | Permite FKs compuestas en `K2` e `IMP-B` que impiden físicamente mezclar Clientes. |
| **`B2-HÍBRIDO`** | 5 secuencias nuevas (`seq_gfis_recorrido`, `seq_gfis_cierre_recorrido`, `seq_gfis_aplicacion_k2`, `seq_gfis_evento_imp`, `seq_gfis_custodia_diferencia`) + 3 secuencias puente (`seq_gfis_cobro_id`, `seq_gfis_mov_cuenta_id`, `seq_gfis_mov_caja_id`) con `setval > MAX(id)` | Evita `23505` en PKs globales cuando todos los escritores migran a `nextval()`; ordinales de negocio (`numeroSecuenciaCliente`) permanecen bajo lock de agregado. |
| **`PE-1`** | `RECONOCIMIENTO_ENTREGA_PE1` + trigger `tg_gfis_proteger_pe1` | Fórmula R08.4 (`K_e = P_e^{exp} + F_e^{FIFO} <= C_e`, `E_e = C_e - K_e`, `V_e^0 = min(D_e, E_e)`), `IMP_C` obliga `V_e^0 = 0`, `estadoContencionUc07 = 'SOLO_ORIGINALES'`, snapshot inmutable (solo `V_e^{vig}` decrece por aplicación `K2`). |
| **`C1-EXPLÍCITO`** | `RECORRIDO_REPARTO` + `RECORRIDO_ENTREGA` (`UNIQUE(idPedido)`) | Identidad durable de Recorrido y pertenencia explícita sin inferencia por fecha/repartidor. |
| **`PE-2` / `C1-a` / `K2`** | `CIERRE_RECORRIDO_PE2` (`UNIQUE(claveIdempotenciaCierre)` + índice único parcial `WHERE estadoCierre='CONFIRMADO'`), `COBRO_RENDICION_K2` (`PK(idCierreRecorrido, idCliente)`), `APLICACION_RENDICION_K2` (`UNIQUE(idCierreRecorrido, idPedido)`) + constraint triggers diferidos `DEFERRABLE INITIALLY DEFERRED` | A lo sumo 1 cierre confirmado por Recorrido (resiste 2 `ADMINISTRADOR` simultáneos); a lo sumo 1 Cobro `REPARTO`/`EFECTIVO`/`CONFIRMADO` por Cliente/Cierre; `COBRO.importe = Σ aplicaciones` con `>= 1` aplicación; conservación `A_e + V_e^{vig} = V_e^0` al `COMMIT` de `T1`; RBAC `ADMINISTRADOR HABILITADO`; `D1` solo efectivo. |
| **Custodia (`S08`)** | `CUSTODIA_DIFERENCIA_RENDICION` + `tg_gfis_validar_custodia` | Eventos tipados inmutables, exclusivos de `ADMINISTRADOR HABILITADO`, segregados de `COBRO` y `MOVIMIENTO_CAJA` (cero ingreso de venta duplicado). |
| **`IMP-01`** | `EVENTO_IMPUTACION_FINANCIERA` (`IMP-B` append-only inmutable) + `VW_IMP_A_DISPONIBILIDAD_COBRO` (`IMP-A` derivada) | Impide sobreuso de `COBRO`; respeta `RCG-01` (`REEMBOLSO RECHAZADO` no veta disponibilidad; `PENDIENTE`/`PROCESADO` sí descuenta). |
| **Guard `D7-A` / `DC05-07-A`** | `tg_gfis_guard_d7a_y_reservas_cobro` (`BEFORE UPDATE OF estadoCobro ON COBRO`) | Bloquea anulación manual de Cobros `REPARTO`/`K2` y de Cobros con reservas o imputaciones activas (incluso invocando `fn_mr3_anular_cobro_manual`). |

---

## 3. Cuestiones contractuales abiertas (NO resueltas silenciosamente en DDL)

Conforme a la resolución del 19/11/2026, las siguientes fronteras se reflejan como pruebas/criterios de rechazo explícitos y **permanecen abiertas para decisión contractual / Fase C**:

1. **Criterio de comienzo y finalización de un `Recorrido`:** el esquema provee identidad durable (`RECORRIDO_REPARTO`) y pertenencia explícita (`RECORRIDO_ENTREGA`), sin inferir inicio/cierre por `(idUsuarioRepartidor, fechaReparto)`. El evento operativo exacto que abre un Recorrido debe confirmarse en el contrato de `UC-43`/`UC-05` antes de `R09.2`.
2. **Cierre sin aplicaciones positivas (ej. 100 % faltante o 100 % custodia):** probado explícitamente en laboratorio: el cierre persiste en `PENDIENTE_CONCILIACION` (o `CONFIRMADO` con motivo `ADMINISTRADOR`), genera **0 `COBRO` ficticio** y **0 `MOVIMIENTO_CAJA`**, y conserva `V_e^{vig}` intacto. Queda sujeto a validación funcional de Fase C si el recibo de cierre con `importeImputadoTotal = 0` requiere formato específico.
3. **Coexistencia entre cierre único confirmado y `V_e^{vig}` remanente (`E`):** probado explícitamente: el índice único parcial impide un segundo cierre confirmado sobre el mismo Recorrido aunque persista `V_e^{vig} > 0`, y `V_e^{vig}` no se extingue automáticamente. Cualquier regularización o recupero posterior requerirá una RD comercial específica.
4. **Medios de pago distintos de `EFECTIVO` (`D1`):** el prototipo los rechaza explícitamente en `CIERRE_RECORRIDO_PE2` y `COBRO_RENDICION_K2`. Transferencias/pagos online continúan fuera de `PE-2` en su propio circuito (`I6`).

---

## 4. Pruebas de laboratorio (`src/test/java/ar/edu/unam/alemilanesas/gfis/`)

- [`GfisLabSupport.java`](../../src/test/java/ar/edu/unam/alemilanesas/gfis/GfisLabSupport.java): carga explícita de `V4_CANDIDATE`, namespaces `A2(ns, id)` y coordinador `T1 + K2` de laboratorio.
- [`GfisPreflightEIntegridadV4LabIntegrationTest.java`](../../src/test/java/ar/edu/unam/alemilanesas/gfis/GfisPreflightEIntegridadV4LabIntegrationTest.java): 4 tests integrales (preflight `V1–V3`/`RCG-06`; `PE-1` + `EX-01` + `IMP-C` + `IMP-B`/`REEMBOLSO`; `T1 + K2` multi-Cliente/multi-Entrega + rollback atómico + custodia + `D7-A` + rechazo segundo cierre; RBAC `ADMINISTRADOR` + `D1` + cierre 0 aplicaciones + `DC05-07-A` + controles negativos multicliente/desajuste de suma).
- [`GfisConcurrenciaLocksGeneradoresLabIntegrationTest.java`](../../src/test/java/ar/edu/unam/alemilanesas/gfis/GfisConcurrenciaLocksGeneradoresLabIntegrationTest.java): 6 tests de concurrencia y locks (dos `ADMINISTRADOR` simultáneos con UUIDs distintas; replay/conflicto `C1-a`; aislamiento `A2(NS_CLIENTE, id)` vs `A2(NS_INSUMO, id)`; `B2-HÍBRIDO` concurrente multi-Cliente sin `2929001` ni `23505`; convivencia real con `CobroManualService`, `RegistrarMovimientoCajaService` `#91` y `AsignarRepartoService` sin `40P01`/`23505`; y acreditación del hallazgo `L02` en `fn_mr3_anular_cobro_manual`).

### Estado de ejecución

- **Entorno local (19/11/2026):** compilación Java 21 / Maven verificada (`mvn -B -ntp test-compile`); Docker no disponible localmente.
- **CI GitHub Actions (`B1 - Maven / Testcontainers PostgreSQL` sobre PostgreSQL 17.11):** verificar ejecución real sobre el HEAD de la PR `DRAFT` antes de evaluar Fase C.

---

## 5. Condiciones pendientes para Fase C (Evaluación de salida `G-FIS` — NO ACREDITADA)

Para autorizar posteriormente la promoción a `src/main/resources/db/migration/V4__*.sql` y abrir `G-DEV / R09.1`:
1. Confirmar `SUCCESS` real en CI (`B1` Testcontainers PostgreSQL 17.11 + `B2` + `SonarCloud`) sobre el HEAD de esta PR `DRAFT`.
2. Definir el plan controlado de migración de `CobroManualService` y `RegistrarMovimientoCajaService` (`#91`) desde `MAX+1` / `2929001` hacia `A2-GLOBAL` + `B2-HÍBRIDO`, y el tratamiento de `L02` (`fn_mr3_anular_cobro_manual`: `COBRO → CLIENTE`).
3. Confirmar aceptación técnica de las 4 fronteras de la sección 3 y autorización expresa separada para promover `V4` a `src/main/resources/db/migration/`.
