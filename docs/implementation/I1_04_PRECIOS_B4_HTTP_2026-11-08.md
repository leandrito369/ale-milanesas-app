# I1-04 — B4 HTTP de UC19 y UC20: precios de Producto

Fecha de este corte: 2026-11-08. Base de implementación: origin/main `1d060b9`,
que contiene los servicios B3 fusionados en `fd4bd6d9dd89e43b2fdec29c49a553a3f1b15d93`.
UC19 escribe bajo bandera no-prod; UC20 consulta sin depender de esa bandera.
Ambos pertenecen exclusivamente a ADMINISTRADOR. Issue #28: sin despliegue.

## Fuentes y trazabilidad

- G0 `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`,
  `governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`, sin modificaciones.
- Contrato canónico: `docs/08-diagramas/consolidacion-b0/CONTRATOS_REV06A_NUEVE_OPERACIONES_B0_ACTUALIZADO.md`,
  §5 actualizarPrecioVigente y §6 consultarHistorialPrecios. Corresponde a REV06A
  y a la ficha Real de UC19/20 en `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`.
- DSS/DS: `docs/08-diagramas/revision/REV06A_COMERCIAL_PROMOCIONES/propuestos/`:
  `DSS_REV06A_CORREGIDO.drawio.xml` / `DS_REV06A_COMPLETO_CORREGIDO.drawio.xml`.
- RBAC: B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv, filas UC19/UC20;
  el permiso de VENDEDOR para buscar Productos por UC35 no habilita precios.
- RD-RP018-001: separa alta UC30 y primer precio UC19; no inventa precio al registrar Producto.
- RD-UC19-001: primera auditoría MODIFICACION_PROGRAMADA, precioAnterior NULL.
- RD-UC19-002: acotar vigencia anterior, incluso futura, sin borrar su historia.
- RD-RP022-001: cuatro decimales internos; se conserva BigDecimal sin pasar por double.
- RD-UC19-001/002 y RD-RP018-001 se inspeccionan en el gobierno posterior integrado en
  `ee555ba9d020aaf66f1f95e1e4e73d99a9235448`. REV06A y RP022 pertenecen al corte
  documental de referencia `6bea1c0`; la precedencia G0 permanece fijada por source-lock.

| CU / operación | B3 existente, intacto | Tablas | HTTP B4 | Prueba |
|---|---|---|---|---|
| UC19 actualizarPrecioVigente | ActualizarPrecioVigenteService | PRECIO_PRODUCTO, AUDITORIA_PRECIO; Producto bloqueado por B3 | POST /api/v1/productos/{idProducto}/precios | RegistrarPrecioProductoHttpExternoTest |
| UC20 consultarHistorialPrecios | ConsultarHistorialPreciosService | PRODUCTO, PRECIO_PRODUCTO, AUDITORIA_PRECIO, solo lectura | GET /api/v1/productos/{idProducto}/precios | ConsultarHistorialPreciosHttpExternoTest |

## Decisiones de implementación

| # | Decisión | Justificación / respaldo |
|---|---|---|
| D1 | POST y GET /api/v1/productos/{idProducto}/precios | REV06A no fija rutas HTTP. Subrecurso del Producto, consistente con B4 de productos; no agrega operación funcional. |
| D2 | vigenteDesde/vigenteHasta ISO-8601 con offset | OffsetDateTime de B3; fin opcional y posterior al inicio. B3 conserva su normalización a microsegundos. |
| D3 | Query desde/hasta LocalDate opcionales | Firma de B3 y REV06A §6. B3 interpreta el calendario comercial de Buenos Aires. |
| D4 | Ambos solo ADMINISTRADOR | Matriz RBAC UC19/20. POST combina bandera y autoridad exclusiva; GET usa hasAuthority(ROL_ADMINISTRADOR). |
| D5 | UC20 sin bandera de escritura ni perfil !prod | Operación de solo lectura: sigue disponible aunque POST esté apagado; no habilita despliegue por sí misma. |

## Rutas HTTP y errores

La ruta es una decisión del adaptador dentro del margen del contrato. Ambos
controllers delegan directamente a los records de salida y métodos existentes
de B3, pasando el UUID de sesión autenticada. No contienen SQL de negocio.

POST recibe únicamente nuevoPrecioBase, vigenteDesde y vigenteHasta opcional.
El parser local conserva BigDecimal/BigInteger, rechaza claves duplicadas,
tokens sobrantes, campos desconocidos, tipos incorrectos y fechas sin offset.
Precio positivo, escala <=4 y dentro de NUMERIC(18,4), sin truncar ni redondear.
La capacidad de enteros se verifica también para rechazar entradas exponenciales
fuera de rango antes de llegar a PostgreSQL. B3 y sus reglas permanecen intactos.

GET admite exclusivamente desde/hasta, a lo sumo una aparición por parámetro,
con fechas ISO válidas. Retorna historial y auditorías tal como los selecciona B3;
no inventa precio actual cuando está ausente. No modifica precios ni eventos.

| Situación | Respuesta |
|---|---|
| Alta de precio / consulta | 201 / 200, records existentes de B3 |
| No se permiten precios retroactivos | 400 VIGENCIA_RETROACTIVA |
| Intervalo solapado con precio programado | 409 SOLAPE_VIGENCIA |
| Rango desde > hasta | 400 RANGO_INVALIDO |
| Body, fechas, ID/rango inválidos; producto inexistente/inactivo según B3 | 400 SOLICITUD_INVALIDA |
| Falta token, cookie sin Bearer o sesión rechazada por el filtro | 401 SESION_INVALIDA |
| Autoridad incorrecta / SecurityException de B3 | 403 OPERACION_PROHIBIDA |
| DataAccessException | 503 PERSISTENCIA_NO_DISPONIBLE |

Advice compartido acotado a los dos controllers; errores no contienen SQL ni el
mensaje interno del servicio. Todas las respuestas, incluidas las del filtro de
seguridad, llevan Cache-Control: no-store.

## Gate y perfil

`ale.http.writes.precios-uc19-enabled=false` por defecto. POST tiene la condición
de bandera, perfil !prod y matcher POST explícito con bandera + ADMIN. La
precondición de arranque prod veta la bandera activa. GET tiene matcher explícito
ADMIN, sin condición de escritura y sin exclusión del perfil prod.

Se comprueba HTTP real GET 200 y POST 403 en un segundo contexto con bandera
false. Un contexto de controllers verifica exclusión POST/presencia GET en prod;
PrecondicionesDespliegueTest cubre el veto. No se certifica un despliegue prod.

## Resultados de tests

PostgreSQL real `postgres:17.11-alpine` con Flyway V1-V3. Se reutiliza
IngresoReventaFixture para Producto y sesiones de cinco roles sin modificarla.
El soporte HTTP se comparte dentro de las dos suites nuevas; no se altera B3.

| Suite | Resultado |
|---|---|
| RegistrarPrecioProductoHttpExternoTest | 8/8 |
| ConsultarHistorialPreciosHttpExternoTest | 7/7 |
| PreciosProductoServiceIntegrationTest | 11/11 |
| PrecioProductoConcurrenciaIntegrationTest | 1/1 |
| PrecondicionesDespliegueTest | 20/20 |
| AutenticacionHttpExternaTest | 11/11 |
| Total focalizado | 58/58, sin errores ni omisiones |

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=RegistrarPrecioProductoHttpExternoTest,ConsultarHistorialPreciosHttpExternoTest test
mvn -B -ntp -Dtest=PreciosProductoServiceIntegrationTest,PrecioProductoConcurrenciaIntegrationTest test
mvn -B -ntp -Dtest=PrecondicionesDespliegueTest,AutenticacionHttpExternaTest test
```

Compilación local: BUILD SUCCESS. Se utiliza temporalmente
`JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1` en este entorno por las incidencias
previas de procesos del JDK local; no se incorpora al proyecto ni a CI.
Workflow focalizado i1-04-precios-http-focused.yml con Java21 estándar, reportes
reales en target/surefire-reports y artifact i1-04-precios-http.

Pruebas adicionales dentro de las 15 nuevas: exactitud de 99999999999999.9999,
precisión excedida, fin vacío tras normalizar microsegundos, retroactividad,
roles no autorizados, sesión revocada, cookies, 503 y ausencia de efectos tras solape.
Tests sintéticos, sin certificación comercial ni aceptación humana.

## Límites del corte

V1/V2/V3, source-lock, gobierno, B3, sus dos suites y controllers UC30/35/47/48
intactos. Cambios existentes limitados a seguridad, veto, bandera y su prueba,
además de README. No se agrega cancelación de precios, UI ni despliegue.
Issue #28 permanece como bloqueo. Entrega por PR draft, sin merge automático.
