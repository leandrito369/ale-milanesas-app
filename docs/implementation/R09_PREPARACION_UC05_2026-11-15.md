# R09-PRE — Preparación técnica anticipada de UC-05 online y rendición UC-29

**Identificador:** `R09-PRE`  
**Fecha:** 2026-11-15  
**Repositorio:** `leandrito369/ale-milanesas-app`  
**Rama de trabajo:** `docs/r09-preparacion-uc05-2026-11-15` (creada desde `origin/main` @ `4dbe38668fc5cbc08d04e10920e3bf165c71be11`)  
**Documento:** `docs/implementation/R09_PREPARACION_UC05_2026-11-15.md`  
**Naturaleza:** Planificación técnica, trazado de arquitectura conceptual y matriz de pruebas. **No implementa código, no agrega DDL ni Flyway `V4`, no habilita despliegue.**

> **DICTAMEN GLOBAL DE SALIDA:** **`PREPARADO DOCUMENTALMENTE / BLOQUEADO PARA IMPLEMENTACIÓN`**

---

## 0. Línea base verificada, trazabilidad y dependencia paralela (`R08.5-C`)

### 0.1. Repositorios, commits y PRs verificados

| Artefacto | Repositorio | Rama / Ref / PR | SHA verificado | Estado y vigencia |
|---|---|---|---|---|
| Aplicación base | `leandrito369/ale-milanesas-app` | `origin/main` | `4dbe38668fc5cbc08d04e10920e3bf165c71be11` | **Canónica vigente** (`V1–V3`, 62 tablas, 88 archivos `.java` en `src/main`) |
| Lock normativo G0 | `leandrito369/ale-milanesas-app` | `docs/traceability/source-lock.md` | `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` / `governance_commit=27738b08` | **G0 APROBADO**; 64 CU / 67 ops / 536 celdas RBAC / 15 filas `BC-07` / 18 escenarios `BC-06` pendientes (`0` ejecutados); `CR-B0-F04-001` y `ADR-B1-05` vigentes |
| Lab Nivel R | `leandrito369/ale-milanesas-app` | PR `#88` (`test/c3-a3-r-servicios-reales-2026-11-15`) | `5a83c7f6406d4fc156e742e0fb0bffa184f369d0` | `OPEN / DRAFT` (`C3-01`, `C3-02`; experimento, no merge) |
| Lab Nivel R/P | `leandrito369/ale-milanesas-app` | PR `#89` (`test/c3-a3-rp-intercalados-2026-11-15`) | `642abd6541f0e358d1f9124d7d80bb49234faa14` | `OPEN / DRAFT` (`C3-03`, `C3-04`, `C3-08` Nivel R/P + `C3-05` Nivel D; `RT-UC07-04` abierta) |
| Documentación local L05 | `leandrito369/ale-milanesas-app` | PR `#87` (`docs/l05-local-synthetic-2026-11-15`) | `72860a530c9366863fe2f40f3af1f8c8c2fab98d` | `OPEN / DRAFT` |
| Control de despliegue | `leandrito369/ale-milanesas-app` | Issue `#28` | — | **`OPEN` — despliegue pausado por orden del responsable** |
| Gobierno base | `leandrito369/ale-milanesas` | `main` | `cdd57ed194268f1f5ab29aa0f55781ddddebdfb5` | **Canónica vigente** (`REV04B`, `REV05`, `RD-F01`, `RD-F04-001..007`, `G0`, `RBAC`, `BC-07`, `CR-B0-F04-001`) |
| Expediente PE-1/PE-2 | `leandrito369/ale-milanesas` | PR `#44` (`RD-I302-001`) | `e5e82f843f8a579e3201a332c645f62680d56832` | `OPEN / DRAFT` (`D1–D12`, `D2=B` expresada sin asiento canónico; referencia histórica "60 tablas") |
| UC-07 CR-C y CR-F | `leandrito369/ale-milanesas` | PR `#47` (`CR-C`) / PR `#48` (`CR-F`) | `9c0a54e4a2147f5f048f7827f26a21b1bfeecf1b` / `fda51c929258dc42e71c40c95bbcd89d0324c306` | `OPEN / DRAFT` (pendiente ratificación y `RT-UC07-04`) |
| Roadmap operativo I3–I7 | `leandrito369/ale-milanesas` | PR `#49` | `d08decb8a88195c1b4e9b1fa1b84d490b46af9e5` | `OPEN / DRAFT` (fecha objetivo sistema `12/12/2026`) |
| Decisiones D5-B/D6-1/D7-A | `leandrito369/ale-milanesas` | PR `#50` (`RD-I302-002`) | `c36920e09f6ea57a96e246592ffa01be72c65e56` | `OPEN / DRAFT` (asienta decisiones funcionales del 15/11/2026: `D5-B`, `D6-1`, `D7-A`) |
| Antecedente conceptual | `leandrito369/ale-milanesas` | PR `#51` (`R08.2`) | `a1c5492def2e8f72f5def376cc0346d02292eccb` | `OPEN / DRAFT` |
| Diseño candidato PE-1/PE-2 | `leandrito369/ale-milanesas` | PR `#52` (`R08.3`) | `adf77bc2c4f3d7e88a1c301926bdd607dd6a4a78` | `OPEN / DRAFT` (asienta `ANT-01`, `D56-01..D56-16`, `P1-A` vs `P1-B`) |
| Contrato de cálculo e `IMP-01` | `leandrito369/ale-milanesas` | PR `#53` (`R08.4` + anexo `§17`) | `7448e123c3c43c51d48def6a22d10b58679efb2d` | `OPEN / DRAFT` (`C_e`, `P_e`, `D_e`, `V_e^0`, `V_e^vig`, `A_e`, `Q_e`, `T01–T30`, `IMP-A/B/C`, `DC05-01..12`, `IMP-T01..T20`) |
| Auditoría e integración contractual | `leandrito369/ale-milanesas` | PR `#54` (`R08.5-A/B/C`) | Referencia `R08.5-B`: `5fe7248ef91882a80f9b32140da6eb1cc06d3ded`; **HEAD verificado `R08.5-C`**: `cc948fcde1ac2dbfe0a78febff6fd2326b8de258` | `OPEN / DRAFT` (ver §0.2 sobre dependencia paralela y dictámenes por puerta) |

### 0.2. Registro obligatorio de dependencia paralela (`R08.5-C`) y sujeción a Puerta 3

Al momento de apertura de `R09-PRE`, `R08.5-C` se encontraba en elaboración paralela sobre `PR #54` a partir del corte `R08.5-B` (`5fe7248e`). La verificación viva de `PR #54` muestra el nuevo commit `cc948fcde1ac2dbfe0a78febff6fd2326b8de258` (`R08.5-C`, en estado `OPEN / DRAFT`).

En cumplimiento estricto de las restricciones de `R09-PRE` y de la **Puerta 3 (`R08.5-C` §10.6)** contenida en dicha PR en borrador:

1. **Ninguna conclusión ni recomendación de `PR #53` (`7448e123`) o `PR #54` (`cc948fc`) se considera ratificada normativamente mientras ambas PR permanezcan en `DRAFT` sin aprobación expresa del responsable (`leandrito369`).**
2. Las decisiones funcionales del 15/11/2026 ya comunicadas —**`D5-B`**, **`ANT-01`**, **`D6-1`** y **`D7-A`**— se respetan como marco funcional no reabierto (`PR #50`, `PR #52`), dejando constancia de que su asentamiento editorial en contratos canónicos (`E-01`..`E-07`) y el cierre de todas sus dependencias técnicas y comerciales siguen abiertos.
3. Conforme a la **Puerta 3 (`APTO CONDICIONADO / PARCIAL`)** de `R08.5-C`, este documento mantiene marcados expresamente como **`[PENDIENTE DE DECISIÓN / NO DEFINIDO]`** todos los puntos dependientes de:
   - **`IMP-01` (`DC05-01..DC05-12`):** incluida la elección arquitectónica candidata `IMP-B append-only + proyección IMP-A` vs `IMP-A` o contención `IMP-C`, la precedencia entre `P_e` y el FIFO global de `UC-15` (`DC05-04`), la liberación en `UC-06` (`DC05-06`) y el efecto de `anularCobroManual` sobre anticipos generales (`DC05-07`).
   - **Fórmulas y bordes de cálculo (`DC-01..DC-08`, `CAL-01/02`, `D2=B` formal):** incluida `C1` vs `C2` vs `C3`, `V1`/`V3` vs `V2`/`V4`, redondeo `HALF_UP`, adicionales `UC-07` (`DC-03`), borde `C_e = 0` (`DC-07`) y tipado `Q_e` (`DC-08`).
   - **Gobierno físico, rendición y trazabilidad (`D1`, `D3`, `D4`, `D9`, `D10`, `D12`, `DEC-GRAN-D6`, `DEC-UC29-ANUL`, `DEC-E01-E07`, `DC-10`/`DC05-10`):** incluidas la granularidad exacta de `D6-1`, el literal `COBRO.origen` (`'REPARTO'` vs `'ADMINISTRACION'`), la proyección administrativa `D9` en `BC-07`, la excepción parcial/levantamiento de `CR-B0-F04-001` (`D12`) y la remediación de `C-04`.
4. **Puerta 2 (`R08.6` físico / DDL `V4`) = `NO APTO / BLOQUEADO`** y **Puerta 4 (`R09` implementación productiva) = `NO APTO / BLOQUEADO`**. Este documento **no habilita** migración `V4`, DDL, DTO finales productivos ni ramas de código de `UC-05`, `UC-14`, `UC-29` rendición, `UC-06` o `UC-07`.

---

## 1. Actividad 1 — Auditoría técnica de clases actuales (`main` @ `4dbe3866`)

La auditoría directa sobre los archivos Java, migraciones Flyway `V1–V3` y configuraciones integradas en `origin/main` confirma el estado exacto por subdominio:

### 1.1. Pedidos y Reparto (`ar.edu.unam.alemilanesas.ventas`)

| Clase | CU | Transacción, locks y reglas observadas | Brechas y observaciones frente a `UC-05` / `F04` |
|---|---|---|---|
| `CargarPedidoService.java` | `UC-03` | `@Transactional`. Valida rol `VENDEDOR` (`autorizacion.exigirRoles(sesion, Set.of("VENDEDOR"))`). Idempotencia vía `idOperacionOrigen` (`uq_pedido_0`). Orden de locks: `A2(3003, 0)` → `A1(idCliente)` → `CLIENTE FOR UPDATE` → `PRODUCTO FOR SHARE` (ordenado por `idProducto`) → `PRECIO_PRODUCTO FOR SHARE`. Reevalúa categoría vía `AplicarAjusteComercialService` (`UC-16`/`15`) y congela `categoriaComercialAplicada`, `idPoliticaComercialAplicada`, `tipoAjuste`, `porcentajeAjuste` y `totalOriginal` en `PEDIDO`, más `DETALLE_PEDIDO`. Por fix `I3-02` (`2026-11-10`), `anterior(DatosPedido)` filtra `d."cantidadOriginal" > 0` para no inflar `cantidadItems` ante futuras líneas técnicas `UC-07`. | **Brecha `C-08` (latente):** no consulta deuda exigible ni `saldoExigibleProvisional` (`RD-F04-006`) al cargar Pedido. Sin impacto mientras no exista tránsito `V_e^vig`; exigible al activar `F04`. La capa web ya anticipa mapeo `409 CLIENTE_CON_DEUDA`. |
| `AsignarRepartoService.java` | `UC-43` | `VENDEDOR`. Locks observados: `A1(9172001)` → `A2(60505, 7)` → `USUARIO(Repartidor) FOR SHARE` → `PEDIDO FOR UPDATE` → `ASIGNACION_REPARTO WHERE fechaHoraFin IS NULL FOR UPDATE`. Exige Pedido `PENDIENTE` y usuario Repartidor habilitado. Cierra asignación previa vigente e inserta `numeroAsignacion + 1`. | Cualquier prototipo o futuro `UC-05` que bloquee `ASIGNACION_REPARTO FOR UPDATE` antes de `PEDIDO FOR UPDATE` genera deadlock `40P01` confirmado (`C3-03a`). |
| `BuscarPedidoService.java` | `UC-34` | `@Transactional(readOnly = true)`. Roles `ADMINISTRADOR`, `VENDEDOR`, `REPARTIDOR`, `RESPONSABLE_PRODUCCION`. Si el actor es `REPARTIDOR` (y no otro rol supervisor), restringe la búsqueda por subconsulta `ASIGNACION_REPARTO` con `fechaHoraFin IS NULL` y devuelve `PedidoRepartidor(idPedido, idCliente, fechaReparto, estado, canalOrigen)` (sin importes ni costos). | Reutilizable tal como está para que `UC-05` implemente `«include» UC-34` respetando `BC-07`. No sustituye revalidación bloqueante `PEDIDO FOR UPDATE` + `ASIGNACION_REPARTO FOR UPDATE` en la escritura de `UC-05`. |
| *(Ausentes en `ventas`)* | `UC-05`, `UC-06`, `UC-07`, `UC-41` | — | **No existen servicios ni controladores** de entrega, cancelación ni excedentes. |

### 1.2. Preparación de Producción (`ar.edu.unam.alemilanesas.produccioninventario`)

| Clase | CU | Locks observados y frontera con `UC-05` / `UC-07` | Observación técnica |
|---|---|---|---|
| `RegistrarPreparacionService.java` | `UC-28` | `RESPONSABLE_PRODUCCION`. Orden observado: `PEDIDO U` → `DETALLE_PEDIDO U` → `A2(808, 0)` → `PRODUCTO S` → `VERSION_RECETA`/`RECETA`/`DETALLE_VERSION_RECETA S` → `A1(idInsumo)` → `INSUMO U` → `PARTIDA_INSUMO U`. Descuenta insumos y congela costos en `PREPARACION` y `MOVIMIENTO_INSUMO`. | 1. **Colisión de advisory lock `A1(bigint)` (`C3-01`):** `UC-28` usa `A1(idInsumo)` y `UC-03` usa `A1(idCliente)`; si `idCliente == idInsumo`, hay contención cruzada falsa.<br>2. La convivencia de `UC-28` con `UC-05`/`UC-06`/`UC-07` sobre `PEDIDO`, `DETALLE_PEDIDO` y `EXCEDENTE` sigue en **Nivel D (`C3-05`)**. |

### 1.3. Cobro, Categoría Comercial y Cuenta Corriente (`ar.edu.unam.alemilanesas.finanzas`)

| Clase | CU | Comportamiento real verificado | Brechas y compatibilidad con `UC-05` / `D6-1` / `D7-A` / `IMP-01` |
|---|---|---|---|
| `CobroManualService.java` | `UC-29` | **Alta `registrarCobro(sesion, idCliente, idPedido, importe, medio, claveOperacion)`:** exige rol `ADMINISTRADOR` o `VENDEDOR`. Locks: `A1(2929001)` → `CLIENTE FOR UPDATE` → `[PEDIDO FOR SHARE si idPedido != null]`. Reprueba idempotencia vía `claveIdempotenciaOrigen` (`uq_cobro_0`) con comparación de payload (`idCliente`, `idPedido`, `importe`, `medio`). Rechaza `MERCADOPAGO` y `importe` fuera de rango/escala `>2`. Inserta `COBRO` con `origen = 'ADMINISTRACION'` y `estadoCobro = 'CONFIRMADO'`, `MOVIMIENTO_CUENTA('PAGO')`, `ORIGEN_MOVIMIENTO_CUENTA(idCobro)`, `MOVIMIENTO_CAJA('INGRESO','COBRO')`, y llama `evaluador.reevaluar(idCliente)`.<br>**Anulación `anularCobroManual(sesionAdmin, idCobro, motivo)`:** exige rol `ADMINISTRADOR`. Toma `A1(2929001)`, verifica `motivo`, invoca función SQL `fn_mr3_anular_cobro_manual(...)` y llama `evaluador.reevaluar(idCliente)`. | 1. **No tiene controlador HTTP** en `web/` todavía (`UC-29` solo existe en capa servicio `B3`).<br>2. **`anularCobroManual` vs `D7-A`:** `fn_mr3_anular_cobro_manual` no verifica ni existe aún vínculo con rendición `D6-1` (`C-03`).<br>3. **Brecha ejecutada `C-04`:** `anularCobroManual` no recibe clave de idempotencia ni retorna IDs de contraasientos en `Resultado(idCobro, idCliente, estado, saldoConfirmado, categoriaComercial)`; un reintento tras pérdida de respuesta post-commit falla con `IllegalArgumentException` (`Cobro ya anulado...`).<br>4. **Asimetría de lock `C-03`:** `fn_mr3_anular_cobro_manual` adquiere `COBRO U` antes que `CLIENTE U`, inverso a `registrarCobro` (`CLIENTE U` antes de `PEDIDO S` y sin lock previo de `COBRO`). Hoy lo enmascara `A1(2929001)` compartido, pero es frágil ante transacciones futuras (`T1`, `T2`, `T3`) si alguna no pasa primero por `2929001` en el mismo orden. |
| `EvaluadorCategoriaComercialInterno.java` | `UC-15` | `@Transactional` / invocado dentro de transacción llamadora. Adquiere `CLIENTE FOR UPDATE`. Suma créditos globales (`PAGO + AJUSTE_CREDITO - ANULACION_COBRO`), lista débitos (`CARGO_POR_VENTA`, `AJUSTE_DEBITO`) usando `COALESCE(e.fechaHoraEntrega, m.fechaHoraMovimiento)`, ordena cronológicamente y consume débitos en **FIFO estricto** para determinar `primeraImpaga` y fijar `EN_MORA` si `ChronoUnit.DAYS.between(primeraImpaga, ahora) > diasTolerancia`. | 1. **Cumple `RD-F04-007` (`D8`):** no lee tránsito ni filas provisionales.<br>2. **Brecha semántica `DC05-04` (`IMP-T15`):** ignora totalmente `COBRO.idPedido` y cualquier aplicación explícita por obligación; un anticipo imputado a `Pedido B` cancelaría en FIFO a `Pedido A` más antiguo salvo decisión expresa del responsable (`DC05-04-A` vs `DC05-04-B`). |
| `AplicarAjusteComercialService.java` | `UC-16` | Toma `CLIENTE FOR UPDATE`, llama `evaluador.reevaluar(idCliente)`, lee `POLITICA_CATEGORIA_COMERCIAL` y `AJUSTE_POLITICA_CATEGORIA` vigentes. Escala base con `setScale(2, HALF_UP)` y calcula ajuste `base * % / 100` con `RoundingMode.HALF_UP` al final. | Referente directo de consistencia aritmética para resolver `DC-04` / `CAL-02` cuando el responsable confirme criterio de parciales (`DC-01/DC-02`). |
| *(Ausentes en `finanzas`)* | `UC-14`, `D6-1`, `IMP-01`, `UC-26`, `UC-49`, `UC-22`, `UC-23`, `UC-27` | — | **No existen servicios ni controladores** para Cuenta Corriente, cálculo/gestión de tránsito, evidencia `IMP-01` ni rendición administrativa. |

### 1.4. Esquema físico vigente (`V1__baseline_mr5.sql`, `V2__f01_cobro_estados.sql`, `V3__trazabilidad_partidas_insumo.sql`)

- **`V1` (`60` tablas iniciales):**
  - `ENTREGA` (`pk_entrega(idPedido)`): contiene **exclusivamente** `idPedido`, `fechaHoraEntrega`, `idUsuarioRepartidor`. No tiene `idOperacionOrigen`, `efectivoDeclaradoRecibido`, `V_e^0`, `V_e^vig`, `Q_e` ni snapshot de cálculo.
  - `DETALLE_ENTREGA`: `(idPedido, numeroDetalle, cantidadEntregada >= 0, costoHistoricoElaboradoVendido)`.
  - `EXCEDENTE`, `OFERTA_EXCEDENTE`, `MOVIMIENTO_EXCEDENTE`, `IMPUTACION_ENTREGA_ADICIONAL`: presentes en `V1`, pero sin las ampliaciones propuestas en `CR-C (#47)` y `CR-F (#48)` (`COTIZACION_OFERTA_EXCEDENTE`, `EVIDENCIA_ACEPTACION_OFERTA`, `EVENTO_EVIDENCIA_ACEPTACION`).
  - `MOVIMIENTO_STOCK_REVENTA`: incluye `idPedidoEntrega`, `numeroDetalleEntrega`, `tipoMovimiento = 'EGRESO_VENTA'`, `costoTotalHistoricoVenta`, secuencia por producto (`uq_movimiento_stock_reventa_0`).
  - `COBRO`: `importe > 0`, `medioPago IN ('EFECTIVO','TRANSFERENCIA','MERCADOPAGO','OTRO')`, `origen IN ('REPARTO','ONLINE','ADMINISTRACION')`, `uq_cobro_0(claveIdempotenciaOrigen)`, `uq_cobro_1(medioPago, referenciaExterna)`.
  - `MOVIMIENTO_CUENTA`: secuencia por cliente `uq_movimiento_cuenta_0`, `tipoMovimiento IN ('CARGO_POR_VENTA','PAGO','AJUSTE_DEBITO','AJUSTE_CREDITO','ANULACION_COBRO')` (ampliado en `V1` L1098 `ck_movimiento_cuenta_2`), más `ORIGEN_MOVIMIENTO_CUENTA` (`num_nonnulls` entre `idPedidoEntrega`, `idCobro`, `idReembolso`, `idCobroAnulado` en `ck_origen_movimiento_cuenta_0` L1103).
  - `MOVIMIENTO_CAJA`: `origen IN ('MANUAL','COBRO','REEMBOLSO')` en tabla inicial + `'ANULACION_COBRO'` y `idCobroAnulado` añadidos en sección MR3 de `V1`, respaldados por constraint triggers diferidos `ct_mr3_cobro_anulado`, `ct_mr3_caja_anulacion`, `ct_mr3_origen_anulacion` (`fn_mr3_verificar_contraasiento`).
  - `fn_mr3_anular_cobro_manual(...)`: rechaza `ONLINE`, `MERCADOPAGO`, `ANULADO`, existencia en `REEMBOLSO` o falta de ingreso/pago conciliable; adquiere **`COBRO FOR UPDATE` y luego `CLIENTE FOR UPDATE`**; inserta `MOVIMIENTO_CUENTA('ANULACION_COBRO')`, `ORIGEN_MOVIMIENTO_CUENTA("idCobroAnulado")`, `MOVIMIENTO_CAJA('EGRESO','ANULACION_COBRO')` y actualiza `COBRO.estadoCobro = 'ANULADO'`.
- **`V2` (`F01`):** agrega `ck_f01_cobro_estado CHECK ("estadoCobro" IN ('CONFIRMADO','ANULADO'))` y trigger `tg_f01_transicion_cobro` (`CONFIRMADO -> ANULADO` irreversible).
- **`V3` (`MR-6`, 62 tablas totales verificadas):** añade `PARTIDA_INSUMO` y `DETALLE_MOVIMIENTO_PARTIDA`.
- **Consecuencia para `R09-PRE`:** las citas históricas a "60 tablas" en `REV04B`, `REV05` y `RD-I302-001` quedan desactualizadas frente a las **62 tablas** reales; ninguna de ellas posee estructuras para tránsito (`D_e`/`V_e`), aplicaciones por obligación (`IMP-01`) ni rendición (`PE-2`), cuya creación sigue vedada por `CR-B0-F04-001`.

### 1.5. Autorización, Seguridad HTTP y Controladores (`acceso` + `web`)

| Componente | Archivo real | Reglas de seguridad e integración observadas | Requisito para futuros endpoints `UC-05` / `UC-14` / `UC-29` |
|---|---|---|---|
| Autorización de sesión | `acceso/AutorizacionSesionInterna.java` | Verifica en servidor `SESION_USUARIO` no revocada/no expirada (`clock_timestamp()`), `USUARIO.estado = 'HABILITADO'`, `cambioCredencialPendiente = false` y presencia de alguno de los roles exigidos en `USUARIO_ROL`. | Toda escritura u operación sensible deberá invocar `exigirRoles` en servicio y nunca confiar en parámetros de cliente o rol en token sin revalidar DB. |
| Seguridad HTTP Bearer | `web/SeguridadHttpInterna.java` | Cadena stateless `@Order(1)` para `/api/v1/**` con `FiltroSesionBearer` (`PrincipalInterno(idSesion, idUsuario)`). Posee helpers `administradorAutenticado`, `vendedorAutenticado`, `produccionAutenticada` vinculados a propiedades `ale.http.writes.*`. | **Carece hoy de `repartidorAutenticado(...)`** para endpoints de escritura de `Repartidor` (`UC-05`) y de rutas HTTP para `finanzas` (`UC-14`, `UC-29`). |
| Guard de despliegue fail-closed | `web/PrecondicionesDespliegue.java`, `application.yml`, `application-prod.yml` | En `application.yml`, todos los flags `ale.http.writes.*` valen `false` por defecto. Todo controlador HTTP de escritura lleva `@Profile("!prod")` y `@ConditionalOnProperty(prefix="ale.http.writes", name="...", havingValue="true")`, y `PrecondicionesDespliegue` aborta el arranque `prod` si alguna bandera experimental está activa o falta `127.0.0.1` / `singleton-db-guard`. | Cualquier futuro controlador `UC-05` o `UC-29` deberá nacer bajo `@Profile("!prod")`, bandera `ale.http.writes.* = false`, registro en `PrecondicionesDespliegue` y prueba en `PrecondicionesDespliegueTest` hasta recibir habilitación explícita de etapa. |
| Controladores y errores | `web/CargarPedidoHttpController.java`, `web/AsignarRepartoHttpController.java`, `web/BuscarPedidoHttpController.java`, `web/comun/ErroresHttpGlobales.java` | Parser estricto `JsonMapper` (`USE_BIG_DECIMAL_FOR_FLOATS`, `USE_BIG_INTEGER_FOR_INTS`, `FAIL_ON_TRAILING_TOKENS`, `STRICT_DUPLICATE_DETECTION`), `Cache-Control: no-store`, conjunto cerrado de `CAMPOS` JSON y `@RestControllerAdvice` específico por controlador extendiendo `ErroresHttpGlobales`. | Mantener exactamente esta disciplina de deserialización estricta (`BigDecimal`, claves cerradas, cero `Cache-Control` almacenable) en `UC-05`, `UC-14` y `UC-29`. |
| Instancia única | `web/UnicaInstanciaPostgres.java` | Retiene advisory lock de sesión `(4402, 2026)` para evitar múltiples instancias sin coordinación. | No usar el namespace `(4402, 2026)` ni locks globales sin catálogo unificado. |

---

## 2. Actividad 2 — Inventario de componentes reutilizables vs componentes futuros

En respeto estricto a las 7 fronteras modulares verificadas por `ModuleSkeletonTest` (`acceso`, `maestros`, `ventas`, `produccioninventario`, `abastecimiento`, `finanzas`, `canalesintegraciones`):

### 2.1. Componentes existentes reutilizables tal cual (sin cambios en `UC-05` / `UC-29`)

| Módulo | Ruta exacta verificada | Uso dentro de `R09` |
|---|---|---|
| `acceso` | `src/main/java/ar/edu/unam/alemilanesas/acceso/AutorizacionSesionInterna.java` | Autenticación/autorización en servidor para `UC-05` (`REPARTIDOR`), `UC-14` (`VENDEDOR`), `UC-29` (`ADMINISTRADOR`/`VENDEDOR`), `UC-06` y futura proyección `D9` (`ADMINISTRADOR`). |
| `acceso` | `src/main/java/ar/edu/unam/alemilanesas/acceso/SesionesUsuarioService.java` | Gestión de sesiones activas usadas por pruebas y HTTP (`FiltroSesionBearer`). |
| `maestros` | `src/main/java/ar/edu/unam/alemilanesas/maestros/BuscarClienteService.java` | Base obligatoria de `UC-14 «include» UC-33`. |
| `ventas` | `src/main/java/ar/edu/unam/alemilanesas/ventas/BuscarPedidoService.java` | Base obligatoria de `UC-05 «include» UC-34` y `UC-06 «include» UC-34`; ya proyecta alcance acotado al `REPARTIDOR` por asignación vigente. |
| `ventas` | `src/main/java/ar/edu/unam/alemilanesas/ventas/AsignarRepartoService.java` | Provee la asignación vigente (`ASIGNACION_REPARTO WHERE fechaHoraFin IS NULL`) y fija la arista `PEDIDO U -> ASIGNACION_REPARTO U`. |
| `finanzas` | `src/main/java/ar/edu/unam/alemilanesas/finanzas/AplicarAjusteComercialService.java` | Referencia de política/escala histórica ya congelada en `PEDIDO`. |
| `web` | `src/main/java/ar/edu/unam/alemilanesas/web/comun/ErroresHttpGlobales.java`, `src/main/java/ar/edu/unam/alemilanesas/web/UnicaInstanciaPostgres.java` | Manejo estándar de errores HTTP internos y lock singleton de aplicación. |
| `db/migration` | `src/main/resources/db/migration/V1__baseline_mr5.sql`, `V2__f01_cobro_estados.sql`, `V3__trazabilidad_partidas_insumo.sql` | Línea base intangible (`62` tablas). Ninguna se edita retroactivamente. |

### 2.2. Componentes existentes que requerirán extensión o corrección controlada en `R09` *(bloqueados hasta aprobación)*

| Módulo | Ruta exacta verificada | Adaptación futura requerida y condición bloqueante |
|---|---|---|
| `finanzas` | `src/main/java/ar/edu/unam/alemilanesas/finanzas/CobroManualService.java` | 1. Corregir `C-04` (clave idempotente + detección de conflicto + contrato de salida en `anularCobroManual` según `DEC-UC29-ANUL`).<br>2. Hacer cumplir `D7-A` en servidor rechazando anulación de `COBRO` con vínculo relacional de rendición, manteniendo intactos los cobros `'ADMINISTRACION'` (`bc0604–0606`).<br>3. Implementar comportamiento que decida **`DC05-07`** si el Cobro general tiene reserva/aplicación previa de anticipo.<br>4. Mantener **separado** el Subflujo B de rendición `D6-1` (`ADMINISTRADOR` exclusivo). |
| `db` (`V1` → futura `V4`) | `fn_mr3_anular_cobro_manual` en `V1__baseline_mr5.sql` | Sin editar `V1`, futura migración autorizada `V4` deberá reemplazar/corregir `fn_mr3_anular_cobro_manual` para: (i) verificar vínculo `D7-A` en SQL, (ii) soportar idempotencia `C-04` si corresponde en DB, y (iii) alinear orden de locks `CLIENTE U` vs `COBRO U` (`DC-10`/`DC05-10`). |
| `finanzas` | `src/main/java/ar/edu/unam/alemilanesas/finanzas/EvaluadorCategoriaComercialInterno.java` | **Sin cambios hoy (`RD-F04-007` cumplido).** Solo se adaptaría en `R09.4` si el responsable elige la alternativa de mora consciente de imputación bajo **`DC05-04`**. Si elige coexistencia (`DC05-04-A`), se mantiene sin cambios. |
| `ventas` | `src/main/java/ar/edu/unam/alemilanesas/ventas/CargarPedidoService.java` | En `R09.4`, cerrar brecha **`C-08`** (`RD-F04-006`) incorporando validación de deuda/provisional cuando exista tránsito habilitado y política/umbral aprobados. |
| `web` | `src/main/java/ar/edu/unam/alemilanesas/web/SeguridadHttpInterna.java`, `PrecondicionesDespliegue.java`, `application.yml` | Añadir `repartidorAutenticado`, flags `ale.http.writes.*` (por defecto `false`) y guardas `prod` para nuevos endpoints de escritura. |

### 2.3. Componentes futuros completamente nuevos (`NO EXISTEN HOY — NO IMPLEMENTAR AÚN`)

| Categoría | Componente futuro propuesto | Módulo destino | Dependencia bloqueante |
|---|---|---|---|
| Entrega online | Servicio transaccional `RegistrarEntregaService` (`UC-05`) + DTOs y validación de snapshot/idempotencia | `ventas` | `R08.6` (`V4`), `D1`, `D3`, `DC05-*`, `DC-01..07` |
| Cálculo entrega | Servicio/puerto puro + orquestador de cálculo `C_e`, `P_e`, `V_e^0`, `Q_e` | `ventas` / `finanzas` *(frontera a fijar por `ModuleBoundary`)* | `DC-01..08`, `D2=B`, `IMP-01` |
| Evidencia anticipos | Servicio/repositorio `IMP-01` (`IMP-B append-only + proyección IMP-A` candidato, con fallback `IMP-C`) | `finanzas` | `DC05-01..12`, `D12`, `V4` |
| Rendición cierre | Servicio `RendirEfectivoRepartoService` (subflujo B `D6-1` de `UC-29`) | `finanzas` | `D6-1`, `DEC-GRAN-D6`, `D4`, `DC-08`, `D10`, `PE-2` (`V4`) |
| Consultas BC-07 | `ConsultarCuentaCorrienteService` (`UC-14`, `VENDEDOR`) y servicio/proyección `D9` (`ADMINISTRADOR`) | `finanzas` | `E-06`, `D9` (`BC-07`), `PE-1`/`PE-2` |
| Cancelación | `CancelarPedidoService` (`UC-06`) con liberación `IMP-01` (`DC05-06`) y `EXCEDENTE` | `ventas` | `DC05-06`, `CR-C`/`CR-F` o acotamiento inicial |
| Capa HTTP | `RegistrarEntregaHttpController`, `CobroManualHttpController` / `RendicionRepartoHttpController`, `ConsultarCuentaCorrienteHttpController` | `web` | Servicios aprobados + `ale.http.writes.*` + `BC-07` |
| Esquema físico | Futura migración Flyway `V4__*` (`PE-1`, `PE-2`, `IMP-01`, reemplazo `fn_mr3_anular_cobro_manual`) | `resources/db/migration` | **Puerta 2 (`R08.6`) + levantamiento parcial `CR-B0-F04-001` (`D12`)** |

---

## 3. Actividad 3 — Arquitectura conceptual e interfaces candidatas *(NO en `src/main/**`)*

> [!WARNING]
> **CONTENIDO EXCLUSIVAMENTE CONCEPTUAL (`Puerta 3` de `R08.5-C`):** Las interfaces Java de esta sección son bocetos de arquitectura orientados a desacoplar responsabilidades antes de codificar. **NO constituyen contratos públicos definitivos de API/DTO ni están autorizadas para escribirse en `src/main/**`.** Todo elemento dependiente de decisiones abiertas conserva su marcador explícito `[PENDIENTE DE DECISIÓN / NO DEFINIDO]`.

La separación estricta de responsabilidades distingue **cinco puertos conceptuales** para impedir el acoplamiento observado en redacciones históricas previas (donde se mezclaba declaración `D_e` con tránsito `V_e^0`, `COBRO.idPedido` con imputación y cobro general con rendición):

```text
[UC-05 Controlador / Capa App]
   |
   +--> 1. PuertoRegistroEntregaOperativa (ventas: ENTREGA, DETALLE_ENTREGA, REVENTA, EXCEDENTE)
   |       |
   |       +--> 3. PuertoEvidenciaImputacionAnticipo [IMP-01] (finanzas: consulta P_e bajo lock + snapshot)
   |       +--> 2. PuertoCalculoFinancieroEntrega (calcula C_e, V_e^0, Q_e^decl; conserva D_e)
   |       +--> Emite CARGO_POR_VENTA (C_e) + Uc15Evaluador (RD-F04-007: saldo confirmado)
   |       +--> Registra decisión/tránsito provisional [PE-1 — PENDIENTE V4 / D1 / DC-06]
   |
[UC-14 / D9 Consultas Read-Only (finanzas)]
   +--> 4a. ConsultarCuentaCorriente (VENDEDOR exclusivo): saldoConfirmado - SUM(V_e^vig)
   +--> 4b. ConsultarRendicionesCierreD9 (ADMINISTRADOR exclusivo) [PENDIENTE BC-07 D9]

[UC-29 Subflujo B — Rendición Administrativa D6-1 (finanzas)]
   +--> 5. PuertoRendicionEfectivoReparto (ADMIN exclusivo): aplica 0 < a <= V_e^vig -> cese atómico + COBRO + PAGO + CAJA + UC-15
```

### 3.1. Puerto 1 — Registro operativo de Entrega (`UC-05` en `ventas`)

```java
// PROPUESTA CONCEPTUAL — NO IMPLEMENTADA — PROHIBIDO COPIAR A src/main/**
public interface RegistroEntregaOperativa {

    ResultadoEntregaOnline registrarEntregaOnline(
            UUID idSesionRepartidor,          // Validado en servidor: rol REPARTIDOR
            ComandoEntregaOnline comando);    // Incluye idPedido, items, efectivoDeclarado, claveIdempotencia

    record ComandoEntregaOnline(
            long idPedido,
            List<ItemCantidadEntregada> items, // Originales + [PENDIENTE DC-03: adicionales UC-07]
            BigDecimal efectivoDeclaradoRecibido, // >= 0 (opcional en UI => 0 si ausente [A CONFIRMAR])
            UUID claveIdempotencia               // [PENDIENTE D1/D3: alcance replay y rechazo previo]
    ) {}

    record ResultadoEntregaOnline(
            long idPedido,                       // PK real de ENTREGA
            boolean creadaEnEstaLlamada,
            BigDecimal cargoEfectivoVentaCe,     // [PENDIENTE DC-01..04]
            BigDecimal pagosPreviosAplicadosPe,  // [PENDIENTE IMP-01 / DC05-*]
            BigDecimal efectivoDeclaradoDe,      // Conservado íntegro (D2=B)
            BigDecimal reconocimientoInicialVe0, // [PENDIENTE DC-06: V1/V3 vs V2/V4/IMP-C]
            BigDecimal excesoDeclaradoQeDecl,    // [PENDIENTE DC-08 / D2=B]
            List<ExcedenteGeneradoResumen> excedentesGenerados
            // NOTA: No retorna COBRO ni MOVIMIENTO_CAJA porque UC-05 jamás los crea.
    ) {}
}
```

**Responsabilidades delimitadas:** valida sesión/rol `REPARTIDOR` y asignación vigente sin cerrar (`ASIGNACION_REPARTO.fechaHoraFin IS NULL`) bajo orden de locks compatible con `C3`; verifica replay/conflicto idempotente por `claveIdempotencia` (`[PENDIENTE D3 y T27/T28]`); persiste `ENTREGA` y `DETALLE_ENTREGA`; decrementa `PRODUCTO.stockActualReventa` e inserta `MOVIMIENTO_STOCK_REVENTA ('EGRESO_VENTA')` con costo histórico; crea `EXCEDENTE ('ENTREGA')` por sobrante elaborado preparado/reasignado no entregado; invoca al Puerto 2 y 3 para `C_e`, `P_e`, `D_e`, `V_e^0`, `CARGO_POR_VENTA` y `EvaluadorCategoriaComercialInterno`.

### 3.2. Puerto 2 — Motor de cálculo financiero de Entrega (`C_e`, `P_e`, `D_e`, `V_e^0`, `Q_e`)

```java
// PROPUESTA CONCEPTUAL — NO IMPLEMENTADA — PROHIBIDO COPIAR A src/main/**
public interface CalculadoraFinancieraEntrega {

    ResultadoCalculoEntrega calcular(EntradaCalculoEntrega entrada);

    record EntradaCalculoEntrega(
            PedidoCongelado pedidoHistorico,       // precios, promos, ajuste congelados
            List<DetalleEntregadoValorizado> items, // incluye [PENDIENTE DC-03: adicionales UC-07]
            SnapshotAnticipoPe anticipoPe,          // Provisto por Puerto 3 (IMP-01); jamás COBRO.idPedido solo
            BigDecimal efectivoDeclaradoDe          // D_e >= 0
    ) {}

    record ResultadoCalculoEntrega(
            BigDecimal cargoRealCe,                 // [PENDIENTE DC-01..04: C1 vs C2 vs C3 + redondeo]
            BigDecimal anticipoAcreditadoPe,        // [PENDIENTE DC05-08: tope P_e <= C_e]
            BigDecimal pendienteElegibleEe,         // [PENDIENTE DC-06]
            BigDecimal efectivoDeclaradoConservadoDe, // Inmutable D2=B
            BigDecimal reconocimientoProvisionalVe0,// [PENDIENTE DC-06/07: V1 vs V2/V4/IMP-C]
            DiferenciaTipadaQe diferenciasQe,        // [PENDIENTE DC-08: Q_e^decl, Q_e^rend, Q_e^falt]
            String reglaCalculoAplicadaVersion      // Auditoría estilo V3 (`R08.4` §7)
    ) {}
}
```

**Marcadores obligatorios:**
- **Cálculo de `C_e`:** `[PENDIENTE DE DECISIÓN DC-01, DC-02, DC-03, DC-04 / CAL-02]`. No se codifica `C1`, `C2` ni `C3` ni se asume prorrateo porcentual de `tipoAjuste`/`porcentajeAjuste` ni inclusión de `UC-07` hasta ratificación expresa del responsable.
- **Cálculo de `V_e^0` y borde `C_e = 0`:** `[PENDIENTE DE DECISIÓN DC-06, DC-07, D2=B formal]`. Aun si el responsable elige `V1` (`E_e = max(0, C_e - P_e); V_e^0 = min(D_e, E_e)`), su cálculo exige previamente tener resuelto `IMP-01` (`DC05-01..12`) y persistido su snapshot (`T28`). Si `C_e = 0` y `D_e > 0`, `[PENDIENTE DC-07]`: conservar `D_e` con `V_e^0 = 0` y `Q_e^decl = D_e` vs rechazar comando.

### 3.3. Puerto 3 — Proveedor canónico de evidencia de imputación (`IMP-01` — `ANT-01`)

```java
// PROPUESTA CONCEPTUAL — NO IMPLEMENTADA — PROHIBIDO COPIAR A src/main/**
public interface ProveedorImputacionAnticipos {

    // Solo lectura bajo lock de transacción (inv. 10 §17.7 R08.4)
    SnapshotAnticipoPe obtenerSnapshotParaEntrega(long idPedido, long idCliente);

    // Consolidación al confirmar Entrega UC-05 [PENDIENTE DC05-01/02/08]
    void consolidarAplicacionEnEntrega(long idPedido, long idEntrega, BigDecimal cargoRealCe, UUID claveOperacion);

    // Liberación explícita ante cancelación UC-06 [PENDIENTE DC05-06]
    void liberarReservasPorCancelacion(long idPedido, UUID claveOperacion);

    // [PENDIENTE DC05-03 / DC05-09: IMP-B append-only + proyección IMP-A vs fallback IMP-C]
    record SnapshotAnticipoPe(
            BigDecimal importeAplicablePe,          // >= 0; 0 bajo IMP-C o sin evidencia canónica
            long versionCorteSnapshot,              // Evita confirmaciones obsoletas (T30 / IMP-T09)
            ModoEvidenciaImputacion modo,           // CANONICO_IMPUTADO vs CONTENCION_IMP_C [PENDIENTE DC05-09]
            List<TramoCobroAplicado> tramos         // Porciones trazables por idCobro (nunca COBRO.idPedido suelto)
    ) {}
}
```

**Invariantes no negociables que gobiernan este puerto:**
- Prohíbe explícitamente usar `COBRO.idPedido`, el saldo global a favor del `Cliente` o el FIFO de `EvaluadorCategoriaComercialInterno` como sustituto de `P_e` (`ANT-01`, `D5-B`, `DC05-11`, `IMP-T02`, `IMP-T06`).
- Ningún método aquí crea `COBRO`, segundo `PAGO` ni segundo `MOVIMIENTO_CAJA` (`inv. 5 §17.7 R08.4`).
- El ciclo reserva pre-reparto vs consolidación efectiva (`DC05-01/02`), recorte ante parcial `C_e < reservado` (`DC05-08`), autorización RBAC de alta/corrección (`DC05-03`) y comportamiento en `anularCobroManual` (`DC05-07`) permanecen **`[PENDIENTE DE DECISIÓN DC-05]`**.

### 3.4. Puerto 4 — Reconocimiento provisional y consultas segregadas (`UC-14` vs `D9`)

```java
// PROPUESTA CONCEPTUAL — NO IMPLEMENTADA — PROHIBIDO COPIAR A src/main/**
public interface ConsultaCuentaCorrienteService { // UC-14: SOLO VENDEDOR (ADMIN = X)
    CuentaCorrienteProvisional consultarCuentaCorriente(
            UUID idSesionVendedor,                  // exigirRoles("VENDEDOR") exclusivamente
            long idCliente,                         // precondición UC-33
            LocalDate fechaDesde,
            LocalDate fechaHasta);
}

public interface ConsultaRendicionAdministrativaD9 { // D9 (BC-07): SOLO ADMINISTRADOR [PENDIENTE RD]
    VistaCierreRecorridoAdmin consultarRecorridoParaRendicion(
            UUID idSesionAdmin,                     // exigirRoles("ADMINISTRADOR") exclusivamente
            CriterioFiltroRecorrido filtro);        // Sin exponer MOVIMIENTO_CUENTA completo de UC-14
}
```

**Reglas estructurales:**
- `UC-14` mantiene `ADMINISTRADOR = X` (`RD-B0-BC01`, `C-06`, `E-06`). El rango `[fechaDesde, fechaHasta]` solo filtra las filas del historial devuelto y deja intactos `saldoConfirmado`, `pagosEnTransitoValidados = Sum(V_e^vig(t))` y `saldoExigibleProvisional = saldoConfirmado - pagosEnTransitoValidados`.
- En caso de contención transitoria `IMP-C` (`[PENDIENTE DC05-09/12]`), el reconocimiento omitido no reduce `saldoExigibleProvisional`.
- Los campos exactos visibles en `UC-05` (`REPARTIDOR`), `UC-14` (`VENDEDOR`) y `D9` (`ADMINISTRADOR`) quedan **`[PENDIENTE DE DECISIÓN D9 / BC-07]`**.

### 3.5. Puerto 5 — Subflujo administrativo de Rendición (`D6-1` dentro de `UC-29`)

```java
// PROPUESTA CONCEPTUAL — NO IMPLEMENTADA — PROHIBIDO COPIAR A src/main/**
public interface RendicionEfectivoRepartoService { // UC-29 Subflujo B (D6-1)

    ResultadoRendicion registrarRendicionRecorrido(
            UUID idSesionAdmin,                     // exigirRoles("ADMINISTRADOR") — Vendedor/Repartidor DENEGADOS
            ComandoRendicionRecorrido comando);

    record ComandoRendicionRecorrido(
            Long idRecorridoOAgrupacion,            // [PENDIENTE DEC-GRAN-D6: granularidad por recorrido vs detalle]
            List<AplicacionEntregaRendida> aplicaciones, // Cada ítem: idPedidoEntrega + importeRecibidoAplicado
            BigDecimal totalEfectivoFisicoRecibido, // [PENDIENTE D10 / D56-09: si recibido > Sum(V_e^vig)]
            String medioPago,                       // Catalogado en V1 (`ck_cobro_1`, p.ej. 'EFECTIVO')
            String referenciaEvidenciaD4,           // [PENDIENTE D4: evidencia mínima de recepción]
            String observacion,
            UUID claveIdempotenciaRendicion         // Reintentos seguros de rendición
    ) {}
}
```

**Contratos funcionales ya fijados (sin habilitar implementación):**
- **Actor exclusivo:** `ADMINISTRADOR` (`D6-1`, `RD-F04-002`, `RD-RP007-001`). `VENDEDOR` conserva únicamente Subflujo A (`CobroManualService.registrarCobro` con `origen='ADMINISTRACION'`). `REPARTIDOR` nunca ejecuta `UC-29`.
- **Cese atómico e invariante `0 < a <= V_e^vig(t)`:** por cada entrega rendida, la porción aplicada `a` genera (dentro de la misma transacción) `COBRO(CONFIRMADO)`, `MOVIMIENTO_CUENTA('PAGO')`, `MOVIMIENTO_CAJA('INGRESO','COBRO')`, actualización de `EvaluadorCategoriaComercialInterno` (`UC-15`), **cese atómico de `a` en `V_e^vig(t)`** y **vínculo relacional explícito Cobro–rendición** para `D7-A`.
- **Diferencias sin Caja ficticia (`RD-F04-005`):** un faltante (`Q_e^falt`) o exceso declarado (`Q_e^decl`) nunca crea `COBRO` ni `MOVIMIENTO_CAJA`, ni restablece automáticamente la deuda exigible del `Cliente`.
- **Pendientes bloqueantes de este puerto:** **`[PENDIENTE DEC-GRAN-D6]`** (transacción monolítica vs cabecera + aplicaciones atómicas por Cliente/entrega, respetando `COBRO.idCliente NOT NULL`), literal `COBRO.origen` (`'REPARTO'` vs `'ADMINISTRACION'`), semántica de `idUsuarioRegistrador`, **`[PENDIENTE D4]`**, **`[PENDIENTE DC-08 / D10 / D56-09]`**.

---

## 4. Actividad 6 — Diseño de protección `D7-A` en `UC-29` sin afectar cobros manuales generales y cierre de `C-04`

### 4.1. Principio rector (`D7-A`, `C-03`, `E-05`)

La decisión funcional **`D7-A`** (15/11/2026) ordena bloquear **en el servidor** la anulación manual de cualquier `COBRO` vinculado a una rendición de `UC-05` (`D6-1`), mientras no exista autorización futura de contraaplicación (`D7-B`), **sin restringir ni degradar en nada** la anulación manual general de cobros `'ADMINISTRACION'` (`CobroManualServiceIntegrationTest.bc0604..bc0606` y `CobroManualCommitRollbackIntegrationTest`).

### 4.2. Mecanismo relacional en servidor y doble barrera Servicio + SQL

Se **prohíbe** deducir `D7-A` únicamente inspeccionando `COBRO.origen` (dado que `COBRO.origen` podría compartir literales o evolucionar y el vínculo auditable debe señalar exactamente qué rendición/aplicación respalda al Cobro). Cuando `R08.6` / `V4` autoricen las tablas de `PE-2`, la protección `D7-A` operará en **dos capas solidarias**:

1. **Vínculo relacional inequívoco (`PE-2` en futura `V4`):**
   - La entidad de aplicación/rendición (`APLICACION_PAGO_EN_TRANSITO` o cabecera/detalle de rendición asociada) mantendrá `FK` obligatoria a `COBRO("idCobro")` (y cardinalidad explícita definida en `DEC-GRAN-D6`), de modo que exista una consulta `EXISTS` exacta e indexada que identifique cualquier `COBRO` originado o vinculado con rendición `UC-05`.
   - Adicionalmente, los registros de rendición `PE-2` serán no borrables (`append-only` / sin `DELETE` en aplicación ni triggers permisivos).
2. **Validación en `CobroManualService.anularCobroManual` (capa Java):**
   - Antes de intentar el contraasiento, tras validar sesión/rol `ADMINISTRADOR` y bloquear en el orden unificado `C3` (`DC-10`), verifica el vínculo relacional de rendición; si existe, lanza excepción de negocio mapeada a HTTP `409 CONFLICT` (`COBRO_VINCULADO_A_RENDICION_D7A`, mensaje claro indicando que el cobro corresponde a rendición de reparto sujeta a `D7-A`), sin mutar `COBRO`, `MOVIMIENTO_CUENTA`, `MOVIMIENTO_CAJA` ni tránsito.
   - Si **no** existe vínculo con rendición (`origen = 'ADMINISTRACION'` general sin vínculo `PE-2`), procede normalmente la verificación y el contraasiento. *(Queda `[PENDIENTE DE DECISIÓN DC05-07]` qué ocurre si un cobro manual general posee una reserva/aplicación previa de anticipo `IMP-01` aún activa: bloquear hasta liberación previa vs invalidación compensatoria atómica en `IMP-B`.)*
3. **Guarda ineludible en base de datos (`fn_mr3_anular_cobro_manual` en futura `V4`):**
   - Si alguna invocación directa a la función SQL intentara anular un `idCobro` existente en la tabla/relación de rendición `PE-2`, `fn_mr3_anular_cobro_manual` lanzará `RAISE EXCEPTION` (`D7-A: cobro vinculado a rendición de reparto no anulable manualmente`). De este modo, **ni un defecto en Java ni una llamada SQL saltan `D7-A`**.
   - Se conserva intacto el rechazo preexistente para `origen = 'ONLINE'`, `medioPago = 'MERCADOPAGO'`, `estadoCobro = 'ANULADO'` o existencia en `REEMBOLSO`.

### 4.3. Corrección planificada de `C-04` (`anularCobroManual`: idempotencia y salida)

Actualmente, `CobroManualService.anularCobroManual(UUID sesionAdmin, long idCobro, String motivo)` tiene una **brecha real ejecutada en `main`**: si el commit tiene éxito y el cliente pierde la respuesta HTTP, un reintento arroja `IllegalArgumentException` porque `fn_mr3_anular_cobro_manual` ve `estadoCobro = 'ANULADO'`. Asimismo, `REV05` §3 L113 menciona devolver IDs de contraasientos ausentes en `CobroManualService.Resultado`.

- **Diseño candidato para remediar `C-04` (condicionado a `DEC-UC29-ANUL` y futura `V4` / o fix autorizado en `R09.1`):**
  1. Incorporar `claveIdempotenciaAnulacion: UUID` en el comando de servicio (preservando sobrecarga temporal de compatibilidad o adaptando callers internos/tests bajo autorización explícita) con restricción `UNIQUE` en columna o registro de anulaciones idempotentes.
  2. Comparar `(idCobro, normalize(motivo))` al detectar clave existente: igual contenido → retornar resultado previo sin reejecutar `fn_mr3_anular_cobro_manual`; mismo UUID con distinto `idCobro` o `motivo` → rechazar con conflicto (`409`).
  3. Definir mediante `DEC-UC29-ANUL` si `Resultado` amplía campos para exponer `idMovimientoCuentaAnulacion` e `idMovimientoCajaAnulacion` (`REV05` L113) o si `REV05` se alinea al DTO actual.
- **Vinculación con `D3`:** confirmar por decisión expresa `D3` que las fallas previas a escritura (`SecurityException`, `D7-A`, `ONLINE`, `motivo` vacío) hacen rollback total y **no** consumen `claveIdempotenciaAnulacion`.

---

## 5. Actividad 8 — Plan por incrementos `R09.1`–`R09.5` y compuertas con `R08.5-C` y `R08.6`

Ningún incremento productivo (`R09.1`–`R09.5`) puede iniciar ejecución en código hasta que el responsable cierre las compuertas previas de `R08.5-C` y `R08.6`:

```text
[Hoy: R09-PRE (Puerta 3 APTO CONDICIONADO)]
   ==> Hito G1: Responsable decide P0-P2 (§10.5 R08.5-C: DC05-*, DC-01..08, D2=B, D3/D4, D9, D10, D12, DEC-GRAN-D6, DEC-UC29-ANUL)
   ==> Hito G2: Tanda 2 Gobierno (E-01..E-07 aplicados en REV04B/REV05/RD-I302-001 + D12 parcial CR-B0-F04-001 + convergencia CR-C/CR-F #47/#48)
   ==> Hito G3: Puerta 2 — R08.6 Físico (DDL V4 candidata + C3 ampliada en PostgreSQL 17.11 efímero + autorización expresa responsable)
   ==> Hito G4: Puerta 4 — R09 Implementación productiva habilitada (R09.1 -> R09.2 -> R09.3 -> R09.4 -> R09.5)
```

| Incremento | Alcance técnico exacto | Criterios obligatorios de **inicio** | Criterios verificables de **cierre (DoD)** |
|---|---|---|---|
| **`R09.1` — Fundaciones físicas `V4`, remediación `C-04` y base `IMP-01`** | Aplicar migración `V4` autorizada en `R08.6` (`PE-1`, `PE-2`, `IMP-01`, reemplazo `fn_mr3_anular_cobro_manual` con fix orden locks `CLIENTE U`/`COBRO U`, guarda `D7-A` e idempotencia `C-04`). Implementar módulo/servicio `ProveedorImputacionAnticipos` (`IMP-B + IMP-A` o modelo ratificado) y remediación `C-04` en `CobroManualService`. | 1. Puerta 2 (`R08.6`) aprobada con `V4` cerrada.<br>2. Decisiones **P0/P1/P2** cerradas (`DC05-01..09/11`, `DEC-UC29-ANUL`, `DC-10`/`DC05-10`, `D12`).<br>3. `B2SchemaMigrationTest` preparado para validar `V1–V4`. | 1. `mvn -B -ntp clean verify` verde en PostgreSQL 17.11.<br>2. Regresión intacta en `CobroManualServiceIntegrationTest` (`bc0601–0606`) y `CobroManualCommitRollbackIntegrationTest`.<br>3. Pruebas `IMP-T01..T14`, `IMP-T16`, `C-04` replay/conflicto e inversión `COBRO U -> CLIENTE U` verificadas sin `40P01`. |
| **`R09.2` — `UC-05` online completo (`PE-1` + cálculo `C_e/P_e/V_e^0/Q_e` + stock/excedentes)** | Implementar `RegistrarEntregaService` (`ventas`), `CalculadoraFinancieraEntrega` (`finanzas`/`ventas`), emisión `CARGO_POR_VENTA`, `MOVIMIENTO_STOCK_REVENTA`, `EXCEDENTE('ENTREGA')`, llamada a `UC-15` (`RD-F04-007`), `ENTREGA.idOperacionOrigen` y persistencia `D_e`, `V_e^0`, `V_e^vig`, `Q_e^decl`. Endpoint `POST` `UC-05` tras flag `ale.http.writes.* = false` + `repartidorAutenticado`. | 1. Cierre verde de `R09.1`.<br>2. Fórmulas `C_e` (`DC-01/02/04`), `V_e^0` (`DC-06`), borde `C_e = 0` (`DC-07`), asiento `D2=B` y regla `DC-03` (`UC-07`) ratificados. | 1. Suites reales Testcontainers para `T01–T15`, `T17`, `T20–T21`, `T23–T30`, `IMP-T17`, `IMP-T18`.<br>2. Concurrencia `C3-03` y `C3-08` probada ahora en **Nivel R puro** (ambos servicios reales sin `40P01`).<br>3. Negativas RBAC (`VENDEDOR`, `ADMIN`, `PROVEEDOR`, sin sesión, Repartidor no asignado → `403`/`SecurityException`). |
| **`R09.3` — Consulta `UC-14`, vista `D9` y rendición `UC-29` Subflujo B (`PE-2` + `D7-A`)** | Implementar `ConsultarCuentaCorrienteService` (`UC-14`, exclusivo `VENDEDOR`), proyección administrativa `D9` (`ADMINISTRADOR`, `BC-07`), `RendirEfectivoRepartoService` (`D6-1`, exclusivo `ADMINISTRADOR`), controladores HTTP (`ale.http.writes.* = false`) y verificación completa `D7-A` servicio+SQL. | 1. Cierre verde de `R09.2`.<br>2. Decisiones `DEC-GRAN-D6` (granularidad + literal `COBRO.origen`), `D4`, `D9` (`BC-07`) y `D10`/`D56-09` ratificadas. | 1. Escenarios `D56-01..D56-16`, `T16`, `T18`, `T19`, `T22`, `IMP-T19`, `IMP-T20` verdes con rollback real.<br>2. Validación estricta `ADMIN = X` en `UC-14` (`403`), `VENDEDOR/REPARTIDOR = X` en `D6-1` (`403`), `REPARTIDOR = X` en `UC-29`.<br>3. Cese atómico `A_e + V_e^vig = V_e^0` en `T18` y concurrencia `T16`/`T22` sin `40P01`. |
| **`R09.4` — Integración transversal (`UC-06`, `UC-07` si aprobado, `C-08` en `UC-03`, decisión `DC05-04`)** | Implementar liberación de reservas/aplicaciones `IMP-01` en `UC-06` (`DC05-06`), incorporar o validar adicionales `UC-07` según estado final de `PR #47`/`#48` y `RT-UC07-04` (`DC-03`, `DC05-08`), habilitar `RD-F04-006` en `CargarPedidoService` (`C-08`) tras definir política de deuda provisional, y aplicar o documentar `DC05-04` (`EvaluadorCategoriaComercialInterno` vs `IMP-T15`). | 1. Cierre verde de `R09.3`.<br>2. Definiciones expresas `DC05-04`, `DC05-06`, `DC05-08`, estado `C-08` y (si aplica al primer corte) `CR-C`/`CR-F` + sandbox `C3-05/06/07`. | 1. Escenarios `IMP-T07`, `IMP-T13`, `IMP-T15`, `T12`, `T24` verdes.<br>2. Si `UC-07` integra este corte: `C3-05/06/07` superados en servicios reales y `RT-UC07-04` cerrada formalmente en gobierno.<br>3. Regresión `UC-03` (`CargarPedidoServiceIntegrationTest` + deuda provisional `409 CLIENTE_CON_DEUDA`). |
| **`R09.5` — Consolidación seguridad, matriz RBAC `67×8`, `BC-07`, `BC-06` HTTP E2E y preparación entrega** | Verificación integral de proyecciones `BC-07` (15+ filas incluyendo `D9`), matriz HTTP negativa/positiva por rol, guardas `PrecondicionesDespliegueTest` y `application-prod.yml`, actualización matriz `CU → servicio → DB → API/UI → test` y preparación de los **18 escenarios `BC-06`** (cuando sean ejecutables sobre el backend completo). | 1. Cierre verde de `R09.4`.<br>2. Aprobación documental `BC-07` (`D9`) y definición de plan de aceptación `BC-06`. | 1. CI `B1` (`mvn -B -ntp clean verify`) y cobertura JaCoCo completas sobre SHA candidato.<br>2. Reporte explícito de los 18 escenarios `BC-06` ejecutados realmente (sin anticipar resultado antes de correrlos).<br>3. **`Issue #28` sigue bajo gobierno del responsable:** ningún despliegue automático ni paso a `prod` sin levantar `Issue #28` por orden expresa. |

*(Nota de alcance: `D11` / `PE-3` / `ADR-B1-05` —inbox/cola offline— permanece expresamente fuera de `R09.1–R09.5` como subcorte posterior `PE-3`, conservando compatibilidad vía `idOperacionOrigen` UUID desde `R09.2`.)*

---

## 6. Actividad 5 — Protocolos de integración y concurrencia (`UC-05` ↔ `UC-03`, `UC-29`, `UC-43`, `UC-07`)

### 6.1. Registro actual de advisory locks en `src/main` (`4dbe3866`)

Para prevenir colisiones e inversiones ocultas, todo nuevo servicio debe respetar el catálogo existente observado en el código integrado:

| Namespace (`pg_advisory_xact_lock`) | Tipo | Titular en `src/main` | Alcance / observación |
|---|---|---|---|
| `(808, 0)` | `A2(int,int)` | `RegistrarPreparacionService`, `ConfigurarRecetaService`, `RegistrarIngresoStock*`, `RegistrarAjusteInventarioService` | Dominio Producción/Insumos |
| `9172001` y `(60505, 7)` | `A1` y `A2` | Servicios `acceso` y `AsignarRepartoService` (`UC-43`) | Gestión usuarios/roles/asignación |
| `(3003, 0)` | `A2(int,int)` | `CargarPedidoService` (`UC-03`) | Serialización sección `UC-03` |
| `2929001` | `A1(bigint)` | `CobroManualService` (`UC-29` alta y anulación) | Dominio `Finanzas` (`COBRO` / Cuenta Corriente) |
| `5715001` | `A1(bigint)` | `ConfigurarPoliticaCategoriaComercialService` (`UC-57`) | Políticas comerciales |
| `(1919, 1)` | `A2(int,int)` | `ActualizarPrecioVigenteService` (`UC-19`) | Migrado desde `1919001` en `INC-UC19` (PR `#86`) |
| `3202001` / `5051052` / `3030047` | `A1(bigint)` | `GestionClientesService`, `GestionInsumosService`, `GestionProductoService` | Maestros específicos |
| `A1(idInsumo)` / `A1(idCliente)` | `A1(bigint)` | `UC-28` (`idInsumo`), `UC-03` (`idCliente`), otros maestros (`idElemento`) | **Riesgo observado (`C3-01`):** `A1` de un ID crudo puede colisionar entre entidades distintas (falsa contención). **Nuevo código debe preferir `A2(constanteModulo, idEntidad)` si usa `pg_advisory_xact_lock`.** |
| `(4402, 2026)` *(sesión)* | `A2(int,int)` | `UnicaInstanciaPostgres` | Singleton del backend |

### 6.2. Separación estricta: **Evidencia demostrada** vs **Hipótesis / Diseño candidato**

| Clasificación | Escenario / Arista | Detalle técnico exacto y fuente verificada | Estado y obligación para `R09` |
|---|---|---|---|
| **DEMOSTRADO (`Nivel R`, PR `#88`)** | **`C3-01`** (`UC-03` ↔ `UC-28`) | Si `idCliente == idInsumo`, `UC-03` toma `A1(idCliente)` y `UC-28` espera en el mismo `A1(idInsumo)`. Produce contención falsa entre dominios distintos, **sin** generar `40P01` en ese par. | Evitar `A1(idEntidad)` sin prefijo en nuevos servicios `UC-05`/`IMP-01`/`UC-29`. |
| **DEMOSTRADO (`Nivel R`, PR `#88`)** | **`C3-02`** (`UC-03` ↔ `UC-29`) | Si `idCliente == 2929001`, `UC-29` (`A1(2929001)`) y `UC-03` (`A1(idCliente)`) compiten en el mismo lock advisory sin deadlock. | Señal adicional de separación estricta de namespaces `A2(ns, id)` a evaluar en refactor futuro. |
| **DEMOSTRADO (`INC-UC19`, PR `#86`)** | **`UC-03` ↔ `UC-19`** | Antes usaban `A1(idCliente)` vs `1919001` generando `40P01` cuando `idCliente = 1919001`. Resuelto separando `UC-19` a `A2(1919, 1)`. | Regresión permanente en `PrecioProductoUc03DeadlockIntegrationTest`. |
| **DEMOSTRADO (`Nivel R/P`, PR `#89`)** | **`C3-03a / C3-03b`** (`UC-43` ↔ `UC-05`) | `USUARIO FOR SHARE` es compatible; la arista incompatible real es **`PEDIDO U ↔ ASIGNACION_REPARTO U`**. Tomar `ASIGNACION_REPARTO U -> PEDIDO U` causa `40P01` real (`C3-03a`). Tomar **`PEDIDO U -> ASIGNACION_REPARTO U`** serializa limpiamente (`C3-03b`). | **Invariante obligatoria de `UC-05`:** siempre bloquear `PEDIDO FOR UPDATE` antes de `ASIGNACION_REPARTO FOR UPDATE` y revalidar estado `PENDIENTE`. |
| **DEMOSTRADO (`Nivel R/P`, PR `#89`)** | **`C3-08a / C3-08b`** (`UC-29` alta ↔ `UC-05`) | `UC-29` (`A1(2929001) -> CLIENTE U -> [PEDIDO S]`) frente a un `UC-05` que tome `PEDIDO U -> CLIENTE U` provoca `40P01` real (`C3-08a`). El orden **`A1(2929001) -> CLIENTE U -> PEDIDO U -> ASIGNACION_REPARTO U`** serializa `MOVIMIENTO_CUENTA(numeroSecuenciaCliente, saldoResultante)` sin `40P01` ni `23505` (`C3-08b`). | **Invariante obligatoria de `UC-05`:** adquirir `A1(2929001)` (o secuencia `C3` unificada aprobada en `R08.6`) y `CLIENTE FOR UPDATE` **antes** de `PEDIDO FOR UPDATE`. |
| **DEMOSTRADO (`Nivel R/P`, PR `#89`)** | **`C3-04a / C3-04b`** (`UC-19` ↔ `UC-07 Parte A`) | `ActualizarPrecioVigenteService` (`UC-19`) evalúa `clock_timestamp()` **antes** de esperar `A2(1919, 1)`. La captura de oferta `UC-07 Parte A` evita desfasajes capturando `clock_timestamp()` **después** de adquirir `PRODUCTO S -> PRECIO_PRODUCTO S`. | Regla de implementación para cualquier lectura de vigencia temporal bajo lock (`Parte A`). |
| **HIPÓTESIS / DISEÑO PENDIENTE (`R08.5-C` §10.4, `DC-10` / `DC05-10`)** | **Inversión `anularCobroManual` (`COBRO U -> CLIENTE U`) ↔ `T1` / `T2` / `IMP-01`** | `fn_mr3_anular_cobro_manual` toma `COBRO U` y luego `CLIENTE U`. Cualquier futura transacción `T1` (`UC-05`), `T2` (rendición `D6-1`) o liberación `IMP-01` que adquiera `CLIENTE U` primero y luego consulte/bloquee `COBRO S/U` (sin coincidir en un lock raíz único anterior) reproducirá un nuevo ciclo `40P01`. Además, bajo **`DC05-07`**, `anularCobroManual` debe serializarse frente a `UC-05` leyendo/validando `P_e` (`IMP-T09`, `IMP-T19`, `IMP-T20`, `T30`). | **`[PENDIENTE DE DECISIÓN DC-10 / DC05-10]`** Orden candidato a probar en `R08.6`: corregir `fn_mr3_anular_cobro_manual` para adquirir `CLIENTE U` antes de `COBRO U` (o fijar root advisory + orden único `A1(2929001) -> CLIENTE U -> PE-1/PE-2/IMP-01 -> COBRO U -> PEDIDO U -> ASIGNACION U -> PRODUCTO/EXCEDENTE`). **No afirmado como probado aún.** |
| **HIPÓTESIS / NIVEL D (`PR #89` §4)** | **`C3-05`, `C3-06`, `C3-07`** (`UC-28` ↔ `UC-07` ↔ `UC-41`) | Sin tablas/triggers finales de `CR-C (#47)` y `CR-F (#48)` ni `V4` autorizada, la no aparición de locks `FOR KEY SHARE` tardíos tras `OFERTA U` y los generadores de `EXCEDENTE` siguen en Nivel D. | Requerirá pruebas físicas `R08.6` / `R09.4` antes de cerrar **`RT-UC07-04`**. |
| **HIPÓTESIS / DISEÑO (`R09.2–R09.3`)** | **Concurrencia multi-entrega en rendición `D6-1` (`T2 ↔ T1`, `T2 ↔ T2`)** | Dos Administradores rindiendo la misma declaración (`D56-11`), o rendición concurrente con segunda interacción / `C3` (`T16`), sin bloquear por `idRecorrido` + orden ascendente determinista de `idPedidoEntrega` dentro de un único `CLIENTE U` o lote multi-cliente, podrían producir esperas cruzadas o doble aplicación (`A_e > V_e^0`). | **`[PENDIENTE DEC-GRAN-D6 + DC-10]`** Ordenar lotes por `(idCliente ASC, idPedidoEntrega ASC)` o aislar por entrega bajo lock determinista tras definir granularidad de `D6-1`. |

---

## 7. Actividades 4 y 7 — Matriz incremental de pruebas y plan CI (`PostgreSQL 17.11` / Testcontainers)

### 7.1. Infraestructura CI verificada (`.github/workflows/b1-build.yml` y `pom.xml`)

| Elemento | Configuración real verificada | Regla operativa obligatoria para `R09` |
|---|---|---|
| Runner y JDK | `ubuntu-latest`, Temurin Java **`21`**, Spring Boot **`4.1.2`** | Toda prueba debe compilar y correr bajo Java 21 con `mvn -B -ntp clean verify`. |
| PostgreSQL efímero | `org.testcontainers:testcontainers-postgresql:2.0.5`, imagen **`postgres:17.11-alpine`** | Ninguna prueba nueva puede usar base Compose local persistente (`dev/l05`) ni H2/mocks en reemplazo de SQL real; solo datos sintéticos y contenedor efímero por clase (patrón `CobroManualCommitRollbackIntegrationTest`). |
| Semántica transaccional real | Pruebas sin `@Transactional` en la clase usando `TransactionTemplate` para fixture y llamadas directas al servicio para confirmar o revertir al `COMMIT` | Indispensable para ejecutar triggers diferidos `DEFERRABLE INITIALLY DEFERRED` (`ct_mr3_cobro_anulado`, `ct_mr3_caja_anulacion`, `ct_mr3_origen_anulacion`) y verificar rollback íntegro ante excepción tardía o `CHECK` fallido (`D56-16`). |
| Concurrencia determinista | `Executors`, `CountDownLatch`, `pg_locks`, `pg_stat_activity`, `pg_blocking_pids`, condición explícita sobre `wait_event_type = 'Lock'` / clasificación `SQLSTATE 40P01` vs `23505` | Prohibido usar `Thread.sleep(...)` para ordenar intercalados. Todo test `C3` debe observar el lock real en PostgreSQL antes de soltar barreras (patrón PR `#88` y `#89`). |
| Reportes y artefactos | JaCoCo **`0.8.15`** (`report_coverage.py`), `target/surefire-reports/`, `scripts/acceptance/package_bundle.sh` | En entorno Windows local sin daemon Docker, la ejecución de integración se delega al CI de GitHub Actions y **nunca** se reporta como ejecutada localmente (`NO_EJECUTADO` si no corrió). |

### 7.2. Matriz de pruebas: **Ejecutables hoy (`V1–V3`)** vs **Dependientes de `V4` y Puerta 4**

#### Grupo A — Pruebas de regresión y preparación **ejecutables sobre `V1–V3`**

| Categoría / Suite | Archivo o alcance real | Qué verifica en `V1–V3` | Estado actual |
|---|---|---|---|
| **A.1 — Regresión `UC-29` existente** | `finanzas/CobroManualServiceIntegrationTest.java` (`bc0601–bc0606`, `uc15Mora*`) + `CobroManualCommitRollbackIntegrationTest.java` + `CobroYCategoriaConciliacionIntegrationTest.java` | Autorización `REPARTIDOR` denegado (`bc0601`), alta `VENDEDOR` atómica (`bc0602`), idempotencia `uq_cobro_0` y conflicto (`bc0603`), `VENDEDOR` no anula (`bc0604`), contraasiento `ADMIN` (`bc0605`), rechazo `ONLINE` (`bc0606`), rollback real al `COMMIT`, FIFO `UC-15`. | **Activa y verde en `main` (`4dbe3866`) vía CI.** |
| **A.2 — Regresión `UC-03`/`UC-28`/`UC-43`/`UC-19`/`B2`/`Web`** | `CargarPedidoServiceIntegrationTest`, `AsignarRepartoServiceIntegrationTest`, `RegistrarPreparacionServiceIntegrationTest`, `PrecioProductoUc03DeadlockIntegrationTest`, `PrecondicionesDespliegueTest`, `ModuleSkeletonTest`, `B2SchemaMigrationTest`, `TrazabilidadPartidasMigrationTest`, `*HttpExternoTest` | Integridad de 7 módulos, migraciones `V1–V3`, filtro `cantidadOriginal > 0` (`I3-02`), fail-closed de flags `ale.http.writes.*` en `prod`, deadlock resuelto `(1919, 1)`. | **Activa y verde en `main` (`4dbe3866`) vía CI.** |
| **A.3 — Lab `C3` existente (`Nivel R` + `Nivel R/P`)** | PR `#88` (`C3A3ServiciosRealesIntegrationTest`: `C3-01`, `C3-02`) y PR `#89` (`C303*`, `C304*`, `C308*`) | Comportamiento real de locks `UC-03`, `UC-28`, `UC-29`, `UC-43`, `UC-19` frente a prototipos `UC-05` / `UC-07 Parte A` sobre tablas `V1`. | **Activa en PRs `#88` y `#89` (no mergeadas).** |
| **A.4 — Pruebas nuevas preparables en `V1–V3` sin DDL `F04` *(tras autorización expresa de corrección o inicio `R09.1`)*** | Ej.: pruebas negativas de reintento `anularCobroManual` (`C-04`) demostrando el fallo actual si se valida baseline, o pruebas del fix `C-04` / orden `CLIENTE U -> COBRO U` si su tabla/clave no depende de `PE-1/PE-2`. | Aísla la remediación de `C-04` y la simetría `UC-29` alta ↔ anulación sin mezclar tablas `F04`. | **`[PENDIENTE DE AUTORIZACIÓN DEC-UC29-ANUL / R09.1]`.** *(No crear en `src/test/**` en `R09-PRE`.)* |

#### Grupo B — Matriz de pruebas **bloqueadas hasta `R08.6` (`V4`) e implementación `R09.1–R09.5`**

| Incremento | IDs / Fuentes normativas (`R08.4` / `§17.8` / `R08.3` / `G0`) | Cobertura mínima obligatoria tras habilitar `V4` | Reglas de validación exigidas |
|---|---|---|---|
| **`R09.1`** | `B2SchemaMigrationTest` ampliada + `IMP-T01..T14`, `IMP-T16`, `C-04`, `DC-10` | Migración limpia `V1->V2->V3->V4`; conservación por Cobro (`inv. 1`) y obligación (`inv. 2`); no doble destino (`inv. 3`); `COBRO.idPedido` ignorado sin imputación (`IMP-T02`); reserva no es `P_e` (`IMP-T01`); anulación `F01` antes/concurrente (`IMP-T08`, `IMP-T09`); concurrencia dos pagos (`IMP-T10`); idempotencia y conflicto (`IMP-T11`, `IMP-T12`, `C-04`). | Cero filas `PAGO`/`MOVIMIENTO_CAJA` duplicadas (`IMP-T14`); triggers diferidos y rollback real con `TransactionTemplate`. |
| **`R09.2`** | `T01–T15`, `T17`, `T20–T21`, `T23–T30`, `IMP-T17`, `IMP-T18`, `C3-03`/`C3-08` reales (`Nivel R` puro) | `C_e` parcial vs completo (`T01`, `T05`, `T13`, `T25`); `P_e` deducido y `P_e = C_e` (`T02`, `T03`, `T04`, `T29`); aislamiento `D5-B` entre entregas y deudas viejas (`T07`, `T08`, `T20`); exceso `D_e > E_e` (`T06`, `D2=B`, `Q_e^decl`); borde `C_e = 0` (`T21`) y `D_e = 0` (`T26`); offline no confirmado (`T14`); concurrencia `T15`, `T30`, `C3-03`, `C3-08`; replay y conflicto (`T17`, `T27`, `T28`, `IMP-T18`); HTTP negativo `REPARTIDOR` no asignado / `VENDEDOR` / `ADMIN` (`403`) + `repartidorAutenticado` + `JsonMapper` estricto. | `0 <= V_e^0 <= D_e`; `C_e` coincide al centavo con `CARGO_POR_VENTA` único; sin filas en `COBRO` ni `MOVIMIENTO_CAJA`; `PrecondicionesDespliegueTest` incluye nueva flag `ale.http.writes.*`. |
| **`R09.3`** | `D56-01..D56-16`, `T16`, `T18`, `T19`, `T22`, `IMP-T19`, `IMP-T20`, `RBAC`/`BC-07` (`D9`) | `UC-14` solo `VENDEDOR` (`ADMIN=403`, `REPARTIDOR=403`), filtros de fecha sin mutar `saldoConfirmado`/`pagosEnTransitoValidados`/`saldoExigibleProvisional`; proyección `D9` solo `ADMIN`; rendición exacta y parcial (`T18`, `D56-06/07`); faltante `Q_e^falt` (`T19`, `D56-08/15`); recibido `> V_e^vig` (`D56-09`); `D6-1` permisos negativos (`D56-13/14`); concurrencia `T16` (`UC-29` vs `UC-05`), `D56-11` (dos Admin), `T22` (anulación concurrente); **`D7-A`** bloquea cobro de rendición vía Java **y** vía llamada SQL directa (`IMP-T19`, `D56-12`) sin bloquear cobro `'ADMINISTRACION'` (`IMP-T20`, `bc0604–0606`); rollback `D56-16`. | `A_e(t) + V_e^vig(t) = V_e^0` en modelo inicial; cero doble descuento en `UC-14`; aislamiento total de `COBRO` `'ADMINISTRACION'`. |
| **`R09.4`** | `IMP-T07`, `IMP-T13`, `IMP-T15`, `T12`, `T24`, `C-08`, `C3-05/06/07` *(si `UC-07` entra)* | Cancelación `UC-06` con liberación `IMP-01` (`IMP-T07`) + preservación `EXCEDENTE('CANCELACION')`; parcial `< reserva` (`IMP-T13`); prueba explícita `IMP-T15` verificando comportamiento elegido bajo `DC05-04`; adicionales `UC-07` válidos/inválidos (`T12`, `T24`) y concurrencia `C3-05/06/07`; admisión `UC-03` contra `saldoExigibleProvisional` (`C-08`, `409 CLIENTE_CON_DEUDA`). | Cero regresión en `UC-28` (`RegistrarPreparacionServiceIntegrationTest`) y `UC-03` (`CargarPedidoServiceIntegrationTest`). |
| **`R09.5`** | **`BC-06` (18 escenarios en `source-lock.md`)** + verificación completa `RBAC 67×8` + `BC-07` + `PrecondicionesDespliegueTest` | Matriz HTTP E2E de permisos (cero celdas `P` implícitas, `403` en todo rol no autorizado), `Cache-Control: no-store`, exposición de `BC-07` por proyección y los 18 escenarios `BC-06` sobre entorno HTTP real integrado. | **Prohibido marcar `BC-06` como ejecutado o aprobado antes de completar realmente sus 18 escenarios en `R09.5`.** |

---

## 8. Actividad 9 — Ruta crítica al `12/12/2026`, dependencias y riesgos

**Premisa de alcance no negociable:** La fecha objetivo académica/operativa del sistema completo en Roadmap (`PR #49`) es el **`12/12/2026`** (~27 días calendario desde `15/11/2026`). En cumplimiento de `source-lock.md` y de la consigna de `R09-PRE`, **NO se reduce ni recorta unilateralmente el alcance ratificado de 64 casos de uso / 67 operaciones / 536 celdas RBAC / 15+ proyecciones `BC-07` / 18 escenarios `BC-06`**.

### 8.1. Ruta crítica (camino bloqueante de menor holgura)

1. **Eslabón 1 (BLOQUEO INMEDIATO — holgura nula): Paquete decisorio P0–P2 del responsable (`leandrito369`):**
   - Resolver `DC05-01..09/11` (`IMP-01`), `DC-01..08`, `D2=B` formal, `D3`, `D4`, `D9` (`BC-07`), `D10`, `D12` (`CR-B0-F04-001`), `DEC-GRAN-D6`, `DEC-UC29-ANUL` (`C-04`), más definir si adicionales `UC-07` integran el primer corte `UC-05` (`DC-03` + cierre `PR #47/#48`).
2. **Eslabón 2: Tanda 2 documental en `ale-milanesas`:**
   - Aplicar `E-01..E-07` conciliados en `REV04B`, `REV05` y `RD-I302-001`, asentar `BC-07` (`D9`), formalizar excepción/CR online articulada (`D12`: `PE-1 + PE-2 + IMP-01`) y cerrar convergencia documental `UC-07` (`CR-C`/`CR-F`).
3. **Eslabón 3: `R08.6` físico / laboratorio DDL (`Puerta 2` → `Puerta 4`):**
   - Validar migración `V4` candidata y campaña `C3` ampliada (`DC-10`/`DC05-10` + corrección `COBRO U -> CLIENTE U` + `C3-05/06/07` para `RT-UC07-04`) sobre PostgreSQL `17.11` efímero en CI.
4. **Eslabón 4: Ejecución secuencial `R09.1 → R09.2 → R09.3 → R09.4 → R09.5` y convergencia del resto del backlog `I3 → I4–I7`:**
   - Una vez superado el Eslabón 3, dentro de `R09` es factible paralelizar trabajo modular sin romper integridad (p. ej. preparar controladores HTTP y proyección `UC-14`/`D9` en paralelo con pruebas `UC-06`/`C-08`, manteniendo la dependencia de servicio `R09.1 → R09.2 → R09.3`), en paralelo con otros bloques del Roadmap (`UC-22`, `UC-23`, `UC-26`/`49`, compras `I4–I7`, web Thymeleaf, Android Room/online).

### 8.2. Matriz de riesgos principales y mitigaciones

| ID | Riesgo | Impacto en cálculo, seguridad o plazo `12/12/2026` | Mitigación y dueño |
|---|---|---|---|
| **`R-01`** | Demora en decisiones **P0–P2** (`DC05-*`, `DC-01..08`, `D9`, `DEC-GRAN-D6`, `D12`) o en cierre `CR-C`/`CR-F` | Paraliza Puerta 2 (`R08.6`) y consume días de la ventana hacia `12/12/2026`. | Priorizar Tanda 2 en bloques P0 → P1 → P2 (`R08.5-C` §10.5); mantener `D11`/`PE-3`, `D7-B` y `C-08` post-`F04` diferidos limpiamente sin reabrir `D5-B`/`ANT-01`/`D6-1`/`D7-A`. |
| **`R-02`** | Divergencia no resuelta `DC05-04` (`P_e` explícito vs FIFO global `EvaluadorCategoriaComercialInterno`) | Dos módulos computan distinto qué obligación está paga (`IMP-T15`), o se altera regresivamente `UC-15` sin autorización. | Prohibido tocar `EvaluadorCategoriaComercialInterno` hasta que el responsable decida `DC05-04-A` (coexistencia declarada) o `DC05-04-B` (adaptación con nueva batería `UC-15`). |
| **`R-03`** | Deadlock `40P01` por inversión `COBRO U -> CLIENTE U` (`fn_mr3_anular_cobro_manual`) o colisión `A1(idCliente == idInsumo)` (`C3-01`) | Bloqueos intermitentes bajo concurrencia real `UC-05 ↔ UC-29 ↔ UC-43 ↔ UC-03 ↔ UC-28`. | Incluir corrección de orden en `fn_mr3_anular_cobro_manual`, fijar secuencia unificada `DC-10`/`DC05-10`, migrar nuevos locks a `A2(ns, id)` y demostrar en `R08.6` con `pg_locks`. |
| **`R-04`** | Reintentos fallidos en `anularCobroManual` (`C-04`) o consumo indebido de UUID en rechazos (`D3`) | Operación manual falla ante pérdida de respuesta HTTP post-commit, o usuarios quedan bloqueados por reintentar tras error de negocio. | Incluir remediación `C-04` en `R09.1` y fijar regla `D3` (no consumir clave ante rollback previo). |
| **`R-05`** | Exposición accidental de `UC-14` / costos / datos a `ADMIN` o `REPARTIDOR`, o activación prematura de endpoints de escritura | Violación directa de `RBAC 67×8` (`ADMIN = X` en `UC-14`, `REPARTIDOR = X` en `UC-29`) o `BC-07`. | Proyección separada `D9`, `repartidorAutenticado`, flags `ale.http.writes.* = false`, `@Profile("!prod")` y verificación en `PrecondicionesDespliegueTest`. |
| **`R-06`** | Compresión extrema del calendario hacia el `12/12/2026` frente a 64 CU / 67 ops + `BC-06` (18 escenarios) | Riesgo de acumulación en `R09.5` o de marcar controles como aprobados sin corrida real. | Mantener alcance íntegro `64 CU / 67 ops`, automatizar pruebas incrementalmente por cada subcorte en CI (`ubuntu-latest` + `postgres:17.11-alpine`) y nunca marcar `BC-06` o `RT-UC07-04` sin corrida real de CI. |
| **`R-07`** | Despliegue no autorizado antes de completar pruebas ni cerrar controles | Exposición de backend incompleto con migración o reglas pendientes (`Issue #28` abierto). | Mantener `Issue #28` abierta y despliegue pausado hasta cierre explícito de `R09.5`, `BC-06` y autorización final del responsable. |

---

## 9. Dictamen final de cierre (`R09-PRE`)

> **VEREDICTO FINAL OBLIGATORIO:** **`PREPARADO DOCUMENTALMENTE / BLOQUEADO PARA IMPLEMENTACIÓN`**

1. **Habilitado en este corte (`Puerta 3` de `R08.5-C`):** queda trazado el inventario real sobre `main` (`4dbe3866`), las brechas abiertas (`C-03`, `C-04`, `C-08`, inversión `COBRO U -> CLIENTE U`, ausencia de `REPARTIDOR` en `SeguridadHttpInterna` y ausencia de controlador `UC-29`/`UC-14`), los cinco puertos conceptuales desacoplados, la guarda `D7-A` en Java + SQL sin dañar Cobros `'ADMINISTRACION'`, la matriz de concurrencia verificada vs hipotética, el plan incremental `R09.1–R09.5`, la estrategia `PostgreSQL 17.11` / Testcontainers y la ruta crítica al `12/12/2026`.
2. **Estrictamente bloqueado (`Puerta 2` y `Puerta 4`):** este documento **NO autoriza** escribir interfaces o servicios en `src/main/**`, agregar o modificar tests de DDL futuro en `src/test/**`, redactar o ejecutar migración Flyway `V4`, cerrar `RT-UC07-04`, fusionar ramas ni desplegar (`Issue #28` permanece `OPEN`). Toda implementación productiva permanece condicionada al cierre secuencial de las decisiones **P0–P2**, Tanda 2 contractual (`E-01..E-07` + `D12`) y autorización expresa de **`R08.6` físico**.
