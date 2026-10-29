# Metodología obligatoria — Ale Milanesas (Antigravity y asistentes de código)

Esta regla complementa el `AGENTS.md` de la raíz. **No prevalece sobre `source-lock` ni las RD/contratos del repositorio `leandrito369/ale-milanesas`**.

1. Leer `docs/traceability/source-lock.md` y anclar toda tarea al SHA G0 aprobado.
2. Consultar ficha de CU (tres niveles), DSS, DS y contrato preferente exactos del manifiesto. No inferir comportamiento desde diagramas antiguos `originales/`.
3. Asegurar Rol/canal/identidad/objeto con RBAC y 15 proyecciones BC07; revisar en servidor, no por UI.
4. Proponer clases/métodos/transaction boundary e impacto de persistencia; someter diferencias a RD/CR antes de codificar.
5. No programar offline S-01, inbox o rendición de efectivo física sin CR-B0-F04-001 aprobada.
6. Implementar por PR de CU con pruebas reales; reportar íntegramente comandos, resultados, limites de cobertura y bloqueos; no insertar secretos, datos personales o pagos reales.
7. Nunca conceder nuevo permiso ni ampliar un CU por conveniencia; preservar trazabilidad de cada cambio.
