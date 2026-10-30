#!/usr/bin/env python3
"""Crear manifiesto verificable: observaciones del workspace, nunca éxito supuesto."""
import csv
import json
import os
from pathlib import Path
from datetime import datetime, timezone
from xml.etree import ElementTree as ET

out = Path("ci-evidence")
out.mkdir(exist_ok=True)
reports = sorted(Path("target/surefire-reports").glob("TEST-*.xml"))
totals = dict(tests=0, failures=0, errors=0, skipped=0)
issues = []
for p in reports:
    try:
        root = ET.parse(p).getroot()
        for key in totals:
            totals[key] += int(root.get(key, "0"))
    except (ET.ParseError, ValueError) as exc:
        issues.append({"file": str(p), "error": str(exc)})
coverage = {"status": "not_generated"}
p = Path("target/site/jacoco/jacoco.csv")
if p.exists():
    with p.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    coverage = {}
    for category in ("LINE", "BRANCH"):
        missed = sum(int(x[f"{category}_MISSED"]) for x in rows)
        covered = sum(int(x[f"{category}_COVERED"]) for x in rows)
        coverage[category.lower()] = {
            "covered": covered, "missed": missed,
            "percentage": round(100 * covered / (covered + missed), 2)
            if covered + missed else None
        }
manifest = {
    "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
    "repository": os.getenv("GITHUB_REPOSITORY"),
    "workflow": os.getenv("GITHUB_WORKFLOW"),
    "run_id": os.getenv("GITHUB_RUN_ID"),
    "run_attempt": os.getenv("GITHUB_RUN_ATTEMPT"),
    "commit_sha": os.getenv("GITHUB_SHA"),
    "ref": os.getenv("GITHUB_REF"),
    "source_g0_sha": "1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e",
    "test_report_files": len(reports),
    "test_totals_from_xml": totals if reports else None,
    "parse_issues": issues,
    "jacoco": coverage,
    "ci_result": "NOT_DETERMINED_INSIDE_JOB",
    "restrictions": [
        "No equivale a aceptacion funcional/E2E ni despliegue",
        "BC06 0/18 no cambia por tests de infraestructura",
        "El resultado final del workflow debe verificarse en GitHub Actions",
        "El manifiesto refleja solo ficheros disponibles en el workspace",
    ],
}
(out / "manifest.json").write_text(
    json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(json.dumps(manifest, indent=2, ensure_ascii=False))
