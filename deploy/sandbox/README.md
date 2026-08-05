# Governed Sandbox Broker deployment

## E2B provider

E2B is the sole sandbox provider. The application talks only to the governed
Broker: Candidate/ProjectVersion binding, receipts, explicit apply, timeouts,
output limits, cancellation, and cleanup remain enforced. The E2B key exists
only in the Broker container and is never forwarded to user code.

The supplied E2B template supports Java 17, Python 3, C17, and C++20 through
fixed server-side profiles. MATLAB remains review-only and has no execution
profile. E2B sandbox Internet access is disabled on creation for ordinary
profiles. Governed Maven projects, Java source commands with exact Maven
coordinates, and Python source commands with exact pinned package versions
receive temporary dependency-network access before user code is uploaded. The
Broker runs a fixed server-owned download command, atomically tightens and
verifies deny-all networking, and only then uploads the full Workspace and runs
the original command offline. Project files cannot supply the preparation
command, and no application secrets are injected. Prepared dependencies are
reused only inside that disposable sandbox; no cross-user cache is shared.

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
