# Local development

This is the Windows PowerShell helper workflow for the complete editor/execution
demo. It starts local services only. AWS operation is separate and documented in
[DEPLOYMENT.md](DEPLOYMENT.md).

## Prerequisites

- Windows with PowerShell 7.4 or newer.
- JDK 21 on `PATH` (CI pins Temurin 21.0.12+1).
- Node 24 and npm on `PATH` (CI pins Node 24.19.0).
- Docker Desktop using Linux containers, Compose v2+, cgroup v2 and seccomp.
- Internet access for initial Maven/npm dependencies and pinned sandbox images.
- Free configured ports: 5173, 8080, 8081, 5432, 6379 and 5672 by default.

The Maven 3.9.16 wrapper is committed with its distribution checksum. Spring Boot
and npm versions are pinned in the build files/lockfile; infrastructure images
are pinned in [compose.yaml](../compose.yaml). No global Maven install is needed.
The convenience launcher calls `mvnw.cmd` and `npm.cmd`, so it is a Windows
workflow. Linux CI uses the Bash Maven wrapper and PowerShell 7 helpers; a complete
Linux interactive development launcher is not supplied.

From the repository root, check tools before initialization:

```powershell
$PSVersionTable.PSVersion
java -version
node --version
npm.cmd --version
docker --version
docker compose version
docker info --format '{{.OSType}} {{.CgroupVersion}} {{json .SecurityOptions}}'
.\mvnw.cmd --version
```

The Docker server check must succeed and report Linux/cgroup 2/seccomp. The worker
also verifies effective sandbox controls at startup; unavailable controls fail
closed. Do not run submissions directly on the host to bypass that check.

## First start

Run from the repository root:

```powershell
.\scripts\dev.ps1 -Service infrastructure -Initialize
Push-Location frontend
npm.cmd ci
Pop-Location
```

Initialization generates local database/broker/worker credentials and the API
signing key in ignored `.env`. It refuses to overwrite an existing file. To choose
different ports before the first Compose startup, initialize from
`.env.example` with unique secrets and edit ports, or stop the local services
before changing ports in the generated file. Never commit `.env` or print rendered
Compose configuration containing credentials. `docker compose config --quiet`
validates configuration without printing expanded values.

Open three terminals at the repository root. Each launcher stays attached to its
application.

### Terminal 1: API and migrations

```powershell
.\scripts\dev.ps1 -Service backend
```

The API owns Flyway migrations and validates the resulting schema. Wait until
the readiness URL returns `UP` before provisioning the worker. PostgreSQL must
be available; migration/schema failures require fixing the cause and restarting
the API. Never use schema deletion or automatic Flyway repair as a workaround.

### Terminal 2: restricted worker and Docker execution

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
.\scripts\provision-worker.ps1
.\scripts\prepare-sandbox.ps1
$env:PAIRFORGE_SANDBOX_WORKSPACE_ROOT = Join-Path (Get-Location).Path '.tmp/execution-workspaces'
# First start, or after verifying the previous PairForge worker has stopped.
$env:PAIRFORGE_WORKER_PREVIOUS_WORKER_STOPPED = 'true'
.\scripts\dev.ps1 -Service worker -Sandbox
```

Adjust the readiness URL if `API_PORT` differs. Provisioning after migrations
creates/updates the worker role and grants execution reads plus limited lifecycle/
result-column updates. It cannot read account hashes, insert executions, change
source/identity, or modify the schema. If provisioned before migration, run the
provisioner again afterward. It is repeatable without recreating durable data.

Image preparation builds the trusted pinned Dockerfiles and sets immutable image
IDs **in that terminal's environment**. Keep preparation and worker startup in
the same terminal. Use the same Docker engine, absolute workspace path and sandbox
namespace across restarts, and run only one worker. `-Sandbox` is required:
without it, the worker is health-only and accepted jobs remain queued.

Local processes share the development account and Docker Desktop VM. Use controlled
demo programs; this does not provide the dedicated-host boundary of the deployed
worker. Docker daemon access belongs to the trusted worker orchestrator, never
to the API or submission containers.

### Terminal 3: frontend

```powershell
.\scripts\dev.ps1 -Service frontend
```

Open [http://127.0.0.1:5173](http://127.0.0.1:5173). Use two separately authenticated
browser sessions, create a room, and save its invitation with **Copy invitation**.
The guest joins through **Have an invitation?** using both room ID and token.
Invitations are retained only in the current browser session and cannot be
recovered after reload/logout; share them privately. Room membership has no
hard-coded two-person limit.

Edit in Monaco and choose Java or Python. Run captures the visible source/language,
including pending debounce edits. A valid Java submission defines `public class
Main` without a package declaration; Python uses `main.py`. The UI displays saved
stdout/stderr, exit status and timing. Fast executions need not visibly display
every intermediate state. Repeated Run clicks create distinct executions; inspect
history/Refresh Status after an uncertain request before submitting again.

## Configuration and observations

| Component | Default local address |
| --- | --- |
| Frontend | `http://127.0.0.1:5173` |
| API readiness | `http://127.0.0.1:8080/actuator/health/readiness` |
| Worker readiness | `http://127.0.0.1:8081/actuator/health/readiness` |
| PostgreSQL / Redis / RabbitMQ | `127.0.0.1:5432` / `:6379` / `:5672` |

All bindings are loopback. Redis persistence/authentication and RabbitMQ management
UI are not enabled by this local Compose configuration. It is not the production
configuration. The launcher reads the supported literal `KEY=VALUE` entries from
[.env.example](../.env.example); quotes, interpolation and inline comments are
unsupported. Existing process environment values take precedence. The launcher
passes configuration to JVMs and removes the API signing key from worker/frontend
children and the bootstrap database password from the worker child.

`VITE_API_BASE_URL` is public build-time configuration. The frontend launcher
derives it from `API_PORT` unless explicitly overridden. It must agree with API
origin rules. Never place secrets in `VITE_` variables. Frontend production hosting
must serve `index.html` for client routes; the deployed Caddy configuration does so.

For private metrics, append `-Observability` when starting the API and sandbox
worker above. Health moves to loopback management ports 8082/8083; both also serve
`/actuator/prometheus`. `API_MANAGEMENT_PORT` and `WORKER_MANAGEMENT_PORT` override
these ports. The profile does not itself enable execution or start a Prometheus
server. Do not expose management listeners publicly.

## Subsequent starts and shutdown

Reuse `.env` and persistent volumes; omit `-Initialize`:

```powershell
.\scripts\dev.ps1 -Service infrastructure
```

Then repeat API/readiness → worker provisioning/preparation → sandbox worker →
frontend. For shutdown, wait for accepted jobs to finish, stop all application
terminals with Ctrl+C, then stop dependencies:

```powershell
.\scripts\dev.ps1 -Service stop
```

This preserves PostgreSQL/RabbitMQ data volumes. Redis editor state is ephemeral.
Do not add a volume-deletion command unless you deliberately intend to erase data.

## Troubleshooting

- **Missing Docker or controls:** correct Docker Desktop/Linux-engine readiness.
  Required tests fail on missing prerequisites; they do not silently skip.
- **Port already used:** change the corresponding `.env` port, update readiness
  URLs and restart. Vite uses strict-port mode rather than selecting another port.
- **Existing credentials:** initialization refuses overwrites. The upgrade-only
  helpers `initialize-auth.ps1` and `provision-worker.ps1 -InitializeCredentials`
  fill missing keys in older environments; they are not normal startup steps.
- **Changed database/broker password:** environment edits do not update existing
  users in persisted volumes. Restore the correct credentials or deliberately
  update the user; do not delete volumes automatically.
- **Worker readiness DOWN:** restore dependencies, verify the old worker has
  stopped, then start one replacement with the same namespace/workspace. The
  failure latch intentionally requires operator recovery. Follow the
  [recovery checklist](ARCHITECTURE.md#milestone-12-recovery-checklist-and-retry-audit);
  never reset terminal jobs to queued or retry uncertain submissions automatically.
- **Lost connection:** use **Reconnect** to restore available Redis state. Copy
  the unsynchronized draft before navigation/reload. Expiration/loss causes an
  explicit reset; offline edits are not replayed automatically.

See [TESTING.md](TESTING.md) for regression commands and
[ARCHITECTURE.md](ARCHITECTURE.md) for API, execution and security contracts.
