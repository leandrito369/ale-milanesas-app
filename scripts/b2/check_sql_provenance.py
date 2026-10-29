#!/usr/bin/env python3
"""B2: comprobar que V1/V2 derivan SIN cambios funcionales del G0.
Ejecutar con el repositorio documental fijado a SOURCE_COMMIT en ./g0-source.
No descifra ni interpreta reglas; solo coteja el contenido SQL byte a byte.
"""
from pathlib import Path

SOURCE = Path("g0-source/docs/05-modelo-relacional/sql")
MIGRATION = Path("src/main/resources/db/migration")
FILES = (
    (SOURCE / "MR5_DDL_NO_EJECUTADO.sql", MIGRATION / "V1__baseline_mr5.sql", 2),
    (SOURCE / "f01/F01_PATCH_POST_MR5.sql", MIGRATION / "V2__f01_cobro_estados.sql", 3),
)

for origin, target, removed_count in FILES:
    reference = origin.read_text(encoding="utf-8")
    lines = reference.splitlines(keepends=True)
    kept = []
    removed = 0
    for line in lines:
        if line.strip() in ("BEGIN;", "COMMIT;", r"\set ON_ERROR_STOP on"):
            removed += 1
        else:
            kept.append(line)
    if removed != removed_count:
        raise SystemExit(f"FAIL: {origin}: expected {removed_count} delimiters, got {removed}")

    expected = "".join(kept)
    rendered = target.read_text(encoding="utf-8")
    if not rendered.startswith("-- B2: Migracion Flyway derivada"):
        raise SystemExit(f"FAIL: {target}: missing provenance header")
    if expected not in rendered or not rendered.endswith(expected):
        raise SystemExit(f"FAIL: {target}: source SQL changed beyond permitted delimiters")
    print(f"PASS source fidelity: {origin.name} => {target.name}; {removed} delimiters removed")

print("PASS: no new functional SQL introduced beyond canonical MR5 + F01.")
