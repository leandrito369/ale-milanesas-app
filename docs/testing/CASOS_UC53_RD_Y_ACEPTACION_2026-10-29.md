# Plan de casos UC-53 — Cambiar Contraseña

**Fuente:** UC53 REV02 PATCH `RD-UC53-001` (repositorio documental, commit `3cf4b19078f02a374229012cbf8d278b4bb58196`) y `Clase Testing.md`.  
**IC activa:** [IC-UC53-MR5-002](https://github.com/leandrito369/ale-milanesas/issues/30), la contraseña cambia pero MR5 revoca todas las sesiones, incluso la de origen.
**Estado:** casos definidos en PR, marcar PASS solo después del run real. No hay API/GUI B4.

## Tabla de casos derivada de requisitos (caja negra y equivalencia)

| Caso | RD/Alternativa | Entrada y contexto | Resultado esperado | Nivel / automatización |
|---|---|---|---|---|
| T-UC53-01 | Camino normal restringido, RD-UC53 | Usuario PROVEEDOR pendiente=true, sesión válida, actual ausente, nueva confirmada | Hash sustituido, pendiente=false, Roles conservados | Integración PostgreSQL |
| T-UC53-02 | Camino normal voluntario | Usuario interno pendiente=false, actual coincide y nueva confirmada | Hash sustituido, pendiente=false, Roles conservados | Integración PostgreSQL |
| T-UC53-A1a | A1, sesión revocada/expirada | Sesión no vigente | No mutación | Integración PostgreSQL |
| T-UC53-A1b | A1, cuenta DESHABILITADO | Sesión existente de cuenta inactiva | No mutación | Integración PostgreSQL |
| T-UC53-A2a | A2, actual omitida/no coincide | Usuario pendiente=false | No mutación | Unidad e integración |
| T-UC53-A2b | A2, temporal reutilizada | Usuario pendiente=true y nueva=clave temporal vigente | No mutación | Unidad e integración |
| T-UC53-A2c | A2, confirmación diferente o ausente | Cualquier cuenta | No mutación | Unidad e integración |
| T-UC53-L01 | Límite técnico BCrypt | Nueva 72 bytes / 73 bytes / multibyte 74 bytes | 72 aceptada, 73+ rechazada sin truncar | Unidad y parte integración |
| T-UC53-SEC | Trigger MR5 | Cambio exitoso con varias sesiones activas | Todas las sesiones revocadas **según MR5 actual**; requiere resolución IC | Integración PostgreSQL |
| T-UC53-A3 | A3 cancelación o fallo persistencia | Cancelación previa o fallo SQL provocado | Mantener hash/indicador previo | **Pendiente** de prueba de sistema / inyección de fallo |
| T-UC53-AC01 | Aceptación con usuario | Sesión restringida B4/UI, cambio, nuevo inicio de sesión | El responsable ratifica el comportamiento y experiencia | **NO EJECUTADO: falta B4 e IC** |

## Estructura AAA y evidencia

Tests de unidad `PoliticaCambioCredencialTest` (sin Spring ni PostgreSQL), con casos de frontera y particiones. Integración `CambiarContrasenaServiceIntegrationTest` ejecutada contra PostgreSQL y migraciones reales. Resultado final en Surefire y JaCoCo adjunto a GitHub Actions.

**Revisión crítica:** los casos T-UC53-SEC y T-UC53-AC01 no tienen postcondición documental completamente conciliada. Se ensaya la consecuencia física vigente **sin cambiar el contrato ni conceder acceso de sesión**. El CU no alcanza aceptación completa hasta que se cierre la IC.
