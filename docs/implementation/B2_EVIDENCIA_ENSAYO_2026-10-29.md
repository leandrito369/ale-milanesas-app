# B2 — Informe de ensayo reproducible MR-5/MR-6 + F01

**Fecha:** 29/10/2026  
**Repositorio:** `leandrito369/ale-milanesas-app` · **PR #3** (abierta, pendiente de revisión/merge)  
**Commit del SQL/ensayo validado:** `2311568ab215ede4f7e17ca733a90b8a33312251`  
**GitHub Actions final:** [B2 #40944185068](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40944185068) (**SUCCESS**) y [B1 regresión #40944184919](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40944184919) (**SUCCESS**).  
**Motor real:** PostgreSQL 17.11 (servicio CI y contenedores Testcontainers), JDK 21/Spring Boot 4.1.2.

## Pruebas y resultados observados

| Identificador | Evidencia | Resultado |
|---|---|---|
| B2-01 | SQL V1 coincide literalmente con MR5 del `source_commit` G0, quitando 2 delimitadores transaccionales | **PASS** |
| B2-02 | SQL V2 coincide literalmente con F01 de G0, quitando 2 delimitadores y `\set`, sin cambiar precheck/constraint/trigger | **PASS** |
| B2-03 | `mvn -B -ntp clean verify` con 6 pruebas Java/Testcontainers | **6/6 PASS**, 0 fallos, errores u omitidos |
| B2-04 | Flyway en DB nueva: `flyway_schema_history` versiones 1 y 2, segunda invocación sin reejecutarlas | **PASS**, 2 migraciones |
| B2-05 | Catálogo de tablas del dominio, sin `flyway_schema_history` | **60 tablas / 453 columnas / 125 FK** |
| B2-06 | F01: CHECK, trigger, prueba de estados prohibidos/idempotencia en DB descartable original | **PASS** |
| B2-07 | Comparación estricta de huella MR6 entre MR5+F01 directa, histórica 59→MR3→MR5→F01 y Flyway V1/V2 | **PASS** — sin diferencias |
| B2-08 | Backup **completo** `pg_dump -Fc`, restauración `pg_restore` en cuarta DB con extensión `btree_gist` | **PASS** |
| B2-09 | Catálogo restaurado: **1.225** objetos; **1.187** definiciones idénticas + **38** CHECK ANY(ARRAY) reserializados | **PASS** — solo diferencias de paréntesis/casts redundantes, ninguna otra |
| B2-10 | Una `UNIDAD_MEDIDA` sintética preservada tras restaurar, `flyway_schema_history` con 2 filas y pruebas F01 en restaurada | **PASS** |

**Nota sobre la huella:** El cotejo triple **pre-restauración** conserva el comparador estrictamente literal de MR6. El cotejo de **restauración** aplica un normalizador limitado a 38 `CHECK ... ANY(ARRAY...)` reescritos por el deparser PostgreSQL al recrear constraints; no ignora diferencias en columnas, índices, funciones, triggers, nombres, términos de otras constraints ni literales de dominio.

## Historial transparente de incidencias y correcciones

1. [B2 #40942382163](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40942382163) falló al ejecutar batería F01 de referencia porque esa prueba usa internamente `mr5_validacion`, no el schema aplicativo `ale_gestion`. **Corrección:** adaptar **solo copia de ensayo de la prueba**, no F01 ni MR5; triple huella posterior pasó.
2. [B2 #40942670984](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40942670984) **SUCCESS**: seis pruebas y triple huella MR6 idéntica, antes de agregar backup/restore.
3. [B2 #40943123186](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40943123186) falló el respaldo filtrado `pg_dump -n ale_gestion` por objetos de extensión `btree_gist` omitidos. **Corrección:** respaldo completo del DB descartable.
4. [B2 #40943450111](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40943450111) restauró correctamente pero un `diff` literal encontró 38 diferencias de deparser `CHECK ANY(ARRAY)`; 1.225 identidades de objetos estaban conservadas. **Corrección:** normalizar exclusivamente esos casts/paréntesis en un comparador separado y revalidar F01 sobre restaurada.
5. [B2 #40944185068](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40944185068) **SUCCESS**, todo verde, incluidos backup, restore, datos sintéticos y comprobaciones F01. [B1 regresión #40944184919](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40944184919) **SUCCESS**.

## Alcance real y reservas

**Sí se ha ensayado** una **instalación nueva y vacía** con MR5+F01 por Flyway y se ha contrastado contra la ruta de diseño histórica de 59 tablas (también vacía). **No** se han migrado registros existentes ni datos reales; ello exige precheck, backup anonimizado, estrategia de transformación/retorno y aprobación propia.

La base restaurada utiliza un dato ficticio de prueba, sin información real de Clientes/Cobros. **No** hay API de Pedidos, Caja, RBAC real, webhooks ni servicios de negocio implementados; BC-06 sigue **0/18**. **F04/S-01** continúa restringida por **CR-B0-F04-001**. La evidencia de CI no equivale a aceptación académica ni a despliegue de producción.

**Dictamen técnico:** las migraciones de B2 son **reproducibles y superaron sus ensayos en CI**, disponibles para revisión de la PR #3. **No declarar B2 integrado hasta la revisión/fusión del PR**, y no aplicar estas migraciones sobre una DB existente con datos.
