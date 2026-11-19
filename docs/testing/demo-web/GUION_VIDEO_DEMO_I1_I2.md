# Guion de demostración en navegador (6–9 min) — Demo Web I1 + I2

**Proyecto:** Sistema de Gestión para Ale Milanesas (UNaM–FCEQyN).  
**Fecha:** 16/11/2026.  
**Alcance del guion:** Primer Estado de Avance 2026 (Alternativa B — MVP P0 local/privado sobre `origin/main` integrado).  
**Estado:** Borrador preparado para G4/G5; ejecutar y grabar únicamente tras superar G4 (`GO para grabar`).

---

## 1. Checklist previo de ocultamiento (obligatorio antes de grabar)

- [ ] Usar exclusivamente base de datos aislada (`ale_milanesas_demo`) y datos sintéticos ficticios.
- [ ] No mostrar `.env`, contraseñas en claro, hashes BCrypt ni tokens de sesión en consola o DevTools.
- [ ] Verificar que la barra de marcadores/escritorio no exponga información personal o credenciales.
- [ ] Tener abierta una terminal con consulta SQL acotada (`SELECT` de columnas no sensibles de `PEDIDO`, `DETALLE_PEDIDO`, `ASIGNACION_REPARTO`) y un navegador con DevTools en pestaña Network/Headers lista.

---

## 2. Secuencia de grabación (objetivo 7–8 minutos)

| Tramo | Tiempo | Qué mostrar en pantalla | Mensaje clave obligatorio |
|---|---|---|---|
| **1. Contexto y entorno** | 0:00–1:00 | Mostrar SHA integrado de `main`, arranque Spring Boot con perfil demo no-prod y Flyway V1–V3 en PostgreSQL 17.11 aislado. | Aclarar que se trata de un **entorno local HTTPS con datos sintéticos**, correspondiente a las funciones I1+I2. Indicar expresamente que **Entrega (UC-05), Cobro (UC-29), UC-04 y despliegue productivo (Issue #28) siguen pendientes**. |
| **2. Login real y seguridad web** | 1:00–2:10 | `/web/login`: intento fallido (mensaje genérico) y login válido como `VENDEDOR` sintético. En DevTools mostrar `Set-Cookie: __Host-ale_session` (`HttpOnly`, `Secure`, `SameSite=Strict`), ausencia de Bearer en HTML/DOM/`localStorage`, `CSP` restrictiva y `Cache-Control: no-store`. | Explicar separación entre cookie web con CSRF/Origin y API Bearer. |
| **3. Consultas I1 (UC-33 y UC-35)** | 2:10–3:20 | Navegar a `/web/demo/clientes` (filtrar cliente ACTIVO) y `/web/demo/productos` (filtrar elaborados/reventa). | Mostrar proyecciones BC-07 (sin saldos en clientes; 6 campos actuales en productos y mención honesta de que el precio previo `D-UC35-BC07-001` está pendiente de corte propio). |
| **4. Cargar Pedido (UC-03)** | 3:20–4:45 | Desde cliente ACTIVO ir a `/web/demo/pedidos/nuevo`: completar `fechaReparto`, 2 ítems (sin repetir producto) y dirección estructurada. Confirmar -> redirección PRG 303 a `/web/demo/pedidos` con banner de confirmación (`categoriaComercialAplicada` y `totalOriginal` congelados). Mostrar fila persistida en PostgreSQL. | Destacar que el navegador nunca envía precios ni descuentos: el servidor congela precio vigente, categoría y ajuste en una única transacción e idempotencia por UUID. |
| **5. Asignar Repartidor (UC-43)** | 4:45–5:55 | Desde el pedido `PENDIENTE`, abrir `/web/demo/pedidos/{id}/asignar`: registrar primera asignación (motivo vacío) y, opcionalmente, demostrar reasignación exigiendo motivo obligatorio. Verificar en PostgreSQL el historial en `ASIGNACION_REPARTO`. | Mostrar cumplimiento de `RD-RP002-001` (una sola asignación vigente y conservación de historial). |
| **6. Proyecciones distintas UC-34 + Vista REPARTIDOR + 403** | 5:55–7:25 | Comparar `/web/demo/pedidos` como `VENDEDOR` (8 columnas) vs `ADMINISTRADOR` (6 columnas, sin categoría ni total). Luego iniciar sesión como `REPARTIDOR`: mostrar navegación a `/web/demo/mis-entregas` con el mensaje **«Módulo en preparación...»** (aclarando que **no es UC-04**). Probar acceso directo a `/web/demo/pedidos/nuevo` o `/web/demo/pedidos` como `REPARTIDOR` -> **403 denegado**. | Demostrar que la seguridad y BC-07 se aplican en servidor, no solo ocultando menús. |
| **7. Logout y cierre** | 7:25–8:00 | Ejecutar `Cerrar sesión` (`POST /web/logout`) e intentar recargar `/web/demo/clientes` -> acceso rechazado. | Resumir alcance real demostrado por CU y cerrar sin declarar 64/67 completos ni aceptación humana UC-54/BC-06. |
