# Acta de consolidación I1 B3 — 01/11/2026

**Estado:** rama técnica de integración **sin merge a main**, pendiente repetición B1/B2 del SHA posterior a RD-UC50-001 y revisión de integración; no equivale a cierre funcional total de I1.

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

## Issue #25 — decisión APROBADA RD-UC50-001 y fuentes renumeradas

**Estado actualizado el 01/11/2026:** decisión `RD-UC50-001` **APROBADA (Opción A)** por el responsable. [Texto de la RD canónica](https://github.com/leandrito369/ale-milanesas/blob/main/docs/01-gobierno/b0/RD_UC50_001_ALTA_INSUMO_Y_MODIFICACION_2026-11-01.md). [Issue #25](https://github.com/leandrito369/ale-milanesas-app/issues/25) queda resuelta por la aprobación documental; no requiere cambiar código ni MR5/F01.

Se identificó la fuente actualizada `ALE_MILANESAS_FASE_4_CU_01_A_64_TRES_NIVELES.docx` (equivalente a `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`) y la partición correcta de B0: UC50 registrarInsumo y UC51 modificarInsumo están en **REV-01**, junto con UC42; REV-06B.2 reúne los otros seis CU de inventario. Los archivos de 49 CU del 23/10 son antecedentes de numeración previa, **no** evidencia contradictoria para el catálogo de 64 CU.

La ratificación permite concluir que UC50/51 están homologados al alcance B3 actual:
- **UC50:** exclusivamente ADMINISTRADOR. Informa nombre, unidad registrada, umbral >= 0 y objetivo > umbral. Alta `INSUMO.activo=true` con `stockActual=0`; sin `MOVIMIENTO_INSUMO`, `COSTO_INSUMO_HISTORICO`, `SALDO_INICIAL`, compra ni ronda automática. `automatizacionCompraActiva=false` queda **ratificada expresamente** como excepción acotada al paso de captura de UC50 Real; el Administrador no informa ese booleano en esta operación B3 y UC31 gestionará su habilitación posterior.
- **UC51:** exclusivamente ADMINISTRADOR. Modifica solo nombre y unidad; verifica nombre normalizado único, unidad existente y dependencias históricas antes de cambiar unidad. Preserva `stockActual`, costos, parámetros y estado. La búsqueda UC36 incluida funcionalmente se realiza mediante identificación/consulta interna antes de bloquear, sin atribuir GUI/HTTP B4.
- **UC52/UC36:** se conservan según contratos G0 REV06B; no se inventa CRUD de unidades ni conversión automática.

El `stockActual=0` **no** es una inmovilidad permanente del campo: indica ausencia de apertura física registrada al alta; UC42 podrá registrar movimientos reales posteriormente. La unicidad normalizada se controla en el servicio bajo protocolo cooperativo; no se declara una restricción SQL nueva.

**Evidencia anterior a esta adenda documental:** SHA `c372aed91b719032efd678191c3614932b1bb62f`, B1/B2 **136/136 SUCCESS**, 3 rutas MR6, restauración 1.225 objetos y digests registrados en la PR. El nuevo SHA documental requiere repetición B1/B2 para ser gate final.

## Gate de integración obligatorio

- Ejecutar en un mismo HEAD **B1** (`mvn clean verify` + JUnit + Testcontainers + JaCoCo) y **B2** (Flyway V1/V2, integridad fuente MR5/F01, triple huella MR6, backup/restore físico 1.225 objetos), cotejando artefactos/digests y sin sumar resultados históricos por rama.
- Registrar fallos intermedios con causa y SHA. No atribuir a SonarQube un resultado inexistente.
- **Prohibiciones y límites:** no merge automático a `main`; no cambios MR5/F01 ni reinvención de auditoría de eventos; UC03/21 no persisten Pedido desde UC16; no UC10/42 ni SALDO_INICIAL; no HTTP B4 ni E2E hasta resolver el gate I1. No confundir prueba B3 integrada con acreditación de 18 escenarios BC06 ni cuatro UC54.
- Dependencias adicionales: Issue #21 para primer precio y revisión funcional PR20, revisión de locks UC29/UC15 con futuros servicios, y revisión humana de las PR de origen.
