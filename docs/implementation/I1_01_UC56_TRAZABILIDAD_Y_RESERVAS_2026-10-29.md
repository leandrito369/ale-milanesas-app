# I1-01 — Primera operación de acceso: UC-56 buscarUsuario

**Repositorio:** implementación `ale-milanesas-app`. **Fecha:** 29/10/2026.  
**Estado:** propuesta de código para revisión, pruebas CI pendientes.

## Trazabilidad exacta G0

- CU: **UC-56 — Buscar Usuario**, operación `buscarUsuario`.
- Contrato: `docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/contratos/CONTRATO_UC56_REV02.md`.
- DSS: `docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/DSS_REV02_SEGURIDAD_CORREGIDO.drawio.xml`.
- DS: `docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/DS_REV02_SEGURIDAD_CORREGIDO.drawio.xml`.
- RD y RBAC: rol **ADMINISTRADOR (D)**; demás roles y canales `NR`, sin herencia.
- Persistencia MR5: `USUARIO`, `USUARIO_ROL`, `USUARIO_PROVEEDOR`, `ROL`, `SESION_USUARIO`; cuenta de pedidos `PEDIDO` + `ASIGNACION_REPARTO`. **Sin migraciones nuevas**.
- Commit G0 fijado: `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`; acta `27738b089afdd2698cdae915f75c0e8089a6248a`.

## Resultado de B3, no B4

Se implementa un **servicio de aplicación de solo lectura**, sin exponer endpoint HTTP ni autenticación pública. `buscarUsuario(idSesionUsuario, criterios)` verifica en PostgreSQL una sesión activa, no revocada, Usuario `HABILITADO`, con credencial no pendiente y rol nominal `ADMINISTRADOR`; rechaza el resto. Los criterios de nombre/estado/rol son parámetros SQL; se escapan comodines de búsqueda. La salida expone solo los campos del contrato: identidad de Usuario, estado, indicador de clave pendiente, lista de Roles, vinculación de Proveedor si existe y cantidad de Pedidos pendientes vigentes del Repartidor; **nunca credenciales ni tokens**.

**Límite de seguridad explícito:** recibir un identificador UUID no autentica por sí solo a un cliente remoto. El servicio permanece **inaccesible por API** hasta que B4 implemente validación criptográfica del token/sesión, CSRF y autorización a nivel servidor. La condición de actor probada ahora corresponde a un contexto interno ficticio de pruebas, no a login funcional.

### Reservas G0-D05 identificadas

- **I1-RP-01 / UC-17:** la política inicial de `cambioCredencialPendiente` de Usuario interno **no está determinada** en la ficha Real/contrato; prohibido imponer `true` general sin RD.
- **I1-RP-02 / UC-18:** contrato propone `EventoAsignacionRol.tipoEvento = ALTA/BAJA`, mientras MR5 restringe `EVENTO_ASIGNACION_ROL.tipoEvento` a `ASIGNACION/REVOCACION`. Requiere RD/corrección de contrato o CR de DB; **no** crear tablas/valores alternativos por inferencia.
- **I1-RP-03 / UC-53:** discrepancia sobre `credencialActual` obligatoria entre contrato propuesto y ficha Real; cotejo/decisión previa a implementar el flujo.
- **I1-RP-04 / UC-54:** verificación de identidad comercial del Proveedor requiere procedimiento controlado no inventado.
- **I1-RP-05 / UC-01:** B4 debe definir token, caducidad y almacenamiento seguro de sesión sin reinterpretar columnas del MR5; ninguna cuenta bootstrap debe venir con contraseña de desarrollo habilitada.

**No se declaran implementadas UC01/17/18/53/54/55**, ni clientes/productos aún. La siguiente PR I1-02 solo avanzará tras revisar sus contratos y BC07. Se mantiene abierto el CR de F04, así como BC06 0/18.
