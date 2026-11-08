# I2-02 — UC-34 buscarPedido: B3 y GET HTTP

Fecha: 08/11/2026. Base `main`: `f2ae45d` (merge de I2-03 / PR #77).

## Alcance y fuentes

`BuscarPedidoService.buscarPedido(UUID, CriterioBusquedaPedido)` y
`GET /api/v1/pedidos` implementan la búsqueda de resúmenes de Pedidos, sin
modificaciones de estado. ADMINISTRADOR y VENDEDOR tienen acceso directo;
REPARTIDOR consulta exclusivamente Pedidos con asignación vigente a su Usuario.
Producción contextual queda rechazada hasta que exista el contexto de UC-28.

Fuentes cotejadas en `leandrito369/ale-milanesas` antes de programar:

- `docs/08-diagramas/consolidacion-b0/CONTRATOS_REV04A_CORREGIDOS_B0_ACTUALIZADO.md`, §1: firma, A1–A3 y postcondiciones.
- `docs/08-diagramas/revision/REV04_PEDIDOS_ENTREGAS/propuestos/DS_REV04A_CORREGIDO.drawio.xml` y `DSS_REV04A_CORREGIDO.drawio.xml`.
- Ficha Real UC-34 en `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`.
- `docs/01-gobierno/b0/B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv`, UC-34.
- `docs/01-gobierno/b0/BC07_PROYECCIONES_MINIMAS_RATIFICADAS_2026-10-29.csv`, filas UC-34 ADMIN/VENDEDOR/PRODUCCION/REPARTIDOR, líneas 4–7.
- `docs/01-gobierno/RD_RP007_002_PERMISOS_BUSQUEDAS_2026-10-27.md`: permisos contextuales y mínimo privilegio.

Se conserva [source-lock](../traceability/source-lock.md), G0 aprobado:
`source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` y
`governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`.

## Diagnóstico previo

No había servicio ni endpoint buscarPedido. V1 ya contiene las columnas del
resumen en PEDIDO y el ámbito en ASIGNACION_REPARTO. No se agregan tablas,
columnas, índices ni migraciones. AutorizacionSesionInterna solo proporciona
`exigirRoles`; no existe un método público para obtener roles. Se reutiliza
su autorización sin modificarlo y se consulta el ámbito efectivo en SQL.

BC07 distingue proyecciones por rol, no autoriza devolver todos los campos de
PEDIDO a cualquier actor admitido. Su fila ADMIN contiene metadatos operativos;
VENDEDOR incluye condiciones comerciales; REPARTIDOR excluye deuda, costos,
márgenes y datos administrativos. Por eso el DTO único de ocho campos del
encargo se adapta a tres proyecciones. La categoría EN_MORA no se expone al
Repartidor como efecto lateral de permitirle encontrar un Pedido.

## Decisiones de implementación

| ID | Decisión | Respaldo / justificación |
|---|---|---|
| D1 | GET `/api/v1/pedidos`, cinco query params opcionales | REV04A §1 no fija rutas; recurso raíz consistente con UC-36. |
| D2 | Parámetro omitido/null no filtra; criterio B3 null equivale a todos omitidos | Firma `[0..1]`; el ámbito del actor siempre se aplica. |
| D3 | Sin coincidencias autorizadas → 200 `[]` | A1. Un ID explícito fuera de ámbito de REPARTIDOR es A3 y se deniega. |
| D4 | REPARTIDOR usa EXISTS de ASIGNACION_REPARTO vigente a su Usuario | RD-RP007-002 / BC07. No basta una asociación histórica ni un ID suministrado por HTTP. |
| D5 | RESPONSABLE_PRODUCCION sin otro rol admitido → 403 | UC-28 no existe en este corte; no se inventa un contexto de preparación. |
| D6 | Proyecciones distintas por rol | BC07 líneas 4–7 y mínimo privilegio; ver tabla siguiente. |
| D7 | `Cache-Control: no-store` en éxitos y errores | Consistencia con GETs existentes y respuestas con ámbito privado. |

## Proyección BC07

Todos los resúmenes contienen: `idPedido`, `idCliente`, `fechaReparto`, `estado`
y `canalOrigen`. Cliente se representa únicamente por su ID; no se exponen
nombre, teléfono, domicilio, deuda, cuenta corriente ni datos de contacto.

| Rol efectivo | Tipo B3 | Campos adicionales a los cinco comunes | Total |
|---|---|---|---|
| ADMINISTRADOR | PedidoAdministrativo | fechaHoraCarga | 6 |
| VENDEDOR | PedidoComercial | categoriaComercialAplicada, totalOriginal, fechaHoraCarga | 8 |
| REPARTIDOR contextual | PedidoRepartidor | Ninguno | 5 |

`PedidoResumen` es una interfaz sealed con estos tres records, en lugar de un
record común con información sensible o campos nulos. Se mantiene la firma
`List<PedidoResumen>` y el JSON es un array directo, sin discriminador de tipo.
Los tests HTTP comprueban el conjunto **exacto** de claves por rol, además de
las filas visibles. El Repartidor no recibe categoría, total ni fecha de carga;
ADMIN tampoco recibe condiciones comerciales que su proyección mínima no pide.
Las condiciones de VENDEDOR proceden del snapshot de PEDIDO, no de reevaluar
la categoría actual del Cliente ni recalcular precios.

Este endpoint devuelve resúmenes para identificación; no incorpora una vista
de detalle, Entrega, preparación ni un endpoint UC-04. Los campos de dirección
y detalles permitidos en otros contextos no se incorporan a este resumen.

## RBAC contextual y consistencia de la consulta

SeguridadHttpInterna acepta GET solamente para ADMINISTRADOR, VENDEDOR y
REPARTIDOR mediante `hasAnyAuthority`. B3 vuelve a comprobar la sesión con
AutorizacionSesionInterna antes de validar criterios o devolver información.

La sentencia de datos vuelve a validar Usuario habilitado, sesión no revocada,
fechas de inicio/expiración, máximo de ocho horas y credencial sin cambio
pendiente. En esa misma sentencia se obtienen los roles actuales, se filtran
las asignaciones y se seleccionan las columnas permitidas. Las condiciones
comerciales se proyectan mediante CASE únicamente si existe rol VENDEDOR.
Así, ámbito y proyección utilizan el mismo snapshot PostgreSQL; no dependen
de roles o identidad enviados por query params ni de una lista cacheada.

El permiso directo ADMIN/VENDEDOR prevalece sobre el ámbito REPARTIDOR cuando
el Usuario tiene varios roles admitidos; se prueba ADMIN+REPARTIDOR. VENDEDOR
habilita su proyección comercial. Producción sola y PROVEEDOR reciben 403.

- REPARTIDOR sin filtro de ID: se devuelve exclusivamente lo asignado vigente;
  si no tiene coincidencias, 200 `[]`.
- REPARTIDOR que indica un `idPedido` ajeno, con asignación cerrada o inexistente:
  403 `OPERACION_PROHIBIDA`, conforme a A3. No se revela si el ID existe.
- Si el ID sí pertenece al ámbito pero otro criterio no coincide, se devuelve
  200 `[]`, conforme a A1. La comprobación de ámbito es independiente de los
  otros filtros para no confundir una búsqueda vacía con una denegación.

No se añade un filtro PENDIENTE implícito que el contrato no exige. Se respeta
`fechaHoraFin IS NULL` para vigencia y los criterios de estado indicados.
No hay locks de escritura, escrituras de auditoría ni reevaluación UC-15/16.
`@Transactional(readOnly=true)` encierra el caso de uso.

## Criterios y HTTP

Opcionales: `idPedido`, `idCliente`, `fechaReparto`, `estado`, `canalOrigen`.
IDs positivos representables como Long; fecha ISO LocalDate; estado
PENDIENTE/ENTREGADO/CANCELADO; canal INTERNO/ONLINE. Las fechas históricas son
válidas para buscar. Se rechazan valores vacíos, parámetros repetidos,
parámetros desconocidos, formatos inválidos y enums ajenos. Ningún valor
recibido se concatena en SQL: las cláusulas son fijas y los valores se bindean.
Orden estable: `fechaHoraCarga DESC, idPedido DESC`, sin paginación añadida.

Respuesta 200: array de resúmenes o `[]`. Advice acotado al controller:
400 SOLICITUD_INVALIDA, 403 OPERACION_PROHIBIDA, 503 PERSISTENCIA_NO_DISPONIBLE;
Bearer ausente/cookie sola → 401 SESION_INVALIDA. Se verifica no-store en todos.
No hay gate, `@Profile` ni `@ConditionalOnProperty`; PrecondicionesDespliegue y
application.yml quedan intactos. Los gates de escritura no condicionan el GET.

## Matriz CU → servicio → DB → API/UI → test

| CU / operación | Servicio | DB | API / UI | Pruebas |
|---|---|---|---|---|
| UC-34 buscarPedido | BuscarPedidoService | SELECT PEDIDO, ASIGNACION_REPARTO, SESION_USUARIO, USUARIO, USUARIO_ROL | GET /api/v1/pedidos; sin UI nueva | BuscarPedidoServiceIntegrationTest (15), BuscarPedidoHttpExternoTest (10) |
| Regresión UC-03 / UC-43 | Servicios existentes intactos | Persistencia sin cambios | Controllers existentes intactos | CargarPedidoServiceIntegrationTest (18), AsignarRepartoServiceIntegrationTest (16) |

## Resultados reproducibles

Java 21, Maven y PostgreSQL real `postgres:17.11-alpine` mediante Testcontainers.
No se permiten skips por ausencia de Docker en las suites nuevas.

Fixture nuevo: reutiliza PedidoFixture, AsignacionRepartoFixture, UC-03 y UC-43
sin modificarlos. Contiene dos clientes, los tres estados, ambos canales, una
asignación vigente de A y otra histórica cerrada de A con vigente de B. El caso
ONLINE se prepara mediante SQL sintético exclusivamente en el fixture; no
implementa UC-21 ni envía OTP. Se igualan fechas de carga para probar el
orden secundario por ID. HTTP usa datos confirmados; B3 se aísla por rollback.

El test originalmente denominado rollbackNoAplica verifica realmente que las
consultas no modifican el estado: compara representaciones canónicas de las
tablas antes/después y mantiene una categoría actual de Cliente distinta del
snapshot comercial para detectar una reevaluación indebida. Incluye Pedido,
detalles, asignaciones, Cliente, sesiones, Cuenta y Caja. La prueba HTTP de 503
renombra temporalmente PEDIDO en su contenedor aislado y restaura el nombre;
no modifica migraciones ni una base externa.

| Verificación | Resultado |
|---|---|
| `mvn -B -ntp test-compile` | BUILD SUCCESS |
| BuscarPedidoServiceIntegrationTest | 15/15 (los 15 enumerados en el encargo) |
| BuscarPedidoHttpExternoTest | 10/10 |
| Regresión CargarPedidoServiceIntegrationTest | 18/18, sin cambios |
| Regresión AsignarRepartoServiceIntegrationTest | 16/16, sin cambios |
| AutenticacionHttpExternaTest | 11/11, sin cambios |
| Total focalizado | 70/70, cero fallos, errores o skips |

Workflow: `.github/workflows/i2-02-pedidos-busqueda-http-focused.yml`.

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=BuscarPedidoServiceIntegrationTest,BuscarPedidoHttpExternoTest,CargarPedidoServiceIntegrationTest,AsignarRepartoServiceIntegrationTest,AutenticacionHttpExternaTest test
```

## Bloqueo y aceptación

[Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) sigue
abierta. PR draft para revisión humana; sin merge ni despliegue. Este corte no
habilita contextos UC-28 de Producción ni acredita aceptación humana BC-06.
