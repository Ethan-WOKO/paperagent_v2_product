#!/usr/bin/env python3
"""Build the governed E2B template without putting the API key in argv or files."""

import os
from pathlib import Path
import shlex
import sys

from e2b import CommandExitException, Sandbox, Template, default_build_logger


JAVA_DEPENDENCY = "ch.qos.logback:logback-core:1.2.13"
PYTHON_DEPENDENCY = "requests==2.32.3"
JAVA_ENV = {
    "JAVA_HOME": "/opt/yanban/temurin-17",
    "PATH": (
        "/opt/yanban/temurin-17/bin:/usr/local/sbin:/usr/local/bin:"
        "/usr/sbin:/usr/bin:/sbin:/bin"
    ),
}


def run(sandbox, argv, timeout=180):
    try:
        result = sandbox.commands.run(
            shlex.join(argv),
            cwd="/home/user/project",
            envs=JAVA_ENV if argv[0] in (
                "yanban-java-dependencies", "yanban-runner") else {},
            stdin=False,
            timeout=timeout,
        )
    except CommandExitException as error:
        detail = (error.stderr or error.stdout or "no command output").strip()
        raise RuntimeError(
            f"template verification failed: {argv[0]}: {detail[:1000]}"
        ) from error
    if result.exit_code != 0:
        raise RuntimeError(f"template verification failed: {argv[0]}")


def verify_template(template_name):
    sandbox = Sandbox.create(
        template_name,
        allow_internet_access=True,
        timeout=300,
        secure=True,
        lifecycle={"on_timeout": "kill", "auto_resume": False},
    )
    try:
        run(sandbox, ["yanban-java-dependencies", JAVA_DEPENDENCY])
        run(sandbox, ["yanban-python-dependencies", PYTHON_DEPENDENCY])
        sandbox.update_network({"allow_internet_access": False})
        if Sandbox.get_info(sandbox.sandbox_id).allow_internet_access is not False:
            raise RuntimeError("template verification failed: network remained enabled")
        sandbox.files.write(
            "/home/user/project/Smoke.java",
            "import ch.qos.logback.core.Context;"
            "public class Smoke { public static void main(String[] args) {"
            "System.out.println(Context.class.getName()); } }",
        )
        sandbox.files.write(
            "/home/user/project/smoke.py",
            "import requests\nprint(requests.__version__)\n",
        )
        run(sandbox, [
            "yanban-runner", "java", "Smoke.java",
            f"--dependency={JAVA_DEPENDENCY}",
        ])
        run(sandbox, [
            "yanban-runner", "python", "smoke.py",
            f"--dependency={PYTHON_DEPENDENCY}",
        ])
    finally:
        sandbox.kill()


def main() -> int:
    if not os.environ.get("E2B_API_KEY"):
        raise RuntimeError("E2B_API_KEY must be set in the current terminal")
    root = Path(__file__).resolve().parent
    os.chdir(root)
    template_name = os.environ.get("YANBAN_E2B_TEMPLATE", "yanban-research-v1")
    template = Template().from_dockerfile("e2b.Dockerfile")
    result = Template.build(
        template,
        template_name,
        cpu_count=2,
        memory_mb=512,
        on_build_logs=default_build_logger(),
    )
    verify_template(result.name)
    print(f"Template ready: {result.name} ({result.template_id})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception as error:
        print(f"Template build failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
