#!/usr/bin/python3
"""Installs bounded, exactly pinned PyPI wheels for one Python execution."""

from pathlib import Path
import re
import shutil
import subprocess
import sys


SITE = Path("/tmp/yanban-python-site")
MANIFEST = Path("/tmp/yanban-python-requirements")
REQUIREMENT = re.compile(
    r"[A-Za-z0-9](?:[A-Za-z0-9._-]{0,78}[A-Za-z0-9])?"
    r"==[0-9][A-Za-z0-9.!+_-]{0,63}"
)


def main():
    requirements = sys.argv[1:]
    canonical_names = {
        re.sub(r"[-_.]+", "-", value.split("==", 1)[0].lower())
        for value in requirements
    }
    if (not requirements or len(requirements) > 8
            or len(canonical_names) != len(requirements)):
        raise ValueError("expected one to eight unique pinned requirements")
    if any(len(value) > 146 or not REQUIREMENT.fullmatch(value)
           for value in requirements):
        raise ValueError("invalid pinned Python requirement")
    if SITE.exists():
        shutil.rmtree(SITE)
    SITE.mkdir(mode=0o700, parents=True)
    if MANIFEST.exists():
        MANIFEST.unlink()
    command = [
        "/usr/bin/python3", "-I", "-m", "pip", "--isolated", "install",
        "--disable-pip-version-check", "--no-input",
        "--only-binary=:all:",
        "--index-url", "https://pypi.org/simple",
        "--target", str(SITE),
        *requirements,
    ]
    completed = subprocess.run(command, cwd=SITE, check=False)
    if completed.returncode != 0 or not any(SITE.iterdir()):
        return completed.returncode or 70
    MANIFEST.write_text("\n".join(requirements) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception as error:
        print(f"yanban-python-dependencies: {error}", file=sys.stderr)
        raise SystemExit(64)
