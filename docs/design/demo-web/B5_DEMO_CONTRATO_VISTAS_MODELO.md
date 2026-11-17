# Contrato explícito Vista ↔ Modelo Thymeleaf (`B5-DEMO-02` ↔ `B5-DEMO-01`)

**Proyecto:** Sistema de Gestión para Ale Milanesas (UNaM–FCEQyN).  
**Fecha:** 16/11/2026.  
**Base normativa:** `B5_DEMO_CONTRATO_UI_MVC_G1_RATIFICADO_CON_DISENO_VISUAL_2026-11-16.md` (G1 A–E y `D-UI-01/02/06/07/16` ratificadas; F condicionado post-merge), `origin/main` @ `26996e82fbbe264a797565cb4c53933b4e9c39b9` (PR #94 `B5-DEMO-01` integrada).
**Propósito:** fijar los nombres de plantillas, variables del `Model` y parámetros de formulario (`@RequestParam` / `@ModelAttribute` / `RedirectAttributes`) usados por las vistas en `src/main/resources/templates/demo/**`, para evitar colisiones con los controladores MVC de Codex (`web/demo/**`) en desarrollo paralelo.

---

## 1. Recurso estático y cabeceras requeridas

| Elemento | Valor esperado por las vistas | Responsable de habilitación |
|---|---|---|
| Archivo CSS | `src/main/resources/static/demo/demo.css` | Antigravity (creado) |
| URL referenciada en HTML | `@{/demo/demo.css}` (`/demo/demo.css`) | Codex (`SecurityConfiguration` / allowlist mínima) |
| CSP requerida en `/web/**` | `default-src 'none'; style-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'` | Codex (`SeguridadWebInterna`, sin `'unsafe-inline'`, sin `script-src`) |
| Atributos inline / JS | **Ninguno** (`0` scripts, `0` estilos inline, `0` CDN) | Antigravity |

---

## 2. Variables transversales del `Model` (esperadas en todas las vistas `demo/**`)

Todas las vistas incluyen `demo/fragments :: cabecera(activo)` y `demo/fragments :: mensajes`:

| Atributo `Model` | Tipo esperado | Uso en `fragments.html` y vistas | Observación de seguridad |
|---|---|---|---|
| `esAdmin` | `Boolean` (`true`/`false`) | Muestra enlaces de consulta (UC-33/35/34) y columna `fechaHoraCarga` en UC-34 | Derivado en servidor desde `Authentication`; nunca desde formulario |
| `esVendedor` | `Boolean` (`true`/`false`) | Muestra enlaces UC-33/35/03/34 y columnas comerciales/acción UC-43 en UC-34 | Derivado en servidor desde `Authentication` |
| `esRepartidor` | `Boolean` (`true`/`false`) | Muestra **únicamente** enlace ilustrativo `/web/demo/mis-entregas` | **No** habilita enlace ni consulta a `/web/demo/pedidos` (UC-34) |
| `rolMostrado` | `String` (opcional) | Insignia informativa de rol en cabecera | Solo etiqueta visual; sin `idUsuario` ni `idSesionUsuario` |
| `_csrf` | `CsrfToken` | `<input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">` | Obligatorio en todo `<form method="post">` (incluye `/web/logout`) |
| `mensajeExito` | `String` (opcional / flash) | Banner verde con `<output class="status-output">` (`role="status"` implícito) | Texto saneado sin secretos |
| `mensajeError` | `String` (opcional / flash) | Banner rojo `role="alert"` | Texto saneado sin SQL ni stacktraces |
| `errores` | `List<String>` (opcional) | Lista de errores de validación dentro de `role="alert"` | Mensajes controlados por servidor |

*Nota de diseño defensivo:* las expresiones en las plantillas comprueban nulidad (`filtro != null`, `form != null`, `esVendedor == true`) para tolerar atributos ausentes sin fallos SpEL.

---

## 3. Contrato por vista / ruta

### 3.1 `demo/clientes.html` — `GET /web/demo/clientes` (UC-33)
- **Roles permitidos en servidor:** `ADMINISTRADOR`, `VENDEDOR`.
- **Query params de filtro (formulario GET):** `idCliente`, `nombre`, `telefonoPrincipal`, `zona`, `estado` (`""`, `ACTIVO`, `INACTIVO`).
- **Atributos de `Model`:**
  - `filtro`: objeto con propiedades `idCliente`, `nombre`, `telefonoPrincipal`, `zona`, `estado`.
  - `clientes`: `List<ClienteConsulta>` (o DTO homólogo con `idCliente`, `nombre`, `telefonoPrincipal`, `direccion`, `zona`, `estado`, `categoriaComercial`).
- **Regla BC-07:** no incluir saldo, deuda en pesos ni cobros. Si `esVendedor == true` y `c.estado == 'ACTIVO'`, enlaza a `@{/web/demo/pedidos/nuevo(idCliente=${c.idCliente})}`.

### 3.2 `demo/productos.html` — `GET /web/demo/productos` (UC-35)
- **Roles permitidos en servidor:** `ADMINISTRADOR`, `VENDEDOR`.
- **Query params de filtro (formulario GET):** `idProducto`, `nombre`, `tipoProducto` (`""`, `ELABORADO`, `REVENTA`), `activo` (`""`, `true`, `false`).
- **Atributos de `Model`:**
  - `filtro`: objeto o mapa con propiedades `idProducto`, `nombre`, `tipoProducto`, `activo`. La selección de `activo` acepta tanto `Boolean` (`true`/`false`) como `String` (`"true"`/`"false"`) provisto por `ConsultasDemoController` en `main@26996e8`.
  - `avisoPrecio`: `String` opcional provisto por controlador para reforzar el alcance P0 (`D-UC35-BC07-001`), con respaldo estático en la plantilla.
  - `productos`: `List<ProductoConsulta>` con los 6 campos actuales de B3 (`idProducto`, `nombre`, `descripcion`, `tipoProducto`, `codigoUnidadVenta`, `activo`).
- **Regla G1 (`D-UC35-BC07-001`):** no simula precio ni consulta UC-20. Muestra aviso explícito de que el precio vigente para Vendedor queda pendiente de corte técnico independiente.

### 3.3 `demo/pedido-nuevo.html` — `GET /web/demo/pedidos/nuevo` y `POST /web/demo/pedidos` (UC-03)
- **Roles y gates en servidor:** **solo `VENDEDOR`**, `@Profile("!prod")`, `ale.http.writes.pedidos-uc03-enabled=true` (en ambos `GET` y `POST`).
- **Atributo `Model` `form` (binding `@ModelAttribute("form")` en POST y precarga opcional `idCliente` en GET):**
  - `idCliente` (`Long`)
  - `fechaReparto` (`LocalDate`, formato ISO `YYYY-MM-DD`)
  - `claveIdempotencia` (`UUID` generado por servidor en GET y conservado en reintentos/errores)
  - `items[0..2].idProducto` (`Long`), `items[0..2].cantidadSolicitada` (`BigDecimal`, hasta 3 decimales)
  - `calle` (obligatorio, <=150), `numero` (<=20), `piso` (<=10), `unidadFuncional` (<=20), `localidad` (obligatorio, <=100), `provincia` (<=100), `zona` (<=100), `referencia` (<=250)
- **Redirección PRG exitosa (`303 See Other` recomendado en G1):** a `/web/demo/pedidos`, opcionalmente con flash attribute `pedidoConfirmado` (`idPedido`, `estado`, `canalOrigen`, `fechaReparto`, `categoriaComercialAplicada`, `totalOriginal`, `cantidadItems`) y/o `mensajeExito`.

### 3.4 `demo/pedidos.html` — `GET /web/demo/pedidos` (UC-34)
- **Roles permitidos en demo P0:** **exclusivamente `ADMINISTRADOR` y `VENDEDOR`** (`REPARTIDOR` y `PRODUCCION` fuera de esta ruta en P0, conforme G1 §4 y `D-UC34-CTX-001`).
- **Query params de filtro (formulario GET):** `idPedido`, `idCliente`, `fechaReparto`, `estado` (`PENDIENTE`, `ENTREGADO`, `CANCELADO`), `canalOrigen` (`INTERNO`, `ONLINE`).
- **Atributos de `Model`:**
  - `filtro`: objeto con `idPedido`, `idCliente`, `fechaReparto`, `estado`, `canalOrigen`.
  - `pedidos`: `List<PedidoResumen>` (B3 devuelve `PedidoComercial` cuando es `VENDEDOR` y `PedidoAdministrativo` cuando es `ADMINISTRADOR` sin rol `VENDEDOR`).
    - Campos comunes leídos siempre: `idPedido`, `idCliente`, `fechaReparto`, `estado`, `canalOrigen`.
    - Campo leído cuando `esVendedor == true or esAdmin == true`: `fechaHoraCarga`.
    - Campos leídos **únicamente** cuando `esVendedor == true` (gracias a `th:if="${esVendedor == true}"`, Thymeleaf nunca evalúa estas propiedades sobre `PedidoAdministrativo`): `categoriaComercialAplicada`, `totalOriginal` y enlace a `@{/web/demo/pedidos/{idPedido}/asignar(idPedido=${p.idPedido})}` si `p.estado == 'PENDIENTE'`.
  - Flash opcionales post-PRG: `pedidoConfirmado` (UC-03) y `asignacionConfirmada` (UC-43: `idPedido`, `numeroAsignacion`, `idUsuarioRepartidor`, `fechaHoraInicio`, `fueReasignacion`).

### 3.5 `demo/pedido-asignar.html` — `GET/POST /web/demo/pedidos/{idPedido}/asignar` (UC-43)
- **Roles y gates en servidor:** **solo `VENDEDOR`**, `@Profile("!prod")`, `ale.http.writes.pedidos-uc43-enabled=true` (en ambos `GET` y `POST`).
- **Atributos de `Model`:**
  - `idPedido` (`Long` proveniente de `@PathVariable`, nunca de input oculto).
  - `aviso` (`String` opcional emitido por `AsignarRepartoDemoController` cuando corresponde advertencia operativa de reasignación).
  - `form`: `idUsuarioRepartidor` (`Long`), `motivoReasignacion` (`String`, opcional/blanco en primera asignación, obligatorio <=250 en reasignación).
- **Redirección PRG exitosa (`303`):** a `/web/demo/pedidos`, opcionalmente con flash attribute `asignacionConfirmada` o `mensajeExito`.

### 3.6 `demo/mis-entregas.html` — `GET /web/demo/mis-entregas` (Ilustrativa NO CU04)
- **Roles permitidos en servidor:** **únicamente `REPARTIDOR` autenticado** bajo perfil no-prod (403 o ruta cerrada para otras autoridades).
- **Atributos requeridos:** solo las variables transversales de cabecera (`esRepartidor`, `rolMostrado`, `_csrf`) y `aviso` opcional del controlador. **No invoca UC-34, UC-04 ni SQL**, no espera listados ni contadores.

### 3.7 `demo/error.html` — Vista de error controlada
- **Atributos de `Model` opcionales:** `codigoEstado` (ej. `400`, `403`, `404`, `409`, `503`), `tituloError`, `detalleError`. Sin trazas ni nombres internos de tablas/roles.
