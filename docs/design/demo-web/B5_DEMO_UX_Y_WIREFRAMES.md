# Especificación UX, Wireframes y Accesibilidad — Demo Web I1 + I2 (`B5-DEMO-02`)

**Proyecto:** Sistema de Gestión para Ale Milanesas (UNaM–FCEQyN).  
**Fecha:** 16/11/2026.  
**Referencia de código:** `origin/main` @ `26996e82fbbe264a797565cb4c53933b4e9c39b9` (PR #94 `B5-DEMO-01` integrada).
**Autoridad:** `B5_DEMO_CONTRATO_UI_MVC_G1_RATIFICADO_CON_DISENO_VISUAL_2026-11-16.md` (G1 A–E y `D-UI-01/02/06/07/16` ratificadas; F condicionado a PR independiente post-merge).

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

## 4. Identidad visual G1 y decisiones ratificadas (`D-UI-*`)

- **D-UI-01 (estética):** estilo **administrativo moderno con identidad gastronómica sutil**, sin tarjetas de venta B2C ni KPIs inventados.
- **D-UI-02 (paleta ratificada, cero azul primario):**
  - Bordó `--brand-bordo: #742C36` (acciones primarias, estado hover `#5C222A`, enlace de navegación activo, borde superior institucional y énfasis de alertas informativas).
  - Crema `--brand-crema: #FFF7EA` (fondo base de documento y estados vacíos) y Crema alt `--brand-crema-alt: #F5EAD8` (encabezados de tabla y barra de navegación secundaria).
  - Grafito `--brand-grafito: #252A31` (tipografía principal, títulos, etiquetas y bordes de foco de máxima jerarquía).
  - Ámbar `--brand-ambar: #F3BE65` (reservado para acento de insignia de entorno, señal de estado `PENDIENTE` / advertencia con borde `#7A5412` y fondo suave `#FDF0D5`, siempre acompañado de texto en grafito `#252A31`).
- **D-UI-06:** etiqueta de retorno al portal principal definida como **«Inicio»** (`/web/inicio`).
- **D-UI-07:** prioridad PC/tablet (`@media (min-width: 720px)`) con diseño fluido básico para móvil (`@media (max-width: 480px)` y `.table-wrap`).
- **D-UI-16:** vista `/web/demo/mis-entregas` estrictamente ilustrativa, sin consultas ni exposición de UC-04/UC-34.

---

## 5. Criterios de accesibilidad (WCAG 2.1 AA) y remediación SonarCloud

1. **Landmarks y navegación por teclado:** cada página incluye `<head>` semántico con `<title>` estático propio (resolución de los 7 BUGs `Web:PageWithoutTitleCheck`) y reutiliza la hoja CSS mediante `<link th:replace="~{demo/fragments :: cssDemo}">` sin duplicar `<head>` ni `<title>`. El cuerpo incluye `<a class="skip-link" href="#contenido">`, `<header>`, `<nav aria-label="Navegación demo">` y `<main id="contenido">`. El indicador `:focus-visible` usa contorno de `3px` (`#742C36` en superficies claras y `#252A31` en botones/enlaces activos bordó).
2. **Etiquetas, estados deshabilitados y ayudas:** todo campo posee `<label for="...">` explícito, borde `#636973` y `aria-describedby` cuando requiere aclaración operativa. Los controles `:disabled` usan fondo `#E8DFD1`, texto `#4E5560` y borde `#918778`.
3. **Agrupación estructural:** `pedido-nuevo.html` separa sus tres bloques con `<fieldset>` y `<legend>`.
4. **Retroalimentación accesible (`Web:S6819`):** los mensajes de confirmación y estados vacíos emplean el elemento HTML5 nativo `<output class="status-output">` dentro de contenido phrasing (`<p>`), evitando `role="status"` en contenedores complejos con `<dl>` o múltiples bloques; los errores usan `role="alert"`. Los estados no dependen exclusivamente del color (etiquetas textuales `ACTIVO`/`INACTIVO`/`PENDIENTE`/`ENTREGADO`/`CANCELADO`).
5. **Responsive móvil:** diseño *single-column* por defecto en pantallas pequeñas (`@media (min-width: 720px)` para grillas de 2/3/4 columnas, ajuste compacto `<= 480px`) y tablas dentro de `.table-wrap` con `overflow-x: auto`, `<caption>` y `<th scope="col">`.
6. **Contraste WCAG 2.1 AA medido (relaciones sRGB verificadas):**

| Combinación | Uso principal | Relación medida | Umbral requerido | Resultado |
|---|---|---|---|---|
| `#252A31` sobre `#FFF7EA` | Texto principal sobre fondo crema y foco fuerte | `13.58:1` | `>= 4.5:1` / `>= 3:1` | **PASA (AAA)** |
| `#252A31` sobre `#FFFFFF` | Texto en tarjetas, formularios y tablas | `14.44:1` | `>= 4.5:1` | **PASA (AAA)** |
| `#252A31` sobre `#F5EAD8` | Encabezados `<th>`, navegación e info | `12.13:1` | `>= 4.5:1` | **PASA (AAA)** |
| `#FFFFFF` sobre `#742C36` | Botón primario, skip-link y nav activo (reposo) | `9.74:1` | `>= 4.5:1` | **PASA (AAA)** |
| `#FFFFFF` sobre `#5C222A` | Botón primario y nav activo (`:hover`) | `12.23:1` | `>= 4.5:1` | **PASA (AAA)** |
| `#742C36` sobre `#FFFFFF` | Enlaces, botón secundario e insignia de rol | `9.74:1` | `>= 4.5:1` | **PASA (AAA)** |
| `#742C36` sobre `#FFF7EA` | Enlaces sobre crema y contorno `:focus-visible` | `9.16:1` | `>= 4.5:1` / `>= 3:1` | **PASA (AAA)** |
| `#742C36` sobre `#F9EBED` | Insignia `.role-badge` y hover secundario | `8.41:1` (`10.56:1` hover `#5C222A`) | `>= 4.5:1` | **PASA (AAA)** |
| `#252A31` sobre `#F3BE65` | Insignia `.env-badge` y `.status-warn` (`PENDIENTE`) | `8.5:1` | `>= 4.5:1` | **PASA (AAA)** |
| `#252A31` sobre `#FDF0D5` | Alerta `.alert-warning` (texto / borde `#7A5412`) | `12.79:1` / borde `5.99:1` | `>= 4.5:1` / `>= 3:1` | **PASA** |
| `#4B515C` sobre `#FFFFFF` / `#FFF7EA` | Textos auxiliares `.muted` y `.field-hint` | `7.98:1` / `7.51:1` | `>= 4.5:1` | **PASA (AAA)** |
| `#636973` sobre `#FFFFFF` / `#FFF7EA` | Borde de inputs y bordes dashed | `5.53:1` / `5.2:1` | `>= 3:1` | **PASA** |
| `#4E5560` sobre `#E8DFD1` | Controles `:disabled` | `5.7:1` | N/A (supera `4.5:1`) | **PASA** |
| `#064E3B` sobre `#ECFDF5` / `#7F1D1D` sobre `#FEF2F2` | Alertas y badges de éxito / error | `9.23:1` / `9.16:1` | `>= 4.5:1` | **PASA (AAA)** |

> **Prohibiciones verificadas:** `#FFFFFF` sobre `#F3BE65` (`1.7:1`) y `#F3BE65` sobre `#FFF7EA` (`1.6:1` como indicador único) **no se utilizan en ninguna regla CSS**.

---

## 6. Estrategia CSS local y CSP

- Hoja única: `src/main/resources/static/demo/demo.css`, enlazada con `<link th:replace="~{demo/fragments :: cssDemo}" rel="stylesheet" th:href="@{/demo/demo.css}">`.
- Cero JavaScript, cero `style="..."`, cero `<style>`, cero fuentes o CDNs externos.
- Los 3 renglones de ítems en UC-03 se resuelven en HTML puro sin scripts (`items[0]` obligatorio, `items[1..2]` opcionales), preservando `default-src 'none'` para `script-src` y limitando la ampliación CSP en Codex a `style-src 'self'`.
