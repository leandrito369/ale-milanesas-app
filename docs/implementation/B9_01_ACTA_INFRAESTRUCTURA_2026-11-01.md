# B9-01 — Acta de infraestructura real (plantilla sin firma)

**Estado inicial:** NO EJECUTADA / NO CERTIFICADA. Seguimiento #28; coordinador `leandrito369`. Paquete/procedimiento: [B9-01 entorno de aceptacion](B9_01_ENTORNO_ACEPTACION_UC54_2026-11-01.md).

| Metadato | Valor |
| --- | --- |
| FQDN / host aislado / IP y estacion externa | PENDIENTE |
| SHA desplegado + checksum JAR + URL CI | PENDIENTE |
| Operador / rol / fecha-hora-zona | PENDIENTE |
| Revisor / rol / fecha-hora-zona | PENDIENTE |
| Versiones JDK / Nginx / PostgreSQL | PENDIENTE |
| DB de aceptacion / Flyway V1-V2 / huella B2 | PENDIENTE |
| Ventana autorizada para interrupciones / recuperacion | PENDIENTE |

| Control | Evidencia saneada requerida | Resultado | Responsable / fecha |
| --- | --- | --- | --- |
| DNS y certificado CA | Resolucion desde estacion externa, SAN/FQDN, cadena confiable, fechas; sin `curl -k` | NO EJECUTADO | PENDIENTE |
| TLS1.2 y TLS1.3 | Handshake externo de ambas versiones validando cadena y hostname | NO EJECUTADO | PENDIENTE |
| Nginx/HSTS/HTTP claro | `nginx -t`, HSTS HTTPS, cierre de HTTP sin enviar credenciales | NO EJECUTADO | PENDIENTE |
| Red y firewall IPv4/IPv6 | `ss -tulpn` en host y prueba externa; Java 127.0.0.1:8080, 8080/5432 inaccesibles desde fuera | NO EJECUTADO | PENDIENTE |
| IP y rate limit | Configuracion viva reemplaza X-Forwarded-For; rafaga sintetica controlada → 429, cabecera falsificada no cambia origen; cuota por cuenta | NO EJECUTADO | PENDIENTE |
| Guard singleton | `pg_locks` classid=4402/objid=2026; segunda JVM falla; perdida lock → 503/fail-closed; reinicio manual restaurado | NO EJECUTADO | PENDIENTE |
| Cookies/CSRF/no-store | Secure/HttpOnly/SameSite, Origin y rechazo CSRF web, API no acepta cookie; sin secretos en evidencias | NO EJECUTADO | PENDIENTE |
| Actores/canal/UC54 | Nominaciones y canal ratificado, enlace a acta humana con 4/4 acreditados | NO EJECUTADO | PENDIENTE |
| Cierre operativo | Sesiones de prueba revocadas, restauracion confirmada, acceso/fixtures retirados segun acuerdo | NO EJECUTADO | PENDIENTE |

**Dictamen humano:** NO EMITIDO. **Firma y fecha:** PENDIENTES. **Evidencias/repruebas:** PENDIENTES.

No guardar credenciales, hashes, cookies, tokens, clave TLS, datos personales ni logs crudos. Conservar pruebas saneadas por control. El certificado efimero del workflow Nginx y el artefacto B1 no certifican este host. Ninguna firma ni resultado se completa por inferencia.
