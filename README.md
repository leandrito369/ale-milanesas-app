# ale-milanesas-app

**Sistema de Gestión para Ale Milanesas** — Implementación (UNaM-FCEQyN).

## Estado del proyecto

- **G0 aprobado documentalmente** el 29/10/2026 en el [repositorio de análisis](https://github.com/leandrito369/ale-milanesas).
- **B1/B2 integrados:** monolito modular Java 21 / Spring Boot 4.1.2, PostgreSQL 17.11, Flyway V1 MR5 + V2 F01 y pruebas Testcontainers. No equivale a certificación productiva.
- **B3/B4 incrementales:** autenticación UC01/53/54 y consultas autorizadas; PR #39 integrada con consulta comercial ADMIN contextual UC56/37. UC02/UC32 HTTP experimentales permanecen apagadas y excluidas de `prod`. Consultar trazabilidad por operación; no se declara completo I1 ni el circuito Pedido/Cobro.
- **Aceptación B8/B9:** [paquete, entorno y responsables](docs/implementation/B9_01_ENTORNO_ACEPTACION_UC54_2026-11-01.md). CI B1 publica JAR/configuración/actas con SHA y checksums. Host/FQDN/certificado/operadores pendientes; UC54 humana **0/4**, infraestructura **NO CERTIFICADA**.
- **BC06:** 18 escenarios funcionales pendientes, 0 ejecutados. **CR-B0-F04-001** pendiente de diseño físico.

## Cómo arrancar y verificar

Ver [guía reproducible B1](docs/implementation/B1_ENTORNO_Y_PRUEBAS.md). Requisitos: JDK 21, Maven 3.9+, Docker.

```bash
cp .env.example .env
# Cambiar ALE_DB_PASSWORD por una contraseña local.
docker compose up -d postgres
set -a; . ./.env; set +a
mvn -B -ntp clean verify
mvn -B -ntp spring-boot:run
```

Una vez iniciado: `http://localhost:8080/` (informativo) y `/actuator/health` (diagnóstico). `/api/pedidos` debe ser denegado. Docker es necesario para los tests de integración.

## Gobierno y agentes

- [Source-lock G0](docs/traceability/source-lock.md): commit de fuentes `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e` y acta `27738b089afdd2698cdae915f75c0e8089a6248a`.
- [AGENTS.md](AGENTS.md): reglas obligatorias de contratos, permisos y pruebas para OpenCode, Antigravity u otros.
- [ADR-IMP-001](docs/architecture/ADR-IMP-001_HERRAMIENTAS_B1.md): selección de versiones y arquitectura del scaffold.
- [Límites de migraciones](docs/implementation/BASELINE_B2_PENDIENTE.md): no agregar DDL sin cotejo formal B2.

**La documentación de requisitos, CU, DSS/DS, RD/RP/CR y modelo conceptual/relacional canónico vive exclusivamente en `ale-milanesas`.** Los cambios funcionales deben aprobarse allí antes de propagarse a esta aplicación.

## B2 — Registro histórico de revisión (29/10/2026)

Las migraciones `V1__baseline_mr5.sql` y `V2__f01_cobro_estados.sql` fueron extraídas de G0 con cambios mecánicos limitados a delimitadores transaccionales y directiva psql y ya están integradas. `spring.flyway.enabled=true` en `main`. [ADR-IMP-002](docs/architecture/ADR-IMP-002_BASELINE_MR5_F01.md) · [ensayo y límites](docs/implementation/B2_MIGRACIONES_MR5_F01.md). El esquema de aplicación es `ale_gestion` y la metadata de Flyway **no** se cuenta dentro de las 60 tablas de negocio. Se conserva el gate B2 de equivalencia triple MR6/F01; BC06 sigue pendiente de E2E originales.

## Propuesta B4-02D — Hardening operativo y gate anterior a B4-03

**Integrado en `main`:** cadena [PR #27/#29/#30/#31/#32](https://github.com/leandrito369/ale-milanesas-app/pull/32). El gate de infraestructura real y aceptación humana continúa abierto en #28. No habilita escrituras HTTP comerciales.

El perfil `prod` acepta exclusivamente un ingreso Nginx TLS en el mismo host, backend Java vinculado a `127.0.0.1`, origen HTTPS `ALE_PUBLIC_ORIGIN` obligatorio y un lock de sesión PostgreSQL que impide iniciar dos instancias simultáneas. Un filtro falla cerrado cuando se pierde la conexión de ese lock. Nginx aplica tasa compartida por IP a POST de login; dentro de la JVM se conserva el límite por cuenta.

Para ensayar el perímetro con Docker y certificado autofirmado **exclusivamente de CI**, ejecutar:

```bash
bash scripts/security/verify_perimeter.sh
```

El procedimiento de despliegue, límites del control, matriz de aceptación y las dependencias reales de BC06 se detallan en [B4-02D: gate operativo y aceptación](docs/implementation/B4_02D_GATE_OPERATIVO_Y_ACEPTACION_2026-11-01.md). **Una prueba TLS con certificado efímero no acredita HTTPS público:** falta validación real del certificado, dominio, firewall, canal comercial UC54 y revisión humana. No ejecutar pruebas con contraseñas/productos/pagos reales.

## B4-03-01 — UC02 Registrar Cliente (solo corte de prueba)

[PR #36](https://github.com/leandrito369/ale-milanesas-app/pull/36), Draft: incorpora un adaptador HTTP de **desarrollo controlado** para `POST /api/v1/clientes`. El flag `ale.http.writes.clientes-uc02-enabled` es **false por defecto**; además el controlador no se carga con `prod` y el validador de arranque de producción rechaza el flag activo. En consecuencia, **ninguna escritura comercial queda habilitada** al crear esta PR. El contrato, las pruebas HTTP reales y la matriz CU→B3→MR5→API→test figuran en [B4-03-01 UC02](docs/implementation/B4_03_01_UC02_ALTA_CLIENTE_HTTP_GATED_2026-11-01.md). No activar el flag fuera de las pruebas hasta revisión y aprobación expresa por operación; [Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) conserva las aceptaciones externas pendientes.

## B4-03-02 — UC32 Modificar Cliente (sólo test, bajo gate)

[PR B4-03-02](https://github.com/leandrito369/ale-milanesas-app/issues/37) propone `PUT /api/v1/clientes/{idCliente}` exclusivo de Vendedor, reutilizando B3. La nueva bandera `ale.http.writes.clientes-uc32-enabled` permanece **false por defecto**, el controlador es `!prod` y `PrecondicionesDespliegue` rechaza el perfil prod cuando se la activa. No autoriza UC38 ni otras mutaciones. Las verificaciones de OTP, no-op, concurrencia, rollback y permisos están especificadas en [B4-03-02](docs/implementation/B4_03_02_UC32_MODIFICAR_CLIENTE_HTTP_GATED_2026-11-01.md). El despliegue requiere el gate externo de [#28](https://github.com/leandrito369/ale-milanesas-app/issues/28).

**B4-03-03 / UC38 (Issue [#41](https://github.com/leandrito369/ale-milanesas-app/issues/41)):** `PATCH /api/v1/clientes/{idCliente}/estado` para `ACTIVO`/`INACTIVO`, **solo ADMINISTRADOR**, usa servicio B3 e historial MR5. Su gate propio `ale.http.writes.clientes-uc38-enabled=false` arranca apagado, controlador `@Profile("!prod")`, y el arranque `prod` aborta si se fuerza el flag. INACTIVO conserva Pedidos PENDIENTE/Cuenta Corriente y revoca OTP/sesiones ONLINE. [Contrato, matriz y pruebas](docs/implementation/B4_03_03_UC38_ESTADO_CLIENTE_HTTP_GATED_2026-11-01.md). Corte en PR independiente sin aprobación de exposición productiva; #28 sigue abierto.

## B4-02E — Verificación comercial visible antes de UC54 (sólo Administrador)

[Issue #10](https://github.com/leandrito369/ale-milanesas-app/issues/10) y [Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28). El corte propuesto incorpora `GET /api/v1/usuarios/proveedores?idUsuario=<id>` (o filtro CUIT/nombreUsuario) con respuesta mínima `idUsuario`, `nombreUsuario`, estados de cuenta/Proveedor, `idProveedor`, `cuit`, `razonSocial` y contacto autorizado. Requiere **Bearer ADMINISTRADOR**, al menos un criterio y una sesión revalidada; `no-store`; no modifica contraseñas ni confirma identidad automáticamente. **MR5 NO tiene `PROVEEDOR.nombreComercial`** y por eso el campo no se expone ni crea. La [matriz de 4 escenarios E2E humanos y gate de host real](docs/implementation/B4_02E_UC54_CONSULTA_COMERCIAL_Y_ACEPTACION_HUMANA_2026-11-01.md) sigue NO EJECUTADA hasta que la persona responsable complete y firme la evidencia; tampoco habilita POST/PUT comercial.

## B4-03-04 — UC30 Alta de Producto (solo prueba no-prod)

[Issue #43](https://github.com/leandrito369/ale-milanesas-app/issues/43): `POST /api/v1/productos` exclusivamente ADMINISTRADOR, con bandera independiente `ale.http.writes.productos-uc30-enabled=false`, controlador `!prod` y arranque prod que rechaza activarla. Alta del maestro sin precio/receta/stock/costo ficticios; duplicados activos o inactivos devuelven coincidencia minima autorizada. [Contrato HTTP, trazabilidad y pruebas](docs/implementation/B4_03_04_UC30_ALTA_PRODUCTO_HTTP_GATED_2026-11-02.md). **Despliegue del entorno pausado por el responsable (02/11/2026)**; se continua desarrollo/CI bajo gate, #28 permanece abierta y UC54 humana 0/4.

## B4-03-05 — UC35 Buscar Producto (solo lectura)

[Issue #46](https://github.com/leandrito369/ale-milanesas-app/issues/46) / [PR #47](https://github.com/leandrito369/ale-milanesas-app/pull/47): `GET /api/v1/productos` en solo lectura autorizado exclusivamente a `ADMINISTRADOR` y `VENDEDOR` (`RD-RP007-002`), delegando en `BuscarProductoService.buscarProducto` (B3) con proyección mínima `BC-07` de 6 campos (`idProducto`, `nombre`, `descripcion`, `tipoProducto`, `codigoUnidadVenta`, `activo`), sin exponer `stockActualReventa`, `costoPromedioActualReventa` ni `nombreNormalizado`, y con `Cache-Control: no-store`. [Contrato HTTP, trazabilidad y pruebas](docs/implementation/B4_03_05_UC35_BUSCAR_PRODUCTO_HTTP_2026-11-03.md). Implementado, pendiente de activación productiva por pausa de despliegue (#28 permanece abierta); no habilita `PUT`/`PATCH`/`DELETE` de Productos (`UC-47`, `UC-48`) ni activa `UC-30`.

## B4-03-06 — UC47 Modificar Producto (solo prueba no-prod)

[Issue #49](https://github.com/leandrito369/ale-milanesas-app/issues/49): `PUT /api/v1/productos/{idProducto}` exclusivamente `ADMINISTRADOR`, delegando en `GestionProductoService.modificarProducto` (B3) bajo triple gate no-prod (`ale.http.writes.productos-uc47-enabled=false` por defecto, controlador `@Profile("!prod")` y veto en `PrecondicionesDespliegue`). Whitelist de cuerpo (`nombre`, `descripcion`, `tipoProducto`, `codigoUnidadVenta`), verificación de las 7 tablas históricas (`409 PRODUCTO_CON_DEPENDENCIAS` al cambiar tipo/unidad), duplicados activos o inactivos con proyección segura `BC-07` (`409 PRODUCTO_DUPLICADO`), no-op (`huboCambio = false`) y `Cache-Control: no-store`. [Contrato HTTP, trazabilidad y pruebas](docs/implementation/B4_03_06_UC47_MODIFICAR_PRODUCTO_HTTP_2026-11-05.md). Implementado, no desplegado por pausa de despliegue (#28 permanece abierta); no modifica `activo` (`UC-48`) ni precios/recetas.
