# G-FIS Fase C0 — Expediente de Elegibilidad Técnica Previo a Solicitar Promoción `V4`

- **Proyecto:** Sistema de Gestión para Ale Milanesas (`R08.6 / G-FIS`)
- **Fecha del microcorte C0:** 21/11/2026 (UTC−03, Argentina)
- **Repositorio / PR asociada:** `leandrito369/ale-milanesas-app`, PR `DRAFT #101` (`exp/gfis-fase-ab-pe1-pe2-imp01-2026-11-19`)
- **Estado normativo/técnico:** `PROPUESTO` (expediente técnico de elegibilidad pre-promoción; **NO ejecuta promoción `V4`**, **NO crea `V4__*.sql`**, **NO modifica `src/main/**`**, **NO fusiona PR `#76` ni `#101`**, **NO abre `G-DEV/R09.1`**, **NO reactiva despliegue**)

> [!IMPORTANT]
> **Límites estrictos preservados:** `0` archivos modificados en `src/main/**`; `V1–V3`, `G0`, `docs/traceability/source-lock.md`, RBAC `67×8`, `BC-07` (`15` proyecciones), Demo, Gobierno PR `#75` (`UC-07` `PROPUESTO`) y `leandrito369/ale-milanesas-app#28` (bajo pausa de despliegue) 100 % intactos. Se mantiene **`S10 = NO ACREDITADO`** (limitación histórica de procedencia de G-ARQ Nivel 1, con tratamiento autónomo de Nivel 2).

---

## 1. Línea base verificada y conciliación documental (Tarea 1)

| Referencia | Repositorio | SHA verificado (21/11/2026) | Estado histórico vs estado normativo vigente |
|---|---|---|---|
| **Gobierno `main`** | `leandrito369/ale-milanesas` | `e3f4ee5a752b29329117c7bb097e2a851ea60ca5` | `INTEGRADO` (`#64–#67`, `#70–#73`; `S10 = NO ACREDITADO`). |
| **Gobierno PR `#76`** (`RD-GFIS-AB-001` + ficha P1-A) | `leandrito369/ale-milanesas` | `1630c4773a6019ee25b1f803230a0e788d7cb2e7` | `OPEN / DRAFT` (`B0` y `SonarCloud` `SUCCESS`). Formaliza las 7 reglas A/B ratificadas el 21/11; **aún NO integrada en Gobierno `main`**. |
| **Gobierno PR `#75`** (`UC-07` P1-A / F8 / G / H1 / `IC-UC07-GFIS-02`) | `leandrito369/ale-milanesas` | `82e59f8e030c7d80dbf2689d95506f8f6fbc58d9` | `OPEN / DRAFT`, `PROPUESTO` (excluido de `V4`; intacto). |
| **Aplicación `main`** | `leandrito369/ale-milanesas-app` | `21db1f536d0d7153e5d4abb721115778dadf14b3` | `INTEGRADO` (`V1–V3`, `62` tablas, `CobroManualService` + `ProtocoloIdentificadoresFinancieros`). |
| **Aplicación PR `#101` (HEAD acreditado `B1-R1`)** | `leandrito369/ale-milanesas-app` | `26d78880da683e488e99343696f2d1ab8bcef63c` | `OPEN / DRAFT`, `B1` (`44922445242`) y `B2` (`44922445102`) `SUCCESS` (`756/756` tests, `40/40` G-FIS), `SonarCloud` `Quality Gate passed`. |
| **Constancia ratificación A/B** | `leandrito369/ale-milanesas-app` | [Comentario `7064807213`](https://github.com/leandrito369/ale-milanesas-app/pull/101#issuecomment-7064807213) (21/11 `15:21:10Z`) | **`RATIFICADO` por responsable:** `A1`, `FA-1`, `FA-2`, `B1`, `Alternativa A`, `B1-R1`, `B1-R2`. **No se vuelven a someter a votación.** |

**Conciliación aplicada sobre `GFIS_FASE_AB_EXPEDIENTE_PREFLIGHT_Y_LABORATORIO_2026-11-19.md`:** se preservaron las fechas históricas (`19/11` creación, `20/11` auditoría `E-AG-01..10`) y se retiraron las solicitudes obsoletas de ratificación de las 7 reglas A/B en `E-AG-08/09/10`, reflejando su estado vigente (`RATIFICADO` el 21/11, `RD-GFIS-AB-001` en PR DRAFT `#76` aún no fusionada, y acreditación técnica de laboratorio en `26d78880`).

---

## 2. Auditoría exhaustiva de la migración candidata (Tarea 2)

### 2.1 Inventario verificado por programa (`V4_CANDIDATE__pe1_pe2_imp01_lab.sql` + `V4_CANDIDATE_L02_ANULACION_LAB.sql`)

Conteo exacto extraído de los dos archivos candidatos de laboratorio (`1856` + `160` = `2016` líneas; **ninguno promovido a `src/main/resources/db/migration/`**):

| Categoría | Cantidad exacta | Objetos nominales verificados |
|---|---|---|
| **Bloques preliminares** | `1` `SET LOCAL` + `2` `DO` | `SET LOCAL lock_timeout = '10s'`; `DO $preflight$` (existencia tablas V1–V3, catálogo F01, PKs positivas); `DO $uq_cobro$` (`ALTER TABLE "COBRO" ADD CONSTRAINT "uq_gfis_cobro_cliente" UNIQUE ("idCobro","idCliente")`). |
| **Secuencias (`B2-HÍBRIDO`)** | **`12`** (`9` nuevas + `3` puente financiero con `setval`) | Dominio G-FIS (`9`): `seq_gfis_recorrido`, `seq_gfis_recorrido_asignacion`, `seq_gfis_cierre_recorrido`, `seq_gfis_revision_conciliacion`, `seq_gfis_propuesta_app`, `seq_gfis_aplicacion_k2`, `seq_gfis_evento_imp`, `seq_gfis_custodia_diferencia`, `seq_gfis_egreso_custodia`.<br>Puente B2 (`3`, con `setval > MAX(id)`): `seq_gfis_cobro_id`, `seq_gfis_mov_cuenta_id`, `seq_gfis_mov_caja_id`. |
| **Tablas candidatas nuevas** | **`13`** | 1. `RECONOCIMIENTO_ENTREGA_PE1` · 2. `RECORRIDO_REPARTO` · 3. `RECORRIDO_ASIGNACION` · 4. `RECORRIDO_ENTREGA` · 5. `CIERRE_RECORRIDO_PE2` · 6. `REVISION_CONCILIACION_PE2` · 7. `PROPUESTA_APLICACION_PENDIENTE` · 8. `COBRO_RENDICION_K2` · 9. `APLICACION_RENDICION_K2` · 10. `CUSTODIA_DIFERENCIA_RENDICION` · 11. `EGRESO_CUSTODIA_RENDICION` · 12. `EVENTO_IMPUTACION_FINANCIERA` · 13. `ANULACION_COBRO_IDEMPOTENCIA`. |
| **Vistas (`IMP-A`)** | **`1`** | `VW_IMP_A_DISPONIBILIDAD_COBRO` (proyección derivada no canónica de `importeReembolsoBloqueante`, `importeReservadoActivo`, `importeImputadoDefinitivo`, `importeLibreDisponible`). |
| **Índices únicos parciales** | **`4`** | `uq_gfis_unico_recorrido_abierto_repartidor` (`RECORRIDO_REPARTO`, `FA-1`), `uq_gfis_recorrido_asig_activa_pedido` (`RECORRIDO_ASIGNACION`, `A1`), `uq_gfis_rev_unica_vigente` (`REVISION_CONCILIACION_PE2`), `uq_gfis_rev_unica_confirmada` (`REVISION_CONCILIACION_PE2`). |
| **Constraints declarados** | **`13` PK · `17` UNIQUE · `39` FK · `36` CHECK** | Incluye `1` `ALTER TABLE` sobre tabla existente (`COBRO.uq_gfis_cobro_cliente`) y `1` `ALTER TABLE` diferido de creación (`CIERRE_RECORRIDO_PE2.fk_gfis_cierre_rev_vigente → REVISION_CONCILIACION_PE2`) para resolver la dependencia cíclica `CIERRE ↔ REVISION`. |
| **Funciones SQL / PL/pgSQL** | **`17`** (`16` nuevas + `1` reemplazo `L02`) | Helper (`1`): `fn_gfis_advisory_lock_key(BIGINT)` (proyección 64-bit `hashtext` sin overflow `22003`).<br>Triggers nuevos (`15`): `fn_gfis_proteger_pe1`, `fn_gfis_validar_recorrido_asignacion`, `fn_gfis_validar_recorrido_entrega`, `fn_gfis_validar_revision_conciliacion`, `fn_gfis_verificar_coherencia_revision_commit`, `fn_gfis_validar_admin_y_cierre`, `fn_gfis_validar_propuesta_pendiente`, `fn_gfis_validar_cobro_k2`, `fn_gfis_aplicacion_k2_inmutable`, `fn_gfis_validar_custodia`, `fn_gfis_validar_egreso_custodia`, `fn_gfis_verificar_k2_y_conservacion`, `fn_gfis_impb_guardar`, `fn_gfis_guard_d7a_y_reservas_cobro`, `fn_gfis_validar_anulacion_idemp`.<br>Reemplazo `L02` (`1`, en `V4_CANDIDATE_L02_ANULACION_LAB.sql`): `fn_mr3_anular_cobro_manual(BIGINT, BIGINT, BIGINT, BIGINT, TEXT)` (corrige inversión `COBRO → CLIENTE` a `A2(3202, hashtext(idCliente)) → CLIENTE U → COBRO U` y preserva `D7-A`/`DC05-07-A`). |
| **Triggers** | **`18`** (`13` regulares + `5` diferidos al `COMMIT`) | Regulares (`13`): `tg_gfis_proteger_pe1`, `tg_gfis_validar_recorrido_asignacion`, `tg_gfis_validar_recorrido_entrega`, `tg_gfis_validar_revision_conciliacion`, `tg_gfis_validar_admin_y_cierre`, `tg_gfis_validar_propuesta_pendiente`, `tg_gfis_validar_cobro_k2`, `tg_gfis_aplicacion_k2_inmutable`, `tg_gfis_validar_custodia`, `tg_gfis_validar_egreso_custodia`, `tg_gfis_impb_guardar`, `tg_gfis_guard_d7a_y_reservas_cobro` (sobre `COBRO` existente!), `tg_gfis_validar_anulacion_idemp`.<br>`CONSTRAINT TRIGGER DEFERRABLE INITIALLY DEFERRED` (`5`): `ct_gfis_revision_coherencia_commit`, `ct_gfis_k2_cobro_suma`, `ct_gfis_k2_aplicacion_conservacion`, `ct_gfis_pe1_conservacion`, `ct_gfis_cierre_total_k2`. |

### 2.2 Dependencias externas (V1–V3) e internas y orden de creación compatible con Flyway

- **Dependencias externas obligatorias en `V1–V3`:** `COBRO`, `ENTREGA`, `PEDIDO`, `ASIGNACION_REPARTO`, `CLIENTE`, `USUARIO`, `USUARIO_ROL`, `MOVIMIENTO_CUENTA`, `ORIGEN_MOVIMIENTO_CUENTA`, `MOVIMIENTO_CAJA`, `REEMBOLSO`, `PARTIDA_INSUMO` (V3, verificada en preflight) y función V1 `fn_mr3_anular_cobro_manual` (reemplazada in-place con misma firma por `L02`).
- **Orden topológico validado para Flyway (una sola transacción DDL atómica):**
  1. `SET LOCAL lock_timeout = '10s'` + bloque `DO $preflight$` de validación de esquema/datos previos.
  2. `ALTER TABLE "COBRO" ADD CONSTRAINT "uq_gfis_cobro_cliente" UNIQUE ("idCobro", "idCliente")` (habilita FKs compuestas de `K2`, `IMP-B` y `C-04`).
  3. Función pura `fn_gfis_advisory_lock_key` + `12` secuencias + `setval` bajo exclusión `pg_advisory_xact_lock(2929001)` (ver `ADAPT-06`).
  4. `RECONOCIMIENTO_ENTREGA_PE1` + `tg_gfis_proteger_pe1`.
  5. `RECORRIDO_REPARTO` (+ índice `FA-1`) → `RECORRIDO_ASIGNACION` (+ índice `A1` + trigger) → `RECORRIDO_ENTREGA` (+ trigger).
  6. `CIERRE_RECORRIDO_PE2` (sin `fk_gfis_cierre_rev_vigente` aún) → `REVISION_CONCILIACION_PE2` (+ 2 índices parciales) → `ALTER TABLE "CIERRE_RECORRIDO_PE2" ADD CONSTRAINT "fk_gfis_cierre_rev_vigente"` → triggers de `REVISION` (`tg_*` + `ct_gfis_revision_coherencia_commit`) y de `CIERRE` (`tg_gfis_validar_admin_y_cierre`).
  7. `PROPUESTA_APLICACION_PENDIENTE` (+ trigger) → `COBRO_RENDICION_K2` (+ trigger) → `APLICACION_RENDICION_K2` (+ trigger) → `CUSTODIA_DIFERENCIA_RENDICION` (+ trigger) → `EGRESO_CUSTODIA_RENDICION` (+ trigger).
  8. `4` `CONSTRAINT TRIGGER` de conservación diferidos (`ct_gfis_k2_cobro_suma`, `ct_gfis_k2_aplicacion_conservacion`, `ct_gfis_pe1_conservacion`, `ct_gfis_cierre_total_k2`).
  9. `EVENTO_IMPUTACION_FINANCIERA` (+ `tg_gfis_impb_guardar`) → vista `VW_IMP_A_DISPONIBILIDAD_COBRO`.
  10. `tg_gfis_guard_d7a_y_reservas_cobro` (sobre `COBRO`) + `ANULACION_COBRO_IDEMPOTENCIA` (+ trigger) + reemplazo `L02` `fn_mr3_anular_cobro_manual`.
  11. Índices B-tree auxiliares de FKs (`ADAPT-05`) y ACLs acotadas de objetos nuevos (`ADAPT-03`).

### 2.3 Adaptaciones obligatorias antes de convertir el SQL de laboratorio en un `V4__*.sql` ejecutable

| ID | Brecha verificada en SQL de laboratorio | Adaptación obligatoria antes de promover `V4__*.sql` | Severidad |
|---|---|---|---|
| **ADAPT-01** | `V4_CANDIDATE_L02_ANULACION_LAB.sql` está separado del archivo principal. | Consolidar el reemplazo `L02` (`fn_mr3_anular_cobro_manual`) dentro de la migración promovida (o par ordenado `V4`+`V5` explícito), verificando que la firma `(BIGINT, BIGINT, BIGINT, BIGINT, TEXT)` coincide bit a bit con `V1`. | **Bloqueante** |
| **ADAPT-02** | `0` cláusulas `SET search_path` en las `17` funciones y referencias sin esquema (`"COBRO"`, `"USUARIO"`, etc.). | Evaluar y fijar `SET search_path = pg_catalog, ale_gestion` (o esquema parametrizado/validado contra `B2`) en las `17` funciones y en `VW_IMP_A_DISPONIBILIDAD_COBRO`, eliminando riesgo de objetos sombra en runtime. | **Bloqueante** |
| **ADAPT-03** | `0` sentencias `GRANT`/`REVOKE` en el SQL de laboratorio (Testcontainers corre como propietario). | Definir matriz mínima de privilegios sobre las `13` tablas nuevas, `12` secuencias, `1` vista y `17` funciones para el rol operativo real de la aplicación **sin** aplicar `REVOKE ALL` global no comprobado sobre objetos `V1–V3`. | **Bloqueante** |
| **ADAPT-04** | `ALTER TABLE "COBRO" ADD CONSTRAINT "uq_gfis_cobro_cliente"` y `CREATE TRIGGER ... ON "COBRO"` toman `ACCESS EXCLUSIVE` breve sobre `"COBRO"` existente. | Mantener `SET LOCAL lock_timeout = '10s'`, ejecutar en ventana controlada de drenaje de tráfico y validar previamente ausencia de duplicados `(idCobro, idCliente)` (garantizado por `pk_cobro`). | **Operativa** |
| **ADAPT-05** | Ausencia de índices B-tree auxiliares sobre FKs de alto barrido (`EVENTO_IMPUTACION_FINANCIERA`, `PROPUESTA_APLICACION_PENDIENTE`, `APLICACION_RENDICION_K2`, `CUSTODIA_DIFERENCIA_RENDICION`, `RECORRIDO_ASIGNACION`, `RECORRIDO_ENTREGA`). | Agregar índices no-únicos B-tree sobre columnas FK consultadas por los triggers y por `VW_IMP_A_DISPONIBILIDAD_COBRO` para mitigar degradación de locks bajo carga (`S09`). | **Recomendada (pre-prod)** |
| **ADAPT-06** | Los `3` `SELECT setval('seq_gfis_*', ...)` corren sin lock de exclusión contra escritores legacy `MAX+1` (`CobroManualService`, `RegistrarMovimientoCajaService`). | Adquirir `PERFORM pg_advisory_xact_lock(2929001);` y `LOCK TABLE "COBRO", "MOVIMIENTO_CUENTA", "MOVIMIENTO_CAJA" IN SHARE MODE;` dentro del bloque de seed de `V4` para impedir que una escritura concurrente durante la migración supere el valor sembrado. | **Bloqueante** |
| **ADAPT-07** | El workflow CI `B2` (`ensayo-mr5-f01`) comprueba `migraciones=3` y huella de `1257` objetos. | Al promover `V4` (solo tras autorización Fase C), actualizar el control de `B2` para preservar intacto el cotejo histórico `V1/V2 = MR5+F01 = MR6` y añadir verificación de `migraciones=4` + `pg_dump`/`pg_restore` con `V4`. | **Bloqueante (en Fase C)** |

---

## 3. Integridad y seguridad (Tarea 3)

### 3.1 Auditoría de `SECURITY DEFINER`, `search_path`, contexto transaccional y permisos por objeto

- **`SECURITY DEFINER`:** el inventario confirmó **`0` funciones `SECURITY DEFINER`** en los dos scripts candidatos. Todas las `17` funciones son **`SECURITY INVOKER`**, lo cual es positivo porque elimina superficies de escalación de privilegios en PostgreSQL; exige únicamente que el rol operativo de la aplicación tenga permisos de lectura sobre `USUARIO`/`USUARIO_ROL`/`COBRO`/`REEMBOLSO`/`PEDIDO`/`ENTREGA` (que ya posee para ejecutar `UC-29`/`UC-03`/`UC-43`) y los permisos mínimos exactos sobre los objetos nuevos.
- **`search_path`:** como se documentó en `ADAPT-02`, ninguna de las `17` funciones declara `SET search_path`. Antes de promover `V4`, cada función debe declarar `SET search_path = pg_catalog, ale_gestion` (o validarse contra el esquema efectivo configurado en Flyway/B2) para blindar resolución de tablas y operadores contra esquemas sombra (`pg_temp`/`public`).
- **Contexto transaccional y RBAC:**
  - Las validaciones de rol (`ADMINISTRADOR HABILITADO` en `CIERRE_RECORRIDO_PE2`, `REVISION_CONCILIACION_PE2`, `CUSTODIA_DIFERENCIA_RENDICION`, `EGRESO_CUSTODIA_RENDICION`, `ANULACION_COBRO_IDEMPOTENCIA` y `fn_mr3_anular_cobro_manual`; `REPARTIDOR` en `RECORRIDO_REPARTO`) se verifican contra `USUARIO`/`USUARIO_ROL` en cada operación sin depender de GUCs falsificables (`SET LOCAL app.*`).
  - **Límite honesto de frontera (coherente con F8):** en PostgreSQL la comprobación de `idUsuarioAdministrador` valida existencia/estado/rol del actor declarado por la capa Java tras autenticar el Bearer en `SeguridadHttpInterna`, pero **no** sustituye la autenticación criptográfica HTTP ni el control de sesiones revocadas de Spring Security.
- **Permisos por objeto recomendados para promoción (sin revocaciones globales no comprobadas):**

| Objeto nuevo G-FIS | Rol migrador (`flyway`/owner) | Rol operativo de aplicación (`app_runtime`) | `PUBLIC` |
|---|---|---|---|
| `RECONOCIMIENTO_ENTREGA_PE1` | Owner | `SELECT`, `INSERT`, `UPDATE("reconocimientoVigenteVvig")` (0 `DELETE`) | Sin grants nuevos (`REVOKE ALL` acotado solo a tabla nueva) |
| `RECORRIDO_REPARTO`, `RECORRIDO_ASIGNACION` | Owner | `SELECT`, `INSERT`, `UPDATE` (0 `DELETE`) | Sin grants nuevos |
| `RECORRIDO_ENTREGA`, `PROPUESTA_APLICACION_PENDIENTE`, `COBRO_RENDICION_K2`, `APLICACION_RENDICION_K2`, `CUSTODIA_DIFERENCIA_RENDICION`, `EGRESO_CUSTODIA_RENDICION`, `EVENTO_IMPUTACION_FINANCIERA`, `ANULACION_COBRO_IDEMPOTENCIA` | Owner | `SELECT`, `INSERT` (0 `UPDATE`, 0 `DELETE` — append-only reforzado por trigger) | Sin grants nuevos |
| `CIERRE_RECORRIDO_PE2`, `REVISION_CONCILIACION_PE2` | Owner | `SELECT`, `INSERT`, `UPDATE` (0 `DELETE` — transiciones guardadas por trigger + `ct_gfis_revision_coherencia_commit`) | Sin grants nuevos |
| `VW_IMP_A_DISPONIBILIDAD_COBRO` | Owner | `SELECT` | Sin grants nuevos |
| `12` secuencias `seq_gfis_*` | Owner | `USAGE`, `SELECT` (0 `UPDATE` manual salvo migrador) | Sin grants nuevos |
| `17` funciones (`fn_gfis_*`, `fn_mr3_anular_cobro_manual`) | Owner | `EXECUTE` (solo las funciones invocadas directamente: `fn_gfis_advisory_lock_key` y `fn_mr3_anular_cobro_manual`; funciones trigger no requieren `EXECUTE` explícito del invocador) | `REVOKE EXECUTE` acotado exclusivamente a `fn_gfis_*` nuevas |

*(Regla estricta respetada: **NO** se proponen ni aplican `REVOKE ALL ON SCHEMA ale_gestion FROM PUBLIC` globales sin inventario previo de todos los servicios `V1–V3`, ni cambios en perfiles Spring o feature flags.)*

### 3.2 Auditoría de los 5 controles diferidos al `COMMIT` (`DEFERRABLE INITIALLY DEFERRED`)

| Constraint Trigger | Tabla(s) | Invariante garantizada al `COMMIT` | Estado verificado en laboratorio (`26d78880`) |
|---|---|---|---|
| `ct_gfis_revision_coherencia_commit` | `REVISION_CONCILIACION_PE2` | Relee estado efectivo al `COMMIT` (sin snapshots `NEW.*`); prohíbe `VIGENTE`, `SUPERADA`, `INVALIDADA` o `CONFIRMADA_T1` aisladas; exige coherencia 4-vías (`REVISION ↔ CIERRE ↔ RECORRIDO ↔ COBRO_RENDICION_K2/PROPUESTA`) e historial `rev 1` preservado en `B1-R1` (`CONFIRMADO`). | **PROBADO** (negativos `P0001` + positivos cadena completa + `B1-R1`). |
| `ct_gfis_k2_cobro_suma` | `COBRO_RENDICION_K2` | Verifica `COUNT(apps) >= 1`, `SUM(apps) = COBRO.importe` por Cliente/cierre y `SUM(COBRO) = CIERRE.importeImputadoConfirmado`. | **PROBADO** (`T1-C2`, `K2`, multi-Cliente). |
| `ct_gfis_k2_aplicacion_conservacion` | `APLICACION_RENDICION_K2` | Verifica `SUM(A_e) + V_e^{vig} = V_e^0` por cada `idPedido`. | **PROBADO**. |
| `ct_gfis_pe1_conservacion` | `RECONOCIMIENTO_ENTREGA_PE1` | Verifica `SUM(A_e) + V_e^{vig} = V_e^0` ante cualquier `INSERT` o `UPDATE` de `reconocimientoVigenteVvig`. | **PROBADO**. |
| `ct_gfis_cierre_total_k2` | `CIERRE_RECORRIDO_PE2` | Verifica al `COMMIT` suma de Cobros K2, coherencia de propuestas en `PENDIENTE_CONCILIACION`, validez de `CONFIRMADA_T1` (tanto `CERRADO_CON_DIFERENCIA_RESUELTA` como `CONFIRMADO` vía `B1-R1`) y `EGRESO_CUSTODIA_RENDICION` exacto sobre la revisión confirmada (`E-04`). | **PROBADO** (`40/40` G-FIS). |

---

## 4. Procedimiento de Cutover `A2 / B2` y frontera `G-FIS Fase C` vs `G-DEV / R09.1` (Tarea 4)

### 4.1 Diagnóstico real de generadores `MAX+1` actualmente integrados en `app main@21db1f53`

La inspección directa de `src/main/java/ar/edu/unam/alemilanesas/finanzas/` confirmó tres escritores `MAX+1` activos hoy bajo el guard `pg_advisory_xact_lock(2929001)`:
1. `CobroManualService.java:207`: `SELECT COALESCE(MAX("idCobro"),0)+1 FROM "COBRO"` (bajo `A1(2929001)`).
2. `CobroManualService.java:213`: `SELECT COALESCE(MAX("idMovimientoCuenta"),0)+1 FROM "MOVIMIENTO_CUENTA"` (bajo `A1(2929001)`).
3. `ProtocoloIdentificadoresFinancieros.java:25` (usado por `CobroManualService` y `RegistrarMovimientoCajaService` `UC-22`): `SELECT COALESCE(MAX("idMovimientoCaja"),0)+1 FROM "MOVIMIENTO_CAJA"` (bajo `A1(2929001)`).
4. `CobroManualService.java:166`: invoca `fn_mr3_anular_cobro_manual(idCobro, idAdmin, idMovCuenta, idMovCaja, motivo)` pasando los `idMovCuenta`/`idMovCaja` generados con `MAX+1`.

### 4.2 Por qué NO hay colisión durante `G-FIS Fase C` sola — y dónde SÍ ocurriría si se mezclaran generadores sin cutover atómico

- **Durante `G-FIS Fase C` (promoción exclusiva de `V4__*.sql` sin código nuevo en `src/main/**`):**
  - Ningún servicio productivo invoca todavía `nextval('seq_gfis_cobro_id')`, `nextval('seq_gfis_mov_cuenta_id')` ni `nextval('seq_gfis_mov_caja_id')`.
  - `CobroManualService` y `RegistrarMovimientoCajaService` continúan operando 100 % con `MAX+1` bajo `A1(2929001)` sin colisiones.
  - La única precaución necesaria en `V4` es que `setval` inicial adquiera `A1(2929001)` + `LOCK TABLE ... IN SHARE MODE` (`ADAPT-06`) y que `fn_mr3_anular_cobro_manual` (`L02`) sea 100 % retrocompatible con `CobroManualService` actual (verificado: mantiene exactamente la misma firma de 5 parámetros y `CobroManualService` ya adquiere `A1(2929001)` antes de invocarla!).
- **Riesgo crítico al entrar en `G-DEV / R09.1`:**
  - Si `UC-05`/`UC-29` empezaran a usar `nextval('seq_gfis_*')` mientras `CobroManualService` o `RegistrarMovimientoCajaService` siguieran usando `MAX+1` — o si `seq_gfis_*` quedara desactualizada respecto de nuevos registros `MAX+1` escritos entre `G-FIS Fase C` y `G-DEV / R09.1` — ocurrirían colisiones `SQLSTATE 23505` (`unique_violation`)!

### 4.3 Protocolo obligatorio de Cutover `A2 / B2` en dos etapas claramente separadas

| Etapa | Qué ocurre | Requisitos obligatorios de seguridad anti-colisión |
|---|---|---|
| **Etapa 1 — `G-FIS Fase C` (Promoción física `V4`, SIN cambios en `src/main/**`)** | Se instalan las `13` tablas, `12` secuencias, vista `IMP-A`, triggers, `L02` y `C-04` en `ale_gestion`. | 1. Sembrar `seq_gfis_cobro_id`, `seq_gfis_mov_cuenta_id` y `seq_gfis_mov_caja_id` bajo `pg_advisory_xact_lock(2929001)` + `LOCK TABLE ... IN SHARE MODE`.<br>2. Mantener **inactivos** todos los consumidores de `seq_gfis_*` (`0` código nuevo en `src/main/**`).<br>3. Verificar que `CobroManualService` y `UC-22` pasan 100 % de regresión contra `V4` (incluyendo `L02` y `tg_gfis_guard_d7a_y_reservas_cobro`). |
| **Etapa 2 — `G-DEV / R09.1` (Cutover atómico de aplicación en `src/main/**`)** | En **un único corte coordinado** (nunca gradual por servicio suelto), `CobroManualService`, `RegistrarMovimientoCajaService` (`ProtocoloIdentificadoresFinancieros`), `UC-05` y `UC-29` pasan simultáneamente a `B2-HÍBRIDO` + `A2-GLOBAL`. | 1. **Re-seed obligatorio pre-arranque de `R09.1`** (si hubo tráfico `MAX+1` tras `V4`): ejecutar bajo exclusión `pg_advisory_xact_lock(2929001)` + `LOCK TABLE "COBRO", "MOVIMIENTO_CUENTA", "MOVIMIENTO_CAJA" IN SHARE MODE` un `setval('seq_gfis_*', GREATEST(MAX(id), last_value), ...)` antes de habilitar la versión `R09.1` (p. ej. en migración de cutover o procedimiento administrativo auditado).<br>2. **Migración simultánea de los 3 generadores:** reemplazar los `MAX+1` de `CobroManualService` (L207, L213) y `ProtocoloIdentificadoresFinancieros` (L25) por `nextval('seq_gfis_cobro_id')`, `nextval('seq_gfis_mov_cuenta_id')` y `nextval('seq_gfis_mov_caja_id')` **en el mismo despliegue**, prohibiendo coexistencia `MAX+1` + `nextval` sobre la misma tabla.<br>3. **Puente `A1(2929001)` preservado hasta verificación global:** todos los escritores financieros conservan `A1(2929001)` antes de `A2(NS_CLIENTE)`/`CLIENTE U` hasta que toda la suite `C3` acredite su retiro seguro.<br>4. **Ordinales de negocio intactos:** `MOVIMIENTO_CUENTA.numeroSecuenciaCliente` y `ASIGNACION_REPARTO.numeroAsignacion` **NO** pasan a secuencia global; siguen calculándose bajo bloqueo `CLIENTE U` / `PEDIDO U`. |

---

## 5. Checklist reproducible de Preflight y Reversibilidad (Tarea 5)

> **Prohibición respetada:** no se conectó a ningún entorno real ni se aprovisionó infraestructura; el checklist distingue explícitamente la corrida en PostgreSQL 17.11 descartable de la futura corrida en destino autorizado.

### 5.1 Fase A — Checklist en PostgreSQL 17.11 descartable (previa a promover `V4` en PR de Fase C)

1. **Compatibilidad `V1–V3` y Flyway:** aplicar `V1`, `V2`, `V3` intactas (checksums inalterados) sobre PostgreSQL `17.11-alpine` en esquema `ale_gestion`; verificar `flyway_schema_history` (`3` filas `success=true`).
2. **Validación de `V4__*.sql` sobre base vacía y sobre base con datos sintéticos:**
   - Aplicar `V4__*.sql` tras `V1–V3`; verificar `4` migraciones `success=true`, `0` errores, `0` objetos fuera de `ale_gestion`.
   - Verificar `setval` con tablas vacías (`nextval() = 1`) y con tablas pobladas (`MAX = 5000` ⇒ `nextval() = 5001`).
   - Verificar `B2` `pg_dump --schema-only` / `pg_restore` preservando `V1–V4`, triggers, `5` constraint triggers diferidos y vista `VW_IMP_A_DISPONIBILIDAD_COBRO`.
3. **Ausencia de regresiones comerciales/operativas:** ejecutar la suite completa (`756+` tests) contra la migración `V4` real en `ale_gestion`, verificando `0` regresiones en `UC-03`, `UC-08`, `UC-19`, `UC-22`, `UC-28`, `UC-29`, `UC-43` y `0` cambios en reglas comerciales `RCG-01..08` ni RBAC G0.
4. **Prueba de roles reales (no owner):** ejecutar pruebas de lectura/escritura y denegación (`42501` en `DELETE` o mutación directa prohibida) usando el rol operativo real de aplicación.

### 5.2 Fase B — Checklist en base de destino real (SOLO con autorización administrativa expresa posterior)

1. **Respaldo y prueba de restauración previa obligatoria:**
   - Ejecutar `pg_dump -Fc` (o snapshot de almacenamiento consistente) antes de tocar el destino.
   - **Restaurar el respaldo en una instancia descartable** y verificar conteos de tablas críticas (`COBRO`, `PEDIDO`, `ENTREGA`, `MOVIMIENTO_CUENTA`, `MOVIMIENTO_CAJA`, `REEMBOLSO`, `PARTIDA_INSUMO`) antes de autorizar la ventana.
2. **Preflight `READ ONLY` sobre destino (`BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`):**
   - Confirmar PostgreSQL `17.x`, esquema efectivo `ale_gestion`, ausencia de objetos homónimos `RECONOCIMIENTO_ENTREGA_PE1..ANULACION_COBRO_IDEMPOTENCIA` o `seq_gfis_*`, y `flyway_schema_history` con `V1–V3` exitosas.
   - Validar datos históricos: `0` `COBRO` fuera de `('CONFIRMADO','ANULADO')`; `0` PKs financieras `<= 0`; `0` duplicados `(idCobro, idCliente)`; firma actual de `fn_mr3_anular_cobro_manual`.
   - Ejecutar evaluación de las **5 señales `RCG-06`** (`A`..`E`) por Cliente (reportando qué Clientes requieren contención selectiva `IMP-C` al activar `R09.1`, **sin backfill heurístico** de `COBRO.idPedido`).
3. **Ventana de ejecución y reversibilidad:**
   - **Rollback previo al `COMMIT`:** toda `V4__*.sql` corre en una única transacción PostgreSQL con `SET LOCAL lock_timeout = '10s'`. Ante cualquier fallo de preflight, timeout de lock o error DDL, PostgreSQL ejecuta `ROLLBACK` atómico dejando la base bit a bit idéntica (`V1–V3`, `0` objetos residuales).
   - **Contingencia posterior al `COMMIT` (antes de desplegar `R09.1`, con `0` filas operativas en tablas G-FIS):** como `V4` es estrictamente aditiva en esta etapa (ningún servicio productivo escribe aún en las `13` tablas nuevas), una eventual reversión de emergencia se realiza mediante restauración del respaldo validado en paso 1 (o migración forward-only correctiva en ventana controlada; **nunca** `DROP CASCADE` ad-hoc ni edición manual de `flyway_schema_history` en producción).
   - **Contingencia posterior a activar tráfico `R09.1`:** prohibido rollback destructivo de esquema; aplicar mitigación forward-only preservando íntegramente `PE-1`, `PE-2`, `IMP-B`, `COBRO`, `MOVIMIENTO_CUENTA` y `MOVIMIENTO_CAJA`.

---

## 6. Evidencia de CI, avisos Surefire, deuda SonarCloud, matriz de riesgos y dictamen `GO / NO-GO` (Tarea 6)

### 6.1 Evidencia exacta del HEAD acreditado (`26d78880da683e488e99343696f2d1ab8bcef63c`)

| Check | Resultado | Evidencia verificable | Observaciones / límites honestos |
|---|---|---|---|
| **B1 — Maven / Testcontainers PG 17.11** | **`SUCCESS`** (`7m03s`) | [Run 44922445242](https://github.com/leandrito369/ale-milanesas-app/actions/runs/44922445242) / [Job 136901202926](https://github.com/leandrito369/ale-milanesas-app/actions/runs/44922445242/job/136901202926) | Surefire log: **`Tests run: 756, Failures: 0, Errors: 0, Skipped: 0`** (`40/40` G-FIS, incluyendo `12/12` en `GfisRectificacionPendienteLabIntegrationTest`). |
| **B2 — PostgreSQL MR5/F01 Flyway y cotejo MR6** | **`SUCCESS`** (`8m49s`) | [Run 44922445102](https://github.com/leandrito369/ale-milanesas-app/actions/runs/44922445102) / [Job 136901202732](https://github.com/leandrito369/ale-milanesas-app/actions/runs/44922445102/job/136901202732) | `migraciones=3`, `PASS B2: Flyway V1/V2 = MR5+F01 = MR6`, `PASS restore: 1257 objects`. No ejecuta `V4` todavía porque `V4` no está en Flyway productivo. |
| **SonarCloud Code Analysis** | **`SUCCESS`** (`Quality Gate passed`) | [Check 136901446908](https://github.com/leandrito369/ale-milanesas-app/runs/136901446908) | `0` Bugs, `0` Vulnerabilities, `0` Security Hotspots, `0.0%` Duplication; **`25` Code Smells** (`MAJOR`) exclusivamente en `src/test/**`. |

### 6.2 Registro honesto de avisos Surefire / CI observados (no ocultados)

1. **`[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0)`** (observado tanto al finalizar `B1` `44922445242` como `B2` `44922445102`), precedido por **`WARN HikariPool-70..79 housekeeper - Failed to validate connection ... (This connection has been closed.)`**: ocurre al finalizar los `756` tests cuando Testcontainers detiene contenedores PostgreSQL antes de que Spring cierre los `HikariDataSource` cacheados en el `TestContext`. **Impacto:** `0` fallos funcionales (`756/756 PASS`, `BUILD SUCCESS`), pero agrega ~30 s de cierre en CI y constituye deuda de higiene del harness de tests (recomendado resolver en corte de testing, **no bloqueante** para elegibilidad física `V4`).
2. **`WARNING: A Java agent has been loaded dynamically (byte-buddy-agent-1.18.15.jar)`** (Java 21 / Mockito): aviso estándar de JVM sin impacto funcional.
3. **`WARN UserDetailsServiceAutoConfiguration`**, **`WARN DefaultSqlScriptExecutor: extension "btree_gist" already exists (42710)`**, **`AutenticacionHttpExternaTest.java` deprecated API** y **`actions/setup-java@v4` / Node `punycode` deprecated**: avisos preexistentes del repositorio/runner, no introducidos por PR `#101`.

### 6.3 Inventario exacto de deuda SonarCloud en PR `#101` (consulta API SonarCloud)

- **Total:** `25` issues abiertas (`100 %` `CODE_SMELL`, `MAJOR`, `100 %` en `src/test/java/ar/edu/unam/alemilanesas/gfis/`, **`0` en `src/main/**`**):
  - `java:S5778` (lambda con múltiples invocaciones dentro de `assertThrows`): **`24`**
  - `java:S5961` (>25 aserciones en un único método `@Test`): **`1`**
- **Distribución por archivo:** `GfisPreflightEIntegridadV4LabIntegrationTest.java` (`16`), `GfisRectificacionPendienteLabIntegrationTest.java` (`8`), `GfisL02AnulacionCandidataLabIntegrationTest.java` (`1`). *(Los 4 tests `B1-R1` añadidos en `26d78880` aportaron `0` issues nuevas.)*
- **Límite explícito:** SonarCloud evalúa código Java; **no** audita semántica PL/pgSQL ni ACLs de PostgreSQL.

### 6.4 Matriz de riesgos bloqueantes y no bloqueantes para `G-FIS Fase C`

| ID | Riesgo / Dependencia | Tipo | Mitigación requerida y puerta |
|---|---|---|---|
| **R-C0-01** | **`RD-GFIS-AB-001` (Gobierno PR `#76`) aún en DRAFT, no integrada en Gobierno `main`.** | **Bloqueante documental** | Aprobación editorial y merge de Gobierno PR `#76` antes (o como acto conjunto expreso) de autorizar Fase C. |
| **R-C0-02** | **Fusión `L02`, `SET search_path`, ACLs acotadas y bloqueo de `setval` (`ADAPT-01`, `02`, `03`, `06`).** | **Bloqueante técnico de promoción** | Aplicar dichas 4 adaptaciones al construir la migración `V4__*.sql` en la rama autorizada de Fase C y re-ejecutar `B1`/`B2` + prueba con rol no-owner. |
| **R-C0-03** | **Actualización de control `B2` (`migraciones=4` + restore `V4`) (`ADAPT-07`).** | **Bloqueante técnico de CI** | Verificar en el corte de Fase C que `B2` sigue probando `V1/V2 = MR6` intacto y además valida `V4` + `pg_dump`/`pg_restore`. |
| **R-C0-04** | **Colisión `23505` entre `MAX+1` (`CobroManualService`/`UC-22`) y `seq_gfis_*`.** | **Bloqueante de `G-DEV / R09.1`** (no de Fase C sin código) | Ejecutar el cutover atómico `B2` + re-seed bajo `A1(2929001)` descrito en §4.3 al abrir `G-DEV / R09.1`. |
| **R-C0-05** | **Salvedad histórica `S10 = NO ACREDITADO` (G-ARQ Nivel 1 sin SHA primario).** | **Condición de gobierno** | Conservada explícitamente bajo tratamiento autónomo de G-ARQ Nivel 2 (`#72`); el responsable reitera su aceptación al decidir Fase C. |
| **R-C0-06** | **Índices B-tree auxiliares sobre FKs (`ADAPT-05`) y `S09` (tiempos de lock en recorridos masivos).** | **No bloqueante de laboratorio / recomendada en `V4`** | Incluir índices FK en `V4__*.sql` y medir latencias en carga real antes de producción. |
| **R-C0-07** | **Aviso Surefire `30s` al cierre de Testcontainers + `25` smells JUnit SonarCloud en `src/test/**`.** | **No bloqueante** | Limpiar en corte de mantenimiento de pruebas (`@DirtiesContext` / cierre ordenado de pools y refactor de `assertThrows`), sin alterar lógica de negocio. |

### 6.5 Criterios verificables de aceptación para solicitar `G-FIS Fase C` y Dictamen `GO / NO-GO`

**Criterios ya cumplidos al cierre de C0 (`21/11/2026`):**
1. Siete reglas `A1`, `FA-1`, `FA-2`, `B1`, `Alternativa A`, `B1-R1` y `B1-R2` ratificadas por el responsable ([comentario `7064807213`](https://github.com/leandrito369/ale-milanesas-app/pull/101#issuecomment-7064807213)) y formalizadas en Gobierno PR `#76` (`1630c477`, `B0` y SonarCloud `SUCCESS`).
2. Laboratorio PostgreSQL 17.11 acreditado en PR `#101` (`26d78880`): `40/40` pruebas G-FIS (incluyendo `B1-R1` completa) y `756/756` pruebas totales `PASS`, `B1`/`B2`/`SonarCloud` `SUCCESS`, `0` cambios en `src/main/**`.
3. Inventario completo de los objetos candidatos, brechas `ADAPT-01..07`, matriz ACL, plan de cutover `A2/B2`, checklist reproducible de preflight/reversibilidad y registro honesto de avisos Surefire/SonarCloud documentados en este expediente.

**Dictamen final C0:**
- **`GO` para elevar al responsable la solicitud formal de autorización independiente de `G-FIS Fase C` (promoción de `V4` a `src/main/resources/db/migration/`)**, condicionada a:
  1. Integración editorial autorizada de Gobierno PR `#76` (`RD-GFIS-AB-001`),
  2. Incorporación obligatoria de `ADAPT-01`, `ADAPT-02`, `ADAPT-03`, `ADAPT-06` y `ADAPT-07` (más `ADAPT-05` recomendada) al construir el archivo `V4__*.sql` en el corte de Fase C, con nueva corrida verde de `B1`, `B2` y `SonarCloud`.
- **`NO-GO` automático** para ejecutar hoy la promoción `V4`, modificar `src/main/**`, fusionar `#76`/`#101`, abrir `G-DEV / R09.1`, iniciar `UC-07` físico (PR `#75`), tocar Demo o levantar la pausa de despliegue de `Issue #28`.
