# Governed Sandbox Broker deployment

## Recommended fast path: E2B

E2B is the recommended production provider when the host cannot or should not
run KVM-backed Docker Sandboxes. The application still talks only to the
governed Broker: Candidate/ProjectVersion binding, receipts, explicit apply,
timeouts, output limits, cancellation, and cleanup are unchanged. The E2B key
exists only in the Broker container and is never forwarded to user code.

The supplied E2B template supports Java 17, Python 3, C17, and C++20 through
fixed server-side profiles. MATLAB remains review-only and has no execution
profile. E2B sandbox Internet access is disabled on creation for ordinary
profiles. A governed Maven Project sandbox instead receives temporary
dependency-network access while only `pom.xml` manifests are uploaded. It runs
the fixed server-owned dependency preparation command, atomically tightens and
verifies deny-all networking, and only then uploads the full Workspace and runs
the original offline Maven test. Project files cannot supply the preparation
command, and no application secrets are injected. Maven's local repository is
reused between preparation and testing inside that one disposable sandbox; no
cross-user dependency cache is shared.

Build the private template once from the repository root. Do not put the API
key in a command argument, shell history, repository file, or chat message:

```bash
cd /opt/paperagent
python3 -m venv /tmp/yanban-e2b-build
/tmp/yanban-e2b-build/bin/pip install 'e2b==2.34.0'
read -rsp 'E2B API key: ' E2B_API_KEY && export E2B_API_KEY && echo
export YANBAN_E2B_TEMPLATE=yanban-research-v1
/tmp/yanban-e2b-build/bin/python deploy/sandbox/e2b/build_template.py
unset E2B_API_KEY
rm -rf /tmp/yanban-e2b-build
```

The template defaults to Hobby-compatible 2 vCPU and 512 MiB. This is enough
for focused source execution, but a dependency-heavy Maven build may need a
Pro template with more memory.

Create the least-privilege `yanban_sandbox` database once using
`initialize-yanban-sandbox.sql.example`, then set these values in the
deployment `.env` (never commit that file):

```dotenv
YANBAN_SANDBOX_ENABLED=true
YANBAN_SANDBOX_REQUIRED_AT_STARTUP=false
YANBAN_SANDBOX_PROVIDER=e2b
YANBAN_SANDBOX_BROKER_URL=http://sandbox-broker:8091
YANBAN_SANDBOX_BROKER_TOKEN=<at-least-32-random-characters>
YANBAN_SANDBOX_DB_USER=yanban_sandbox_broker
YANBAN_SANDBOX_DB_PASSWORD=<generated-database-password>
YANBAN_SANDBOX_DB_NAME=yanban_sandbox
E2B_API_KEY=<server-side-key>
YANBAN_E2B_TEMPLATE=yanban-research-v1
```

Build and start the private Broker together with the application:

```bash
docker compose -f docker-compose.prod.yml --profile sandbox build sandbox-broker api frontend
docker compose -f docker-compose.prod.yml --profile sandbox up -d
docker compose -f docker-compose.prod.yml --profile sandbox ps
docker compose -f docker-compose.prod.yml --profile sandbox logs --tail=100 sandbox-broker api
```

The Broker has no published host port; only the API can reach it on the private
Compose network. Setting `YANBAN_SANDBOX_ENABLED=false` disables validation
without affecting ordinary chat or Candidate review.

## Optional self-hosted provider: Docker Sandboxes

The Broker is deliberately **not** a Docker Compose service. Docker Sandboxes requires host KVM access, so production runs the Broker as a host `systemd` service under the dedicated non-root `yanban-sandbox` account. That account may belong to `kvm`, but must not have Docker-socket access, API/Project volumes, `yanban_agent` credentials, or any other application secret.

An administrator creates `yanban_sandbox` once with `initialize-yanban-sandbox.sql.example`. Compose publishes MySQL only on host loopback port `13306`; the Broker receives only that schema's least-privilege account. Its environment file supplies an absolute official `sbx` path, private workspace, token, and Broker DB credentials. The Broker itself remains on `127.0.0.1:8091`. A host TLS terminator exposes only an authenticated, firewall-restricted bridge listener such as `host.docker.internal:8443`; Compose installs the `host-gateway` route and the API uses that HTTPS URL. Never publish this listener to the public Internet or expose naked HTTP on `0.0.0.0`.

`PRODUCTION` mode requires Linux, the configured dedicated user, and readable/writable `/dev/kvm`. The example unit grants only the `kvm` supplementary group and hardens filesystem/device access. It creates persistent private HOME/XDG config, data and state beneath `/var/lib/yanban-sandbox`, allowing sbx login and daemon state to survive restart. Before enabling the service, an administrator runs the official `sbx login`, `sbx policy init` with deny-all network policy, and `sbx diagnose` as the `yanban-sandbox` user with those same HOME/XDG values. These commands are deployment prerequisites, not startup automation. Absence of the Broker affects only explicit sandbox steps; `YANBAN_SANDBOX_ENABLED=false` remains the default and performs no Broker probe or connection.

For Windows 11 acceptance only, explicitly set `YANBAN_SANDBOX_BROKER_MODE=LOCAL_ACCEPTANCE`, loopback address, `remote-access=false`, a separate test database/workspace/token, and an absolute `sbx.exe`. This mode is rejected on non-Windows or non-loopback deployments and does not relax `PRODUCTION`. No real sbx acceptance was run while sbx was absent.
### Production API-to-host transport

Production Compose uses `https://host.docker.internal:8443`; it never uses
`127.0.0.1` for the Broker because that address would name the API container.
Before enabling the feature, an administrator must provision a certificate
trusted by the API JVM for `host.docker.internal`, install the accompanying
`nginx-sandbox-broker.conf.example`, and firewall port 8443 so it is reachable
only from the local container bridge. The terminator forwards only to the
Broker's loopback `127.0.0.1:8091`; the bearer token remains mandatory.
Startup validation intentionally fails when the URL, token, TLS trust, or
Broker health check is unavailable. No certificate or private key is shipped.

For Windows `LOCAL_ACCEPTANCE`, run both API and Broker natively and explicitly
set `YANBAN_SANDBOX_BROKER_URL=http://127.0.0.1:8091`; the Broker validation
requires loopback binding and `remote-access=false`. Do not use this override
inside the production API container.
