# Plan de pruebas — estándar de Proyecto Software II (UNaM)

**Versión:** 2026-10-29 / revisión inicial I1.  
**Fuente académica suministrada:** `Clase Testing.md`, clase de pruebas de software del Mgter. Sergio Daniel Caballero (FCEQyN/UNaM, 2026); especialmente diapositivas 9–16, 18–22 y 26–29.  
**Estado:** plan inicial de aplicación del material, **no aceptación académica ya otorgada**.  
**Repositorio de código:** `ale-milanesas-app`. **Fuente normativa del negocio:** G0 + RD/CR posteriores en `ale-milanesas`.

## 1. Alcance y prioridad (qué se probará)

1. **Acceso y seguridad (riesgo crítico):** UC01, UC17, UC18, UC53, UC54, UC55, UC56; roles, autenticación, cambio de contraseña y revocación de sesiones; RD ratificadas, RF08.
2. **Datos maestros (riesgo funcional):** UC02, UC32, UC33, UC38, Productos, precios y catálogos; RBAC/proyecciones BC07.
3. **Núcleo transaccional (riesgo crítico):** Pedidos, Preparación, Entrega, Cobros, Caja y reembolsos; BC06 18/18 **pendientes hasta disponibilidad de servicios**.
4. **Integraciones y escenarios E2E:** web online, webhooks autenticados, Android y offline. F04/S01 bloqueado por CR hasta ratificación; no realizar pruebas de flujo inexistente.

## 2. Tipos, técnicas y niveles

| Tipo | Técnica/alcance | Framework/evidencia | Estado actual |
|---|---|---|---|
| Revisión estática / verificación | RD, contrato, DSS/DS, diff, esquema, checklist de riesgos | Auditoría G0, revisión PR, CI documental | En uso |
| **Unidad aislada** | AAA (Arrange–Act–Assert), particiones, equivalencias, decisiones y límites | JUnit Jupiter, `PoliticaCambioCredencialTest` | Añadida en PR UC53 |
| **Integración** | Servicios + Spring + PostgreSQL 17.11 + Flyway MR5/F01, triggers, rollback, concurrencia | JUnit/Testcontainers, Surefire y CI | En uso; UC53 se añade en su PR |
| Sistema / caja negra / seguridad | HTTP real, control de rutas, autenticación, requisitos no funcionales | SpringBootTest, pruebas API/E2E cuando B4 exista | Bootstrap disponible; flujos de negocio aún no |
| **Aceptación (alfa/beta)** | Requisitos del usuario real, escenarios de ficha Real/Extendida, flujos principales y alternativos | Acta de casos y evidencia con usuario/stakeholders | **No ejecutada**; requiere app y aceptación humana |
| Regresión | Repetición automatizada tras PR | GitHub Actions B1/B2, `mvn clean verify` | Operativa |

**No confundir:** 28/28 de CI antes de UC53 son pruebas automatizadas (integración incluidas), **no** aceptación, ni cobertura del sistema, ni pruebas BC06. Una prueba documental no equivale a prueba de servidor.

## 3. Herramientas y CI/CD

- JDK 21, Maven, JUnit Jupiter; Spring Boot Test y PostgreSQL 17.11 Testcontainers; Docker en CI.
- **JaCoCo 0.8.15** calcula cobertura de líneas y ramas y genera `target/site/jacoco/index.html`, `jacoco.xml`, `jacoco.csv`, adjuntados como artefactos de GitHub Actions.
- **Meta docente recomendada:** 70–80 % de cobertura, según la clase, **no umbral obligatorio ni porcentaje alcanzado**. Se registrará el valor real total, por paquete crítico y por líneas/ramas después de CI; no imponer exclusión artificial para alcanzar la meta.
- CI ejecuta `mvn -B -ntp clean verify`, tests de integración y huella estructural B2; publicación de reportes incluso al fallar. No habilitar `deploy` automático productivo.

## 4. Criterios de entrada/salida

**Entrada:** CU + contrato y RD identificados; schema y source-lock definidos; testabilidad; datos ficticios; entorno CI disponible. Si hay IC no resuelta (ej. UC53/MR5), registrar limitación y no declarar aceptación final de flujo.

**Salida de PR técnica:** cambios dentro del alcance, análisis estático/revisión, unitarias y/o integración apropiadas, pruebas positivas y negativas con resultados, B1/B2 verdes, reporte JaCoCo generado, limitaciones y defectos listados, aprobación del responsable antes de merge.

**Salida de aceptación de CU:** además de lo anterior, suite de casos derivada de los flujos Normal/Alternativos/Excepciones, evidencia de sistema end-to-end cuando exista UI/API, validación humana de escenarios reales, acta con decisión y resultados. No se alcanza por el mero `BUILD SUCCESS`.

## 5. Responsabilidades y agenda

- **Implementador/agentes:** casos, código, datos ficticios, ejecución y evidencia CI, análisis de fallos.
- **Responsable de negocio/proyecto:** ratificar RD/CR, revisar PR, aprobar aceptación y riesgos excepcionales.
- **Cátedra:** evaluación académica posterior; no se presume su aprobación.
- **Cadencia:** cada PR incluye unitarias/integración relevantes y CI; revisión de cobertura y regresión por iteración I1/I2/...; aceptación formal de incrementos ejecutables al cerrar cada bloque; B8 consolida resultados.

## 6. Registro de evidencia exigido

Por caso: ID, CU/RF/RD, condición inicial, tipo de prueba, datos ficticios, pasos/estímulo, resultado esperado, resultado observado, estado PASS/FAIL/NO EJECUTADO, commit, workflow/run URL y responsable/revisor. Conservar fallos y correcciones anteriores, no sobrescribirlos con una afirmación de éxito.

**Métricas mínimas de reporte:** número de pruebas totales/ejecutadas/fallidas/omitidas, cobertura líneas y ramas (real medida), hallazgos abiertos, tasa de aceptación por CU. No atribuir aceptación de usuario a tests automáticos.
