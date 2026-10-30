# I1-01 / UC-55 — cambiarEstadoUsuario (B3, 30/10/2026)

**Estado:** Implementación propuesta para revisión independiente; sin HTTP/B4 ni pruebas E2E. La PR #14 (UC29) no se fusionó ni se modificó.

## Fuente y precedencia

- Fuente canónica G0: `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, `CONTRATO_UC55_REV02.md` (UC55 incluye UC56), RD-RP009-001, F07, esquema MR5 + F01 y permisos ADMINISTRADOR ratificados. Revisar también DS/DSS REV02 al inspeccionar la PR.
- [Issue #15](https://github.com/leandrito369/ale-milanesas-app/issues/15) — seguimiento de I1-01; [plan rector B0 G0-D03](https://github.com/leandrito369/ale-milanesas/blob/1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e/docs/01-gobierno/b0/B0_BACKLOG_I0_I1_PRIMER_VERTICAL_2026-10-29.md).

## Alcance implementado

Servicio interno `CambiarEstadoUsuarioService.cambiarEstadoUsuario(sesionAdministrador, idUsuario, nuevoEstado)`:

1. Verificar autorización de Administrador con sesión PostgreSQL vigente y sin cambio de contraseña pendiente, antes de consultar Usuario objetivo; validar únicamente `HABILITADO` y `DESHABILITADO`.
2. Adquirir cerraduras transaccionales existentes de identidad `pg_advisory_xact_lock(9172001)` y F07 `pg_advisory_xact_lock(60505,7)`, en ese orden; revalidar rol y sesión después de esperar, bloquear Usuario objetivo `FOR UPDATE`.
3. Si estado idéntico, devolver `huboCambio=false` sin disparar UPDATE ni generar revocación artificial.
4. Para DESHABILITADO: impedir baja del último Admin; impedir baja de un Usuario con rol REPARTIDOR y uno o más Pedidos `PENDIENTE` con `ASIGNACION_REPARTO.fechaHoraFin IS NULL`. Entregar IDs de los Pedidos a reasignar en excepción específica, sin realizar UC43 automáticamente.
5. Para HABILITADO de Usuario PROVEEDOR: verificar asociación `USUARIO_PROVEEDOR` y rechazar si el mismo Proveedor tiene otra cuenta habilitada; conservar Roles y asociaciones.
6. Grabar `estado` y `fechaHoraDeshabilitacion` (marca al deshabilitar, `NULL` al habilitar) atómicamente. El trigger MR5 `tg_mr5_revocar_sesiones_usuario` ejecuta la revocación de sesiones al deshabilitar; la aplicación no duplica ese UPDATE.

**Sin migraciones ni estado nuevo.** Se conserva rol PROVEEDOR exclusivo, último ADMINISTRADOR y 1+ Roles por Usuario mediante constraints diferidos F07, incluso al COMMIT.

## Pruebas propuestas y criterio de atribución

`CambiarEstadoUsuarioServiceIntegrationTest`: 11 pruebas con PostgreSQL Testcontainers/MR5 para baja, alta, no-op, RBAC, sesión invalidada, último Admin, dos Admin, Proveedor duplicado/alternativo, Repartidor con Pedido pendiente/Asignación cerrada, parámetros inválidos.

`CambiarEstadoUsuarioConcurrenteIntegrationTest`: carrera real de dos sesiones administrativas que intentan deshabilitarse mutuamente, sin `@Transactional` de test; exactamente una transacción debe confirmar, quedando **al menos un Administrador habilitado** y su contraparte con sesión revocada por trigger.

La batería anterior `UC17/18/53/54` se conserva y debe pasar en B1 y B2 sobre el SHA final.

## Limitaciones explícitas

- **B3 solamente**: el UUID de `SESION_USUARIO` no es un token criptográfico de cliente remoto ni autentica solicitudes HTTP. La capa B4 se abordará **después de finalizar I1**, sin acreditar E2E ahora.
- **Concurrencia de UC43 futura**: la comprobación de Pedidos pendientes se hace bajo locks de identidad y usuario; cuando UC43 se implemente, debe acordar el protocolo de bloqueo de Usuario Repartidor (o una invariancia SQL equivalente) antes de insertar/reasignar `ASIGNACION_REPARTO` para cerrar la carrera con UC55. No se afirma protección frente a escritores externos arbitrarios.
- El estado sin cambio no revoca sesiones, no altera marcas, ni genera eventos ficticios. Habilitar no reabre sesiones previamente revocadas.
- Los controles de UC55 no autorizan ni modifican pedidos, no realizan reasignación automática y no cambian MR5/F01.
- Los cuatro E2E de UC54 están **documentados pero no acreditados**. BC06 sigue **0/18 E2E ejecutados** hasta contar con B4 e integraciones; los éxitos B3 no se suman a ellos.
- Integración a `main` exige CI B1+B2 verde para el mismo SHA, revisión humana de diff y del protocolo UC43, sin merge automático.

## Continuidad

Cerrar el incremento I1-01/B3 y retomar I1-02 (UC02/32/38), I1-03, I1-04, I1-05, I1-06 según G0-D03. Evitar adelantar I2/I3 en nombre de un verde aislado. Mantener `CR-B0-F04-001` sin implementación física hasta su resolución formal.
