# B4-02E — Acta de aceptación humana de UC-54

**Issue origen:** [#10](https://github.com/leandrito369/ale-milanesas-app/issues/10).  
**Gate productivo:** [#28](https://github.com/leandrito369/ale-milanesas-app/issues/28).  
**Referencia del procedimiento:** [B4-02E UC54 Consulta Comercial y Aceptación Humana](B4_02E_UC54_CONSULTA_COMERCIAL_Y_ACEPTACION_HUMANA_2026-11-01.md).  
**Estado:** **NO EJECUTADO, 0/4 E2E humanos acreditados**. Esta plantilla no es un acta firmada.

## Metadatos que debe completar el responsable

| Campo | Valor/evidencia |
| --- | --- |
| Fecha/hora y zona horaria de ejecución | **PENDIENTE** |
| Nombre/rol de Administrador verificador | **PENDIENTE** |
| Responsable de seguridad o testigo | **PENDIENTE** |
| Ambiente (staging / producción) y FQDN real | **PENDIENTE** |
| Commit exacto desplegado y migraciones MR5/F01 | **PENDIENTE** |
| Certificado TLS real validado y evidencia de red | **PENDIENTE** |
| Canal aprobado de entrega temporal y doble verificación de destinatario | **PENDIENTE** |
| Ruta segura del archivo de evidencias anonimizadas (no Git si contiene secretos) | **PENDIENTE** |

## Matriz de ejecución E2E con evidencia obligatoria

| ID | Procedimiento humano y expectativa | Status | Captura/consulta DB saneada | Aprobación |
| --- | --- | --- | --- | --- |
| E2E-UC54-A1 | Administrador localiza Usuario/Proveedor, no confirma identidad o idUsuario inexistente. POST false → **400**; inexistente → **409** con servicio actual. Hash, pendiente y sesiones sin cambio. | **NO EJECUTADO** | PENDIENTE | PENDIENTE |
| E2E-UC54-A2 | Admin prueba rol no PROVEEDOR, mixto o vínculo inválido; POST UC54 **409** sin mutar credencial/roles/vínculo/sesiones. | **NO EJECUTADO** | PENDIENTE | PENDIENTE |
| E2E-UC54-A3 | Cancelación humana tras visualizar CUIT/razón social y **antes** de enviar POST: ninguna mutación. Evidencia técnica complementaria de trigger SQL debe realizarse en BD de test aislada: HTTP **503**, rollback. | **NO EJECUTADO** | PENDIENTE | PENDIENTE |
| E2E-UC54-OK | Admin coteja CUIT y razón social mediante canal comercial verificado, confirma UC54 true (**204**), entrega temporal al Proveedor verificado por canal seguro no logueado, sesión anterior 401, UC01 temporal restringido, otras rutas 403, UC53 cambia clave y revoca todas, UC01 nueva clave operativo. | **NO EJECUTADO** | PENDIENTE | PENDIENTE |

## Evidencia que no se debe almacenar

No capturar ni incorporar a Git: contraseñas temporales o definitivas, cabecera Authorization, tokens Bearer, cookie de sesión, `credencialProtegida`, `tokenProtegido`, cuerpo JSON que contenga `credencialTemporal`, credenciales de PostgreSQL, claves privadas TLS, CUIT/teléfonos/emails reales sin anonimizar o detalles identificatorios de la verificación comercial.

Registrar **códigos HTTP**, `Cache-Control: no-store`, orden de acciones, marca horaria, valores booleanos del indicador pendiente, conteos de sesiones revocadas y un indicador de comparación de hash **sin publicar el hash**. En la rama de solo lectura propuesta, el Administrador usa `GET /api/v1/usuarios/proveedores?idUsuario=<id>` o filtros por CUIT/nombre, siempre con token ADMINISTRADOR; el JSON no reemplaza la comprobación comercial en un canal autorizado.

## Dictamen final (a completar; no presuponer)

- A1: **NO EJECUTADO**
- A2: **NO EJECUTADO**
- A3: **NO EJECUTADO**
- OK: **NO EJECUTADO**
- E2E acreditados: **0/4**
- Canal de entrega confidencial verificado: **PENDIENTE**
- Infraestructura TLS real y singleton: **PENDIENTE**
- Dictamen humano: **NO EMITIDO**
- Responsable / firma / fecha: **PENDIENTES**

**No cerrar Issue #10 ni Issue #28 por la creación de este archivo, por pasar CI o por integrar únicamente la consulta GET.**
