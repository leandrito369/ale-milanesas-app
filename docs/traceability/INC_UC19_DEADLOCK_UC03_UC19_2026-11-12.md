# INC-UC19 — Corrección propuesta del Deadlock UC-03 / UC-19

## 1. Identificación y Alcance

- **Código de Incidente:** `INC-UC19` (Deadlock de concurrencia entre UC-03 y UC-19).
- **Repositorio:** `leandrito369/ale-milanesas-app`.
- **Base inicial de referencia:** `main` @ `a07fd95bc0e8910c5b81efbe4253e959fc45e23f`.
- **SQLSTATE reproducido:** `40P01` (`deadlock_detected`).
- **Estado de INC-UC19:** CORREGIDO EN PR #86 (DRAFT) / NO INTEGRADO A `main` / CIERRE PENDIENTE DE REVISIÓN.
- **Estado de RT-UC07-04:** ABIERTA / PENDIENTE de coordinación global de operaciones pendientes (`UC-05`, `UC-06`, `UC-07`, `UC-41`).

---

## 2. Causa Raíz Observada (Corte 3 de RT-UC07-04)

En el análisis experimental de concurrencia del Corte 3 se reprodujo de forma determinista (3/3 ejecuciones) un deadlock `40P01` entre `CargarPedidoService` (`UC-03`) y `ActualizarPrecioVigenteService` (`UC-19`).

### Mecanismo de colisión:

1. `CargarPedidoService` (`UC-03`) adquiere en su flujo:
   - `SELECT pg_advisory_xact_lock(3003, 0)`
   - `SELECT pg_advisory_xact_lock(idCliente)` (lock transaccional a nivel de cliente)
   - `SELECT "estado" FROM "CLIENTE" WHERE "idCliente"=? FOR UPDATE`
   - Posteriormente, al congelar items: `SELECT "codigoUnidadVenta","activo" FROM "PRODUCTO" WHERE "idProducto"=? FOR SHARE`.
2. `ActualizarPrecioVigenteService` (`UC-19`) adquiere en su flujo:
   - `SELECT "idProducto","activo" FROM "PRODUCTO" WHERE "idProducto"=:id FOR UPDATE`
   - Posteriormente, para serializar generadores de IDs BIGINT: `SELECT pg_advisory_xact_lock(1919001)`.
3. Cuando una transacción de `UC-03` opera sobre un cliente con `idCliente = 1919001`:
   - `UC-03` adquiere `pg_advisory_xact_lock(1919001)` como lock de cliente (utilizando la función de 64 bits `bigint`).
   - `UC-19` adquiere `PRODUCTO FOR UPDATE` sobre el producto a actualizar.
   - `UC-03` intenta obtener `PRODUCTO FOR SHARE` sobre dicho producto y queda en espera del row lock retenido por `UC-19`.
   - `UC-19` intenta obtener `pg_advisory_xact_lock(1919001)` y queda en espera del lock advisory retenido por `UC-03`.
   - **Resultado:** Ciclo de espera circular irreversible. El motor de PostgreSQL detecta el deadlock (`40P01`) y aborta una de las dos transacciones.

---

## 3. Auditoría de Locks Advisory y Selección de Namespace

Se auditó la totalidad de locks advisory presentes en el repositorio `ale-milanesas-app`:

| Clave / Namespace | Servicio / Archivo | Propósito |
|---|---|---|
| `(9172001)` | `CambiarEstadoUsuarioService`, `CrearUsuarioService`, `GestionarAsignacionesRolesService`, `RestablecerAccesoUsuarioService`, `AsignarRepartoService` | Mutaciones globales de estado de usuario |
| `(60505, 7)` | `CambiarEstadoUsuarioService`, `GestionarAsignacionesRolesService`, `RestablecerAccesoUsuarioService`, `AsignarRepartoService`, `V1__baseline_mr5.sql` | Espacio compartido de asignaciones y roles |
| `(2929001)` | `CobroManualService` | Serialización de cobros manuales (UC-29) |
| `(5715001)` | `ConfigurarPoliticaCategoriaComercialService` | Políticas de categoría comercial (UC-57) |
| `(3202001)` | `GestionClientesService` | Serialización de alta/modificación de clientes (UC-32) |
| `(5051052)` | `GestionInsumosService` | Serialización de insumos (UC-50) |
| `(3030047)` | `GestionProductoService` | Serialización de productos (UC-30) |
| `(808, 0)` | `RegistrarAjusteInventarioService`, `RegistrarIngresoStockInsumoService`, `RegistrarIngresoStockReventaService`, `ConfigurarRecetaService`, `RegistrarPreparacionService` | Operaciones de producción, recetas e inventario |
| `(3003, 0)` | `CargarPedidoService` | Espacio global de carga de pedidos (UC-03) |
| `(idCliente)` (bigint) | `CargarPedidoService` | Exclusión por cliente |
| `(idElemento)` (bigint) | `RegistrarAjusteInventarioService`, `RegistrarIngresoStockInsumoService`, `RegistrarIngresoStockReventaService`, `RegistrarPreparacionService` | Exclusión por insumo/producto |

**Conclusión de la auditoría:**  
El par de enteros `(1919, 1)` **no se encuentra utilizado ni reservado** por ningún otro componente.  
En PostgreSQL, la función `pg_advisory_xact_lock(int, int)` opera en el namespace con `objsubid = 2`, el cual está físicamente aislado del namespace de enteros de 64 bits `pg_advisory_xact_lock(bigint)` (`objsubid = 1`). Por ende, `(1919, 1)` jamás puede colisionar con un `idCliente` de 64 bits, eliminando esta colisión específica sin certificar el orden global de todos los servicios.

---

## 4. Corrección Propuesta en PR #86 (pendiente de merge)

En `ActualizarPrecioVigenteService.java`:

```java
// ANTES:
sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(1919001)");

// DESPUÉS:
sql.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(1919,1)");
```

Se conserva estrictamente el orden de adquisición:  
`PRODUCTO FOR UPDATE → pg_advisory_xact_lock(1919, 1) → PRECIO_PRODUCTO FOR UPDATE`.

No se modificaron firmas G0, reglas de negocio, estructura de tablas ni lógica de generación de IDs BIGINT.

---

## 5. Verificación y Regresión Permanente

1. **Prueba de Regresión Específica:**  
   Se incorporó `src/test/java/ar/edu/unam/alemilanesas/maestros/PrecioProductoUc03DeadlockIntegrationTest.java`:
   - Configura un escenario con `idCliente = 1919001`.
   - Ejecuta los servicios reales con intercalado observable: UC-19 espera A2(1919,1) retenido por una tercera conexión después de bloquear PRODUCTO; UC-03 retiene A1(1919001) mientras espera PRODUCTO de UC-19. Comprueba `pg_locks`, `pg_stat_activity` y `pg_blocking_pids` antes de liberar el retenedor. La prueba falla si UC-19 vuelve al advisory anterior.
   - Verifica que ambas transacciones finalicen con éxito en tiempo acotado sin arrojar excepción `40P01` (`deadlock_detected`).
   - Verifica la persistencia íntegra de `PEDIDO`, `DETALLE_PEDIDO`, `PRECIO_PRODUCTO` (cierre de vigencia previa y apertura de nueva vigencia) y `AUDITORIA_PRECIO`.
   - Valida un replay de `UC-03` con `creado = false` y `cantidadItems = 1`, sin duplicar pedidos. **No agrega una línea técnica**; la regresión específica para `cantidadOriginal = 0` corresponde al test independiente de PR #85, ya fusionada.
2. **Preservación de Exclusión Mutua en Precios:**  
   La prueba existente `PrecioProductoConcurrenciaIntegrationTest.java` continúa verificando que dos altas simultáneas de precios para el mismo producto se serialicen limpiamente bajo el nuevo namespace `(1919, 1)`.

---

## 6. Estado de Coordinación

- `INC-UC19`: corrección implementada en PR #86 y validada por CI anterior; cierre formal sujeto a revisión, validación del HEAD definitivo e integración en `main`.
- `RT-UC07-04`: Permanece **ABIERTA** hasta la integración y prueba de concurrencia de los restantes casos de uso del ciclo de vida (`UC-05`, `UC-06`, `UC-07`, `UC-41`).

## 7. Límite de evidencia y continuidad

El Corte 3 informó 3/3 reproducciones de `40P01` en la versión anterior y seis intercalados correctos con el namespace separado. La prueba permanente de esta PR no reejecuta el código anterior: observa el protocolo nuevo y falla si UC-19 usa el namespace viejo. `RT-UC07-04` sigue ABIERTA para UC-05/UC-06/UC-07/UC-41 y los locks implícitos.
