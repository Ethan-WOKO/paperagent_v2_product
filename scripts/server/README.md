# Server management scripts

These scripts are intended for a Docker deployment located at `/opt/paperagent` and can be run by BaoTa scheduled tasks.

Run them with `bash`; making the files executable is optional.

```bash
bash /opt/paperagent/scripts/server/start.sh
bash /opt/paperagent/scripts/server/stop.sh
bash /opt/paperagent/scripts/server/update.sh
bash /opt/paperagent/scripts/server/status.sh
TAIL=300 bash /opt/paperagent/scripts/server/logs.sh api
FOLLOW=1 bash /opt/paperagent/scripts/server/logs.sh api
```

`update.sh` pulls the `main` branch using Git HTTP/1.1, reloads itself when the
management scripts changed, rebuilds the Docker images, starts the updated
services, and waits for the API and enabled infrastructure to become ready. It
deliberately does not run `docker compose down -v`, so persistent data volumes
are retained. The checkout must already be on the branch named by `BRANCH`
(`main` by default); the script fails instead of updating a different branch.

## ReAct Engine deployment

The production stack runs `agent-engine-reactplan` only on the private Compose
network. The API reaches it at `http://agent-engine-reactplan:8092`; no Engine
port is published on the host. Add two independent random secrets to `.env`:

```dotenv
YANBAN_AGENT_REACTPLAN_ENABLED=true
YANBAN_AGENT_REACTPLAN_ENGINE_SERVICE_TOKEN=<at-least-32-random-characters>
YANBAN_AGENT_ENGINE_GATEWAY_ENABLED=true
YANBAN_AGENT_ENGINE_GATEWAY_TASK_GRANT_SECRET=<different-at-least-32-random-characters>
DEEPSEEK_API_KEY=<server-side-model-key>
COMPOSE_PROFILES=sandbox,reactplan
YANBAN_AGENT_MAX_CONCURRENT_TASKS=20
YANBAN_AGENT_MAX_CONCURRENT_TASKS_PER_USER=3
YANBAN_AGENT_MAX_QUEUED_TASKS_PER_USER=10
YANBAN_AGENT_TASK_LEASE_SECONDS=30
YANBAN_SANDBOX_MAX_CONCURRENT_RUNS=18
YANBAN_SANDBOX_MAX_CONCURRENT_RUNS_PER_USER=3
```

ReAct checkpoints and ordered events are stored in the product MySQL database.
`status.sh` checks the private Engine with its service bearer. Do not publish
port 8092 or copy either secret
into frontend configuration. Use
`FOLLOW=1 bash /opt/paperagent/scripts/server/logs.sh agent-engine-reactplan`
to follow Engine logs.

The defaults above admit 20 running Agent tasks system-wide, at most 3 per
user, and 10 additional queued tasks per user. Sandbox execution is separately
limited to 18 system-wide and 3 per user. The MySQL-backed leases make these
limits apply across multiple Engine or Broker instances, rather than once per
container.

## Optional Python Project engine

In the **repository-root** server `.env`, add:

```dotenv
YANBAN_AGENT_PYTHON_ENABLED=true
YANBAN_AGENT_PYTHON_SERVICE_TOKEN=<independent-random-secret-at-least-32-characters>
COMPOSE_PROFILES=sandbox,reactplan,python
```

Keep the existing ReAct/gateway configuration. Compose injects this new secret
into both API and Python, and maps the existing ReAct service token to Python's
Java gateway token. Do not copy the local `agent-engine-python/.env` to the server.
The startup/update scripts automatically enable the `python` profile from the
enable flag, validate the token, and wait for the engine healthcheck. The explicit
`COMPOSE_PROFILES` value also supports direct `docker compose` invocations.

After the updated code is on the server, build and start with:

```bash
docker compose -f docker-compose.prod.yml up -d --build
bash scripts/server/status.sh
docker compose -f docker-compose.prod.yml logs -f agent-engine-python
```

The Python service has no published host port. API uses
`http://agent-engine-python:8097`; Python uses `http://api:8080`.
Its local graph state uses the `python_engine_data` named volume with one worker.
Container replacement retains that state, but interrupted tasks still require
explicit resubmission; this does not introduce automatic recovery. Do not use
`down -v` for ordinary updates. TS remains the default Project engine.

To stop only Python, cancel/drain Python tasks, then run
`docker compose -f docker-compose.prod.yml stop agent-engine-python`.
For lasting disablement also set the enable flag to `false`, remove `python`
from `COMPOSE_PROFILES`, and recreate API to apply its environment. Preserve the
data volume. Ctrl+C on `logs -f` only stops following logs, not the container.

## E2B sandbox deployment

The server only needs the repository-root `.env`; do not copy the Windows
`.env.sandbox.local` file. To enable E2B, set:

```dotenv
COMPOSE_PROFILES=sandbox
YANBAN_SANDBOX_ENABLED=true
YANBAN_SANDBOX_REQUIRED_AT_STARTUP=false
YANBAN_SANDBOX_PROVIDER=e2b
YANBAN_SANDBOX_BROKER_URL=http://sandbox-broker:8091
YANBAN_SANDBOX_BROKER_TOKEN=<at-least-32-random-characters>
YANBAN_SANDBOX_DB_NAME=yanban_sandbox
YANBAN_SANDBOX_DB_USER=yanban_sandbox_broker
YANBAN_SANDBOX_DB_PASSWORD=<16-to-128-safe-random-characters>
E2B_API_KEY=<server-side-e2b-key>
YANBAN_E2B_TEMPLATE=yanban-research-v1
```

`YANBAN_SANDBOX_DB_PASSWORD` may contain letters, digits, `.`, `_`, `~`, and
`-`. The `sandbox-db-init` one-shot Compose service creates or reconciles the
least-privilege Broker database account before the Broker starts. It never
deletes application data or volumes. The E2B API key is passed only to the
private Broker container, not to the API, frontend, database initializer, or
executed Candidate.

The named E2B template must already exist in the account that owns
`E2B_API_KEY`. Building `yanban-research-v1` once from
`deploy/sandbox/e2b/e2b.Dockerfile` is sufficient for both local and server
deployments that use the same E2B account. Java, Python, gcc/g++, and the E2B
SDK do not need to be installed on the cloud host: the Broker image contains
its client runtime and the compilers/interpreters live in the remote template.

`COMPOSE_PROFILES=sandbox` is important for the first upgrade from an older
`update.sh`: the old script will still activate the new profiled services after
pulling the repository. Future versions also select the profile automatically
when `YANBAN_SANDBOX_ENABLED=true`.

After editing `.env`, deployment remains one command:

```bash
bash /opt/paperagent/scripts/server/update.sh
```

Use `bash /opt/paperagent/scripts/server/status.sh` to verify the application
and the private Broker, and `FOLLOW=1 bash
/opt/paperagent/scripts/server/logs.sh sandbox-broker` for Broker logs.
