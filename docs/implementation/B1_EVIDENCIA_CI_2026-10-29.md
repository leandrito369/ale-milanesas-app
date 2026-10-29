# Evidencia B1/I0 — Primera ejecución de CI

**Fecha:** 29/10/2026  
**PR:** [#2](https://github.com/leandrito369/ale-milanesas-app/pull/2)  
**Commit probado:** `df060800be88642e9f4918a2f816273d4807c8f3`  
**GitHub Actions:** [run #40939222234](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40939222234)  
**Conclusión real:** **SUCCESS**; `mvn -B -ntp clean verify` terminó en `BUILD SUCCESS`.

## Resultados acreditados en los logs

| Suite | Escenarios | Resultado |
|---|---|---|
| `ModuleSkeletonTest` | Siete paquetes/marcadores de módulos lógicos presentes | 1 test, 0 fallos, 0 errores, 0 omitidos |
| `BootstrapIntegrationTest` | Arranque de Spring Boot con PostgreSQL 17.11 Testcontainers y DataSource JDBC | 1 de 3 tests |
| `BootstrapIntegrationTest` | Respuestas HTTP reales 200 para `/` y `/actuator/health` | 1 de 3 tests |
| `BootstrapIntegrationTest` | HTTP 403 para `/api/pedidos` y `/actuator/env` | 1 de 3 tests |
| **Total** | **Cuatro tests** | **4 aprobados / 0 fallos / 0 errores / 0 omitidos** |

El job configuró **Java 21**, verificó la disponibilidad de Docker, descargó/levantó contenedores Testcontainers **2.0.5**, conectó PostgreSQL y empaquetó el JAR. Los reportes `target/surefire-reports` están disponibles en los artefactos del run.

## Límites que siguen vigentes

- Esta evidencia **sí** acredita que el bootstrap compiló, arrancó y atendió HTTP con PostgreSQL real efímero en CI.
- **No** valida un CU, autenticación real de Usuario, autorización RBAC por operación, integraciones externas, Android ni el esquema MR5/MR6.
- **Flyway deshabilitado intencionalmente**; no se ejecutaron scripts históricos ni migraciones.
- **BC-06: 0/18** de pruebas de implementación de operaciones financieras, reparto y webhooks.
- **ADR-B1-05:** diseño físico offline F04/S01 sujeto a CR-B0-F04-001.
- El entorno local del usuario no fue inspeccionado; reproducibilidad local depende de Java 21, Maven, Docker y variables de base correctamente configuradas.

**Estado del incremento:** B1-I0-02 con evidencia de CI y cobertura de arranque, más parte de I0-04. El primer PR de negocio y la baseline migratoria B2 son trabajo posterior, no se consideran terminados.
