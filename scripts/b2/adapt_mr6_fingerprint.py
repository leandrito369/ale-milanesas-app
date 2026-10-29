#!/usr/bin/env python3
"""Adaptación estricta de huella MR6 para esquema de aplicación ale_gestion.
Solo sustituye nombre de esquema y excluye metadatos de Flyway.
"""
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: adapt_mr6_fingerprint.py SOURCE.sql DEST.sql")
origin = Path(sys.argv[1]).read_text(encoding="utf-8")
assert "mr5_validacion" in origin
mapped = origin.replace("mr5_validacion", "ale_gestion")
replacements = {
    "FROM information_schema.columns c WHERE c.table_schema='ale_gestion'":
        "FROM information_schema.columns c WHERE c.table_schema='ale_gestion' AND c.table_name <> 'flyway_schema_history'",
    "WHERE n.nspname='ale_gestion'\n UNION ALL":
        "WHERE n.nspname='ale_gestion' AND cl.relname <> 'flyway_schema_history'\n UNION ALL",
    "FROM pg_indexes i WHERE i.schemaname='ale_gestion'":
        "FROM pg_indexes i WHERE i.schemaname='ale_gestion' AND i.tablename <> 'flyway_schema_history'",
}
for before, after in replacements.items():
    if mapped.count(before) != 1:
        raise SystemExit(f"Not exactly one occurrence of expected fragment: {before!r}")
    mapped = mapped.replace(before, after)
Path(sys.argv[2]).write_text(mapped, encoding="utf-8")
print("MR6 fingerprint adopted (schema ale_gestion; Flyway history excluded)")
