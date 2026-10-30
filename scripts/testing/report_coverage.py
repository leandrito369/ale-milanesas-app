#!/usr/bin/env python3
"""Mostrar cobertura JaCoCo real (líneas/ramas) en la evidencia de GitHub Actions.

No añade umbral de calidad. La clase de cátedra recomienda 70–80 %, no lo
exige como condición obligatoria formal de aprobación.
"""
import csv
from pathlib import Path

report = Path("target/site/jacoco/jacoco.csv")
if not report.is_file():
    raise SystemExit("ERROR: JaCoCo no generó jacoco.csv (no declarar cobertura)")

records = list(csv.DictReader(report.open(encoding="utf-8")))
if not records:
    raise SystemExit("ERROR: JaCoCo reportó cero clases")


def ratio(missed, covered):
    total = missed + covered
    return f"{100 * covered / total:.1f}%" if total else "N/A"


def totals(items):
    return (sum(int(r["LINE_MISSED"]) for r in items),
            sum(int(r["LINE_COVERED"]) for r in items),
            sum(int(r["BRANCH_MISSED"]) for r in items),
            sum(int(r["BRANCH_COVERED"]) for r in items))


def desc(title, items):
    ml, cl, mb, cb = totals(items)
    return (f"{title}: lineas={ratio(ml, cl)} ({cl}/{ml + cl}), "
            f"ramas={ratio(mb, cb)} ({cb}/{mb + cb})")


lines = [
    f"JaCoCo / UC53 — clases medidas={len(records)}",
    desc("TOTAL APP", records),
    desc("ACCESO", [r for r in records if ".acceso" in r["PACKAGE"]
                     or r["PACKAGE"].endswith("/acceso")]),
    desc("CAMBIO CREDENCIAL", [r for r in records
                             if r["CLASS"] in ("CambiarContrasenaService",
                                               "PoliticaCambioCredencial")]),
    "Meta de catedra 70–80 %: recomendada, no umbral de CI ni aceptación.",
]
for line in lines:
    print(line)
summary = Path("target/site/jacoco/resumen-cobertura.txt")
summary.write_text("\n".join(lines) + "\n", encoding="utf-8")
