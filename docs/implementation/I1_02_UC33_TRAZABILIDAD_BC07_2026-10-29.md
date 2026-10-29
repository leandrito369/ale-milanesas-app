# I1-02 / UC-33 — Buscar Cliente con permisos y proyección BC-07

**Fecha:** 29/10/2026 · **Estado:** propuesta de B3 en PR apilada sobre la PR #4 de I1-01.  
**Fuente G0:** `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, acta `27738b089afdd2698cdae915f75c0e8089a6248a`.

### Contrato y trazabilidad

- **UC-33 `buscarCliente(idCliente, nombre, telefonoPrincipal, zona, estado)`**; contrato `docs/08-diagramas/revision/REV06B_CLIENTES_RECETAS_INVENTARIO/propuestos/CONTRATOS_REV06B_TRECE_CORREGIDOS.md`, §2.
- DSS/DS: `docs/08-diagramas/revision/REV06B1_CLIENTES_CATEGORIA/propuestos/DSS_REV06B1_CLIENTES_CATEGORIA_UC02_15_16_32_33_38_57_CORREGIDO.drawio.xml` y DS equivalente.
- **RD-RP007-002**: ADMINISTRADOR y VENDEDOR **directo/contextual**; otros roles no están habilitados para UC-33. Cliente ONLINE no adquiere Rol de Usuario.
- **BC07**: id, nombre/identidad, teléfono, domicilio/zona y los campos funcionales de búsqueda UC-33; **nunca** saldo, cobros ni historial financiero. La salida se deriva de columnas físicas de `CLIENTE`, sin inventar `nombreNormalizado` ni almacenar un domicilio agregado.
- MR5/F01: `CLIENTE` existente; solo `SELECT` sin migraciones, efectos de saldo, historial ni pedidos.

### Implementación de alcance limitado

`BuscarClienteService` reutiliza `AutorizacionSesionInterna` (servicio transversal de sesión/rol introducido en esta PR) y aplica filtros SQL parametrizados, con escape de comodines de LIKE y validación de estado. Exige Usuario HABILITADO, sesión no revocada/no expirada, `cambioCredencialPendiente=false` y Rol nominal VENDEDOR/ADMINISTRADOR. La proyección `ClienteConsulta` no tiene atributos de saldo ni pagos. No se habilita endpoint HTTP, menú ni operaciones de mutación mientras B4 no complete verificación criptográfica de credenciales/token.

**Pruebas planeadas en PostgreSQL 17.11 efímero:** vendedor autorizado, administrador ve inactivo, producción rechazada, sesión revocada y clave pendiente rechazadas, filtros inválidos, campos financieros ausentes.

**No se implementan UC02 `registrarCliente`, UC32 `modificarCliente` ni UC38 `cambiarEstadoCliente` en este PR.** Su tratamiento requiere postcondiciones de duplicidad, OTP, historial y transacciones de cambio. Tampoco se afirma que UC56/UC33 ya tengan interfaz o API. BC06 continúa 0/18; F04 sigue pendiente de CR.

**Dependencia:** PR #4 debe revisarse y fusionarse antes de retargetear esta PR a `main`.
