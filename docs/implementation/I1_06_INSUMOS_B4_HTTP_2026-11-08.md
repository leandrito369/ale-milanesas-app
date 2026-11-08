# I1-06 — B4 HTTP de UC50/51/52 y dependencias MR-6

Fecha del corte: 2026-11-08. Base verificada: origin/main `12e30c5` (merge I1-05).
Se agregan tres escrituras, cada una con gate independiente y rol ADMINISTRADOR.
UC36 permanece en ConsultasI1Controller, sin cambios ni otro endpoint de lectura.
Issue #28: sin despliegue; entrega por PR draft, sin merge automático.

## Fuentes y precedencia

- G0 source_commit `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` y governance_commit
  `27738b089afdd2698cdae915f75c0e8089a6248a`, preservados según source-lock.
- Contratos consolidados: `docs/08-diagramas/consolidacion-b0/CONTRATO_UC50_REV01_B0_ACTUALIZADO.md`
  y `CONTRATO_UC51_REV01_B0_ACTUALIZADO.md`.
- UC52: `docs/08-diagramas/revision/REV06B_CLIENTES_RECETAS_INVENTARIO/propuestos/CONTRATOS_REV06B_TRECE_CORREGIDOS.md` §11.
- DSS/DS de G0: REV01_UC50/propuestos/DSS_ADMIN_6A_REV01.drawio.xml,
  DS_UC50_REV01.drawio.xml; REV01_UC51/propuestos/DS_UC51_REV01.drawio.xml;
  REV06B2_RECETAS_INVENTARIO/propuestos/DSS_REV06B2_RECETAS_INVENTARIO_UC09_10_31_36_52_64.drawio.xml
  y DS_REV06B2_RECETAS_INVENTARIO_UC09_10_31_36_52_64.drawio.xml.
- Fichas Real UC50/51/52: docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx.
- RD-UC50-001, docs/01-gobierno/b0/RD_UC50_001_ALTA_INSUMO_Y_MODIFICACION_2026-11-01.md,
  prevalece sobre la firma anterior de cinco parámetros: alta con cuatro entradas,
  automatización false. Homologa modificarInsumo a nombre y unidad, preservando historia.
- RD-RP016-001: alta sin stock físico ni costo ficticio; primera existencia por UC42.
- RD-MR-PARTIDAS-001 D1, merge `cd603d25addb4312d9bee62ec03514760f7e2894`, y
  docs/04-casos-uso/ADENDA_MR6_IMPACTO_8_UC_2026-11-07.md §UC51: las partidas son
  referencias históricas que bloquean reinterpretar su unidad.
- B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv, filas UC50/51/52:
  ADMINISTRADOR exclusivo. El permiso de Producción para UC36 no autoriza escrituras.
- B3 integrado por `fd4bd6d9dd89e43b2fdec29c49a553a3f1b15d93`; conserva las tres firmas públicas.

| CU | B3 | Persistencia | HTTP / test |
|---|---|---|---|
| UC50 | GestionInsumosService.registrarInsumo | INSUMO, sin movimientos/costo/partidas | POST /api/v1/insumos; RegistroInsumoHttpExternoTest |
| UC51 | GestionInsumosService.modificarInsumo | Nombre/unidad de INSUMO bajo bloqueo de historia | PUT /api/v1/insumos/{idInsumo}; ModificacionInsumoHttpExternoTest |
| UC52 | GestionInsumosService.cambiarEstadoInsumo | Estado lógico, sin borrar ni cancelar referencias | PATCH /api/v1/insumos/{idInsumo}/estado; EstadoInsumoHttpExternoTest |
| UC36, existente | ConsultarInsumosUnidadesService vía ConsultasI1Controller | Solo lectura | GET /api/v1/insumos y /api/v1/unidades; ConsultasI1HttpIntegrationTest intacto |

## Decisiones de implementación

| # | Decisión | Respaldo / motivo |
|---|---|---|
| D1 | Tres controllers separados y tres advices acotados | Patrón de escrituras de Producto/Cliente. Comparten parser y base de errores, sin modificar el JSON global. |
| D2 | POST /insumos, PUT /insumos/{id}, PATCH /insumos/{id}/estado bajo /api/v1 | Rutas del adaptador dentro del margen de contratos, análogas a Productos. No se duplican lecturas UC36. |
| D3 | activo boolean y EstadoInsumo con dependencias | B3 recibe Boolean; estado repetido devuelve huboCambio=false en B3 y se comunica como 409 SIN_CAMBIO en B4. |
| D4 | Alta con whitelist de cuatro campos | RD-UC50-001 y E5: stock/costo y automatización se rechazan incluso con valores 0/false/NULL. |
| D5 | Dependencias agrega partidas y las incluye en tieneHistoria | MR-6 D1 y adenda UC51; no se quitan las seis dependencias anteriores. |
| D6 | Cambio de unidad con historia ->409 CAMBIO_UNIDAD_BLOQUEADO | UC51 E3, revalidado dentro de B3; renombrar sin reinterpretar unidad sigue permitido. |

## Refactor dependencias() — MR-6 D1

Antes del cambio, una partida podía quedar como única referencia de un Insumo y
no impedir cambiar su unidad. Se agrega int partidas al record Dependencias,
un subselect count(*) por idInsumo sobre PARTIDA_INSUMO y partidas>0 a tieneHistoria().
Se cuentan todas las partidas, también agotadas: stock/disponibilidad cero no borran historia.

No cambia ninguna firma de registrarInsumo/modificarInsumo/cambiarEstadoInsumo,
ni su flujo de locks/validaciones. Se conservan los seis conteos y el comportamiento
sin partidas. El reporte de estado expone ahora siete conteos de dependencias.
No se actualiza stock, costo, lote ni unidad de partidas. Sin cambios de migración.

Dos pruebas B3 nuevas: una crea una partida por el servicio real UC08 y verifica
el incremento del conteo; la otra usa una partida agotada como única dependencia,
comprueba los seis conteos anteriores en cero y rechaza el cambio de unidad.
La segunda aísla el gap: movimientos, costos o compras no pueden ocultar el fallo.

## Bodies, respuestas y errores

Alta admite únicamente nombre, codigoUnidadMedida, umbralMinimo y
nivelObjetivoReposicion. B3 valida nombre/unidad y objetivo>umbral>=0; HTTP conserva
BigDecimal exacto dentro de NUMERIC(18,3), sin coerción desde string ni truncamiento.
JSON con campos desconocidos, claves duplicadas, tokens sobrantes, controles NUL
o tipos incorrectos se rechaza con 400. El alta crea activo=true, stock=0 y
automatizacionCompraActiva=false; no crea PARTIDA, MOVIMIENTO ni COSTO.

PUT admite solo nombre y codigoUnidadMedida. No edita stock, reposición ni estado.
Se permite cambiar unidad sin historia y renombrar con historia. Un PUT sin cambios
conserva la respuesta 200 con huboCambio=false de B3.

PATCH admite solo activo boolean real. Responde EstadoInsumo incluyendo sus siete
dependencias; no cancela solicitudes/órdenes ni elimina historia o existencias.
La traducción de no cambio a 409 ocurre solo en HTTP, tras la respuesta de B3.

| Situación | Respuesta |
|---|---|
| Alta / modificación / estado cambiado | 201 / 200 / 200, records existentes |
| Insumo inexistente | 404 INSUMO_NO_ENCONTRADO |
| Unidad inexistente | 400 UNIDAD_INEXISTENTE |
| Nombre normalizado duplicado | 409 INSUMO_DUPLICADO |
| Cambio de unidad con historia | 409 CAMBIO_UNIDAD_BLOQUEADO |
| Estado solicitado igual al actual | 409 SIN_CAMBIO |
| Body, ID, parámetros o tipos inválidos | 400 SOLICITUD_INVALIDA |
| Token ausente/revocado o cookie sin Bearer | 401 SESION_INVALIDA del filtro existente |
| Rol no ADMIN / SecurityException de B3 | 403 OPERACION_PROHIBIDA |
| DataAccessException | 503 PERSISTENCIA_NO_DISPONIBLE |

Todos llevan Cache-Control: no-store; no se devuelve SQL ni mensajes internos.

## Tres gates independientes y UC36 intacto

insumos-uc50-enabled, insumos-uc51-enabled e insumos-uc52-enabled, false por defecto,
bajo ale.http.writes. Cada controller usa !prod y su condición de bandera.
Seguridad reutiliza el predicado ADMIN existente; PATCH precede a PUT y todos los
matchers de escritura distinguen explícitamente el método HTTP. Los tres vetos
se prueban por separado; prod no puede habilitar ninguna de estas escrituras.

Cada suite verifica su gate apagado con los otros dos activos. Otra comprobación
apaga los tres: POST/PUT/PATCH ->403 y GET de UC36/unidades ->200. Los tres controllers
se excluyen del perfil prod aun con banderas true. No se modifica ConsultasI1Controller,
sus mappings ni su suite de once pruebas. Sus GET continúan autorizando ADMIN y Producción.

## Pruebas reproducibles y resultados

Docker disponible; imagen postgres:17.11-alpine, Flyway real. Fixture UC08 existente
reutilizada sin modificar para identidades y datos sintéticos. El soporte HTTP se
comparte en las suites nuevas; todas las aserciones de respuesta verifican no-store.

| Suite | Resultado |
|---|---|
| InsumosUnidadesServiceIntegrationTest | 14/14 (12 existentes + 2 nuevos) |
| InsumoConcurrenciaIntegrationTest | 1/1 existente |
| RegistroInsumoHttpExternoTest | 7/7 |
| ModificacionInsumoHttpExternoTest | 7/7 |
| EstadoInsumoHttpExternoTest | 6/6 |
| ConsultasI1HttpIntegrationTest | 11/11, intacto |
| PrecondicionesDespliegueTest | 24/24 |
| AutenticacionHttpExternaTest | 11/11 |
| Total de las suites verificadas | 81/81, sin errores ni omisiones |

El total B3 actual es 13 existentes +2 nuevos =15, frente a los 14 estimados en el
comando del encargo. El caso PUT sin historia se denomina putPermiteCambioUnidadSinHistoria,
porque verifica permiso, no rechazo. No cambia la regla funcional solicitada.

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=InsumosUnidadesServiceIntegrationTest,InsumoConcurrenciaIntegrationTest test
mvn -B -ntp -Dtest=RegistroInsumoHttpExternoTest,ModificacionInsumoHttpExternoTest,EstadoInsumoHttpExternoTest,ConsultasI1HttpIntegrationTest,PrecondicionesDespliegueTest,AutenticacionHttpExternaTest test
```

Compilación: BUILD SUCCESS. En este entorno se usa temporalmente
JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1 por incidencias previas del JDK local,
sin incorporarlo al proyecto ni a CI. El workflow i1-06-insumos-http-focused.yml
usa Java21 estándar y publica reportes en artifact i1-06-insumos-http.

## Invariantes

V1/V2/V3, source-lock, gobierno y controllers UC19/20/57/08/42 intactos. UC36 y sus
tests intactos. Solo se amplía dependencias() en B3; las pruebas anteriores no se
reescriben. No se implementan UC31, stock/costo inicial ni UI. Issue #28: sin despliegue;
las pruebas sintéticas no constituyen aceptación humana ni certificación productiva.
