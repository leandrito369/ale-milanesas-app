# I1-01 / B4 — UC-17 Crear Usuario HTTP (incluye UC-18 inicial)

**Fecha:** 2026-11-05. **Estado:** IMPLEMENTACIÓN EN REVISIÓN; COMPILACIÓN LOCAL NO EJECUTADA; CI Y APROBACIÓN PENDIENTES.
**Rama:** `feat/i1-01-b4-uc17-crear-usuario-http-2026-11-05` desde `main` @ `7598d854e38b850634658304df7aeec7bb6290db`.
**Issue:** [#56](https://github.com/leandrito369/ale-milanesas-app/issues/56).
**G0:** `source_commit=1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`; `governance_commit=27738b089afdd2698cdae915f75c0e8089a6248a`. Ambos inmutables.

## Fuentes, alcance y trazabilidad

- UC-17: `crearUsuario(nombreUsuario, credencialInicial, rolesIniciales, idProveedor[0..1])` incluye la asignación **inicial** de UC-18. No implementa su canal posterior autónomo.
- Contrato: `docs/08-diagramas/revision/REV02_SEGURIDAD/propuestos/contratos/CONTRATO_UC17_REV02.md`; DSS/DS REV02 en `leandrito369/ale-milanesas`.
- Decisiones ratificadas: `RD-UC17-001`, `RD-UC18-001` e `IC-UC18-MR5-001`; PR documental #29 @ `3cf4b19078f02a374229012cbf8d278b4bb58196`.
- RBAC: solo ADMINISTRADOR, con sesión Bearer y revalidación de rol en B3. Se reutiliza `CrearUsuarioService.crearUsuario`, sin alterar regla de negocio.
- MR5: `USUARIO`, `ROL`, `USUARIO_ROL`, `EVENTO_ASIGNACION_ROL`, `USUARIO_PROVEEDOR`, `PROVEEDOR` y `SESION_USUARIO`. Sin DDL, migraciones ni cambio a `source-lock.md`. El RP de `PARTIDA_INSUMO` continúa separado.

## Contrato HTTP propuesto

**`POST /api/v1/usuarios`** · `Content-Type: application/json` · `Authorization: Bearer` válido.

| Entrada | Tipo | Regla |
|---|---|---|
| `nombreUsuario` | cadena obligatoria | Nombre válido, no duplicado |
| `credencialInicial` | cadena obligatoria | Hash BCrypt en B3, hasta 72 bytes; nunca se devuelve ni registra |
| `rolesIniciales` | arreglo obligatorio | Al menos un código de Rol vigente; sin duplicados ni mezcla con PROVEEDOR |
| `idProveedor` | entero positivo opcional | Solo con Rol exclusivo PROVEEDOR, comercio ACTIVO elegible |

La respuesta **201** expone **solo cuatro campos**: `idUsuario`, `estado`, `cambioCredencialPendiente` y `roles`. Sin nombre, credencial, hash, datos comerciales o información de sesión. Los códigos de error neutros propuestos son **400** `SOLICITUD_INVALIDA`, **401** `SESION_INVALIDA`, **403** `OPERACION_PROHIBIDA` y **503** `PERSISTENCIA_NO_DISPONIBLE`. No se revelan SQL ni identidades duplicadas. `Cache-Control: no-store`.

## Seguridad no productiva

1. Bandera `ale.http.writes.usuarios-uc17-enabled=false` por defecto; seguridad HTTP rechaza POST si está apagada.
2. Controlador `@Profile("!prod")`, que no carga en producción.
3. `PrecondicionesDespliegue` veta el arranque productivo si se intenta activar la bandera.

Se mantiene la pausa de despliegue (Issue #28). La inclusión de UC-18 es transaccional: una asociación y un evento `ASIGNACION` por Rol inicial; no agrega endpoint de gestión de Roles.

## Pruebas previstas, aún no ejecutadas

| Artefacto | Verificación | Estado |
|---|---|---|
| `RegistroUsuarioHttpExternoTest` | HTTP real, alta interna y Proveedor, proyección de cuatro campos, hash, eventos, negativos, RBAC, revocación y concurrencia de nombre duplicado | NO EJECUTADO |
| `PrecondicionesDespliegueTest` | Bandera prohibida y controlador ausente en perfil prod | NO EJECUTADO |
| CI B1/B2/B4 | Compilación, Testcontainers PostgreSQL 17.11 y regresión/Flyway | NO EJECUTADO |

Este entorno no dispone de Maven, compilador Java ni Docker. Lean ratificó el 05/11/2026 una **regla general para próximos cortes de I1**: publicar Issues y PR en borrador con compilación local pendiente y delegar pruebas a GitHub Actions. No declarar pruebas aprobadas hasta sus resultados y **no fusionar ni desplegar** sin CI verde, revisión humana y aprobación explícita. Pendientes posteriores: UC-18 autónoma, UC-55, Precios, Insumos, Categorías, E05/OpenAPI y E2E humanos.
