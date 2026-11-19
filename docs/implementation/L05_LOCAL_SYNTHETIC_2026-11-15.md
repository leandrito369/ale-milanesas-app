# L0.5 — Pruebas manuales locales con datos sintéticos y regresión inicial C3-A

**Estado:** EVIDENCIA LOCAL REPORTADA — registrada documentalmente; no equivale a aceptación funcional ni autorización de despliegue.  
**Fecha informada de ejecución:** 2026-11-15, aproximadamente 03:20–03:38 UTC.  
**Aplicación examinada:** `leandrito369/ale-milanesas-app`, `main` @ `4dbe38668fc5cbc08d04e10920e3bf165c71be11`.  
**Origen de los resultados:** reporte del responsable que operó el laboratorio local; este commit documental no reproduce ni reejecuta las pruebas.  
**Alcance del cambio:** documentación exclusivamente; sin código Java, SQL ejecutable, datos privados, migraciones ni modificaciones de configuración.

## 1. Objetivo y límites de la evidencia

Registrar de manera trazable el primer laboratorio L0.5 con PostgreSQL sintético, comprobaciones HTTP de UC-03/UC-34/UC-35 y seis pruebas Testcontainers de concurrencia sobre servicios ya implementados.

**Demuestra dentro del alcance reportado:**
- Arranque local de un backend independiente contra `ale_milanesas_l05`; health HTTP 200.
- Aplicación de Flyway V1, V2 y V3 en esa base nueva, sin V4.
- Controles Bearer/roles comprobados en casos positivos y negativos; sesiones sintéticas insertadas directamente (no se prueba login UC-01).
- Alta de UC-03, replay idempotente sin pedido duplicado, rechazo de cantidad cero y consulta posterior UC-34.
- Persistencia comercial coincidente con la salida HTTP.
- Seis escenarios existentes de Testcontainers finalizados sin fallos, incluida regresión del namespace advisory de UC-19 (PR app #86).

**No demuestra ni autoriza:**
- Implementación, contratos definitivos o validación de UC-07; no se han ejecutado CR-C/CR-F.
- Ausencia global de deadlocks en todos los servicios/intercalados; RT-UC07-04 permanece ABIERTA.
- Pruebas humanas, certificación de infraestructura, despliegue o cierre de Issue #28.
- Seguridad de un flujo de login real UC-01, interfaz web final ni autenticidad de clientes reales.
- Calibración de los límites de E ni autorización de D/E/F/G/H.
- Que no existan movimientos físicos: el chequeo de nombres de tablas `%CONSUMO%` no constituye inventario de filas en las tablas operativas reales.

## 2. Línea base y entorno

| Elemento | Resultado informado |
|---|---|
| Sistema anfitrión | Windows 11, WSL2 con Ubuntu 26.04 |
| Docker | Desktop 29.4.3; integración WSL2 |
| Docker Compose | Plugin v5.1.3 |
| Java | JDK 21 (Temurin 21.0.13 informado; confirmar proveedor con `java -version` al repetir) |
| Maven | 3.9.9 |
| Git | 2.53.0 |
| Motor PostgreSQL | Imagen `postgres:17.11-alpine` |
| Código aplicado | `4dbe38668fc5cbc08d04e10920e3bf165c71be11` |
| Servidor de desarrollo | `127.0.0.1:8080` |
| Servidor L0.5 | `127.0.0.1:8081`, con flag `ale.http.writes.pedidos-uc03-enabled=true` solo para ese proceso |
| Base de desarrollo | `ale_milanesas_dev` |
| Base sintética | `ale_milanesas_l05` |
| Puerto PostgreSQL anfitrión | `127.0.0.1:5433`; ambas bases comparten un contenedor Compose |

Las pruebas Testcontainers utilizan instancias efímeras de PostgreSQL con puertos asignados dinámicamente; no deben confundirse con el contenedor Compose persistente. La separación de bases aísla datos, pero **no** equivale al aislamiento de procesos o recursos de dos servidores PostgreSQL diferentes.

## 3. Línea base previa L0

Según el reporte recibido:

- `mvn -B -ntp clean verify`: **619 tests; 0 failures; 0 errors; 0 skipped; BUILD SUCCESS**.
- Health de la instancia original, `127.0.0.1:8080/actuator/health`: **HTTP 200, UP**.
- Migraciones en `ale_milanesas_dev`: V1 `baseline mr5`, V2 `f01 cobro estados` y V3 `trazabilidad partidas insumo`, todas exitosas.

La fila `<< Flyway Schema Creation >>` refleja la creación del esquema por Flyway; no constituye una cuarta migración de negocio.

## 4. Aislamiento L0.5 y carga de datos ficticios

Primero se verificó que `ale_milanesas_l05` no existiera. Se creó con `createdb` y la aplicación inició con:

```bash
ALE_DB_PORT=5433 ALE_DB_NAME=ale_milanesas_l05 ALE_HTTP_PORT=8081 SERVER_ADDRESS=127.0.0.1
mvn -B -ntp spring-boot:run \
  -Dspring-boot.run.arguments='--ale.http.writes.pedidos-uc03-enabled=true'
```

**Nota operativa:** esas asignaciones representan la configuración efectiva; para reproducirlas, exportar las variables en la terminal del backend según la guía L0.5. Mantener el perfil no productivo y el puerto enlazado a loopback.

Flyway sobre `ale_milanesas_l05`: **V1–V3 exitosas; no apareció V4**. Health local `8081`: HTTP 200.

El generador `ale_l05_preparar.py` produjo `seed.sql` y `tokens.env` privados (permisos `0600`). La carga utilizó `psql -X -v ON_ERROR_STOP=1` y controles que abortaban si la base no era `ale_milanesas_l05` o si contenía usuarios/pedidos previos.

**Resultado informado de carga (diez grupos de INSERT, COMMIT):**

| Tabla | Filas post-seed |
|---|---:|
| USUARIO | 2 |
| USUARIO_ROL | 2 |
| CLIENTE | 1 |
| PRODUCTO | 1 |
| PRECIO_PRODUCTO | 1 |
| SESION_USUARIO | 2 |
| PEDIDO | 0 |

Se generaron sesiones sintéticas para ADMINISTRADOR y VENDEDOR, vigentes al momento de los ensayos. **No se versionan UUID de sesiones, tokens, huellas, contraseñas, ni contenido de `seed.sql` / `tokens.env`.** La emisión de tokens opacos y su huella SHA-256 se verifican por el código de `TokenSesionInterna`; la batería HTTP solamente comprueba el acceso y rechazo de los escenarios indicados.

## 5. Batería HTTP local

**Resultado informado: 9/9 verificaciones OK.**

| # | Caso | HTTP esperado | HTTP observado |
|---|---|---:|---:|
| 1 | Health de la instancia L0.5 | 200 | 200 |
| 2 | UC-35 buscar producto ficticio | 200 | 200 |
| 3 | UC-34 listado antes de alta | 200 | 200 |
| 4 | UC-03 sin Bearer | 401 | 401 |
| 5 | UC-03 con ADMINISTRADOR, sin rol VENDEDOR | 403 | 403 |
| 6 | UC-03 con VENDEDOR, alta válida | 201 | 201 |
| 7 | UC-03 replay de igual clave, sin duplicación | 200 | 200 |
| 8 | UC-03 cantidad cero, rechazo sin alta | 400 | 400 |
| 9 | UC-34 consulta posterior al alta | 200 | 200 |

La batería utilizó `ale_l05_probar.py` con sesiones de laboratorio. El servidor UC-03 utilizó exclusivamente su gate experimental no-prod; no se habilitaron endpoints UC-07.

## 6. Verificación SQL posterior

**Fila comercial observada:**

| idPedido | estado | totalOriginal | tipoAjuste | porcentajeAjuste | cantidadOriginal | precioUnitarioOriginal |
|---:|---|---:|---|---:|---:|---:|
| 1 | PENDIENTE | 15300.00 | DESCUENTO | 10.00 | 2.000 | 8500.0000 |

Cálculo: `2.000 × 8500.0000 = 17000.00`; descuento de `10.00% = 1700.00`; total `15300.00`.

Conteos post-batería reportados:

- `PEDIDO = 1`: el replay no duplicó el pedido.
- `DETALLE_PEDIDO = 1`: una línea original con `cantidadOriginal = 2.000`.
- La búsqueda de nombres de tablas `%CONSUMO%` devolvió cero coincidencias. **Esto no prueba conteos cero** de `PREPARACION`, `MOVIMIENTO_INSUMO`, `MOVIMIENTO_EXCEDENTE` o `MOVIMIENTO_STOCK_REVENTA`.

**Comprobación complementaria sugerida, NO ejecutada en este informe:**

```sql
SELECT 'PREPARACION' AS tabla, count(*) AS filas FROM ale_gestion."PREPARACION"
UNION ALL
SELECT 'MOVIMIENTO_INSUMO', count(*) FROM ale_gestion."MOVIMIENTO_INSUMO"
UNION ALL
SELECT 'MOVIMIENTO_EXCEDENTE', count(*) FROM ale_gestion."MOVIMIENTO_EXCEDENTE"
UNION ALL
SELECT 'MOVIMIENTO_STOCK_REVENTA', count(*) FROM ale_gestion."MOVIMIENTO_STOCK_REVENTA";
```

La ausencia de una migración UC-07 y la inexistencia de operaciones UC-07 implementadas en este SHA son afirmaciones distintas de esos conteos.

## 7. Primer ensayo C3-A: regresión concurrente existente

**Comando reportado:**

```bash
mvn -B -ntp \
  -Dtest=PrecioProductoUc03DeadlockIntegrationTest,PrecioProductoConcurrenciaIntegrationTest,CargarPedidoConcurrenciaIntegrationTest,RegistrarPreparacionConcurrenciaIntegrationTest \
  test
```

| Clase de prueba | Tests | Fallos | Errores | Omitidos |
|---|---:|---:|---:|---:|
| PrecioProductoUc03DeadlockIntegrationTest | 1 | 0 | 0 | 0 |
| PrecioProductoConcurrenciaIntegrationTest | 1 | 0 | 0 | 0 |
| RegistrarPreparacionConcurrenciaIntegrationTest | 2 | 0 | 0 | 0 |
| CargarPedidoConcurrenciaIntegrationTest | 2 | 0 | 0 | 0 |
| **Total** | **6** | **0** | **0** | **0** |

Resultado reportado: **BUILD SUCCESS**, duración **42.492 segundos**. Cada clase utilizó PostgreSQL Testcontainers efímero, con Flyway V1–V3, y los contenedores se descartaron después.

La prueba `PrecioProductoUc03DeadlockIntegrationTest` aporta regresión específica para la corrección del advisory lock UC-19 a `pg_advisory_xact_lock(1919,1)`, integrada en la PR app #86. El éxito de esta batería **no demuestra ausencia universal de deadlocks** ni cierra H/RT-UC07-04.

## 8. Reproducción y custodia de artefactos

La guía y los scripts empleados pertenecen al paquete de laboratorio L0.5 entregado fuera de ambos repositorios. **No se afirma que estén versionados en Git**. Para reproducibilidad futura se recomienda revisar y versionar por separado únicamente los scripts/guías sin secretos.

Huellas de referencia SHA-256 del paquete suministrado (comparar con las copias efectivamente ejecutadas):

- `ale_l05_preparar.py`: `b2dcd1b974c4b7b8b8eeb600a23f984d3e7c50fbaf6f39f93434eb9fa87e9068`.
- `ale_l05_probar.py`: `5ecc6957ef25b113df9758daf627888c472c43bf0843c352b92342506ba2ac50`.
- `GUIA_L05.md`: `d1effa5fcaf4cd50ff447dca473d138bc68552758487f324eeb23e2cffe6e353`.

Estas huellas identifican los archivos distribuidos, **no sustituyen** un contraste con las copias del operador ni prueban por sí mismas que fueran los bytes utilizados durante la ejecución.

Hitos reproducibles sin secretos:

```bash
# Desde ale-milanesas-app, con .env cargado y conexión de laboratorio comprobada:
docker compose exec -T postgres psql -U "$ALE_DB_USER" -d ale_milanesas_l05 \
  -c 'SELECT version, description, success FROM ale_gestion.flyway_schema_history ORDER BY installed_rank;'

python3 ~/ale-l05-tools/ale_l05_preparar.py --destino "$HOME/ale-l05"

docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 \
  -U "$ALE_DB_USER" -d ale_milanesas_l05 < "$HOME/ale-l05/seed.sql"

python3 ~/ale-l05-tools/ale_l05_probar.py \
  --tokens "$HOME/ale-l05/tokens.env"
```

**Atención:** estos pasos de generación/carga solo son válidos para una base nueva y vacía; el generador no debe sobrescribir `seed.sql` ni `tokens.env` previos. Esta documentación no autoriza borrar/recrear la base con datos de evidencia.

## 9. Incidencias operativas del anfitrión

Se informaron, sin cambios de código de negocio:

1. Conflicto de publicación en puerto `5432` por reservas de Windows/WinNAT: configuración efectiva `127.0.0.1:5433`.
2. La variable `ALE_DB_PORT` exportada en el shell podía sobrescribir la del `.env` usada por Compose: se revisó y desasignó cuando correspondía.
3. Interrupción temporal de la red WSL2 después de manipular WinNAT: recuperación local mediante reinicio de WSL.
4. Dos instancias backend en puertos `8080` y `8081` contra bases distintas; no se observó interferencia en la campaña.

Son detalles de reproducción local, no reglas del dominio.

## 10. Gobierno, restricciones y próximos pasos

- [CR-C / gobierno PR #47](https://github.com/leandrito369/ale-milanesas/pull/47) y [CR-F / gobierno PR #48](https://github.com/leandrito369/ale-milanesas/pull/48): **OPEN / DRAFT / NO AUTORIZADAS** al redactar este informe; no se modifican aquí.
- C1 A–C ratificadas; D/E especificadas para ratificación, límites numéricos de E sujetos a calibración; F/G candidatos no autorizados; H/RT-UC07-04 **ABIERTA**.
- [Issue app #28](https://github.com/leandrito369/ale-milanesas-app/issues/28): abierta; despliegue pausado.
- Próximo corte: **C3-A.2**, inventario real de locks y diseño de intercalados, diferenciando servicios reales (R), prototipos aislados (P) y diseño documental.
- Antes de reutilizar el laboratorio, renovar sesiones caducadas **solo mediante un procedimiento autorizado**; conservar las evidencias del primer ciclo y no ejecutar `dropdb`/`down -v` automáticamente.
- Pendiente de complemento: conteos sobre tablas operativas reales y custodia persistente de logs redaccionados/Surefire XML (sin secretos).

**Dictamen limitado:** L0.5 y la primera regresión C3-A se consideran satisfactorias **según los resultados locales reportados** para el SHA identificado. Esto no equivale a cierre de UC-07, de RT-UC07-04 o de infraestructura.
