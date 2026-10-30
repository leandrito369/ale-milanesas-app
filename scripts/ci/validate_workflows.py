#!/usr/bin/env python3
"""Control estático de GitHub Actions: solo acciones permitidas con SHA completo.

No sustituye la ejecución real del workflow ni audita el contenido de la acción.
"""
from collections import Counter
from pathlib import Path
import re
import sys

EXPECTED = {
    "actions/checkout": "11bd71901bbe5b1630ceea73d27597364c9af683",  # v4.2.2
    "actions/setup-java": "b843ebf9b913146b38da2eaa7aeddf76074ae1ad",  # v6.1.0
    "actions/upload-artifact": "ea165f8d65b6e75b540449e92b4886f43607fa02",  # v4.6.2
}
WORKFLOWS = {
    ".github/workflows/b1-build.yml": Counter({
        "actions/checkout": 1,
        "actions/setup-java": 1,
        "actions/upload-artifact": 1,
    }),
    ".github/workflows/b2-migrations.yml": Counter({
        "actions/checkout": 2,
        "actions/setup-java": 1,
        "actions/upload-artifact": 1,
    }),
}
SOURCE_G0 = "1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e"
USES = re.compile(r"(?m)^\s*(?:-\s*)?uses:\s*([^\s#]+)")


def check_actions(content: str, required: Counter | None = None) -> list[str]:
    """Reportar usos variables, acciones ajenas o SHAs inesperados."""
    errors = []
    counts: Counter = Counter()
    for value in USES.findall(content):
        if "@" not in value:
            errors.append(f"Accion sin ref: {value}")
            continue
        action, ref = value.rsplit("@", 1)
        counts[action] += 1
        if action not in EXPECTED:
            errors.append(f"Accion no aprobada: {action}")
        elif not re.fullmatch(r"[0-9a-f]{40}", ref):
            errors.append(f"Ref mutable o SHA incompleto: {value}")
        elif ref != EXPECTED[action]:
            errors.append(f"SHA distinto del aprobado: {value}")
    if required is not None and counts != required:
        errors.append(f"Acciones esperadas {dict(required)}; observadas {dict(counts)}")
    return errors


def check_workflow(path: str, content: str) -> list[str]:
    errors = check_actions(content, WORKFLOWS[path])
    if "on:\n  pull_request:\n  push:\n    branches: [main]\n  workflow_dispatch:" not in content:
        errors.append("Se requiere CI en PR, push a main y ejecucion manual, sin filtros de rutas")
    if content.count("persist-credentials: false") < WORKFLOWS[path]["actions/checkout"]:
        errors.append("Cada checkout debe desactivar persistencia de credenciales")
    if "mvn -B -ntp clean verify" not in content:
        errors.append("Falta Maven verify")
    if "python3 scripts/ci/validate_workflows.py" not in content:
        errors.append("Falta ejecutar control de SHA")
    if "python3 -m unittest discover -s scripts/ci/tests -v" not in content:
        errors.append("Faltan tests unitarios del control")
    if "if: always()" not in content or "ci-evidence/" not in content:
        errors.append("Falta evidencia adjunta incluso ante fallos")
    if path.endswith("b2-migrations.yml"):
        if f"ref: {SOURCE_G0}" not in content:
            errors.append("G0 no esta fijado en el SHA canonico")
        if "bash scripts/b2/compare_mr6.sh" not in content:
            errors.append("Falta cotejo triple MR6")
    return errors


def main() -> int:
    findings = []
    for path in WORKFLOWS:
        file = Path(path)
        if not file.is_file():
            findings.append(f"{path}: no existe")
            continue
        findings.extend(f"{path}: {e}" for e in check_workflow(path, file.read_text(encoding="utf-8")))
    if findings:
        print("\n".join(f"ERROR: {f}" for f in findings), file=sys.stderr)
        return 1
    print("CI SHA guard: OK; 7 usos de acciones con SHA completo, G0 fijo y rutas de evidencia")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
