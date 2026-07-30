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


class JavaDependencyRunnerTest(unittest.TestCase):
    def test_dependency_helper_uses_fixed_central_settings_and_no_shell(self):
        helper = load("yanban_java_dependencies")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            helper.ROOT = root / "deps"
            helper.CLASSPATH = root / "classpath"

            def execute(argv, **kwargs):
                self.assertIsInstance(argv, list)
                self.assertNotIn("shell", kwargs)
                self.assertIn("/opt/yanban/maven-central-settings.xml", argv)
                helper.CLASSPATH.write_text(__file__, encoding="utf-8")
                return types.SimpleNamespace(returncode=0)

            with patch.object(helper.subprocess, "run", side_effect=execute):
                with patch.object(helper.sys, "argv", [
                        "yanban-java-dependencies",
                        "ch.qos.logback:logback-core:1.5.18"]):
                    self.assertEqual(0, helper.main())
            pom = (helper.ROOT / "pom.xml").read_text(encoding="utf-8")
            self.assertIn("<groupId>ch.qos.logback</groupId>", pom)
            self.assertNotIn("<repositories>", pom)

    def test_dependency_helper_rejects_commands_unversioned_and_excess(self):
        helper = load("yanban_java_dependencies")
        for values in (
                ["g:a"],
                ["g:a:LATEST"],
                ["g:a:1;touch-x"],
                ["g:a:1"] * 9):
            with patch.object(helper.sys, "argv", ["helper", *values]):
                with self.assertRaises(ValueError):
                    helper.main()

    def test_java_runner_uses_prepared_classpath_without_network_commands(self):
        runner = load("yanban_runner")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            runner.ROOT = root
            source = root / "Sort.java"
            source.write_text(
                    "import ch.qos.logback.core.Context; public class Sort {"
                    "public static void main(String[] a) { System.out.print(Context.class); }}",
                    encoding="utf-8")
            jar = root / "logback.jar"
            jar.write_bytes(b"jar")
            runner.JAVA_CLASSPATH = root / "classpath"
            runner.JAVA_CLASSPATH.write_text(str(jar), encoding="utf-8")
            commands = []

            def execute(argv, **_kwargs):
                commands.append(argv)
                return types.SimpleNamespace(returncode=0)

            with patch.object(runner.subprocess, "run", side_effect=execute):
                with patch.object(runner.sys, "argv", [
                        "yanban-runner", "java", "Sort.java",
                        "--dependency=ch.qos.logback:logback-core:1.5.18"]):
                    self.assertEqual(0, runner.main())
            self.assertEqual("javac", commands[0][0])
            self.assertEqual("java", commands[1][0])
            self.assertIn(str(root), commands[0][commands[0].index("-classpath") + 1])
            self.assertEqual(str(root), commands[0][commands[0].index("-sourcepath") + 1])
            self.assertIn(str(root), commands[1][commands[1].index("-classpath") + 1])
            self.assertFalse(any(command[0] in ("mvn", "curl", "wget") for command in commands))


if __name__ == "__main__":
    unittest.main()
