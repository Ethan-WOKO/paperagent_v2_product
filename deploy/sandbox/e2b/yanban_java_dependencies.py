#!/usr/bin/python3
"""Builds one Maven Central-only classpath from strict server-supplied coordinates."""

from pathlib import Path
import html
import os
import re
import subprocess
import sys


ROOT = Path("/tmp/yanban-java-dependencies")
CLASSPATH = Path("/tmp/yanban-java-classpath")
COORDINATE = re.compile(
    r"[A-Za-z0-9](?:[A-Za-z0-9_-]{0,62}[A-Za-z0-9])?"
    r"(?:\.[A-Za-z0-9](?:[A-Za-z0-9_-]{0,62}[A-Za-z0-9])?)*:"
    r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}:[0-9][A-Za-z0-9_.+\-]{0,63}"
)


def main():
    coordinates = sys.argv[1:]
    if not coordinates or len(coordinates) > 8 or len(set(coordinates)) != len(coordinates):
        raise ValueError("expected one to eight unique coordinates")
    if any(len(value) > 226 or not COORDINATE.fullmatch(value) for value in coordinates):
        raise ValueError("invalid Maven coordinate")
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    dependencies = []
    for value in coordinates:
        group, artifact, version = map(html.escape, value.split(":"))
        dependencies.append(
            "<dependency><groupId>%s</groupId><artifactId>%s</artifactId>"
            "<version>%s</version></dependency>" % (group, artifact, version)
        )
    pom = (
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
        "<modelVersion>4.0.0</modelVersion><groupId>com.yanban.sandbox</groupId>"
        "<artifactId>bounded-java-dependencies</artifactId><version>1</version>"
        "<dependencies>%s</dependencies></project>" % "".join(dependencies)
    )
    pom_path = ROOT / "pom.xml"
    pom_path.write_text(pom, encoding="utf-8")
    if CLASSPATH.exists():
        CLASSPATH.unlink()
    command = [
        "/usr/bin/mvn", "-B", "-ntp", "-s", "/opt/yanban/maven-central-settings.xml",
        "-f", str(pom_path),
        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
        "-Dmdep.outputFile=" + str(CLASSPATH),
        "-Dmdep.pathSeparator=" + os.pathsep,
        "-DincludeScope=test",
    ]
    completed = subprocess.run(command, cwd=ROOT, check=False)
    if completed.returncode != 0 or not CLASSPATH.is_file():
        return completed.returncode or 70
    classpath = CLASSPATH.read_text(encoding="utf-8").strip()
    if not classpath or any(not Path(item).is_file() for item in classpath.split(os.pathsep)):
        return 70
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception as error:
        print(f"yanban-java-dependencies: {error}", file=sys.stderr)
        raise SystemExit(64)
