#!/usr/bin/env python3
"""BC-06: inventario ejecutable y evidencias de BLOQUEO, sin simular negocio.

Esta herramienta NO ejecuta Cobros, entregas, webhooks o autorizaciones B4.
Sin un driver funcional real por escenario, todos quedan NO_EJECUTADO.
"""
import argparse
import csv
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, ElementTree

SOURCE_COMMIT = "1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e"
SOURCE_BLOB = "77c3343bd5b7af49b76a05880e812fd73986cb9e"
SOURCE_CSV = "docs/01-gobierno/b0/BC06_CASOS_PRUEBA_IMPLEMENTACION_18.csv"
E2E_ISSUE = "https://github.com/leandrito369/ale-milanesas-app/issues/10"
EXPECTED_IDS = [f"BC06-{i:02}" for i in range(1, 19)]
HEADERS = {"caso", "operacion", "estimulo", "resultado_esperado",
           "fuentes", "estado_ejecucion", "evidencia"}
BLOCKERS = {
    **{f"BC06-{i:02}": "B3 UC-29 y B4 RBAC transaccional: Cobro/anulacion funcional no expuesto"
       for i in range(1, 7)},
    **{f"BC06-{i:02}": "F04/S01: CR-B0-F04-001 sin diseno fisico de efectivo en transito/rendicion"
       for i in range(7, 10)},
    **{f"BC06-{i:02}": "B3 UC-26 + B6: adaptador de reembolsos y reintentos no implementado"
       for i in range(10, 13)},
    **{f"BC06-{i:02}": "B6 UC-49: adaptador webhook autenticado y concurrencia no implementados"
       for i in range(13, 17)},
    **{f"BC06-{i:02}": "B4: endpoint, autenticacion y RBAC reales por operacion no implementados"
       for i in range(17, 19)},
}


def read_matrix(path: Path) -> list[dict]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or set(reader.fieldnames) != HEADERS:
            raise ValueError("BC06: columnas de matriz distintas a la fuente normativa")
        rows = list(reader)
    ids = [row["caso"] for row in rows]
    if ids != EXPECTED_IDS or len(set(ids)) != 18:
        raise ValueError("BC06: faltan casos, hay duplicados o difiere el orden G0")
    for row in rows:
        if not all(row.get(key) for key in HEADERS):
            raise ValueError(f"BC06: campos vacios en {row['caso']}")
        if row["estado_ejecucion"] != "NO_EJECUTADO_SIN_BACKEND" or row["evidencia"] != "N/A":
            raise ValueError(f"BC06: fuente G0 inesperadamente modificada: {row['caso']}")
    return rows


def verify_g0_checkout(root: Path) -> Path:
    sha = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"],
                                  text=True).strip()
    if sha != SOURCE_COMMIT:
        raise ValueError(f"Fuente G0 incorrecta: {sha}")
    source = root / SOURCE_CSV
    blob = subprocess.check_output(["git", "-C", str(root), "hash-object", SOURCE_CSV],
                                   text=True).strip()
    if blob != SOURCE_BLOB:
        raise ValueError(f"Blob BC06 alterado: {blob}")
    return source


def make_results(rows: list[dict], app_sha: str) -> list[dict]:
    # No existe driver ejecutable que implemente los 18 escenarios.
    # No confundir invocacion del catalogo con ejecucion funcional.
    return [{
        "id": row["caso"],
        "operacion": row["operacion"],
        "estimulo": row["estimulo"],
        "resultado_esperado": row["resultado_esperado"],
        "fuentes": row["fuentes"],
        "estado": "NO_EJECUTADO",
        "resultado_observado": None,
        "evidencia_funcional": None,
        "razon": BLOCKERS[row["caso"]],
        "source_commit": SOURCE_COMMIT,
        "app_commit": app_sha or None,
    } for row in rows]


def write_evidence(results: list[dict], dest: Path, app_sha: str) -> dict:
    dest.mkdir(parents=True, exist_ok=True)
    metadata = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "source_commit": SOURCE_COMMIT,
        "source_blob": SOURCE_BLOB,
        "app_commit": app_sha or None,
        "run_id": os.getenv("GITHUB_RUN_ID"),
        "matrix_total": len(results),
        "functional_executed": 0,
        "functional_passed": 0,
        "not_executed": len(results),
        "e2e_uc54": {"documented": 4, "executed_here": 0, "reference": E2E_ISSUE},
        "status": "READINESS_ONLY_NOT_EXECUTION",
        "results": results,
    }
    (dest / "bc06-resultados.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    with (dest / "bc06-resultados.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["id", "operacion", "estado",
                                                     "razon", "app_commit", "source_commit"])
        writer.writeheader()
        writer.writerows({key: row[key] for key in writer.fieldnames} for row in results)
    suite = Element("testsuite", name="BC06_PREPARACION_NO_FUNCIONAL", tests=str(len(results)),
                    failures="0", errors="0", skipped=str(len(results)))
    for row in results:
        case = SubElement(suite, "testcase", name=row["id"], classname="BC06.BLOQUEADO")
        SubElement(case, "skipped", message=row["razon"]).text = (
            "NO EJECUTADO: no se invoco servicio de negocio, API ni webhook")
    ElementTree(suite).write(dest / "bc06-skip-report.xml", encoding="utf-8",
                             xml_declaration=True)
    summary = (
        "# BC-06: evaluacion de preparacion (no prueba funcional)\n\n"
        f"- Matriz G0 fijada: \`{SOURCE_COMMIT}\`\n"
        f"- SHA aplicacion analizada: \`{app_sha or 'no informado'}\`\n"
        f"- Casos cargados: **{len(results)}/18**\n"
        "- Escenarios funcionales ejecutados: **0/18**\n"
        f"- Escenarios bloqueados/no ejecutados: **{len(results)}/18**\n"
        "- Cobertura, aceptacion E2E, reembolsos, Cobro y F04: **NO ACREDITADOS**\n"
        f"- UC54 E2E: 4 documentados en {E2E_ISSUE}, ninguno ejecutado aqui\n\n"
        "## Motivos por caso\n\n" +
        "\n".join(f"- {row['id']}: {row['razon']}" for row in results) + "\n"
    )
    (dest / "bc06-resumen.md").write_text(summary, encoding="utf-8")
    step_summary = os.getenv("GITHUB_STEP_SUMMARY")
    if step_summary:
        with Path(step_summary).open("a", encoding="utf-8") as stream:
            stream.write(summary + "\n")
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--g0-root", default="g0-source")
    parser.add_argument("--out", default="bc06-evidence")
    args = parser.parse_args()
    source = verify_g0_checkout(Path(args.g0_root))
    results = make_results(read_matrix(source), os.getenv("GITHUB_SHA", ""))
    report = write_evidence(results, Path(args.out), os.getenv("GITHUB_SHA", ""))
    print(f"BC06: {report['matrix_total']}/18 catalogados; "
          f"{report['functional_executed']}/18 funcionales ejecutados; "
          f"{report['not_executed']}/18 NO_EJECUTADO")
    return 0  # Exito de auditoria de preparacion, NO exito de BC06 funcional.


if __name__ == "__main__":
    raise SystemExit(main())
