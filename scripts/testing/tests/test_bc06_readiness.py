"""Controles de matriz BC06: prueban catalogo/integridad, NO negocio."""
import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree

SCRIPT = Path(__file__).resolve().parents[1] / "bc06_readiness.py"
SPEC = importlib.util.spec_from_file_location("bc06_readiness", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def make_source(path, ids=None):
    ids = ids if ids is not None else module.EXPECTED_IDS
    with Path(path).open("w", newline="", encoding="utf-8") as handle:
        w = csv.DictWriter(handle, fieldnames=[
            "caso", "operacion", "estimulo", "resultado_esperado",
            "fuentes", "estado_ejecucion", "evidencia"])
        w.writeheader()
        for case_id in ids:
            w.writerow({"caso": case_id, "operacion": "operacion ficticia",
                        "estimulo": "estimulo de prueba", "resultado_esperado": "rechazo",
                        "fuentes": "RD", "estado_ejecucion": "NO_EJECUTADO_SIN_BACKEND",
                        "evidencia": "N/A"})


class BC06ReadinessTest(unittest.TestCase):
    def test_matriz_con_18_identificadores_estrictos(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "casos.csv"
            make_source(path)
            rows = module.read_matrix(path)
            self.assertEqual(18, len(rows))
            self.assertEqual(module.EXPECTED_IDS, [r["caso"] for r in rows])

    def test_matriz_incompleta_falla(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "casos.csv"
            make_source(path, module.EXPECTED_IDS[:-1])
            with self.assertRaisesRegex(ValueError, "faltan casos"):
                module.read_matrix(path)

    def test_matriz_duplicada_falla(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "casos.csv"
            make_source(path, module.EXPECTED_IDS[:17] + [module.EXPECTED_IDS[0]])
            with self.assertRaisesRegex(ValueError, "duplicados"):
                module.read_matrix(path)

    def test_no_se_inventa_pass_de_negocio(self):
        rows = [{"caso": name, "operacion": "x", "estimulo": "x",
                 "resultado_esperado": "x", "fuentes": "RD"}
                for name in module.EXPECTED_IDS]
        results = module.make_results(rows, "abc")
        self.assertEqual(18, len(results))
        self.assertEqual({"NO_EJECUTADO"}, {item["estado"] for item in results})
        self.assertTrue(all(item["resultado_observado"] is None for item in results))
        self.assertTrue(all(item["evidencia_funcional"] is None for item in results))

    def test_xml_indica_18_skip_y_cero_funcional(self):
        rows = [{"caso": name, "operacion": "x", "estimulo": "x",
                 "resultado_esperado": "x", "fuentes": "RD"}
                for name in module.EXPECTED_IDS]
        results = module.make_results(rows, "abc")
        with tempfile.TemporaryDirectory() as td:
            summary = module.write_evidence(results, Path(td), "abc")
            self.assertEqual(0, summary["functional_executed"])
            self.assertEqual(18, summary["not_executed"])
            xml = ElementTree.parse(Path(td) / "bc06-skip-report.xml").getroot()
            self.assertEqual("18", xml.attrib["skipped"])
            self.assertEqual(18, len(xml.findall(".//skipped")))
            self.assertEqual("READINESS_ONLY_NOT_EXECUTION", summary["status"])

    def test_cuatro_e2e_documentados_no_simulados(self):
        rows = [{"caso": name, "operacion": "x", "estimulo": "x",
                 "resultado_esperado": "x", "fuentes": "RD"}
                for name in module.EXPECTED_IDS]
        with tempfile.TemporaryDirectory() as td:
            summary = module.write_evidence(
                module.make_results(rows, "abc"), Path(td), "abc")
            self.assertEqual(4, summary["e2e_uc54"]["documented"])
            self.assertEqual(0, summary["e2e_uc54"]["executed_here"])


if __name__ == "__main__":
    unittest.main()
