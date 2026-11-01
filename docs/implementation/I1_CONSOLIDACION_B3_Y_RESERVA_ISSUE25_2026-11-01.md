# Acta de consolidación I1 B3 — 01/11/2026

**Estado:** rama técnica de integración **sin merge a main**, pendiente gate B1/B2 del SHA definitivo y revisión documental; no equivale a cierre funcional total de I1.

## Corte y procedencia

La rama `integration/i1-b3-completo-2026-11-01` deriva del HEAD de [PR #23](https://github.com/leandrito369/ale-milanesas-app/pull/23), `c75d253bffd6ce534c3efb3b8496427184538ee1` (conciliación UC29 con UC15/16/57 y prueba real de COMMIT). Se incorporaron por copia fiel de sus archivos de código, pruebas y documentación, **sin hacer merge de las PR originales a main**, los cambios completos de:

| PR | Incremento | HEAD de procedencia |
| --- | --- | --- |
| [#16](https://github.com/leandrito369/ale-milanesas-app/pull/16) | Usuario UC55 | `5cd06999737455ced1e820f2c075f7be0a07ee3f` |
| [#17](https://github.com/leandrito369/ale-milanesas-app/pull/17) | I1-02 Cliente UC02/32/38 | `10603a70b0aa90022a4760cb58e52941901a3278` |
| [#18](https://github.com/leandrito369/ale-milanesas-app/pull/18) | I1-03 Producto UC30/35/47/48 | `115b5b163c70ff3264af81820393b9e44dc6868f` |
| [#20](https://github.com/leandrito369/ale-milanesas-app/pull/20) | I1-04 Precio UC19/20 | `c1ba992f95ac7755c27fda75c94cacfe732bb7d0` |
| [#24](https://github.com/leandrito369/ale-milanesas-app/pull/24) | I1-06 Insumo UC50/51/52/36 | `9652d956896eabad51f2088cdc083e061e75c898` |

`EvaluadorCategoriaComercialInterno` permanece en la variante conciliada de PR23 (una marca PostgreSQL, dos firmas, bloqueo CLIENTE/política y FIFO), sin volver a introducir la versión previa de PR14. Los cambios portados de PR22 y PR14 ya se encuentran en PR23. I1-01 solo incorpora lo que contiene `main` más la PR16; no se afirma que estén implementados automáticamente todos los CU de identidad sin comprobación individual.

## Issue #25 — discrepancia y propuesta de cierre normativo

El [backlog G0 de 29/10/2026](https://github.com/leandrito369/ale-milanesas/blob/main/docs/01-gobierno/b0/B0_BACKLOG_I0_I1_PRIMER_VERTICAL_2026-10-29.md), SHA `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, fija expresamente **I1-06 = UC50/51/52/36**, alta Insumo stock 0 y prohibición de `SALDO_INICIAL` antes de UC42.

**Incompatibilidad de numeración:** en `C_ANEXO_49_CU_TRES_NIVELES_ACTUALIZADO_23-10-2026.md` y `C_CasosDeUso_y_Estado_ACTUALIZADO_23-10-2026.md`, fecha anterior, **UC50 = Registrar Resultado de Reembolso Electrónico** y **UC36 = Buscar Producto**. Es una versión de 49 CU, no la línea G0 de 64 CU. **No usarla para inventar las fichas Fase4 de Insumos**; hace falta identificar la renumeración y revisar las nuevas fichas UC50/51.

La decisión técnica provisional de PR24 deja explícito:
- **Alta UC50 B3**: solo ADMIN, unidad preexistente; exige umbral y objetivo explícitos para campos NOT NULL MR5; `stockActual=0`, `automatizacionCompraActiva=false`, `activo=true`; no genera movimiento de Insumo, costo ni recepción ficticia. Esta es una regla conservadora implementada, **no demostración de aceptación normativa de parámetros**.
- **UC51**: modificar nombre/unidad únicamente, sin alterar stock ni umbral/objetivo (UC31). Rechazar cambio de unidad con historia, conservar `idInsumo`; extensiones requieren revisar ficha Fase4 del corte posterior al 23/10.
- **UC52**: ADMIN cambia activo sin cancelar órdenes/recetas ni crear stock. **UC36**: Admin y Responsable Producción ven Insumos activos/inactivos (REV06B G0).
- **Unidades**: catálogo `UNIDAD_MEDIDA` de solo lectura y referencias históricas por FK; no se implementa CRUD ni conversión por inferencia.

**Condición de cierre Issue #25:** contar con fichas de Fase4 de UC50/51 posteriores a la renumeración y una decisión explícita sobre RBAC, parámetros de alta, automatización, duplicados y campos mutables, o aprobar una RD acotando expresamente este contrato mínimo. Mientras ese soporte no exista, **Issue #25 continúa abierta como bloqueo documental**, aunque B1/B2 sean verdes. No equivale a no haber hecho la consolidación técnica.

## Gate de integración obligatorio

- Ejecutar en un mismo HEAD **B1** (`mvn clean verify` + JUnit + Testcontainers + JaCoCo) y **B2** (Flyway V1/V2, integridad fuente MR5/F01, triple huella MR6, backup/restore físico 1.225 objetos), cotejando artefactos/digests y sin sumar resultados históricos por rama.
- Registrar fallos intermedios con causa y SHA. No atribuir a SonarQube un resultado inexistente.
- **Prohibiciones y límites:** no merge automático a `main`; no cambios MR5/F01 ni reinvención de auditoría de eventos; UC03/21 no persisten Pedido desde UC16; no UC10/42 ni SALDO_INICIAL; no HTTP B4 ni E2E hasta resolver el gate I1. No confundir prueba B3 integrada con acreditación de 18 escenarios BC06 ni cuatro UC54.
- Dependencias adicionales: Issue #21 para primer precio y revisión funcional PR20, revisión de locks UC29/UC15 con futuros servicios, y revisión humana de las PR de origen.
