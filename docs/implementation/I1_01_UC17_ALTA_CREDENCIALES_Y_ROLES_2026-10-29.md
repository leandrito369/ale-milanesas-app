# I1-01 — UC-17 `crearUsuario` (incluye asignación UC-18)

**Fecha:** 29/10/2026. **Estado:** PR de implementación B3, sin endpoint B4.  
**Fuente original inmutable:** G0 `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`.  
**Acta de RD posteriores:** `leandrito369/ale-milanesas` `3cf4b19078f02a374229012cbf8d278b4bb58196`, [PR documental #29](https://github.com/leandrito369/ale-milanesas/pull/29).

## Trazabilidad

- CU-17 / operación `crearUsuario(nombreUsuario, credencialInicial, rolesIniciales, idProveedor[0..1])`.
- Contrato REV02 UC17 **PATCH aprobado** por RD-UC17-001; incluye asignación inicial UC18 y eventos **ASIGNACION** conforme RD-UC18-001/IC-UC18-MR5-001.
- DSS/DS REV02 de Seguridad, etiquetas parcheadas en PR #29. Modelo físico **MR5 (V1 Flyway) + F01 (V2 Flyway)**.
- Tablas existentes sin migraciones: `USUARIO`, `ROL`, `USUARIO_ROL`, `EVENTO_ASIGNACION_ROL`, `USUARIO_PROVEEDOR`, `PROVEEDOR`, `SESION_USUARIO`.

## Implementación

El servicio de aplicación `CrearUsuarioService` se invoca **internamente** y revalida sesión no expirada/revocada, Usuario habilitado, clave no pendiente y Rol nominal **ADMINISTRADOR** en la BD. Rechaza Roles vacíos/inexistentes, mezcla PROVEEDOR/internos, proveedor inactivo o con otra cuenta habilitada, nombre duplicado y credencial ausente. Al crear Usuario:
- **Interno:** `HABILITADO`, hash BCrypt de credencial asignada por Administrador, `cambioCredencialPendiente=false`.
- **PROVEEDOR:** temporal con hash BCrypt, `cambioCredencialPendiente=true`, vínculo comercial único en `USUARIO_PROVEEDOR`.
- Inclusión UC-18: una fila en `USUARIO_ROL` y evento en `EVENTO_ASIGNACION_ROL` con `tipoEvento='ASIGNACION'` por cada Rol.
- Una sola transacción Spring `@Transactional` para Usuario, Roles, auditoría y vínculo.

### Decisiones de implementación, sin cambio de negocio
- MR5 tiene PK `BIGINT` sin generador automático para Usuario/evento. Se utiliza bloqueo transaccional PostgreSQL `pg_advisory_xact_lock(9172001)` para serializar la asignación de máximos + 1 **dentro de los comandos I1 de la aplicación**. Este bloqueo **no protege escrituras manuales ni otras apps que no respeten el mismo protocolo**; antes de producción se requerirá revisar política de ID y seguridad concurrente al integrar UC18/UC55. No añade tablas ni secuencias.
- Hash BCrypt de costo 12 y límite técnico de 72 bytes de clave (evitar truncado silencioso). No existe aún una política funcional completa de complejidad de contraseñas; requisitos adicionales se aprobarán por RD/CR, sin reinterpretarlos ahora.
- No se crean cuentas bootstrap de desarrollo ni se publica una API. El parámetro UUID de sesión no es token criptográfico de usuario remoto; B4 debe resolver autenticación HTTP antes de exponer el comando.

## Pruebas de integración incluidas

1. Usuario interno: flag falso, hash real y evento ASIGNACION por cada Rol; sin vínculo Proveedor.
2. Usuario Proveedor exclusivo: flag verdadero, vinculación comercial, un evento y bloqueo de segunda cuenta habilitada.
3. Rechazos de Rol vacío, mezclado, inexistente o Proveedor inactivo; sin registros parciales.
4. Vendedor y sesión ausente no autorizados.
5. Nombre duplicado case-insensitive y sesión revocada bloquean cambios.

**No declara UC-18 como operación independiente implementada**, solo la inclusión inicial exigida por UC-17. UC01/53/54/55, API B4 y tests BC-06 permanecen pendientes.
