# B1/I0 — Instalación y pruebas reproducibles

**Fuente G0:** [source-lock](../traceability/source-lock.md) — 64 CU / 67 operaciones.  
**Alcance:** arranque, paquetes modulares, home Thymeleaf, health Spring Actuator, seguridad cerrada, PostgreSQL local y Testcontainers. No operaciones comerciales, ni migraciones históricas MR5/MR6, ni Android, ni integraciones.

## Requisitos
- JDK **21** y Maven **3.9+**.
- Docker Engine o Docker Desktop funcionando (necesario para PostgreSQL local y Testcontainers en CI).
- Puertos locales **5432** y **8080** libres, o ajustar `ALE_DB_PORT` / `ALE_HTTP_PORT`.

## Desarrollo local (sin contraseñas en Git)

```bash
cp .env.example .env
# Editar ALE_DB_PASSWORD con una contraseña exclusivamente local.
docker compose up -d postgres
# Exportar variables al shell actual (o configurar entorno IDE).
set -a
. ./.env
set +a
mvn -B -ntp spring-boot:run
```

Comprobar en otra terminal:
```bash
curl -i http://localhost:8080/
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/api/pedidos  # 403: todavía no existe ni está autorizada
```

**IMPORTANTE:** la pantalla raíz es informativa; un `200` en home o health no prueba un CU. Los demás paths quedan bloqueados; no hay usuario de prueba ni API de negocio.

## Pruebas reproducibles

```bash
mvn -B -ntp clean verify
```

El test `BootstrapIntegrationTest` inicia **PostgreSQL 17.11** con Testcontainers y el servidor HTTP real. Comprueba conexión DB, home/health pública y denegación de rutas no autorizadas. `ModuleSkeletonTest` verifica presencia de los siete módulos lógicos, **no prueba dependencias entre capas ni casos de uso**.

La ejecución exige Docker incluso si hay PostgreSQL local. Los reportes Maven se generan en `target/surefire-reports/`; CI adjunta evidencia. Al no contar con Maven/Docker en el entorno de preparación, **se distingue claramente PR generado de CI pasado**; confirmar los resultados en GitHub Actions antes de declarar el arranque reproducible.

## Persistencia: B2, no B1

El contenedor PostgreSQL arranca vacío. **Flyway está intencionalmente deshabilitado** y no ejecuta migraciones. No editar/aplicar SQL MR5/MR6 ni F01 a mano. Ver [estado Flyway](BASELINE_B2_PENDIENTE.md).

## Próximos trabajos

- I0-03/B2: baseline de 60 tablas MR5/MR6, parche F01, migraciones versionadas, ensayo de recuperación y huella.
- I1/B3–B5: Usuarios/Roles y maestros, luego recorrido Pedido/Preparación/Entrega/Cobro.
- B6/B7: adaptadores externos y Android offline; sincronización F04 pendiente de CR.
- B8: regresión BC06 **0/18** pruebas aún no ejecutadas.

---

## Complemento B2 (posterior a B1)

Los comandos de la guía B1 siguen aplicando para probar el entorno, pero el comportamiento de persistencia **cambia en la rama B2**: Flyway administra las migraciones de MR5 consolidado y F01 bajo schema `ale_gestion`. Ver [guía B2](B2_MIGRACIONES_MR5_F01.md), evitar reusar un volumen de desarrollo que contenga tablas previas, y ejecutar solo sobre bases vacías de laboratorio. La sección histórica «Flyway deshabilitado» corresponde al corte B1, **no** describe la rama B2.
