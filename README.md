# PairForge

PairForge is a collaborative coding and asynchronous Java/Python execution
platform being built one reviewed milestone at a time. This repository currently
contains **Milestone 2 authentication** on the persistence foundation: registration,
login, JWT-protected identity, and Redis-backed authentication limits. The React
page and worker remain foundations; room APIs and code execution are not enabled.

## Structure

| Path | Purpose |
| --- | --- |
| `backend/` | Modular-monolith API; authentication, health, migrations, domain repositories |
| `execution-worker/` | Independent worker process; currently operational health only |
| `frontend/` | React + TypeScript + Vite foundation page |
| `compose.yaml` | PostgreSQL, Redis, and RabbitMQ for local development |
| `scripts/` | Windows development/environment and smoke-check commands |
| `.github/workflows/ci.yml` | Backend/worker integration tests and frontend checks |
| `docs/` | Product specification, architecture, and milestone acceptance criteria |

The runnable Java modules do not depend on each other. The API owns Flyway
migrations for users, rooms, membership, and execution records; Hibernate validates
the schema and never creates it. Repositories are grouped by domain and use UUID
references. Room APIs, WebSockets, queue producers/consumers, and execution
paths remain for later milestones. No Docker socket is mounted into an application.

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

Fresh initialization also creates `JWT_KEY_HEX` for the API. To upgrade an existing
Milestone 1 `.env`, run `.\scripts\initialize-auth.ps1` once. It preserves existing
credentials and refuses to overwrite a signing key. The API requires 64 hex
characters encoding 32 random bytes. Never put this key in a `VITE_` variable;
the local launcher removes it from worker/frontend child environments.

Provision the worker's separate database role after infrastructure is healthy:

```powershell
.\scripts\provision-worker.ps1
```

New initialization generates `WORKER_DB_PASSWORD` alongside the other secrets.
For an existing Milestone 0 `.env`, first run
`.\scripts\provision-worker.ps1 -InitializeCredentials`; this adds only missing
worker credentials and preserves the existing database/broker passwords. After
that, omit the flag. Provisioning is repeatable on fresh or existing data volumes;
it sets a dedicated `pairforge_worker` role's password and restricted permissions
without recreating the database. Use this role only for PairForge's worker.
Provisioning refuses an existing role that owns objects or belongs to other
roles. The local worker launcher also removes the API/bootstrap password from
the child process environment. Local processes still share your development
account; the deployed worker-host trust boundary is a later milestone.

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
the API also needs `REDIS_HOST` and `JWT_KEY_HEX`. Optional broker/Redis ports have defaults.
The default configuration still binds HTTP to loopback and exposes only health
through Actuator. Authentication endpoints are served on the API port.
For the worker, `DATABASE_USER`/`DATABASE_PASSWORD` must identify its restricted
role; the local profile uses `WORKER_DB_USER`/`WORKER_DB_PASSWORD`. The worker has
health connectivity only, no application-table reads/writes or schema ownership.
Execution-table grants will accompany its execution persistence implementation.
The local API uses the Compose bootstrap account to migrate; these local settings
are not a production deployment configuration.

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
- API-owned migrations run on a clean database and preserve data on repeat runs;
- failed migrations roll back partial DDL and prevent API startup; altered
  migration checksums are rejected;
- all four models round-trip through PostgreSQL, with normalized unique emails,
  foreign keys, membership uniqueness, deterministic history pagination, enum and
  timestamp constraints, and UTF-8 source/output limits;
- the worker creates no schema and its restricted role cannot read password
  hashes, modify memberships, or create tables, schemas, or temporary tables.

Unit tests cover email normalization, length boundaries, and locale independence.
Authentication tests exercise real HTTP registration/login, hashing, validation,
JWT and CORS failures, Redis limit concurrency/expiry, dependency failure/recovery,
and stateless access. Tests use BCrypt cost 4; runtime defaults to 12.
Room-owner membership and execution state transitions are
future service-layer transactions, not behavior supplied by repositories.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
.\scripts\test-provision-worker.ps1
.\scripts\test-auth-environment.ps1
Push-Location frontend
npm.cmd ci
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run build
Pop-Location
```

The credential-script tests use disposable files and a Docker stub. They verify
LF/CRLF initialization, preservation of existing secrets, and overwrite refusal;
they do not change your `.env` or contact your database. CI runs them with `pwsh`.

With all applications running, validate the local environment:

```powershell
.\scripts\smoke.ps1
.\scripts\smoke.ps1 -CheckOutages
.\scripts\smoke-auth.ps1
```

`-CheckOutages` deliberately stops each **PairForge Compose** dependency and
restores it in a `finally` block. Use it only when no other work depends on these
local services. It verifies both processes, including that a Redis outage does
not affect worker readiness. The smoke script checks frontend HTTP delivery;
also inspect the page in a browser for rendering and runtime errors.

`smoke-auth.ps1` verifies register → login → authenticated `/api/auth/me` and
unauthenticated denial without printing passwords or tokens. It leaves one
uniquely named smoke account in the local database and consumes a registration
attempt. Repeated runs are subject to the configured authentication limits.

## Authentication API

| Request | Success |
| --- | --- |
| `POST /api/auth/register` with JSON email/password | 201 `{id, email}` |
| `POST /api/auth/login` with JSON email/password | 200 `{accessToken, tokenType, expiresIn, expiresAt}` |
| `GET /api/auth/me` with `Authorization: Bearer <token>` | 200 `{id, email}` |

Passwords require 15 characters and no more than 72 UTF-8 bytes; they are not
trimmed. Emails use the existing normalized unique representation. Duplicate
registration returns 409. Invalid/missing credentials return 401, denied authenticated
routes or origins 403, invalid input 400, non-JSON auth bodies 415, oversized JSON
auth bodies 413, throttling 429 with
Retry-After, and unavailable authentication dependencies 503. Errors contain a
code, safe message, request ID, and field-error map, never rejected secret values.

Defaults: HS256, 15-minute tokens, BCrypt cost 12; login limits of 20 per IP/minute
and 10 per account/15 minutes, registration limit of 5 per IP/hour. The Redis
operation is atomic and expiry-bounded. Admission fails closed on Redis outage;
Redis loss resets counters. Forwarded IP headers are ignored. Configure these
defaults under `pairforge.auth` in Spring configuration; no proxy trust is assumed.

The frontend token contract is memory-only, requiring login after reload/expiry.
No refresh tokens or server-side logout revocation are implemented. Cookie/form
authentication is disabled. CORS permits explicit configured frontend origins;
wildcard/malformed origins fail startup. CSRF is disabled because this API accepts
only explicit bearer headers, never browser-automatic authentication credentials;
reassess it before adding cookies, sessions, or Basic authentication. JSON-only
credential endpoints reject form/multipart bodies before parsing. HTTPS and deliberately configured
origins/proxy trust are required for deployment. Frontend auth screens start in
Milestone 4. See Architecture for the full contract and known tradeoffs.

Readiness is `/actuator/health/readiness` (PostgreSQL/RabbitMQ, plus Redis for
the API). Liveness is `/actuator/health/liveness` and is independent of those
dependencies. A dependency outage returns HTTP 503 from readiness. Hikari,
Redis, and RabbitMQ operations have bounded connection/operation timeouts.
PostgreSQL driver connect/read timeouts are two seconds; Hikari pool acquisition
is two seconds. RabbitMQ TCP connection, AMQP handshake, and channel RPC timeouts
are two seconds, with a five-second heartbeat. The handshake requires a small
connection-factory customizer in each independent Java application.
Dependency recovery does not require restarting the application.
PostgreSQL must be available at API startup: migration or schema-validation errors
fail startup rather than launching against an unknown schema. Fix the underlying
problem and restart. Never edit an applied migration or use Flyway clean/repair
as an automatic workaround; introduce a new version for subsequent schema changes.

GitHub Actions runs the equivalent Maven and npm checks on Linux, using Docker
for Testcontainers. Reports appear under each Java module's
`target/failsafe-reports/` (integration) and `target/surefire-reports/` (unit) and
are uploaded when available. Linux users can run
the wrapper with `bash ./mvnw`; the PowerShell helpers are for Windows.
Remote CI can only be verified after the repository is connected and pushed to
GitHub. A locally passing build alone does not establish a passing remote run.

## Shutdown and troubleshooting

### Milestone 2 verification (2026-09-15)

Milestone 1 was committed as `a771844`. Authentication passes its complete
register/login/protected-user acceptance flow. The final review's root Maven
`clean verify` passes all 134 tests (35 unit, 99 integration), with zero failures,
errors, or skips. The 26 real HTTP authentication cases include dependency loss
and recovery. No test flakiness was observed.

The review fixed missing CORS headers on oversized-request errors, accidental
CSRF-created sessions on denied POSTs, and a multipart-request 500. Request IDs
now precede Security while JSON/body enforcement follows it. Bearer-only security
creates no sessions, and form/multipart bodies are rejected before parsing. Added
tests cover those regressions, explicit-origin configuration, exact JWT expiry,
and unchanged rate-limit windows on denial. An unused security-test dependency
was removed. The earlier transaction-start failure remains covered by outage tests.

Frontend clean install/lint/typecheck/build, both credential-script suites on
PowerShell 7/Windows PowerShell 5.1, script/workflow syntax, whitespace/secret
checks, and full infrastructure/authentication smoke tests pass. Local API smoke
used default BCrypt cost 12; this review's single register/login HTTP samples were
877/369 ms, not throughput benchmarks. This smoke run left one additional uniquely
named local account. Docker's recurring stale socket directories were preserved
and recreated before verification; its data volumes were not deleted.
Milestone 2 is committed locally following review; remote CI for it is unverified.
The unchanged frontend's visual browser check was not repeated; build and HTTP
delivery passed. No required Milestone 2 acceptance check is unverified, and no
Milestone 3 functionality was introduced.

### Milestone 1 review verification (2026-09-15)

Maven `clean verify` passes 76 tests (3 unit and 73 integration), with zero failures,
errors, or skips. Clean/repeat migrations, failure rollback, persistence
constraints, schema-drift startup rejection, and worker privilege checks pass.
The review fixed empty-password detection in CRLF `.env` files and removed
column-level grants during repeat worker provisioning; table-level revocation
alone does not remove them. Credential tests pass in PowerShell 7 and Windows
PowerShell 5.1 (with process-only execution-policy bypass on this machine).
Frontend clean install/lint/typecheck/build, PowerShell syntax, Actionlint, and full local smoke
checks pass, including real dependency stop/start recovery with the restricted
worker credentials. The first review build failed because Docker was unavailable;
its recurring stale sockets were backed up and recreated without altering its
data disks before rerunning all Java checks. These changes have not been pushed, so a new
remote CI run is unverified. See the roadmap for the acceptance evidence.

### Milestone 0 verification (2026-09-14)

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

[GitHub Actions run 34874301790](https://github.com/Ywrd10/PairForge/actions/runs/34874301790)
passes for foundation commit `9795240` in the private `Ywrd10/PairForge` repository:
both the Linux Java/container-test job and the frontend job succeed. All
Milestone 0 acceptance checks passed, and the milestone is DONE. This run verifies
the foundation commit only; it does not establish CI results for later changes.

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
[the roadmap](docs/ROADMAP.md) before extending the application. Complete each
milestone's acceptance checks before proceeding; do not automatically start
Milestone 3. The planned execution architecture retains the documented
dual-write limitations, no initial outbox, and constrained Docker execution.
