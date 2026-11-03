# B4-03-04 / UC30 — Alta de Producto HTTP bajo gate no-prod

**Estado inicial:** implementacion propuesta en PR independiente; CI del HEAD pendiente hasta evidencia. **Issue:** [#43](https://github.com/leandrito369/ale-milanesas-app/issues/43). **Base:** main `5b0fdbd224bb86f156330e96cdc200802b9c855f` (UC38/PR42 integrada, B1 201/201). **Despliegue PAUSADO por instruccion del responsable del 02/11/2026**; #28 permanece ABIERTA.

## Fuentes revisadas y alcance

G0 `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, gobierno `27738b089afdd2698cdae915f75c0e8089a6248a`. Manifiesto canonico: UC30, ficha Fase4 de tres niveles, contrato `docs/08-diagramas/consolidacion-b0/CONTRATOS_REV06A_NUEVE_OPERACIONES_B0_ACTUALIZADO.md` §2; DSS `REV06A_COMERCIAL_PROMOCIONES/propuestos/DSS_REV06A_CORREGIDO.drawio.xml` y DS `DS_REV06A_COMPLETO_CORREGIDO.drawio.xml` en G0. Revisados mensajes de alta, validacion, unicidad entre activos/inactivos, registro activo y excepcion de duplicado.

Adenda posterior **RD-UC30-001**, commit documental `4971625a215d94a8b9f6439d9b0836e0925ea1ad`: identidad estable, nombre normalizado unico/modificable por UC47, integridad historica y autorizacion transaccional; **no existe evento de auditoria de alta/actor/fecha en MR5** y no se simula. RBAC UC30 ADMIN D; demas NR. UC35/BC07 permiten solo proyeccion minima del Producto coincidente. Esta consulta es contextual al rechazo del alta, no una ruta general UC35.

## Contrato HTTP de prueba

`POST /api/v1/productos`, `Content-Type: application/json`, Bearer ADMINISTRADOR vigente y sin cambio pendiente.

```json
{"nombre":"Milanesa especial","descripcion":"Ficha opcional","tipoProducto":"ELABORADO","codigoUnidadVenta":"UN"}
```

`nombre`, `tipoProducto`, `codigoUnidadVenta` son strings obligatorios; descripcion string opcional/null. Lista blanca: rechazar propiedades adicionales, tipos JSON distintos y datos invalidos. Se conserva validacion B3: nombre hasta 150, descripcion hasta 10000, unidad hasta 20 y existente en UNIDAD_MEDIDA, tipo ELABORADO|REVENTA. Se rechaza NUL en descripcion, no representable por PostgreSQL TEXT. La normalizacion B3 sigue trim + espacios repetidos + minusculas Locale.ROOT, sin transliterar.

| Resultado | HTTP y cuerpo minimo | Efecto |
| --- | --- | --- |
| Alta | 201 `idProducto, activo=true` | Solo PRODUCTO; sin precio, receta, auditoria de precio ni movimientos/valoraciones; stock/costo NULL |
| Duplicado normalizado activo/inactivo | 409 `codigo=PRODUCTO_DUPLICADO`, `coincidencia={idProducto,nombre,descripcion,tipoProducto,codigoUnidadVenta,activo}` | Cero filas nuevas; no muestra costos/stock ni normalizacion interna |
| Formato, campo extra, unidad/tipo invalidos | 400 `SOLICITUD_INVALIDA` | Cero altas |
| Bearer ausente/invalido/revocado | 401 | Cero altas; cookies no autentican API |
| Rol no ADMIN, pendiente o bandera false | 403 | Cero altas |
| Fallo PostgreSQL | 503 `PERSISTENCIA_NO_DISPONIBLE` | Rollback; no SQL/mensaje privado en cuerpo |

Todas las respuestas de esta ruta son `Cache-Control: no-store`. El token determina sesion/actor; nunca JSON, query, cookie ni UUID de sesion aislado. No se informa que un Producto activo sin precio sea comercializable: UC19 sigue siendo una operacion independiente.

## Persistencia, permisos y diff

`RegistroProductoHttpController` delega a `GestionProductoService.gestionarProducto`. Se conservan transaccion B3, revalidacion ADMIN antes/despues de `pg_advisory_xact_lock(3030047)`, MAX(id)+1 cooperativo y UNIQUE(nombreNormalizado). La modificacion B3 es una excepcion tipada `ProductoDuplicadoException`, subtipo de IllegalArgumentException, que transporta la proyeccion segura leida en esa misma transaccion; no se extrae el ID de un mensaje, ni se hace una consulta posterior con distinta autorizacion. UC47 conserva rechazo del duplicado como IllegalArgumentException; no se habilita HTTP UC47.

`ale.http.writes.productos-uc30-enabled=false` por defecto, `@ConditionalOnProperty` y `@Profile("!prod")`. Spring Security exige bandera y ADMIN para POST exacto. `PrecondicionesDespliegue` rechaza prod con flag true; la unidad de aceptacion tambien fija UC30=false. UC02/UC32/UC38 no se activan y `anyRequest().denyAll()` sigue cerrando lo demas. No hay nuevas GET/PUT/PATCH/DELETE de Productos. MR5/F01 y G0 sin cambios.

## Matriz de trazabilidad y verificacion

| CU / fuente | Servicio / DB | API | Prueba y evidencia |
| --- | --- | --- | --- |
| UC30 alta / REV06A §2 + RD-UC30-001 | GestionProductoService; PRODUCTO, UNIDAD_MEDIDA; USUARIO/ROL/SESION | POST productos | RegistroProductoHttpExternoTest: ambos tipos, campos/normalizacion y sin efectos ficticios |
| UC30 A2 / UC35 contextual + BC07 | Excepcion tipada con seis campos de PRODUCTO | 409 coincidencia | Duplicado activo/inactivo, sin nueva fila ni campos sensibles |
| ADMIN y gate no-prod | SeguridadHttpInterna + revalidacion B3 + PrecondicionesDespliegue | 401/403, controlador ausente prod | Roles internos/Proveedor/pendiente, cookie/UUID, revocacion/cambio rol; flag false y contexto prod |
| UC30 transaccion / concurrencia | Lock 3030047, UNIQUE MR5 | 503 o carrera 201/409 | Trigger AFTER INSERT solo en Testcontainers; rollback y reintento; dos HTTP simultaneos |
| Regresion I1/B4 y esquema | UC02/32/38/53, CSRF, MR5/F01/MR6 | Rutas existentes | B1/B2, suites focalizadas, Surefire/JaCoCo y SonarCloud del HEAD final |

CI focalizada usa puerto real `RANDOM_PORT` y PostgreSQL Testcontainers; sus tokens/usuarios son sinteticos. No es E2E humano, TLS publico ni evidencia de despliegue. El entorno local del asistente no tiene Maven/Docker/JDK21: resultados de compilacion se atribuyen a CI, no se inventa ejecucion local. Sonar cobertura y JaCoCo se informan por separado segun sus valores reales.

**Salida:** PR revisable con gates del mismo HEAD y decision de integracion por responsable. Mantener despliegue pausado, #28 ABIERTA, UC54 humana 0/4 y BC06 original 0/18 E2E. No cambiar documentos normativos ni crear DDL/auditoria. Proximo corte de Productos a evaluar: consulta UC35 y luego UC47/48, sin acreditarlos por esta alta.
