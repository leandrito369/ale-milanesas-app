# I3-03 / UC-23 — Consultar Libro Diario

Estado: incremento técnico en rama; no desplegado ni aceptado humanamente.

- Fuente: G0 `1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e`, gobierno `27738b089afdd2698cdae915f75c0e8089a6248a`, contrato REV05 consolidado y RBAC (solo `ADMINISTRADOR`).
- B3: lectura de `MOVIMIENTO_CAJA` en una transacción `readOnly`, intervalo civil inclusivo en `America/Argentina/Buenos_Aires`; SQL expresado como `[inicio, día posterior a fechaFinal)`.
- B4 propuesto, no parte del contrato G0: `GET /api/v1/finanzas/libro-diario?fechaInicial=AAAA-MM-DD&fechaFinal=AAAA-MM-DD`.
- Salida: filas cronológicas, ingresos, egresos y resultado monetario. Un resultado vacío devuelve importes monetarios cero y no afirma arqueo físico, utilidad ni margen.
- Seguridad: sesión Bearer real, matcher exclusivo `ADMINISTRADOR`, errores sanitizados y `Cache-Control: no-store`.
- Concurrencia: filas y totales provienen de la misma lectura; no se ejecuta una segunda agregación susceptible de observar otra versión concurrente.
- Límites: no modifica Caja ni agrega migraciones; no implementa UC-14/F04, UC-22, UC-29, UC-05, UC-07 ni reembolsos.
