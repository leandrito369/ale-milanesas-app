# G-FIS (Fase A + Fase B — Auditoría final R08.6 y rectificación auditable de conciliaciones pendientes)

**Fecha inicial:** 19/11/2026 · **Auditoría final R08.6 (Entregables E-AG-01..E-AG-10):** 20/11/2026 (UTC−03)  
**Estado:** **LABORATORIO AISLADO EN PR `DRAFT #101` (NO MERGE / NO DESPLEGAR)** · **`S10 = NO ACREDITADO`** · **`G-FIS Fase C = NO AUTORIZADA`** · **`G-DEV / R09.1 = NO AUTORIZADO`**.  
**Invariantes no negociables verificadas:** `0` archivos modificados en `src/main/**`; `V1–V3`, `G0`, `docs/traceability/source-lock.md`, matriz RBAC `67×8`, `BC-07` (`15` proyecciones), Demo e `Issue #28` 100 % intactos; `UC-07` físico (`#47/#48`) y `PE-3` offline excluidos; `A1`, `FA-1`, `FA-2`, `B1` y **Alternativa A de rectificación versionada** son **PROPUESTAS CANDIDATAS, NO RATIFICADAS**.

---

## E-AG-01 — Acta de preflight (M0 verificado el 20/11/2026)

| Verificación | Repositorio / ruta | SHA / estado comprobado | Discrepancias |
|---|---|---|---|
| **Gobierno `main`** | `leandrito369/ale-milanesas` (`#67→#66→#65→#70→#71→#72→#73`) | `e3f4ee5a752b29329117c7bb097e2a851ea60ca5` | Ninguna (coincide con SHA de referencia). |
| **Aplicación `main`** | `leandrito369/ale-milanesas-app` (`#96→#88→#91→#87`) | `21db1f536d0d7153e5d4abb721115778dadf14b3` | Ninguna (coincide con SHA de referencia). |
| **PR `#101` (HEAD de entrada)** | `exp/gfis-fase-ab-pe1-pe2-imp01-2026-11-19` (`OPEN / DRAFT`) | `8c3a85ea1fc6d7743fabbf3688c8096a811ebce2` | Ninguna (`8` archivos en `src/test/**` y `docs/implementation/**`, `0` en `src/main/**`). |
| **Source-lock G0** | `docs/traceability/source-lock.md` | `source_commit=1263ffc1` / `governance_commit=27738b08` | Intacto. |
| **Fuentes normativas leídas** | `AGENTS.md`, `CR-B0-F04-001`, `ACT-G-COM-001`, `RD-F04-002/003/005/006/007`, `#70`, `G-ARQ-N2-01/02`, `E-01..07`, `RD-I302-003..011`, G0 `UC-05/14/29/43`, RBAC `67×8`, `BC-07` | Verificadas en su texto exacto | `S10 = NO ACREDITADO`; `CR-C #47` / `CR-F #48` bloqueadas por `RCG-02`. |
| **PRs abiertas en app** | `#9, #11, #12, #20, #22, #24, #89, #92, #101` | Ninguna alterada ni cerrada | Sin conflictos con ramas externas. |

---

## E-AG-02 — Diagnóstico (M1) y análisis comparativo de alternativas (M2)

### Diagnóstico de la brecha residual (`8c3a85ea`)
En `8c3a85ea`, `PENDIENTE_CONCILIACION` garantizaba correctamente `0` efectos financieros antes de `T1-C2`, pero impedía corregir errores materiales antes de la confirmación definitiva porque:
1. `CIERRE_RECORRIDO_PE2` bloqueaba mutar `importeImputadoPropuesto`, `importeDiferenciaFaltante`, `importeCustodiaNoImputada` y `motivoDiferencia`.
2. `PROPUESTA_APLICACION_PENDIENTE` tenía `UNIQUE(idCierreRecorrido, idPedido)` sin versionado y prohibía `UPDATE`/`DELETE`.
3. `CUSTODIA_DIFERENCIA_RENDICION` no distinguía entre un registro de custodia erróneo superado y el registro vigente para `EGRESO_CUSTODIA_RENDICION` (`E-04`).
4. `uq_gfis_unico_cierre_por_recorrido` impedía crear una fila sucesora.

Esto dejaba al `ADMINISTRADOR` atrapado ante errores materiales en los escenarios **A** (importe propuesto erróneo), **B** (Pedido equivocado), **C** (custodia/diferencia incorrecta), **D** (cobertura `422` detectada antes del cierre), **E** (carrera rectificación vs confirmación `T1-C2`), **F** (pérdida de ACK en rectificación), **G** (intento post-cierre) y **H** (invalidación sin borrar historia).

### Comparativa obligatoria: Alternativa A vs Alternativa B

| Dimensión | **Alternativa A — Rectificación versionada append-only (RECOMENDADA, CANDIDATA)** | **Alternativa B — Invalidación y nueva cabecera `CIERRE_RECORRIDO_PE2`** |
|---|---|---|
| **Operación G0 / subflujo** | Subflujo de rendición de **`UC-29`** (sin crear `UC-65`). | Subflujo de rendición de **`UC-29`** (sin crear `UC-65`). |
| **Actor y ámbito** | Exclusivamente **`ADMINISTRADOR HABILITADO`** sobre `CIERRE_RECORRIDO_PE2` en estado `PENDIENTE_CONCILIACION` (sin ampliación RBAC). | Exclusivamente **`ADMINISTRADOR HABILITADO`**. |
| **Máquina de estados** | Cabecera `CIERRE_RECORRIDO_PE2` conserva `PENDIENTE_CONCILIACION → CERRADO_CON_DIFERENCIA_RESUELTA` (o `CONFIRMADO` directo). Hija `REVISION_CONCILIACION_PE2`: `VIGENTE → {SUPERADA, INVALIDADA, CONFIRMADA_T1}`. | Cabecera añade estado `INVALIDADO` y permite múltiples filas `CIERRE_RECORRIDO_PE2` por `idRecorrido`. |
| **Identidad e idempotencia** | `claveIdempotenciaRevision UUID UNIQUE` + `versionHuella` + `huellaCanonica` por cada revisión (replay/409 en escenario F); `claveIdempotenciaCierre` intacta para `C1-a`. | Requiere encadenar múltiples `idCierreRecorrido` y separar claves de cierre fallidas/invalidadas vs definitivas. |
| **Persistencia y cardinalidad** | 1 `CIERRE_RECORRIDO_PE2` por Recorrido (`UNIQUE("idRecorrido")` **intacta**); `1..N` `REVISION_CONCILIACION_PE2` (máximo 1 `VIGENTE` y máximo 1 `CONFIRMADA_T1`); `PROPUESTA_APLICACION_PENDIENTE` y `CUSTODIA_DIFERENCIA_RENDICION` vinculadas por `idRevisionConciliacion`. | `1..N` `CIERRE_RECORRIDO_PE2` por Recorrido; **obliga a reemplazar `UNIQUE("idRecorrido")` por un índice único parcial**, debilitando el Bloqueante 3. |
| **Invariantes SQL** | `UNIQUE("idRecorrido")` incondicional + `uq_gfis_rev_unica_vigente` + `uq_gfis_rev_unica_confirmada` + coincidencia exacta cabecera↔revisión `VIGENTE` (cero reescritura silenciosa) + `E04-CUSTODIA` exclusivamente sobre la revisión confirmada. | Índice parcial `WHERE estadoCierre <> 'INVALIDADO'`; riesgo de carrera concurrente al invalidar y crear nueva cabecera; mayor complejidad de FKs entre cabeceras. |
| **Transacciones y locks `A2`** | `A2(NS_RECORRIDO, idRecorrido) → CIERRE_RECORRIDO_PE2 FOR UPDATE → REVISION_CONCILIACION_PE2 FOR UPDATE`; confirmación exige `numeroRevisionEsperado` (`409 REVISION_OBSOLETA` ante carrera E). | Requiere lock adicional a nivel Recorrido para serializar creación de nueva cabecera vs confirmación de la anterior. |
| **Impacto `T1-C2`, `K2`, `C1-a`** | **Nulo sobre el COMMIT financiero:** `0` Cobros/Caja/`V_e` antes de `T1-C2`; `T1-C2` confirma solo la revisión `VIGENTE` (`CONFIRMADA_T1`) en un único `COMMIT`; `C1-a` intacto. | Complica `C1-a` al existir múltiples cabeceras históricas por Recorrido y obliga a distinguir replays de cabeceras invalidadas. |
| **Compatibilidad con las 12 tablas** | **100 % aditiva** (+1 tabla `REVISION_CONCILIACION_PE2` → `13` tablas candidatas; `0` rupturas en las 28 pruebas previas). | Altera unicidad central de `CIERRE_RECORRIDO_PE2` y obliga a rediseñar controles del Bloqueante 3. |
| **Riesgos y necesidad de RD/CR** | **No requiere nueva operación G0.** Requiere incorporar `REVISION_CONCILIACION_PE2` en `CR-B0-F04-001` / ratificación de `B1`, más definir explícitamente 2 bordes comerciales: (1) si una rectificación con `faltante=0 ∧ custodia=0` puede cerrar como `CONFIRMADO`, y (2) alcance operativo tras `INVALIDADA`. | Mismos requisitos comerciales más mayor riesgo técnico de concurrencia/auditoría. |

**Recomendación fundamentada:** **Alternativa A (`REVISION_CONCILIACION_PE2`, CANDIDATA NO RATIFICADA)**, porque preserva incondicionalmente `UNIQUE("idRecorrido")` del Bloqueante 3, elimina toda posibilidad de reescritura silenciosa de cabecera, resuelve los 8 escenarios **A–H** con `0` efectos financieros anticipados y mantiene un único `COMMIT` `T1-C2`.

---

## E-AG-03 — Matriz de trazabilidad (Escenarios A–H y controles no negociables)

| Necesidad / Escenario | Requisito / RD / CR | Operación G0 / Subflujo | Tabla / Función / Constraint SQL | Prueba ejecutada en PR `#101` | Resultado |
|---|---|---|---|---|---|
| **A, B, C** — Rectificar importe, Pedido y custodia sin efectos financieros anticipados | `G-ARQ-N2-01 §8`, `RD-F04-002`, `E-04`, `CR-B0-F04-001` | `UC-29` rendición (`ADMIN`) | `REVISION_CONCILIACION_PE2`, `PROPUESTA_APLICACION_PENDIENTE(idRevisionConciliacion)`, `CUSTODIA_DIFERENCIA_RENDICION(idRevisionConciliacion)`, `fn_gfis_validar_egreso_custodia` | `GfisRectificacionPendienteLabIntegrationTest.escenariosABC_RectificarImportePedidoYCustodiaSinEfectosFinancierosYConfirmarT1C2ConE04` | **PASS PARCIAL** *(SQL/P PG 17.11)* |
| **D** — Cobertura `422` y pertenencia revalidadas + rollback íntegro | `ART-GCON-B1`, `C1-EXPLÍCITO` | `UC-29` rendición (`ADMIN`) | `RECORRIDO_ENTREGA`, `RECONOCIMIENTO_ENTREGA_PE1`, `GfisLabSupport.rectificarPendienteConciliacionEnTx` | `GfisRectificacionPendienteLabIntegrationTest.escenarioD_RectificacionConCoberturaInsuficiente422OPedidoAjenoRevierteIntegramente` | **PASS PARCIAL** *(SQL/P PG 17.11)* |
| **E** — Carrera rectificación vs confirmación `T1-C2` sin estados inconsistentes | `A2-GLOBAL`, `T1-C2`, `C1-a` | `UC-29` rendición (`ADMIN`) | `A2(NS_RECORRIDO)`, `CIERRE_RECORRIDO_PE2 FOR UPDATE`, control `numeroRevisionEsperado` (`409 REVISION_OBSOLETA`) | `GfisRectificacionPendienteLabIntegrationTest.escenarioE_CarreraConcurrenteRectificacionVsConfirmacionT1C2SinEstadosInconsistentes` | **PASS PARCIAL** *(SQL/P PG 17.11)* |
| **F** — Replay idempotente tras pérdida de ACK, conflicto `409` y RBAC `403` | `C1-a`, `RCG-05`, `E-10`, RBAC `67×8` | `UC-29` rendición (`ADMIN`) | `uq_gfis_rev_clave_idemp`, `tg_gfis_validar_revision_conciliacion` | `GfisRectificacionPendienteLabIntegrationTest.escenarioF_ReplayIdempotenteDeRectificacionPorPerdidaDeAckConflicto409YRbac403` | **PASS PARCIAL** *(SQL/P PG 17.11)* |
| **G** — Prohibido rectificar o mutar historial tras cierre terminal | `C1-a`, `RD-F04-002/005`, `E` | `UC-29` rendición (`ADMIN`) | `tg_gfis_validar_revision_conciliacion`, `tg_gfis_validar_admin_y_cierre` | `GfisRectificacionPendienteLabIntegrationTest.escenarioG_ProhibidoRectificarOAlterarRevisionesTrasCierreTerminal` | **PASS PARCIAL** *(SQL/P PG 17.11)* |
| **H** — Invalidación sin borrado histórico, bloqueo de `T1-C2` y revisión sucesora | `RD-F04-002`, `G-ARQ-N2-01 §8` | `UC-29` rendición (`ADMIN`) | `REVISION_CONCILIACION_PE2(estadoRevision='INVALIDADA')`, `CIERRE_RECORRIDO_PE2(idRevisionVigente=NULL)`, `ct_gfis_cierre_total_k2` | `GfisRectificacionPendienteLabIntegrationTest.escenarioH_InvalidacionSinBorradoBloqueaT1YPermiteNuevaRevisionSucesora` | **PASS PARCIAL** *(SQL/P PG 17.11)* |
| **Base previa (28 tests G-FIS)** — `T1-C2`, `K2`, `A2/B2`, `L02` 64-bit, `C-04`, `FA-1/FA-2`, `E-04`, `RCG-06`, `D7-A`, `DC05-07-A`, `C3-03/08` | `ACT-G-COM-001`, `#65–#73`, `G0` | `UC-05/29/43/22` (R+P / P) | 12 tablas previas + `V4_CANDIDATE_L02_ANULACION_LAB.sql` | 4 clases `Gfis*` previas (`7+8+9+4 = 28` tests) | **PASS PARCIAL** *(SQL/P y R+P)* |
| **`UC-07` físico, `PE-3` offline, HTTP/UI/E2E global** | `CR-C #47`, `CR-F #48`, `I3–I7` | `UC-05/07/14/29` | Excluidos de `V4_CANDIDATE` | — | **DIFERIDO JUSTIFICADO** (`R09.1–R09.5`) |

---

## E-AG-04 / E-AG-05 / E-AG-06 — Plan de diff, pruebas y CI verificable

- **Microcorte final de integridad SQL (`ct_gfis_revision_coherencia_commit`):** cierra la brecha donde un `UPDATE` directo sobre `REVISION_CONCILIACION_PE2` podía pasar `VIGENTE → CONFIRMADA_T1`, `SUPERADA` o `INVALIDADA` de forma aislada sin coherencia con `CIERRE_RECORRIDO_PE2`, sin sucesora o sin efectos financieros `K2`. El constraint trigger diferido `AFTER INSERT OR UPDATE OF "estadoRevision"` relee los estados efectivos al `COMMIT` (sin usar snapshots intermedios `NEW.*`) y verifica en cada `COMMIT` la coherencia 4-vías (`REVISION_CONCILIACION_PE2` ↔ `CIERRE_RECORRIDO_PE2` ↔ `RECORRIDO_REPARTO` ↔ `COBRO_RENDICION_K2` / `PROPUESTA_APLICACION_PENDIENTE`).
- **Total de tablas candidatas de laboratorio:** **`13`** (`12` previas + `REVISION_CONCILIACION_PE2`), `0` cambios en `src/main/**`, `0` cambios en `V1–V3`.
- **Total de pruebas G-FIS de laboratorio:** **`36` tests ejecutables** en PostgreSQL 17.11 efímero (`7` preflight/integridad + `8` concurrencia/locks/generadores + `9` campaña C3 ampliada + `4` L02/C-04 contenedor aislado + **`8` rectificación escenarios A–H e integridad diferida al `COMMIT`**), sumadas a la regresión completa de `ale-milanesas-app` (**`752` tests totales**).
- **Clasificación honesta de deuda técnica SonarCloud (en código `src/test/**`):** las incidencias reportadas en PR `#101` son exclusivamente smells de estilo JUnit en `src/test/java/**/gfis/**` (`java:S5778` lambdas multi-invocación en `assertThrows`, `java:S5961` >25 aserciones en un test compuesto), **`0` Security Hotspots**, **`0` Bugs**, **`0` Vulnerabilities**, **`0.0%` duplicación**. Los enlaces exactos de `B1`, `B2` y `SonarCloud` del HEAD definitivo se adjuntan en el comentario de verificación de PR `#101` y en el reporte final al orquestador.

---

## E-AG-07B — Preparación de decisiones `B1-R1` y `B1-R2` (**PROPUESTAS — NO RATIFICADAS**)

| Decisión | Recomendación del orquestador (NO RATIFICADA) | Impacto comercial | Impacto físico en `V4` (si se ratifica) | Alternativas contrastadas | Pruebas necesarias |
|---|---|---|---|---|---|
| **`B1-R1`** | Permitir cerrar como `CONFIRMADO` cuando una rectificación auditada (`numeroRevision > 1`) elimina todas las diferencias (`faltante = 0 ∧ custodia = 0`) y existe cobertura suficiente, conservando el historial de errores/rectificaciones sin blanqueamiento ni doble Cobro. | Evita etiquetar falsamente como `CERRADO_CON_DIFERENCIA_RESUELTA` un recorrido cuya discrepancia fue un mero error de carga/recuento y terminó cuadrando al 100 %, conservando intacta la auditoría de los errores previos (`rev 1..N-1`). | **Aún NO activado en `V4_CANDIDATE`** (requiere ratificación previa). Si se ratifica, requiere 3 ajustes acotados antes de promover `V4`: (1) en `REVISION_CONCILIACION_PE2.ck_gfis_rev_montos`, exigir `faltante > 0 OR custodia > 0` solo para `numeroRevision = 1` (admitir `0/0` en `numeroRevision > 1` con `motivoRevision` obligatorio); (2) en `CIERRE_RECORRIDO_PE2`, admitir `estadoCierre = 'CONFIRMADO'` con `idRevisionVigente IS NOT NULL` cuando `faltante = 0 AND custodia = 0` (conservando prohibición estricta de `CONFIRMADO` si `faltante > 0 OR custodia > 0`); (3) en `ct_gfis_revision_coherencia_commit`, admitir `v_cierre.estadoCierre = 'CONFIRMADO'` para `CONFIRMADA_T1` solo si `faltante = 0 AND custodia = 0`. | **Opción 1 (recomendada):** permitir `PENDIENTE(rev N con diff=0) → CONFIRMADO` conservando `rev 1..N` inmutables. **Opción 2 (conservadora actual):** forzar siempre `CERRADO_CON_DIFERENCIA_RESUELTA` incluso si la diferencia era un error de tipeo (semánticamente confuso en reportes de auditoría). | (a) Positiva: `rev 1 (faltante=10) → rev 2 (faltante=0, custodia=0) → T1-C2 CONFIRMADO` con historial `rev 1 SUPERADA`, `rev 2 CONFIRMADA_T1`, `0` doble Cobro; (b) Negativa: `rev 1` directa con `0/0` rechazada, y `PENDIENTE (diff>0) → CONFIRMADO` rechazada; (c) Replay `C1-a` sobre `CONFIRMADO` post-rectificación. |
| **`B1-R2`** | Tras `INVALIDADA`, el Recorrido permanece en `PENDIENTE_CONCILIACION`, sin revisión vigente (`idRevisionVigente = NULL`) y sin habilitar `T1-C2`; solo una revisión sucesora válida (`numeroRevision = N+1`) permite continuar, sin reabrir ni reasignar automáticamente el Recorrido. | Preserva la trazabilidad de que el Recorrido ya inició rendición administrativa, impide introducir entregas o reasignaciones tardías sobre un recorrido ya en rendición y evita conflictos con `FA-1` (si el Repartidor ya inició otro Recorrido `ABIERTO`). | **Ya alineado con el prototipo actual de `V4_CANDIDATE`** (`REVISION_CONCILIACION_PE2` + `ct_gfis_revision_coherencia_commit`), marcado como **PROPUESTO** hasta ratificación. No requiere columnas ni tablas adicionales. | **Opción 1 (recomendada):** mantener `PENDIENTE_CONCILIACION` con `idRevisionVigente = NULL` hasta revisión sucesora. **Opción 2 (descartada):** volver `RECORRIDO_REPARTO` a `ABIERTO` — colisiona con `FA-1` si el Repartidor ya tiene otro Recorrido `ABIERTO` y abre riesgo de entregas/reasignaciones retroactivas. | Ya probadas en laboratorio (`escenarioH_...` + `integridadCommitRechaza...` + `integridadCommitValidaCadenaCompleta...`): `INVALIDADA` bloquea `T1-C2` (`409`), rechaza dejar enlace obsoleto al `COMMIT` (`P0001`), conserva historial y habilita continuar solo con sucesora `N+1`. |

---

## E-AG-08B — Matriz consolidada de estados (`PROPUESTO` / `RATIFICADO` / `INTEGRADO` / `PROBADO`)

| Elemento / Frente | Estado normativo | Estado en `main` | Estado de verificación |
|---|---|---|---|
| **G0 + `source-lock` + RBAC `67×8` + `BC-07` (15)** | **`RATIFICADO`** | **`INTEGRADO`** (`1263ffc1` / `27738b08`) | Intacto |
| **`ACT-G-COM-001` (`RCG-01..08`), `G-CON E-01..10`, `RD-I302-003..011`, `T1+K2` (`#70`), `G-ARQ N2` (`#71/#72`)** | **`RATIFICADO`** *(comercial/documental; `S10 = NO ACREDITADO`)* | **`INTEGRADO`** en Gobierno (`e3f4ee5a`) | Verificado documentalmente; sin código productivo |
| **Frontera A (`A1` + `FA-1` + `FA-2`)** | **`PROPUESTO`** *(NO RATIFICADO)* | Solo en PR `DRAFT #101` | **`PROBADO`** en laboratorio PG 17.11 (`src/test/**`) |
| **Frontera B (`B1` bajo `T1-C2` + Alternativa A `REVISION_CONCILIACION_PE2` + `ct_gfis_revision_coherencia_commit`)** | **`PROPUESTO`** *(NO RATIFICADO)* | Solo en PR `DRAFT #101` | **`PROBADO`** en laboratorio PG 17.11 (`src/test/**`) |
| **`B1-R1` (`PENDIENTE(rev N, diff=0) → CONFIRMADO`)** | **`PROPUESTO`** *(NO RATIFICADO)* | Documentado en `#101` *(aún no habilitado en SQL)* | **NO EJECUTADO** *(a implementar en `V4` solo si se ratifica)* |
| **`B1-R2` (`INVALIDADA` mantiene `PENDIENTE_CONCILIACION` sin reabrir Recorrido)** | **`PROPUESTO`** *(NO RATIFICADO)* | Prototipo en PR `DRAFT #101` | **`PROBADO`** en laboratorio PG 17.11 (`src/test/**`) |
| **13 tablas candidatas + `L02` 64-bit + `C-04` (`ANULACION_COBRO_IDEMPOTENCIA`)** | **`PROPUESTO`** *(CR-B0-F04-001 / `G-FIS` pendiente)* | Solo en `src/test/resources/gfis/` (`0` en `src/main/**`) | **`PROBADO`** en laboratorio PG 17.11 (`36/36` G-FIS, `752/752` total) |
| **`G-DEV / R09.1–R09.5`, `UC-07` físico (`#47/#48`), `PE-3` offline, despliegue (`Issue #28`)** | **CERRADO / DIFERIDO** | No integrado | **DIFERIDO JUSTIFICADO** |

---

## E-AG-07 — Informe de integridad (controles no negociables §6)

- **`PENDIENTE_CONCILIACION` (0 efectos financieros):** garantizado por `CHECK (importeImputadoConfirmado = 0)` + triggers en `COBRO_RENDICION_K2` y `APLICACION_RENDICION_K2` + `PROPUESTA_APLICACION_PENDIENTE` no financiera, incluso tras múltiples rectificaciones o invalidaciones.
- **`T1-C2` + `K2` + conservación `A_e + V_e^{vig} = V_e^0`:** único `COMMIT` atómico sobre la revisión `VIGENTE → CONFIRMADA_T1`; máximo 1 `COBRO REPARTO` positivo por Cliente/cierre; conservación diferida al `COMMIT` (`ct_gfis_pe1_conservacion`, `ct_gfis_k2_cobro_suma`).
- **Unicidad incondicional del cierre:** `CONSTRAINT uq_gfis_unico_cierre_por_recorrido UNIQUE ("idRecorrido")` permanece 100 % intacta.
- **Recorrido explícito y pertenencia (`FA-1` / `FA-2`):** `fn_gfis_validar_recorrido_entrega` exige `RECORRIDO_ASIGNACION(ACTIVA)` y `ASIGNACION_REPARTO` vigente; `FA-1` y `FA-2` probadas como propuestas no ratificadas; `UC-43` estrictamente `VENDEDOR`.
- **Custodia y `E-04`:** segregada de `MOVIMIENTO_CAJA`; devolución física exige `EGRESO_CUSTODIA_RENDICION` con recibo único y receptor sobre el evento de la **revisión confirmada** (rechaza eventos de revisiones superadas o invalidadas).
- **`C1-a`, `C-04`, `D7-A`, `DC05-07-A`, `IMP-B/A/C`, `RCG-06`, `A2/B2` 64-bit:** 100 % preservados y verificados en regresión; `S10 = NO ACREDITADO`.

---

## E-AG-08 / E-AG-09 / E-AG-10 — Dictamen de salida y separación `G-FIS` vs `G-DEV`

- **PR `#101`:** permanece **`OPEN / DRAFT / NO MERGE / NO DESPLEGAR`**, con **`9` archivos** exclusivamente en `src/test/**` y `docs/implementation/**` (`0` en `src/main/**`).
- **Dictamen técnico de laboratorio para `R08.6 / G-FIS`:** **APTO TÉCNICAMENTE EN LABORATORIO (`GO CONDICIONADO` a decisiones humanas; `NO-GO` automático mientras no se ratifiquen las fronteras pendientes).**
- **Decisiones comerciales / normativas humanas requeridas antes de autorizar `G-FIS` Fase C (promoción de `V4`):**
  1. Ratificar **`A1` + `FA-1` + `FA-2`** (Recorrido explícito, 1 `ABIERTO` por Repartidor, 0 `PENDIENTE` activos antes del cierre, conservando `UC-43` exclusivo `VENDEDOR`).
  2. Ratificar **`B1` corregida bajo `T1-C2` + Alternativa A (`REVISION_CONCILIACION_PE2`)**, definiendo explícitamente los 2 bordes comerciales:
     - **B1-R1:** si una rectificación que corrige un error material y reduce las diferencias a `0` (`faltante = 0 ∧ custodia = 0`) puede confirmarse como `CONFIRMADO` (conservando el historial de revisiones) o exige permanecer bajo `CERRADO_CON_DIFERENCIA_RESUELTA`.
     - **B1-R2:** si tras `INVALIDADA` (escenario H) el cierre permanece en `PENDIENTE_CONCILIACION` esperando una nueva revisión sucesora (modelo actual de Alternativa A) o si se habilita reabrir el estado operativo del Recorrido.
  3. Autorizar expresamente **`G-FIS` Fase C** (promoción de `V4` a `src/main/resources/db/migration/` con las `13` tablas aditivas, vista `IMP-A`, reemplazo `L02` 64-bit y soporte `C-04`, sin tocar `V1–V3`).
- **Puerta `G-DEV / R09.1`:** estrictamente **separada y posterior** a `G-FIS` (cutover atómico `A2/B2` en `CobroManualService`/`RegistrarMovimientoCajaService`, implementación `C-04` e `IMP-B`, y reconciliación `UC-15`/`UC-03`). Demo intacta; `Issue #28` pausada.
