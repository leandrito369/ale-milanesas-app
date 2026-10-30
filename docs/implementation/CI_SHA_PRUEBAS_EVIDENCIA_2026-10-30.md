# Continuidad CI: SHA, pruebas y evidencia (30/10/2026 UTC)

**Ámbito:** PR de endurecimiento de los workflows B1/B2 en `ale-milanesas-app`. No modifica aplicación, Flyway, fuente G0, RD/CR, contratos ni criterios de aceptación. No autoriza un merge automático.

## Cambios propuestos

- Las siete referencias externas `uses:` de B1/B2 quedan fijadas por **SHA de commit completo**: `checkout v4.2.2` = `11bd71901bbe5b1630ceea73d27597364c9af683`; `setup-java v6.1.0` = `b843ebf9b913146b38da2eaa7aeddf76074ae1ad`; `upload-artifact v4.6.2` = `ea165f8d65b6e75b540449e92b4886f43607fa02`. Los tres commits fueron verificados en sus repositorios de origen. La mención de versión es informativa; **manda el SHA**.
- Ambos workflows ejecutan sobre toda PR, push a `main` y disparo manual, sin filtros de rutas que omitan cambios importantes. Permisos mínimos `contents: read`; dos checkout B2 y checkout B1 con `persist-credentials: false`.
- Guard estático de acciones y G0: `python3 scripts/ci/validate_workflows.py`; pruebas negativas JUnit-no: `python3 -m unittest discover -s scripts/ci/tests -v`. Son pruebas de configuración en Python `unittest`, **no** reemplazan JUnit/PostgreSQL.
- B1 conserva `mvn -B -ntp clean verify`, Docker/Testcontainers, JaCoCo y publicación de Surefire. B2 conserva verificación de procedencia SQL, instalación Flyway, comparación de MR6 por tres rutas, PostgreSQL y evidencias `b2-logs/`.
- `scripts/ci/evidence.py` escribe `ci-evidence/manifest.json` siempre que el job alcance ese paso: fecha UTC, ref, commit, run, contadores desde Surefire XML, métricas JaCoCo si existen y limitaciones. El manifiesto dice explícitamente `NOT_DETERMINED_INSIDE_JOB`: **no certifica** el resultado del job antes de terminar.

## Evidencia PREVIA comprobada (no atribuir a esta PR)

| Run de PR #9, commit `97859ba50419b61ad048e865530f6c9d0a81836b` | Resultado consultado en Actions | Artefacto |
|---|---|---|
| [B1 40992263909](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40992263909) | SUCCESS | `b1-surefire-reports`, id 12950100295 |
| [B2 40992263918](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40992263918) | SUCCESS | `b2-postgresql-f01-evidence`, id 12949528606 |

En el corte A3 anterior, [B1 40991928240](https://github.com/leandrito369/ale-milanesas-app/actions/runs/40991928240) reportó **57/57** y JaCoCo **92,2 % líneas (333/361), 75,8 % ramas (185/244)** según el informe de UC54 en esa PR; esas cifras **no son resultados de los workflows endurecidos**. Verificar conteos XML del run final antes de atribuirlos a otro SHA.

## Evidencia que falta para aprobar esta PR

1. Obtener nuevo **commit SHA** y URLs de jobs B1/B2 de la presente rama; confirmar SUCCESS de los dos tras el endurecimiento.
2. Descargar y preservar los artefactos; registrar `manifest.json`, Surefire, JaCoCo, `b2-logs` y sus digests suministrados por GitHub. Comparar commit del artefacto con `head_sha` de la PR.
3. Verificar que las pruebas negativas del guard pasaron y que GitHub cargó ambos archivos YAML. Revisar compatibilidad de `setup-java v6` con el runner real.
4. Cotejar el resultado de B2, incluida huella MR6 y restauración, sin inferirlo del color de B1.
5. Revisar el diff y solicitar aprobación humana. No declarar 67 operaciones implementadas, E2E aceptados ni BC06 (0/18) resuelto.

## Limitaciones y riesgos documentados

- **Sin ejecución local Docker/Java/psql verificada en esta actividad**: los resultados previos provienen de los runs consultados de GitHub; la rama nueva requiere su propio CI.
- El guard compara referencias con una **allowlist cerrada** y detecta etiquetas mutables; no realiza auditoría de seguridad de código de terceros, provenance criptográfica del publisher ni escaneo de vulnerabilidades. Actualizar SHAs requiere revisión de seguridad.
- `ubuntu-latest`, imagen de servicio `postgres:17.11`, distribución de JDK 21, dependencias Maven y Docker no están fijados por digest en este cambio. No declarar builds completamente reproducibles.
- `if: always()` ayuda a preservar evidencia cuando se llega al paso, pero no garantiza carga ante caída del runner, cancelación abrupta ni interrupción del servicio GitHub.
- El manifiesto no puede conocer el resultado final del workflow desde el interior del job; confirmar por API/portal y preservar ID, URL, timestamp y hash.
- **UC54:** PR #9 permanece separada; aceptación B4/UI pendiente, prueba de cancelación humana no ejecutada. **BC06 0/18.** F04/S01 mantiene la CR física pendiente.
- **No se implementa deployment ni escritura a producción.** G0 queda fijado en `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`.
