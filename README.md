# PairForge

PairForge is a collaborative coding and asynchronous Java/Python execution
platform being built one reviewed milestone at a time. This repository currently
contains the **Milestone 0 foundation**: two Spring Boot applications, a minimal
React page, local infrastructure, health checks, and CI. Product features are
not implemented yet.

## Structure

| Path | Purpose |
| --- | --- |
| `backend/` | Modular-monolith API; currently operational health only |
| `execution-worker/` | Independent worker process; currently operational health only |
| `frontend/` | React + TypeScript + Vite foundation page |
| `compose.yaml` | PostgreSQL, Redis, and RabbitMQ for local development |
| `scripts/` | Windows development/environment and smoke-check commands |
| `.github/workflows/ci.yml` | Backend/worker integration tests and frontend checks |
| `docs/` | Product specification, architecture, and milestone acceptance criteria |

The runnable Java modules do not depend on each other. There are no domain
entities, migrations, authentication, WebSockets, queue producers/consumers, or
code-execution paths in this milestone. JDBC is present for real database health;
JPA/Flyway belong to Milestone 1. No Docker socket is mounted into an application.

The browser loads Vite's HTML and React assets and renders the static foundation
page; it does not call the API yet. A health request to the API checks PostgreSQL,
Redis, and RabbitMQ. Worker health checks PostgreSQL and RabbitMQ independently.
Readiness aggregates those checks into HTTP 200/UP or 503/DOWN, while liveness
reports process state. These probes create no product records or execution jobs.

## Prerequisites and pinned versions

- JDK 21; `java -version` must report 21.
- Node.js 24 and npm; verify with `node --version` and `npm --version`.
- Docker Desktop on Windows using Linux containers, or Docker Engine on Linux.
  `docker info` must succeed. Docker Compose v2 or newer is required.
- PowerShell 5.1 or newer for the local helper scripts.
- Internet access for the first dependency and container-image downloads.

Maven 3.9.16 is provided by the official wrapper; no global Maven installation is
required. Its distribution checksum is pinned. Spring Boot is 3.5.16, with JUnit 5
and Testcontainers versions managed by its dependency BOM. Frontend dependencies
are exact versions with `package-lock.json`; TypeScript 5.9.3 is compatible with
the selected ESLint tooling. Infrastructure images are PostgreSQL
`17.11-bookworm`, Redis `7.4.11-bookworm`, and RabbitMQ `4.1.8`.

## Local startup (PowerShell)

Run commands from the repository root. Initialize local secrets and start the
three infrastructure containers:

```powershell
.\scripts\dev.ps1 -Service infrastructure -Initialize
```

Initialization creates an ignored `.env` with cryptographically generated local
database and broker passwords. It refuses to overwrite an existing `.env`.
For subsequent starts, omit `-Initialize`. Alternatively copy `.env.example` to
`.env` and supply unique passwords yourself.

The environment parser accepts the keys documented in `.env.example`, using
literal `KEY=VALUE` entries (letters, digits, `_`, `.`, `/`, `:`, `-`), blank lines,
and full-line comments. Quotes, interpolation, and inline comments are not
supported. Nonempty process environment variables take precedence. Credentials
are never printed. The helper explicitly passes these values to locally launched
JVMs; Compose loading `.env` does not do that automatically.

Install frontend dependencies and build/test Java:

```powershell
Push-Location frontend
npm.cmd ci
Pop-Location
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Then use three separate terminals, each at the repository root:

```powershell
.\scripts\dev.ps1 -Service backend
.\scripts\dev.ps1 -Service worker
.\scripts\dev.ps1 -Service frontend
```

Each command stays attached to its application. Open
[the frontend](http://127.0.0.1:5173). Its page is a static foundation screen,
not a live infrastructure-health dashboard.

| Component | Default local address |
| --- | --- |
| Frontend | `http://127.0.0.1:5173` |
| API health | `http://127.0.0.1:8080/actuator/health` |
| Worker health | `http://127.0.0.1:8081/actuator/health` |
| PostgreSQL | `127.0.0.1:5432` |
| Redis | `127.0.0.1:6379` |
| RabbitMQ AMQP | `127.0.0.1:5672` |

All host bindings are loopback. Override ports in `.env` before starting the
services if necessary. RabbitMQ management UI is not enabled or published.
Redis has no persistence or authentication in this loopback-only development
configuration; it is not a production configuration.

For a non-local launch, supply `DATABASE_URL`, `DATABASE_USER`,
`DATABASE_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_USER`, and `RABBITMQ_PASSWORD`;
the API also needs `REDIS_HOST`. Optional broker/Redis ports have defaults.
The default configuration still binds HTTP to loopback and exposes health only.
Milestone 0 is not a production deployment configuration.

## Validation

Java builds run the `*IT` integration tests during Maven `verify`. Tests start
their own disposable PostgreSQL/RabbitMQ containers and, for the API, Redis;
they do not use your Compose data or fixed host ports. Docker absence is a test
failure, never a silent skip. Tests verify:

- real dependency readiness and recovery after each container pauses/resumes,
  retaining its randomly allocated port and simulating an unresponsive service;
- process liveness remains healthy during dependency outages;
- aggregate health does not disclose connection details;
- other Actuator endpoints remain unavailable;
- no product database schema is created.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
Push-Location frontend
npm.cmd ci
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run build
Pop-Location
```

With all applications running, validate the local environment:

```powershell
.\scripts\smoke.ps1
.\scripts\smoke.ps1 -CheckOutages
```

`-CheckOutages` deliberately stops each **PairForge Compose** dependency and
restores it in a `finally` block. Use it only when no other work depends on these
local services. It verifies both processes, including that a Redis outage does
not affect worker readiness. The smoke script checks frontend HTTP delivery;
also inspect the page in a browser for rendering and runtime errors.

Readiness is `/actuator/health/readiness` (PostgreSQL/RabbitMQ, plus Redis for
the API). Liveness is `/actuator/health/liveness` and is independent of those
dependencies. A dependency outage returns HTTP 503 from readiness. Hikari,
Redis, and RabbitMQ operations have bounded connection/operation timeouts.
PostgreSQL driver connect/read timeouts are two seconds; Hikari pool acquisition
is two seconds. RabbitMQ TCP connection, AMQP handshake, and channel RPC timeouts
are two seconds, with a five-second heartbeat. The handshake requires a small
connection-factory customizer in each independent Java application.
Dependency recovery does not require restarting the application.

GitHub Actions runs the equivalent Maven and npm checks on Linux, using Docker
for Testcontainers. Reports appear under each Java module's
`target/failsafe-reports/` and are uploaded when available. Linux users can run
the wrapper with `bash ./mvnw`; the PowerShell helpers are for Windows.
Remote CI can only be verified after the repository is connected and pushed to
GitHub. A locally passing build alone does not establish a passing remote run.

## Shutdown and troubleshooting

### Current verification status (2026-09-14)

Docker Desktop is running with Linux Engine 29.4.3. Maven `verify` passes all
23 integration tests (12 API, 11 worker), with no failures, errors, or skips.
Frontend clean install, lint, type checking, production build, and browser
rendering pass, with no captured browser warnings/errors and no npm audit
vulnerabilities. Compose configuration, all three dependency health checks,
PowerShell syntax, ignored-secret checks, and Actionlint workflow validation pass.
The full smoke check passes with the final application configuration: startup,
restricted endpoints, frontend delivery, and stop/start recovery of all three
dependencies, with liveness preserved and the worker unaffected by Redis loss.
The documented shutdown also passes: applications and Compose services stop,
PostgreSQL/RabbitMQ volumes remain, and Docker itself stays running.

The review corrected RabbitMQ test credentials (use the container's dedicated
credential setters), replaced unreliable stop/start of randomly published test
ports with pause/resume, and bounded PostgreSQL socket reads and AMQP handshakes.
Earlier runs failed or were interrupted while fixing those issues; they are not
counted as passes. Expected outage warnings appear in successful test logs.

Remote GitHub Actions remains unverified. The target is the private repository
`Ywrd10/PairForge`; an initial push and successful workflow run are still needed.
Milestone 0 remains IN PROGRESS until every acceptance check is verified.

### Docker recovery on the reviewed Windows machine

Two independent host issues blocked startup: inaccessible `dockerInference` and
`engine.sock` runtime sockets, and a missing `docker-desktop` WSL registration.
With Docker stopped, the socket-only parent directories were preserved under
`.pairforge-backup-20260914` and `.pairforge-backup-20260914-retry` names. The
existing `Docker/wsl/main/ext4.vhdx` was backed up, checksum-verified, and registered
using Microsoft's `wsl --import-in-place` command. Docker then started normally.
No factory reset, data-volume deletion, or replacement of the Docker data disk
was performed. These are machine-specific recovery notes, not routine setup.
The socket problem can recur after an unclean Docker exit.

References: [matching Docker socket report](https://github.com/docker/desktop-feedback/issues/536)
and [Microsoft's in-place WSL import](https://learn.microsoft.com/en-us/windows/wsl/basic-commands#import-a-distribution-in-place).

This machine already has a service on port 5432. Its ignored `.env` uses
`POSTGRES_PORT=15432`; the shared default remains 5432 and other local services
were left running. Use the documented port override when a default is occupied.

### Stopping local services

Stop each application with Ctrl+C, then:

```powershell
.\scripts\dev.ps1 -Service stop
```

This preserves PostgreSQL and RabbitMQ volumes. Do not add a volume-removal flag
unless you intentionally want to erase local data. Testcontainers removes its
own test resources separately.

- **Docker unavailable:** resolve `docker info` failures and confirm Linux
  containers before running tests. Do not disable the test gate as a workaround.
- **Port occupied:** change the corresponding `.env` port and restart that
  service. The Vite development server fails instead of silently switching ports.
- **Readiness DOWN:** check `docker compose ps`, dependency logs, ports, and
  credentials. Use `docker compose config --quiet` to validate without printing
  expanded secrets; avoid sharing unredacted logs or full rendered configuration.
- **Changed database/broker passwords:** initialization settings do not change
  existing users in persisted volumes. Keep the original local credentials or
  deliberately update the users; never delete volumes automatically to fix this.
- **Missing configuration:** the helper requires nonempty passwords. Direct JVM
  launches must supply the required environment or use the local profile with
  the same variables. No fallback credentials are committed.
- **Windows Maven arguments:** quote `-Dname=value` arguments containing dots
  when invoking Maven from PowerShell.

## Project guidance

Read [the specification](docs/PROJECT_SPEC.md),
[the architecture](docs/ARCHITECTURE.md), and
[the roadmap](docs/ROADMAP.md) before extending the foundation. Milestone 0 is
complete only when its acceptance checks pass; do not automatically start
Milestone 1. The planned execution architecture retains the documented
dual-write limitations, no initial outbox, and constrained Docker execution.
