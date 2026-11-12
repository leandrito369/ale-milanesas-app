# I3-02 Pre-Fix — UC-03 CargarPedidoService: Filtrar líneas técnicas en cantidadItems del replay idempotente

Fecha: 10/11/2026.
Base `main`: `d5f7607`
Rama: `fix/uc03-cantidaditems-lineas-tecnicas-2026-11-10`
Estado: Implementado — Compilación exitosa — Pruebas delegadas a CI GitHub Actions.

## 1. Contexto y Alcance

En el marco de la preparación para el bloque I3-02 (Entrega y Excedentes), la decisión arquitectónica y funcional D1-B de UC-07 (`RD-UC07-001`, ratificada parcialmente en PR #45 de `leandrito369/ale-milanesas`) define que la persistencia de ofertas de excedentes físicos aceptadas se realiza incorporando una línea técnica en `DETALLE_PEDIDO` con:
- `cantidadOriginal = 0`
- `precioUnitarioOriginal = NULL`
- `porcentajePromocionOriginal = 0`
- `idPromocionAplicada = NULL`

### Problema detectado
En `CargarPedidoService.java` (UC-03 `cargarPedido`), el método `anterior(DatosPedido datos)` para replay idempotente de pedidos ya registrados ejecutaba la subconsulta:
```sql
SELECT p.*, (SELECT count(*) FROM "DETALLE_PEDIDO" d WHERE d."idPedido"=p."idPedido") AS items
FROM "PEDIDO" p WHERE "idOperacionOrigen"=?
```
Esta subconsulta contaba **todas** las filas de `DETALLE_PEDIDO`, por lo que la adición posterior de una línea técnica por excedente físico aceptado (UC-07) causaba que un replay idempotente de `cargarPedido` retornara un `cantidadItems` inflado respecto al pedido original.

### Alcance del fix
- Modificar exclusivamente la subconsulta de conteo de ítems en `CargarPedidoService.anterior(DatosPedido)` para filtrar `AND d."cantidadOriginal" > 0`.
- Agregar prueba de regresión en `CargarPedidoServiceIntegrationTest.java`: `idempotenciaConLineaTecnicaPreservaCantidadItems()`.
- No alterar contratos canónicos G0 ni `source-lock.md`.
- No alterar migraciones V1, V2 ni V3.

## 2. Auditoría previa de compatibilidad D1-B

Conforme a la auditoría técnica previa, los siguientes servicios y componentes ya manejan correctamente la existencia de líneas con `cantidadOriginal = 0` y no requieren modificaciones:
1. `RegistrarPreparacionService` (UC-28): itera sobre detalles a preparar basados en la solicitud explícita de producción o cantidades preparables; no se ve afectado por líneas con cantidad cero ni interactúa con la carga de pedidos.
2. `BuscarPedidoService` (UC-34): calcula y proyecta totales y líneas considerando el modelo canónico y las proyecciones de BC-07.
3. `GestionProductoService`: opera sobre catálogo de productos y precios, sin dependencia de filas técnicas en `DETALLE_PEDIDO`.

## 3. Modificaciones realizadas

### 3.1 `CargarPedidoService.java`
Se actualizó la subconsulta en `anterior(DatosPedido datos)`:
```java
// Antes:
SELECT p.*, (SELECT count(*) FROM "DETALLE_PEDIDO" d WHERE d."idPedido"=p."idPedido") AS items
FROM "PEDIDO" p WHERE "idOperacionOrigen"=?

// Ahora:
SELECT p.*, (SELECT count(*) FROM "DETALLE_PEDIDO" d WHERE d."idPedido"=p."idPedido" AND d."cantidadOriginal" > 0) AS items
FROM "PEDIDO" p WHERE "idOperacionOrigen"=?
```

### 3.2 `CargarPedidoServiceIntegrationTest.java`
Se incorporó el test `@Test void idempotenciaConLineaTecnicaPreservaCantidadItems()`:
- Crea un pedido inicial con 2 líneas originales (`f.base` y `f.base + 1`).
- Verifica `primero.cantidadItems() == 2`.
- Inserta directamente en `DETALLE_PEDIDO` una línea técnica simulando UC-07 D1-B con `idProducto = f.base + 2`, `numeroDetalle = 3`, `cantidadOriginal = 0`, `precioUnitarioOriginal = NULL`, `codigoUnidadVenta = "UN"`.
- Ejecuta nuevamente `pedidos.cargarPedido(vendedor, d)` con la misma clave de idempotencia.
- Asevera que:
  - `replay.idPedido() == primero.idPedido()`.
  - `replay.creado() == false`.
  - `replay.cantidadItems() == itemsOriginales` (2), confirmando que la línea técnica no infla el conteo.

## 4. Verificación y Entorno

- **Compilación local:**
  `mvn -B -ntp test-compile` ejecutado con éxito (`BUILD SUCCESS`).
- **Ejecución de pruebas:**
  El entorno local no cuenta con daemon de Docker/Podman (`docker: command not found`). Dado que los tests de integración (`CargarPedidoServiceIntegrationTest`) dependen de Testcontainers para instanciar PostgreSQL, la ejecución de las pruebas se delega formalmente al pipeline de CI en GitHub Actions. No se infieren ni simulan ejecuciones locales sin el soporte de contenedores requerido.

## 5. Control de Despliegue y Trazabilidad

- **Issue #28:** Permanece ABIERTA en `leandrito369/ale-milanesas-app`.
- **Despliegue:** Continúa formalmente PAUSADO por orden del responsable. No se habilita ni se ejecutan pasos de despliegue.
- **Pull Request:** Se publica como Draft PR con etiquetas `B4` y `no-desplegar`, sin merge automático ni modificación de `main`.

