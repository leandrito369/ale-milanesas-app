# Especificación UX, Wireframes y Accesibilidad — Demo Web I1 + I2 (`B5-DEMO-02`)

**Proyecto:** Sistema de Gestión para Ale Milanesas (UNaM–FCEQyN).  
**Fecha:** 16/11/2026.  
**Referencia de código:** `origin/main` @ `aa68b7843296aafcb6c9ead510f0cbad265ab45b`.  
**Autoridad:** `B5_DEMO_CONTRATO_UI_MVC_G1_RATIFICADO_2026-11-16.md` (G1 A–E aprobados; F condicionado post-merge Codex).

---

## 1. Alcance y límites visuales

- **P0 implementado en plantillas nuevas (`src/main/resources/templates/demo/**`):**
  1. `clientes.html` — UC-33 Buscar Cliente (`ADMINISTRADOR`, `VENDEDOR`).
  2. `productos.html` — UC-35 Buscar Producto (`ADMINISTRADOR`, `VENDEDOR`, 6 campos actuales de B3 + nota explícita de `D-UC35-BC07-001` pendiente).
  3. `pedido-nuevo.html` — UC-03 Cargar Pedido (`VENDEDOR`, 3 renglones de ítems, 8 campos de dirección manual, `claveIdempotencia` UUID, `_csrf`).
  4. `pedidos.html` — UC-34 Buscar Pedido (**exclusivamente `ADMINISTRADOR` y `VENDEDOR`** en P0 con columnas diferenciadas BC-07).
  5. `pedido-asignar.html` — UC-43 Asignar / Reasignar Repartidor (`VENDEDOR`, ID de fixture sintético, motivo condicional).
  6. `mis-entregas.html` — Vista ilustrativa para `REPARTIDOR` (*«Módulo en preparación. La consulta de pedidos asignados estará disponible cuando UC-04 esté implementado y probado»*), sin listas, contadores ni enlaces a UC-34.
  7. `error.html` + `fragments.html` — Navegación contextual por rol, alertas accesibles y manejo de errores saneado.
- **Restricción F explícita:** `web-login.html`, `web-inicio.html` y `web-cambiar-clave.html` **no se modifican en esta PR**. El enlace desde `/web/inicio` hacia `/web/demo/**` se realizará en rama/PR posterior tras el merge de Codex.

---

## 2. Mapa de navegación y flujos

```text
/web/login (existente) ──▶ /web/inicio (existente; enlace post-F)
                                │
        ┌───────────────────────┼──────────────────────────────┐
        ▼                       ▼                              ▼
[ADMIN / VENDEDOR]        [SOLO VENDEDOR]               [SOLO REPARTIDOR]
  ├─ /web/demo/clientes     ├─ /web/demo/pedidos/nuevo     └─ /web/demo/mis-entregas
  ├─ /web/demo/productos    └─ /web/demo/pedidos/{id}/asignar   (ilustrativa; sin UC-34/UC-04)
  └─ /web/demo/pedidos
```

---

## 3. Wireframes y tabla Vista → Modelo → Rol → Datos BC-07

| Plantilla | Ruta | CU | Rol(es) | Datos visibles autorizados | Datos excluidos |
|---|---|---|---|---|---|
| `demo/clientes.html` | `GET /web/demo/clientes` | UC-33 | `ADMIN`, `VENDEDOR` | `idCliente`, `nombre`, `telefonoPrincipal`, `direccion`, `zona`, `estado`, `categoriaComercial` | Saldo, pagos, cobros, movimientos |
| `demo/productos.html` | `GET /web/demo/productos` | UC-35 | `ADMIN`, `VENDEDOR` | `idProducto`, `nombre`, `descripcion`, `tipoProducto`, `codigoUnidadVenta`, `activo` | Costos, `stockActualReventa`, `nombreNormalizado`, UC-20 |
| `demo/pedido-nuevo.html` | `GET /web/demo/pedidos/nuevo`, `POST /web/demo/pedidos` | UC-03 | `VENDEDOR` | Formulario 3 ítems + dirección 8 campos + `claveIdempotencia` + `_csrf` | Precio/descuento editable por usuario |
| `demo/pedidos.html` | `GET /web/demo/pedidos` | UC-34 | `ADMIN`, `VENDEDOR` | Comunes (5) + `fechaHoraCarga`; si `VENDEDOR`: +`categoriaComercialAplicada`, `totalOriginal` y enlace UC-43 en `PENDIENTE` | En `ADMIN`: categoría, total y acción UC-43. Para todos: detalle ítems/domicilio/credenciales |
| `demo/pedido-asignar.html` | `GET/POST /web/demo/pedidos/{idPedido}/asignar` | UC-43 | `VENDEDOR` | `idPedido`, `idUsuarioRepartidor`, `motivoReasignacion` | Listado UC-56 de usuarios, datos financieros |
| `demo/mis-entregas.html` | `GET /web/demo/mis-entregas` | Ilustrativa | `REPARTIDOR` | Encabezado + estado vacío de módulo en preparación | Pedidos reales, contadores, CTA a UC-34/UC-04 |
| `demo/error.html` | Error controlado MVC | Transversal | Según sesión | `codigoEstado`, `tituloError`, `detalleError` | SQL, stacktraces, tablas, flags internos |

---

## 4. Criterios de accesibilidad (WCAG 2.1 AA)

1. **Landmarks y navegación por teclado:** cada página incluye `<a class="skip-link" href="#contenido">`, `<header>`, `<nav aria-label="Navegación demo">` y `<main id="contenido">`. El indicador `:focus-visible` usa contorno de `3px` de alto contraste (`#b45309`).
2. **Etiquetas y ayudas:** todo campo posee `<label for="...">` explícito y `aria-describedby` cuando requiere aclaración operativa (fecha no retroactiva, regla de motivo UC-43, decimal de 3 posiciones).
3. **Agrupación estructural:** `pedido-nuevo.html` separa sus tres bloques con `<fieldset>` y `<legend>`.
4. **Retroalimentación accesible:** confirmaciones en `role="status"` / `aria-live="polite"`; errores en `role="alert"`.
5. **Responsive móvil:** diseño *single-column* por defecto en pantallas pequeñas (`@media (min-width: 720px)` para grillas de 2/3/4 columnas) y tablas dentro de `.table-wrap` con `overflow-x: auto`, `<caption >` y `<th scope="col">`.
6. **Contraste:** texto principal `#1b2432` sobre `#ffffff` / `#f5f7fb` (> 13:1), enlaces y botones primarios `#14539a` (> 7:1 sobre blanco, texto blanco > 7:1).

---

## 5. Estrategia CSS local y CSP

- Hoja única: `src/main/resources/static/demo/demo.css`, enlazada con `<link rel="stylesheet" th:href="@{/demo/demo.css}">`.
- Cero JavaScript, cero `style="..."`, cero `<style>`, cero fuentes o CDNs externos.
- Los 3 renglones de ítems en UC-03 se resuelven en HTML puro sin scripts (`items[0]` obligatorio, `items[1..2]` opcionales), preservando `default-src 'none'` para `script-src` y limitando la ampliación CSP en Codex únicamente a `style-src 'self'`.
