# Expediente Técnico — G-FIS Fase C: Construcción y Validación de Migración Flyway V4

- **Proyecto:** Sistema de Gestión para Ale Milanesas
- **Fecha:** 21/11/2026
- **Estado normativo/técnico del entregable:** `CONSTRUIDO Y PROBADO EN CI AISLADO (PR DRAFT, NO INTEGRADO)` — **NO MERGE / NO DESPLIEGUE / NO EJECUCIÓN SOBRE BASES REALES**. Acreditación técnica inicial: PR #102 HEAD `ac2a6d0a96e6b027446ce010cb0d2c8a821d36c6` (22/11/2026); la integración continúa sujeta a autorización humana expresa.
- **Constancia de autorización limitada (construcción y pruebas en nueva rama + PR DRAFT):** [`leandrito369/ale-milanesas-app#101` (comment `7071217116`)](https://github.com/leandrito369/ale-milanesas-app/pull/101#issuecomment-7071217116)

---

## 1. Líneas base y trazabilidad obligatoria

| Repositorio / Referencia | Rama / PR | SHA exacto | Rol en Fase C |
| :--- | :--- | :--- | :--- |
| Gobierno (`leandrito369/ale-milanesas`) | `main` (PR `#76` `RD-GFIS-AB-001` MERGED) | `2c74174af7850df5c7cd76f7158c0d729a889122` | **Solo lectura** — autoridad normativa ratificada (`A1`, `FA-1`, `FA-2`, `B1`, `Alternativa A`, `B1-R1`, `B1-R2`) |
| Aplicación (`leandrito369/ale-milanesas-app`) | `main` | `21db1f536d0d7153e5d4abb721115778dadf14b3` | Base limpia desde la que nace la rama de Fase C |
| Aplicación (`leandrito369/ale-milanesas-app`) | PR `#101` (OPEN / DRAFT) | `4779519013ed24cef0a642c665e18383fbc50ed9` | **Referencia versionada de laboratorio** (no modificada ni fusionada) |
| Aplicación (`leandrito369/ale-milanesas-app`) | PR `#102`, `feat/gfis-fase-c-flyway-v4-2026-11-21` | `ac2a6d0a96e6b027446ce010cb0d2c8a821d36c6` (HEAD acreditado al 22/11/2026) | Única rama de escritura de G-FIS Fase C; DRAFT sin merge |

### Límites estrictos preservados
- **Único archivo nuevo bajo `src/main/**`:** `src/main/resources/db/migration/V4__gfis_pe1_pe2_imp01.sql`.
- **Cero cambios en `src/main/java/**`.**
- **Cero cambios en `V1__baseline_mr5.sql`, `V2__f01_cobro_estados.sql` y `V3__trazabilidad_partidas_insumo.sql`.**
- **Cero cambios en `leandrito369/ale-milanesas`**, PR `#101`, PR `#75` (`UC-07` PROPUESTO), PR `#76`, G0, `source-lock`, Demo o infraestructura.
- **No se ejecuta sobre bases reales**; toda validación ocurre en PostgreSQL `17.11-alpine` efímero (Testcontainers y GitHub Actions).
- **`S10` continúa `NO ACREDITADO`** y **Issue `#28` permanece abierto y pausado** hasta cerrar su definición funcional/comercial.
- **Exclusiones explícitas de V4:** `UC-07` físico (`CR-C`/`CR-F`/`#47`/`#48`), `PE-3` offline, recupero automático post-cierre (`E`), medios distintos de `EFECTIVO` en `PE-2` (`D1`) y cualquier backfill retroactivo de `COBRO`, `PEDIDO` o `ENTREGA` históricos (`RCG-06`).

---

## 2. Inventario exacto de archivos creados y modificados

| Ruta en `leandrito369/ale-milanesas-app` | Acción | Alcance |
| :--- | :--- | :--- |
| `src/main/resources/db/migration/V4__gfis_pe1_pe2_imp01.sql` | **CREADO** | Única modificación bajo `src/main/**`; migración Flyway `V4` con `ADAPT-01..ADAPT-06` |
| `scripts/b2/compare_mr6.sh` | **MODIFICADO** | `ADAPT-07`: preserva huella histórica MR-6 (`MR5+F01` directa = incremental) y extiende validación a `V1–V4` + `pg_dump`/`pg_restore` con datos sintéticos V1+V4 (`T7`) |
| `src/test/java/ar/edu/unam/alemilanesas/B2SchemaMigrationTest.java` | **MODIFICADO** | `ADAPT-07`: actualiza catálogo esperado a `V1–V4` (`4` migraciones, `75` tablas base, `606` columnas, `168` FKs, `1` vista G-FIS, `18` triggers G-FIS) |
| `src/test/java/ar/edu/unam/alemilanesas/gfis/GfisLabSupport.java` | **CREADO** | Soporte de pruebas de integración contra Flyway `V4` real (sin carga manual de fixtures SQL) |
| `src/test/java/ar/edu/unam/alemilanesas/gfis/GfisFlywayV4InstalacionYReversibilidadIntegrationTest.java` | **CREADO** | Pruebas `T1` (instalación limpia V1–V4), `T2` (upgrade sobre V1–V3 poblada sin backfill y seed > MAX) y `T6` (rollback ante preflight, `lock_timeout` `55P03`, DDL y no-transaccionalidad de `setval`/`nextval`) |
| `src/test/java/ar/edu/unam/alemilanesas/gfis/GfisC01CompatibilidadLegacyYSeguridadLabIntegrationTest.java` | **CREADO** | Pruebas `T3` (servicios legacy `CobroManualService` y `RegistrarMovimientoCajaService` reales contra V1–V4) y `T4` (ataques `search_path` `pg_temp`/`public` contra funciones V4 reales y rol no-owner `42501`) |
| `src/test/java/ar/edu/unam/alemilanesas/gfis/GfisPreflightEIntegridadV4LabIntegrationTest.java` | **CREADO** | Prueba `T5`: integridad PE-1, T1-C2, K2, A1/FA-1/FA-2, B1, E-04, D7-A, DC05-07-A, RCG-06 e IMP-B/IMP-A contra Flyway V4 real |
| `src/test/java/ar/edu/unam/alemilanesas/gfis/GfisRectificacionPendienteLabIntegrationTest.java` | **CREADO** | Prueba `T5`: Alternativa A (escenarios A–H), `B1-R1`, `B1-R2` y `ct_gfis_revision_coherencia_commit` contra Flyway V4 real |
| `src/test/java/ar/edu/unam/alemilanesas/gfis/GfisConcurrenciaLocksGeneradoresLabIntegrationTest.java` | **CREADO** | Prueba `T5`: concurrencia real, A2/B2 64-bit, Bloqueante 3, verificación `ADAPT-01`/`ADAPT-02` en `fn_mr3_anular_cobro_manual` y transición `MAX+1` vs B2 |
| `src/test/java/ar/edu/unam/alemilanesas/gfis/GfisL02AnulacionCandidataLabIntegrationTest.java` | **CREADO** | Prueba `T5`: `L02` integrada en V4 (`CLIENTE U -> COBRO U`, 64-bit > `2^31-1`, D7-A, DC05-07-A, convivencia T1/K2) e idempotencia física `C-04` |
| `src/test/java/ar/edu/unam/alemilanesas/gfis/GfisCampanaC3AmpliadaLabIntegrationTest.java` | **CREADO** | Prueba `T5`: campaña C3 ampliada (`UC-43` real vs `PE-1`, concurrencia multi-Cliente, `C1-a` y `E-04`) contra Flyway V4 real |
| `docs/implementation/GFIS_FASE_C_FLYWAY_V4_EXPEDIENTE_2026-11-21.md` | **CREADO** | Este expediente técnico de Fase C |

---

## 3. Inventario físico exacto de `V4__gfis_pe1_pe2_imp01.sql`

| Dimensión de catálogo (`ale_gestion`) | Línea base V1–V3 | Delta V4 | Total V1–V4 |
| :--- | ---: | ---: | ---: |
| Migraciones SQL Flyway | 3 | +1 | **4** |
| Tablas base (excl. `flyway_schema_history`) | 62 | +13 | **75** |
| Vistas | 0 | +1 (`VW_IMP_A_DISPONIBILIDAD_COBRO`) | **1** |
| Columnas (`information_schema.columns`, excl. historial Flyway) | 467 | +139 (131 en 13 tablas + 8 en vista) | **606** |
| Claves foráneas (`contype = 'f'`) | 129 | +39 | **168** |
| Secuencias nuevas (`seq_gfis_*`) | 0 | +12 (9 técnicas + 3 financieras puente) | **12** |
| Funciones endurecidas (`ADAPT-02`) | 0 | +16 `fn_gfis_*` + 1 reemplazo `L02` (`fn_mr3_anular_cobro_manual`) | **17** |
| Triggers nuevos (`tg_gfis_*` / `ct_gfis_*`) | 0 | +18 (13 `BEFORE` + 5 `DEFERRABLE INITIALLY DEFERRED CONSTRAINT`) | **18** |
| Índices únicos parciales (`uq_gfis_* WHERE`) | 0 | +4 | **4** |
| Índices B-tree auxiliares sobre FKs (`ADAPT-05`, `idx_gfis_*`) | 0 | +13 | **13** |
| Objetos nuevos cubiertos por `REVOKE ALL ... FROM PUBLIC` (`ADAPT-03`) | 0 | 42 (13 tablas + 1 vista + 12 secuencias + 16 `fn_gfis_*`) | **42** |

---

## 4. Delta exacto frente al candidato de laboratorio (`#101@47795190`) — Tratamiento `ADAPT-01..ADAPT-07`

| Código | Adaptación obligatoria | Implementación exacta en Fase C | Verificación asociada |
| :--- | :--- | :--- | :--- |
| **`ADAPT-01`** | Integrar `L02` directamente en `V4` | Bloque 7 de `V4__gfis_pe1_pe2_imp01.sql` reemplaza `ale_gestion."fn_mr3_anular_cobro_manual"(BIGINT, BIGINT, BIGINT, BIGINT, TEXT)` conservando firma y semántica legacy válida, aplicando orden `A2(3202, pg_catalog.hashtext(v_id_cliente::text)) -> CLIENTE FOR UPDATE -> COBRO FOR UPDATE`, revalidación post-espera, `D7-A` y `DC05-07-A`. | `T1`, `T3`, `T5` (`GfisL02`, `GfisConcurrencia`) |
| **`ADAPT-02`** | `SET search_path = pg_catalog, ale_gestion, pg_temp` + calificación explícita | Aplicado en las **17 funciones** (`16` `fn_gfis_*` + `fn_mr3_anular_cobro_manual`) con `pg_temp` explícitamente al final y calificación explícita `ale_gestion."..."` y `pg_catalog.*` en todas las funciones, `VW_IMP_A_DISPONIBILIDAD_COBRO` y DDL/DML de `V4`. | `T1`, `T4` (`t4B1` ataca funciones reales V4 con tablas sombra en `pg_temp` y `public`), `B2` |
| **`ADAPT-03`** | Privilegios mínimos acotados exclusivamente a objetos nuevos G-FIS | Bloque 9 ejecuta `REVOKE ALL ... FROM PUBLIC` sobre las `13` tablas nuevas, `1` vista, `12` secuencias `seq_gfis_*` y `16` funciones `fn_gfis_*`. **No** revoca permisos de `V1–V3` ni de `fn_mr3_anular_cobro_manual` para preservar compatibilidad legacy. | `T1` (`role_table_grants` y `has_function_privilege`), `T4` (`t4B2` demuestra `42501` antes de `GRANT`, flujo válido tras `GRANT` mínimo y `42501` en `UPDATE`/`DELETE` prohibidos) |
| **`ADAPT-04`** | Conservar `SET LOCAL lock_timeout = '10s'` | Primera sentencia ejecutable de `V4__gfis_pe1_pe2_imp01.sql`. Evita bloqueos indefinidos sobre `COBRO` / `MOVIMIENTO_CUENTA` / `MOVIMIENTO_CAJA`. | `T6` (`t6a`: retención `ACCESS EXCLUSIVE` sobre `COBRO` provoca aborto limpio `SQLSTATE 55P03` con rollback atómico de toda `V4`) |
| **`ADAPT-05`** | Índices B-tree auxiliares sobre FKs nuevas con justificación y costo | Bloque 8 crea **13 índices B-tree** (`idx_gfis_*`) sin redundancia respecto de prefijos `PK`/`UNIQUE` existentes (detalle en §5). | `T1` (`13` índices `idx_gfis_*`), `B2` huella estructural exacta y restore |
| **`ADAPT-06`** | Proteger los 3 `setval` financieros con `pg_advisory_xact_lock(2929001)` + `LOCK TABLE ... IN SHARE MODE` | Bloque 1 encapsula los 3 `pg_catalog.setval` en `DO $seed_b2$` adquiriendo `pg_catalog.pg_advisory_xact_lock(2929001)` y `LOCK TABLE ale_gestion."COBRO", ale_gestion."MOVIMIENTO_CUENTA", ale_gestion."MOVIMIENTO_CAJA" IN SHARE MODE` con `GREATEST(COALESCE(MAX(...), 0), 1)`. | `T2` (`nextval = MAX + 1` tras upgrade sobre datos altos), `T6` (`t6b`), `B2` |
| **`ADAPT-07`** | Extender `B2` y `compare_mr6.sh` a `V1–V4` preservando huella histórica `MR-6` y `pg_dump`/`pg_restore` | `scripts/b2/compare_mr6.sh` compara primero `MR5+F01` directa vs incremental `59->MR3->MR5->F01` (huella MR-6 histórica intacta), luego aplica `V3` y `V4`, arranca Spring Boot sobre la 3.ª DB con Flyway real (`V1–V4`), exige diff cero entre las 3 DBs y valida `pg_dump`/`pg_restore` con datos sintéticos V1+V4, `VW_IMP_A`, `F01 PASS` y `4` migraciones. | `B2` (`B2SchemaMigrationTest` + `compare_mr6.sh` en CI) |

---

## 5. Justificación técnica y costo de los 13 índices auxiliares (`ADAPT-05`)

Ninguno duplica el prefijo izquierdo de una `PRIMARY KEY` o `UNIQUE` ya creada en `V4` (por ejemplo, `RECORRIDO_ENTREGA("idRecorrido")` se omitió por ser prefijo de `pk_gfis_recorrido_entrega`, y `PROPUESTA_APLICACION_PENDIENTE("idRevisionConciliacion")` se omitió por ser prefijo de `uq_gfis_propuesta_rev_pedido`).

| Índice (`ale_gestion`) | Tabla / Columnas | Consulta / Trigger / FK beneficiado | Costo de escritura / almacenamiento |
| :--- | :--- | :--- | :--- |
| `idx_gfis_recorrido_repartidor_estado` | `RECORRIDO_REPARTO ("idUsuarioRepartidor", "estadoOperativo")` | Consultas de recorridos por repartidor y FK `fk_gfis_recorrido_repartidor` | Bajo (pocos recorridos por jornada) |
| `idx_gfis_rec_asig_rec_estado` | `RECORRIDO_ASIGNACION ("idRecorrido", "estadoPertenencia")` | Guard `FA-2` en `fn_gfis_validar_admin_y_cierre` y carga de pedidos del recorrido | Bajo (~1 fila por asignación UC-43) |
| `idx_gfis_rev_anterior` | `REVISION_CONCILIACION_PE2 ("idRevisionAnterior") WHERE "idRevisionAnterior" IS NOT NULL` | `ct_gfis_revision_coherencia_commit` (búsqueda de sucesora `numeroRevision+1`) y auto-FK | Muy bajo (índice parcial, excluye revisión 1) |
| `idx_gfis_propuesta_cierre` | `PROPUESTA_APLICACION_PENDIENTE ("idCierreRecorrido")` | FK `fk_gfis_propuesta_cierre` y auditoría de propuestas por cierre | Bajo (solo cierres con diferencia) |
| `idx_gfis_propuesta_pedido` | `PROPUESTA_APLICACION_PENDIENTE ("idPedido", "idCliente")` | FK compuesta `fk_gfis_propuesta_pe1_cliente` | Bajo (solo cierres con diferencia) |
| `idx_gfis_app_k2_pedido` | `APLICACION_RENDICION_K2 ("idPedido")` | Trigger diferido `ct_gfis_k2_aplicacion_conservacion` / `ct_gfis_pe1_conservacion` (`SUM` por pedido) | Bajo (append-only, 1 fila por entrega imputada) |
| `idx_gfis_app_k2_cobro` | `APLICACION_RENDICION_K2 ("idCobro")` | FK `fk_gfis_aplicacion_cobro_cliente` y conciliación Cobro -> Aplicaciones | Bajo (append-only) |
| `idx_gfis_custodia_cierre_rev` | `CUSTODIA_DIFERENCIA_RENDICION ("idCierreRecorrido", "idRevisionConciliacion")` | Verificación `E-04` al COMMIT en `fn_gfis_verificar_k2_y_conservacion` | Muy bajo (solo eventos de diferencia/custodia) |
| `idx_gfis_egreso_evento` | `EGRESO_CUSTODIA_RENDICION ("idEventoCustodia")` | `fn_gfis_validar_egreso_custodia` (`SUM(importeEgreso)` por evento) | Muy bajo (solo devoluciones físicas) |
| `idx_gfis_egreso_cierre` | `EGRESO_CUSTODIA_RENDICION ("idCierreRecorrido")` | Verificación `E-04` al COMMIT por cierre | Muy bajo |
| `idx_gfis_impb_cobro_pedido` | `EVENTO_IMPUTACION_FINANCIERA ("idCobro", "idPedido")` | **Alto impacto:** `fn_gfis_impb_guardar`, `fn_gfis_guard_d7a_y_reservas_cobro`, `fn_mr3_anular_cobro_manual` (`L02`) y `VW_IMP_A_DISPONIBILIDAD_COBRO` | Moderado y plenamente justificado (evita seq-scan en toda anulación o imputación) |
| `idx_gfis_impb_pedido_cliente` | `EVENTO_IMPUTACION_FINANCIERA ("idPedido", "idCliente")` | FK `fk_gfis_impb_pedido_cliente` y consulta de cobertura por pedido | Moderado (append-only) |
| `idx_gfis_impb_cierre` | `EVENTO_IMPUTACION_FINANCIERA ("idCierreRecorrido") WHERE "idCierreRecorrido" IS NOT NULL` | FK `fk_gfis_impb_cierre` y auditoría de imputaciones K2 | Muy bajo (índice parcial, omite eventos manuales/anticipados) |

---

## 6. Reversibilidad y límites transaccionales de secuencias (`setval` / `nextval`)

PostgreSQL ejecuta el DDL de `V4__gfis_pe1_pe2_imp01.sql` dentro de la transacción de Flyway, pero las operaciones `setval()` / `nextval()` sobre secuencias ya existentes **no son transaccionales**. Por ello se distinguen estrictamente tres escenarios operativos:

1. **Escenario 1 — Aborto de `V4` antes del `COMMIT` de Flyway** (preflight `GFIS-PREFLIGHT-*`, `lock_timeout` `55P03` o error DDL):
   - PostgreSQL revierte atómicamente todas las tablas, secuencias, vista, funciones, triggers, índices y constraints de `V4`.
   - Verificado en `T6` (`t6a_RollbackTransaccionalAntePreflightFallidoYLockTimeoutReal`): el esquema queda exactamente en `62` tablas (`V1–V3`), `0` secuencias `seq_gfis_*`, y tras corregir la causa y reparar `flyway_schema_history`, `V4` aplica limpio.
2. **Escenario 2 — Rollback controlado posterior al `COMMIT` de `V4`, ANTES de habilitar escritores nuevos (`R09.1`) y SIN escrituras en tablas G-FIS:**
   - Si no hubo escrituras de negocio posteriores al backup pre-migración, es viable restaurar el dump lógico previo (`pg_dump`/`pg_restore`), o bien ejecutar un plan correctivo forward-only documentado (nunca editar `V4` ya aplicada ni borrar silenciosamente historial).
   - La no-transaccionalidad de `setval`/`nextval` sobre secuencias preexistentes queda acreditada en `T6` (`t6b_DdlRevierteConRollbackPeroSetvalSobreSecuenciaPreexistenteNoRevierte`).
3. **Escenario 3 — Incidente posterior al `COMMIT` de `V4` CUANDO YA EXISTEN escrituras financieras posteriores al backup:**
   - **QUEDA ESTRICTAMENTE PROHIBIDO restaurar un backup ciego pre-migración**, porque destruiría cobros, movimientos de caja/cuenta corriente o entregas ya confirmadas. Cualquier corrección en este escenario debe ser **forward-only** con `RD`/`CR` y conciliación auditable.

---

## 7. Matriz de pruebas `T1..T8`

| ID | Requisito del plan Fase C | Evidencia / Cobertura | Estado |
| :--- | :--- | :--- | :--- |
| **`T1`** | Instalación Flyway `V1–V4` desde cero, checksums `V1–V3` intactos, `0` objetos fuera de `ale_gestion`, `ADAPT-02`/`03`/`05` | `GfisFlywayV4InstalacionYReversibilidadIntegrationTest` (`t1a`, `t1b`) + `B2SchemaMigrationTest` | **PASS en CI** — ver §8 (HEAD `ac2a6d0`) |
| **`T2`** | Upgrade `V4` sobre base `V1–V3` poblada con datos sintéticos previos, `0` backfill retroactivo y `nextval > MAX` (`ADAPT-06`) | `GfisFlywayV4InstalacionYReversibilidadIntegrationTest.t2_UpgradeFlywayV4SobreBaseV1V3PobladaSinBackfillRetroactivoYSeedMayorQueMax` | **PASS en CI** — ver §8 (HEAD `ac2a6d0`) |
| **`T3`** | Servicios legacy reales (`CobroManualService` y `RegistrarMovimientoCajaService`) contra Flyway `V4` (alta, replay, caja, anulación `L02`, `D7-A`, `DC05-07-A`, concurrencia) | `GfisC01CompatibilidadLegacyYSeguridadLabIntegrationTest` (`t3A1`, `t3A2`, `t3A3`) | **PASS en CI** — ver §8 (HEAD `ac2a6d0`) |
| **`T4`** | Seguridad real: ataque `search_path` (`pg_temp` y `public`) directamente contra funciones `V4` reales + rol no-owner `42501` antes/después de `GRANT` mínimo | `GfisC01CompatibilidadLegacyYSeguridadLabIntegrationTest` (`t4B1`, `t4B2`) | **PASS en CI** — ver §8 (HEAD `ac2a6d0`) |
| **`T5`** | Integridad G-FIS completa contra Flyway `V4` real (`A1`, `FA-1`, `FA-2`, `B1`, `Alternativa A`, `B1-R1`, `B1-R2`, `T1-C2`, `K2`, `L02`, `C-04`, `IMP-B/IMP-A`, 5 triggers diferidos, C3, 64-bit) | 5 suites en `src/test/java/ar/edu/unam/alemilanesas/gfis/` (`GfisPreflightEIntegridad`, `GfisRectificacionPendiente`, `GfisConcurrenciaLocksGeneradores`, `GfisL02Anulacion`, `GfisCampanaC3Ampliada`) | **PASS en CI** — ver §8 (HEAD `ac2a6d0`) |
| **`T6`** | Reversibilidad real: fallo preflight, `lock_timeout` `55P03` (`ADAPT-04`), error DDL y no-transaccionalidad `setval`/`nextval` | `GfisFlywayV4InstalacionYReversibilidadIntegrationTest` (`t6a`, `t6b`) | **PASS en CI** — ver §8 (HEAD `ac2a6d0`) |
| **`T7`** | `pg_dump` / `pg_restore` sobre `V1–V4` con estructura, triggers, vista `VW_IMP_A`, `F01 PASS` y datos sintéticos V1+V4 | `scripts/b2/compare_mr6.sh` en workflow `B2` | **PASS en CI** — ver §8 (HEAD `ac2a6d0`) |
| **`T8`** | `B1`, `B2` y `SonarCloud` en `SUCCESS` sobre el HEAD final exacto de la PR DRAFT | Ver §8 tras ejecución de GitHub Actions | **PASS** — B1, B2 y SonarCloud sobre `ac2a6d0` (ver §8) |

---

## 8. Evidencia de ejecución CI (`T8`) — verificación final del 22/11/2026

**HEAD acreditado:** `ac2a6d0a96e6b027446ce010cb0d2c8a821d36c6` en [PR #102](https://github.com/leandrito369/ale-milanesas-app/pull/102), **OPEN / DRAFT / NO MERGE**. Los resultados siguientes corresponden a ese commit, **antes de este ajuste exclusivamente editorial**; los checks del nuevo commit documental deberán verificarse por separado y no se presumen exitosos.

| Control | Resultado confirmado en `ac2a6d0` | Evidencia |
| :--- | :--- | :--- |
| **B1 — Maven / Testcontainers PostgreSQL 17.11** | **SUCCESS**; Surefire: **765 tests, 0 failures, 0 errors, 0 skipped**; **49/49 G-FIS**, incluidas las 5 pruebas de instalación/reversibilidad Flyway V4. | [Run 44969108465](https://github.com/leandrito369/ale-milanesas-app/actions/runs/44969108465) / [Job 137044729690](https://github.com/leandrito369/ale-milanesas-app/actions/runs/44969108465/job/137044729690) |
| **B2 — MR5/F01, MR6, Flyway V1–V4** | **SUCCESS**; `PASS source fidelity` V1/V2, `PASS B2 histórico` (MR5+F01 directa = incremental); `migraciones=4`, `tablas=75`, `columnas=606`, `fk=168`; huella de esquema directa = incremental. | [Run 44969108552](https://github.com/leandrito369/ale-milanesas-app/actions/runs/44969108552) / [Job 137044729770](https://github.com/leandrito369/ale-milanesas-app/actions/runs/44969108552/job/137044729770) |
| **T7 — Restauración sintética en B2** | **PASS**: `1587 objects`, `1541 exact definitions`, `46 CHECK ANY(ARRAY)` variantes del deparser; **0 diferencias no identificadas**; F01 y datos sintéticos V1+V4 preservados. | [Log B2, run 44969108552](https://github.com/leandrito369/ale-milanesas-app/actions/runs/44969108552) |
| **SonarCloud PR #102** | **SUCCESS — Quality Gate passed**; 0 bugs y 0 vulnerabilidades reportadas, 0 Security Hotspots; **25 Code Smells** en tests y **0,0 % de cobertura informada para código nuevo** (esta métrica no acredita cobertura de SQL/PLpgSQL). | [Check 137044867464](https://github.com/leandrito369/ale-milanesas-app/runs/137044867464) · [Panel SonarCloud](https://sonarcloud.io/dashboard?id=leandrito369_ale-milanesas-app&pullRequest=102) |

**Alcance de las pruebas:** T1–T7 se ejecutaron contra PostgreSQL 17.11 efímero y migraciones Flyway de la **rama de PR #102**, no contra una base operativa real. B1/B2 verifican regresión y restauración sintética; no constituyen E2E humano, autorización de cambio, certificación de infraestructura, acreditación S10 ni cierre del Issue #28.

**Higiene no bloqueante:** el log de CI registra la advertencia de cierre del JVM fork de Surefire tras completar satisfactoriamente los tests; permanece como deuda técnica del harness. Las observaciones de estilo de SonarCloud no se convierten en fallos funcionales.

## 9. Riesgos remanentes y dictamen técnico para futura autorización de merge

### Riesgos remanentes (fuera del alcance de Fase C)
1. **Convivencia temporal `MAX+1` (legacy) vs secuencias B2 (`seq_gfis_*`):** mientras `CobroManualService` y `RegistrarMovimientoCajaService` no migren sus generadores de PK en `R09.1` (fuera de alcance aquí porque `src/main/java/**` no se modifica), el re-seed bajo `pg_advisory_xact_lock(2929001)` + `LOCK TABLE ... IN SHARE MODE` será requisito obligatorio en la ventana de cutover de `R09.1`.
2. **Privilegios de roles en despliegue futuro:** `V4` revoca `PUBLIC` sobre los 42 objetos nuevos (`ADAPT-03`). Antes de ejecutar un rol no-owner en `R09.1`/despliegue, el runbook de aprovisionamiento deberá otorgar los `GRANT` mínimos documentados y probados en `T4`.
3. **Límites funcionales vigentes:** `S10` sigue `NO ACREDITADO`, Issue `#28` sigue pausado y `UC-07` físico (`PR #75`) sigue `PROPUESTO` — ninguno de ellos está habilitado por `V4`.

### Condición de GO / NO-GO para futura solicitud de autorización de merge
- **GO técnico para someter a revisión de merge:** `T1..T8` se acreditaron en el HEAD `ac2a6d0` de la PR #102 con B1, B2 y SonarCloud `SUCCESS` (evidencia §8). Esta valoración no sustituye la revisión del diff/ACL/cutover, los checks del nuevo HEAD documental, ni la **autorización explícita y separada del responsable para fusionar**.
- **Esta PR permanece `DRAFT` y NO autoriza merge, despliegue, `G-DEV/R09.1` ni `UC-07` físico.**
