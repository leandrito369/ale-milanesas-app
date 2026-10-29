#!/usr/bin/env python3
"""Adapta exclusivamente la prueba F01 G0 al schema de app, sin modificar fuente."""
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: adapt_f01_test_schema.py SOURCE.sql DEST.sql")
content = Path(sys.argv[1]).read_text(encoding="utf-8")
original = "SET LOCAL search_path TO mr5_validacion,public;"
replacement = "SET LOCAL search_path TO ale_gestion,public;"
if content.count(original) != 1:
    raise SystemExit("FAIL: versión no esperada de prueba F01")
Path(sys.argv[2]).write_text(content.replace(original, replacement), encoding="utf-8")
print("PASS: F01 test schema adapted without changing business SQL")
