# PairForge

PairForge is a collaborative coding and asynchronous Java/Python execution
platform being built one reviewed milestone at a time. This repository currently
contains **Milestone 8 execution submission** on the authentication and room
foundation: register, log in, create rooms, share invitations, join, and open
authorized rooms with a shared Java/Python editor. Accepted documents live in
Redis with a 24-hour inactivity TTL; simultaneous edits use full-document
last-write-wins. Authorized execution submissions are persisted and queued with
confirmed RabbitMQ publication. The worker remains a foundation; code execution
and the browser Run action are not enabled.

## Structure

| Path | Purpose |
| --- | --- |
| `backend/` | Modular-monolith API; authentication, rooms, collaboration, execution submission/history, health, persistence |
| `execution-worker/` | Independent worker process; currently operational health only |
| `frontend/` | React + TypeScript + Vite authentication, room workflows, and shared Monaco editing |
| `compose.yaml` | PostgreSQL, Redis, and RabbitMQ for local development |
| `scripts/` | Windows development/environment and smoke-check commands |
| `.github/workflows/ci.yml` | Backend/worker integration tests and frontend checks |
| `docs/` | Product specification, architecture, and milestone acceptance criteria |

The runnable Java modules do not depend on each other. The API owns Flyway
migrations for users, rooms, membership, and execution records; Hibernate validates
the schema and never creates it. Repositories are grouped by domain and use UUID
references. The API produces execution jobs; consumers and sandbox execution
remain for later milestones. No Docker socket is mounted into an application.

The browser loads the React application, logs in through the API, and holds its
access token in memory. Protected requests send a bearer header; the API validates
the caller and PostgreSQL room membership before returning data. A health request to the API checks PostgreSQL,
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
[the frontend](http://127.0.0.1:5173). Create an account, log in, create a room,
save its invitation, and choose **Open room**. A second account can join from the
dashboard using the room ID and invitation token. The room displays metadata and
Monaco with a Java or Python starter. Switching the editor language preserves
source and undo history; it does not change the room's saved default. Refreshing
metadata preserves edits, but leaving the room, reloading, logout, and session
expiry discard the draft. No source is saved or shared. Run stays disabled, with
empty stdout/stderr panels and an explicit local-only connection status.

Monaco and its browser worker are bundled locally, with no CDN or language server.
Both syntax definitions load with the lazy editor module; language switching
does not depend on another script download.
The worker supports editor features and never executes submissions. If editor
loading fails, retry; a cached asset failure can require a page reload and a new
login. Monaco's lazy chunk currently exceeds Vite's 500 kB advisory threshold
(about 2.85 MB minified / 730 kB gzip); login/dashboard do not load that chunk.
Monaco 0.56.0 pins DOMPurify 3.4.8, so a scoped override pins patched 3.4.15.

The frontend launcher derives its public API URL from `API_PORT` (default 8080).
To override it, set `VITE_API_BASE_URL` in the process environment, or use a
frontend-only ignored `frontend/.env.local` when running npm directly. Production
builds also consume this value at build time. It is public configuration, never a
place for signing keys or other secrets. Keep the API's explicit allowed origins
aligned with the frontend URL. A future static host must serve `index.html` for
client routes such as `/rooms/<id>`; Vite handles this locally.

Tokens and room invitations are held only in memory. Reload/expiry requires login;
logout clears local state but does not revoke the issued JWT. Invitations are shown
only after creation: copy/save them securely before leaving the page. The app does
not put them in URLs or browser storage. If a create response is lost, check the
room list before retrying: another create can produce a duplicate, and the lost
invitation cannot be recovered. Failed writes are never retried automatically.

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
Room tests cover three-user admission, automatic owner membership, concurrent
joins, rollback, authorization, bounded input/pagination, secret disclosure, and
dependency failure/recovery. Execution tests cover confirmed persistent dispatch,
strict input/byte bounds, membership, history, admission concurrency, Redis loss,
real RabbitMQ nack/return/outage, PostgreSQL loss, conditional dispatch failures,
and the documented commit-to-publication gap. Worker transitions remain future work.

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
.\scripts\smoke-rooms.ps1
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

`smoke-rooms.ps1` registers/logs in three accounts, creates a room, checks denied
access, and verifies owner access, invitations, repeat joins, lists, and reads.
It leaves three uniquely named accounts and one room, consumes three registration
attempts, and prints no passwords, JWTs, or invitation tokens. Both smoke scripts
are subject to the default five-registrations-per-IP/hour limit; a 429 requires
waiting for the window, not bypassing the admission controls.

## Execution API (Milestone 8)

All endpoints require a bearer token and room membership:

| Method/path | Result |
| --- | --- |
| `POST /api/rooms/{roomId}/executions` | Persist source/language snapshot and confirm job publication; 202 receipt + Location |
| `GET /api/rooms/{roomId}/executions?page=0&size=20` | Bounded summaries, newest first; size 1–100 |
| `GET /api/executions/{executionId}` | Durable snapshot, status, revision, and result fields |

POST body: `{"source":"print('hello')","language":"PYTHON"}`; Java uses `JAVA`.
The contract is a single `main.py` or `Main.java`, standard libraries only,
closed stdin, no package installation/network. Maximum source is 65,536 UTF-8
bytes; maximum JSON body is 400,000 bytes. Syntax errors are left to the future
sandbox. This milestone never runs submitted code.

Defaults: 10 valid authorized attempts/user/60-second fixed window, 100 global
outstanding executions, two publication attempts with a two-second confirm wait
each. Attempts are charged before capacity admission and are not refunded.
429 supplies Retry-After; capacity exhaustion returns 503 with Retry-After.
Configuration is documented in [Architecture §9](docs/ARCHITECTURE.md#9-execution-request-flow).

The API commits PostgreSQL before publishing persistent ID-only messages to the
durable `execution.jobs` queue via `pairforge.execution`. Only internal publish
retries reuse an execution ID. Dispatch failure/uncertainty returns 503 with its
ID and either recorded FAILED status or `outcomeUnknown: true` if persistence
cannot be verified. Inspect Location or recent room history before any new POST.
A worker claim/completion wins over an attempted dispatch-failure update.

The approved dual-write crash window remains: committed QUEUED work can be
stranded before publication. See the [operator procedure](docs/ARCHITECTURE.md#operator-procedure-for-abandoned-queued-submissions).
There is no outbox or automatic redispatch. With no worker consumer in M8,
successful jobs normally remain QUEUED and consume capacity. Do not scale beyond
one API instance without replacing the documented in-process admission lock.

## Room API

All requests require `Authorization: Bearer <token>`.

| Request | Success |
| --- | --- |
| `POST /api/rooms` with `{name, language}` | 201 `{room, invitationToken}` and Location |
| `GET /api/rooms?page=0&size=20` | 200 `{items, page, size, hasNext}`, caller's rooms only |
| `GET /api/rooms/{roomId}` | 200 room metadata, membership required |
| `POST /api/rooms/{roomId}/join` with `{invitationToken}` | 200 room metadata, including repeated joins |

Names are nonblank and at most 120 Java UTF-16 code units; language is `JAVA` or
`PYTHON`. Room metadata includes id, ownerId, name, language, createdAt, updatedAt.
List sizes are 1–100, ordered newest first with ID as a tie-breaker. Create/join
require JSON bodies of at most 4096 bytes. Missing/inaccessible rooms or incorrect
invitations return concealed 404 errors; malformed input returns 400.

The owner is added in the creation transaction. Tokens are 256-bit random secrets,
hashed in PostgreSQL, returned only at creation, and required on every join. They
are reusable, with no expiry or rotation yet. Retain the token securely: a lost
creation response cannot recover it, and retrying create may create another room.
Concurrent repeated joins safely preserve one membership. Room requests use only
PostgreSQL after JWT validation; they create no Redis documents or queue messages.

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
origins/proxy trust are required for deployment. See Architecture for the full
contract and known tradeoffs.

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

## Collaboration (Milestones 6–7)

Open one room in two separately authenticated browser sessions after invitation
admission. Typing and Java/Python language changes synchronize after a 300 ms
debounce. Connection status distinguishes accepted state from pending edits,
document resets, and failures. Run remains disabled.

The browser connects to `/ws` on its configured API base URL (`ws` locally,
`wss` with HTTPS), authenticates its STOMP CONNECT header, subscribes to authorized
room events and private replies, and requests a snapshot. The API validates each
send against PostgreSQL membership, atomically commits document state to Redis,
then broadcasts it. JWT expiry closes active sockets. No token goes into the URL.
The existing frontend origin allowlist also applies to WebSocket handshakes.

Click **Reconnect** after connection loss to authenticate again and load the latest
Redis document. Editing pauses until the snapshot arrives; failed recovery keeps
the visible draft and allows another explicit attempt. Pending/uncertain edits
are saved as one memory-only backup with Copy draft and Discard backup actions.
A later recovery with pending edits replaces that backup. Copy before leaving,
reloading, logout, or session expiry; none of the draft is persisted in the browser.
Expiration/loss creates an explicit new document generation, including when
another participant initializes it before you reconnect. Heartbeats do not
refresh TTL. There is no automatic reconnect/retry or offline replay. Concurrent
full-document edits can overwrite each other, and a remote replacement clears
local undo history. Redis is ephemeral, not a durable source backup.

Defaults are 64 KiB UTF-8 source, 400,000-byte wire messages, 10 messages/second,
100 total connections, 10 per direct peer IP, and 5 per account. The five-second
authentication/send deadlines and 1 MiB outgoing buffer limit bound stalled
clients. These per-API-process limits are configurable under
`pairforge.collaboration.*`; see Architecture §7 for all properties and protocol
details. The bounded FIFO channels trade throughput for simple ordering: one
slow send may temporarily delay other rooms. Only one API instance is supported.

## Browser tests (Milestones 4–6)

Build the backend jar first with the root Maven verification command. Then:

```powershell
Push-Location frontend
npm.cmd ci
npm.cmd run lint
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
npx.cmd playwright install chromium
npm.cmd run test:e2e
Pop-Location
```

Browser tests require Docker and JDK 21; they never skip missing infrastructure.
They launch fresh PostgreSQL/Redis/RabbitMQ containers with generated credentials,
an API on port 18080, and a Vite production preview on 15173. The harness rebuilds
the frontend with that test API URL, verifying emitted Monaco worker assets as
well as UI behavior. Both application ports must be free. The
test API explicitly uses BCrypt cost 4 and higher IP admission budgets; normal
application defaults remain unchanged. Test users/rooms live only in the temporary
database. Normal teardown stops the API and removes these containers and their
volumes. Forced termination may require removing the specific
`pairforge-e2e-<run-id>-*` containers shown by `docker ps -a`.

Tests cover the real register/login/create/open and invitation admission flows;
browser-intercepted failures separately cover unavailable requests and expired
authentication. Editor checks cover real typing, undo/redo, language changes,
worker completion responses, refresh preservation, asset failure/recovery, and
mobile layout. Two accounts exchange source/language changes through real
WebSockets; reload restores accepted Redis state, and forced disconnection keeps
local text visibly unsynchronized. Editing creates no execution or metadata
writes. Vitest covers request handling, stale responses, forms, pagination,
editor lifecycle, debouncing, snapshot ordering, resets, and uncertain writes.
Java tests cover real Redis/PostgreSQL failures, membership/origin/JWT checks,
invalid/oversized frames, quotas, sequencing, atomic versions, TTL, and log
redaction; a deterministic transport test covers slow-client buffer overflow.
GitHub Actions runs the same checks. Browser traces/videos/screenshots
are disabled because they can contain credentials or invitations. Local Playwright
failure artifacts are ignored by Git and are not uploaded by CI.

## Shutdown and troubleshooting

### Milestone 8 review verification (2026-09-17)

Milestone 8 remains DONE. Review corrected the classification of Spring-generated
negative confirms after channel loss: uncertain delivery remains
DISPATCH_UNCONFIRMED. Tests also verify persistent jobs survive a RabbitMQ restart
and concurrent per-user admission cannot exceed its Redis quota.

Java 21 clean verification passes 230 tests with no failures or skips. Node 24
clean install, lint, typecheck, 54 frontend tests, build, nine browser tests,
credential-script tests, and whitespace checks pass; npm reports zero
vulnerabilities. The existing Monaco bundle-size advisory remains. Docker's
recurring stale runtime sockets were backed up/recreated for real-container tests.

At review time M8 changes were uncommitted/unpushed and remote CI was unverified. The documented
dual-write limitation and single-instance API admission remain; M9 has not begun.

### Milestone 7 review verification (2026-09-17)

Milestone 7 remains DONE after reviewing all acceptance criteria and the recovery,
authorization, Redis, and backup paths. Clean Maven verification passes 194 tests;
Node 24.19.0 checks pass 54 frontend tests, nine production-browser tests, and three
additional recovery-case runs with retries disabled. Lint, typecheck, build,
dependency audit (zero vulnerabilities), Windows/Linux credential checks,
Actionlint, and whitespace checks pass. No application-code correction was needed.
The existing Monaco bundle-size advisory remains.

The earlier [Milestone 6 CI run](https://github.com/Ywrd10/PairForge/actions/runs/35165402635)
failed during Linux credential-fixture cleanup. The hidden `.env` cleanup fixes
were subsequently pushed with Milestone 7 commit `6d963bd`, and both jobs passed
in [run 35224731699](https://github.com/Ywrd10/PairForge/actions/runs/35224731699).
That passing run is evidence for Milestone 7, not the uncommitted M8 changes.

### Milestone 6 review verification (2026-09-16)

Milestone 6 remains DONE after fixing actual message-channel executor wiring,
strict STOMP JSON types, queued-event JWT expiry, uncertain-write wording, and
initial subscription/send error handling. Regressions reproduced the executor,
payload-coercion, and client failures before the fixes. Final clean Maven
verification passes 189 tests; all 46 frontend tests and eight production-browser
tests pass. Clean install, lint, typecheck, build, dependency audit (zero known
vulnerabilities), credential checks, Actionlint, and whitespace checks pass.

Docker's recurring startup failure required backing up/recreating the inspected
runtime socket directories; images and volumes were preserved. All required
container tests subsequently ran, with no skips. No final failing check or
unresolved flakiness remains. The existing Monaco chunk-size advisory remains.
At that review, changes were uncommitted, remote CI was unverified, and Milestone 7
had not begun. See the roadmap for current acceptance evidence and the
architecture for runtime limits.

### Milestone 5 review verification (2026-09-16)

Milestone 5 remains DONE. The review fixed uncaught highlighting-download failures
by including both language definitions in the lazy editor module. A new browser
regression blocks later script downloads and verifies Python highlighting still
works. All 162 Java tests, 36 frontend tests, and six production-browser tests pass,
as do clean install, lint, typecheck, build, dependency audit (zero vulnerabilities),
credential checks, Actionlint, and whitespace checks. Docker required its known
socket-only recovery; images and volumes were preserved. The documented Monaco
bundle-size advisory remains. This historical review preceded Milestone 6;
Milestone 5 was subsequently committed and pushed as `8f79153`.
See the roadmap for current verification details.

### Milestone 4 review verification (2026-09-16)

Milestone 4 remains DONE after independent review. Fixed malformed successful API
responses reaching the UI unchecked, a misleading uncertain-write warning for
locally rejected room names, and mobile overflow for maximum-length room names.
The review adds regression coverage without changing the backend or API contract.

Final checks pass: 162 Java tests (38 unit, 124 integration), 29 frontend tests,
3 real-browser tests, clean install/lint/typecheck/build, dependency audit,
PowerShell 7/5.1 launcher/credential checks, Actionlint, whitespace and secret scans.
Initial regression failures were fixed; no final test is failing or skipped, and
no unresolved flakiness was observed. Temporary test resources were removed.

Docker needed the same socket-only startup recovery; images/volumes were preserved.
Its underlying recurring startup issue remains external to the project. Changes
are still uncommitted and remote CI is unverified. Milestone 5 remains TODO.

### Milestone 4 verification (2026-09-16)

Milestone 4 is DONE. Root Maven verification passes 162 tests (38 unit, 124
integration), with zero failures/errors/skips. Clean frontend install, lint,
typecheck, 24 unit/component tests, production build, and both real-browser tests
pass. Browser acceptance verifies registration through opening a room, invitation
admission/denial, logout/reload/expiry, and failure handling. Desktop/mobile visual
checks, PowerShell 7/5.1 credential/launcher tests, Actionlint, whitespace and
local-secret checks pass. Dependency audit reports no known vulnerabilities.

The browser harness uses temporary data and removes its API and containers.
Windows clipboard line-ending and Java launcher cleanup issues found during
verification were fixed; final checks pass. Changes remain local and uncommitted,
so remote CI for this milestone is unverified. Milestone 5 has not begun.

### Milestone 3 review verification (2026-09-16)

Final root Maven `clean verify` passes 162 tests (38 unit, 124 integration), with
zero failures/errors/skips and no observed flakiness. Review regressions found
blank room IDs returning 500 on detail/join; those now return safe 400 errors.
A new test verifies that a join body's forged userId cannot assign membership
to another account. All 25 room integration cases and prior suites pass.

Frontend build checks, credential scripts on PowerShell 7/5.1, script/workflow
syntax, whitespace/secret checks, live auth/three-user room smoke, and full
dependency outage/recovery smoke pass. Docker required socket-only recovery;
images and data volumes were preserved. Four additional smoke accounts and one
room remain locally. Remote CI is unverified for these uncommitted changes;
the unchanged frontend's visual check was not repeated. Milestone 3 stays DONE,
with no Milestone 4 work introduced.

### Milestone 3 verification (2026-09-15)

Root Maven `clean verify` passes 160 tests (38 unit, 122 integration), with zero
failures/errors/skips. The 23 real HTTP room cases verify three-user admission,
atomic owner membership/rollback, concurrent idempotent joins, membership-filtered
reads, invitation secrecy, validation/CORS, JWT denial, and dependency failures.
Room operations require PostgreSQL only after authentication; Redis/RabbitMQ
outages do not bypass membership checks or prevent these requests from completing.

Frontend install/lint/typecheck/build, credential scripts on PowerShell 7/5.1,
script/workflow syntax, whitespace/secret checks, live auth/room smoke, and full
infrastructure outage/recovery smoke pass. Room smoke ran on Windows PowerShell
5.1 and leaves three accounts/one room; auth smoke leaves one additional account.
No required test was skipped and no flakiness was observed. Remote CI for these
uncommitted changes is unverified; the unchanged frontend visual check was not
repeated. Milestone 3 is DONE; Milestone 4 has not begun.

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
Milestone 9. The planned execution architecture retains the documented
dual-write limitations, no initial outbox, and constrained Docker execution.
