# AGENTS.md — Reglas obligatorias para agentes de implementación

**Repositorio:** `leandrito369/ale-milanesas-app` — **solamente implementación y documentación operativa**.  
**Fuente normativa:** `leandrito369/ale-milanesas` al G0 ratificado. **Antes de intervenir leer [source-lock](docs/traceability/source-lock.md)** y las fuentes exactas de la operación. Los cambios de negocio y sus decisiones van al repo documental, nunca se inventan en este.

## Puerta de trabajo

1. Verificar Gate **G0 APROBADO** y concordancia del commit de fuentes fijado en `source-lock`.
2. El responsable asigna una tarea/Issue con **WBS/iteración + CU/operación + contrato/DSS/DS + criterios de aceptación + permisos/visibilidad + DB y pruebas**.
3. **Primero plan técnico/diff previsto**. Inspeccionar diagramas DS/DSS y contrato real por mensaje, firma, contexto, validaciones, alternativas, postcondiciones, transacción y visibilidad; **no** programar solo por la matriz E01-C candidata.
4. Rama y PR propios. **No modificar `main` directamente**, no fusionar por cuenta propia sin revisión ni ejecutar pagos/mensajes reales.
5. Entregar tests reproducibles, resultados reales y evidencia del entorno. Si no hay backend, reportar `NO_EJECUTADO`, nunca `OK`.

## Arquitectura ratificada y límites

**Monolito modular** Java/Spring Boot, **7 módulos lógicos**; PostgreSQL; web Thymeleaf, Android Kotlin/Room; API HTTP/JSON con autenticación y autorización del servidor; adaptadores aislados para Mercado Pago, WhatsApp (proveedor pendiente) y OSRM. JUnit5/Spring Test, Testcontainers y CI como estrategia. Las **versiones exactas** y herramientas **Maven/Flyway** se fijan por ADR de implementación: su uso fue propuesto, no declarado ya instalado.

**ADR-B1-05 NO ratificada físicamente.** CR-B0-F04-001 prohíbe codificar nuevas tablas/columnas de inbox, snapshots, pago en tránsito o rendición antes de evaluación/decisión. El resto del sistema puede avanzar en el orden del backlog I0→I1→I2→I3→I4–I7.

**Reglas no negociables** (según contratos/RD):
- Sesión/roles válidos y ámbito del objeto autorizados **en servidor**. RBAC 67×8, cero `P`; BC07 15 proyecciones; no exponer costos/datos sin autorización.
- Cliente ONLINE con código OTP y cuenta activa **no es** Usuario interno. Proveedor tiene Rol exclusivo; un Usuario debe conservar al menos un Rol. Proteger último Administrador.
- UC-14 `consultarCuentaCorriente`: solo Vendedor. UC-03 `reprogramarFechaRepartoPedido`: solo Vendedor y Pedido `PENDIENTE`. UC-29 `anularCobroManual`: solo Administrador, con contraasientos.
- Precio, política, descuento y categoría del Pedido **congelados históricamente**. Preparar solo cantidades originales; extras aceptados no consumen Insumos de nuevo; Entrega definitiva.
- Efectivo declarado por Repartidor en UC-05 **no** es Cobro ni Caja definitiva. La rendición es administrativa.
- Reembolso ONLINE: efectos contables **solo cuando `PROCESADO`**, aplicados una vez pese a webhook duplicado/concurrente; verificar origen y correlación.
- Compra requiere aprobación expresa Administrador; ronda automática no origina una Orden de Compra.
- Automatismos/servicios UC-15/16 internos, Cliente ONLINE y eventos Mercado Pago **no heredan Roles humanos**.

## Definition of Done por operación

Contrato y DS revisados; persistencia y transacción implementadas según fuentes, autorización de acceso y proyección BC07; pruebas positivas/negativas, límites, concurrencia/idempotencia si corresponde; comandos y logs sin secretos; README técnico y matriz CU→servicio→DB→API/UI→test actualizados; PR independiente revisada. **BC-06: 18 escenarios pendientes de prueba real**, no marcarlos como ejecutados anticipadamente.

En caso de contradicción o falta de permisos físicos, abrir RP/CR en repo de análisis con referencia cruzada al Issue de implementación. Nunca editar diagramas, RD o contratos aprobados desde la app.
