#!/usr/bin/env python3
"""Compara restauración MR6: igualdad exacta salvo deparser ANY(ARRAY) PostgreSQL.

La restauración pg_dump/pg_restore reserializa 38 CHECK (... ANY(ARRAY ...)):
se acepta SOLO si son constraints, conservan identificadores y tokens/literales
y la única diferencia es paréntesis/casts redundantes. Los demás objetos se
comparan byte a byte. NO se usa para comparar rutas originales MR6.
"""
import re
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: compare_restored_fingerprint.py REFERENCIA.huela RESTAURADA.huella")

original = Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
restored = Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()
if len(original) != len(restored):
    raise SystemExit(f"FAIL: object count changed: {len(original)} != {len(restored)}")

def normalize_array_cast_and_redundant_parentheses(value: str) -> str:
    # La adaptación solo se aplica a un CHECK con ANY(ARRAY), nunca a otra DDL.
    value = re.sub(r"\('((?:''|[^'])*)'::character varying\)::text",
                   r"'\1'::character varying", value)
    return value.replace("::text[]", "").replace("(", "").replace(")", "")

accepted_variants = 0
for index, (before, after) in enumerate(zip(original, restored), start=1):
    if before == after:
        continue
    key_b = before.split("|", 2)[:2]
    key_a = after.split("|", 2)[:2]
    if key_b != key_a:
        raise SystemExit(f"FAIL: object identity changed at row {index}: {key_b} != {key_a}")
    if not before.startswith("CONSTRAINT|") or not after.startswith("CONSTRAINT|"):
        raise SystemExit(f"FAIL: non-constraint definition changed: {key_b}")
    if "ANY (" not in before or "ANY (" not in after:
        raise SystemExit(f"FAIL: constraint without ANY changed: {key_b}")
    if normalize_array_cast_and_redundant_parentheses(before) != normalize_array_cast_and_redundant_parentheses(after):
        raise SystemExit(f"FAIL: business-check tokens differ: {key_b}")
    accepted_variants += 1

print(f"PASS restore: {len(original)} objects; "
      f"{len(original) - accepted_variants} exact definitions and "
      f"{accepted_variants} CHECK ANY(ARRAY) deparser-only variants; "
      "no unidentified differences.")
