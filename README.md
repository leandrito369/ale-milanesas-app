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
