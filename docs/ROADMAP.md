# PairForge Roadmap

## Current Goal

Build PairForge into a deployed, tested, portfolio-quality collaborative coding
platform.

Work on one milestone at a time.

Do not begin the next major milestone automatically.

Current milestone: Milestone 0 (DONE). All foundation acceptance checks pass.
Milestone 1 remains TODO and requires a separate implementation request.
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

**Status: TODO**

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

---

## Milestone 2 — Authentication

**Status: TODO**

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

---

## Milestone 3 — Rooms

**Status: TODO**

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

---

## Milestone 4 — Frontend Application

**Status: TODO**

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

---

## Milestone 5 — Monaco Room

**Status: TODO**

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

---

## Milestone 6 — WebSocket Collaboration

**Status: TODO**

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

---

## Milestone 7 — Collaboration Reconnect and Redis Lifetime

**Status: TODO**

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

---

## Milestone 8 — Execution Submission

**Status: TODO**

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

---

## Milestone 9 — Execution Worker

**Status: TODO**

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

---

## Milestone 10 — Docker Sandbox

**Status: TODO**

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

---

## Milestone 11 — Real-Time Execution Results

**Status: TODO**

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

---

## Milestone 12 — Reliability

**Status: TODO**

Deliverables:

- audit rate/admission limits introduced in Milestone 8
- audit bounded retries and acknowledgement handling introduced in Milestone 9
- exercise combined broker/database/worker outages and documented recovery
- verify terminal-state protection and missed-event recovery
- verify cleanup and resource bounds from Milestone 10

Acceptance test: the failure matrix in Architecture §16 passes, retry budgets
survive relevant redeliveries, and operator recovery/known limitations are
documented. This milestone does not defer correctness required by earlier work.

---

## Milestone 13 — Observability

**Status: TODO**

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

---

## Milestone 14 — CI Hardening

**Status: TODO**

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

---

## Milestone 15 — Deployment

**Status: TODO**

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

---

## Milestone 16 — Load Testing

**Status: TODO**

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

---

## Milestone 17 — Portfolio Polish

**Status: TODO**

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
