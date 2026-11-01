# B4-03-01 — UC-02 Registrar Cliente, adaptador HTTP bajo gate (01/11/2026)

**WBS:** B4-03-01 / I1-02. **Issue:** [#33](https://github.com/leandrito369/ale-milanesas-app/issues/33). **Estado:** propuesta para revisión, NO habilitada ni desplegada en producción.  
**Línea base:** `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, `governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`. **No alterados.** MR5/F01 sin cambios físicos.

## 1. Fuentes revisadas y decisión de alcance

- [Contrato `registrarCliente` UC02 REV06B, sección 1](https://github.com/leandrito369/ale-milanesas/blob/1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e/docs/08-diagramas/revision/REV06B_CLIENTES_RECETAS_INVENTARIO/propuestos/CONTRATOS_REV06B_TRECE_CORREGIDOS.md) y [DSS/DS REV06B1 UC02](https://github.com/leandrito369/ale-milanesas/tree/main/docs/08-diagramas/revision/REV06B1_CLIENTES_CATEGORIA/propuestos).
- [I1-02 B3](I1_02_CLIENTES_UC02_32_38_2026-10-30.md): `GestionClientesService.registrarCliente(UUID,DatosCliente)`, transacción, doble revalidación RBAC, `pg_advisory_xact_lock(3202001)` para alocación y unicidad por nombre+teléfono, no SQL F04.
- Matriz RBAC del G0: UC02, `registrarCliente`, Administración y Vendedor `D`, otros canales `NR`. BC07 tiene **15 proyecciones ratificadas**, pero no define una proyección con datos de tercero para UC02; restringimos output a confirmación propia y **no** emitimos IDs de coincidencias parciales.
- Contrato lógico `nombre,telefonoPrincipal,direccion:DireccionDTO,zona?,diaHabitualReparto?` se adapta a `CLIENTE.nombres/apellidos` y columnas domicilio MR5 ya vigentes. **No se inventan columnas** ni se altera la normalización B3.

## 2. Diff de implementación

- `RegistroClienteHttpController` con `POST /api/v1/clientes` en JSON. Acepta exclusivamente `nombres,apellidos,telefonoPrincipal,calle,numeroPuerta,piso,unidadFuncional,localidad,provincia,referenciaDomicilio,zona,diaHabitualReparto`. Rechaza tipos indebidos y claves desconocidas (incluido `idUsuario,idSesionUsuario,idCliente,roles,estado,categoriaComercial`); no convierte IDs remitidos por el actor en autoridad.
- Spring Security: autorización de ruta **solo si** `ale.http.writes.clientes-uc02-enabled=true` **y** Bearer autenticado Admin/Vendedor; B3 revalida permisos y sesión en transacción. Flag **false por defecto**. El controlador además requiere perfil **`!prod`**. `PrecondicionesDespliegue` rechaza arrancar `prod` con el flag verdadero. Todos los demás POST comerciales siguen `denyAll`.
- Alta `201 Created` sin `Location` (no existe GET individual contratado): `idCliente,estado=ACTIVO,categoriaComercial=CUMPLIDOR,advertenciaCoincidenciaParcial:boolean`. No devuelve IDs ni PII de coincidencias.
- Fallos: 400 `SOLICITUD_INVALIDA`, 409 `CLIENTE_DUPLICADO` para activo y `CLIENTE_INACTIVO_REQUIERE_ADMINISTRADOR` para A3 inactivo (sin IDs/PII de terceros), 401 Bearer ausente/inválido, 403 rol o flag rechazado, 503 `PERSISTENCIA_NO_DISPONIBLE`. `Cache-Control:no-store`, sin SQL ni teléfonos en errores.
- No habilita UC32, UC38, gestión de stock, precios, pedidos ni operaciones de Cobro/BC06. No agrega SQL, tabla ni migración.

## 3. Evidencia de pruebas (no declarar PASS antes de CI)

`RegistroClienteHttpExternoTest` usa Spring Boot `RANDOM_PORT` y PostgreSQL Testcontainers 17.11, sin transacción de test que oculte escrituras. El flag se activa **solo por anotación de test**. Escenarios:

1. Alta con Administrador: 201, estado/categoría, no-store, fila física y salida mínima.
2. Alta con Vendedor: 201.
3. Ausencia de Bearer, Repartidor, sesión con contraseña pendiente, cookie sin Bearer y POST ajeno: rechazo sin crear filas.
4. Payload con campo no permitido, coerción decimal inválida, teléfono inválido: 400 sin filas.
5. Duplicado exacto activo e inactivo: 409, exactamente una fila.
6. Teléfono compartido con distinto nombre: 201, advertencia booleana sin IDs de tercero.
7. Dos POST concurrentes idénticos: exactamente un 201 y un 409, fila única.
8. Trigger sintético en PostgreSQL provoca fallo de inserción: 503 sin fila creada.
9. Test de perfil `prod`: rechaza activar flag aun con HTTPS, Nginx y singleton configurados.
10. B4-02C existente, sin bandera: mantiene 403 sobre `POST /api/v1/clientes`.

**Exigencia CI:** B1, B2, SonarCloud del SHA final; diferenciar resultados HTTP real de MockMvc. Nada aquí certifica TLS/certificado, firewall, identidad humana UC54 ni BC06 18/18.

## 4. Gate y estado para entrega

**La implementación permanece en Draft**, con flag por defecto desactivado incluso fuera de producción y arranque prod prohibido con flag habilitado. **No fusionar ni habilitar esta operación** sin revisión técnica, prueba de ausencia de acceso al código de escritura desde perfiles externos, autorización del responsable para el caso UC02 y cierre de aceptación de despliegue externo de [#28](https://github.com/leandrito369/ale-milanesas-app/issues/28) si se quisiera publicar. B4-03 podrá avanzar en trabajo aislado, pero no usar los resultados de test como autorización comercial.

**Matriz de trazabilidad del corte:** UC02 → `GestionClientesService.registrarCliente` → `CLIENTE` MR5/F01 + sesión/roles MR5 → `POST /api/v1/clientes` (feature flag, perfil no-prod) → `RegistroClienteHttpExternoTest` y `PrecondicionesDespliegueTest`. Los CU de consulta y otros servicios conservan sus permisos y tests.
