# BC-06 — Ejecución solicitada: 18 escenarios, precondiciones y resultados verificables

**Fecha:** 30/10/2026. **Estado del avance:** control ejecutable preparado; ejecución funcional completa bloqueada por componentes ausentes. **No confundir un job CI SUCCESS con 18 casos de negocio aprobados.**

## Autoridad y límite

- Fuente G0 inmutable: `leandrito369/ale-milanesas`, commit `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, archivo `docs/01-gobierno/b0/BC06_CASOS_PRUEBA_IMPLEMENTACION_18.csv`, blob `77c3343bd5b7af49b76a05880e812fd73986cb9e`.
- Alcance: 18 escenarios BC06-01 a BC06-18, **sin cambiar estímulo, expectativa ni decisión funcional**.
- Los cuatro E2E de UC54 (`E2E-UC54-OK`, `A1`, `A2` y `A3`) quedaron **documentados** en [Issue #10](https://github.com/leandrito369/ale-milanesas-app/issues/10); ninguno se acredita como aceptación B4 realizada.
- Código real: configuración de Spring Security todavía cierra toda ruta de negocio con `anyRequest().denyAll()`; no hay contrato HTTP/driver B4 o adaptador B6 ejecutable para estas 18 pruebas. El backend disponible permite verificar infraestructura y ciertos servicios internos de I1, **no** los efectos de Cobro/rendición/webhook del escenario BC06.

## Resultado de la campaña de preparación

El workflow [BC06 readiness](../../.github/workflows/bc06-readiness.yml) lee directamente el CSV original del checkout G0 por commit y blob; verifica IDs 01–18 sin duplicados; ejecuta seis pruebas Python negativas de integridad; genera **18 registros individuales `NO_EJECUTADO`**, un XML de 18 `skipped` y el CSV/JSON de resultados con SHA, run y motivo; y ejecuta `mvn -B -ntp clean verify` sobre los servicios hoy disponibles. El paso Maven **no computa** como caso BC06.

**Métrica correcta:** casos del catálogo revisados 18/18; escenarios funcionales ejecutados **0/18**; escenarios funcionales aprobados **0/18**; bloqueados **18/18**. La creación de artefactos no prueba aceptación de usuario.

| Casos | Escenario comprometido | Dependencia y decisión |
|---|---|---|
| BC06-01–03 | Permisos y efectos atómicos de Cobro manual | B3 UC29 Cobro/CC/Caja + B4 RBAC: implementar y medir antes de ejecutar |
| BC06-04–06 | Denegación y contraasientos de anulación | B3 UC29 anularCobroManual + B4, F01 físico por sí solo insuficiente |
| BC06-07–09 | Efectivo declarado, offline, rendición parcial | **CR-B0-F04-001 abierta**: no implementar estructuras F04/S01 por inferencia |
| BC06-10–12 | Unicidad y reintentos de reembolso | B3 UC26/UC49 + B6 integración, bloqueo financiero hasta PROCESADO |
| BC06-13–16 | Firma, correlación, duplicación y concurrencia webhook | B6 adaptador seguro, pruebas de integración e idempotencia |
| BC06-17–18 | Invocación interna indebida, suplantación de rol ONLINE | B4 autenticación y RBAC/BC07/BC09 con rutas reales |

### Procedimiento de ejecución reproducible

Con GitHub Actions, abrir el run `BC06 - Preparacion 18 escenarios (NO ejecucion E2E)`; verificar que el reporte contiene 18 skipped y 0 funcionales, luego recuperar el artefacto `bc06-readiness-not-functional-execution`.

En un checkout local de la app con Python 3, Maven 3.9+, JDK 21 y Docker:

```bash
git clone https://github.com/leandrito369/ale-milanesas.git g0-source
git -C g0-source checkout 1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e
python3 -m unittest discover -s scripts/testing/tests -p 'test_*.py' -v
python3 scripts/testing/bc06_readiness.py --g0-root g0-source --out bc06-evidence
mvn -B -ntp clean verify
```

El script produce `bc06-evidence/bc06-resultados.{csv,json}`, `bc06-skip-report.xml` y `bc06-resumen.md`. **No se debe convertir el XML de saltos en PASS**, ni usar el estado de CI como porcentaje de cobertura funcional.

## Próximo desbloqueo técnico, sin ampliar permisos

1. **I1/B4:** completar autenticación HTTP verificable, sesión protegida, CSRF y el flujo de UC54 pendiente. Vincular las cuatro historias E2E a pruebas end-to-end auténticas, sin afirmar aceptación solo por B3.
2. **I3/B3:** implementar UC29 de Cobro/anulación con Cuenta y Caja bajo MR5/F01, idempotencia y rollback; priorizar BC06-01–06 con datos sintéticos y pruebas negativa/positiva.
3. **I4/B3-B6:** integrar UC26/49 y adaptar webhooks autenticados; luego BC06-10–16 con correlación, repetidos, carreras y efectos exactamente una vez.
4. **I4/B4:** ejecutar BC06-17/18 contra endpoints reales con autenticación, RBAC y proyecciones BC07.
5. **F04/S01:** resolver formalmente CR-B0-F04-001 **antes** de codificar los flujos de declaración offline y rendición para BC06-07–09.

## Criterios de evidencia futura

Para marcar cada caso `PASS` es indispensable registrar: referencia CU/RD, autorización de actor, entorno PostgreSQL real o sandbox conforme al tipo, datos sintéticos, estímulo invocado, resultado observado y comparado con esperado, before/after de tablas y estados, rollback/concurrencia cuando aplique, SHA de código, URL de run y logs anonimizados. `FAIL` requiere prueba realmente ejecutada y diferencia observada; ausencia de servicio se declara `NO_EJECUTADO`, no `FAIL` ficticio.

**Reservas:** no se usan datos reales ni credenciales externas; no se amplía RBAC, no se toca MR5, no se fusionan PR ajenas, no se ejecutan migraciones sobre datos productivos ni se resuelve CR F04 tácitamente. La suite es incremental y debe pasar a implementaciones verificables por cada escenario.
