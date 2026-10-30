# I1-01 — UC-18 Gestionar Asignaciones de Roles (implementación independiente)

**Fecha:** 29/10/2026. **Estado:** propuesta B3 en PR separada, **sin autorización de merge**.  
**CU:** UC-18 · `gestionarAsignacionesRoles`. **Control F07**.  
**Fuente G0:** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`.  
**RD posterior:** `RD-UC18-001`, `IC-UC18-MR5-001`, contrato REV-02 y DSS/DS parcheados en `leandrito369/ale-milanesas` commit **`3cf4b19078f02a374229012cbf8d278b4bb58196`** (PR #29).

## Contrato y autoridad

`gestionarAsignacionesRoles(idUsuario:Long, rolesAgregar:Set<String>[0..1], rolesQuitar:Set<String>[0..1])`, con `sesionAdministrador:UUID` como **contexto interno**, no parte del mensaje de dominio ni token de API.

| Regla | Comportamiento previsto |
|---|---|
| ADMINISTRADOR exclusivo de invocación | Sesión activa, no revocada, Usuario habilitado, clave no pendiente, Rol nominal ADMINISTRADOR. No basta UUID de cliente HTTP. |
| A1 quitar Roles válidos | Revocar asociación, auditar con literal físico `REVOCACION` por Rol real. |
| Agregar Roles | Insertar asociación y auditar `ASIGNACION` por Rol real, sin crear/modificar catálogo ROL. |
| A2 protección del último Rol/Administrador | Validación previa serializada y trigger de MR5 diferido; no autorizar pérdida del último ADMINISTRADOR habilitado. |
| A3 cambio inexistente | No tocar asociaciones, no emitir evento y no revocar sesión. |
| A4 cancelación/falla | `@Transactional`, todo-o-nada, eventos y Roles en la misma transacción. |
| A5 actor no autorizado | Rechazar antes de observar Usuario objetivo. |
| Exclusividad PROVEEDOR | Rechazar mezcla con Roles internos. |

## Decisiones de implementación y límites del contrato

- MR5 ya incluye `fn_mr5_validar_invariantes_usuarios()` como constraint trigger diferido, con `pg_advisory_xact_lock(60505,7)`, y `tg_mr5_revocar_sesiones_roles` para cualquier INSERT/UPDATE/DELETE de `USUARIO_ROL`. **En consecuencia, MR5 revoca las sesiones también cuando solo se agrega un Rol**. Es un efecto físico preexistente, que se mantiene y se prueba; no fue introducido por este PR.
- El comando toma `pg_advisory_xact_lock(9172001)` **antes** de `pg_advisory_xact_lock(60505,7)`, compatible con UC17. La primera cerradura serializa generación de `idEventoRol = MAX+1` usada en ambos servicios; la segunda permite evaluar F07 con visibilidad posterior al commit de un competidor. Una escritura SQL externa que ignore este protocolo requiere controles operativos adicionales.
- MR5 no incluye generador automático para `EVENTO_ASIGNACION_ROL.idEventoRol`. No se crea secuencia nueva ni se altera V1/V2.
- **Transiciones PROVEEDOR ↔ roles internos no implementadas**: UC-18 no recibe `idProveedor` y MR5 exige coherencia `USUARIO_PROVEEDOR`. Inventar alta/baja de ese vínculo fuera del contrato contradice G0-D05. El servicio acepta conservaciones/no-op de PROVEEDOR pero rechaza conversiones; si se desea habilitarlas, abrir RD/CR con semántica y pruebas específicas.
- El servicio **no expone ningún endpoint HTTP**, no verifica criptográficamente un token externo y no declara autenticación B4 terminada.

## Evidencia de pruebas requeridas

- Repartidor/Vendedor dentro de mismo Usuario: retiro de VENDEDOR válido, evento REVOCACION y sesión revocada.
- Asignación de REPARTIDOR, evento ASIGNACION; sesión revocada por trigger físico MR5.
- Repetición de Rol ya presente o retiro de Rol ausente: **sin evento ni revocación**.
- Rechazo de último Rol, último ADMINISTRADOR habilitado, mezcla PROVEEDOR, Rol inexistente, conjuntos contradictorios y Vendedor no autorizado.
- **Concurrencia F07 con dos administradores**: dos sesiones en hilos separados, intento de retirar ADMINISTRADOR en ambos; exactamente una transacción puede confirmar; se conserva un Administrador habilitado y un único evento REVOCACION.
- Todas las pruebas se ejecutan con PostgreSQL 17.11/Testcontainers y en regresión B1+B2 con Flyway, cotejo MR6. No contabilizar estas pruebas como BC06: las 18 de recorrido I1/I2/B8 siguen pendientes.

## Próximos trabajos en orden rector

UC-53 y UC-54 cuentan con RD pero requieren servicio de sesión restringida y validación/actualización de credenciales protegidas; UC-01 y su interfaz B4 requieren autenticación real. UC-55 y datos maestros I1-02 continúan según el backlog, **en PR separadas**. El cierre de G0 no se reabre y F04 permanece sujeto a CR.
