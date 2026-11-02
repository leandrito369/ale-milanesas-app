# B9-01 / B8 — Entorno y responsables para aceptar UC54 y certificar infraestructura

**Fecha:** 01/11/2026 (America/Argentina/Buenos_Aires). **Estado:** paquete de instalacion preparado; host **NO PROVISIONADO**, cuatro E2E humanos **NO EJECUTADOS 0/4**, infraestructura **NO CERTIFICADA**.

Plan rector: B4-02E → B8/E09/E11 y B9, dentro de I1. No se adelantan I2, B6, B7 ni cambios de reglas. G0 y gobierno fijados en [source-lock](../traceability/source-lock.md). PR #39 integrada en `0a92842bd5c3d6f85216209d41909e8d3da24e12`, HEAD revisado `5aebb0cd46aed3ab4e985a3d998e440ed88f8383`. Es consulta contextual parcial UC56/37, no terminacion integral de ambos buscadores.

## Responsabilidad verificable

| Trabajo | Responsable de seguimiento en GitHub | Ejecutor / firma necesaria | Estado |
| --- | --- | --- | --- |
| Integracion y coordinacion del gate | `leandrito369`, propietario y usuario autenticado | Revision tecnica del asistente registrada en PR39; decision de integracion solicitada por el usuario | PR39 fusionada; CI de merge se verifica por separado |
| A1, A2, A3 y OK de UC54 | `leandrito369`, asignado a [#10](https://github.com/leandrito369/ale-milanesas-app/issues/10) | Administrador verificador: **POR DESIGNAR**; actor Proveedor para OK: **POR DESIGNAR** | 0/4; no hay disponibilidad personal confirmada |
| Provision y certificacion de infraestructura | `leandrito369`, asignado a [#28](https://github.com/leandrito369/ale-milanesas-app/issues/28) | Operador con acceso host/DB/DNS: **POR DESIGNAR** | Host/FQDN/certificado/acceso no aportados |
| Revision de evidencia y dictamen | `leandrito369` coordina | Revisor/testigo de seguridad: **POR DESIGNAR** | Sin firma humana |

La asignacion GitHub establece seguimiento; no prueba disponibilidad, competencia, identidad comercial ni ejecucion. El asistente prepara codigo/paquete y registra resultados tecnicos, no suplanta a los actores ni firma por ellos. No se notificaron personas externas ni se eligio proveedor cloud.

## Paquete disponible a traves de CI

El workflow **B1 - Maven / Testcontainers PostgreSQL**, despues de `clean verify`, publica `ale-acceptance-<SHA>`: `app.jar`, `manifest.json`, `SHA256SUMS`, configuracion prod de aceptacion, unidad systemd, plantilla Nginx y procedimientos/actas. Descargar desde el run del **SHA exacto**; no identificar la version solo por nombre del JAR. El artefacto vence a los 14 dias; puede regenerarse desde el commit.

Reproduccion en una maquina con JDK21, Maven3.9+, Docker y Python3:

```bash
git checkout --detach <SHA_REVISADO>
mvn -B -ntp clean verify
bash scripts/acceptance/package_bundle.sh "$(git rev-parse HEAD)" "<URL_RUN_O_REFERENCIA_LOCAL>"
```

El manifiesto no acredita despliegue. Comprobar SonarCloud del mismo SHA ademas de B1; la huella MR5/F01 de B2 es independiente. El entorno del asistente tiene Java17, sin Maven/Docker: no se atribuye aqui un arranque de Java21 ni se inventa CI local.

## Contrato del entorno que debe aportar el operador

- Una VM/host Linux **aislado de produccion**, JDK21, Nginx y PostgreSQL17.11. Una sola JVM y un solo Nginx en el mismo host, DB de aceptacion separada sin datos reales. No agregar balanceador ni replicas sin decision posterior.
- FQDN con registros DNS correctos y certificado de CA confiable, cadena/SAN/vigencia comprobables desde otra estacion. Certificados y secretos se instalan fuera de Git. La plantilla usa 443; backend fijo a 127.0.0.1:8080 y DB privada. Acceso administrativo restringido a operadores; no publicar 8080/5432.
- Cuenta/DB de aceptacion vacia con permisos para aplicar **solo V1 MR5 y V2 F01** ya versionadas. Primer arranque aplica Flyway: confirmar antes que se apunta a la DB de aceptacion. No reutilizar una base productiva ni cambiar DDL.
- Fixtures ficticias/consentidas: Administrador habilitado sin cambio pendiente, Proveedor ACTIVO vinculado a Usuario exclusivamente PROVEEDOR, cuenta no PROVEEDOR para A2 y objetivo inexistente para A1. Crear mediante preparacion controlada por operador segun servicios B3/MR5; no se entrega una cuenta ni contrasena predeterminada y no se desactivan constraints. Las fixtures de Testcontainers se destruyen al terminar CI y **no** aprovisionan este host.
- Nombres/roles/disponibilidad de Administrador, actor Proveedor, operador y revisor; canal confidencial elegido y ratificado por el Administrador. No se inventa un procedimiento comercial adicional a RD-UC54-001.

## Instalacion controlada (operador en el host aislado)

1. Descargar y extraer el artefacto en un directorio privado. Ejecutar `sha256sum -c SHA256SUMS` y comparar `manifest.json.application_sha` con el SHA revisado/run. Registrar checksum de `app.jar`, fecha y operador.
2. Crear cuenta de sistema sin login `ale-acceptance` y directorios `/opt/ale/acceptance`, `/etc/ale/tls`. Instalar `app.jar` en `/opt/ale/acceptance/app.jar`, legible por el servicio, sin otros JAR en esa ruta. Instalar la unidad en `/etc/systemd/system/ale-acceptance.service`.
3. Copiar `deploy/acceptance.env.example` a `/etc/ale/acceptance.env` con propietario root y modo 0600. Completar `ALE_PUBLIC_ORIGIN` sin barra final y `ALE_DB_*` en el host usando gestor/editor seguro. UC02/UC32 false, `SPRING_PROFILES_ACTIVE=prod`; no incluir secretos en terminal grabada, tickets ni PR. Validar DB vacia/aislada antes del primer arranque.
4. Instalar certificado en `/etc/ale/tls/fullchain.pem` y clave privada en `/etc/ale/tls/privkey.pem` con acceso limitado. Sustituir `@ALE_HOST@` en `deploy/ale-milanesas.nginx.conf.template`. Es una configuracion Nginx **completa** (incluye `http/events`), no un fragmento para `conf.d`; ensayar en una instancia dedicada, conservar configuracion anterior y usar `nginx -t` antes de aplicar. No pisar un servidor compartido.
5. Aplicar firewall a 443 y acceso administrativo restringido; 80 devuelve cierre 444 sin credenciales. `systemctl daemon-reload`; `systemctl start ale-acceptance`. Revisar estado y Flyway sin publicar logs crudos. Aplicar Nginx solo si su validacion y DB/app son correctas. `Restart=no`: un fallo del guard requiere diagnostico y reinicio manual.
6. Ejecutar y registrar la matriz de infraestructura del [runbook B4-02E](B4_02E_UC54_CONSULTA_COMERCIAL_Y_ACEPTACION_HUMANA_2026-11-01.md) y [B4-02D](B4_02D_GATE_OPERATIVO_Y_ACEPTACION_2026-11-01.md). No basta con observar health UP. Las interrupciones de PostgreSQL/segunda JVM solo en ventana de aceptacion, con restauracion/reinicio controlado.
7. Publicar en #28 URL HTTPS, SHA, operador, ventana y acta saneada. No declarar LISTO PARA EJECUTAR E2E hasta TLS/aislamiento y cuentas/canal confirmados. Al terminar, revocar sesiones de prueba y retirar las fixtures/host siguiendo el plan acordado. Para revertir: parar servicio, restaurar JAR/config previamente verificados y reiniciar; no ejecutar migraciones inversas improvisadas.

## Sesion humana: cuatro casos y evidencias

Usar cliente API interactivo aprobado que mantenga tokens/credenciales solo en memoria, con TLS verificado, sin verbose, historial, exportacion de cuerpos ni capturas de secretos. La ruta disponible es HTTP API; PR39 no entrega una pantalla B5. El login/formulario web existente permite ensayar CSRF/cookies; no confundir la API Bearer con un formulario UC54 inexistente. Si se exige aceptacion mediante pantalla administrativa completa, registrar dependencia B5 y no firmar ese alcance.

| Orden / caso | Actor y preparacion | Resultado esperado y evidencia |
| --- | --- | --- |
| 1 / A1 | Admin UC01 y GET `/api/v1/usuarios/proveedores?idUsuario=<objetivo>`; sin confirmacion. Probar tambien ID inexistente | UC54 con false → 400; inexistente con true → 409. Estado/roles/vinculo/flag/hash y sesiones sin cambio; comparar con snapshot **posterior al login**, no antes |
| 2 / A2 | Admin; objetivo no PROVEEDOR o fixture invalida admitida por MR5 | GET puede devolver lista vacia; POST UC54 true → 409; ninguna mutacion. No romper constraints para crear una variante |
| 3 / A3 | Admin ve identidad y cancela antes de confirmar | No se envia POST; snapshot de cuenta/sesiones permanece. Corroborar ausencia de llamada con evidencia del cliente saneada; access_log Nginx esta apagado. Fallo SQL/rollback: complementar con CI UC54, no inducir en host compartido |
| 4 / OK | Admin coteja CUIT/razon social y confirma; Proveedor recibe temporal por canal aprobado | UC54 204, misma identidad/roles/vinculo/estado, pendiente=true y sesiones antiguas revocadas. UC01 temporal 200/restringido, otras rutas 403, UC53 204/revoca todas, token anterior 401, nuevo UC01 200/operativo. Registrar entrega verificada **sin secreto** |

Los cuerpos/campos exactos de login/cambio/reset estan en `AuthInternaController` y contrato B4-02A. Comparar hashes solo en memoria/sesion privada del operador, registrar `hash_cambio=true/false` sin guardar el hash. El cotejo DB usa estado, flag, roles/vinculo y conteos de sesiones, nunca `SELECT *`. Aislar cada caso para que los cambios de OK no contaminen A1-A3. Completar el [acta UC54](B4_02E_ACEPTACION_HUMANA_UC54_2026-11-01.md) y el [acta de infraestructura](B9_01_ACTA_INFRAESTRUCTURA_2026-11-01.md).

## Salida y bloqueo actual

**#10:** cerrar solo con consulta visible validada y cuatro casos firmados con resultado/evidencia. **#28:** cerrar solo con #10 acreditado y todos los controles reales de infraestructura firmados. Una falla mantiene el gate abierto y origina correccion/reprueba especifica. No activar UC02/UC32, otras escrituras, integraciones reales ni modificar G0/MR5/F01 por disponer del paquete. BC06 permanece independiente, 0/18 E2E originales acreditados.

**Datos faltantes que desbloquean la ejecucion:** host/FQDN y acceso de operador por canal seguro; CA/DNS disponibles; cuatro roles humanos nominados (una persona puede asumir mas de un rol si se registra y acepta); horario de disponibilidad y canal confidencial de entrega ratificado. No enviar contrasenas, claves privadas ni tokens por GitHub o este chat. Hasta entonces: **PREPARADO PARA PROVISIONAR, NO DISPONIBLE COMO SERVICIO**.
