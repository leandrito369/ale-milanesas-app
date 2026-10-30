# Registro de resultados UC-54 — ejecución real y cobertura

**Fecha:** 30/10/2026 UTC. **Operación:** UC54 `restablecerAccesoUsuario` — RD-UC54-001.  
**PR:** [#9](https://github.com/leandrito369/ale-milanesas-app/pull/9).  
**SHA de primer ensayo:** `2b86eeaa4073da1bc824881300339baaba0bdee7`.  
**SHA del ensayo A3:** `3566e6833a88b3908c8eaeeec577a4ebde7e19d0`.

## Evidencia observada

| Evidencia | Resultado |
|---|---|
| [B1 #40985072346](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40985072346) | **55/55 tests PASS**, 0 failures/errors/skips |
| `PoliticaRestablecimientoProveedorTest` | 5/5 unitarios (AAA, decisión y límites) |
| `RestablecerAccesoUsuarioServiceIntegrationTest` | 9/9 integración PostgreSQL/Testcontainers |
| [B2 #40985072578](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40985072578) | SUCCESS: MR5/F01, triple huella MR6 y restauración |
| JaCoCo — aplicación | **92,2 % líneas** (333/361), **75,8 % ramas** (185/244) |
| JaCoCo — acceso | **93,0 % líneas** (279/300), **75,9 % ramas** (170/224) |
| Requisitos de aceptación B4/UI, incluida cancelación | **NO EJECUTADO** — [Issue #10](https://github.com/leandrito369/ale-milanesas-app/issues/10) |
| UC54-A3 unidad + rollback PostgreSQL | **2/2 PASS** — fallo SQL simulado en mock y fallo posterior al UPDATE real antes del commit |

## Decisión de revisión

Las pruebas **de unidad e integración B3** para UC54 quedaron verificadas, pero la operación no tiene **aceptación funcional completa** ni API/B4. El Administrador debe confirmar identidad comercial a partir de información disponible por vista autorizada; el booleano solo expresa su declaración. La UI aún no existe; [issue #10](https://github.com/leandrito369/ale-milanesas-app/issues/10). El reporte de cobertura mide código ejecutado, no la totalidad de las 67 operaciones ni los 18 escenarios BC06.

**Estado de integración:** PR #9 abierta y pendiente de aprobación del responsable; no fusionar por resultado de CI solamente.

## Ensayo A3 complementario y decisión de cobertura (30/10/2026 UTC)

La ejecución anterior de **55/55** fue íntegra en su corte histórico. El cambio posterior agrega dos tests sin modificar servicios ni esquema:

| Evidencia nueva | Resultado |
|---|---|
| [B1 #40991928240](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40991928240) | **SUCCESS, 57/57** (55 anteriores + 2 A3) |
| `RestablecerAccesoUsuarioPersistenciaFailureUnitTest` | **1/1 PASS**, excepción simulada al UPDATE, ningún secreto expuesto en mensaje |
| `RestablecerAccesoUsuarioA3RollbackIntegrationTest` | **1/1 PASS**, UPDATE y trigger reales antes del fallo; rollback post-transacción verificado |
| [B2 #40991928301](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40991928301) | **SUCCESS** MR5/F01, triple huella MR6/restauración |
| JaCoCo aplicación | **92,2 % líneas (333/361)** y **75,8 % ramas (185/244)**; cifras observadas, sin confundirlas con aceptación |

**Rigor:** el test unitario no prueba atomicidad. La integración sí demuestra rollback del UPDATE y de su efecto de trigger, con semilla confirmada previamente y consultas fuera de la transacción fallida. El fallo fue **inyectado luego del UPDATE** y antes del commit, no provocado por una excepción nativa del motor durante la sentencia. No atribuir a esta prueba la cancelación del Administrador en B4.

**Puerta de revisión:** la PR #9 sigue **abierta**; su fusión requiere aprobación explícita y CI verde sobre el último commit. La aceptación E2E UC54, [Issue #10](https://github.com/leandrito369/ale-milanesas-app/issues/10), sigue **NO EJECUTADA**; igualmente BC-06 sigue **0/18**.
