#!/usr/bin/env python3
"""Fixed multi-language runner installed in the E2B template."""

from pathlib import Path, PurePosixPath
import os
import subprocess
import sys
import re


ROOT = Path("/home/user/project")
JAVA_CLASSPATH = Path("/tmp/yanban-java-classpath")
COORDINATE = re.compile(
    r"[A-Za-z0-9](?:[A-Za-z0-9_-]{0,62}[A-Za-z0-9])?"
    r"(?:\.[A-Za-z0-9](?:[A-Za-z0-9_-]{0,62}[A-Za-z0-9])?)*:"
    r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}:[0-9][A-Za-z0-9_.+\-]{0,63}"
)


def trusted_source(value, suffixes):
    relative = PurePosixPath(value)
    if relative.is_absolute() or not relative.parts or any(part in ("", ".", "..") for part in relative.parts):
        raise ValueError("untrusted source path")
    if not any(value.endswith(suffix) for suffix in suffixes):
        raise ValueError("source extension does not match profile")
    target = ROOT.joinpath(*relative.parts).resolve(strict=True)
    target.relative_to(ROOT.resolve(strict=True))
    if not target.is_file():
        raise ValueError("source is not a regular file")
    return target


def run(argv):
    return subprocess.run(argv, cwd=ROOT, check=False).returncode


def main():
    if len(sys.argv) < 3:
        raise ValueError("expected language and one source path")
    language, value = sys.argv[1:3]
    dependency_args = sys.argv[3:]
    if language != "java" and dependency_args:
        raise ValueError("dependencies are only supported for Java")
    if language == "python":
        source = trusted_source(value, (".py",))
        return run(["python3", "-I", str(source)])
    if language == "java":
        source = trusted_source(value, (".java",))
        if not dependency_args:
            return run(["java", str(source)])
        if len(dependency_args) > 8 or len(set(dependency_args)) != len(dependency_args):
            raise ValueError("invalid dependency count")
        coordinates = []
        for argument in dependency_args:
            if not argument.startswith("--dependency="):
                raise ValueError("invalid dependency argument")
            coordinate = argument.removeprefix("--dependency=")
            if len(coordinate) > 226 or not COORDINATE.fullmatch(coordinate):
                raise ValueError("invalid Maven coordinate")
            coordinates.append(coordinate)
        if not JAVA_CLASSPATH.is_file():
            raise ValueError("prepared Java classpath is missing")
        classpath = JAVA_CLASSPATH.read_text(encoding="utf-8").strip()
        if not classpath or any(not Path(item).is_file() for item in classpath.split(os.pathsep)):
            raise ValueError("prepared Java classpath is invalid")
        output = Path("/tmp") / f"yanban-java-classes-{os.getpid()}"
        output.mkdir(mode=0o700)
        compile_classpath = str(ROOT) + os.pathsep + classpath
        compile_code = run(["javac", "-classpath", compile_classpath,
                            "-sourcepath", str(ROOT), "-d", str(output), str(source)])
        if compile_code:
            return compile_code
        text = source.read_text(encoding="utf-8")
        package = re.search(r"(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;", text)
        main_class = source.stem if package is None else package.group(1) + "." + source.stem
        return run(["java", "-classpath",
                    str(output) + os.pathsep + str(ROOT) + os.pathsep + classpath, main_class])
    output = Path("/tmp") / f"yanban-candidate-{os.getpid()}"
    if language == "c":
        source = trusted_source(value, (".c",))
        compile_code = run(["gcc", "-std=c17", "-O0", "-Wall", "-Wextra", str(source), "-o", str(output)])
    elif language == "cpp":
        source = trusted_source(value, (".cc", ".cpp", ".cxx"))
        compile_code = run(["g++", "-std=c++20", "-O0", "-Wall", "-Wextra", str(source), "-o", str(output)])
    else:
        raise ValueError("unsupported language profile")
    return compile_code if compile_code else run([str(output)])


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception as error:
        print(f"yanban-runner: {error}", file=sys.stderr)
        raise SystemExit(64)
