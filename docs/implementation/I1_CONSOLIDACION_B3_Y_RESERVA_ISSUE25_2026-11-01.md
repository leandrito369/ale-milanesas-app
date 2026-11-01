# Acta de consolidación I1 B3 — 01/11/2026

**Estado al 01/11/2026:** rama I1 B3 consolidada, con **RD-UC50-001, RD-UC19-001 y RD-UC19-002 APROBADAS**, Issues #25/#21 cerradas. El responsable **autorizó expresamente el merge de PR #26 a main incluyendo el recorte de precios futuros UC-19**. Confirmar B1/B2 sobre el HEAD final antes de ejecutar GitHub merge. B4/E2E no acreditados.

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

## Issue #21 — RD-UC19-001 APROBADA y reserva de primer evento cerrada

El responsable del proyecto ratificó expresamente el 01/11/2026 **RD-UC19-001 — Mapeo `MODIFICACION_PROGRAMADA` con `precioAnterior=NULL`**. [Texto de la decisión aprobada](https://github.com/leandrito369/ale-milanesas/blob/main/docs/01-gobierno/b0/RD_UC19_001_AUDITORIA_PRIMER_PRECIO_2026-11-01.md). [Issue #21](https://github.com/leandrito369/ale-milanesas-app/issues/21) cerrada como `completed`.

El primer precio en UC19 se registra en `PRECIO_PRODUCTO` y `AUDITORIA_PRECIO` de forma atómica, con `tipoEvento='MODIFICACION_PROGRAMADA'`, `precioAnterior=NULL`, `vigenteDesdeAnterior=NULL`, `precioNuevo` positivo y valor de `vigenteDesdeNuevo`, usuario y fecha del evento reales. **No** se inventa `ALTA_PRECIO`, ni `CANCELACION_PROGRAMADA` cuando no existe cancelación real. `CIERRE_VIGENCIA` permanece para el ajuste real del fin de la versión anterior; no cambia el precio histórico de Pedidos. La semántica física y de negocio se validó mediante `PreciosProductoServiceIntegrationTest` y `PrecioProductoConcurrenciaIntegrationTest` en PostgreSQL 17.11; MR5/F01 permanece inmutable.

**SHA previo de lógica y prueba reforzada:** `cc2f6dc5ed37229f0e4cb31baf667fe002a2ee6a`: [B1 #41316787755](https://github.com/leandrito369/ale-milanesas-app/actions/runs/41316787755) y [B2 #41316787756](https://github.com/leandrito369/ale-milanesas-app/actions/runs/41316787756), ambos **SUCCESS 137/137**; restauración de 1225 objetos y triple huella MR6 equivalente. Esta adenda cambia solo documentación y deberá revalidarse B1/B2 del nuevo HEAD.

**Aprobación adicional — [RD-UC19-002](https://github.com/leandrito369/ale-milanesas/blob/main/docs/01-gobierno/b0/RD_UC19_002_ACOTAMIENTO_VIGENCIAS_FUTURAS_2026-11-01.md):** El responsable autorizó expresamente el comportamiento actual de recortar una vigencia futura anterior cuando el nuevo precio empieza después de su inicio y dentro de ella, cerrando `vigenteHasta` y emitiendo `CIERRE_VIGENCIA` sin alterar precios congelados ni crear `CANCELACION_PROGRAMADA`. La autorización también incluye **fusionar PR #26 a main**. El merge solo debe ejecutarse tras B1/B2 SUCCESS en el HEAD final y cumplimiento de las protecciones de GitHub.

## Gate de integración obligatorio

- Ejecutar en un mismo HEAD **B1** (`mvn clean verify` + JUnit + Testcontainers + JaCoCo) y **B2** (Flyway V1/V2, integridad fuente MR5/F01, triple huella MR6, backup/restore físico 1.225 objetos), cotejando artefactos/digests y sin sumar resultados históricos por rama.
- Registrar fallos intermedios con causa y SHA. No atribuir a SonarQube un resultado inexistente.
- **Prohibiciones y límites:** la aprobación del responsable habilita merge de PR #26 exclusivamente tras gate técnico y verificaciones GitHub; no cambios MR5/F01 ni reinvención de auditoría de eventos; UC03/21 no persisten Pedido desde UC16; no UC10/42 ni SALDO_INICIAL; no HTTP B4 ni E2E hasta resolver el gate I1. No confundir prueba B3 integrada con acreditación de 18 escenarios BC06 ni cuatro UC54.
- Dependencias restantes: revisión de locks UC29/UC15 con futuros servicios y verificaciones finales del merge GitHub. **Issues #21 y #25 resueltas; RD-UC19-002 ratifica el recorte futuro y la integración**. La aprobación en chat no se presenta como una review independiente `APPROVED` del autor en GitHub.
