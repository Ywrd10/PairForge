# PairForge Architecture

## 1. Architectural Overview

PairForge uses a modular Spring Boot backend plus a separate execution worker.

High-level architecture:

    React + TypeScript + Monaco <-- REST / WebSocket --> Spring Boot API
                                                          |       |
                                                     PostgreSQL  Redis
                                                          |
                                                   Execution Worker

    API -- RabbitMQ execution.jobs --> Worker -- disposable Docker containers
    API <-- RabbitMQ execution.events -- Worker

Both processes access PostgreSQL through narrowly scoped repositories. The API
owns browser connections; the worker publishes committed execution changes.
Initially deploy one API instance and one worker instance. This is a deployment
scope decision, not a two-member room limit or a horizontal-scaling claim.

The architecture intentionally avoids microservices.

The API is a modular monolith.

The execution worker is separate because untrusted user code must not execute
inside the API process.

---

## 2. Component Responsibilities

### React Frontend

Responsible for:

- authentication UI;
- dashboard;
- room UI;
- Monaco Editor;
- sending REST requests;
- maintaining WebSocket connection;
- displaying execution events/output.

The frontend must not contain security-sensitive authorization logic that is
trusted by the backend.

---

### Spring Boot API

Responsible for:

- authentication;
- authorization;
- user management;
- room management;
- room membership;
- WebSocket connections;
- collaboration validation;
- execution submission;
- execution history;
- RabbitMQ publishing;
- consuming execution events and broadcasting committed state;
- metrics.

The API MUST NOT execute submitted source code.

---

### PostgreSQL

PostgreSQL is the durable system of record.

It stores:

- users;
- rooms;
- room membership;
- executions.

Schema evolution uses Flyway.

The API owns migrations. The worker does not migrate schemas and uses credentials
limited to execution data, with no password-hash access or membership writes.

---

### Redis

Redis stores ephemeral/distributed state.

Initial responsibilities:

- latest editor state;
- editor document version;
- document generation and 24-hour inactivity TTL;
- presence if implemented;
- execution rate limiting.

Redis must not become the durable system of record for core application data.

---

### RabbitMQ

RabbitMQ transports jobs through `execution.jobs` and committed state changes
through `execution.events`. These are separate durable queues bound to an
application direct exchange using the corresponding routing keys. Use persistent
messages, publisher confirms, mandatory routing, and manual consumer acknowledgements.

The API:

    validates request
    → persists execution as QUEUED
    → publishes execution ID

The worker consumes the message and performs execution.

Delivery is at least once for successfully published messages. PostgreSQL commit
and RabbitMQ publish are not atomic. There is no transactional outbox in the
initial MVP; §§9 and 14 define explicit failure handling and remaining gaps.

Large source contents should generally remain in PostgreSQL rather than being
duplicated inside queue messages.

---

### Execution Worker

Responsible for:

- consuming execution jobs;
- transitioning execution state;
- preparing temporary files;
- launching execution containers;
- enforcing limits;
- capturing stdout/stderr;
- persisting results;
- publishing committed state changes through `execution.events`;
- cleaning resources.

---

## 3. Backend Structure

Organize primarily by domain.

Suggested structure:

    com.pairforge.api
    ├── auth
    ├── user
    ├── room
    ├── collaboration
    ├── execution
    ├── infrastructure
    └── common

Within domains, use controllers/services/repositories/DTOs where appropriate.

Do not create unnecessary layers simply to satisfy this structure.

Use a Maven aggregate with independently runnable `backend` and
`execution-worker` modules. Neither runnable module imports the other. The API
uses `com.pairforge.api` with domain subpackages; the worker uses
`com.pairforge.worker`. Start without a shared library; introduce a small shared
contract only when real duplication justifies it. Worker persistence remains a
narrow execution repository, not access to API services or all API entities.

---

## 4. Durable Data Model

### User

    id
    email
    password_hash
    created_at

Email must be unique.

Store and uniquely constrain a documented normalized email representation.
Use UUID identifiers, explicit foreign keys and NOT NULL constraints, and UTC
`timestamptz` timestamps throughout. Enforce supported language/status values
and field-size limits. Add schema changes with the milestone that needs them.

Milestone 1 persistence decisions:

- Normalize email with Java `trim()` followed by `toLowerCase(Locale.ROOT)`;
  allow at most 254 characters and do not rewrite provider-specific aliases.
  The database additionally rejects noncanonical or duplicate stored emails.
  Email syntax validation and password hashing belong to Authentication.
- Store password hashes in a nonempty field of at most 255 characters; model
  constructors accept an already computed hash, never perform authentication.
- Use application-generated UUIDs and `Instant` values truncated to microseconds
  to match PostgreSQL precision. All foreign-key deletion rules are RESTRICT;
  there are no implicit cascading deletes.
- Model relationships as scalar UUID references, with database foreign keys;
  `RoomMember` uses the composite primary key `(room_id, user_id)`. Avoid eager
  entity graphs and cross-module entity dependencies.
- Flyway migrations live only in the API. Hibernate uses `ddl-auto=validate`,
  JDBC timestamps use UTC, and Open Session in View is disabled. A migration or
  schema-validation failure prevents API startup. After startup, dependency loss
  affects readiness while liveness remains independent.
- Local worker credentials are distinct from the API/bootstrap credentials.
  Repeatable provisioning grants only database connection and schema usage in
  Milestone 1, with no table access, DDL, or temporary-table permission. The
  provisioning step revokes both table and column grants and refuses roles with
  existing object ownership or membership in other roles. The
  worker neither migrates nor imports the API module. Grant narrowly scoped
  execution permissions when its persistence path is implemented in Milestone 9.

---

### Room

    id
    owner_id
    name
    language
    invitation_token_hash
    created_at
    updated_at

Names contain 1–120 characters with at least one non-space character. Invitation
hashes are SHA-256 encoded as 64 lowercase hexadecimal characters; token issuance
and admission remain part of Milestone 3.

---

### RoomMember

    room_id
    user_id
    joined_at

Constraint:

    UNIQUE(room_id, user_id)

Create owner membership in the room-creation transaction. Joining is idempotent
for existing members. There is no fixed two-member constraint. Index membership
by user for room listing. Invitations are high-entropy bearer secrets: hash them
at rest, accept them in request bodies, and never log them or expose the hash.
Issue the token to the owner at creation; token rotation is future work.

`Room.language` is the initial/default language. The active collaborative
document language lives with its Redis content and resets to the room default
if temporary state is lost. Each execution stores its own immutable language.

---

### Execution

    id
    room_id
    submitted_by
    language
    source_code
    status
    stdout
    stderr
    exit_code
    duration_ms
    created_at
    started_at
    completed_at
    deadline_at
    state_revision
    failure_reason
    output_truncated

Execution status:

    QUEUED
    RUNNING
    SUCCEEDED
    FAILED
    TIMED_OUT

Index room history by `(room_id, created_at, id)` and execution status/deadline
for interrupted-job inspection. Source and result detail are immutable after a
terminal transition. Exit code may be absent when compilation or infrastructure
fails before a runnable process completes. Duration excludes queue waiting time;
measure preparation/compilation separately from runtime.

Failure reasons distinguish dispatch failure, dispatch unconfirmed, compilation,
runtime, memory limit, output limit, and infrastructure interruption. A terminal
FAILED execution may be created directly from QUEUED before code starts.

Milestone 1 stores source and combined stdout/stderr with separate 64 KiB UTF-8
byte budgets, enforced in PostgreSQL using `octet_length`. New executions have
QUEUED status, empty output, revision zero, and nullable result/deadline timestamps.
History reads use descending `(created_at, id)` ordering and bounded pages.
Revision is an explicit state-transition counter, not a JPA `@Version` field;
conditional claims, terminal-state protection, and authorization are implemented
with the execution features, not inferred from this schema alone.

---

## 5. Authentication

Use Spring Security.

Passwords:

    plaintext
       ↓
    BCrypt
       ↓
    stored hash

Authentication uses JWT access tokens for the MVP.

REST requests use:

    Authorization: Bearer <token>

WebSocket connections must also establish authenticated user identity.

Use short-lived JWT access tokens held in frontend memory for the MVP; reload or
expiry requires login. Validate signature, allowed algorithm, issuer, audience,
and expiry. Refresh tokens are deferred. Apply login throttling and avoid
credential/token disclosure in errors and logs.

### Milestone 2 authentication contract

- `POST /api/auth/register` accepts email/password, normalizes email using the
  existing trim/lowercase rule, and returns 201 with `{id, email}`. A concurrent
  duplicate hits the database unique constraint and returns 409; no registration
  token is issued. Email ownership verification is not part of this milestone.
- `POST /api/auth/login` returns `{accessToken, tokenType, expiresIn, expiresAt}`.
  Unknown users and incorrect passwords receive the same 401 message; unknown
  users still run one BCrypt comparison against a process-local dummy hash.
- `GET /api/auth/me` is the protected acceptance endpoint. It derives the UUID
  from the validated token and returns the persisted user's `{id, email}`.
  A missing user is unauthorized. Room endpoints also require a persisted user.
- Passwords contain at least 15 Unicode code points and at most 72 UTF-8 bytes;
  malformed Unicode and NUL are rejected. Passwords are never trimmed or silently
  truncated. BCrypt cost defaults to 12; tests explicitly use 4 for speed.
- The API signs HS256 JWTs using `JWT_KEY_HEX`, exactly 32 random bytes represented
  by 64 hex characters. Missing/malformed keys fail startup without printing the
  key. Spring's resource-server decoder allows HS256 only and validates issuer
  `pairforge-api`, audience `pairforge-browser`, canonical UUID subject, required
  iat/nbf/exp, and a maximum configured lifetime (default 900 seconds). Time
  validation has zero clock skew. Tokens carry no email, password, or room claims.
- REST authentication is stateless: no session, form login, Basic authentication,
  refresh token, or logout/revocation endpoint. The future frontend holds the token
  only in memory, sends Authorization headers, and requires login after reload or
  expiry. Clearing browser memory does not revoke a stolen token; rotating the
  signing key invalidates all outstanding tokens. Use HTTPS outside loopback.
- CSRF is disabled for this bearer-header-only API: it accepts no automatically
  attached authentication cookies, sessions, or Basic credentials. The credential
  endpoints require JSON, and unmatched routes are denied by default. Reassess CSRF before
  adding any browser-automatic credentials. CORS uses explicit configured origins
  (startup rejects wildcards and malformed origins), GET/POST,
  and Authorization/Content-Type headers, with credentialed cookies disabled.
  The local profile tracks the configured frontend port. WebSocket security
  remains a separate Milestone 6 concern.
- Authentication bodies are capped at 4096 bytes, including chunked requests.
  Request IDs precede security; body limits follow CORS/security and precede MVC,
  so allowed browsers can read oversized-request errors and denied origins cannot
  bypass CORS by sending oversized bodies. Non-JSON content types are rejected
  before MVC can parse forms or multipart uploads.
  Error bodies use `{code, message, requestId, fieldErrors}`; request IDs are
  server-generated. Responses do not disclose rejected passwords/tokens or SQL
  exception details. Non-health API Actuator requests are denied by Security
  (401 without credentials, 403 with a valid token); worker endpoints remain 404.

Default admission limits, configurable through `pairforge.auth` properties:

| Operation | Limit | Window |
| --- | --- | --- |
| Login by peer IP | 20 attempts | 60 seconds |
| Login by normalized account | 10 attempts | 900 seconds |
| Registration by peer IP | 5 attempts | 3600 seconds |

One Redis Lua operation checks and increments all relevant counters atomically,
with expiry set on their first increment. Limits apply before BCrypt and count
successful as well as failed valid-shaped attempts. Rejected attempts do not
extend the window. Redis keys use domain-separated HMAC digests of identifiers.
Return 429 with Retry-After on exhaustion and fail closed with 503 on Redis loss.
Database access/transaction failures also return safe 503 responses; registration
after an uncertain response may return 409, and login can confirm the account.
No automatic database-operation retries are performed.

Peer IP comes from the connection, ignoring client Forwarded/X-Forwarded-For
headers. A trusted-proxy configuration must be deliberately added at deployment.
These are basic abuse controls: shared IPs share a budget, account limits can
temporarily deny legitimate login, and Redis eviction/restart or key rotation
resets counters. There is no durable account lockout or IP-spoofable bypass.

BCrypt runs outside database transactions. Repositories retain short transactions;
no schema migration or API/worker module coupling is added for authentication.

### Milestone 4 browser contract

- The Vite React SPA uses React Router declarative routes for `/login`, `/register`,
  `/dashboard`, and `/rooms/:roomId`. Milestone 4 introduced authorized room
  metadata; Milestone 5 extends that route with the local editor described below.
- A small session object holds the JWT privately in memory and exposes user/expiry
  state through a React provider. Login confirms identity with `/api/auth/me`.
  Registration does not log in automatically. Logout/reload removes browser state,
  without implying server-side token revocation. No cookies, localStorage,
  sessionStorage, refresh tokens, or client-side JWT authorization are introduced.
- Expiry is checked by a timer, on focus/visibility changes, and before/after
  protected requests. Authenticated 401s clear the current session. Session changes
  abort pending requests and reject stale responses, so old data or late login
  results cannot restore a logged-out account or overwrite another login.
- The typed fetch client uses a fixed public `VITE_API_BASE_URL`, explicit bearer
  headers, omitted cookies, no-store requests, refused redirects, a 15-second
  timeout, and cancellation. It displays safe API field errors, request IDs, and
  Retry-After information. POSTs have no automatic retry; forms prevent duplicate
  in-flight submissions. Read failures have explicit refresh controls.
  Successful auth/room payloads are checked at runtime before entering session or
  UI state; malformed data produces a safe error instead of a broken page or
  invitation. Local validation failures do not imply that a write was dispatched.
- Dashboard room lists use the existing 20-item pagination contract. Create returns
  a one-time invitation display with copy and manual-copy fallback. The owner must
  save the room ID/token before navigating away; the frontend neither stores the
  token durably nor encodes it in a share URL. Join sends it only in the JSON body.
  An uncertain create displays the duplicate-room/unrecoverable-invitation warning.
- UI route guards control navigation; the API remains the authorization authority.
  Post-login destinations are restricted to known internal routes. React renders
  user-supplied names and error text as text, never raw HTML.
- Vitest/Testing Library verify state and failure behavior. Playwright verifies
  real browser workflows against an isolated API and fresh instances of the same
  three infrastructure dependencies. Test-only cost/rate configuration does not
  change production defaults. CI retains the independent full Java integration
  suite and adds frontend unit and browser checks.

### Milestone 5 local editor contract

- Successful authorized room metadata loads mount one Monaco editor/model. An
  initial denial never mounts it; logout, expiry, navigation, or a subsequent
  authorization/not-found response disposes it. Transient metadata refresh
  failures show an error while preserving the existing temporary draft.
- The room's persisted default language selects the initial Java/Python starter.
  A selector changes highlighting and the displayed `Main.java`/`main.py` label,
  preserving source and undo history. It does not update room metadata or keep
  separate per-language drafts. Successful metadata refreshes also preserve edits.
- Source stays in the mounted editor and an in-memory lifecycle ref only. Leaving
  the room, reloading, logout, and session expiry discard it explicitly. No source
  is sent to an API, stored in browser persistence, or synchronized through Redis.
- Monaco is loaded lazily with both Java/Python syntax definitions in that same
  module, so switching languages needs no additional script download. The
  definitions use Monaco's public tokenizer/configuration types. A Vite-bundled,
  same-origin editor worker provides editor features only; it does
  not execute or compile submissions. There is no CDN or language server. The
  editor, model, and change listener are disposed together. Late module loading
  cannot mount an editor after navigation. Loading/initialization errors have a
  retry control; cached module failures may require reloading and logging in again.
- Run is disabled, stdout/stderr contain empty-state text, and connection status
  says local editing only. No simulated execution state or WebSocket is created.
- Playwright serves a production build and checks actual editing, undo/redo,
  language switching, same-origin worker responses, local draft lifecycle,
  authorization, dependency/asset failures, and responsive layout. Unit tests
  cover model lifecycle, StrictMode, late loads, and metadata-refresh behavior.

### Later WebSocket authentication

Use STOMP over native WebSocket with the API's simple broker; no SockJS or
RabbitMQ STOMP relay is needed. Authenticate the STOMP CONNECT frame using an
interceptor before message authorization. Browser WebSocket handshakes do not
support arbitrary Authorization headers. Never put JWTs in URL query strings.

Authorize both SUBSCRIBE and SEND against room membership. Deny client sends to
server event destinations and deny unmatched destinations by default. Enforce
token expiry on established sessions, an authentication deadline, allowed
origins, connection/message limits, and bounded outgoing buffers for slow clients.
Do not treat REST CORS settings as WebSocket authorization. Token-only transport
and any CSRF exceptions must be deliberate and tested; do not disable protections
globally as a workaround.

Server-side authorization must verify room membership before users can:

- read protected room data;
- modify shared editor state;
- execute code;
- retrieve room execution history.

---

## 6. Room API

Initial endpoints:

    POST /api/auth/register
    POST /api/auth/login

    POST /api/rooms
    GET  /api/rooms
    GET  /api/rooms/{roomId}
    POST /api/rooms/{roomId}/join

    POST /api/rooms/{roomId}/executions
    GET  /api/rooms/{roomId}/executions

    GET  /api/executions/{executionId}

Exact DTO shapes may evolve during implementation.

Breaking API changes should be deliberate and documented.

`POST /api/rooms` creates owner membership and returns an invitation token to the
owner. `POST /api/rooms/{roomId}/join` requires the token in the request body;
knowledge of the room ID alone never grants membership. All members may edit,
select the active document language, submit executions, and read room history.
Any later capacity policy must be configurable and atomically enforced.

### Milestone 3 room contract

- All four room routes require a valid bearer token and an existing user. The
  server derives owner/member IDs from the token; request-supplied identity is
  ignored. Membership remains durable PostgreSQL state.
- `POST /api/rooms` accepts `{name, language}` and returns 201 with
  `{room, invitationToken}` and `Location: /api/rooms/{id}`. Names must be nonblank,
  valid Unicode without NUL, and at most 120 Java UTF-16 code units. Language must
  be the string `JAVA` or `PYTHON`; numeric enum ordinals are rejected.
- Room metadata is `{id, ownerId, name, language, createdAt, updatedAt}`. Ordinary
  detail/list/join responses contain no invitation or hash. Membership records
  remain in the existing table; no unbounded member roster is embedded in room
  responses. Language is the room default, not collaborative editor state.
- Creation persists room and owner membership in one transaction. A membership
  failure rolls back the room. No schema change is required.
- Invitations use 32 SecureRandom bytes encoded as 43 unpadded Base64URL
  characters. Only a lowercase SHA-256 hash of that encoded token is persisted;
  comparison uses constant-time digest comparison. Tokens are accepted only in
  JSON bodies and are redacted from DTO string representations. Responses use
  no-store caching headers. No token/hash is logged.
- `POST /api/rooms/{roomId}/join` accepts `{invitationToken}` and returns 200 room
  metadata. Every attempt, including an existing member's retry, requires a valid
  invitation. PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` makes concurrent
  repeats idempotent and preserves the original membership timestamp. There is
  no two-member cap.
- `GET /api/rooms` returns `{items, page, size, hasNext}`, filtered by the caller's
  membership and ordered by `(created_at DESC, id DESC)`. Page is zero-based;
  default size is 20, allowed sizes are 1–100. Negative/non-numeric parameters
  and offsets beyond Hibernate's integer range return 400. Offset pages can shift
  when new rooms are created; this MVP does not promise a snapshot across pages.
- `GET /api/rooms/{roomId}` checks membership before returning metadata. Missing
  rooms, inaccessible rooms, and incorrect invitations all return the same 404
  `ROOM_NOT_FOUND` error. Missing/invalid authentication returns 401; malformed
  fields, UUIDs, and pagination return safe 400 errors. Create/join bodies share
  the 4096-byte JSON limit (including chunked requests), 413/415 handling, and
  CORS/error guarantees established in Milestone 2.
- Database failures return 503 and have no automatic write retries. After an
  uncertain join, retrying with the same token is safe. A lost create response
  can leave a committed room whose invitation is unrecoverable; listing recovers
  metadata only. Retrying create may create another room. Invitations are reusable,
  have no expiry, and are returned only at creation; recovery/rotation remains
  future work. Owners must retain the token securely for later sharing.
- Room operations with an existing JWT do not use Redis or RabbitMQ. Aggregate
  readiness still includes those dependencies; new login remains Redis-dependent.
  This milestone adds no room editing/deletion, frontend UI, collaboration,
  invitation management, or execution functionality.

Execution POST receives source and language, derives the submitting user from
authentication, and returns the ID, current status, and Location. Confirmed
dispatch normally returns 202 Accepted; dispatch failures follow §9. Bound list
pagination, return execution summaries, and fetch source/output through the
authorized detail endpoint. Render output as text, never executable HTML.

Use a consistent error envelope with code, message, request ID, and field errors
when relevant. Use 401 for missing/invalid authentication, a consistent 403 or
concealed 404 for unauthorized resources, 400 for invalid input, 413 for oversized
requests, 429 with Retry-After for rate limits, and 503 for unavailable dependencies.
Dispatch errors include execution ID and current/unknown status when a row exists.

---

## 7. Collaboration Model

The MVP does NOT use CRDTs or Operational Transformation.

Use:

- WebSockets;
- debounced client updates;
- server-authoritative document versions;
- last-write-wins behavior.

Last-write-wins means full-document replacement in server acceptance order,
not client timestamp order. A replacement based on older content may overwrite
another user's edits; document versions provide ordering, not automatic merging.

Conceptual update flow:

    User types
       ↓
    Local Monaco state changes
       ↓
    Debounce
       ↓
    WebSocket update
       ↓
    Server validates user + room membership
       ↓
    Redis document state updated
       ↓
    Server broadcasts update
       ↓
    Other clients update editor

A document message should conceptually contain:

    roomId
    content
    language
    version
    generationId
    clientUpdateId

Validate membership, generation, language, UTF-8 content size, and per-connection
update sequence. Drop duplicate/reordered updates from the same connection and
reject obsolete generations. Atomically write content, language, server version,
and TTL in Redis. Acknowledge and broadcast only accepted state. Clients discard
older server versions within a generation. Reset notifications carry a new
generation; do not compare versions across generations.

Clients joining or reconnecting should receive the current server-known
document state.

Subscribe and fetch a snapshot with version ordering so updates racing the
snapshot do not regress local state. Do not blindly replay buffered offline
updates. If Redis is unavailable, retain local edits visibly but do not claim
they were saved; reconnect requires an authoritative snapshot or explicit reset.

Known limitation:

Truly simultaneous edits may overwrite each other.

This is acceptable for the MVP and should be documented.

---

## 8. Redis State

Suggested logical state:

    room:{roomId}:document

Containing:

    content
    version
    language
    updatedAt
    generationId

Redis may additionally store room presence and rate-limit counters.

Each document has a 24-hour inactivity TTL. Refresh it on accepted updates and
authenticated joins/snapshot reads; transport heartbeats alone do not refresh it.
Reconnect restores the document only while it exists. Expiration, eviction, or
Redis data loss reinitializes the room's default template/language atomically
with a new generation and an explicit DOCUMENT_RESET response/event. Clients
must not silently publish old-generation edits into that new document.

Document state is intentionally ephemeral. There is no guaranteed recovery after
Redis data loss and no durable document checkpoint in the MVP. Execution snapshots
are history, not automatic document backups. Presence is optional and deferred.

---

## 9. Execution Request Flow

REST flow:

    POST /api/rooms/{roomId}/executions
                   ↓
             authenticate
                   ↓
         verify room membership
                   ↓
             validate source
                   ↓
       create Execution(QUEUED)
                   ↓
          persist PostgreSQL
                   ↓
         publish execution.jobs with confirms + mandatory routing
                   ↓
         return 202 with execution ID/status/Location on confirmed dispatch

The HTTP request should not wait for code execution.

Source and language are the client's visible snapshot, not a later Redis read.
Validate the contract in §12 and enforce Redis-backed per-user rate limits and
a global outstanding-work admission limit before persisting. With one API
instance, serialize/check outstanding-work admission consistently; document a
future multi-instance approach before scaling. Fail closed if admission state
cannot be checked. Bound publisher-confirm waits independently of execution time.

### Dispatch failures and the approved MVP limitation

Commit PostgreSQL before publication. Use the same execution ID for bounded
publication retries; never create another execution row for a publish retry.

- On a negative confirm, unroutable return, or exhausted publish error, atomically
  change QUEUED to FAILED with DISPATCH_FAILED. Return 503 with the execution ID
  and recorded status. A delayed message then cannot claim that terminal row.
- A confirm timeout/connection loss is an uncertain outcome, not proof of
  non-delivery. Attempt the same conditional QUEUED-to-FAILED transition with
  DISPATCH_UNCONFIRMED. If a worker already claimed the job, do not overwrite
  RUNNING or a terminal result: return the authoritative status/ID (202 when
  accepted for processing) and let REST resolve its eventual outcome.
- If failure-status persistence or the status read also fails, return 503 with
  the known execution ID and an explicit unknown-outcome indicator; log/measure
  the failure. Clients must inspect that ID rather than automatically resubmit.

Every failure branch uses a conditional transition. If any branch loses the race
to a worker claim or completion, read and return the existing state instead of
claiming dispatch failed or overwriting progress. A lost HTTP response can also
hide the ID from a client; the UI should inspect recent room history and warn
about uncertain submission rather than automatically issue another POST.

A crash after PostgreSQL commit but before publish can still strand QUEUED work.
There is no background guaranteed redispatch, outbox table, or claim of atomic
delivery in the initial MVP. Document an operator procedure to inspect aged
QUEUED rows and conditionally fail abandoned work before a user resubmits.
Restarting the API alone does not guarantee recovery of such jobs. Transactional
outbox is future work, not a requirement for milestone acceptance.

---

## 10. Execution Job

Conceptual RabbitMQ message:

    {
      "schemaVersion": 1,
      "executionId": "..."
    }

The worker retrieves authoritative execution details from durable storage.

---

## 11. Worker Flow

    consume execution job
             ↓
       atomically claim QUEUED → RUNNING; set deadline/revision
             ↓
       publish committed RUNNING event
             ↓
      create workspace
             ↓
       write source file
             ↓
    start Docker container
             ↓
      compile / execute
             ↓
    capture output/status
             ↓
      persist final result
             ↓
      cleanup resources
             ↓
      publish committed terminal event
             ↓
      acknowledge job

Cleanup must occur even when execution fails.

Start with worker concurrency and prefetch of one, configurable after measurement.
Do not hold a database transaction open while a container executes. Conditional
state/revision updates prevent concurrent claims and late writes from regressing
a terminal result. A duplicate terminal job never starts another container; it
may republish the stored terminal event to help recover a missed notification.

---

## 12. Docker Execution

Java:

    Main.java
       ↓
    javac
       ↓
    java Main

Python:

    main.py
       ↓
    python main.py

Each submission receives a disposable execution environment.

Support one Main.java without a package declaration, compiled and run as Main,
or one main.py. Standard libraries only; stdin is closed. No interactive input,
multiple files, package installation, or external network. Compilers run inside
the same constrained execution boundary as submitted programs.

Mandatory controls:

- execution timeout;
- memory restriction;
- CPU restriction;
- process restriction;
- output limit;
- 64 KiB source limit measured in UTF-8 bytes;
- network disabled (`none`);
- non-root UID/GID, all capabilities dropped, no-new-privileges;
- retained seccomp restrictions and applicable host security profile;
- read-only root filesystem and size-bounded writable temporary workspace;
- trusted, pinned language images and fixed entry points/argument lists;
- no privileged containers, Docker socket, application secrets, host namespaces,
  devices, or unrelated host mounts.

User input may supply source and an allowlisted language only. Never interpolate
source into shell commands, image names, Docker options, or workspace paths.
Use trusted filenames and isolated per-execution workspaces with safe cleanup.
Keep network disabled even when a standard-library API requests connectivity.

Retain at most 64 KiB combined stdout/stderr, drain both concurrently, and kill
the container on overflow with FAILED/OUTPUT_LIMIT and output_truncated=true.
Disable or bound Docker log retention and all temporary storage. A malicious
program must not exhaust host memory or disk through capture/logging.

Use separate configurable preparation, compilation, and runtime deadlines plus
an overall deadline. The initial runtime target is five seconds. Measure and
record Java/Python memory, CPU, PID, and storage settings in Milestone 10; do not
assume a 128 MiB budget supports javac and JVM overhead. Classify compile/runtime,
OOM, output-limit, timeout, and infrastructure failures distinctly. If required
controls cannot be applied, fail the job without running source.

The worker must kill executions that exceed the configured timeout.

Label containers with execution IDs and enforce cleanup on normal completion,
exceptions, timeout, and worker restart. Include a periodic orphan/deadline
reconciliation pass. Docker CLI/API timeouts must be bounded too. Docker daemon
access belongs only to the trusted worker orchestrator, never to the API or
submission. A process split alone does not protect a shared host from escape.

---

## 13. Worker / Queue Failure Handling

RabbitMQ delivery is at least once; execution processing must be idempotent.
Atomically claim only QUEUED rows. All terminal states, including FAILED and
TIMED_OUT, are final: duplicates are acknowledged without running submitted code.
A duplicate RUNNING job must not start a parallel container. Its recovery is
owned by the current worker/deadline reconciliation, not by another claim.

Persist terminal state before acknowledging. If persistence fails, retain the
bounded result for bounded retries and do not acknowledge success prematurely.
On redelivery/restart, inspect PostgreSQL and labeled containers. Clean up an
interrupted or overdue execution before conditionally recording FAILED with an
infrastructure-interruption reason. Never automatically rerun source that already
started; the user may explicitly submit a new execution. Recovery must prevent
old workers from overwriting terminal state via conditional revision updates.

For the initial single-worker deployment, replacement startup must establish
that the previous process is stopped before reclaiming its work. Do not start
overlapping workers without extending claim ownership/fencing first.

Bound infrastructure retries with backoff. Persist retry accounting when it must
survive redelivery; do not reset retry budgets on every reconnect or use infinite
nack/requeue loops. Invalid messages must be rejected without requeue and logged.
User compile/runtime failures are terminal outcomes, not retryable broker errors.
If the database remains unavailable, pause consumption and surface unhealthy
readiness; never discard a valid job solely because its outcome cannot be saved.

Bounded event-publication failure must not erase the saved execution result or
cause a rerun. Report the notification failure as described in §14. Cleanup
failures must be logged and reconciled; do not silently report successful cleanup.

A dead-letter queue may be added later if useful, but is not required for the
initial MVP.

---

## 14. Real-Time Execution Events

After committing a state change, the API (QUEUED/dispatch failure) or worker
(RUNNING/terminal) publishes a persistent message to `execution.events` with
publisher confirms and mandatory routing. The API consumes that queue and
broadcasts to authorized room subscribers. This event path does not use an
outbox or Redis Pub/Sub.

Conceptual events:

    EXECUTION_QUEUED
    EXECUTION_RUNNING
    EXECUTION_COMPLETED
    EXECUTION_FAILED
    EXECUTION_TIMED_OUT

The frontend should not need to continuously poll for completion.

Events contain schemaVersion, executionId, roomId, status, and stateRevision;
large source/output remains in PostgreSQL and is retrieved via authorized REST.
API consumers and clients ignore duplicate/older revisions. A fast completion
may arrive before an earlier QUEUED/RUNNING notification; never regress state.
The API acknowledges after handling an event locally, not after every browser
confirms receipt. Use bounded consumer retries; an absent/disconnected subscriber
does not keep a message unacknowledged forever.

Publish only committed state. Retry publication within a bounded budget and log
and measure exhausted retries. A worker may acknowledge a job after saving its
terminal result and exhausting event retries; event failure must never rerun code.
The commit-to-event-publish crash window remains an explicit MVP limitation.
Not every state transition is guaranteed to reach a connected browser. REST
reconciliation on reconnect and an explicit Refresh Status action recover durable
state; show a delayed/unknown status hint after a bounded UI wait. Do not claim
durable notifications or exactly-once event delivery. An outbox is future work.

---

## 15. Observability

Use Spring Boot Actuator and Micrometer.

Expose Prometheus-compatible metrics.

Important measurements include:

- HTTP latency;
- HTTP request count;
- WebSocket connections;
- execution submissions;
- successful executions;
- failed executions;
- timed-out executions;
- queue wait duration;
- execution duration.

Useful structured log identifiers include:

- roomId;
- executionId;
- userId when appropriate.

Never log credentials, JWTs, or passwords.

Also exclude invitation tokens and submitted source/output by default. Keep
execution/room/user IDs in logs, not metric labels. Measure dispatch failures,
unconfirmed publications, event failures, and cleanup failures. Expose only safe
health information publicly and restrict metrics/management endpoints. Liveness
must not fail solely because PostgreSQL, Redis, or RabbitMQ is unavailable;
readiness reports the dependencies required by each process.

---

## 16. Testing Architecture

### Unit Tests

Use for:

- validation;
- services;
- status transitions;
- isolated business logic.

### Integration Tests

Use Spring Boot Test and Testcontainers.

Use real test containers when behavior depends on:

- PostgreSQL;
- Redis;
- RabbitMQ.

Important integration paths include:

    register → login
    create → join room
    submit execution → queue
    worker → final result

Required failure scenarios, added with their feature milestone:

- invalid/expired JWTs, invalid invitations, duplicate/concurrent joins;
- unauthorized REST reads, WebSocket subscriptions and sends, origin rejection,
  established-session expiry, malformed/oversized messages, and slow clients;
- concurrent document updates, snapshot races, duplicate updates, stale
  generations, TTL expiry/refresh, Redis loss/unavailability, and explicit reset;
- publisher nack/return/timeout, broker outage, failed failure-state persistence,
  and crash after commit before publish (assert the documented limitation);
- duplicate jobs before/during/after execution, worker termination, missed ack,
  database outage after execution, terminal-state protection, and orphan cleanup;
- duplicate/out-of-order events, event publish failure, API/browser reconnect,
  and REST recovery after missed notification;
- valid Java/Python, compile/runtime errors, infinite loop, memory exhaustion,
  process limits, network denial, disk exhaustion, concurrent stdout/stderr flood,
  and inability to apply required sandbox controls.

Run adversarial sandbox cases in an isolated execution test environment. Unit
tests use a fake runner only in tests; no fake or host-process execution fallback
ships as runtime behavior. Missing Docker fails required integration checks.
Basic CI starts with foundation checks in Milestone 0 and grows with each feature.

---

## 17. Local Development

Docker Compose should provide infrastructure dependencies.

Expected infrastructure:

- PostgreSQL
- Redis
- RabbitMQ

Backend/frontend/worker may initially run locally during development.

Milestone 0 uses Java 21, a pinned Maven 3.9.x wrapper, a pinned Spring Boot 3.5.x
release consistent with JUnit 5, and Node 24 with npm lockfiles. Pin compatible
dependency/image versions when scaffolding; do not use floating latest tags.
The root Maven project aggregates backend and execution-worker builds.

Compose infrastructure ports and local application/management ports bind to
loopback. Credentials come from an ignored local environment file or process
environment; checked-in examples contain no passwords. Compose's environment
file does not automatically populate locally launched JVMs; document and script
that handoff. The API and worker expose health on separate ports (8080/8081);
the worker has no public product endpoints. Frontend development uses port 5173.

Eventually:

    docker compose up

should provide a convenient development environment where practical.

---

## 18. Deployment

Prefer simple AWS deployment over architectural complexity.

Use the topology in §1 with HTTPS and one API plus one worker. Place the
execution worker and its Docker engine on a dedicated host/VM separate from
application infrastructure for the deployed demo. Restrict worker database and
broker credentials, expose no Docker API publicly, and pass no cloud/application
credentials into submission containers. Restrict execution to invited testers;
room invitations alone are not the deployment's execution-access gate.

Exact managed services should be chosen based on cost and practical
availability.

Managed PostgreSQL, Redis, and RabbitMQ are optional, not required additions.
Functional local MVP and deployed portfolio readiness are distinct thresholds.
Document host trust, remaining sandbox risks, costs, and recovery procedures;
do not claim hostile-workload safety from Docker alone.

Do not add Kubernetes solely for portfolio value.

---

## 19. Architectural Tradeoffs

### Why Spring Boot?

The project is intentionally designed to demonstrate production-style Java
backend engineering.

### Why PostgreSQL?

Users, rooms, membership, and execution history are durable relational data.

### Why Redis?

Editor state is frequently changing and ephemeral.

### Why RabbitMQ?

Execution jobs are asynchronous queue-based workloads requiring worker
decoupling, acknowledgements, and retry behavior.

Kafka would add unnecessary complexity for this use case.

### Why a separate worker?

User code can consume resources, fail, hang, or be malicious. It should not
share the API process.

### Why Docker?

Docker provides useful resource/process isolation for a portfolio environment.

It is not claimed to provide perfect hostile-workload security.

### Why no CRDT?

CRDT collaboration would significantly expand scope without improving the
main backend-engineering objectives.

### Why no microservices?

Current scale and domain complexity do not justify their operational cost.

### Why no transactional outbox in the initial MVP?

The approved three-week scope uses PostgreSQL persistence followed by confirmed
RabbitMQ publication with explicit failure handling. This leaves job and event
dual-write crash windows. At-least-once messaging and idempotent processing do
not eliminate those windows or imply exactly-once code execution. A future
transactional outbox can close the durable dispatch gap without replacing the
chosen architecture.

---

## 20. Future Architecture Options

Only consider after MVP completion:

- CRDT collaboration;
- refresh tokens;
- OAuth;
- persistent document checkpoints;
- dead-letter queue;
- transactional outbox;
- OpenTelemetry;
- horizontal WebSocket scaling;
- stronger sandboxing using microVMs;
- Terraform.
