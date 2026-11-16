# Plan de QA en navegador — Demo Web I1 + I2 (`B8-DEMO-01`)

**Proyecto:** Sistema de Gestión para Ale Milanesas (UNaM–FCEQyN).  
**Fecha:** 16/11/2026.  
**Estado de ejecución:** **NO EJECUTADO** en esta PR (espera integración de controladores MVC `B5-DEMO-01` de Codex y entorno local HTTPS aislado).  
**Base normativa:** `B5_DEMO_CONTRATO_UI_MVC_G1_RATIFICADO_2026-11-16.md`.

---

## 1. Condiciones previas para ejecutar (G4)

1. PR de Codex (`B5-DEMO-01/B9-DEMO-01`) y PR de Antigravity (`B5-DEMO-02`) integradas en un SHA común verificable, con CI B1/B2 en verde.
2. PostgreSQL 17.11 aislado (`ale_milanesas_demo`) con Flyway V1–V3 y fixtures exclusivamente sintéticas (`VENDEDOR`, `ADMINISTRADOR`, `REPARTIDOR`, usuario con `cambioCredencialPendiente=true`, clientes, productos con precios vigentes, política comercial).
3. HTTPS privado local con certificado confiable, cookies `__Host-ale_session` y `JSESSIONID` con `Secure; HttpOnly; SameSite=Strict`.
4. Perfil `!prod` y banderas `ale.http.writes.pedidos-uc03-enabled=true` y `pedidos-uc43-enabled=true` solo en entorno demo aislado.

---

## 2. Matriz de pruebas de navegador

| ID | Caso | Rol / Actor | Resultado esperado | Evidencia requerida al ejecutar | Estado |
|---|---|---|---|---|---|
| WEB-01 | Login válido en `/web/login` y navegación a `/web/inicio` | `VENDEDOR` | Cookie `__Host-ale_session` `HttpOnly; Secure; SameSite=Strict`; sin Bearer en HTML/DOM/`localStorage` | Captura DevTools Application/Network saneada | **NO EJECUTADO** |
| WEB-02 | Login inválido y rechazo de sesión revocada tras logout | `VENDEDOR` / anónimo | Mensaje genérico sin enumerar cuenta; cookie revocada rechazada (401/redirigida) | Captura + `SELECT` `SESION_USUARIO` | **NO EJECUTADO** |
| WEB-03 | Cambio obligatorio UC-53 en `/web/cambiar-clave` | Usuario con `cambioCredencialPendiente=true` | Acceso restringido a UC-53; tras cambiar clave revoca sesión y exige nuevo login | Captura + verificación `SESION_USUARIO` | **NO EJECUTADO** |
| WEB-04 | Consulta Clientes UC-33 en `/web/demo/clientes` | `VENDEDOR` y `ADMINISTRADOR` | Filtros funcionan; sin saldo/cobros; enlace "Cargar pedido" solo `VENDEDOR` en `ACTIVO` | Captura de pantalla | **NO EJECUTADO** |
| WEB-05 | Consulta Productos UC-35 en `/web/demo/productos` | `VENDEDOR` y `ADMINISTRADOR` | Muestra 6 campos B3 y aviso de `D-UC35-BC07-001` pendiente; sin costos ni stock | Captura de pantalla | **NO EJECUTADO** |
| WEB-06 | Cargar Pedido UC-03 en `/web/demo/pedidos/nuevo` | `VENDEDOR` | 3 renglones (1..3 ítems), 8 campos de dirección; PRG 303 a `/web/demo/pedidos` con `totalOriginal` congelado | Captura + consulta `PEDIDO`/`DETALLE_PEDIDO` | **NO EJECUTADO** |
| WEB-07 | Doble submit / reintento idempotente UC-03 | `VENDEDOR` | Misma `claveIdempotencia` no duplica `PEDIDO` ni detalles | Captura + `COUNT(*)` en `PEDIDO` | **NO EJECUTADO** |
| WEB-08 | Primera asignación UC-43 en `/web/demo/pedidos/{id}/asignar` | `VENDEDOR` | Motivo vacío permitido; crea fila en `ASIGNACION_REPARTO` con `fechaHoraFin IS NULL` | Captura + consulta `ASIGNACION_REPARTO` | **NO EJECUTADO** |
| WEB-09 | Reasignación UC-43 (motivo obligatorio / mismo repartidor / pedido no PENDIENTE) | `VENDEDOR` | Rechazo controlado sin motivo (`400`) o mismo repartidor (`409`); con motivo válido cierra previa y crea nueva fila | Captura + 2 filas en `ASIGNACION_REPARTO` | **NO EJECUTADO** |
| WEB-10 | Buscar Pedido UC-34 como `VENDEDOR` | `VENDEDOR` | Muestra 8 columnas (`categoriaComercialAplicada`, `totalOriginal`, `fechaHoraCarga`) y enlace UC-43 en `PENDIENTE` | Captura de pantalla | **NO EJECUTADO** |
| WEB-11 | Buscar Pedido UC-34 como `ADMINISTRADOR` | `ADMINISTRADOR` | Muestra 6 columnas (sin categoría ni total ni acción UC-43) | Captura comparada con WEB-10 | **NO EJECUTADO** |
| WEB-12 | Vista ilustrativa `/web/demo/mis-entregas` como `REPARTIDOR` | `REPARTIDOR` | Muestra texto exacto «Módulo en preparación...» sin pedidos, contadores ni enlace a UC-34; `GET /web/demo/pedidos` devuelve 403 | Captura de pantalla + 403 | **NO EJECUTADO** |
| WEB-13 | Acceso cruzado y bypass a rutas protegidas | `ADMINISTRADOR` en `/web/demo/pedidos/nuevo` o `VENDEDOR` en `/web/demo/mis-entregas` | Servidor responde 403 sin mutar BD | Captura `error.html` | **NO EJECUTADO** |
| WEB-14 | POST sin `_csrf` u `Origin` ajeno | Cualquier usuario | Rechazo `403`, cero cambios en BD | Evidencia HTTP / navegador | **NO EJECUTADO** |
| WEB-15 | XSS, CSP y `Cache-Control: no-store` | `VENDEDOR` | Payloads `<script>` renderizados como texto literal (`th:text`); CSP `default-src 'none'; style-src 'self' ...` carga `demo.css` sin errores de consola | Captura DevTools Console + Network | **NO EJECUTADO** |
| WEB-16 | Perfil `prod` o flags `pedidos-uc03-enabled=false` / `pedidos-uc43-enabled=false` | Arranque controlado | `prod` veta flags y deshabilita rutas demo; flags en `false` rechazan GET y POST de formularios UC-03/UC-43 | Log de arranque / 403-404 | **NO EJECUTADO** |
| WEB-17 | Persistencia tras recarga y reinicio | `VENDEDOR` / `ADMIN` | Pedido y asignación permanecen íntegros al consultar tras refrescar o reiniciar servicio | Captura post-reinicio | **NO EJECUTADO** |
| WEB-18 | Accesibilidad teclado y responsive móvil/escritorio | Operador QA | Tab/Enter recorren todos los controles con foco visible; sin desborde horizontal en ~375px y ~1280px | Capturas móvil + escritorio | **NO EJECUTADO** |

---

## 3. Regla de reporte

- Ningún caso puede marcarse `PASS` sobre MockMvc aislado ni solo por inspección de HTML estático.
- Toda captura publicada en el paquete de entrega debe ocultar contraseñas, tokens, cookies en claro y archivos `.env`.
- Esta demo **no** acredita 4/4 E2E humanos UC-54 ni 18/18 escenarios funcionales BC-06, ni cierra la Issue #28.
