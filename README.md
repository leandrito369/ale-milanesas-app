# ale-milanesas-app

**Sistema de Gestión para Ale Milanesas** — Implementación (UNaM-FCEQyN).

## Estado del proyecto

- **G0 aprobado documentalmente** el 29/10/2026 en el [repositorio de análisis](https://github.com/leandrito369/ale-milanesas).
- **B1 / I0 — arquitectura ejecutable:** se prepara un monolito modular Java 21 / Spring Boot 4.1.2 con siete módulos lógicos, pantalla Thymeleaf informativa, Spring Security cerrada, PostgreSQL 17.11 en Docker Compose y Testcontainers.
- **No hay funcionalidades de negocio implementadas**. La app no permite cargar Pedidos, registrar Cobros ni acceder a datos de Clientes.
- **B2 pendiente:** Flyway incluido pero deshabilitado (`spring.flyway.enabled=false`); **no** se despliegan todavía las 60 tablas de MR5/MR6.
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

## B2 — Migraciones físicas en revisión (29/10/2026)

Se prepararon en la **PR de B2** las migraciones `V1__baseline_mr5.sql` y `V2__f01_cobro_estados.sql`, extraídas de G0 con cambios mecánicos limitados a delimitadores transaccionales y directiva psql. `spring.flyway.enabled=true` **únicamente en esta rama pendiente de merge**. [ADR-IMP-002](docs/architecture/ADR-IMP-002_BASELINE_MR5_F01.md) · [ensayo y límites](docs/implementation/B2_MIGRACIONES_MR5_F01.md). El esquema de aplicación es `ale_gestion` y la metadata de Flyway **no** se cuenta dentro de las 60 tablas de negocio. Si CI no verifica equivalencia triple de MR6 y F01, NO fusionar; aplicación real y BC-06 continúan pendientes.

## Propuesta B4-02D — Hardening operativo y gate anterior a B4-03

**No está incorporado en `main`:** [PR #32](https://github.com/leandrito369/ale-milanesas-app/pull/32) es Draft y depende de [PR #31](https://github.com/leandrito369/ale-milanesas-app/pull/31), PR #30, PR #29 y PR #27. No habilita escrituras HTTP comerciales.

El perfil `prod` propuesto acepta exclusivamente un ingreso Nginx TLS en el mismo host, backend Java vinculado a `127.0.0.1`, origen HTTPS `ALE_PUBLIC_ORIGIN` obligatorio y un lock de sesión PostgreSQL que impide iniciar dos instancias simultáneas. Un filtro falla cerrado cuando se pierde la conexión de ese lock. Nginx aplica tasa compartida por IP a POST de login; dentro de la JVM se conserva el límite por cuenta.

Para ensayar el perímetro con Docker y certificado autofirmado **exclusivamente de CI**, ejecutar:

```bash
bash scripts/security/verify_perimeter.sh
```

El procedimiento de despliegue, límites del control, matriz de aceptación y las dependencias reales de BC06 se detallan en [B4-02D: gate operativo y aceptación](docs/implementation/B4_02D_GATE_OPERATIVO_Y_ACEPTACION_2026-11-01.md). **Una prueba TLS con certificado efímero no acredita HTTPS público:** falta validación real del certificado, dominio, firewall, canal comercial UC54 y revisión humana. No ejecutar pruebas con contraseñas/productos/pagos reales.

## B4-03-01 — UC02 Registrar Cliente (solo corte de prueba)

[PR #36](https://github.com/leandrito369/ale-milanesas-app/pull/36), Draft: incorpora un adaptador HTTP de **desarrollo controlado** para `POST /api/v1/clientes`. El flag `ale.http.writes.clientes-uc02-enabled` es **false por defecto**; además el controlador no se carga con `prod` y el validador de arranque de producción rechaza el flag activo. En consecuencia, **ninguna escritura comercial queda habilitada** al crear esta PR. El contrato, las pruebas HTTP reales y la matriz CU→B3→MR5→API→test figuran en [B4-03-01 UC02](docs/implementation/B4_03_01_UC02_ALTA_CLIENTE_HTTP_GATED_2026-11-01.md). No activar el flag fuera de las pruebas hasta revisión y aprobación expresa por operación; [Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) conserva las aceptaciones externas pendientes.

## B4-03-02 — UC32 Modificar Cliente (sólo test, bajo gate)

[PR B4-03-02](https://github.com/leandrito369/ale-milanesas-app/issues/37) propone `PUT /api/v1/clientes/{idCliente}` exclusivo de Vendedor, reutilizando B3. La nueva bandera `ale.http.writes.clientes-uc32-enabled` permanece **false por defecto**, el controlador es `!prod` y `PrecondicionesDespliegue` rechaza el perfil prod cuando se la activa. No autoriza UC38 ni otras mutaciones. Las verificaciones de OTP, no-op, concurrencia, rollback y permisos están especificadas en [B4-03-02](docs/implementation/B4_03_02_UC32_MODIFICAR_CLIENTE_HTTP_GATED_2026-11-01.md). El despliegue requiere el gate externo de [#28](https://github.com/leandrito369/ale-milanesas-app/issues/28).

## B4-02E — Verificación comercial visible antes de UC54 (sólo Administrador)

[Issue #10](https://github.com/leandrito369/ale-milanesas-app/issues/10) y [Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28). El corte propuesto incorpora `GET /api/v1/usuarios/proveedores?idUsuario=<id>` (o filtro CUIT/nombreUsuario) con respuesta mínima `idUsuario`, `nombreUsuario`, estados de cuenta/Proveedor, `idProveedor`, `cuit`, `razonSocial` y contacto autorizado. Requiere **Bearer ADMINISTRADOR**, al menos un criterio y una sesión revalidada; `no-store`; no modifica contraseñas ni confirma identidad automáticamente. **MR5 NO tiene `PROVEEDOR.nombreComercial`** y por eso el campo no se expone ni crea. La [matriz de 4 escenarios E2E humanos y gate de host real](docs/implementation/B4_02E_UC54_CONSULTA_COMERCIAL_Y_ACEPTACION_HUMANA_2026-11-01.md) sigue NO EJECUTADA hasta que la persona responsable complete y firme la evidencia; tampoco habilita POST/PUT comercial.
