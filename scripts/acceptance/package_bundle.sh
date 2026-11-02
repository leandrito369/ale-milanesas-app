#!/usr/bin/env bash
# Empaquetar SOLO despues de mvn clean verify exitoso. No despliega ni certifica.
set -euo pipefail
test "$#" -eq 2 || { echo 'Uso: package_bundle.sh SHA_40 URL_RUN' >&2; exit 2; }
[[ "$1" =~ ^[0-9a-f]{40}$ ]] || { echo 'SHA invalido' >&2; exit 2; }
task_root=$(git rev-parse --show-toplevel)
cd "$task_root"
test "$(git rev-parse HEAD)" = "$1" || { echo 'HEAD no coincide con SHA' >&2; exit 2; }
if ! git diff --quiet || ! git diff --cached --quiet \
        || test -n "$(git ls-files --others --exclude-standard)"; then
    echo 'Se requiere arbol limpio para atribuir el paquete al SHA' >&2
    exit 2
fi
test -f target/ale-milanesas-app-0.1.0-SNAPSHOT.jar
task_bundle=target/acceptance-bundle
# Rechazar reutilizacion para no mezclar evidencias/artefactos de otro SHA.
test ! -e "$task_bundle"
mkdir -p "$task_bundle"/docs/implementation "$task_bundle"/docs/traceability "$task_bundle"/deploy
cp target/ale-milanesas-app-0.1.0-SNAPSHOT.jar "$task_bundle/app.jar"
cp deploy/acceptance/* "$task_bundle/deploy/"
cp deploy/nginx/ale-milanesas.nginx.conf.template "$task_bundle/deploy/"
cp docs/traceability/source-lock.md "$task_bundle/docs/traceability/"
cp docs/implementation/B4_02{D,E}_*.md "$task_bundle/docs/implementation/"
cp docs/implementation/B9_01_*.md "$task_bundle/docs/implementation/"
python3 - "$1" "$2" "$task_bundle/manifest.json" <<'PY'
import json, sys
from datetime import datetime, timezone
from pathlib import Path
Path(sys.argv[3]).write_text(json.dumps({
    "application_sha": sys.argv[1], "ci_run": sys.argv[2],
    "packaged_at_utc": datetime.now(timezone.utc).isoformat(),
    "source_commit": "1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e",
    "governance_commit": "27738b089afdd2698cdae915f75c0e8089a6248a",
    "environment_status": "NO_PROVISIONADO",
    "human_e2e": "NO_EJECUTADOS_0_DE_4",
    "infrastructure_certification": "NO_EJECUTADA",
    "commercial_writes_enabled": False
}, indent=2) + "\n", encoding="utf-8")
PY
cd "$task_bundle"
find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
sha256sum -c SHA256SUMS
