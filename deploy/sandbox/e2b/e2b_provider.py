#!/usr/bin/env python3
"""Narrow E2B adapter for the governed Sandbox Broker; never accepts a shell string."""

import argparse
import json
import math
import os
from pathlib import Path, PurePosixPath
import shlex
import signal
import sys

try:
    from e2b import CommandExitException, Sandbox, SandboxQuery
except Exception:
    # Import failures happen before main() can apply the normal bounded
    # provider diagnostic. Keep this failure safe and actionable too.
    print("E2B provider error: SDK_IMPORT_FAILED:", file=sys.stderr)
    raise SystemExit(70)


MANAGED_KEY = "yanban-managed"
NAME_KEY = "yanban-name"
REMOTE_ROOT = PurePosixPath("/home/user/project")
TEMURIN_HOME = "/opt/yanban/temurin-17"
JAVA_ENV = {
    "JAVA_HOME": TEMURIN_HOME,
    "PATH": (
        f"{TEMURIN_HOME}/bin:/usr/local/sbin:/usr/local/bin:"
        "/usr/sbin:/usr/bin:/sbin:/bin"
    ),
}
JAVA_COMMANDS = frozenset((
    "java", "javac", "mvn", "yanban-runner",
    "yanban-java-dependencies",
))
active_sandbox_id = None


def managed(name=None):
    metadata = {MANAGED_KEY: "true"}
    if name:
        metadata[NAME_KEY] = name
    paginator = Sandbox.list(query=SandboxQuery(metadata=metadata), limit=100)
    result = []
    while paginator.has_next:
        result.extend(paginator.next_items())
    return result


def exact(name):
    matches = [item for item in managed(name) if item.metadata.get(NAME_KEY) == name]
    if len(matches) > 1:
        raise RuntimeError("duplicate managed E2B sandbox identity")
    return matches[0] if matches else None


def terminate_on_signal(signum, _frame):
    if active_sandbox_id:
        try:
            Sandbox.kill(active_sandbox_id)
        except Exception:
            pass
    raise SystemExit(128 + signum)


def command_health(_args):
    managed()
    print(json.dumps({"status": "UP", "provider": "e2b"}, separators=(",", ":")))
    return 0


def upload_workspace(sandbox, workspace, manifests_only=False,
                     dependency_declarations_only=False):
    workspace = Path(workspace).resolve(strict=True)
    for source in sorted(workspace.rglob("*")):
        if source.is_symlink():
            raise RuntimeError("workspace links are forbidden")
        if not source.is_file():
            continue
        if dependency_declarations_only:
            continue
        relative = source.relative_to(workspace)
        if manifests_only and source.name != "pom.xml":
            continue
        remote = REMOTE_ROOT.joinpath(*relative.parts).as_posix()
        sandbox.files.write(remote, source.read_bytes(), request_timeout=60)


def command_create(args):
    global active_sandbox_id
    dependency_declarations_only = getattr(
        args, "dependency_declarations_only", False)
    if dependency_declarations_only and not args.dependency_network:
        raise RuntimeError(
            "dependency-declarations-only mode requires dependency networking")
    if exact(args.name):
        raise RuntimeError("managed E2B sandbox already exists")
    ttl_seconds = min(3600, max(180, math.ceil(args.timeout_millis / 1000) + 120))
    create_options = {
        "timeout": ttl_seconds,
        "metadata": {MANAGED_KEY: "true", NAME_KEY: args.name},
        "envs": {},
        "secure": True,
        "lifecycle": {"on_timeout": "kill", "auto_resume": False},
    }
    if args.dependency_network:
        create_options["allow_internet_access"] = True
    else:
        create_options["allow_internet_access"] = False
    sandbox = Sandbox.create(args.template, **create_options)
    active_sandbox_id = sandbox.sandbox_id
    try:
        info = sandbox.get_info()
        if info.cpu_count > args.cpus or info.memory_mb * 1024 * 1024 > args.memory_bytes:
            raise RuntimeError("E2B template exceeds the requested resource limits")
        upload_workspace(sandbox, args.workspace, manifests_only=args.dependency_network,
                         dependency_declarations_only=dependency_declarations_only)
        print(json.dumps({"name": args.name, "sandboxId": sandbox.sandbox_id}, separators=(",", ":")))
        active_sandbox_id = None
        return 0
    except BaseException:
        try:
            sandbox.kill()
        finally:
            active_sandbox_id = None
        raise


def command_network(args):
    item = exact(args.name)
    if not item:
        raise RuntimeError("managed E2B sandbox not found")
    sandbox = Sandbox.connect(item.sandbox_id)
    sandbox.update_network({"allow_internet_access": False})
    return 0


def command_sync(args):
    item = exact(args.name)
    if not item:
        raise RuntimeError("managed E2B sandbox not found")
    sandbox = Sandbox.connect(item.sandbox_id)
    upload_workspace(sandbox, args.workspace)
    return 0


def command_policy(args):
    item = exact(args.name)
    if not item:
        raise RuntimeError("managed E2B sandbox not found")
    info = Sandbox.get_info(item.sandbox_id)
    network = info.network or {}
    allow_out = sorted(network.get("allow_out") or [])
    deny_out = sorted(network.get("deny_out") or [])
    if args.expect == "dependency-network":
        if info.allow_internet_access is False or "0.0.0.0/0" in deny_out:
            raise RuntimeError("E2B dependency network is not active")
        print(json.dumps({
            "origin": "scoped",
            "resource_type": "network",
            "status": "active",
            "decision": "allow",
            "resources": ["**"],
        }, separators=(",", ":")))
        return 0
    if info.allow_internet_access is not False and "0.0.0.0/0" not in deny_out:
        raise RuntimeError("E2B deny-all network policy is not active")
    print(json.dumps({
        "origin": "scoped",
        "resource_type": "network",
        "status": "active",
        "decision": "deny",
        "resources": ["**"],
    }, separators=(",", ":")))
    return 0


def command_exec(args):
    global active_sandbox_id
    item = exact(args.name)
    if not item:
        raise RuntimeError("managed E2B sandbox not found")
    argv = list(args.argv)
    if argv and argv[0] == "--":
        argv = argv[1:]
    if not argv:
        raise RuntimeError("empty governed command")
    sandbox = Sandbox.connect(item.sandbox_id)
    active_sandbox_id = item.sandbox_id
    try:
        command_env = JAVA_ENV if argv[0] in JAVA_COMMANDS else {}
        try:
            result = sandbox.commands.run(
                shlex.join(argv),
                cwd=REMOTE_ROOT.as_posix(),
                envs=command_env,
                stdin=False,
                timeout=0,
            )
        except CommandExitException as error:
            # A governed user command returning non-zero is an execution result,
            # not an E2B provider failure.
            sys.stdout.write(error.stdout or "")
            sys.stderr.write(error.stderr or "")
            return int(error.exit_code)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        return int(result.exit_code)
    finally:
        active_sandbox_id = None


def command_kill(args):
    item = exact(args.name)
    if item:
        Sandbox.kill(item.sandbox_id)
    return 0


def command_list(_args):
    print(json.dumps([
        {"name": item.metadata.get(NAME_KEY), "sandboxId": item.sandbox_id, "state": str(item.state)}
        for item in managed()
    ], separators=(",", ":")))
    return 0


def parser():
    root = argparse.ArgumentParser(add_help=False)
    commands = root.add_subparsers(dest="command", required=True)
    commands.add_parser("health", add_help=False).set_defaults(handler=command_health)
    create = commands.add_parser("create", add_help=False)
    create.add_argument("--name", required=True)
    create.add_argument("--workspace", required=True)
    create.add_argument("--template", required=True)
    create.add_argument("--cpus", required=True, type=int)
    create.add_argument("--memory-bytes", required=True, type=int)
    create.add_argument("--timeout-millis", required=True, type=int)
    create.add_argument("--dependency-network", action="store_true")
    create.add_argument("--dependency-declarations-only", action="store_true")
    create.set_defaults(handler=command_create)
    policy = commands.add_parser("policy", add_help=False)
    policy.add_argument("--name", required=True)
    policy.add_argument("--expect", required=True, choices=("deny-all", "dependency-network"))
    policy.set_defaults(handler=command_policy)
    network = commands.add_parser("network", add_help=False)
    network.add_argument("--name", required=True)
    network.add_argument("--mode", required=True, choices=("deny-all",))
    network.set_defaults(handler=command_network)
    sync = commands.add_parser("sync", add_help=False)
    sync.add_argument("--name", required=True)
    sync.add_argument("--workspace", required=True)
    sync.set_defaults(handler=command_sync)
    execute = commands.add_parser("exec", add_help=False)
    execute.add_argument("--name", required=True)
    execute.add_argument("argv", nargs=argparse.REMAINDER)
    execute.set_defaults(handler=command_exec)
    kill = commands.add_parser("kill", add_help=False)
    kill.add_argument("--name", required=True)
    kill.set_defaults(handler=command_kill)
    commands.add_parser("list", add_help=False).set_defaults(handler=command_list)
    return root


def provider_error_code(error):
    message = str(error).lower()
    if "network" in message and any(
            marker in message for marker in (
                "not supported", "not available", "requires", "plan", "tier",
                "upgrade", "feature", "access", "billing", "paid", "team",
            )):
        return "NETWORK_POLICY_UNAVAILABLE"
    if "network" in message and any(
            marker in message for marker in ("invalid", "validation", "bad request", "400")):
        return "NETWORK_POLICY_INVALID"
    for status, code in (
            ("401", "UNAUTHORIZED"),
            ("403", "FORBIDDEN"),
            ("429", "RATE_LIMITED"),
            ("500", "PROVIDER_INTERNAL"),
            ("502", "PROVIDER_BAD_GATEWAY"),
            ("503", "PROVIDER_UNAVAILABLE")):
        if status in message:
            return code
    return type(error).__name__


def main():
    if not os.environ.get("E2B_API_KEY"):
        raise RuntimeError("E2B_API_KEY is required")
    signal.signal(signal.SIGTERM, terminate_on_signal)
    signal.signal(signal.SIGINT, terminate_on_signal)
    args = parser().parse_args()
    return args.handler(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception as error:
        # Emit a bounded diagnostic code only. Provider messages can contain
        # request details and must not flow into user-visible receipts.
        print(f"E2B provider error: {provider_error_code(error)}:", file=sys.stderr)
        raise SystemExit(70)
