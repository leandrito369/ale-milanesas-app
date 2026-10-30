"""Pruebas negativas del control de integridad de workflows."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from validate_workflows import EXPECTED, WORKFLOWS, SOURCE_G0, check_actions, check_workflow


class PinningTests(unittest.TestCase):
    def test_sha_completo_permitido(self):
        content = "steps:\n  - uses: actions/checkout@" + EXPECTED["actions/checkout"]
        self.assertEqual([], check_actions(content))

    def test_ref_mutable_rechazada(self):
        self.assertTrue(any("mutable" in x for x in check_actions(
            "steps:\n  - uses: actions/checkout@v4")))

    def test_sha_corto_rechazado(self):
        self.assertTrue(check_actions("steps:\n  - uses: actions/setup-java@b843ebf"))

    def test_sha_desconocido_rechazado(self):
        self.assertTrue(any("distinto" in x for x in check_actions(
            "steps:\n  - uses: actions/checkout@" + "a" * 40)))

    def test_accion_no_permitida_rechazada(self):
        self.assertTrue(any("no aprobada" in x for x in check_actions(
            "steps:\n  - uses: thirdparty/unknown@" + "a" * 40)))

    def test_recuento_estricto(self):
        self.assertTrue(check_actions("steps:\n  - uses: actions/checkout@" +
                                      EXPECTED["actions/checkout"],
                                      WORKFLOWS[".github/workflows/b1-build.yml"]))

    def test_g0_ausente_detectado(self):
        path = ".github/workflows/b2-migrations.yml"
        content = Path(path).read_text(encoding="utf-8").replace(
            SOURCE_G0, "a" * 40)
        self.assertTrue(any("G0" in x for x in check_workflow(path, content)))

    def test_workflows_reales_validos(self):
        for path in WORKFLOWS:
            with self.subTest(path=path):
                self.assertEqual([], check_workflow(
                    path, Path(path).read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
