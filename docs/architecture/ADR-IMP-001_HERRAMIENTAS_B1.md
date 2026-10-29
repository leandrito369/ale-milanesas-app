# ADR-IMP-001 — Herramientas y límites del arranque B1/I0

**Fecha:** 29/10/2026  
**Estado:** DECISIÓN TÉCNICA DE IMPLEMENTACIÓN EN PR — requiere revisión del integrador antes del merge.  
**Autoridad:** G0 (RD-B0-G0-001), ADR-B1-01/02/03/04/06 ratificadas.  
**Fuente de diseño:** [source-lock](../traceability/source-lock.md).

## Selección concreta en el repositorio de código

| Componente | Elección implementada en el scaffold | Razonamiento |
|---|---|---|
| JDK | **21** (LTS, bytecode 21) | Estabilidad y compatibilidad con Spring Boot 4.1.2 |
| Spring Boot | **4.1.2** estable (octubre 2026) | Base actual soportada; sin adoptar versión RC |
| Compilación | **Maven**, `pom.xml`, ejecutable JAR | Convención simple y reproducible en CI/local |
| Testcontainers | **2.0.5**, PostgreSQL **17.11** | Alineación de laboratorio MR-6; casos de integración en contenedor efímero |
| PostgreSQL local | **17.11-alpine** en Docker Compose | Dev/QA con DB aislada y sin datos reales |
| Web | Spring MVC + **Thymeleaf**, vista B1 sin acceso a datos | Valida arranque de UI, **no** implementa UC |
| Seguridad | Spring Security con política **deny-all** excepto GET `/` y `/actuator/health` | Fail-closed hasta implementar autenticación y RBAC por CU |
| Migraciones | Dependencias de **Flyway PostgreSQL** incorporadas, `spring.flyway.enabled=false` | **No** inventar DDL ni ejecutar MR-5 o F01 en B1 sin cotejo B2 |
| Integraciones | Ningún SDK ni token configurado en B1 | Adaptadores, mocks y sandbox quedan para B6 |
| Android Kotlin/Room | Ningún módulo Android en este PR | Desarrollo móvil y cola offline posteriores; F04/S01 requiere CR |

**Referencias externas de compatibilidad:** `https://spring.io/blog/2026/10/23/spring-boot-4-1-2-available-now`, `https://docs.spring.io/spring-boot/reference/using/build-systems.html`, `https://java.testcontainers.org/modules/databases/postgres/`.

## Límites

Siete paquetes lógicos **acceso, maestros, ventas, produccioninventario, abastecimiento, finanzas, canalesintegraciones**, además de `bootstrap` técnico. Los `ModuleBoundary` son **marcadores vacíos**, no 7 microservicios, ni garantía automática de dependencias o implementaciones de casos de uso. **No** se crean endpoints de Pedidos/Cobros/Cliente ni autenticación temporal; las rutas no publicadas se rechazan.

`/actuator/health` tiene detalles ocultos y sirve solo para diagnóstico. El landing `/` es estático/informativo y no ofrece datos comerciales. La política de permisos real sigue siendo RBAC 67 + BC07, **pendiente de traducción a controles de servidor en B4**.

## Revisión necesaria

- Que el build Maven compile con Java 21 y que tests arranquen PostgreSQL efímero sin utilizar H2.
- Que CI demuestre respuestas 200 a salud/home y 403 para rutas no autorizadas.
- Que arranque local requiera variables de DB y no acepte contraseñas reales en Git.
- Que Flyway no ejecute scripts ni permita crear tablas prematuramente.
- Que B2 reciba un ADR de baseline migratoria y comparación de huella de MR-6 antes de cambiar `spring.flyway.enabled`.

**B1-I0-02 y parte de I0-04:** implementados como estructura de arranque, pendientes de evidencia real de CI al abrir PR. **I0-03 (B2)** NO completado. **BC06 0/18** pruebas de aplicación.
