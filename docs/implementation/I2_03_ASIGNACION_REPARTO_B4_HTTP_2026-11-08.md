# I2-03 — UC-43: asignación y reasignación de reparto, B3 + HTTP

Fecha: 08/11/2026. Base `main`: `c62f899` (I2-01 integrado).

## Alcance y fuentes

`AsignarRepartoService.asignarOReasignar(UUID, DatosAsignacion)` implementa la
asignación persistente de UC-43 y su historial. B4 expone
`POST /api/v1/pedidos/{idPedido}/asignacion-reparto`, exclusivamente VENDEDOR,
con `pedidos-uc43-enabled=false`, fuera de prod y veto de arranque si se fuerza
el flag en prod. Asignación y reasignación confirmadas devuelven 201.

Fuentes de `leandrito369/ale-milanesas` cotejadas antes de programar:

- `docs/08-diagramas/consolidacion-b0/CONTRATOS_REV04A_CORREGIDOS_B0_ACTUALIZADO.md`, §5, firma, A1–A6 y postcondiciones.
- `docs/08-diagramas/revision/REV04_PEDIDOS_ENTREGAS/propuestos/DS_REV04A_CORREGIDO.drawio.xml` y `DSS_REV04A_CORREGIDO.drawio.xml`.
- Ficha Real UC-43 de `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`.
- `docs/01-gobierno/RD_RP002_001_ELEGIBILIDAD_ASIGNACION_UC43_2026-10-27.md`, seis reglas y serialización.
- `docs/01-gobierno/RD_RP009_001_INHABILITACION_REPARTIDOR_ASIGNACIONES_2026-10-27.md`, coordinación con UC-55.
- `docs/01-gobierno/b0/B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv`, fila UC-43: VENDEDOR autorizado, exclusivamente online.
- BC07: se devuelve solo la referencia de asignación, repartidor, fecha y marca de reasignación. No se proyectan credenciales, costos ni información financiera.

Se conserva [source-lock](../traceability/source-lock.md): G0 aprobado,
`source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` y
`governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`.

## Diagnóstico previo

UC-55 y UC-18 toman `9172001` → `(60505,7)` → USUARIO objetivo `FOR UPDATE`.
UC-43 utiliza el mismo prefijo y `FOR SHARE` para leer al Repartidor elegible;
luego bloquea Pedido y asignación vigente. UC-55 ya consulta exclusivamente
asignaciones vigentes con Pedido PENDIENTE antes de deshabilitar. No había
endpoint de asignación ni servicio UC-43 previo.

V1 define `ASIGNACION_REPARTO` con PK `(idPedido, numeroAsignacion)`, las dos FKs
a USUARIO (Repartidor y Vendedor), FK a PEDIDO, `ck_asignacion_reparto_1`
(`fechaHoraFin >= fechaHoraInicio`) e índice único parcial
`uq_asignacion_reparto_vigente` por Pedido con `fechaHoraFin IS NULL`.
`motivoReasignacion` es TEXT; el límite HTTP/B3 de 250 caracteres es el solicitado
para este corte y no requiere modificar el esquema.

## Decisiones de implementación

| ID | Decisión | Justificación |
|---|---|---|
| D1 | POST `/api/v1/pedidos/{idPedido}/asignacion-reparto` | REV04A no fija rutas. Cada confirmación válida crea una fila de historial. No hay idempotencia de aplicación. |
| D2 | Mismo Repartidor vigente → 409 `SIN_CAMBIO` | A4: no registrar una asignación redundante. |
| D3 | Motivo no nulo en primera asignación → 400 `MOTIVO_NO_APLICA` | Motivo aplicable al reemplazo de una vigente, según §5. |
| D4 | Reasignación sin motivo o con motivo blanco → 400 `MOTIVO_REQUERIDO` | A5. Se conserva esta causa específica frente al rechazo genérico de blancos. |
| D5 | Pedido no PENDIENTE → 409 `PEDIDO_NO_PENDIENTE` | A2; Pedido inexistente → 404 `PEDIDO_NO_ENCONTRADO`. |
| D6 | Repartidor inexistente, deshabilitado o sin Rol → 400 `REPARTIDOR_NO_ELEGIBLE` | A3 / RD-RP002-001. |
| D7 | `COALESCE(MAX(numeroAsignacion),0)+1` por Pedido | PK existente; cálculo dentro del lock del Pedido. |
| D8 | Un `clock_timestamp()` bindeado al cierre y al alta | Frontera temporal idéntica entre asignaciones y preservación del CHECK. No se utiliza el reloj de la JVM. |
| D9 | `9172001` → `(60505,7)` → USUARIO `FOR SHARE` → PEDIDO `FOR UPDATE` → ASIGNACION_REPARTO `FOR UPDATE` | Protocolo común con UC-55/UC-18; revalidación bajo locks y conservación del índice único. |

La identidad del Vendedor procede de `SESION_USUARIO.idUsuario`, no del UUID de
sesión ni de campos suministrados en el body. Se vuelve a autorizar después de
esperar los advisory locks, antes de leer el Repartidor y el Pedido.

## Coordinación con UC-55 y UC-18

Los escritores de identidad UC-55/UC-18 y UC-43 comparten los advisory locks en
el mismo orden. UC-43 no toma primero una fila de Pedido para luego intentar
adquirir esos locks: evita invertir el orden con los servicios de identidad.
Los locks duran hasta COMMIT/ROLLBACK. USUARIO `FOR SHARE` estabiliza estado y
los writers participantes del protocolo estabilizan los roles consultados.
El Pedido queda `FOR UPDATE`, y después su asignación vigente `FOR UPDATE`.

Si UC-43 confirma primero, UC-55 observa la asignación vigente y rechaza la
baja con `RepartidorConPedidosPendientesException`. Si UC-55 confirma primero,
UC-43 relee DESHABILITADO y rechaza el destinatario. El test concurrente ejecuta
**ambas ordenaciones**, mantiene abierta la transacción ganadora y observa en
`pg_locks` que la segunda conexión está esperando el lock advisory `9172001`.
Luego confirma y comprueba el estado final y la excepción de la segunda
operación. No depende de sleeps para suponer que ambas llamadas se solaparon.

Dos reasignaciones válidas a distintos Repartidores pueden confirmarse de
forma serial: 1 → 2 → 3, con tres filas históricas y exactamente una vigente.
Se relee la asignación actual bajo lock, conforme al flujo del encargo. La firma
no contiene una versión esperada de la pantalla; no se inventa un control de
versión ni un mecanismo offline. Ante un cierre que no afecte exactamente una
fila se produce 409 `CONFLICTO_ASIGNACION` (defensa A6).

Esta coordinación no modifica la lógica ni los tests de UC-55/UC-18. No agrega
reglas de zona, capacidad, turnos u horarios, ni cambios de estado de Pedido.

## Seis reglas de elegibilidad — RD-RP002-001

| Regla | Validación en B3 / prueba |
|---|---|
| 1. Solo Pedido PENDIENTE | Relectura bajo `FOR UPDATE`; pruebas ENTREGADO y CANCELADO. |
| 2. Cuenta HABILITADO y rol REPARTIDOR | USUARIO `FOR SHARE` y EXISTS de USUARIO_ROL bajo protocolo de identidad. |
| 3. Una sola asignación vigente | Lock por Pedido, cierre e INSERT atómicos; índice único parcial y dos reasignaciones concurrentes reales. |
| 4. Preservar historial | Solo se actualiza `fechaHoraFin` de la previa; INSERT con número correlativo. Se comparan todos los demás campos. |
| 5. No asignar a deshabilitado | Comprobación dentro de la transacción y carrera real con UC-55 en ambos órdenes. |
| 6. Exclusivamente online | Confirmación inmediata en servidor con sesión vigente. No hay cola offline, clave de replay ni aceptación local. |

## HTTP y garantías de transacción

Whitelist estricta de `idUsuarioRepartidor` y `motivoReasignacion`. El ID debe ser
un entero positivo representable como long, sin coerción de string/decimal.
Motivo opcional y tipado string/null, máximo 250 caracteres antes de trim, sin
NUL; el motivo de reemplazo se guarda recortado. Se rechazan campos extra,
JSON duplicado, documentos adicionales y cuerpos mal formados. Los blancos
se resuelven con las causas D3/D4 según exista asignación vigente.

Solo Bearer VENDEDOR. ADMINISTRADOR, Producción, Repartidor y Proveedor reciben
403; una cookie sola recibe 401. Con el gate UC-43 apagado se devuelve 403 aunque
el gate de UC-03 esté activo. Todas las respuestas llevan `Cache-Control:
no-store`, incluidos 201/400/401/403/404/409/503. Los errores no exponen SQL.

Un fallo de INSERT después del cierre revierte toda la transacción y deja
vigente la asignación anterior. La prueba de rollback crea un trigger de fallo
exclusivamente en su PostgreSQL de test; no deshabilita triggers de dominio.
Se verifica que Pedido completo, DetallePedido, Producto, Cuenta y Caja
permanecen iguales al reasignar. No se actualiza ASIGNACION_REPARTO desde otros
servicios de producción ni se cambian V1/V2/V3.

## Límite reconocido: invalidación de rutas sugeridas

REV04A §5 incluye invalidar una RutaReparto sugerida dependiente de la asignación
reemplazada y señala explícitamente que **su mecanismo técnico está pendiente**.
V1/V2/V3 y `src/main/java` no contienen persistencia ni servicio de rutas
sugeridas: no hay una ruta existente que este corte pueda invalidar. No se
inventan columnas, tablas ni un mecanismo de invalidación. Esa integración
queda pendiente para el corte que implemente rutas; aquí se entrega la
asignación y su historial, sin presentar esa postcondición futura como probada.

## Matriz CU → servicio → DB → API/UI → test

| CU / operación | Servicio | Persistencia | API / UI | Pruebas |
|---|---|---|---|---|
| UC-43 asignarOReasignarPedidoARepartidor | AsignarRepartoService.asignarOReasignar | INSERT ASIGNACION_REPARTO y UPDATE fechaHoraFin previa; lectura USUARIO/USUARIO_ROL/PEDIDO/SESION_USUARIO | POST /api/v1/pedidos/{idPedido}/asignacion-reparto; sin UI nueva | AsignarRepartoServiceIntegrationTest (16), AsignarRepartoConcurrenciaIntegrationTest (2), AsignarRepartoHttpExternoTest (10) |
| UC-55 / UC-18, coordinación | Servicios existentes intactos | Locks de identidad compartidos | Sin cambios a sus controllers | Carrera con UC-55; regresión CambiarEstadoUsuarioServiceIntegrationTest |

## Resultados reproducibles

Java 21, Maven y Testcontainers PostgreSQL `postgres:17.11-alpine` real.
Las tres suites nuevas usan `disabledWithoutDocker=true` según el encargo;
en este entorno Docker está disponible y **los tests nuevos se ejecutaron**.
B3 se aísla mediante rollback del test; rollback y concurrencia usan
transacciones confirmadas independientes. El fixture reutiliza PedidoFixture
y UC-03 sin modificarlos. Agrega un tercer Repartidor habilitado para permitir
dos reasignaciones concurrentes válidas, además del deshabilitado.

| Verificación | Resultado |
|---|---|
| `mvn -B -ntp test-compile` | BUILD SUCCESS |
| AsignarRepartoServiceIntegrationTest | 16/16 |
| AsignarRepartoConcurrenciaIntegrationTest | 2/2, incluidas ambas ordenaciones UC-43/UC-55 |
| AsignarRepartoHttpExternoTest | 10/10 |
| CargarPedidoServiceIntegrationTest, protegido | 18/18, sin cambios |
| CambiarEstadoUsuarioServiceIntegrationTest, protegido | 11/11, sin cambios |
| PrecondicionesDespliegueTest / AutenticacionHttpExternaTest | 26/26 + 11/11 |
| Total focalizado | 94/94, sin fallos, errores ni skips |

Workflow: `.github/workflows/i2-03-pedidos-http-focused.yml`.

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=AsignarRepartoServiceIntegrationTest,AsignarRepartoConcurrenciaIntegrationTest,AsignarRepartoHttpExternoTest,CargarPedidoServiceIntegrationTest,CambiarEstadoUsuarioServiceIntegrationTest,PrecondicionesDespliegueTest,AutenticacionHttpExternaTest test
```

## Bloqueo y aceptación

[Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) sigue
abierta. Entrega por PR draft para revisión humana. Sin merge, despliegue ni
habilitación del gate. Las pruebas técnicas no acreditan aceptación humana
BC-06 ni completan el mecanismo pendiente de rutas.
