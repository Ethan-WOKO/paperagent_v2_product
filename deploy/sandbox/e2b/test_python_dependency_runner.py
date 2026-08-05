import importlib.util
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch


def load(name):
    path = Path(__file__).with_name(name + ".py")
    spec = importlib.util.spec_from_file_location(name + "_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PythonDependencyRunnerTest(unittest.TestCase):
    def test_dependency_helper_uses_pinned_pypi_wheels_without_shell(self):
        helper = load("yanban_python_dependencies")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            helper.SITE = root / "site"
            helper.MANIFEST = root / "requirements"
            commands = []

            def execute(argv, **kwargs):
                commands.append(argv)
                self.assertIsInstance(argv, list)
                self.assertNotIn("shell", kwargs)
                (helper.SITE / "installed.py").write_text(
                    "ok", encoding="utf-8")
                return types.SimpleNamespace(returncode=0)

            with patch.object(helper.subprocess, "run", side_effect=execute):
                with patch.object(helper.sys, "argv", [
                        "yanban-python-dependencies",
                        "requests==2.32.3", "numpy==2.2.6"]):
                    self.assertEqual(0, helper.main())
            self.assertEqual("/usr/bin/python3", commands[0][0])
            self.assertIn("--isolated", commands[0])
            self.assertIn("--only-binary=:all:", commands[0])
            self.assertIn("https://pypi.org/simple", commands[0])
            self.assertEqual(
                ["requests==2.32.3", "numpy==2.2.6"],
                helper.MANIFEST.read_text(encoding="utf-8").splitlines())

    def test_dependency_helper_rejects_unpinned_urls_markers_and_duplicates(self):
        helper = load("yanban_python_dependencies")
        for values in (
                ["requests"],
                ["requests>=2"],
                ["requests==latest"],
                ["requests==2.32.3;python_version>'3'"],
                ["https://evil.invalid/package.whl"],
                ["my_package==1.0", "my-package==1.1"],
                [f"p{index}==1.0" for index in range(9)]):
            with patch.object(helper.sys, "argv", ["helper", *values]):
                with self.assertRaises(ValueError):
                    helper.main()

    def test_python_runner_uses_only_the_prepared_matching_site(self):
        runner = load("yanban_runner")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            runner.ROOT = root
            runner.PYTHON_SITE = root / "site"
            runner.PYTHON_SITE.mkdir()
            (runner.PYTHON_SITE / "requests.py").write_text(
                "", encoding="utf-8")
            runner.PYTHON_REQUIREMENTS = root / "requirements"
            runner.PYTHON_REQUIREMENTS.write_text(
                "requests==2.32.3\n", encoding="utf-8")
            source = root / "study.py"
            source.write_text("import requests\n", encoding="utf-8")
            commands = []

            def execute(argv, **_kwargs):
                commands.append(argv)
                return types.SimpleNamespace(returncode=0)

            with patch.object(runner.subprocess, "run", side_effect=execute):
                with patch.object(runner.sys, "argv", [
                        "yanban-runner", "python", "study.py",
                        "--dependency=requests==2.32.3"]):
                    self.assertEqual(0, runner.main())
            self.assertEqual(["/usr/bin/python3", "-I", "-c"], commands[0][:3])
            self.assertIn(repr(str(runner.PYTHON_SITE)), commands[0][3])
            self.assertNotIn("pip", commands[0])


if __name__ == "__main__":
    unittest.main()
