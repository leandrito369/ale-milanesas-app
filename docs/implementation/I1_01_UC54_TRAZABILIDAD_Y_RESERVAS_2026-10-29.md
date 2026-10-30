# I1-01 — UC-54 / RD-UC54-001 — Restablecer Acceso de Proveedor

**Fecha:** 29/10/2026 (ART) · **Estado:** implementación B3 propuesta en PR, pendiente de CI y revisión.  
**Fuente G0:** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`. **RD posterior aprobada:** `RD-UC54-001`, contrato y DSS/DS REV02 (doc commit `3cf4b19078f02a374229012cbf8d278b4bb58196`).  
**Nueva RD complementaria UC-53:** `RD-UC53-002`, doc commit `ad696cebafa3600b89df68782e61d28a6059fbed`. No se alteró MR5/F01.

## Contrato y operación

`restablecerAccesoUsuario(idUsuario: Long, identidadComercialVerificada: Boolean, credencialTemporal: String)`.

La llamada a `RestablecerAccesoUsuarioService` recibe adicionalmente `sesionAdministrador` como **contexto interno del servidor**, no argumento de la interfaz de dominio/cliente. El servicio exige sesión de Administrador vigente, Usuario HABILITADO, cambio de credencial no pendiente y Rol nominal ADMINISTRADOR por `AutorizacionSesionInterna`.

**A1 — Confirmación humana:** Administrador identifica mediante UC56 y **corrobora comercialmente los datos**, luego confirma `identidadComercialVerificada=true`. La bandera únicamente representa la acción administrativa; el servicio **no demuestra por sí mismo** que esa verificación humana haya ocurrido. No se crea servicio de identidad externo.

**A2 — Validación del sistema:** revalida en una transacción que el Usuario objetivo tiene **solo** Rol `PROVEEDOR`, existe `USUARIO_PROVEEDOR` con Proveedor comercial identificable y no existe otra cuenta HABILITADO vinculada al mismo Proveedor. Comprueba los invariantes físicos de MR5 sin inventar estados, Roles ni columnas. Se pueden restablecer credenciales de Usuarios DESHABILITADO sin habilitarlos automáticamente; se preservan id, estado, Roles y vínculo.

**Postcondición:** almacena exclusivamente hash BCrypt de la credencial temporal y fuerza `cambioCredencialPendiente=true`. El trigger MR5 revoca todas las sesiones anteriores por cambio de hash. La próxima autenticación quedará restringida a UC53 hasta sustituir la temporal. **No** se crea una sesión nueva ni se expone la temporal. Una credencial igual a la vigente se rechaza como endurecimiento técnico para evitar restablecimiento ineficaz.

**Concurrencia/atomicidad:** `@Transactional`, bloqueo `pg_advisory_xact_lock(9172001)` compartido con UC17/UC18 y `FOR UPDATE` de la cuenta objetivo. No se agregan migraciones. Otras aplicaciones que omitan el bloqueo no quedan protegidas por esa serialización en Java.

**Límite de UC56 para verificación comercial:** la consulta B3 UC56 exhibe `idProveedor` pero no muestra por sí sola `razonSocial/cuit`. La revisión humana deberá disponer de la ficha comercial correspondiente del Proveedor a través de un CU autorizado (por ejemplo UC37); **no afirmar que UC56 expone más campos de los implementados**, y no permitir el flujo UI/B4 hasta cerrar la proyección autorizada.

**Límites de seguridad:** sin endpoint HTTP B4, JWT, OTP ni conexión externa. El booleano de confirmación **no** es mecanismo de autenticación. La disponibilidad de UC54 por API queda bloqueada hasta seguridad HTTP y pantalla administrativa de verificación. La operación UC01 tampoco está implementada.

## Pruebas de la cátedra

Pruebas **unitarias** aisladas (`PoliticaRestablecimientoProveedorTest`): AAA, particiones verdadero/falso, Rol exclusivo, vínculo, duplicidad, nulos/blancos y límite 72/73 bytes BCrypt.

Pruebas de **integración** (`RestablecerAccesoUsuarioServiceIntegrationTest`): PostgreSQL 17.11/Testcontainers, Flyway MR5/F01 real, Administrador válido, actor Vendedor/sesión revocada, identidad no confirmada, Rol interno, mezcla de Roles, vínculo faltante, segunda cuenta activa, Usuario DESHABILITADO, conservación de vínculos/estado, nuevo hash y revocación de sesiones del Proveedor.

**Aceptación del usuario E2E:** NO EJECUTADA. Requiere UC01/B4, perfil administrativo con vista comercial autorizada, confirmación y nuevo ingreso; no existe interfaz implementada.

**BC-06:** 0/18 pruebas funcionales integrales de recorrido; la cobertura JaCoCo debe medirse por CI y no deducirse del número de tests.
