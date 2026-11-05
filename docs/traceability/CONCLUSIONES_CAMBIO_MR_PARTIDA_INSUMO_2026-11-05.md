# Conclusiones — Cambio al Modelo Relacional (PARTIDA_INSUMO)

**Fecha:** 2026-11-05  
**Autor:** Victor Leandro Machado (`leandrito369`)  
**Estado:** Propuesta para RP en `leandrito369/ale-milanesas` — **no aprobada ni implementada**  
**Iteración:** I1/B4 (en curso)  
**Destino:** Documento de trabajo para revisión; no autoriza modificar `MR-5`, migraciones ni desplegar.

## 1. Resumen ejecutivo

- La 4.ª devolución del profesor identifica una limitación efectiva: `INSUMO.stockActual` y `MOVIMIENTO_INSUMO` controlan cantidades agregadas, pero no identifican envases, lotes, vencimientos, apertura y procedencia física de cada consumo. **Corresponde ampliar el MR**.
- La solución propuesta incorpora `PARTIDA_INSUMO` y `DETALLE_MOVIMIENTO_PARTIDA` con las reglas D1–D4. Preserva recetas versionadas, movimientos y el costeo histórico vigente; pasa de 60 a **62 tablas propuestas**, sujeto a homologación.
- **No implementar ahora.** Primero cerrar I1/B4: corregir y fusionar PR #50, implementar UC-48 (`B4-03-07`) y consolidar I1. No abrir frentes B3 antes del cierre B4.
- La fuente G0 y `MR-5/F01` están congelados. El cambio exige **RP/CR en el repositorio de gobierno → aprobación del responsable → MR-6 → nueva migración Flyway y pruebas**; nunca editar la migración de línea base.
- `RD-MR-PARTIDAS-001` permanece en estado **PROPUESTA (lista para homologación)**. La respuesta al profesor puede enviarse en paralelo, como propuesta documentada y no como funcionalidad terminada.

## 2. Cierre de I1/B4: independiente del cambio al MR

El **PR #50 (UC-47)** está abierto y es fusionable; en el corte validado tiene **10/10 controles de CI verdes** y vincula la **Issue #49**. Se detectó una corrección documental puntual: el `governance_commit` del documento UC-47 indica `62c11a7` (commit de implementación), pero debe identificar el acta G0 mediante `27738b089afdd2698cdae915f75c0e8089a6248a`.

**Secuencia acordada:** corregir esa referencia en la misma rama de PR #50 con commit nuevo, verificar CI, someter el PR a revisión y fusionarlo; continuar con **UC-48 (`B4-03-07`)** y emitir el acta de consolidación I1. El cambio de partidas no interviene en ese cierre.

Las observaciones de **SonarCloud (cobertura de código nuevo 0,0 %)** siguen en **Issue #35**, sin bloquear esta corrección. La revisión independiente no debe presentarse como efectuada por otra persona: quedó documentada como **revisión propia**, ya que `main` carece de protección de rama. No se atribuye a este documento ninguna corrección, aprobación o fusión ya ejecutada sobre PR #50.

La Fase 0 fallida de Antigravity (`git checkout`: *is not a branch*) no modificó el estado remoto; el árbol local había quedado con archivos preparados. El procedimiento de recuperación acordado fue limpiar ese árbol local y repetir la obtención explícita de la referencia antes del cambio de rama. **No forma parte de la intervención documental actual.**

## 3. Línea base, alcance y gobernanza

`docs/traceability/source-lock.md` fija estos identificadores, que **no se reemplazan** por la punta móvil de `main`:

| Campo | Valor canónico y función |
|---|---|
| `source_commit (G0)` | `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` — fuente canónica de requisitos y diseño. |
| `governance_commit` | `27738b089afdd2698cdae915f75c0e8089a6248a` — acta RD-B0-G0-001. |
| `RD-UC30-001` | `4971625a215d94a8b9f6439d9b0836e0925ea1ad` — decisión específica posterior a G0. |

La regla de precedencia es expresa: **«En discrepancia, no editar silenciosamente la fuente ni inventar una decisión: abrir RP/CR en `leandrito369/ale-milanesas` y restringir la operación afectada»**. La adenda de RD-UC30-001 ratifica: **«G0 y MR5/F01 permanecen inmutables»**.

Por eso, este ajuste debe nacer en el **repositorio documental** `leandrito369/ale-milanesas`, mediante RP con D1–D4, DDL propuesto, las cinco resoluciones y el acta `RD-MR-PARTIDAS-001`. Solo tras su aprobación se establece **MR-6**, se registra el nuevo commit de decisión como referencia posterior y se actualiza `source-lock.md` mediante un PR independiente, **sin sustituir** los tres valores canónicos anteriores.

En `leandrito369/ale-milanesas-app` se implementará después mediante una **nueva migración Flyway**, nunca modificando `V1__baseline_mr5.sql`. El plan recibido la denominaba `V2__trazabilidad_partidas_insumo.sql`, pero **`V2__f01_cobro_estados.sql` ya ocupa V2 en el repositorio**. Por lo tanto, el siguiente nombre técnicamente disponible sería **`V3__trazabilidad_partidas_insumo.sql`**, sujeto a reconfirmar la secuencia al abrir Fase C. Esta corrección de versión **no adelanta la implementación ni cambia el diseño aprobado de F01**.

## 4. Diseño relacional propuesto: D1–D4

| Código | Decisión consolidada |
|---|---|
| **D1 — Partida física** | `PARTIDA_INSUMO` identifica cada envase o existencia controlable individualmente, aunque comparta `codigoLote` con otro. `idRecepcionCompra` es opcional: ingreso por compra (`UC-08`) o saldo inicial/ajuste positivo (`UC-42`). `fechaVencimiento` admite `NULL` para insumos sin caducidad. |
| **D2 — Desglose y apertura** | **Todo** `MOVIMIENTO_INSUMO` se desglosa en uno o más `DETALLE_MOVIMIENTO_PARTIDA`. Dos FK compuestas con `idInsumo` impiden imputar una partida de otro insumo. Se exige `SUM(cantidadAplicada)=MOVIMIENTO_INSUMO.cantidad` y `SUM(PARTIDA_INSUMO.cantidadDisponible)=INSUMO.stockActual`. `cantidadDisponible` queda materializada y conciliada. La primera salida de un envase cerrado registra apertura automáticamente; también se admite apertura explícita en `UC-28`/`UC-42`. |
| **D3 — Utilizable ≠ físico** | Partida utilizable si `cantidadDisponible > 0` y (`fechaVencimiento IS NULL` o `fechaVencimiento >= fechaActual`). Las vencidas no pueden consumirse en `UC-28`; permanecen como stock físico hasta su descarte por `UC-42`. **`EPISODIO_FALTANTE` y PRA-01 siguen evaluando `INSUMO.stockActual` físico**, sin cambiar umbrales. |
| **D4 — Orden sugerido** | En `UC-28`, filtrar primero partidas utilizables; proponer (1) envases abiertos por `fechaHoraApertura ASC`, (2) FEFO por `fechaVencimiento ASC NULLS LAST`, (3) antigüedad por `fechaHoraRegistro ASC`. Se permite selección manual de otra partida utilizable, sin sobreconsumo ni uso de vencidas. |

**DDL propuesto para homologar; no ejecutado ni incorporado a Flyway:**

```sql
CREATE TABLE "PARTIDA_INSUMO" (
    "idPartidaInsumo" BIGINT NOT NULL,
    "idInsumo" BIGINT NOT NULL,
    "idRecepcionCompra" BIGINT,
    "codigoLote" VARCHAR(80),
    "fechaFabricacion" DATE,
    "fechaVencimiento" DATE,
    "fechaHoraRegistro" TIMESTAMPTZ NOT NULL,
    "fechaHoraApertura" TIMESTAMPTZ,
    "cantidadInicial" NUMERIC(18,3) NOT NULL,
    "cantidadDisponible" NUMERIC(18,3) NOT NULL,
    CONSTRAINT "pk_partida_insumo" PRIMARY KEY ("idPartidaInsumo"),
    CONSTRAINT "uq_partida_insumo_insumo" UNIQUE ("idPartidaInsumo", "idInsumo"),
    CONSTRAINT "ck_partida_insumo_cantidades" CHECK (
        "cantidadInicial" > 0 AND "cantidadDisponible" >= 0
        AND "cantidadDisponible" <= "cantidadInicial"
    ),
    CONSTRAINT "ck_partida_insumo_fechas" CHECK (
        "fechaFabricacion" IS NULL OR "fechaVencimiento" IS NULL
        OR "fechaVencimiento" >= "fechaFabricacion"
    ),
    CONSTRAINT "ck_partida_insumo_apertura" CHECK (
        "fechaHoraApertura" IS NULL
        OR "fechaHoraApertura" >= "fechaHoraRegistro"
    ),
    CONSTRAINT "fk_partida_insumo_insumo" FOREIGN KEY ("idInsumo")
        REFERENCES "INSUMO" ("idInsumo") ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_partida_insumo_recepcion" FOREIGN KEY ("idRecepcionCompra")
        REFERENCES "RECEPCION_COMPRA" ("idRecepcionCompra")
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

ALTER TABLE "MOVIMIENTO_INSUMO"
    ADD CONSTRAINT "uq_movimiento_insumo_insumo"
    UNIQUE ("idMovimientoInsumo", "idInsumo");

CREATE TABLE "DETALLE_MOVIMIENTO_PARTIDA" (
    "idMovimientoInsumo" BIGINT NOT NULL,
    "idPartidaInsumo" BIGINT NOT NULL,
    "idInsumo" BIGINT NOT NULL,
    "cantidadAplicada" NUMERIC(18,3) NOT NULL,
    CONSTRAINT "pk_detalle_movimiento_partida"
        PRIMARY KEY ("idMovimientoInsumo", "idPartidaInsumo"),
    CONSTRAINT "ck_detalle_movimiento_partida_cantidad"
        CHECK ("cantidadAplicada" > 0),
    CONSTRAINT "fk_detalle_mov_partida_movimiento"
        FOREIGN KEY ("idMovimientoInsumo", "idInsumo")
        REFERENCES "MOVIMIENTO_INSUMO" ("idMovimientoInsumo", "idInsumo")
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT "fk_detalle_mov_partida_partida"
        FOREIGN KEY ("idPartidaInsumo", "idInsumo")
        REFERENCES "PARTIDA_INSUMO" ("idPartidaInsumo", "idInsumo")
        ON UPDATE RESTRICT ON DELETE RESTRICT
);
```

El DDL expresa **estructura y restricciones de fila/FK**. Las igualdades entre sumas, apertura automática, idempotencia y conservación de saldos se resolverán mediante operaciones atómicas y verificaciones del RP; no quedan garantizadas solo por estos `CHECK`. No altera `COSTO_INSUMO_HISTORICO` ni las 60 tablas existentes.

**`PREPARACION.idReceta`: se conserva.** `VERSION_RECETA` tiene PK `(idReceta, numeroVersion)`; `numeroVersion` es correlativo **dentro de cada receta**, no un identificador global. `PREPARACION` requiere ambos atributos para su **única FK compuesta**. En Draw.io debe dibujarse **un solo conector** `(idReceta, numeroVersion) → VERSION_RECETA`, no dos relaciones independientes.

## 5. Resolución de las cinco preguntas abiertas

| Pregunta | Resolución y justificación breve |
|---|---|
| **1. Datos históricos** | **Migración histórica integral obligatoria:** partida de origen `BACKFILL_V2` por insumo con `stockActual > 0` y vinculación de **todos** los `MOVIMIENTO_INSUMO` anteriores. No admitir trazabilidad histórica parcial; validar casos límite en el RP. |
| **2. Concurrencia** | Para el primer alcance usar `pg_advisory_xact_lock(idInsumo)` en cada transacción de stock. Serializa por insumo y es consistente con UC-47/48; `FOR UPDATE` en orden D4 queda como evolución. |
| **3. Reposición** | `EPISODIO_FALTANTE` mantiene su disparador sobre `stockActual` físico. `stockUtilizable` se consulta en `UC-09` y `UC-31`; cambiar el umbral a utilizable requiere **`RD-MR-UMBRAL-UTILIZABLE-001`**. |
| **4. Apertura sin consumo** | Permitida de forma explícita en `UC-28` y `UC-42` mediante opción de apertura; el primer consumo de una partida cerrada sigue fijando `fechaHoraApertura` automáticamente. |
| **5. Costeo por partida** | Mantener `MOVIMIENTO_INSUMO → COSTO_INSUMO_HISTORICO`. El costo de recepción se consulta por unión con `RECEPCION_COMPRA`; materializar costo en el detalle requiere **`RD-MR-COSTEO-POR-PARTIDA-001`**. |

## 6. Ocho casos de uso impactados — numeración canónica

Se conservan **64 CU**. No se renumera el catálogo ni se altera el diagrama de CU congelado; se modificarán las fichas y contratos pertinentes **después de aprobar el RP**.

| CU | Secuencia normal / salida | Postcondición y excepción relevante |
|---|---|---|
| **UC-08 — Registrar Ingreso de Stock por Compra** | Captura envases/partidas por recepción, cantidad, lote y fechas aplicables. | Crea partidas y sus detalles `INGRESO_COMPRA`; suma de detalles igual al ingreso. |
| **UC-09 — Consultar Stock** | Presenta físico, utilizable y detalle de partidas, apertura y vencimiento. | Consulta sin movimientos; distingue cantidades vencidas/no utilizables. |
| **UC-28 — Registrar Preparación de Pedido** | Verifica utilizables y propone abiertas → FEFO → antigüedad; permite selección manual válida. | Descuenta por partida, registra desglose y apertura; rechaza vencidas o cantidad insuficiente. |
| **UC-31 — Configurar Parámetros de Reposición de Insumo** | Informa diferencia entre físico y utilizable en pantalla. | Mantiene umbrales y `EPISODIO_FALTANTE` sobre stock físico; sin automatización nueva. |
| **UC-36 — Buscar Insumo** | Incorpora resumen de partidas abiertas, disponibles y próximas a vencer. | Mantiene búsqueda y selección de insumo; sin movimientos. |
| **UC-42 — Registrar Ajuste de Inventario** | Identifica partidas ajustadas; admite origen sin compra y apertura explícita. | Desglosa aumentos/descuentos y descartes; prohíbe saldo negativo. |
| **UC-51 — Modificar Insumo** | Considera partidas entre sus referencias históricas. | Bloquea modificación incompatible de `codigoUnidadMedida` cuando existen dependencias. |
| **UC-64 — Consultar Movimientos de Inventario** | Permite expandir movimiento a sus partidas, lote, vencimiento y cantidades. | Exhibe trazabilidad física sin cambiar la historia del movimiento. |

## 7. Plan de ejecución y fechas tentativas

| Fase | Ventana tentativa | Acciones y entregables | Puerta de salida |
|---|---|---|---|
| **A — Cerrar I1/B4** | **05–07/11/2026** | Corregir `governance_commit` en PR #50; CI y revisión; fusión y cierre de Issue #49; implementar UC-48; acta de consolidación I1. **Sin cambios al MR.** | I1/B4 consolidado. No abrir B3 antes. |
| **B — Gobierno y MR-6** | **09–11/11/2026** | Crear RP/CR en `leandrito369/ale-milanesas`; adjuntar D1–D4, DDL, cinco resoluciones, `RD-MR-PARTIDAS-001`; obtener aprobación; versionar MR-6 y actualizar precedencia de `source-lock.md` mediante PR independiente. | RP y RD aprobados, nueva referencia documental registrada. |
| **C — Implementación y prueba** | **Desde 12/11/2026; 1–2 iteraciones** | Nueva migración Flyway posterior a V2, contratos, fichas CU, Draw.io, diccionario, pruebas de integridad, concurrencia, vencimientos y caso de cinco bolsas. | Evidencia de pruebas reales y revisión del PR de implementación. |
| **D — Devolución al profesor** | **05–12/11/2026, paralela a A/B** | Comunicar observación comprendida, diseño y plan sujeto a RP; responder sobre FK de `PREPARACION` y representación en Draw.io. | Respuesta documentada sin afirmar implementación finalizada. |

Estas fechas son **propuestas de planificación**, no compromisos ni hitos ya ejecutados. El entorno de despliegue continúa pausado; este documento no habilita despliegues.

## 8. Registro formal — `RD-MR-PARTIDAS-001` (§10.4)

| Campo | Contenido para homologación |
|---|---|
| `decision_id` | `RD-MR-PARTIDAS-001` |
| `fecha` | `2026-11-05` |
| `estado` | **PROPUESTA (lista para homologación)** |
| `autoridad` | Victor Leandro Machado, responsable del proyecto, con aprobación formal pendiente en RP. |
| `regla_aprobada` | **Texto propuesto, todavía no aprobado:** ampliar 60 → 62 tablas mediante `PARTIDA_INSUMO` y `DETALLE_MOVIMIENTO_PARTIDA`, aplicando D1–D4 y preservando `MR-5/F01` y su costeo histórico. |
| `alternativas_descartadas` | Agregar lote/fechas a `INSUMO`; crear solo `PARTIDA_INSUMO` sin desglose; separar tres entidades lote/envase/movimiento por complejidad no exigida. |
| `justificacion` | Satisfacer trazabilidad física solicitada por cátedra sin invalidar las relaciones agregadas existentes. |
| `artefactos_afectados` | RP, MR-6, nueva migración posterior a V2, Draw.io y diccionario, `source-lock.md` (adenda), fichas/contratos de las ocho UC de §6 y pruebas. |
| `fuente_chat` | Análisis y decisiones consolidadas ante la 4.ª devolución del profesor, 05/11/2026. |
| `fecha_propagacion` | **No propagada** mientras continúe en PROPUESTA; se registrará al homologar. |
| `pruebas` | Cinco bolsas de pan rallado, dos partidas para 1.300 g, bloqueo de vencidos, apertura y conciliación de sumas con `stockActual`; casos concurrentes e históricos. |

El campo `regla_aprobada` conserva el nombre exigido por §10.4, pero **no convierte una propuesta en decisión aprobada**. Solo el RP y el acta ratificada podrán hacerlo.

## 9. Anexo — Respuesta al profesor (texto para copiar)

> Profesor: revisamos su observación y confirmamos que nuestro modelo actual registra correctamente el stock agregado de cada insumo y sus movimientos, pero no identifica las existencias físicas individuales ni permite determinar de qué envase o lote proviene cada consumo. Por eso proponemos incorporar `PARTIDA_INSUMO`, para registrar origen, lote, vencimiento, apertura y saldo de cada envase, y `DETALLE_MOVIMIENTO_PARTIDA`, para asociar cada movimiento a las partidas realmente utilizadas. La consulta mostrará existencias utilizables y la preparación propondrá primero envases abiertos y luego las fechas de vencimiento más próximas, con control de cantidades.
>
> Esta mejora requiere modificar formalmente el Modelo Relacional: la registraremos mediante un RP para aprobación, generaremos una versión MR-6 y recién después una migración y pruebas, sin alterar la línea base MR-5 que sustenta los avances actuales. Entretanto completaremos la iteración I1/B4 ya iniciada.
>
> Respecto de `PREPARACION.idReceta`, aclaramos que `VERSION_RECETA` tiene clave primaria compuesta `(idReceta, numeroVersion)`. Como el número de versión se reinicia para cada receta, `numeroVersion` solo no identifica una fila. En `PREPARACION`, ambos atributos forman una única FK compuesta y no dos vínculos redundantes. Corregiremos la representación de Draw.io para mostrar un único conector hacia `VERSION_RECETA`.

## 10. Verificaciones residuales del RP antes de aprobar

1. **Migración íntegra:** demostrar que el origen `BACKFILL_V2` y el desglose obligatorio cubren también movimientos históricos de insumos con stock final cero, sin fabricar cantidades ni perder referencias; conciliar el saldo inicial y las secuencias históricas.
2. **Ajustes positivos:** conciliar la regla de incrementar una partida existente en `UC-42` con el `CHECK cantidadDisponible <= cantidadInicial` del DDL propuesto; definir la operación transaccional aplicable sin dejar saldos imposibles.
3. **Integridad entre compra y partida:** comprobar que una partida ligada a `RECEPCION_COMPRA` corresponde al mismo insumo y que las sumas de partidas y detalles respetan la unidad convertida de la recepción.
4. **Pruebas de comportamiento:** establecer validaciones transaccionales de sumas, apertura explícita/implícita, no consumo de vencidos, concurrencia por insumo y conciliación del saldo materializado.
5. **Propagación y numeración:** verificar que la RD aprobada agregue una referencia posterior sin reemplazar G0, que Draw.io represente la FK compuesta, y que Flyway utilice una versión posterior a `V2__f01_cobro_estados.sql`.

**Conclusión operativa:** abrir ahora **solo el PR de este documento para revisión**. El RP de cambio del MR, su aprobación y la implementación quedan sujetos a la secuencia A → B → C; la comunicación al profesor puede avanzar en paralelo.
