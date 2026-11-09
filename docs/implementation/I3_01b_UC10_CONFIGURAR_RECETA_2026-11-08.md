# I3-01b — UC-10 configurarRecetaProducto: B3 y B4 HTTP

Fecha de implementación: 08/11/2026. Base `main`: `aae5c3c`, posterior a UC-09.

## Alcance y fuentes cotejadas

`ConfigurarRecetaService.configurarRecetaProducto(UUID, DatosReceta)` y
`POST /api/v1/productos/{idProducto}/receta`. Operación exclusivamente
ADMINISTRADOR, con triple gate no-prod. No implementa UC-28 ni escribe stock,
partidas, movimientos o preparaciones. Conserva V1/V2/V3 y servicios/controllers
existentes, incluidos UC-09 y servicios de maestros y pedidos.

G0 permanece aprobado e inmutable según [source-lock](../traceability/source-lock.md):
`source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`,
`governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`.
Fuentes exactas en `leandrito369/ale-milanesas`:

- `docs/08-diagramas/revision/REV06B_CLIENTES_RECETAS_INVENTARIO/propuestos/CONTRATOS_REV06B_TRECE_CORREGIDOS.md`, §9, firma, A1–A4 y postcondiciones.
- `docs/08-diagramas/revision/REV06B2_RECETAS_INVENTARIO/propuestos/DS_REV06B2_RECETAS_INVENTARIO_UC09_10_31_36_52_64.drawio.xml` y `DSS_REV06B2_RECETAS_INVENTARIO_UC09_10_31_36_52_64.drawio.xml`, mensajes UC-10 del manifiesto G0.
- `docs/04-casos-uso/CASOS_DE_USO_64_TRES_NIVELES.docx`, ficha Real UC-10.
- `docs/01-gobierno/b0/B0_MATRIZ_RBAC_67_OPERACIONES_CIERRE_2026-10-29.csv`, UC-10: acceso directo ADMINISTRADOR; ningún otro rol hereda permiso.
- `docs/01-gobierno/b0/RD_I301_001_DECISIONES_I3_01_RECETAS_PREPARACION_2026-11-11.md`, §2 D-U10-1 y D-U10-2, merge `1f6a4c7`.
- `docs/01-gobierno/b0/RD_UC19_002_ACOTAMIENTO_VIGENCIAS_FUTURAS_2026-11-01.md`, patrón de acotamiento prospectivo; para recetas prevalece el alcance específico de RD-I301-001 §2.
- `docs/01-gobierno/RD_RP022_001_COSTEO_CONVERSIONES_REDONDEOS_2026-10-27.md`, unidades y aritmética decimal; se preservan las capacidades NUMERIC físicas de V1.

No requiere ni crea una RD nueva; no modifica contratos, diagramas ni source-lock.

## Diagnóstico previo

App `origin/main=aae5c3c`; gobierno `origin/main=1f6a4c7`. El paquete contenía
ModuleBoundary y ConsultarStockService, sin servicio ni endpoint de recetas.
RECETA tiene UNIQUE(idProducto); VERSION_RECETA tiene PK(idReceta,numeroVersion)
y GiST `ex_version_receta_no_solape`; DETALLE_VERSION_RECETA tiene
PK(idReceta,numeroVersion,idInsumo). Cantidades NUMERIC(18,3), unidades VARCHAR(20),
vigencias TIMESTAMPTZ. Se cotejó el patrón de ActualizarPrecioVigenteService sin
modificarlo. Docker disponible; PostgreSQL 17.11 real en tests.

## Decisiones de implementación

| ID | Decisión | Justificación |
|---|---|---|
| D1 | POST `/api/v1/productos/{idProducto}/receta` | Contrato sin ruta HTTP; cada confirmación crea una versión nueva. |
| D2 | ID en path; body con cinco campos | cantidadRendimientoBase, codigoUnidadRendimiento, vigenteDesde, vigenteHasta opcional, itemsInsumo. El encargo decía seis, pero firma y ejemplo contienen cinco sin ID. |
| D3 | Intervalos semiabiertos [desde,hasta), hasta nullable y estrictamente posterior | Contrato A3 y GiST V1. Se comparan instantes tras normalizar a microsegundos. |
| D4 | Unidades idénticas al maestro | RD-I301-001 D-U10-1, sin conversiones implícitas. |
| D5 | Acotar únicamente la versión ya vigente en curso; rechazar solape futuro | RD-I301-001 D-U10-2, ver sección siguiente. |
| D6 | RECETA MAX+1 global; numeroVersion MAX+1 por receta | PK/UNIQUE existentes y lock asesor (808,0). RECETA estable entre versiones. |
| D7 | Insumos existentes y activos | A2; bloqueados FOR SHARE por ID ascendente. Producto ELABORADO activo FOR UPDATE. |
| D8 | Insumos distintos | PK del detalle; duplicados se rechazan antes de escribir. |
| D9 | Un solo clock_timestamp() después de adquirir locks | Referencia común para no retroactividad y distinguir versión vigente/futura. El cierre usa exactamente el mismo vigenteDesde normalizado que el nuevo INSERT. |

## Acotamiento de vigencias (D-U10-2)

Se truncan fechas de entrada a microsegundos **antes** de comparar o escribir.
Si el intervalo se vuelve vacío, se rechaza con 400 SOLICITUD_INVALIDA.
Se obtiene un único `clock_timestamp()` tras los locks y se exige
`nueva.vigenteDesde >= ahora`; retroactividad → 400 VIGENCIA_RETROACTIVA.

Se bloquean todas las versiones de la receta con FOR UPDATE. Dos intervalos
solapan si cada inicio es anterior al final del otro (NULL equivale a infinito).
Una versión ya vigente que cubra el nuevo inicio puede acotarse exactamente a
ese inicio. Se conserva su número, rendimiento, detalles y fecha inicial.
Se inserta otra versión y sus detalles; jamás se sobrescriben los detalles
históricos ni se cambian referencias de PREPARACION.

La RD específica distingue una versión **ya vigente** de una **aún futura**.
Una programada que solape se rechaza con 409 SOLAPE_VERSION_FUTURA, incluso
si su inicio es anterior al nuevo inicio. Esto es más restrictivo que UC-19,
cuya RD permite acortar también ciertos precios futuros. Se aplica aquí
RD-I301-001 §2, no se copia esa excepción de precios al dominio de recetas.
La consulta del pseudocódigo que solo detectaba futuros con inicio >= nuevo
inicio era insuficiente para cubrir esa protección completa.

Se permiten intervalos adyacentes y huecos sin solape: un futuro posterior al
fin propuesto no impide crear una versión anterior finita. GiST permanece
intacto como defensa relacional. Ante cualquier error se revierte receta nueva,
cierre previo, versión y todos sus detalles juntos.

## Validación de unidades (D-U10-1) y precisión

Rendimiento debe coincidir con PRODUCTO.codigoUnidadVenta; cada detalle con
INSUMO.codigoUnidadMedida. Ambos desacuerdos → 400 UNIDAD_NO_COINCIDE.
No se inventan factores ni tablas de conversión. Insumos inactivos/inexistentes
y productos REVENTA/inactivos/inexistentes se rechazan sin cambios.

Las cantidades usan BigDecimal positivo, escala <= 3 y como máximo 15 dígitos
enteros (NUMERIC(18,3)). Se rechaza exceso de escala/rango; no se redondea ni
trunca una cantidad para que quepa. Se prueba el máximo 999999999999999.999.
Unidades no vacías de hasta 20 caracteres, sin NUL, según columnas reales.

## Concurrencia, autorización y HTTP

Orden: autorización → validación → pg_advisory_xact_lock(808,0) → PRODUCTO
FOR UPDATE → INSUMO FOR SHARE por ID ascendente → revalidar autorización →
clock_timestamp() → versiones FOR UPDATE. El lock global serializa MAX+1;
el row lock del producto coordina identidad, estado y unidad con sus maestros.
Las lecturas de insumos mantienen activo/unidad estables hasta el commit.
No se modifica ningún servicio existente.

Whitelist estricta de cinco campos de body y tres por item. Parser decimal
exacto; rechaza duplicación de claves JSON, tokens adicionales, tipos erróneos,
campos desconocidos, IDs no enteros/fuera de Long y fechas sin offset/ inválidas.
ID de producto no es aceptado en body. vigenteHasta puede omitirse o ser null.

201 devuelve el record RecetaRegistrada de ocho campos, versionCerrada nullable.
Errores: 400 SOLICITUD_INVALIDA, PRODUCTO_NO_ES_ELABORADO, PRODUCTO_INACTIVO,
INSUMO_INACTIVO, UNIDAD_NO_COINCIDE o VIGENCIA_RETROACTIVA; 404
PRODUCTO_INEXISTENTE/INSUMO_INEXISTENTE; 409 SOLAPE_VERSION_FUTURA; 403
OPERACION_PROHIBIDA; 503 PERSISTENCIA_NO_DISPONIBLE sin texto SQL.
Sin Bearer, cookie sola o token revocado → 401. Todos usan no-store.
Se sigue el mapeo específico del advice solicitado (404 para inexistentes),
aunque el flujo B3 del encargo etiquetaba producto inexistente como 400.

Triple gate: bandera `recetas-uc10-enabled=false` por defecto, controller
`@Profile("!prod")` y `@ConditionalOnProperty`, más veto de bandera en
PrecondicionesDespliegue. Matcher POST exclusivo ADMINISTRADOR usa el helper
existente administradorAutenticado. UC-09 GET sigue sin gate y no se altera.

## Matriz CU → servicio → DB → API/UI → test

| CU | Servicio | DB | API/UI | Tests |
|---|---|---|---|---|
| UC-10 configurarRecetaProducto | ConfigurarRecetaService | Lee PRODUCTO/INSUMO; crea/recupera RECETA; acota/inserta VERSION_RECETA y DETALLE_VERSION_RECETA | POST /api/v1/productos/{idProducto}/receta; sin UI nueva | ConfigurarRecetaServiceIntegrationTest (13), ConfigurarRecetaHttpExternoTest (8) |
| Regresiones | Servicios existentes intactos | Sin cambios de esquema | UC-09, autenticación, precondiciones | ConsultarStockServiceIntegrationTest, AutenticacionHttpExternaTest, PrecondicionesDespliegueTest |

## Resultados reproducibles

Java 21, Maven, Testcontainers `postgres:17.11-alpine`. Fixture nuevo reutiliza
IngresoReventaFixture sin editarlo. Datos confirmados; cada llamada al servicio
abre su propia transacción. La prueba de acotamiento siembra una versión ya
vigente como dato histórico; no utiliza UC-10 para insertar retroactividad.
La prueba de identidad estable lanza dos confirmaciones concurrentes sin receta
previa, comprueba una única RECETA y versiones distintas {1,2}, con intervalos
adyacentes. El rollback se fuerza con trigger de fallo sintético en el segundo detalle,
exclusivamente en el contenedor: verifica tanto receta recién creada como cierre
previo revertido. Se elimina el trigger al terminar. No hay escrituras externas.

| Verificación | Resultado |
|---|---|
| mvn -B -ntp test-compile | BUILD SUCCESS |
| B3 UC-10 | 13/13 |
| B4 UC-10 | 8/8 |
| Regresión UC-09 | 10/10, test intacto |
| Regresión autenticación y gates | 11/11 + 26/26, tests intactos |
| Total focalizado | 68/68; cero fallos, errores o skips |

Workflow: `.github/workflows/i3-01b-recetas-http-focused.yml`.

```bash
mvn -B -ntp test-compile
mvn -B -ntp -Dtest=ConfigurarRecetaServiceIntegrationTest,ConfigurarRecetaHttpExternoTest,ConsultarStockServiceIntegrationTest,AutenticacionHttpExternaTest,PrecondicionesDespliegueTest test
```

## Bloqueo y aceptación

[Issue #28](https://github.com/leandrito369/ale-milanesas-app/issues/28) permanece
abierta. PR draft para revisión humana, sin merge ni despliegue. No implementa
UC-28 ni acredita aceptación humana BC-06.
