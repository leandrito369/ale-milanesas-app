# ale-milanesas-app

Implementación del **Sistema de Gestión para Ale Milanesas** (UNaM–FCEQyN).

## Estado

**G0 aprobado documentalmente el 29/10/2026** en el repositorio normativo [ale-milanesas](https://github.com/leandrito369/ale-milanesas). Este repositorio es el destino exclusivo del código, migraciones y pruebas de la aplicación, **no la autoridad de requisitos**.

**Fuentes fijadas:** [G0 source-lock](docs/traceability/source-lock.md) — commit de fuentes `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, acta y manifiesto en `27738b089afdd2698cdae915f75c0e8089a6248a`. [Instrucciones para agentes](AGENTS.md).

**B1 no implementado todavía:** este PR documenta el traspaso; aún no hay backend Spring Boot, frontend Thymeleaf, Android Room, servicios de pagos ni migraciones de aplicación. No atribuir resultados funcionales a los 21 tests documentales de G0.

## Próxima etapa

I0/B1: estructura modular Java/Spring Boot, build y entorno reproducible, PostgreSQL versionado, CI/Testcontainers, siguiendo ADR aprobadas y el backlog ratificado. Se iniciará en **otra PR**, sin alterar aquí CU, DSS/DS o contratos. **ADR-B1-05 sigue pendiente de CR física**.

El desarrollo por Antigravity, OpenCode y otros agentes debe seguir `AGENTS.md` y `source-lock`.
