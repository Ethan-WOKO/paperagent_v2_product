import importlib.util
import io
import sys
import tempfile
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path


class FakeCommandExitException(Exception):
    def __init__(self, stderr, stdout, exit_code, error=None):
        super().__init__(error)
        self.stderr = stderr
        self.stdout = stdout
        self.exit_code = exit_code


class E2bProviderCommandTest(unittest.TestCase):
    def load_provider(self):
        fake_e2b = types.ModuleType("e2b")
        fake_e2b.CommandExitException = FakeCommandExitException
        fake_e2b.Sandbox = type("Sandbox", (), {})
        fake_e2b.SandboxQuery = type("SandboxQuery", (), {})
        previous = sys.modules.get("e2b")
        sys.modules["e2b"] = fake_e2b
        try:
            path = Path(__file__).with_name("e2b_provider.py")
            spec = importlib.util.spec_from_file_location("e2b_provider_under_test", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            return module
        finally:
            if previous is None:
                sys.modules.pop("e2b", None)
            else:
                sys.modules["e2b"] = previous

    def test_user_command_nonzero_is_returned_without_provider_reclassification(self):
        provider = self.load_provider()
        provider.exact = lambda _name: types.SimpleNamespace(sandbox_id="sandbox-1")

        class Commands:
            def run(self, *_args, **_kwargs):
                raise FakeCommandExitException("compile failed\n", "partial output\n", 1)

        provider.Sandbox = types.SimpleNamespace(
            connect=lambda _sandbox_id: types.SimpleNamespace(commands=Commands())
        )
        stdout = io.StringIO()
        stderr = io.StringIO()
        args = types.SimpleNamespace(name="test", argv=["java", "Sort.java"])

        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = provider.command_exec(args)

        self.assertEqual(1, code)
        self.assertEqual("partial output\n", stdout.getvalue())
        self.assertEqual("compile failed\n", stderr.getvalue())
        self.assertNotIn("E2B provider error", stderr.getvalue())

    def test_maven_uses_only_the_fixed_non_sensitive_temurin_environment(self):
        provider = self.load_provider()
        provider.exact = lambda _name: types.SimpleNamespace(sandbox_id="sandbox-1")
        observed = {}

        class Commands:
            def run(self, *_args, **kwargs):
                observed.update(kwargs)
                return types.SimpleNamespace(stdout="", stderr="", exit_code=0)

        provider.Sandbox = types.SimpleNamespace(
            connect=lambda _sandbox_id: types.SimpleNamespace(commands=Commands())
        )

        code = provider.command_exec(types.SimpleNamespace(
            name="test", argv=["mvn", "-B", "-ntp", "dependency:go-offline"]))

        self.assertEqual(0, code)
        self.assertEqual(
            {"JAVA_HOME": "/opt/yanban/temurin-17"},
            observed["envs"],
        )

    def test_dependency_network_uploads_only_poms_then_closes_before_full_sync(self):
        provider = self.load_provider()
        provider.exact = lambda _name: None
        created = {}
        updates = []
        writes = []

        class Files:
            def write(self, remote, _content, **_kwargs):
                writes.append(remote)

        class CreatedSandbox:
            sandbox_id = "sandbox-1"
            files = Files()
            commands = types.SimpleNamespace(run=lambda *_args, **_kwargs:
                types.SimpleNamespace(
                    stdout="trustedCertEntry\n",
                    stderr="",
                    exit_code=0,
                ))

            def get_info(self):
                return types.SimpleNamespace(cpu_count=1, memory_mb=256)

            def kill(self):
                pass

        class ConnectedSandbox:
            files = Files()

            def update_network(self, network):
                updates.append(network)

        def create(*_args, **kwargs):
            created.update(kwargs)
            return CreatedSandbox()

        info = types.SimpleNamespace(
            network=None,
            allow_internet_access=True,
        )
        provider.Sandbox = types.SimpleNamespace(
            create=create,
            connect=lambda _sandbox_id: ConnectedSandbox(),
            get_info=lambda _sandbox_id: info,
        )

        with tempfile.TemporaryDirectory() as workspace:
            root = Path(workspace)
            (root / "pom.xml").write_text("<project/>", encoding="utf-8")
            source = root / "src" / "Main.java"
            source.parent.mkdir()
            source.write_text("class Main {}", encoding="utf-8")
            provider.command_create(types.SimpleNamespace(
                name="test",
                template="template",
                timeout_millis=60_000,
                cpus=2,
                memory_bytes=512 * 1024 * 1024,
                workspace=workspace,
                dependency_network=True,
            ))
            self.assertEqual(["/home/user/project/pom.xml"], writes)
            provider.exact = lambda _name: types.SimpleNamespace(sandbox_id="sandbox-1")

            stdout = io.StringIO()
            with redirect_stdout(stdout):
                provider.command_policy(
                    types.SimpleNamespace(name="test", expect="dependency-network"))
            self.assertIn('"resources":["**"]', stdout.getvalue())

            provider.command_network(types.SimpleNamespace(name="test", mode="deny-all"))
            self.assertEqual({"allow_internet_access": False}, updates[-1])
            provider.command_sync(types.SimpleNamespace(name="test", workspace=workspace))
            self.assertEqual(
                [
                    "/home/user/project/pom.xml",
                    "/home/user/project/pom.xml",
                    "/home/user/project/src/Main.java",
                ],
                writes,
            )
        self.assertTrue(created["allow_internet_access"])
        self.assertNotIn("network", created)

    def test_dependency_network_policy_rejects_deny_all(self):
        provider = self.load_provider()
        provider.exact = lambda _name: types.SimpleNamespace(sandbox_id="sandbox-1")
        info = types.SimpleNamespace(
            network={"allow_out": [], "deny_out": ["0.0.0.0/0"]},
            allow_internet_access=False,
        )
        provider.Sandbox = types.SimpleNamespace(
            get_info=lambda _sandbox_id: info,
        )

        with self.assertRaisesRegex(RuntimeError, "not active"):
            provider.command_policy(
                types.SimpleNamespace(name="test", expect="dependency-network"))

    def test_coordinate_mode_uploads_no_project_files_before_network_is_closed(self):
        provider = self.load_provider()
        provider.exact = lambda _name: None
        writes = []

        class Files:
            def write(self, remote, _content, **_kwargs):
                writes.append(remote)

        created_sandbox = types.SimpleNamespace(
            sandbox_id="sandbox-1", files=Files(),
            get_info=lambda: types.SimpleNamespace(cpu_count=1, memory_mb=256),
            kill=lambda: None,
        )
        provider.Sandbox = types.SimpleNamespace(
            create=lambda *_args, **_kwargs: created_sandbox,
        )
        with tempfile.TemporaryDirectory() as workspace:
            root = Path(workspace)
            (root / "pom.xml").write_text("<project/>", encoding="utf-8")
            (root / "Sort.java").write_text("class Sort {}", encoding="utf-8")
            provider.command_create(types.SimpleNamespace(
                name="test", template="template", timeout_millis=60_000,
                cpus=1, memory_bytes=256 * 1024 * 1024, workspace=workspace,
                dependency_network=True, coordinates_only=True))
        self.assertEqual([], writes)

    def test_provider_error_code_is_bounded_and_actionable(self):
        provider = self.load_provider()

        self.assertEqual(
            "NETWORK_POLICY_UNAVAILABLE",
            provider.provider_error_code(
                RuntimeError("custom network policy requires a different plan")),
        )
        self.assertEqual(
            "NETWORK_POLICY_INVALID",
            provider.provider_error_code(
                RuntimeError("invalid network configuration: 400 bad request")),
        )
        self.assertEqual(
            "FORBIDDEN",
            provider.provider_error_code(RuntimeError("request failed with status 403")),
        )


if __name__ == "__main__":
    unittest.main()
