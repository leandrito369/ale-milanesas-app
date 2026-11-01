# B4-01 — Primer corte HTTP interno: UC-33 y UC-36

**Fecha:** 01/11/2026. **Estado:** propuesta de implementación B4 desde `main` post-merge PR26 (`fd4bd6d9dd89e43b2fdec29c49a553a3f1b15d93`). **No acredita todavía E2E externo ni implementa login UC01.**

## Alcance y justificación de secuencia

Se inicia la capa HTTP con el caso de lectura UC-33 (Buscar Cliente) y UC-36 (Buscar Insumo), además de la consulta auxiliar de `UNIDAD_MEDIDA`. Este primer tramo permite validar **identidad remota, autorización real por rol y aislamiento de datos** antes de exponer comandos que mutan Pedidos, Usuarios, Precios, Clientes o Insumos. Reutiliza servicios B3 del SHA integrado. No se abre ningún controlador de escritura.

- `GET /api/v1/clientes` — UC33; filtros opcionales `idCliente`, `nombre`, `telefonoPrincipal`, `zona`, `estado`. Solo `ADMINISTRADOR` / `VENDEDOR`. La proyección original B3 incluye nombre, teléfono, domicilio, zona, estado y categoría comercial, **sin saldos de Cuenta Corriente ni Cobros**.
- `GET /api/v1/insumos` — UC36; filtros opcionales `idInsumo`, `nombre`, `activo`. Solo `ADMINISTRADOR` / `RESPONSABLE_PRODUCCION`. Filtra activos e inactivos sin editar el stock.
- `GET /api/v1/unidades` — dependencia de catálogo `UNIDAD_MEDIDA`, solo lectura, mismos roles. No inventa un CRUD/UC específico para unidades.
- `/actuator/health` sin credenciales, con detalles deshabilitados según `application.yml`. Otras rutas, incluidos todos POST/PUT/PATCH/DELETE, **denegadas**.

## Identidad y política de seguridad B4-01

- Spring Security `SecurityFilterChain` de sesión stateless (sin HTTP Session ni login form/basic). **No aceptar `idSesionUsuario`, `idUsuario`, rol u otros identificadores aportados por el cliente para hacerse pasar por usuario.**
- **Contrato provisional de token de servicio (no de UC54):** `Authorization: Bearer <token>`, 32 bytes criptográficamente aleatorios codificados en **Base64URL canónico sin padding (43 caracteres)**. En `SESION_USUARIO.tokenProtegido BYTEA` se almacena únicamente `SHA-256(bytes_del_token)`, nunca el token en claro. El filtro calcula la huella de un token existente; no genera, entrega ni renueva tokens. Esta convención se adopta como **puente temporal B4** para pruebas de consulta HTTP y deberá ratificarse/conciliarse con la creación/renovación/revocación de sesión de UC01 antes de emitir un token a usuarios reales.
- Por cada request autenticado consulta `SESION_USUARIO` (no revocada, en vigencia) y `USUARIO` (`HABILITADO`, `cambioCredencialPendiente=false`), junto a roles vigentes de `USUARIO_ROL`. Si no existe al menos un rol vigente se rechaza. No se confía en un rol enviado en el payload. La autorización se aplica dos veces: en ruta HTTP (401 sin token o token inválido; 403 si rol incorrecto) y en el servicio B3 (revalidación de `idSesionUsuario` contra PostgreSQL).
- `Cache-Control: no-store` en respuestas de las tres consultas para no almacenar PII ni stock de uso interno. Filtros inválidos producen 400 controlado, sin mostrar SQL/excepciones. La API no refleja el token, digests, `idUsuario`, `idSesionUsuario` o rol en el cuerpo.
- **CSRF conservado por defecto en B4-01, que expone exclusivamente lecturas**; no deben añadirse mutaciones sin diseñar autenticación, origen permitido y riesgos CSRF/CORS. No se habilita CORS global.

## Postura frente a UC54/UC53

El inicio real de una sesión y validación de credencial protegida, generación de secreto aleatorio, alta de `SESION_USUARIO`, limitación de intentos, rotación de secreto y políticas de expiración **no se implementan en este corte**. Solo se verifica un secreto preexistente. Los tests PostgreSQL preparan transaccionalmente sus usuarios, roles, secretos e identificadores de sesión; **eso no constituye una ruta pública de login ni acreditación UC01**.

**Reserva para diseño de integración:** formalizar la función de hash, longitud del token, entrega por TLS y comprobación de contraseñas con UC01 e I1-01 antes de habilitar emisión en un entorno real. Se prohíbe exponer un UUID de sesión como bearer token o persistir el secreto en texto claro.

## Evidencia prevista

- `ConsultasI1HttpIntegrationTest` — 11 escenarios en `@SpringBootTest @AutoConfigureMockMvc` con Testcontainers PostgreSQL 17.11 y filtros Spring Security reales: lectura por roles, 403 cruzados, sin login/UUID/token inválido 401, sesiones revocadas/expiradas, usuario deshabilitado, cambio obligatorio de credencial, revocación de rol antes del segundo request, 400 por filtros inválidos, denegar mutaciones y health.
- Requiere **B1 y B2 SUCCESS sobre el mismo HEAD**; MR5/F01 inmutables, huella MR6 triple y restore 1225 objetos. Artefactos SHA-256.
- No sumar escenarios de ramas independientes. La prueba MockMvc pasa por la cadena Security y consulta base real, pero **no reemplaza un E2E externo** con navegador, credenciales UC54 reales, front end y flujos persistidos completos. BC06 0/18 E2E y UC54 0/4 permanecen sin acreditar.

## Próximo tramo

Después de validar B4-01, ratificar el contrato completo de autenticación de sesión UC01 y abrir comandos de alta/edición según roles de cada CU en PRs pequeñas, con pruebas HTTP reales, transacciones y controles de concurrencia. Priorizar respetar el backlog rector; no incorporar CRUD completo de todos los módulos por inferencia.
