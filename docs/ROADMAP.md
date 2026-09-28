# PairForge Roadmap

## Current Goal

Build PairForge into a deployed, tested, portfolio-quality collaborative coding
platform.

Work on one milestone at a time.

Do not begin the next major milestone automatically.

Milestone 16 is DONE. The bounded local baseline and separately approved AWS
benchmark are complete: 2,040/2,040 collaboration deliveries and nine reconnect
checks in each environment, plus all thirty executions in each environment.
Completed work was preserved without repetition. AWS queues and sandboxes are
clean, the final encrypted backup is verified, and both hosts are stopped.
Milestone 17 (Portfolio Polish) is DONE. The README, architecture visualization,
setup/testing guides, measured evidence and limitations are ready for review.
Milestone 18 remains TODO. Do not restart AWS, repeat Milestone 16 work, or begin
the next milestone automatically.
Keep the approximately three-week target focused on the core workflow and reserve
time for integration/deployment; beta recruitment and deferred technologies must
not expand the critical path.

Required failure handling belongs with the feature that introduces it. The
approved PostgreSQL-to-RabbitMQ dual-write crash windows remain documented MVP
limitations; a transactional outbox is future work, not an acceptance requirement.

---

## Definition of Milestone Completion

A milestone is complete when:

- required behavior works;
- appropriate tests exist;
- tests pass;
- failure cases are handled;
- documentation is updated if needed;
- no known blocker remains for the milestone.

Update this file when milestone status changes.

Status values:

- `TODO`
- `IN PROGRESS`
- `DONE`
- `BLOCKED`

---

## Milestone 0 — Project Foundation

**Status: DONE**

Deliverables:

- Java 21 / pinned Maven 3.9.x wrapper / pinned Spring Boot 3.5.x foundation
- root Maven aggregate with independent backend and execution-worker modules
- React + TypeScript + Vite frontend with Node 24, npm lockfile, and lint/type checks
- Docker Compose with version-pinned PostgreSQL, Redis, and RabbitMQ health checks
- environment/configuration structure, ignored secrets, loopback local ports
- backend health checks PostgreSQL, Redis, RabbitMQ; worker health checks
  PostgreSQL and RabbitMQ, with no worker product API
- Testcontainers foundation tests and repeatable startup/outage/recovery checks
- basic GitHub Actions build/test workflow on relevant pushes and pull requests
- README setup, shutdown, validation, and troubleshooting instructions

### Approved implementation sequence

1. Verify Java 21, Node 24, and a running Linux-container Docker engine. Record
   exact dependency/image versions; use the wrapper rather than require Maven
   installed globally. Resolve Docker access before acceptance checks.
2. Create the Maven aggregate and two independently runnable Spring Boot apps.
   Backend dependencies: Web, Actuator, JDBC/PostgreSQL, Redis, and AMQP. Worker
   dependencies: Web/Actuator for health, JDBC/PostgreSQL, and AMQP. Use Spring
   Boot dependency management; do not create domain classes or shared libraries.
3. Scaffold the minimal frontend foundation page and build/lint/typecheck scripts.
4. Configure Compose health checks, persistent PostgreSQL/RabbitMQ volumes,
   disposable Redis state, loopback bindings, and external credentials. Do not
   mount a Docker socket or create execution containers.
5. Add environment examples and a PowerShell launcher that passes documented
   local environment values to each child application. Compose env loading alone
   does not configure locally launched JVMs. Use application ports 8080/8081 and
   Vite 5173; allow explicit overrides. Expose health only, suppress sensitive
   details, and separate dependency readiness from process liveness.
6. Add backend infrastructure-health and worker-startup integration tests using
   real Testcontainers. Add smoke checks for dependency outage/recovery, health
   endpoint exposure, and frontend startup. No placeholder product tests.
7. Add basic Linux GitHub Actions jobs: Java 21 plus Maven verify with Docker,
   and Node 24 plus npm ci, lint, typecheck, and build. Use read-only repository
   permissions; do not publish or deploy artifacts in Milestone 0.
8. Run all checks, document results, and mark DONE only if every acceptance
   criterion passes. Report blocked/unrun checks explicitly and stop before
   Milestone 1.

### Foundation files

```text
pom.xml
mvnw
mvnw.cmd
.mvn/wrapper/maven-wrapper.properties
.gitignore
.editorconfig
.env.example
compose.yaml
README.md
.github/workflows/ci.yml
backend/pom.xml
backend/src/main/java/com/pairforge/api/PairForgeApiApplication.java
backend/src/main/java/com/pairforge/api/infrastructure/RabbitConnectionConfiguration.java
backend/src/main/resources/application.yml
backend/src/main/resources/application-local.yml
backend/src/test/java/com/pairforge/api/InfrastructureHealthIT.java
execution-worker/pom.xml
execution-worker/src/main/java/com/pairforge/worker/ExecutionWorkerApplication.java
execution-worker/src/main/java/com/pairforge/worker/infrastructure/RabbitConnectionConfiguration.java
execution-worker/src/main/resources/application.yml
execution-worker/src/main/resources/application-local.yml
execution-worker/src/test/java/com/pairforge/worker/WorkerStartupIT.java
frontend/package.json
frontend/package-lock.json
frontend/index.html
frontend/vite.config.ts
frontend/tsconfig*.json
frontend/eslint.config.js
frontend/src/main.tsx
frontend/src/App.tsx
frontend/src/index.css
scripts/dev.ps1
scripts/environment.ps1
scripts/smoke.ps1
```

The existing four guidance documents remain in place. Generated wrapper/Vite
support files may accompany this structure. No application feature files yet.

### Acceptance checks

- root Maven `verify` builds and tests both modules; integration tests are bound
  to verify and fail rather than silently skip when Docker is unavailable
- frontend `npm ci`, lint, typecheck, and production build pass; browser startup
  shows the foundation page without runtime errors
- Compose configuration validates without printing secrets; all three real
  dependencies become healthy
- backend and worker start independently and report their required dependencies
  healthy through `/actuator/health`
- stopping a required dependency makes readiness unhealthy within configured
  timeouts; restarting it restores readiness; dependency loss alone does not
  make liveness fail
- unsafe Actuator endpoints are unavailable; local ports bind to loopback
- GitHub Actions runs the required builds/checks and propagates failures; remote
  checks that cannot run are reported as unverified, not passing
- README commands reproduce setup, validation, and shutdown without deleting
  data volumes by default

Do not implement authentication, entities/repositories, Flyway migrations,
Monaco, WebSockets, queue producers/consumers, sandbox execution, outbox, or other
product features. JPA/Flyway begin in Milestone 1; Security in Milestone 2.

### Review verification (2026-09-14)

- Docker Desktop's stale runtime sockets and missing WSL registration were
  repaired while preserving backups and existing data. Linux Engine 29.4.3 runs.
- Maven `verify`: 23 integration tests pass (12 API, 11 worker), zero failures,
  errors, or skips. Tests now use correct RabbitMQ credential setters and
  pause/resume dependencies without reallocating their random host ports.
- PostgreSQL socket reads and RabbitMQ AMQP handshakes have explicit timeouts;
  the real-container outage tests verify unhealthy readiness and recovery.
- Frontend clean install, lint, typecheck, production build, and browser startup
  pass. Compose health/configuration, loopback bindings, ignored-secret checks,
  script syntax, and workflow syntax validation pass.
- The full smoke check passes against the final configuration: independent
  application startup, health/exposure, frontend delivery, dependency stop/start
  recovery, process liveness, and worker independence from Redis.
- Documented shutdown passes and retains the PostgreSQL/RabbitMQ data volumes.
- [GitHub Actions run 34874301790](https://github.com/Ywrd10/PairForge/actions/runs/34874301790)
  passes for foundation commit `9795240`: Java build and all 23 integration tests
  on Linux, frontend clean install, lint, typecheck, and production build.
- All Milestone 0 acceptance criteria are satisfied. Earlier Docker/fixture
  failures were resolved; no required check is skipped or left unverified.
- No future-milestone product functionality was introduced.

---

## Milestone 1 — Persistence

**Status: DONE**

Deliverables:

- User model
- Room model
- RoomMember model
- Execution model
- repositories
- Flyway migrations
- constraints
- normalized unique email, explicit foreign keys/nullability, UTC timestamps,
  supported status/language constraints, and membership/history indexes
- invitation hash and execution failure/revision/deadline fields per Architecture
- API-owned migrations; later feature milestones add migrations when needed
- PostgreSQL Testcontainers integration tests

Acceptance test:

Database migrations run against a clean PostgreSQL instance and persistence
tests pass.

### Verification (2026-09-14)

- API-owned `V1__initial_schema.sql` creates all four tables on clean PostgreSQL
  17.11. Repeat migration preserves records; checksum changes are rejected and a
  deliberately failing migration rolls back partial DDL and prevents API startup.
- Root Maven `verify` passes 74 tests: 3 email unit tests, 45 persistence integration
  tests, 3 migration integration tests, 12 API health tests, and 11 worker tests.
  Zero failures, errors, or skips. PostgreSQL, Redis, and RabbitMQ tests use real
  Docker containers. An initial microsecond-precision mismatch was fixed before
  the final successful run.
- Tests cover all models, normalized unique email, invalid references and values,
  duplicate membership, at least three members, restrictive deletion, UTF-8 byte
  budgets, nullable results, stable history pagination, and required indexes.
- Separate worker credentials are provisioned repeatedly on an existing local
  volume and in Testcontainers. Tests reject password-hash reads, membership
  writes, table/schema/temp-table creation, and provisioning a role with other
  role memberships. The worker launcher excludes the bootstrap database password.
- Both apps start with the migrated local database. Full smoke tests pass for
  health, restricted Actuator exposure, frontend HTTP delivery, dependency
  stop/start recovery, liveness, and worker independence from Redis.
- Frontend lint, typecheck, and build, PowerShell syntax, workflow Actionlint,
  Compose configuration, and whitespace checks pass. Secrets/logs remain ignored.
- CI includes the new tests through the existing Maven verify command and now
  uploads unit reports too. No new remote GitHub Actions run was triggered for
  these uncommitted changes; the Milestone 0 run is not evidence for Milestone 1.
- All Milestone 1 acceptance criteria pass locally. No authentication, room APIs,
  collaboration, queue processing, or execution behavior was introduced.

### Review verification (2026-09-15)

- Re-read the milestone requirements and reviewed all persistence models,
  repositories, migration/configuration, credential provisioning, and tests.
  All Milestone 1 deliverables and the clean-migration/persistence acceptance
  criteria remain satisfied; status stays DONE. Milestone 2 remains TODO.
- Fixed CRLF empty-worker-password detection and added disposable credential
  tests for LF/CRLF, existing-secret preservation, and overwrite refusal. They
  pass in PowerShell 7 and Windows PowerShell 5.1; CI now includes this check.
- Fixed repeat provisioning to revoke separately granted column privileges,
  with a regression test for password-hash access. Added a missing API startup
  test for schema drift and removed a test override that duplicated the
  production Hibernate validation setting.
- Root Maven `clean verify`: 76 tests pass (3 unit, 46 persistence, 4 migration,
  12 API health, 11 worker), zero failures/errors/skips. No test flakiness was
  observed. The initial Docker-unavailable run failed as required; socket-only
  recovery restored the engine before the final clean run.
- Frontend clean install, lint, typecheck, production build, script tests/syntax,
  Actionlint, whitespace and local-credential scans pass. Full smoke tests pass
  for startup, restricted endpoint exposure, frontend HTTP delivery, dependency
  stop/start recovery, liveness, and the worker's independence from Redis.
- No unnecessary abstraction, dead feature code, new service, product endpoint,
  authentication, collaboration, queue processing, or code execution was added.
  Repository constraints do not replace future service-layer authorization or
  conditional execution transitions; those remain in their assigned milestones.
- Remote CI for these uncommitted changes remains unverified. Frontend visual
  browser checks were not repeated because its implementation is unchanged;
  this review verified its build and HTTP delivery. No required Milestone 1
  acceptance check remains failing, skipped, or unverified.

---

## Milestone 2 — Authentication

**Status: DONE**

Deliverables:

- registration
- login
- BCrypt password hashing
- Spring Security
- JWT authentication
- protected endpoints
- validation/error handling
- authentication tests
- short-lived JWT validation, frontend token-lifetime contract, and login throttling

Acceptance test:

    register → login → receive token → access protected endpoint

### Implementation and verification (2026-09-15)

- Committed the reviewed Milestone 1 foundation as `a771844` before beginning
  authentication. No Milestone 1 schema changes were needed for this milestone.
- Added register/login and protected `/api/auth/me`, validated DTOs, BCrypt,
  Spring Security resource-server JWT support, consistent errors/request IDs,
  bounded JSON bodies, CORS, scoped CSRF exceptions, and stateless access.
- Defaults are HS256 with an API-only 256-bit signing key, 900-second tokens,
  BCrypt cost 12, minimum 15-character/maximum 72-byte UTF-8 passwords, and
  explicit issuer/audience/subject/time validation. Refresh/revocation and
  frontend authentication screens remain deferred as documented in Architecture.
- Redis atomically applies expiring IP/account login limits and IP registration
  limits before BCrypt. Invalid credentials are generic; concurrency, expiry,
  forwarded-header spoofing, and Redis/PostgreSQL outage/recovery are tested.
- Root Maven `clean verify` passed 113 tests. Four additional HTTP token cases
  were then added and the expanded AuthIT rerun passed all 24 cases. Final report
  set: 117 distinct passing tests (20 unit and 97 integration), with zero failures,
  errors, or skips. PostgreSQL, Redis, and RabbitMQ tests use real containers.
- Tests cover the complete auth flow, stored BCrypt hashes, duplicate/concurrent
  registration, malformed/oversized/chunked bodies, UTF-8 limits, invalid signatures,
  algorithms, issuers, audiences, subjects and timestamps, safe errors, CORS,
  session/cookie/Basic rejection, management exposure, and dependency failures.
  An initial import ambiguity and a transaction-start 500 response were fixed;
  transaction/access failures now return safe 503 responses.
- Credential initialization/isolation checks pass in PowerShell 7 and Windows
  PowerShell 5.1. Fresh setup generates a signing key; the upgrade script preserves
  other credentials and refuses overwrites. Worker/frontend launchers exclude it.
- Frontend clean install/lint/typecheck/build, PowerShell syntax, Actionlint,
  whitespace, and local secret scans pass. CI includes the new Java/script tests;
  no remote CI run was triggered or claimed for these local changes.
- The real local auth smoke flow passes with default BCrypt cost 12: registration,
  login, authenticated identity, and 401 without a bearer token. Single local HTTP
  samples were 983 ms register and 385 ms login; these are not throughput benchmarks.
  One uniquely named smoke account remains in the local database.
- Full infrastructure smoke tests pass after authentication is enabled: health,
  restricted management exposure, frontend HTTP delivery, dependency stop/start
  recovery, liveness, and worker independence from Redis.
- All Milestone 2 acceptance criteria pass. No room API, WebSocket collaboration,
  queue processing, sandbox execution, extra service, or Milestone 3 work was added.

### Review verification (2026-09-15)

- Re-read the project guidance and reviewed every authentication deliverable.
  The full register → login → receive token → protected persisted identity flow
  passes in both real HTTP integration tests and the local smoke script. Milestone
  2 remains DONE; Milestone 3 remains TODO.
- Regression tests exposed missing CORS headers on oversized-request errors and
  a session cookie created by Spring's default CSRF repository on denied POSTs.
  Request IDs now precede Security; JSON/body limits follow CORS/Security and
  precede MVC. CSRF is disabled under the explicit bearer-only contract (no
  browser-automatic credentials), with form/multipart inputs rejected before
  parsing. Both anonymous and authenticated denied POSTs remain blocked without
  creating sessions. The new form test also exposed and fixed a multipart 500.
- Configuration rejects wildcard/malformed origins, JWTs are invalid at their
  exact expiry, and exhausted counters remain blocked through their final
  millisecond. Tests also verify denied attempts do not increment counters or
  extend their window. Removed an unused security-test dependency.
- Root Maven `clean verify`: all 134 tests pass (35 unit, 99 integration), with
  zero failures, errors, or skips. Authentication contributes 26 real HTTP cases;
  PostgreSQL, Redis, and RabbitMQ behavior uses real Testcontainers. Initial
  regression failures were fixed and included in the final clean run. No test
  flakiness was observed; expected outage warnings do not indicate test failures.
- Frontend clean install, lint, typecheck and production build; both credential
  script suites on PowerShell 7 and Windows PowerShell 5.1; script syntax;
  Actionlint; whitespace; and local-secret exclusion checks pass.
- Docker initially failed to start. With no Docker processes running, only the
  verified zero-byte socket directories were backed up and recreated; Engine
  29.4.3 then ran all required checks. Existing images/data volumes were preserved.
  This recovers the local runtime; it does not claim to fix Docker Desktop's
  underlying recurring socket problem.
- Documented independent startup, repeat worker provisioning, loopback binding,
  health/management exposure, frontend HTTP delivery, and full dependency
  stop/start recovery pass. Auth smoke used default BCrypt cost 12 (877 ms
  registration, 369 ms login, single local samples). One additional uniquely
  named smoke account remains in the local database.
- Review completed before the subsequent local Milestone 2 commit; remote CI
  for these changes is unverified. The unchanged
  frontend's visual browser check was not repeated. Neither is an outstanding
  Milestone 2 acceptance criterion. No required check remains failing or skipped.
- The API remains a modular monolith plus the independent foundation worker.
  No migrations, room API, frontend authentication screens, WebSockets, queue
  processing, execution, extra services, or other future-milestone features were
  added. No remaining unnecessary abstraction or dead feature code was identified.

---

## Milestone 3 — Rooms

**Status: DONE**

Deliverables:

- create room
- retrieve rooms
- retrieve room
- join room
- high-entropy invitation issuance and hashed-token admission; room ID alone
  cannot authorize joining; tokens are not logged

- automatic owner membership
- authorization
- tests

Acceptance test:

Independent accounts can join through a valid invitation; invalid tokens and
unauthorized room reads fail. Owner membership is automatic and duplicate or
concurrent joins do not duplicate membership. Test at least three members to
guard against an accidental two-user cap; do not build capacity-management UI.

### Implementation and verification (2026-09-15)

- Added create/list/detail/join REST endpoints with validated DTOs and persisted
  bearer identity. Room reads/lists enforce membership; missing/inaccessible rooms
  and incorrect invitations use concealed 404 errors. Client identity fields do
  not control ownership or membership. Existing JWT/CORS/error conventions remain.
- Room creation and owner membership commit together. An injected membership
  constraint failure verifies rollback of the room. Join uses PostgreSQL
  `ON CONFLICT DO NOTHING`, requires a valid invitation on every attempt, and
  preserves membership timestamps across repeated/concurrent joins.
- Invitations contain 256 random bits, use Base64URL, and are stored only as
  SHA-256 hashes. Creation alone returns the token; normal DTOs and logs disclose
  neither token nor hash. Unit and captured-output HTTP tests verify the contract.
  Reusable tokens, no expiry/rotation, lost-create-response recovery limits, and
  offset-pagination behavior are documented in Architecture and README.
- List defaults to 20 rooms, caps size at 100, and retains deterministic
  `(created_at DESC, id DESC)` ordering. Invalid pagination/UUIDs return safe 400s;
  create/join share the bounded JSON, chunked-body, 413/415, and CORS protections.
  Language accepts JAVA/PYTHON strings and rejects numeric enum ordinals.
- Root Maven `clean verify`: 160 tests pass (38 unit, 122 integration), zero
  failures/errors/skips. New coverage includes 3 invitation unit tests and 23
  RoomIT cases using real PostgreSQL/Redis/RabbitMQ Testcontainers. The focused
  room/auth run also passed before the final clean build. No flakiness observed.
- Room acceptance passes with three separately registered/logged-in accounts:
  create, automatic owner membership, join, list/read, wrong invitation and
  unauthorized-read rejection, repeated joins, and concurrent joins. Expired,
  invalid, and nonexistent-user JWTs cannot access room operations. PostgreSQL
  outage/recovery returns safe errors; existing-token room requests work while
  Redis/RabbitMQ are paused. No automatic database write retries were introduced.
- Frontend clean install/lint/typecheck/build pass. Credential script suites pass
  on PowerShell 7 and Windows PowerShell 5.1. Script syntax, Actionlint, whitespace
  checks (including new files), and local-secret scans pass.
- Documented application startup and repeat worker provisioning pass. Live auth
  smoke passes with BCrypt cost 12; the new room smoke passes on Windows
  PowerShell 5.1 with three users. Full health/exposure/frontend HTTP and
  PostgreSQL/Redis/RabbitMQ stop/start recovery smoke checks pass. These runs leave
  four additional uniquely named smoke accounts and one room in the local database.
- No new dependency, schema migration, worker behavior, frontend UI, Redis editor
  document, queue processing, execution feature, or Milestone 4 work was added.
  Existing auth tests now use a still-unimplemented route for deny-by-default
  assertions rather than treating the new room routes as unavailable.
- All Milestone 3 acceptance criteria pass. Changes remain local and uncommitted;
  remote CI for them is unverified. The unchanged frontend's visual browser check
  was not repeated; its build and HTTP delivery passed. No required check is
  failing, skipped, or unverified. Stop before Milestone 4.

### Review verification (2026-09-16)

- Re-read the project guidance and reviewed all Milestone 3 acceptance criteria.
  Three independent users can create/join/list/read a room; automatic owner
  membership, invalid-invitation denial, unauthorized reads, and concurrent
  duplicate joins pass. Creation rollback and membership timestamp preservation
  remain covered. Milestone 3 stays DONE; Milestone 4 remains TODO.
- Added blank-ID regressions for detail/join and reproduced two 500 responses.
  Spring converts whitespace UUIDs to null and raises MissingPathVariableException;
  the error handler now maps this client-input case to safe 400. Actual server
  mapping failures retain 500 handling. Added a regression proving a forged
  userId in a join body cannot grant membership to another account.
- Final root Maven `clean verify`: 162 tests pass (38 unit, 124 integration),
  zero failures/errors/skips, including all 25 RoomIT cases. The two initial
  regression failures were fixed and passed in the final clean run. No flakiness
  was observed. Required container tests were not bypassed.
- Docker initially failed on its recurring inaccessible runtime sockets. Verified
  Docker processes were stopped, and only the verified socket directories were
  backed up/recreated; Engine 29.4.3 then ran the tests. Existing images and data
  volumes were preserved. This is recovery, not a fix for Docker Desktop's
  underlying recurring startup issue.
- Frontend clean install/lint/typecheck/build; credential-script tests on
  PowerShell 7 and Windows PowerShell 5.1; script syntax; Actionlint; tracked and
  new-file whitespace checks; and local-secret scans pass.
- Independent application startup, repeat worker provisioning, auth smoke,
  three-user room smoke on Windows PowerShell 5.1, and full health/exposure,
  frontend HTTP, and dependency stop/start recovery smoke checks pass. The auth
  and room smoke runs leave four additional unique accounts and one room locally.
- Reviewed authorization, token hashing/disclosure, database transactions,
  concurrency, failure responses, pagination, and scope. No remaining Milestone 3
  blocker, unnecessary abstraction, dead feature code, or architectural drift
  was identified. No frontend feature, dependency, schema migration, worker
  behavior, collaboration, or execution functionality was introduced.
- Changes remain local and uncommitted; remote CI for them is unverified. The
  unchanged frontend's visual browser check was not repeated. All required
  Milestone 3 acceptance checks pass; no required check remains failing or skipped.

---

## Milestone 4 — Frontend Application

**Status: DONE**

Deliverables:

- login page
- registration page
- dashboard
- authenticated routing
- API client
- room creation
- room navigation
- invitation sharing/join flow and login-expiry handling

Acceptance test:

A user can register, log in, create a room, and open it entirely through the
browser.

### Implementation and verification (2026-09-16)

- Added login/registration, protected dashboard and room-overview routes, typed
  API modules, and a centralized memory-only session. Registration leads to login;
  login confirms `/api/auth/me`. Expiry timers, focus/visibility checks, request
  guards, and authenticated 401s require a new login. Logout cancels requests and
  clears protected content; stale responses cannot restore an earlier session.
- Dashboard lists authorized rooms with pagination, creates rooms with a default
  Java/Python language, displays the one-time invitation with copy/manual fallback,
  and joins using room ID/token. Opening a room fetches authorized metadata.
  Invitations never enter URLs or persistent browser storage. Uncertain creation
  warns about duplicate rooms and unrecoverable invitations; writes are not retried
  automatically and in-flight form submissions cannot be duplicated.
- The public API URL follows the configured API port in the Windows launcher and
  supports an explicit frontend override. Signing keys remain isolated from the
  frontend. Added React Router, Vitest/Testing Library, and Playwright with pinned
  dependencies; no new backend dependency, endpoint, schema, or worker behavior.
- Root Maven `clean verify`: 162 tests pass (38 unit, 124 integration), zero
  failures/errors/skips. Existing PostgreSQL/Redis/RabbitMQ container tests ran.
- Clean `npm ci`, lint, typecheck, all 24 Vitest tests, and production build pass.
  Dependency install/audit reports zero known vulnerabilities. Tests cover API
  errors, timeout/cancellation, no write retries, expired/invalid login, 401/503,
  stale response/login races, registration, protected routing, pagination,
  duplicate creation, invitation copy fallback, logout, and focus-time expiry.
- Both Playwright tests pass against a fresh API and isolated real PostgreSQL,
  Redis, and RabbitMQ. Browser acceptance covers registration, login, creation,
  invitation copying, opening, second-user invalid invitation/unauthorized-read
  denial, valid admission, room listing, logout, reload, and timed expiry. A
  separate browser fault test covers network loss/uncertain creation and 401.
- Initial browser checks exposed Windows clipboard newline differences and a
  Java PATH shim leaving a child process after teardown. The assertion now compares
  normalized newlines; teardown stops its owned process tree. Final reruns pass,
  and test API/containers are removed. No required check is skipped or failing;
  no unresolved flakiness was observed. The normal local database is untouched.
- Desktop/mobile visual checks passed using non-sensitive fixture data, including
  room navigation and no horizontal overflow. Launcher/credential tests pass on
  PowerShell 7 and 5.1; Actionlint, whitespace and local-secret checks pass. CI now
  includes frontend unit and real-browser acceptance alongside the Java suite.
  Remote CI for these local, uncommitted changes remains unverified.
- README and Architecture document the browser flow, configuration, tests, and
  memory/invitation limitations. Milestone 5 remains TODO: no Monaco, WebSockets,
  editor controls, collaboration, or execution functionality was introduced.

### Review verification (2026-09-16)

- Re-read the project guidance and checked every Milestone 4 deliverable. Browser
  registration, login, dashboard, authenticated routing, API integration, room
  creation/opening, invitation sharing/admission, and login-expiry handling pass.
  The register → login → create → open acceptance flow passes against the real API.
- Fixed three review findings: successful JSON responses were trusted without
  checking their shape, local blank-name validation incorrectly suggested an
  uncertain write, and a valid 120-character unbroken room name overflowed mobile
  headings. Auth/room API boundaries now reject malformed payloads with safe errors;
  local validation uses 400 semantics; headings wrap. No additional dependency or
  backend/API/schema change was needed.
- Four new regressions initially failed on malformed identity/list/create payloads
  and the misleading local-validation warning. A browser regression reproduced
  mobile overflow. All now pass, along with a dashboard recovery test proving
  malformed responses leave navigation and refresh usable.
- Final Maven `clean verify`: 162 tests pass (38 unit, 124 integration), zero
  failures/errors/skips. Clean frontend install, lint, typecheck, all 29 Vitest
  tests, production build, and all 3 Playwright tests pass. Dependency audit reports
  no known vulnerabilities. Browser tests cover real admission/authorization,
  reload/logout/expiry, network/401 handling, and maximum-length mobile room names.
- Credential/launcher checks pass on PowerShell 7 and 5.1; Actionlint, changed/new
  file whitespace checks, and local-secret scans pass. The browser-test API and
  containers were removed; tests did not change the normal development database.
- Docker was initially stopped, then hit its recurring inaccessible socket error.
  After verifying Docker had exited, only the known zero-byte runtime socket
  directories were backed up/recreated. Engine 29.4.3 then ran all required tests;
  images and data volumes were preserved. This restores operation but does not
  resolve Docker Desktop's underlying recurring startup problem.
- Reviewed session ownership, secret handling, authorization boundaries, failure
  messages, cancellation, dependencies, cleanup, and scope. No remaining blocker,
  obvious dead feature code, unnecessary architectural layer, or future-milestone
  feature was identified. Milestone 4 remains DONE; Milestone 5 remains TODO.
- No final local test is failing or skipped; no unresolved flakiness was observed.
  Remote GitHub Actions for these uncommitted changes remains unverified. Desktop
  visual screenshots from implementation were not repeated; current browser
  acceptance and mobile layout regressions passed.

---

## Milestone 5 — Monaco Room

**Status: DONE**

Deliverables:

- room page
- Monaco Editor
- Java/Python language selector
- Run button
- output panel
- connection state UI

Execution may still be disabled at this point.

Acceptance test: an authorized user opens Monaco, switches Java/Python, and sees
the output and connection-state panels. Run remains disabled until the actual
execution path is available; no fake success or host-process runner is used.

### Implementation and verification (2026-09-16)

- Authorized room metadata now mounts a lazy-loaded Monaco editor with Java/Python
  highlighting, locally bundled worker assets, and `Main.java`/`main.py` labels.
  Room defaults select the initial starter; switching language preserves the one
  draft and undo history. It does not persist a new room default.
- Drafts exist only in memory. Refreshing metadata preserves them, including on
  transient API errors; leaving/reloading/logout/expiry discards them. Initial
  access denial never mounts an editor; a later authorization/not-found response
  removes it. Loading and initialization errors show retry/reload guidance, and
  cleanup disposes the editor/model/listener without mounting late loads.
- Run is disabled, stdout/stderr show empty states, and the connection panel
  reports local editing only. No backend, schema, worker-process, execution,
  WebSocket, or Redis document behavior was added. Milestone 6 remains untouched.
- Root Maven `clean verify`: 162 tests pass (38 unit, 124 integration), zero
  failures/errors/skips, using real PostgreSQL/Redis/RabbitMQ Testcontainers.
- Frontend clean install, lint, typecheck, all 36 unit/component tests, and
  production build pass. Five real-browser tests pass against the production
  preview and isolated API/dependencies, with retries disabled and no skips.
  They verify typing, undo/redo across language switching, Python highlighting,
  same-origin worker completion responses, metadata refresh preservation,
  authorization, expiry/logout/reload/navigation, mobile resizing, and asset
  failure/recovery. Editing creates no API writes or WebSocket connections.
- Desktop/mobile visual inspection passes. Workflow Actionlint and whitespace
  checks pass; generated files and secrets remain ignored. Browser fixture API,
  containers, and temporary volumes are cleaned up after tests.
- Pinned Monaco 0.56.0 with a scoped DOMPurify 3.4.15 override to replace its
  vulnerable 3.4.8 pin. Full `npm audit` reports zero vulnerabilities. Vite retains
  its advisory for the approximately 2.84 MB minified / 728 kB gzip lazy Monaco
  chunk; it is not loaded on login/dashboard. This warning is documented.
- Initial development checks found overlapping StrictMode lazy imports, test
  typing issues, and browser assertions that assumed a single undo group and
  platform-independent completion roles. These were corrected; the final suites
  pass without observed flakiness. No required local check remains failing,
  skipped, or unverified. Remote GitHub Actions has not run for these uncommitted
  changes; prior milestone CI is not evidence for this milestone.
- README, Project Spec, and Architecture document the temporary-draft contract,
  worker loading, build warning, test workflow, and explicit feature boundaries.
  All Milestone 5 acceptance criteria pass. Stop before Milestone 6.

### Review verification (2026-09-16)

- Re-read the four project guidance documents and reviewed every Milestone 5
  deliverable. Authorized room access, Monaco editing, Java/Python switching,
  disabled Run, empty stdout/stderr, and explicit local connection status all pass.
  Milestone 5 remains DONE; Milestone 6 remains TODO.
- Reproduced one error-handling gap: Monaco's separate Python definition download
  could fail after the editor opened, leaving highlighting unavailable and raising
  uncaught browser errors. Both small definitions now load in the lazy editor
  module, under its existing load/error boundary. Added narrow declarations using
  Monaco's public types and a real-browser regression that switches/highlights
  after later script downloads are blocked. No new dependency or service was added.
- Final Maven `clean verify` passes all 162 tests (38 unit, 124 integration), with
  zero failures/errors/skips. The initial attempt failed because Docker was down;
  startup then reproduced both known stale runtime sockets. With Docker stopped,
  only verified socket directories were backed up/recreated. Engine 29.4.3 now
  runs, and images/volumes/settings were preserved. This recovers the environment;
  it does not fix Docker Desktop's underlying recurring socket problem.
- Clean frontend installation, lint, typecheck, all 36 unit/component tests,
  production build, and all six production-browser tests pass. Browser retries
  remain disabled; no flakiness was observed. The full dependency audit reports
  zero vulnerabilities. Credential-isolation script checks, Actionlint, and
  tracked/new-file whitespace checks pass. Temporary browser services are removed.
- Reviewed source handling, authorization, session expiry, editor/model/listener
  disposal, cancelled loads, draft lifecycle, and dependency pinning. No remaining
  milestone blocker, unnecessary abstraction, dead feature code, or architectural
  drift was identified. Backend/schema/worker code is unchanged; no execution,
  WebSocket, Redis document, or future-milestone feature was introduced.
- The approximately 2.85 MB minified / 730 kB gzip lazy Monaco chunk still raises
  Vite's advisory size warning. Required local acceptance checks are all verified;
  remote GitHub Actions remains unverified because changes are uncommitted and
  unpushed. The unchanged full development-stack smoke scripts were not repeated;
  real dependency failure tests and the isolated browser workflows ran instead.

---

## Milestone 6 — WebSocket Collaboration

**Status: DONE**

Deliverables:

- authenticated WebSocket connection
- STOMP CONNECT authentication, allowed origins, established-session JWT expiry
- room subscriptions
- document-update messages
- server-side membership validation
- authorization for both subscriptions and sends, including denied destinations
- debounced updates
- server-authoritative document versions
- Redis-backed atomic content/language/version/generation updates and 24-hour
  inactivity TTL from the first collaboration implementation
- full-document last-write-wins in server acceptance order; no implicit merging
- bounded message sizes/rates, client update sequencing, and slow-client handling
- update broadcasting
- tests

Acceptance test:

Two browsers logged into different accounts can join one room and see each
other's editor changes. Nonmembers cannot subscribe or send updates; expired
tokens and oversized/invalid messages are rejected. Concurrent updates preserve
content/version consistency. Include Redis Testcontainers and authorization tests.

### Implementation and verification (2026-09-16)

- Added native STOMP `/ws` with CONNECT bearer authentication, explicit origin
  checks, query-token rejection, PostgreSQL user/membership authorization on
  subscriptions and sends, denied unmatched destinations, and established-session
  JWT expiry. No SockJS, RabbitMQ relay, or new application service was introduced.
- The collaboration domain separates thin message controllers, services, Redis
  persistence, security, session limits, and transport configuration. Redis Lua
  atomically maintains content/language/version/generation and the 24-hour
  inactivity TTL. Initial snapshots, explicit resets, and obsolete-generation
  rejection make the first collaboration implementation safe; no durable editor
  schema or execution-worker change was required.
- The browser centralizes its connection/state handling, debounces full-document
  replacements for 300 ms, keeps one update in flight, and orders snapshots/events.
  Monaco suppresses remote-change echoes. Pending, overwritten, reset, and
  disconnected states are visible; unsynchronized text stays local. Reopening
  loads Redis state. Automatic reconnect/offline replay remains unimplemented.
- Configured source/frame/rate/connection limits, authentication and acknowledgement
  deadlines, bounded channel queues and outgoing buffers, and transport send
  timeouts. Protocol errors omit payloads/tokens from responses and logs. The
  single FIFO channel tradeoff and Redis-commit-to-notification gap are documented
  in Architecture §7; neither is represented as guaranteed durable delivery.
- Root Maven `verify` passes **182 tests: 41 unit and 141 integration**, with zero
  failures, errors, or skips. The 17 new real-container collaboration tests cover
  three members, two-way authorization, origins/tokens/expiry, denied destinations,
  duplicate/reordered updates, malformed/oversized frames, the valid 64 KiB UTF-8
  boundary, forged identity, connection/message limits, concurrent writes and
  snapshot ordering, TTL refresh/expiry/heartbeat behavior, Redis reset/outage,
  PostgreSQL outage, and log redaction. Three unit tests cover connection admission,
  idle expiry, and deterministic slow-client send-buffer overflow.
- Clean frontend install, lint, typecheck, all **44 Vitest tests**, production
  build, and all **8 Playwright tests** pass. Browser acceptance uses the production
  frontend and a fresh API with real PostgreSQL/Redis/RabbitMQ: different accounts
  exchange edits and language changes, accepted state survives reopening/reload,
  and forced disconnection retains a visibly unsynchronized draft. Browser retries
  are disabled. Frontend tests cover debouncing, one in-flight write, snapshot
  ordering, reset without replay, malformed data, and uncertain acknowledgement.
- Fixed issues exposed during verification: the native socket's default frame
  size rejected valid large documents, a stale test expected local-only drafts,
  and a reset acknowledgement could schedule another update. Regression checks
  pass after configuring native frame limits, aligning lifecycle assertions with
  Redis state, and preventing post-reset replay.
- Dependency audit reports zero known vulnerabilities. Credential provisioning/
  environment checks, Actionlint, and tracked/new-file whitespace checks pass.
  Secrets, test logs, dependencies, and build output remain ignored. Test-owned
  browser/API/container resources are cleaned up without changing development data.
- Every Milestone 6 acceptance criterion passes locally; no required check is
  failing or skipped and no unresolved flakiness was observed. Remote GitHub
  Actions for these uncommitted changes is unverified. The unchanged development
  smoke script was not repeated; real dependency failure and browser checks ran.
  Slow-client buffer overflow is tested deterministically, not as a network load
  benchmark. The existing large Monaco chunk advisory remains non-blocking.
- README, Specification, and Architecture now describe the implemented protocol,
  configuration, flow, tests, and limitations. Run remains disabled. Milestone 7
  and all later milestones remain TODO; do not begin them automatically.

### Review verification (2026-09-16)

- Re-read AGENTS, Specification, Architecture, and this roadmap; reviewed the
  complete collaboration domain, frontend connection/editor lifecycle, dependency
  changes, and tests. Every Milestone 6 acceptance criterion passes after the fixes
  below. Status remains DONE; Milestone 7 remains TODO.
- Fixed actual channel wiring: Boot selected the same unbounded scheduler for
  both channels despite the intended executor limits. Separate explicitly bound
  FIFO executor beans now enforce one thread and a 64-task queue each. A new
  integration test inspects the running channels and reproduced the old failure.
  The expiry task now explicitly selects its scheduler. Outbound authorization
  also rechecks JWT expiry immediately before delivery, covering time in the queue.
- Fixed JSON coercion: numeric source and fractional/string sequences were
  accepted and mutated Redis. A strict STOMP-only converter now precedes Boot's
  converter. Five real-WebSocket payload-type cases pass; three failed before
  the fix. Existing REST behavior is unchanged. The first converter change was
  ineffective because of ordering; regression tests caught it before completion.
- Fixed the client to describe dependency failures as uncertain outcomes and to
  catch initial subscription/snapshot-send exceptions. Both new client regressions
  failed before the fixes and now pass; no automatic retry/reconnect was added.
- Acceptance evidence: separate browser accounts exchange source and language;
  backend tests verify three members, denied subscriptions/sends/destinations,
  JWT/origin/expiry checks, invalid/oversized messages, connection/rate limits,
  duplicate sequences, atomic concurrent versions, snapshot ordering, TTL/reset,
  and PostgreSQL/Redis failures. Deterministic tests cover slow-client buffer
  overflow, actual executor bounds, and queued-event expiry. No load or hostile
  network benchmark is claimed.
- Final root Maven `clean verify`: **189 tests pass (41 unit, 148 integration)**,
  zero failures/errors/skips, including 24 collaboration integration tests and all
  11 worker regressions. Clean frontend install, lint, typecheck, all **46 Vitest
  tests**, production build, and all **8 Playwright tests** pass. The final browser
  run uses the final backend jar, real dependencies, and retries disabled.
- Dependency audit reports zero known vulnerabilities. Credential/environment
  scripts, Actionlint, tracked/new-file whitespace, and ignored-secret/build-output
  checks pass. Temporary API/browser/container resources were removed. The unchanged
  full development smoke script was not repeated; integration outage and real
  browser checks cover the affected paths.
- Docker was initially stopped and then failed on both known inaccessible runtime
  sockets. With Docker stopped, only inspected zero-byte socket directories were
  backed up/recreated; Engine 29.4.3 then ran the required checks. Images and data
  volumes were preserved. This restores operation, not a permanent fix for the
  recurring Docker Desktop startup issue. The initial Docker test failure and the
  intentionally failing regressions are resolved; no final required check is
  failing/skipped and no unresolved flakiness was observed.
- Architecture/README document the corrected runtime guarantees and error behavior.
  No unnecessary future-milestone feature, new technology, schema change, worker
  behavior, host execution, or obvious dead feature code was found. Initial Redis
  snapshots/TTL/reset remain necessary Milestone 6 safety prerequisites. Existing
  last-write-wins, ephemeral Redis, single-API throughput, notification-gap, and
  Monaco bundle-size limitations remain explicit. Changes are uncommitted;
  remote GitHub Actions for this review remains unverified.

---

## Milestone 7 — Collaboration Reconnect and Redis Lifetime

**Status: DONE**

Deliverables:

- build on Redis state established in Milestone 6
- document version stored
- latest state supplied on join/reconnect
- stale/invalid update handling
- refresh TTL on accepted edits and authenticated join/snapshot activity only
- explicit DOCUMENT_RESET/new generation after expiration, eviction, or data loss
- reconnect snapshot ordering; no blind replay of offline or old-generation edits
- visible unsaved/degraded state during Redis outage
- Redis Testcontainers tests

Acceptance test:

Reconnect restores the latest state while it remains in Redis. TTL expiry and
Redis data loss explicitly reset the document; old-generation updates are
rejected. Tests cover TTL refresh/expiry, snapshot races, and dependency failure
using controllable shorter test TTLs rather than waiting 24 hours.

Implementation and verification (2026-09-17):

- Added manual Reconnect with fresh authenticated subscriptions and a snapshot
  before sends resume. Recovery ignores old connections and timers, buffers
  racing events, rejects version regression, resets per-connection sequencing,
  and visibly reports changed generations. No automatic reconnect or write replay.
- Pending/uncertain source and language are retained as one copyable, discardable
  memory-only backup before recovery replaces the editor. Failure retains local
  text; another explicit attempt is allowed. Navigation/logout/expiry discards
  the page. Existing last-write-wins semantics and 24-hour inactivity TTL remain.
- Existing backend storage and security behavior required no production changes.
  Added five real-container integration cases, eight client unit cases, one
  browser case, and extended two existing browser cases.

| Acceptance criterion | Verified evidence |
| --- | --- |
| Latest state restored on join/reconnect | Real WebSocket reconnect restores content/language/version; two-browser recovery observes another member's accepted edits and resumes deliberate writes |
| Version/generation safety | Stale generations are rejected; client tests cover snapshot/reset races, retired frames, old callbacks, and a version floor across reconnects |
| Redis inactivity lifetime | Real Redis tests use a three-second configured TTL and controlled short expiry; snapshots/accepted edits refresh it, heartbeats do not |
| Explicit reset after expiry/loss | Integration tests cover expiry and concurrent initialization; browser test deletes only its isolated document and verifies the reset notification |
| Dependency failure and authorization | Real Redis pause/recovery, membership revocation on reconnect, existing PostgreSQL loss/JWT expiry tests, and visible browser failure with preserved backup |
| No blind replay | Unit/browser checks verify pending and uncertain drafts stay local, recovery waits for a snapshot, and only deliberate new edits publish |

Checks run:

- `mvnw.cmd --batch-mode --no-transfer-progress verify`: **194 tests passed**
  (41 backend unit, 142 backend integration including 29 collaboration, 11 worker
  integration); zero failures, errors, or skips. Docker was required and available.
- Frontend clean install, lint, typecheck, production build, and **54 unit/component
  tests** pass. **Nine production-browser tests** pass with retries disabled.
- An immediate mobile-width assertion exposed a ResizeObserver timing race.
  It now waits for the same required layout condition. The full suite and three
  additional runs of the affected recovery case pass; no unresolved flakiness.
- Repaired the known Linux CI credential-fixture cleanup prerequisite: remove
  hidden `.env` fixtures with `-Force`. Both credential scripts pass on Windows
  and Linux PowerShell; Linux verification mounted only seven inspected helper/
  test files, not the repository or its secrets. No workflow/technology expansion.
- `git diff --check` passes. The existing Monaco chunk-size advisory remains.
  No required local check is failing, skipped, or unverified. GitHub Actions for
  these uncommitted changes remains unverified until a later authorized push.

Scope: no new dependencies, migrations, backend production behavior, worker jobs,
execution submissions, durable checkpoints, or automatic retries. Run remains
disabled. All Milestone 7 acceptance criteria pass; stop before Milestone 8.

### Review verification (2026-09-17)

- Re-read AGENTS, specification, architecture, and milestone acceptance criteria;
  reviewed all thirteen changed files and the existing Redis/security request path.
  Every acceptance criterion in the table above passes again. No application-code
  correction was required, and no unnecessary abstraction, dead code, security
  regression, or future-milestone feature was identified in the reviewed changes.
- Clean Maven `clean verify`: **194 tests**, zero failures/errors/skips. Real
  PostgreSQL, Redis, and RabbitMQ containers were required and available.
- Frontend clean install, lint, typecheck, **54 tests**, production build, and
  **nine browser tests** pass on Node **24.19.0**. Three further runs of the mobile
  reconnect/backup case pass with retries disabled; no flakiness reproduced.
  The default terminal had Node 26 and produced an engine warning on the first
  install; verification was repeated with an isolated Node 24 runtime, without
  changing the global installation. Dependency audit reports zero vulnerabilities.
- Credential checks pass on Windows and isolated Linux PowerShell; Actionlint
  and `git diff --check` pass. The existing Monaco bundle-size advisory remains.
- Inspected the current [GitHub Actions run](https://github.com/Ywrd10/PairForge/actions/runs/35165402635)
  for Milestone 6 commit `b08b72c`: the frontend job passed, while the Java job
  failed in credential-fixture cleanup and **skipped** its backend/worker test
  step. Hidden `.env` was left behind, causing an interactive directory-removal
  prompt. The two local `-Force` cleanup fixes pass on Linux but are not pushed.
  Remote CI remains a verification gap until an authorized commit/push includes
  these fixes and the resulting workflow passes; this review does not claim
  remote verification of Milestone 7.
- Changes remain uncommitted. Milestone 7 remains DONE on its verified local
  acceptance criteria; Milestone 8 remains TODO. Only review documentation was
  updated during this review.

---

## Milestone 8 — Execution Submission

**Status: DONE**

Deliverables:

- execution REST API
- validation
- source-size limit
- Execution persisted as QUEUED
- RabbitMQ producer
- PostgreSQL commit followed by `execution.jobs` publication with publisher
  confirms, mandatory routing, bounded waits/retries, and explicit failure handling
- conditional QUEUED-to-FAILED handling for dispatch failure/uncertainty; do not
  overwrite a worker claim or terminal result; return ID/status for REST recovery
- Redis-backed per-user rate limits and bounded global outstanding-work admission
- immutable client-visible source/language snapshot and the single-file contract
- execution-history endpoint
- RabbitMQ integration tests

Acceptance test:

Submitting valid code creates one logical execution and publishes a confirmed
job under normal operation. Publish retries reuse its ID and may duplicate
messages. Tests cover nack, unroutable return, confirm timeout, broker outage,
failure-status persistence failure, validation, authorization, and admission limits.

Document/test the remaining crash-after-commit/before-publish window and the
operator procedure for abandoned QUEUED jobs. No transactional outbox or guaranteed
background redispatch is required or implemented in the initial MVP.

### Verification (2026-09-17)

- Added member-authorized submission, paginated history, and detail endpoints.
  Strict source/language input, 64 KiB UTF-8 source and bounded JSON bodies,
  immutable snapshots, no-store responses, and concealed unauthorized reads pass.
- PostgreSQL commits QUEUED before persistent ID-only publication to the durable
  RabbitMQ queue. Mandatory routing, correlated confirms, bounded same-ID retries,
  and conditional dispatch-failure updates pass. No database transaction or
  admission lock spans broker I/O.
- Redis per-user limits/expiry and concurrent global outstanding-work admission
  pass. Redis/PostgreSQL loss fails closed; RabbitMQ nack, unroutable return,
  confirm timeout, and outage produce the documented durable or unknown outcome.
  Tests verify failure-persistence/status-read outages and preservation of a
  concurrent RUNNING or terminal state.
- Seven new publisher unit tests and 26 execution integration tests pass. A
  controlled interruption after commit demonstrates the remaining dual-write
  gap, and the conditional operator recovery SQL is tested and documented in
  Architecture §9. This is a simulated interruption, not an actual process kill.
- Full Maven `verify`: **227 tests** (48 unit, 168 API integration, 11 worker
  integration), zero failures, errors, or skips; real PostgreSQL, Redis, and
  RabbitMQ containers. Docker's recurring inaccessible runtime sockets were
  backed up/recreated before verification; application data was preserved.
- Node 24 clean install, lint, typecheck, **54 frontend tests**, production build,
  and **nine browser acceptance tests** pass. Dependency audit reports zero
  vulnerabilities. Both credential-script checks and `git diff --check` pass.
- The first full run exposed an obsolete RoomIT assertion that the previously
  unimplemented execution endpoint was forbidden. It now checks invalid-body
  rejection and retains denial of unknown routes; the full rerun passes. The
  existing Monaco bundle-size advisory remains; no required local check is skipped.
- No schema migration, new dependency, worker consumer, sandbox runner, execution
  events, or Run UI was added. Single-instance API admission and the documented
  crash window remain approved MVP limitations. Milestone 9 has not begun.
- At implementation verification, changes were uncommitted and remote GitHub
  Actions was **unverified for M8**. The
  passing Milestone 7 workflow is not evidence for these changes. The existing
  workflow will include these tests on a subsequent authorized push.

### Review verification (2026-09-17)

- Re-read AGENTS, the execution product/architecture contracts, and M8/M9 scope.
  Reviewed submission, admission, persistence, publisher/topology, authorization,
  body bounds, error responses, configuration, and tests. M8 remains **DONE**;
  every local acceptance criterion passes and M9 remains TODO.
- Fixed one confirmed defect: Spring-generated negative confirms on channel
  shutdown were classified as definite dispatch failures. A regression test first
  reproduced the error; the publisher now preserves DISPATCH_UNCONFIRMED for a
  local nack without a mandatory return, even when a later retry gets a broker
  nack. Real broker negative confirms still produce DISPATCH_FAILED.
- Added real RabbitMQ stop/start durability verification and concurrent Redis
  per-user admission verification. Removed a potential timing race in the quota
  test: it uses a 60-second window, verifies rejection does not extend TTL, then
  shortens the test key's TTL to exercise real expiry without racing HTTP calls.
- Acceptance evidence: one committed immutable row and a confirmed persistent
  ID-only job; same-ID publication retries; validation/membership/history bounds;
  per-user/global admission; real nack/return/broker outage and simulated confirm
  timeout; failed failure-state persistence/status-read recovery; conditional
  protection of RUNNING/terminal rows; simulated commit-before-publish interruption
  and the documented conditional operator procedure all pass.
- Java 21 root `clean verify`: **230 tests** (49 unit, 170 API integration, 11
  worker integration), zero failures/errors/skips. Node 24 clean install, lint,
  typecheck, **54 frontend tests**, production build, and **nine browser tests**
  pass. Credential-script checks and whitespace checks pass; npm reports zero
  vulnerabilities. No flaky test was observed in this review run. The new defect
  regression intentionally failed before the fix and passed afterward.
- Docker startup again encountered inaccessible runtime sockets. The inspected
  socket directories were backed up/recreated, preserving application data, and
  real-container tests ran successfully. This recurring Desktop issue is an
  environment limitation; no required test was silently skipped.
- No further blocking security, error-handling, dead-code, or complexity issue
  was found within M8. No worker consumer/runner, execution events, frontend Run
  action, migration, dependency, or future-milestone feature was introduced.
- Existing limitations remain: single-API admission, the approved dual-write
  crash window with manual recovery, and the Monaco bundle-size build advisory.
  At review time changes were uncommitted/unpushed; GitHub Actions was unverified for M8.

---

## Milestone 9 — Execution Worker

**Status: DONE**

Deliverables:

- RabbitMQ consumer
- RUNNING state transition
- atomic claims and conditional final-state writes; all terminal executions
  are skipped on duplicate delivery, including failed and timed-out executions

- worker execution abstraction
- final-state persistence
- worker failure handling
- manual acknowledgements, bounded infrastructure retries/backoff, default
  concurrency/prefetch one, and invalid-message handling
- deadline/interruption recovery design; never automatically rerun started code
- database outage/readiness handling and result persistence before acknowledgement
- tests

Acceptance test:

A queued execution reaches a terminal state using a test-only fake runner.

Duplicates before/during/after completion do not invoke the runner again.

Crash/redelivery and failed result-persistence paths are tested. Runtime
execution stays disabled until Milestone 10; no host-process fallback is allowed.

### Verification (2026-09-17)

| Acceptance criterion | Evidence |
| --- | --- |
| QUEUED reaches terminal using a test-only fake | Real PostgreSQL/RabbitMQ test verifies atomic RUNNING claim, immutable snapshot, revision increments, saved output, and terminal result |
| No duplicate rerun | Duplicates before/during/after completion, every terminal status, RUNNING duplicates, eight concurrent claims, and stale final writes are tested |
| Manual acknowledgement and bounded consumption | One consumer/prefetch one verified against the broker; malformed/missing jobs rejected without requeue; result failures retain unacknowledged work for redelivery |
| Crash/redelivery safety | Separate test JVM forcibly killed during fake execution and after committed result before ack; actual redelivery recovers interruption or preserves terminal output without rerunning |
| Failed result persistence | Three-attempt retained-result unit tests, definite denied writes, real PostgreSQL pause, uncertain commit responses, and restart recovery pass; timeout is not mistaken for proof of rollback |
| Failure/readiness handling | Database and broker loss latch consumption off; dependency restoration does not resume it; liveness stays healthy; active timeout, failed/hanging cleanup, and failed startup cleanup are tested |
| Runtime disabled and no host fallback | Packaged worker fails startup when enabled without a runner; fake runners and Flyway absent from runtime JAR; ordinary disabled startup/health checks pass |

- Added worker-local JDBC persistence, runner/result contracts, bounded processing,
  manual-ack consumer, and startup recovery. Three infrastructure attempts with
  backoff, one consumer/prefetch, and operator restart after exhaustion implement
  the approved plan without retry tables, automatic requeue loops, or an outbox.
- Repeatable worker provisioning grants execution SELECT and only lifecycle/result
  column UPDATE after API migrations. Tests prove source/language/room changes,
  INSERT/DELETE, account/membership access, and schema writes remain denied. The
  API still owns migrations; worker Flyway dependencies are test-scoped only.
- Documented configuration and predecessor-stop attestation, cleanup-before-final
  persistence, ambiguous outcomes, manual recovery, and the possible loss of an
  unsaved result. Attestation is not fencing; overlapping workers remain unsupported.
- Java 21 root `clean verify`: **267 tests** (69 unit, 170 API integration, 28
  worker integration), zero failures/errors/skips. After a final test-only
  refinement for ambiguous database commits, worker `verify` passes again:
  **48 tests**, including 17 new execution integration tests. Docker was required
  and available. M9 adds 37 tests overall.
- Node 24.19.0 clean install, lint, typecheck, **54 frontend tests**, production
  build, and **nine browser tests** pass. Credential initialization/isolation
  checks, Actionlint, and whitespace checks pass. npm audit reports zero
  vulnerabilities; the existing Monaco bundle-size advisory remains.
- Initial worker verification exposed test-classpath Flyway auto-configuration;
  worker migrations are now explicitly disabled. An outage assertion incorrectly
  assumed timeout implied rollback; tests now verify both legitimate commit
  outcomes and separately prove definite write failure. Final checks pass, with
  no required local check skipped or outstanding failure.
- No runtime dependency, migration, sandbox implementation/socket, execution
  events, frontend behavior, or host-process execution was added. M10 has not
  begun. Changes are uncommitted/unpushed; GitHub Actions is **unverified for M9**
  until a separately authorized push. No remote CI result is claimed here.

### Review verification (2026-09-21)

- Re-read AGENTS, the specification, architecture, and M9/M10 requirements.
  Reviewed every M9 production/test/configuration/provisioning change. All
  acceptance criteria in the table above pass again; M9 remains **DONE**.
- Fixed a deadline defect: the worker previously trusted only PostgreSQL's
  absolute deadline, so database/worker clock skew could extend the configured
  execution budget. A regression test reproduced the defect before the fix.
  The worker now also enforces a local monotonic budget beginning at the first
  claim attempt. Three tests cover future database time, slow claims consuming
  the budget, and expired durable deadlines; cleanup still precedes final writes.
- Java 21 root `clean verify`: **270 tests** (72 unit, 170 API integration, 28
  worker integration), zero failures/errors/skips. Real claims, duplicate delivery,
  invalid messages, failed/uncertain persistence, database/broker loss, cleanup
  failure, readiness latching, and both actual child-process kill cases pass.
- Node 24.19.0 clean install, lint, typecheck, **54 frontend tests**, production
  build, and **nine browser tests** pass with browser retries disabled. Credential
  scripts, Actionlint, and whitespace checks pass. npm reports zero vulnerabilities;
  the existing Monaco bundle-size advisory remains. No flaky test was observed.
- Rebuilt runtime JAR excludes fake runners and Flyway and rejects startup when
  consumption is enabled without a runner. Default disabled health/startup and
  restricted database permission tests pass. No source is executed on the host.
- Docker initially failed on stale inference/secrets-engine sockets. Backed up
  and recreated only the inspected runtime socket directories, preserving images,
  volumes, and VM data; Docker 29.4.3 then passed the required container tests.
  This restores this run and does not claim a permanent Docker Desktop repair.
- No further blocking security, error-handling, complexity, dead-code, or
  architectural-drift issue was found within M9. The single-worker/predecessor-stop
  requirement, possible loss of unsaved results, and approved dual-write gap remain
  documented limitations. No sandbox, execution events, or later milestone began.
- This review changes only ExecutionProcessor, its unit tests, Architecture, and
  this verification record. No required local check remains failing, skipped, or
  unverified. Changes remain uncommitted/unpushed; remote GitHub Actions for M9
  remains **unverified** until a separately authorized push.

---

## Milestone 10 — Docker Sandbox

**Status: DONE**

Deliverables:

- isolated Java execution
- isolated Python execution
- timeout handling
- memory/CPU restrictions
- mandatory process/storage limits, network none, non-root, dropped capabilities,
  no-new-privileges, retained seccomp, and bounded writable workspace
- trusted pinned images, fixed commands/paths, closed stdin, no packages/secrets
- separate bounded preparation/compilation/runtime and overall deadlines;
  measured language-specific resource settings (no assumed 128 MiB Java budget)

- output limit
- container cleanup
- execution-ID labels and startup/periodic orphan/deadline reconciliation
- bounded Docker logs and concurrent bounded stdout/stderr capture
- compile/runtime failure handling

Required test cases:

- valid Java
- valid Python
- Java compile error
- Python runtime error
- infinite loop
- excessive output
- simultaneous stdout/stderr flood with combined 64 KiB retention and termination
- memory exhaustion, process limit, disk exhaustion, and denied external network
- inability to apply required controls fails closed without executing source
- worker termination, orphan cleanup, and stale result-write prevention

Acceptance test:

All source, including compilation, executes only inside constrained containers,
never the API or worker host process. Required safety/failure tests pass in an
isolated execution test environment and leave no unaccounted containers/workspaces.

### Verification (2026-09-21)

| Acceptance criterion | Evidence |
| --- | --- |
| Real isolated Java/Python | Real Docker compilation/execution, standard-library usage, closed stdin, Java compile/runtime errors, and Python runtime errors pass; PostgreSQL/RabbitMQ integration persists real output |
| Mandatory controls | Inspect and kernel preflight verify non-root UID, no capabilities, no-new-privileges, seccomp, private namespaces, disabled network/swap, read-only source/root, and bounded CPU/memory/PIDs/storage before source runs |
| Time bounds | Separate preparation, Java compilation, runtime, and overall deadlines tested; periodic reconciliation stops overdue activity independently of a blocked exec wait |
| Memory/CPU/process/storage/network | Both languages exceed total container memory in controlled fixtures; half-CPU throttling, refused child creation, full workspace/tmp/shared-memory filesystems, protected-path writes, and denied external connectivity are tested |
| Bounded output/source | Concurrent stdout/stderr flood terminates with exactly 64 KiB combined retention and OUTPUT_LIMIT; invalid UTF-8/NUL handling, source-size rejection, and application exit-code spoofing are tested; Docker logs disabled |
| Fail closed | Missing controls, wrong language image, missing Docker, unsafe configuration/source, and unverified cleanup cannot authorize execution; host proxy settings are cleared and checked |
| Cleanup/recovery | Normal/error/timeout cleanup, startup and periodic orphan cleanup, ownership checks, noncanonical workspace rejection, shutdown interruption, and graceful shutdown final reconciliation pass; actual worker JVM kill leaves an orphan that restart removes before interruption persistence |
| Durable processing contract | Duplicates do not rerun completed executions; stale final writes fail; database/broker loss, manual acknowledgements, bounded retries, and readiness latching remain tested |

- Added a worker-local Docker CLI adapter, shared bounded output collector,
  validated sandbox configuration, and one-container-per-job runner. Source is
  written only to a fixed read-only bind file; compilation artifacts remain in
  bounded tmpfs. No compiler/interpreter runs on the host. No runtime dependency,
  schema migration, additional service, execution event, or frontend feature was
  introduced.
- Added digest-pinned Java/Python image definitions, repeatable image preparation,
  an explicit sandbox profile/launcher flag, and required CI image preparation.
  Default startup stays disabled for execution. The documented environment aliases
  and predecessor-stop attestation are exercised by a real child worker.
- Resource defaults and measured local fixtures are recorded in Architecture §12:
  Java 512 MiB/128 PIDs, Python 128 MiB/32 PIDs, one CPU, no swap, 32/8/4 MiB
  work/tmp/shared memory, 10-second preparation/compilation, 5-second runtime, and
  the existing 30-second overall deadline. Tests used Docker Desktop's Linux VM
  with cgroup v2/seccomp and controlled fixtures, not arbitrary hostile workloads.
- Java 21 root `clean verify` passed **302 tests** before final shutdown/startup
  refinements. The final full worker `verify` passes **86 tests** (29 unit, 57
  integration). Together with the unchanged API's 219 passing tests, all **305
  current Java tests** pass with zero failures/errors/skips. Final JUnit report
  totals confirm 78 unit and 227 integration cases.
- Node 24 clean install, lint, typecheck, **54 frontend tests**, production build,
  and **nine browser tests** pass with browser retries disabled. Credential and
  launcher checks pass on PowerShell 7/5.1; script parsing, Actionlint, local-secret
  exclusion, and whitespace checks pass. npm reports zero vulnerabilities. The
  existing Monaco bundle-size advisory remains.
- Initial fixture failures distinguished a one-CPU workload from actual throttling,
  JVM allocation errors from cgroup OOM, and reliable source-start observation
  from process-list formatting. A real shutdown regression exposed cancellation
  interrupting cleanup even after the host thread exited. Shutdown now joins the
  run thread and performs bounded final reconciliation in the lifecycle stop
  phase before dependencies close; the regression and full worker suite pass.
- All sandbox tests check resource cleanup. Final Docker inspection finds **zero
  sandbox containers**, and execution workspaces are cleaned. No required local
  acceptance check remains skipped, failing, or unverified. There is no observed
  flakiness in the final runs.
- Remaining documented limits: one worker with operator predecessor-stop
  attestation; a crashed worker/Docker outage can leave a constrained container
  until recovery; Docker is not perfect hostile-workload isolation; approved
  dual-write windows remain. Dedicated deployed worker hosting is still M15.
  M10 changes are uncommitted/unpushed, so **remote GitHub Actions for M10 is
  unverified**. M11 Run UI and committed execution events have not begun.

### Review verification (2026-09-22)

- Re-read the agent instructions, specification, architecture, and milestone
  criteria, then reviewed all M10 runtime, configuration, image, launcher, CI,
  and test changes. Every acceptance row above was reverified. No blocking code
  defect, unnecessary abstraction, dead runtime code, or architectural drift was
  identified. No API/frontend feature, schema change, or M11 event path was added.
  Corrected a stale README sentence that still described the sandbox as future
  work; this review required no application-code changes.
- Rebuilt both pinned sandbox images. Java 21 root `clean verify` passed all
  **305 tests** in one run: API 49 unit/170 integration and worker 29 unit/57
  integration, with **zero failures, errors, or skips**. This includes actual
  compiler/runtime execution, all resource/network/output limits, fail-closed
  controls, deadlines, graceful shutdown, worker kill/restart, orphan cleanup,
  dependency failures, duplicate handling, and stale-write protection. Packaged
  worker inspection confirms test-only runners/fixtures are absent.
- Node 24 `npm ci`, lint, typecheck, **54 unit tests**, production build, and
  **nine browser tests** passed; browser retries remain disabled. npm audit
  reports zero vulnerabilities. PowerShell 7/5.1 credential/launcher tests,
  script parsing, Actionlint, Compose configuration, local-secret exclusion,
  and tracked/new-file whitespace checks passed.
- Docker was initially unavailable because Desktop's inference and secrets
  engine sockets were inaccessible. Moved only the stopped runtime socket
  directories to backups and restarted Desktop, preserving images, volumes,
  and settings. The complete container suite then passed on Linux Engine
  29.4.3 with cgroup v2 and built-in seccomp. Final inspection found zero sandbox
  or browser-test containers, no running containers, and no leftover source
  files in the local execution workspace root; test workspace assertions pass.
- No required local check remains failing, skipped, or unverified, and no test
  flakiness was observed in this review. Existing Monaco bundle-size and JVM
  instrumentation advisories remain non-blocking. Remote GitHub Actions for
  these uncommitted/unpushed M10 changes remains **unverified**. The documented
  single-worker, Docker-isolation, crash-recovery, and dual-write limitations
  remain unchanged. **Milestone 10 remains DONE; Milestone 11 remains TODO.**

---

## Milestone 11 — Real-Time Execution Results

**Status: DONE**

Deliverables:

- QUEUED WebSocket event
- RUNNING event
- completion/failure/timeout event
- frontend output display
- persistent RabbitMQ `execution.events` after committed state changes, publisher
  confirms/mandatory routing, and bounded publication failure handling
- API consumes events and broadcasts only to authorized room subscribers
- execution revisions prevent duplicate/out-of-order state regression
- REST snapshot on reconnect and explicit Refresh Status for missed notifications
- documented commit-to-event-publish crash window; no outbox in the initial MVP

Acceptance test:

    click Run → QUEUED → RUNNING → result shown automatically

No refresh is required on the normal delivery path.

The normal path shows the result automatically; intermediate transitions may be
coalesced for fast jobs. Duplicate/delayed events cannot regress a terminal state.

Test event publish failure, API/browser reconnect, and REST recovery when an
event is missed. Persisted results survive notification failure, and code is
never rerun to recover an event. Continuous polling is not required.

### Verification (2026-09-22)

| Acceptance | Evidence |
| --- | --- |
| Run captures the visible source/language | Client regression verifies one POST independent of collaboration debounce; loaded editor and ready connection are required. |
| QUEUED, RUNNING, and terminal notifications follow commits | API/worker integration tests inspect persisted state and persistent RabbitMQ messages; real-browser Java/Python runs show results automatically. Fast intermediate transitions may coalesce. |
| Authorized room delivery | Existing authenticated STOMP connection supports a third same-room subscription; integration tests reject strangers, cross-room subscriptions, and client SEND to execution topics. Two authorized browsers receive the same result. |
| Publication failure cannot lose saved results or rerun code | Confirm/return/nack/timeout/exception tests verify bounded retries. Missing event routing preserves REST results; duplicate job delivery invokes the runner only once. |
| Duplicate/delayed events cannot regress state | Strict envelope validation, authoritative database reads, bounded revision caches, consumer-restart tests, and stale REST/client regressions preserve terminal state. |
| API/browser recovery and missed notifications | Listener stop/restart drains durable events; browser tests deliberately drop notifications, independently recover through Refresh Status and reconnect, and verify no recovery resubmission. A 30-second UI hint performs no polling. |
| Output and failure display | Real sandbox browser tests cover Java/Python success, Python runtime failure, Java compilation failure, and timeout. Output is rendered as text; recent selection remains stable. |

- Java 21 root `clean verify` passes **323 tests**: API 57 unit/174 integration,
  worker 33 unit/59 integration; **zero failures, errors, or skips**. Real
  PostgreSQL, Redis, RabbitMQ, and Docker sandbox checks were required and ran.
- Node 24 clean install, lint, typecheck, **64 unit tests**, production build,
  and dependency audit pass; npm reports zero vulnerabilities. Final browser
  acceptance passes **12 tests** with retries disabled, using the packaged API,
  restricted-role sandbox worker, and isolated dependencies. CI now prepares
  sandbox images and both Java packages for browser acceptance.
- One browser run overlapped the sandbox lifecycle suite and failed an existing
  test before page load with Chromium `ERR_NO_BUFFER_SPACE`; the other 11 passed.
  The complete isolated rerun passed without changing assertions or enabling
  retries. This is recorded as a transient local browser/resource failure, not
  hidden as an automatic retry. The earlier full browser run also passed.
- PowerShell 7/5.1 credential/launcher checks, Actionlint, Compose configuration,
  local-secret exclusion, and tracked/new-file whitespace checks pass. Final
  inspection found zero sandbox containers, browser dependency containers, or
  temporary browser workspaces. Existing Monaco bundle-size/JVM advisories and
  a Rabbit publisher-callback shutdown diagnostic are non-failing; no required
  local check remains failing, skipped, or unverified.
- Architecture/specification/README document the five-field event contract,
  bounded publisher/consumer handling, listener readiness, visible-source Run,
  stable recent-result selection, and manual recovery. No schema, dependency,
  shared-library, outbox, or additional service was added. The approved
  commit-to-publish crash window and single API/worker limits remain.
- M10 commit `8eb1d03` has a successful [GitHub Actions run](https://github.com/Ywrd10/PairForge/actions/runs/35807792192).
  M11 changes remain uncommitted/unpushed, so remote CI for this milestone is
  **unverified**. Local acceptance is complete. **Milestone 12 remains TODO**;
  its broader combined-outage audit has not begun.

### Review verification (2026-09-22)

- Re-read the agent guidance, specification, architecture, and roadmap, then
  reviewed M11 runtime, configuration, security boundaries, tests, CI, and docs.
  Every acceptance row above passes. No additional service, dependency, schema,
  sandbox relaxation, dead runtime code, or unnecessary future-milestone feature
  was identified. The small failure counters/readiness/CI additions belong to M11.
- Fixed one error-handling defect: an accepted HTTP 202 with unreadable JSON
  previously showed only a generic error. The execution client now treats it as
  an uncertain submission and directs read-only recovery without automatic POST
  retry. A new regression failed before the fix and passes afterward, verifying
  recovery of the persisted result and exactly one submission.
- Strengthened browser recovery coverage: a second deliberate run loses its
  terminal notification and remains RUNNING locally until reconnect itself
  fetches the completed result. Refresh Status is independently checked on the
  first run; exactly two deliberate submissions occur. The test now waits for
  the dropped terminal notification rather than assuming all three intermediate
  events arrive. Clarified the architecture's older subscription-order wording
  to match the current pre-snapshot execution subscription.
- Fresh root Maven `clean verify`: **323 Java tests**, zero failures/errors/skips.
  Frontend clean install, lint, typecheck, **65 unit tests**, production build,
  and audit pass (zero reported vulnerabilities). All **12 browser tests** pass
  on the first review run, with retries disabled and no concurrent Java tests.
  The earlier Chromium resource failure did not recur; no new flakiness was
  observed. Existing Monaco/JVM advisories remain non-blocking.
- PowerShell 7/5.1 credential/launcher tests, Actionlint, Compose validation,
  credential exclusion, and whitespace checks pass. Final cleanup inspection
  found zero sandbox containers, browser dependency containers, or browser
  workspaces. No required local check remains failing, skipped, or unverified.
- M11 remains uncommitted/unpushed, so remote CI is still **unverified**. The
  documented dual-write gap, opt-in sandbox worker, and single API/worker limits
  remain. **Milestone 11 remains DONE; Milestone 12 remains TODO.**

---

## Milestone 12 — Reliability

**Status: DONE**

Deliverables:

- audit rate/admission limits introduced in Milestone 8
- audit bounded retries and acknowledgement handling introduced in Milestone 9
- exercise combined broker/database/worker outages and documented recovery
- verify terminal-state protection and missed-event recovery
- verify cleanup and resource bounds from Milestone 10

Acceptance test: the failure matrix in Architecture §16 passes, retry budgets
survive relevant redeliveries, and operator recovery/known limitations are
documented. This milestone does not defer correctness required by earlier work.

### Implementation and verification (2026-09-22)

| Acceptance area | Verified evidence |
| --- | --- |
| Admission/rate limits | Existing concurrent admission tests plus combined Redis/broker outage coverage; restoring dependencies cannot bypass PostgreSQL outstanding capacity. Redis counter loss remains an explicitly documented ephemeral-state limitation. |
| Bounded retry/acknowledgement behavior | Read/claim/result retry limits, duplicate delivery, persistence uncertainty, and both database/broker recovery orders pass. Restoring connectivity does not clear the worker failure latch or reset its budget. Operator restart is a deliberate recovery attempt, not an unlimited automatic retry. |
| Terminal protection and missed events | Started/terminal executions are not rerun; conditional writes preserve committed results. API database outage exhausts notification retries without losing saved output; REST and browser recovery succeed without another submission. |
| Worker/sandbox recovery and bounds | Real process-kill, resource-limit, orphan, shutdown, and combined interruption/Docker-loss tests pass. Cleanup precedes interrupted-state persistence; command-boundary Docker fault injection does not stop the shared daemon. |
| Operator guidance/failure matrix | Architecture §13 documents recovery ordering and retry boundaries; §16 maps every failure scenario to coverage. README links the checklist. Existing dual-write, single-worker, and sandbox trust-boundary limitations remain. |

- The first targeted run exposed an intermittent shutdown error: a RabbitMQ
  channel callback used a terminated executor after a failure-triggered consumer
  stop was interrupted by application shutdown. Fixed `ExecutionConsumer` to
  drain that stop with a ten-second bound before listener/processor teardown and
  prevent late failure callbacks from scheduling work after shutdown starts.
  A deterministic lifecycle regression and both subsequent worker integration
  runs pass; the callback exception did not recur. No exception suppression or
  test weakening was introduced.
- Fresh root Maven `clean verify`: **330 Java tests** (233 backend, 97 worker),
  zero failures/errors/skips. Frontend clean install, lint, typecheck, **65 unit
  tests**, production build, and audit pass (zero reported vulnerabilities).
  All **12 browser tests** pass with retries disabled, run after Java completion.
- PowerShell 7 and 5.1 credential/launcher tests, Actionlint, Compose validation,
  credential exclusion, and whitespace checks pass. Final inspection found zero
  sandbox containers, browser dependency containers, or browser workspaces.
  Existing Monaco bundle-size and JVM agent advisories remain non-blocking.
- No required local check remains failing, skipped, or unverified. M11 commit
  `04693f5` has a successful [GitHub Actions run](https://github.com/Ywrd10/PairForge/actions/runs/35811886123).
  At this implementation checkpoint, M12 changes were uncommitted/unpushed and
  remote CI was **unverified**. No schema, dependency, additional service, outbox, retry ledger,
  automatic restart, or future-milestone functionality was added.
  **Milestone 13 remains TODO.**

### Review verification (2026-09-23)

- Re-read the agent instructions, specification, architecture, and roadmap and
  checked every acceptance area against its implementation and failure tests.
  The reliability changes preserve authorization, resource controls, conditional
  state transitions, manual recovery, and the approved dual-write limitations.
  No further runtime defect, unnecessary abstraction, dead code, architectural
  drift, or future-milestone implementation was identified in the M12 changes.
- Strengthened `ExecutionConsumerTest`: a second consumer stop now asserts that
  the first stop already finished. The earlier fixture blocked both stops on the
  same latch and could miss an overlap; the regression now checks ordering as
  well as waiting and rejects a concurrent teardown.
- The first review build failed its Docker prerequisite (seven backend fixture
  errors, worker reactor skipped). Docker Desktop reproduced its stale Windows
  runtime-socket startup error. Preserved the two socket-only runtime directories
  under dated backup names and restarted Desktop; Linux Engine 29.4.3 and the
  pinned sandbox images were restored without resetting Docker data or settings.
  No application change or test skip was used to work around that failure.
- Fresh root `clean verify` then passed **330 Java tests**, zero failures/errors/
  skips. Frontend clean install, lint, typecheck, **65 unit tests**, build, and
  audit pass (zero reported vulnerabilities). All **12 browser tests** pass with
  retries disabled and Java/browser container suites run sequentially. No new
  flakiness or recurrence of the shutdown callback exception was observed.
- PowerShell 7/5.1 credential and launcher tests, Actionlint, Compose validation,
  secret exclusion, and whitespace checks pass. Cleanup inspection found zero
  sandbox containers, browser dependency containers, or browser workspaces.
  Existing bundle-size/JVM advisories are non-blocking. All required local
  acceptance checks pass; the reviewed snapshot is ready for the authorized
  commit/push and its GitHub Actions verification.
- **Milestone 12 remains DONE. Milestone 13 remains TODO and is planning only.**

### CI correction and final local verification (2026-09-23)

- The first M12 push (`5aaa23a`) passed frontend/browser CI but failed one Linux
  sandbox test in [run 35872386851](https://github.com/Ywrd10/PairForge/actions/runs/35872386851).
  Killing the Docker CLI after output overflow could close its pipe during a
  read, replacing the expected limit result with `IOException: Stream closed`.
  This was a runtime race, not an acceptable flaky-test retry; M12 was reopened
  while correcting it.
- `DockerCommandClient` now tolerates pipe I/O errors only after its own limit
  termination and confirmed CLI exit. Unexpected read errors and drain timeouts
  still fail closed; the runner must separately verify container cleanup.
  Deterministic timeout/overflow regressions failed before the fix and pass
  afterward; a third case confirms ordinary read failures remain errors.
- Targeted verification passed three unit and 26 real sandbox/lifecycle tests.
  A subsequent fresh worker `clean verify` passed all **100 worker tests**.
  Together with the unchanged backend's **233 passing tests** from the full
  review build, the final local report set contains **333 Java tests**, zero
  failures/errors/skips. A final browser run passed all **12 tests** with retries
  disabled. Frontend's 65 tests and its build/lint/type checks remain passing.
- Final cleanup found no sandbox or browser dependency containers/workspaces.
  No required local acceptance check remains failing or unverified. The corrected
  commit's remote CI must be checked after push; the failed first run is not
  passing evidence. No Milestone 13 implementation was introduced.

---

## Milestone 13 — Observability

**Status: DONE**

Deliverables:

- extend Actuator/health introduced in Milestone 0
- Prometheus metrics
- HTTP metrics
- WebSocket metrics
- execution metrics
- queue waiting-time metric
- execution-duration metric
- useful structured logging
- dispatch/event/cleanup failure metrics and bounded metric-label cardinality

Acceptance test: metrics reflect representative successful/failed/timed-out jobs
and queue wait/runtime separately; management exposure is restricted and logs
contain no credentials, invitation tokens, or submitted source/output by default.

### Implementation and verification (2026-09-23)

- Milestone 12 was already committed/pushed as `5aaa23a` and `ae483e0`; confirmed
  local HEAD and GitHub master both match `ae483e0`. Its final
  [CI run passed](https://github.com/Ywrd10/PairForge/actions/runs/35875340626).
- Added Boot-managed Prometheus registries and opt-in observability profiles.
  Separate loopback listeners default to API 8082 / worker 8083; health details
  stay hidden, JMX remains disabled, application ports reject scrapes, and unsafe
  public/shared listener configurations fail startup. Default exposure is still
  health-only; observability does not enable sandbox consumption.
- Added active/authenticated WebSocket gauges, committed submission/outcome
  counters, database-clock queue timing, stored runner-duration timers, separate
  preparation/compilation/runtime timers, and cleanup-failure metrics. Existing
  dispatch/event/uncertain-persistence counters are exported. Outcome counts are
  independent of notification delivery and do not increase on duplicate jobs or
  persistence retries. Uncertain commits can undercount; no durable ledger or
  outbox was added. Labels are bounded and omit private identifiers/payloads.
- New execution creation and API dispatch-failure completion use PostgreSQL
  timestamps, preserving ordering across host-clock differences without schema
  changes. Stored duration and REST contracts remain unchanged. JSON logs carry
  safe request/execution identifiers; request context clears on exceptions.
- Fresh root Maven `clean verify` passed **351 Java tests** (243 backend,
  108 worker), zero failures/errors/skips. Tests verify restricted scrapes with
  and without the profile, unsafe bind rejection, dependency loss, label bounds,
  HTTP/WS metrics, real successful/failed/timed-out executions, separate phase and
  queue timing, duplicate/uncertain-commit behavior, cleanup failure, timestamp
  ordering, structured records, and exclusion of passwords/tokens/invitations/
  source/output. Existing authorization, recovery and sandbox suites also pass.
- Frontend clean install, lint, typecheck, **65 unit tests**, production build,
  and audit pass (zero reported vulnerabilities). All **12 browser tests** pass
  with retries disabled, run after Java/container verification completed.
- PowerShell 7 and 5.1 credential/launcher tests, script syntax, Actionlint,
  Compose validation, whitespace and secret-exclusion checks pass. The live
  `smoke.ps1 -Observability -CheckOutages` run passed startup, private scrapes,
  application-port denial, health/liveness and all three dependency stop/start
  recovery checks using the non-consuming worker. Launched app processes were
  stopped; final inspection found no sandbox/browser containers or browser
  workspaces. Existing Compose dependencies remain healthy and data is retained.
- Initial checks exposed test-fixture constructor/registry-cleanup mistakes and
  Spring test contexts disabling exporters by default; these were corrected
  before passing verification. Smoke checks now wait for startup before asserting
  scrape denial. Docker Desktop's recurring stale runtime-socket error required
  a reversible socket-directory backup/restart; no data/settings reset or skipped
  integration test was used. No unresolved failure or observed flakiness remains.
- All local acceptance criteria pass. M13 changes remain uncommitted for review;
  remote CI for this snapshot is **unverified**, not represented by the M12 run.
  Existing bundle/JVM advisories remain non-blocking. No additional service,
  frontend feature, deployment, CI-hardening work, or Milestone 14 implementation
  was introduced. **Milestone 14 remains TODO.**

---

## Milestone 14 — CI Hardening

**Status: DONE**

Deliverables:

Extend the basic GitHub Actions workflow introduced in Milestone 0. It runs:

- backend build/tests
- worker build/tests
- frontend build/checks
- safe, isolated execution integration checks where the runner supports controls
- explicit failure on missing required prerequisites, not silent test skips
- documented reproducible versions and test-result diagnostics

Acceptance test:

A failing test produces a failing workflow.

### Implementation and verification (2026-09-24)

- Preserved the two Ubuntu hosted jobs and existing Java/frontend/container
  coverage. Actions use verified full SHAs; CI pins Temurin 21.0.12+1 and Node
  24.19.0, retaining the Maven checksum, npm lockfile and sandbox image digests.
  Read-only permissions, disabled checkout credential persistence, concurrency
  cancellation and 20-minute job limits bound the workflow. No deployment or
  repository protection settings were changed.
- Added explicit tool/Docker/Linux/cgroup v2/seccomp prerequisite checks and
  source-discovered report gates. Missing, malformed, empty, inconsistent,
  failed or skipped suite reports fail validation. Existing worker preflight
  still checks effective sandbox limits. Required tests never silently skip.
- Java and frontend jobs publish only sanitized source/status/count summaries
  after success or failure, retained for seven days. Raw XML, exception payloads,
  test-case names, environment files and browser captures are excluded. Frontend
  unit results are collected before Playwright clears its output directory.
- All **59 CI gate checks** pass in PowerShell 7 and Windows PowerShell 5.1,
  including prerequisite faults, missing suites, malformed/DTD XML, failure/
  skip/count inconsistencies and sentinel-secret exclusion. A real prerequisite
  CLI run against an unavailable local Docker endpoint also failed explicitly;
  the real daemon and its configuration were unchanged. Existing credential
  and launcher tests, PowerShell syntax, Actionlint, Compose configuration,
  workflow permission/pin review, whitespace and ignored-file checks pass.
- Fresh local Maven `clean verify`: **351 tests**, zero failures/errors/skips.
  Frontend clean install, lint, typecheck, **65 unit tests**, production build and
  audit pass (zero reported vulnerabilities). The first full local browser run
  had one guest-reconnect failure. Review found injected disconnects closed only
  the browser side of Playwright's proxy; the shared fault fixture now closes
  both sides. Five independent reconnect repetitions and the corrected full
  **12-test browser suite** pass with retries disabled. Production connection
  limits and application behavior were not changed or weakened.
- Docker Desktop required the previously verified reversible stale runtime-
  socket directory backup/restart. No data/settings reset was used. Final local
  inspection found no sandbox/browser dependency containers or browser workspaces.
- The deliberate Java and frontend failure probes each failed their job and
  [workflow 35959029066](https://github.com/Ywrd10/PairForge/actions/runs/35959029066).
  Downloaded sanitized artifacts correctly identify both failing suites; other
  suites skipped after these intentional failures are not passing evidence.
  The temporary probe branch/worktree was removed and neither probe is in the
  implementation candidate.
- The initial clean candidate passed
  [workflow 35958984096](https://github.com/Ywrd10/PairForge/actions/runs/35958984096)
  with 351 Java, 65 frontend unit and 12 browser tests. The final candidate
  `06f3c67`, including the browser fixture correction, passed
  [workflow 35959977550](https://github.com/Ywrd10/PairForge/actions/runs/35959977550).
  Downloaded final artifacts confirm **351 Java, 65 frontend unit and 12 browser
  tests**, with zero failures/errors/skips. No unresolved required check remains.
- Implementation commits `5adce48` and `06f3c67` were published to
  `codex/milestone-14-ci` for real acceptance before publication to `master`.
  This completion record preserves the verified milestone evidence.
  No intentional failure probe is retained.

Milestone 15 remains TODO; no deployment work was introduced.

---

## Milestone 15 — Deployment

**Status: DONE**

Deliverables:

- deployed frontend
- deployed backend
- deployed worker
- PostgreSQL
- Redis
- RabbitMQ
- HTTPS
- production configuration
- secrets handled securely
- dedicated worker host/VM trust boundary and restricted worker credentials
- execution access limited to invited testers; registration alone is insufficient
- no public Docker/broker/database/management interfaces
- measured configuration, recovery procedure, and documented dual-write limitations

Acceptance test:

At least two invited testers can complete the full PairForge workflow using the deployed
application.

Verified 2026-09-27 on the existing approved AWS two-host deployment. The owner
confirmed the teammate independently authenticated, joined by invitation, edited
in both directions and received Java/Python results automatically. Database
evidence verifies successful runs by both approved UUIDs, compilation/runtime
errors, timeout/recovery and overlapping accepted requests processed serially by
the worker. Reload restoration and pre-allowlist rejection were owner-reported.
The fresh encrypted S3 backup restored all 18 executions and application-role
reads passed; temporary restore resources were removed. Admission closed, work
drained, services stopped and both EC2 hosts were independently verified stopped.
See `docs/MILESTONE_15_ACCEPTANCE.md` and `docs/DEPLOYMENT.md` for exact evidence,
sampled capacity, prior test results and limitations. CPU-credit metrics were
unavailable to the deployer; no sustained-load or continuous peak measurement is
claimed. At the Milestone 15 checkpoint, Milestone 16 remained TODO.

Post-completion review (2026-09-27): corrected the worker-role helper's missing
SQL-file reference and added a release-artifact regression check. Frontend's
70 tests/checks/build, nine focused Java production tests, Maven packaging,
configuration/identity/syntax checks and read-only AWS checks pass. Docker-based
fixture reruns are currently environment-blocked; previous deployed acceptance
remains the completion evidence. See `docs/DEPLOYMENT.md` for exact limitations.

---

## Milestone 16 — Load Testing

**Status: DONE**

Measure separately:

### WebSocket workload

Record:

- concurrent connections
- update latency
- error rate

### Execution workload

Record:

- number of jobs
- queue delay
- job throughput
- execution latency
- failures

Document the exact test environment and methodology.

Never present local benchmarks as production-scale performance.

Local checkpoint: candidate `149b579` passed Actions run `36355070812` with
365 Java, 70 frontend unit, 22 harness and 12 browser tests; zero failures,
errors or skips. The saved baseline and documented summaries match: 2,040/2,040
collaboration deliveries, nine successful reconnect checks and 30 successful
execution submissions including six warm-ups. The original measurements remain
valid and have not been rerun. See `docs/LOAD_TESTING.md` for the environment,
methodology, resource observations, cleanup evidence and limitations.

AWS checkpoint (2026-09-28): the approved 45-minute operating window ended with
both hosts stopped, queues drained, sandbox/workspace cleanup verified and a
fresh encrypted backup verified. All 2,040 expected collaboration deliveries and
nine reconnect checks passed. The first Java batch accepted and completed five
jobs, including its warm-up; both observers received terminal results. A multiline
JSON parsing error in the adapter's subsequent read-only timing inspection
stopped progression. The parser is fixed and the saved timings were recovered
without rerunning any work. The approved seven-minute continuation did not start:
the private login terminal was unavailable and the operating-window guard left
insufficient time. No additional jobs were submitted.

The owner subsequently approved one new twenty-minute operating window. The
visible-terminal/authentication gates were tested before startup; admission
remained closed until both approved accounts authenticated privately. Only the
remaining two Java and three Python batches ran: 25 new submissions including
five warm-ups, bringing the complete cloud benchmark to exactly thirty accepted,
successful jobs. Both observers received all terminal results; no rejections,
uncertain submissions, execution/notification failures or timeouts occurred.
The original collaboration stages and five Java jobs were verified unchanged.
Complete queue/runner/terminal measurements and small-batch throughput are in
`docs/LOAD_TESTING.md` and `docs/load-results/m16-aws-2026-09-28.json`.

Combined AWS sampling retained 194 observations with zero collection errors,
readiness loss, OOM or cleanup failures. Sampled CPU peaked at 83% app / 65%
worker; minimum available RAM was 2,665.40 / 1,212.35 MiB and disk 23.17 / 14.14
GiB. No configured guard fired and no upgrade was required for the bounded
workload. Standard credits were verified; credit balances remain unmeasured
under existing IAM permissions. These are sampled observations and small
repeated batches, not sustained production-capacity claims.

Final durable inspection verified thirty distinct successful jobs and zero
QUEUED/RUNNING executions globally. Both queues drained; admission closed;
worker/sandbox/workspace and observer cleanup passed. The fresh encrypted backup
passed checksum verification and retains the seven-day lifecycle. Both original
hosts were independently verified stopped by 13:34:32.683 UTC, 17m26.313s after
startup, inside the approved twenty-minute window. Permitted before/after
inventories and private app routes matched; no resources were added or resized.
The original local measurements remain valid and were not rerun.

Earlier CI run `36374218145` passed Java but failed one frontend
page test; dependent load/build/browser steps did not run. Its synchronization
fix preserves all assertions. Replacement candidate `bdf5c54` passed Actions run
`36375165081`: 365 Java, 70 frontend unit, 35 harness and 12 browser tests, with
zero failures, errors or skips. Keep those gates green for the final candidate.
Documentation checkpoint `c4fa299` also passed run `36375766436` before the
continuation. Final relevant checks pass 35 harness tests, lint/typecheck,
PowerShell syntax and mocked authentication gates/marker cleanup. The final
pushed candidate uses the same mandatory workflow/report gates. See
`docs/LOAD_TESTING.md` for complete measurements, fixes, verification and
operational limitations. Milestone 17 remains untouched.

---

## Milestone 17 — Portfolio Polish

**Status: DONE**

Deliverables:

- final README
- architecture diagram
- screenshots
- setup documentation
- testing documentation
- deployment explanation
- benchmark results
- known limitations
- future improvements

Acceptance test:

A technical reviewer should understand what PairForge is, why its architecture
is interesting, and how to run it within approximately one minute of opening
the repository.

Completion evidence (2026-09-28): the root README now leads with the implemented
workflow, engineering features and a Mermaid architecture diagram, followed by
the execution path, role-based stack, verified tests, bounded AWS measurements,
deployment/security boundaries, usable local startup and explicit limitations.
It avoids private account/host/allowlist identifiers. The original 900-line
development log was replaced with focused `LOCAL_DEVELOPMENT.md` and `TESTING.md`
guides; dated verification/incident notes remain in
`docs/history/VERIFICATION_NOTES.md`. Architecture cross-references now point to
the current guides and clarify that Compose starts infrastructure only.

No tracked presentation screenshots were available. The approved fallback is
documented in `docs/SCREENSHOTS.md`: three exact manual captures for collaboration,
execution output and the dashboard, with redaction guidance. No invented images,
broken image placeholders or demo outcomes were added. These optional owner
captures remain a presentation follow-up, not fabricated completion evidence.

Documentation checks passed: relative links/anchors, Markdown fences, GitHub
Markdown rendering, Mermaid parsing/rendering with visual inspection, quoted
benchmark/test counts against saved artifacts, script/configuration references,
fifteen PowerShell command-block syntax checks, and whitespace/repository hygiene.
Exact README setup blocks were exercised against copied real helpers with
external commands stubbed; this verified ordering/profile/environment handoff
without starting application services. Maven/tool versions and Compose config
validation passed. This is documentation verification, not a new end-to-end
startup or benchmark run. No product code, configuration, dependency pins, CI
workflow, Milestone 16 evidence or deployment resource changed. Full existing
CI runs automatically on the publication push; the resulting run is reported
with the final commit. Milestone 18 remains untouched.

---

## Milestone 18 — Beta Testing

**Status: TODO**

Goal:

Have real users test PairForge if practical.

Possible users:

- UCF classmates
- CS friends
- project teammates

Record genuine:

- bugs
- synchronization failures
- usability issues
- execution failures

Fix meaningful problems.

Never fabricate usage numbers.

---

# Resume-Ready Threshold

PairForge becomes resume-ready once the following are complete:

- Java/Spring Boot backend
- PostgreSQL persistence
- authentication
- real-time WebSocket collaboration
- Redis state
- asynchronous execution queue
- separate worker
- Docker-based Java/Python execution
- tests
- CI
- deployment
- architecture documentation
- meaningful measured load/performance test

Do not delay resume readiness solely to add additional technologies.

---

# Explicitly Deferred

Do not prioritize:

- Kubernetes
- Kafka
- Terraform
- CRDTs
- GraphQL
- microservices
- additional execution languages
- extensive social features
- transactional outbox (future remedy for job/event dual-write crash windows)
- durable collaborative-document checkpoints
- presence, refresh tokens, and managed-service optimization
