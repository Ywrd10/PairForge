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
  worker neither migrates nor imports the API module. Since Milestone 9,
  re-provisioning after API migrations grants SELECT on executions and UPDATE
  only on status/result/timing/revision columns. Snapshot, identity, room, and
  creation fields remain immutable to the worker; INSERT/DELETE, account and
  membership access, DDL, and temporary tables remain denied.

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
  The local profile tracks the configured frontend port. Milestone 6 applies the
  same explicit origin allowlist to WebSocket handshakes, with separate message
  authentication and authorization below.
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

This describes the completed Milestone 5 baseline. Milestone 6 replaces its
local-only source lifecycle and connection status with the shared document
contract in §7; Monaco loading, language assets, disposal, and disabled execution
remain unchanged.

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

### Milestone 6 WebSocket authentication

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

The implemented `/ws` upgrade permits unauthenticated HTTP GET only so a native
browser can connect; it grants no room access. Missing/disallowed Origin or any
query string is rejected. CONNECT requires exactly one `Authorization: Bearer
<JWT>` native header, the existing JWT validator, and an existing PostgreSQL user.
The socket must authenticate within five seconds. Connections close at JWT expiry
even while idle, and message processing checks expiry again. Outbound events are
checked both before queueing and immediately before socket delivery, since a
queued event can outlive its token. There is no cookie
authentication, SockJS endpoint, STOMP CSRF token, or broker relay; the existing
stateless REST CSRF policy is unchanged. Origin checks supplement bearer
authentication rather than replace it. STOMP client debug logging and the
framework parser's payload-bearing logger are disabled; safe exception
classifications are logged instead. Do not enable framework message/payload
debug logging with real credentials or source.

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

### Milestone 6 wire contract and bounds

- One native STOMP connection serves one room. Before sending document requests,
  subscribe to `/user/queue/collaboration` for session-specific replies and
  `/topic/rooms/{roomId}/document` for accepted room changes. Milestone 11 permits
  a third subscription, `/topic/rooms/{roomId}/executions`, for the same authorized
  room. No duplicate or other subscriptions are allowed. SEND is restricted to
  `/app/rooms/{roomId}/snapshot` and `/app/rooms/{roomId}/update`; both require the
  two collaboration subscriptions and a fresh
  PostgreSQL membership check. Client identity comes solely from CONNECT.
- The snapshot request is `{}`. An update contains `generationId`, positive
  connection-local `sequence`, UUID `clientUpdateId`, `content`, and `language`
  (`JAVA` or `PYTHON`). Room identity comes from the destination. The source must
  be valid UTF-8 without NUL and at most 65,536 bytes. Unknown languages, malformed
  bodies, duplicate/reordered sequences, and obsolete generations cannot mutate
  the document. Sequence numbers provide connection-local duplicate protection;
  update IDs correlate acknowledgements and are not a durable deduplication store.
  The STOMP JSON converter rejects scalar type coercion (for example numeric
  content or a fractional/string sequence) and numeric enum values. It is separate
  from REST's mapper and does not alter existing REST contracts.
- Replies/events contain `type`, `roomId`, `document`, `clientUpdateId`, and `code`.
  A document contains `content`, `language`, `version`, and `generationId`.
  Types are `SNAPSHOT`, `UPDATED`, `DOCUMENT_RESET`, `REJECTED`, and `ERROR`.
  Successful writes broadcast `UPDATED` and reply to the originating session;
  clients tolerate the duplicate. Initialization/reset is explicit, including a
  `DOCUMENT_RESET` code on a newly initialized snapshot. Errors return safe codes
  and no document; protocol/authorization/abuse failures close the connection.
- A Redis Lua operation atomically initializes or replaces document fields,
  increments the version, and refreshes TTL. Missing/obsolete generations do not
  apply source. An authorized reset/snapshot initializes the room's default
  template if needed. Heartbeats do not touch TTL. The API serializes each room's
  commit and publication; this remains a single-API-instance implementation.
- The browser debounces for 300 ms, with one update in flight and one coalesced
  local draft. It orders snapshots/events by generation and version and does not
  echo remote changes. Remote full-document replacements clear Monaco undo
  history so undo cannot silently restore another participant's previous source;
  acknowledgements of unchanged local text preserve local undo. A newer accepted
  remote replacement can discard pending local edits with a visible warning.
- Initial snapshot and update acknowledgement deadlines are ten seconds. A Redis
  timeout or lost acknowledgement has an uncertain outcome; no write is retried.
  Dependency-error responses also display an uncertain outcome, since a failed
  notification can follow an already committed write. Initial subscription or
  snapshot-send failures are caught and displayed without uncaught browser errors.
  The UI retains local text visibly on disconnect/failure. Copy it before leaving;
  reopening loads current Redis state and discards unsynchronized edits. There is
  no automatic reconnect or offline replay in Milestone 6. Essential initial
  snapshots, generation resets, and TTL failure tests are prerequisites here;
  Milestone 7 adds the manual recovery workflow described below.
- Redis commit and WebSocket publication are not atomic. API failure between
  them can leave an accepted edit without notification. Redis state is recovered
  on a fresh snapshot while it exists; a broadcast failure does not roll back an
  accepted write or imply that retry is safe. This ephemeral notification gap is
  separate from the documented execution job/event dual-write limitations.

Configuration uses `pairforge.collaboration.*` (Spring environment binding is
also supported). Defaults:

| Property | Default |
| --- | --- |
| `ttl-seconds` | 86400 (24 hours of inactivity) |
| `source-bytes` | 65536 (may be lowered, never raised above the MVP limit) |
| `message-bytes` | 400000 (STOMP/JSON overhead, including escaped source) |
| `messages-per-second` | 10 per connection; raw WebSocket frames limited to 10× this |
| `max-connections` | 100 per API process |
| `max-connections-per-ip` | 10 using the direct peer IP, not forwarded headers |
| `max-connections-per-user` | 5 |
| `connect-timeout-ms` | 5000 |
| `send-timeout-ms` | 5000 |
| `send-buffer-bytes` | 1048576 |

Inbound and outbound channels each have one FIFO executor with a 64-task queue.
They bind explicitly to separate executor beans; runtime integration tests verify
that Boot auto-configuration cannot silently substitute the heartbeat scheduler.
Configurer ordering also places strict STOMP JSON conversion before Boot's default
converter. The expiry task explicitly uses the collaboration scheduler.
This preserves ordering with bounded memory instead of unbounded per-session
ordering queues. Overload rejects work, and transport writes/buffers are bounded;
the browser exposes missing acknowledgements as uncertain failures. A slow client
can delay other rooms until the five-second send timeout: this is a deliberate
small-MVP throughput tradeoff, not a horizontal-scaling claim. Heartbeats run
every ten seconds and native sockets have a 30-second idle timeout. Limits are
per process, not distributed quotas or a two-member room restriction.

---

### Milestone 7 manual recovery

- Reconnect is an explicit action, including after initial connection failure.
  Each attempt uses fresh session headers, creates a new STOMP connection,
  reauthorizes subscriptions/sends, and requests an authoritative snapshot.
  Only one attempt is active. Old connections, callbacks, debounce timers, and
  in-flight acknowledgements cannot mutate a later connection's state.
- The editor and language selector are read-only while connecting/synchronizing.
  Updates received before the snapshot are buffered and ordered by generation
  and version. Only a snapshot completes synchronization. A snapshot cannot
  regress a known version within the same generation, even across reconnects.
  Reset events retire old generations. A changed generation is visibly reported
  even if another participant initialized the replacement document first.
- Before recovery replaces pending/uncertain edits, preserve one memory-only
  backup containing their source and language. Copy and discard are explicit UI
  actions; clipboard failure leaves selectable text available. Another recovery
  with pending edits replaces this single backup. Navigation, reload, logout,
  and session expiry discard the page and backup. There is no browser storage,
  durable checkpoint, backup history, merge, or automatic replay.
- Failed recovery retains the visible local text and backup, and permits another
  explicit attempt. After a successful snapshot only a deliberate new edit can
  publish, using the new connection's sequence starting at one. Initial/recovery
  snapshot and update-acknowledgement deadlines remain ten seconds; a reset event
  alone does not satisfy the snapshot deadline. No automatic retry is enabled.
- Redis TTL, atomic storage operations, security checks, wire schema, and the
  single-API broker remain as implemented in Milestone 6. Recovery adds no REST
  endpoint, PostgreSQL table, worker behavior, or execution feature.

## 8. Redis State

Implemented Redis hash key and fields:

    room:{roomId}:document

Containing:

    content
    version
    language
    generation (exposed as generationId in messages)

The hash TTL provides inactivity tracking; no separate updatedAt field is needed
for this milestone.

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

### Milestone 8 submission contract

`POST /api/rooms/{roomId}/executions` accepts exactly
`{"source":"...","language":"JAVA"}` (or `PYTHON`). Identity comes from the JWT;
room membership is checked before admission and again in the insert transaction.
Unknown fields, non-string values, NUL, and malformed Unicode are rejected.
Source is limited to 65,536 UTF-8 bytes, independently of the 400,000-byte JSON
transport limit (which permits JSON escaping); both fixed-length and chunked
bodies are bounded. Other authentication/room JSON limits remain 4,096 bytes.
Empty source and syntax/entry-point errors are handled by the worker sandbox.
The API never compiles or interprets source. Filenames are determined by language,
not accepted from clients. No Redis document read changes the submitted snapshot.

Confirmed dispatch returns 202 with `executionId`, `status`, `stateRevision`,
`failureReason`, and a `Location: /api/executions/{executionId}` header. A durable
dispatch failure returns 503 with the standard error fields plus `executionId`,
`status`, `failureReason`, and `outcomeUnknown: false`. Persistence/read uncertainty
returns 503 with code `EXECUTION_OUTCOME_UNKNOWN`, the attempted ID, null status
and reason, and `outcomeUnknown: true`. An insert/commit failure can mean that ID
has no row; inspect it and recent history after recovery. The API never asserts
that an unconfirmed message was not delivered. Repeating the HTTP POST creates a
new logical execution; only internal publication retries reuse the original ID.

`GET /api/rooms/{roomId}/executions?page=0&size=20` returns
`{items,page,size,hasNext}`, ordered by `createdAt DESC, id DESC`; size is 1–100.
Summaries contain IDs, language, status, revision, failure reason, and creation/
completion times, but no source or output. `GET /api/executions/{executionId}`
returns the durable snapshot and result fields. Both require current membership;
missing and inaccessible executions return the same 404. Responses are no-store.

Admission uses an atomic Redis fixed window per user, beginning with the first
valid authorized attempt. Rejections do not extend its TTL. Authorized attempts
are charged before global admission and are not refunded for capacity or dispatch
failures. Redis loss fails closed. A bounded in-process lock covers PostgreSQL
outstanding count, insert, and commit, never broker I/O. QUEUED and RUNNING rows
count toward capacity. This is deliberately a **single API instance** design;
multiple instances require a shared admission transaction/lock before scaling.

Configuration under `pairforge.execution` (Spring environment overrides supported):

| Property | Default | Purpose |
| --- | --- | --- |
| `user-limit` | 10 | Valid authorized attempts per user/window |
| `window-seconds` | 60 | Redis fixed-window lifetime |
| `max-outstanding` | 100 | Global QUEUED + RUNNING rows |
| `admission-wait-ms` | 100 | Maximum wait for local admission lock |
| `source-bytes` | 65536 | Source UTF-8 budget; cannot exceed schema bound |
| `publish-attempts` | 2 | Same-ID publish attempts, maximum 3 |
| `confirm-timeout-ms` | 2000 | Confirm wait per attempt, maximum 10000 |
| `retry-backoff-ms` | 100 | Pause between attempts, maximum 1000 |

The API declares a durable direct exchange `pairforge.execution`, durable queue
`execution.jobs`, and binding with routing key `execution.jobs`. Messages are
persistent, have content type `application/json`, and contain only schema version
and execution ID. The stable AMQP message ID is that execution ID; each publish
attempt has a separate confirm correlation ID. Positive confirmation succeeds
only when no mandatory return accompanied it. Connection/handshake, channel
checkout, NIO write enqueue, and confirm waits are bounded; earlier uncertainty
survives a later failed retry. Failure counters use bounded reason labels, and
recovery logs contain IDs rather than source or credentials. An API RabbitAdmin
declares/redeclares topology on connection. The Milestone 9 consumer uses this
existing topology and never declares it; runtime consumption is disabled until M10.

A broker `basic.nack` has no reason string in the correlated confirm. Spring's
locally generated negative confirmations include a reason (for example after
channel shutdown); without a mandatory return these are classified as
DISPATCH_UNCONFIRMED, because the message may already have reached the queue.

### Operator procedure for abandoned QUEUED submissions

1. Restore PostgreSQL/RabbitMQ connectivity and inspect age, recent dispatch logs,
   and queue/consumer health. Age alone is not proof that publication failed; a
   legitimately queued backlog is possible. Through Milestone 9 runtime consumption
   is disabled, so successfully submitted work normally remains QUEUED.
2. Select specific IDs for investigation, without dumping submitted source:

   ```sql
   SELECT id, room_id, status, created_at, state_revision
   FROM executions
   WHERE status = 'QUEUED' AND created_at < now() - interval '15 minutes'
   ORDER BY created_at;
   ```

3. For an individually confirmed abandoned ID, use the operator database role
   (not a public API) and a conditional update. Replace the placeholder UUID:

   ```sql
   UPDATE executions
   SET status = 'FAILED', failure_reason = 'DISPATCH_FAILED',
       completed_at = greatest(clock_timestamp(), created_at),
       state_revision = state_revision + 1
   WHERE id = '<reviewed-execution-uuid>'::uuid AND status = 'QUEUED'
   RETURNING id, status, state_revision;
   ```

4. If zero rows changed, re-read the current status: a claim/completion may have
   won the race. Do not overwrite it. Only after verifying the old execution's
   durable terminal state should the user deliberately submit a new execution.
   Delayed/duplicate deliveries must skip terminal rows in the Milestone 9 worker.
   Do not mass-fail work solely by age or blindly republish/repeat POST requests.

Tests simulate interruption immediately after commit, verify the stranded row and
absent message, and exercise this conditional update. This demonstrates the gap;
it does not provide automatic recovery or claim a real process-kill test. Outbox,
guaranteed redispatch, worker consumption, and execution events are outside M8.

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
      cleanup resources
             ↓
      persist final result
             ↓
      publish committed terminal event
             ↓
      acknowledge job

Cleanup must occur even when execution fails.

### Milestone 9 worker contract

The flow above is the complete target. M9 implements consumption, claims, the
runner interface, persistence, and recovery only. Fake runners live exclusively
in test sources and never interpret submissions. M10 now supplies the explicitly
enabled Docker runner described in §12; the default consumer remains disabled.
No Docker socket reaches the API/submission containers, and no execution events
are published until M11.

`pairforge.worker` configuration (Spring property/environment overrides apply):

| Property | Default | Meaning |
| --- | --- | --- |
| `enabled` | false | Consumption opt-in; startup fails without an ExecutionRunner |
| `previous-worker-stopped` | false | Explicit operator attestation required before enabled startup recovery |
| `deadline-ms` | 30000 | Overall claim-to-run deadline, 100–300000 ms; also caps M10 phase budgets |
| `cleanup-timeout-ms` | 2000 | Bound each stop call and wait for runner exit, 100–10000 ms; sandbox profile requires 10000 |
| `retry-backoff-ms` | 100 | Backoff base, 0–1000 ms; waits of base and twice base |

Consumption and prefetch are fixed at one. Messages must be JSON of at most
1024 bytes containing exactly schemaVersion 1 and a canonical execution UUID.
Malformed messages and absent execution IDs are rejected without requeue and
logged without their payload. Terminal or already-RUNNING duplicates are
acknowledged without invoking the runner.

A single JDBC UPDATE claims only QUEUED and returns its immutable snapshot,
deadline, and incremented revision. It commits before the runner starts. Final
writes require both RUNNING and the claimed revision. No database transaction
spans execution. `ExecutionRunner.run` must return a bounded result only after
its activity and resources are cleaned up; `stop(id)` must idempotently stop and
clean active or predecessor resources, or throw if cleanup cannot be confirmed.
Timeout/runner failure invokes bounded stop and verifies the runner has exited
before recording TIMED_OUT or FAILED/INFRASTRUCTURE_INTERRUPTION. Result text
must be valid PostgreSQL-compatible Unicode and fit 64 KiB combined UTF-8.
The runner wait uses the earlier of the durable deadline and a local monotonic
budget beginning at the first claim attempt. Clock skew cannot extend the
configured budget, and claim/retry latency consumes it; an expired budget stops
and cleans the claimed job without starting the runner.

Database operations have at most three attempts. The bounded result is retained
through final-write retries; the runner is never called again to retry persistence.
An uncertain claim that no longer matches QUEUED pauses consumption rather than
acknowledging a potentially stranded RUNNING row. A final-write retry that finds
a terminal row preserves it, including when the first commit response was lost.
Only a durable terminal result or an existing RUNNING/terminal duplicate permits
acknowledgement. A crash after commit and before ack redelivers safely.

Exhausted database retries, cleanup failure, or a broker/consumer failure latch
consumption off and make worker readiness DOWN; liveness stays independent.
The channel closes, returning any valid unacknowledged delivery to RabbitMQ.
Reconnects and restored database health do not resume consumption. There is no
nack/requeue retry loop, retry table, or automatic process-restart policy. Retry
budgets therefore cannot reset through automatic redelivery; another operator
restart is a deliberate recovery attempt. An unsaved result can be lost on
restart. Durable terminal state is preserved; remaining interrupted work becomes
FAILED after verified cleanup rather than being rerun.

Operator recovery for an enabled worker (test harness in M9; runtime from M10):

1. Stop the old worker and independently verify its process has exited. Never
   overlap workers or use automatic restarts to bypass an unresolved failure.
2. Restore database/broker access, inspect the safe reason/type logs, and resolve
   any cleanup failure. Start the API to apply migrations and declare topology;
   rerun `scripts/provision-worker.ps1` afterward for execution-table grants.
3. Start one replacement with `previous-worker-stopped=true` only after step 1.
   This flag is operator attestation, **not fencing or proof of process death**.
   Before consuming, startup calls stop(id) for every leftover RUNNING row and
   conditionally records INFRASTRUCTURE_INTERRUPTION. More than 100 leftover rows
   fails closed for investigation. Failed cleanup/persistence leaves readiness DOWN.
4. Verify readiness and durable states. Do not reset RUNNING/terminal rows to
   QUEUED. Explicit new user submissions are separate executions. Continue using
   §9's manual procedure for abandoned QUEUED jobs; the dual-write gap is unchanged.

M9 enforces active deadlines and startup interruption recovery. M10 adds actual
container cleanup plus periodic labelled-resource/deadline reconciliation; M11
adds committed events. No host-process execution fallback is permitted.

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

### Milestone 10 implementation and configuration

The trusted worker invokes only the Docker CLI through Java `ProcessBuilder`
argument lists, with bounded waits and concurrent output drains. It never runs
an interpreter/compiler on the host and has no fallback. Build the two images
from `infra/sandbox/` using `scripts/prepare-sandbox.ps1`; bases are pinned by
digest. The script resolves immutable local image IDs for worker configuration.
Jobs use `--pull=never`; missing images or unavailable required controls fail
closed. Image updates require rebuilding, retesting, and restarting the worker.

Opt in with the `sandbox` Spring profile plus
`pairforge.worker.previous-worker-stopped=true`. This is an operator attestation,
not fencing. Run exactly one worker and retain the same Docker engine, sandbox
namespace, and workspace root across restarts. The workspace root must be local,
worker-owned, canonical (no symlink ancestors), and contain no comma. Namespace
is a short lowercase identifier; it scopes labels and cleanup, not security.

Each job stages exactly one UTF-8 source file under a generated UUID directory.
Only that file is bind-mounted read-only. A non-root `sleep` process holds the
container while fixed `docker exec` commands compile into `/work` and execute
`Main`, or execute isolated Python with `-I -S -B`. No `-i`/TTY is used; stdin is
closed. Java disables annotation processing, uses a bounded compiler heap, and
uses Serial GC for predictable small-container overhead. Python's image removes
pip/site packages. Compilation artifacts stay inside tmpfs. Source is never
interpolated into command arguments, shell scripts, image names, or paths.

Before any source command, inspect container configuration and verify actual
cgroup memory/swap/PID/CPU limits, UID, effective capabilities, no-new-privileges,
and seccomp inside the container. Startup preflight exercises both images using
empty source files and fixed compiler/interpreter version commands, so a wrong
language image cannot admit work. The root filesystem is read-only; `/work` and `/tmp` are
bounded noexec/nosuid/nodev tmpfs, and `/dev/shm` is separately bounded. Network is
`none`; no host namespace, extra device, credentials, or Docker socket is passed.
Host Docker proxy variables are explicitly cleared and checked so automatic
client proxy configuration cannot leak credentials into submissions. On POSIX
hosts, staging directories are private and source is readable through only the
individual file bind mount; Windows uses the worker-owned directory's ACLs.
Container logs are disabled; stdout and stderr share one 64 KiB budget across
compile/run phases. Invalid UTF-8, incomplete trailing characters, and NUL bytes
are discarded to produce PostgreSQL-safe text without expanding that budget.
When a deadline or output limit deliberately kills the Docker CLI, pipe-read I/O
errors caused by termination do not replace that limit outcome. This handling is
allowed only after the worker initiated the kill and confirmed CLI exit; other
reader errors and drain timeouts still fail closed. CLI exit does not prove
container cleanup: the runner must still remove and verify its owned resources.

`pairforge.sandbox` properties use Spring environment naming: uppercase, dots
become underscores, and hyphens are removed (for example,
`PAIRFORGE_SANDBOX_JAVAMEMORYMIB`). The `sandbox` profile additionally provides the
explicit friendly aliases `PAIRFORGE_SANDBOX_JAVA_IMAGE`,
`PAIRFORGE_SANDBOX_PYTHON_IMAGE`, `PAIRFORGE_SANDBOX_WORKSPACE_ROOT`, and
`PAIRFORGE_WORKER_PREVIOUS_WORKER_STOPPED` used by the README startup commands.

| Property | Default | Supported range / purpose |
| --- | --- | --- |
| `enabled` | false | Explicit opt-in; `sandbox` profile sets true and enables consumption |
| `docker-executable` | docker | Trusted local CLI path; never user input |
| `java-image`, `python-image` | required when enabled | Trusted immutable digest/local `sha256:` image ID |
| `namespace` | pairforge | `[a-z][a-z0-9-]{0,31}`; stable across restarts |
| `workspace-root` | .tmp/execution-workspaces | Stable local host staging root; namespace subdirectory underneath |
| `java-memory-mib`, `python-memory-mib` | 512, 128 | 128–1024 / 32–512; memory-swap equals memory, disabling swap |
| `java-pids`, `python-pids` | 128, 32 | 32–256 / 8–128, including container helper processes |
| `cpus` | 1.0 | 0.1–2 CPU quota |
| `workspace-mib`, `temp-mib`, `shm-mib` | 32, 8, 4 | 1–64 / 1–16 / 1–16; charged to container memory |
| `source-bytes`, `output-bytes` | 65536 each | 1–65536; output is combined across both streams/phases |
| `command-timeout-ms` | 1500 | 100–3000 per Docker control request; cleanup has a separate 10000 ms worker budget |
| `preparation-ms`, `compilation-ms` | 10000 each | 1000–15000 / 100–15000; compilation applies to Java |
| `runtime-ms` | 5000 | 100–10000; preparation/compilation/runtime also obey overall deadline |
| `reconciliation-ms` | 2000 | 500–10000 between maintenance passes |

`MEMORY_LIMIT` uses Docker/cgroup OOM evidence, never source output. Ordinary
compile/nonzero program failures remain `COMPILATION_ERROR`/`RUNTIME_ERROR`.
JVM allocation errors without a kernel OOM kill are language/runtime failures;
the worker does not trust an application-selected exit code as proof of OOM.
Output overflow yields `FAILED/OUTPUT_LIMIT` with truncation flagged; phase or
overall deadline yields `TIMED_OUT`. Docker/control failures require verified
cleanup before the existing processor can persist `INFRASTRUCTURE_INTERRUPTION`.
Failure to verify cleanup leaves the job unacknowledged and latches readiness
DOWN; it never licenses further work or host execution.

Containers carry namespace, execution-ID, and durable-deadline labels. `stop` is
idempotent and checks ownership before removal; only fixed source filenames in
canonical UUID directories are deleted. Startup removes predecessor resources
before recovery writes. Periodic reconciliation removes owned orphans and stops
active work past its monotonic deadline, independently of a blocked exec wait.
It keeps running after consumption is paused, allowing cleanup when Docker
recovers; operator restart is still required to resume consumption. Unexpected
workspace entries, ownership mismatches, or more than 100 tracked resources
fail closed for operator inspection. Cleanup and terminal persistence are ordered
as in M9; terminal duplicates and stale writes cannot rerun or replace results.
Graceful shutdown stops consumption, interrupts and joins the run thread, then
performs bounded final reconciliation before dependencies close. Both the join
and reconciliation have the worker cleanup timeout; a terminated host thread is
not treated as proof of container cleanup. Failure remains explicit and requires
restart recovery, without acknowledging unfinished work.

After worker termination or Docker loss, an orphan can survive until Docker and
the worker return: Docker does not supply an independent wall-clock TTL. Bounded
CPU/memory/PIDs/storage and disabled network still apply. Confirm the predecessor
is dead, restore Docker, keep the same root/namespace, and restart; interrupted
RUNNING rows become FAILED only after cleanup. Do not manually requeue/re-execute
started work. Unknown files/labels require inspection, not recursive deletion or
a global Docker prune. A dedicated execution host/VM remains required for deployed
use (§18); local controlled tests do not establish hostile production isolation.

Local measurement (2026-09-21): Docker Desktop Linux Engine 29.4.3, WSL2 kernel
6.6.114.1, 12 visible CPUs and 8,286,998,528 bytes engine memory. Pinned images
contain Temurin 21.0.12+8 and Python 3.13.15; the host build uses JDK 21.0.9.
With warm images, one standard-library Java fixture recorded 45,080,576 bytes
of cgroup peak memory (including compilation) and 2,720 ms runner duration;
Python recorded 5,664,768 bytes and 1,612 ms. Duration includes preparation and
result inspection, excludes queue wait and final cleanup. These are controlled
single-job samples, not throughput/production benchmarks or proposed lower memory
limits. Exhaustion fixtures separately verify total container memory enforcement;
a half-CPU fixture proves actual throttling. Tests emit fresh measurements on
each run, and every sandbox test verifies cleanup.

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

### Milestone 12 recovery checklist and retry audit

1. Record execution IDs, durable status/revision, dependency health, worker
   readiness, and the safe failure reason. Do not dump submitted source/output or
   credentials into diagnostics. Stop new test submissions while investigating.
2. Restore PostgreSQL, Redis, RabbitMQ, and Docker access as applicable. An API
   event listener reconnects automatically; an enabled worker with an exhausted
   retry/cleanup/broker failure remains latched DOWN. Liveness UP is not permission
   to submit more work or evidence that cleanup succeeded.
3. Stop the old worker and independently confirm its process exited. Restore
   Docker before cleanup/recovery; preserve the same engine, namespace, and
   workspace root. Inspect unexpected files/labels rather than deleting broadly.
   Start the API first for schema/topology ownership and provision the restricted
   worker role if needed. Never overlap replacement workers.
4. Start one replacement with the documented predecessor-stop attestation.
   Startup must clean owned resources before conditionally failing interrupted
   RUNNING rows. A cleanup/persistence failure keeps consumption off; do not clear
   the latch through automatic restarts. An unsaved result may be lost, but source
   that started is never automatically rerun. Preserve any terminal commit whose
   response was lost; a timeout does not prove a transaction rolled back.
5. Verify readiness, preserved terminal rows, the queue's consumer count, and
   owned-resource cleanup. Investigate aged QUEUED IDs using §9's conditional
   operator procedure; age alone does not establish abandonment. Never reset
   started/terminal rows to QUEUED, blindly republish, or repeat a POST to recover.
6. Use authorized REST detail/history, Refresh Status, or browser reconnect to
   recover missed notifications. Only a deliberate user action creates a new
   execution after the previous outcome is understood.

Worker shutdown also drains any failure-triggered consumer stop before continuing
with listener/processor teardown (at most ten seconds, with an explicit failure
if that wait is interrupted or exhausted). Failure callbacks cannot enqueue a
new stop after shutdown begins. This prevents an in-progress channel close from
being interrupted and racing destruction of Spring's RabbitMQ connection factory.

Retry boundaries remain unchanged:

| Operation | Budget and exhaustion behavior |
| --- | --- |
| API job publication | Default 2 attempts, maximum 3, same execution ID; conditional dispatch-failure persistence or explicit unknown outcome (§9). |
| Worker state read, claim, result persistence | At most 3 attempts per operation; final-result retries retain the bounded result, never rerun source. Exhaustion closes the job channel, returns unacknowledged delivery, and latches consumption off. |
| Event publication | Default 2 attempts, maximum 3; saved state remains authoritative when notification fails (§14). |
| API event consumption | At most 3 local attempts; reject exhausted notifications without requeue and recover through REST. Browser receipt is not an acknowledgement condition. |
| Sandbox controls/cleanup | Existing phase, overall, and cleanup deadlines apply; inability to verify cleanup cannot become a successful terminal write. Periodic reconciliation may clean resources after Docker returns but cannot resume a latched consumer. |

No persistent retry ledger is required under the current stop-on-failure policy:
dependency recovery and broker redelivery cannot automatically start a fresh
worker retry budget. An operator restart is a new, explicit recovery attempt;
terminal rows are skipped and interrupted rows are cleaned/failed. This is not
a lifetime bound across unlimited operator restarts. Automatic restarts, multiple
workers, or automatic redispatch would require a new ownership/retry design.

Admission remains fail-closed during dependency outages. QUEUED/RUNNING rows
continue to count against the PostgreSQL global limit while the worker is paused.
Redis quota counters are ephemeral: an outage with retained data preserves the
window, but Redis data loss can reset it. The durable outstanding limit still
applies. The commit-to-job/event-publish gaps, single API/worker constraints, and
Docker's lack of an independent wall-clock container TTL remain MVP limitations.

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

### Milestone 11 event and browser contract

- The API owns durable `execution.events` topology on `pairforge.execution` with
  routing key `execution.events`, alongside the existing jobs queue. Both processes
  publish JSON with exactly `schemaVersion: 1`, `executionId`, `roomId`, `status`,
  and `stateRevision`. The status identifies the conceptual event above; there is
  no separate type field. No source, output, identity token, or invitation travels
  in the notification. Messages are persistent, with a stable message ID of
  `executionId:stateRevision` and a fresh confirm correlation ID on each attempt.
- API publication follows the QUEUED commit and precedes job dispatch. Dispatch
  failure publication uses the committed authoritative state, including a worker
  transition that won the race. Worker publication follows RUNNING, final-result,
  and restart-recovery commits. Reading/publishing a notification cannot roll back
  a result or rerun source. RUNNING publication time consumes the existing overall
  execution deadline. Job acknowledgement follows persistence and bounded event
  attempts; a lost broker channel still follows the existing worker failure latch.
- `pairforge.execution-events.attempts` defaults to 2 (1–3),
  `confirm-timeout-ms` to 500 (50–2000), and `retry-backoff-ms` to 100 (0–500),
  independently in each process. Connection, handshake, channel checkout, and
  write enqueue are also bounded. Exhaustion logs only safe identifiers and
  increments `pairforge.execution.events.publish.failures`.
- The API consumes with one consumer, prefetch 1, and manual acknowledgements.
  It rejects malformed/oversized (>1024 bytes), duplicate-key, trailing, and
  unsupported-schema envelopes. A metadata-only PostgreSQL read verifies room,
  revision, and status before broadcasting the current committed revision; older
  intermediate events may be coalesced. A 1024-entry revision cache suppresses
  duplicates; eviction/restart can rebroadcast the current state without making
  it older. This is not durable deduplication.
- Local database/broadcast failures receive at most three attempts, with 100/200
  ms backoff, then rejection without requeue and a bounded-reason
  `pairforge.execution.events.consume.failures` counter. Invalid/mismatched events
  cannot direct a broadcast to another room. A process/channel crash may redeliver
  an unacknowledged event. No browser acknowledgement or absent subscriber holds a
  broker delivery open. Consumer connection recovery is automatic; API readiness
  includes the event listener, while liveness remains independent.
- The browser reuses its one authenticated STOMP connection and subscribes to
  execution notifications before requesting the document snapshot. Its ready
  callback reconciles execution REST state on initial connection and manual
  reconnect. Existing membership, origin, expiry, and transport controls apply;
  client SEND to execution topics is prohibited.
- Run requires a loaded editor and ready connection. It sends one POST with the
  visible source and language, without waiting for collaboration debounce. Only
  an in-flight POST disables additional submissions; subsequent deliberate runs
  are new executions subject to existing server limits. HTTP uncertainty preserves
  a validated execution ID when present and warns the user to inspect status or
  recent executions. There is no automatic POST retry.
- The UI retains at most 20 recent summaries, one selected detail, a 128-entry
  event-revision cache, and one coalesced refresh request. Events trigger REST
  reads after a 100 ms coalescing window. Monotonic revisions and terminal-state
  protection prevent stale responses from regressing results. Selection remains
  stable when other executions finish; submitting a new run selects its receipt
  unless the user changed selection meanwhile. Room/session disposal cancels
  requests and ignores late responses. Output renders as text, never HTML.
- Missing progress for 30 seconds shows a delayed-status hint, not a timeout
  result. This timer sends no request. Refresh Status and reconnect recover through
  authorized REST history/detail; there is no continuous polling. The committed
  state-to-event crash window remains an approved limitation. Browser acceptance
  starts the real worker with a restricted database role and isolated dependencies;
  required Docker execution tests are never skipped.

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

### Milestone 13 observability contract

Both JVMs use Boot-managed Micrometer/Prometheus and JSON console logging. Default
HTTP management exposure remains health-only. The opt-in `observability` profile
moves health and Prometheus to a separate `127.0.0.1` listener: API 8082 and worker
8083 (configurable ports). It rejects a public bind or a shared application port.
The API permits GET scrapes only on its actual management listener; application
ports never expose Prometheus. Only health and Prometheus are exposed, JMX stays
disabled, and health details remain hidden. Local host access is trusted; use a
local scraper or an authenticated SSH tunnel, never a public reverse-proxy route.
Enabling observability does not enable worker consumption or relax sandbox opt-in.

New execution creation timestamps are generated by PostgreSQL using the existing
column. API dispatch-failure completion also uses the database clock, preserving
timestamp ordering when API and database host clocks differ. No schema or REST
contract changes are required.

Metric names below are Micrometer names; Prometheus uses underscores, `_total`
for counters, and seconds with `_count`/`_sum` for timers. All series include the
fixed `application` tag identifying API or worker.

| Metric | Meaning / bounded labels |
| --- | --- |
| `http.server.requests` | Boot request count and latency; route templates, method, status, outcome and exception. URI cardinality is capped at 100 per process; excess series are denied. |
| `pairforge.websocket.connections` / `.authenticated` | Current admitted transport/authenticated sessions; derived from the session map, including expiry and repeated disconnect handling. No identity/IP tags. |
| `pairforge.execution.submitted` | Confirmed QUEUED insert commits; `language` JAVA/PYTHON. |
| `pairforge.execution.completed` | Confirmed conditional terminal writes by their owning API/worker; `status` and fixed `reason` (or NONE). Includes dispatch failures and interrupted recovery. |
| `pairforge.execution.queue.wait` | Successful claim's `started_at - created_at`, including dispatch time; `language`. Both timestamps originate in PostgreSQL. Unclaimed/uncertain claims have no sample. |
| `pairforge.execution.duration` | Known stored runner duration on confirmed terminal write; `status`. Includes preparation, compilation, runtime and result inspection; excludes queue wait and final cleanup. Unknown recovery/overall-timeout durations are omitted, never fabricated as zero. |
| `pairforge.execution.phase` | Observed preparation/compilation/runtime attempts; `language` and `phase`. Timed in the worker with a monotonic clock, including failed attempts. Compilation exists only for Java. Excludes sandbox preflight and final cleanup. |
| `pairforge.execution.dispatch.failures` | Exhausted dispatch failure/uncertainty, fixed `reason`. |
| `pairforge.execution.persistence.unknown` | API persistence outcome cannot be confirmed. |
| `pairforge.execution.events.publish.failures` / `.consume.failures` | Exhausted publication or rejected notification; consume uses a fixed `reason`. |
| `pairforge.execution.cleanup.failures` | Failed cleanup attempts by boundary: `operation` sandbox_stop, stop, or reconcile. One incident may fail at more than one boundary; this is not a count of distinct executions. |

Counters and timers are process-local operational evidence, not durable accounting.
They reset on restart and may miss a transition when a commit response is lost or
a process crashes before recording it. A conditional update affecting zero rows,
duplicate job/event, or result persistence retry does not increment a completion
counter. Notification delivery never drives outcome counts. There is no outbox,
durable metric ledger, historical replay, or new monitoring service.

Structured application logs include server-generated request IDs and explicit
execution/room IDs where available. HTTP logging context is cleared even on
exceptions; asynchronous worker records explicitly attach execution IDs. They
exclude request bodies, auth headers, invitations and source/output. Application
error handlers log exception types instead of raw database exception messages.
Existing Hibernate/STOMP payload-logging suppression remains.
Do not enable verbose framework/driver logging when handling private submissions.

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

### Milestone 14 CI contract

Two independent GitHub-hosted Ubuntu jobs verify Java (API and worker) and the
frontend including real-browser acceptance. Required Docker/Linux/cgroup v2/
seccomp prerequisites fail explicitly; existing sandbox preflight verifies
effective controls. Compilation and submitted code still run only inside
disposable submission containers. CI has no production credentials, uses
read-only repository permissions, and runs controlled fixtures on disposable
hosts. It does not provide a public execution environment or deployment.

Actions use verified immutable SHAs, toolchains use explicit patch versions,
and existing Maven checksums, npm lockfiles and trusted sandbox digests remain
authoritative. Hosted OS packages are not immutable; tool/capability versions are
recorded. No retries, continue-on-error, or optional Docker integration suites
hide failures. Browser JAR packaging skips Java tests only in the frontend job;
the independent Java job must run the complete suite.

Source-discovered suite reports must exist and contain nonzero, consistent test
counts without errors, failures or skips. Fault-injection helper tests cover
missing/incompatible tools, unavailable Docker/security controls and bad reports.
Only sanitized source-path/status/count summaries are uploaded (7-day retention),
even after a failing test. XML entities/DTDs are rejected. Raw reports and browser
captures remain excluded to protect credentials and source/output. Acceptance
requires real failed-test workflow evidence followed by a passing clean candidate;
local validation alone is insufficient. See README for commands and pinned versions.
Browser fault fixtures close both the browser and upstream sides of Playwright's
WebSocket proxy when injecting a disconnect; closing only one side can leave
upstream sessions behind and interfere with later reconnect checks.

### Milestone 12 failure acceptance matrix

Existing feature tests remain required; the reliability milestone adds combined
cases rather than duplicating their fixtures. Worker fakes never interpret source.
Docker faults injected at the command boundary use real disposable resources;
they do not claim to stop the developer's shared Docker daemon.

| Failure/scenario | Required state and recovery | Coverage |
| --- | --- | --- |
| Invalid/expired identity, invitation, room access, or WebSocket destination | No unauthorized read/write/subscription; expiry closes or suppresses delivery. | `AuthIT`, `RoomIT`, `CollaborationIT` |
| Malformed/oversized frames, connection floods, slow subscribers | Reject invalid input and enforce connection, executor, send-buffer and timeout bounds. | `CollaborationIT`, `CollaborationLimitsTest` (blocked-send fixture for slow clients) |
| Concurrent edits, stale generations, TTL loss, Redis outage | Versions do not regress; explicit document reset and manual recovery; no offline replay. | `CollaborationIT`, collaboration client tests, `rooms.spec.ts` |
| Concurrent admission and full backlog | Atomic per-user window and bounded durable outstanding count; rejected requests create no job. | `ExecutionIT.perUserAdmissionIsAtomicUnderConcurrentAttempts`, `concurrentGlobalAdmissionNeverExceedsDurableOutstandingLimit` |
| Redis plus broker outage with a full backlog | Dependency restoration does not bypass PostgreSQL capacity; only a conditional terminal transition frees a slot. | `ExecutionIT.dependencyRecoveryCannotBypassDurableOutstandingCapacity` |
| Nack, return, lost confirm, persistence uncertainty | Same-ID retries; conditional failure cannot overwrite a claim; preserve ID/unknown outcome for REST. | `ExecutionJobPublisherTest`, `ExecutionIT` dispatch/outage cases |
| Crash after commit before job publication | Assert stranded QUEUED limitation and conditional operator recovery, not guaranteed redispatch. | `ExecutionIT.interruptionAfterCommitLeavesDocumentedQueuedGapAndManualConditionalFailureIsSafe` (injected interruption, not OS kill) |
| Duplicate job, uncertain claim/commit, late final write | One claim; no rerun of started/terminal work; revision-conditional results; persistence before ack. | `ExecutionProcessorTest`, `ExecutionWorkerIT` duplicate, concurrent claim, and lost-persistence cases |
| Exhausted reads/claims/result writes and broker recovery | Finite attempts, readiness DOWN/liveness independent, no automatic resume or new attempt budget. | `ExecutionProcessorTest` budget cases; `ExecutionWorkerIT.databaseLossBeforeClaimPausesWithoutRunningAndDoesNotAutoResume` |
| Database plus broker loss after execution, either restore order | Valid job remains available; latch survives reconnect; replacement preserves terminal commit or cleans/fails interruption, never reruns that source. | `ExecutionWorkerIT.combinedDatabaseAndBrokerLossCannotResetRetriesOrRerunStartedWork` |
| Worker process kill before/after terminal commit; orphaned real sandbox | Redelivery preserves terminal state; confirmed-dead predecessor resources are removed before interruption recovery. | `ExecutionWorkerIT.killedWorkerProcessRedeliversWithoutRerunningStartedOrCompletedJob`, `killedRealWorkerLeavesOrphanWhichRestartRemovesBeforeInterruptedCommit` |
| Interruption plus unavailable Docker cleanup | No terminal persistence before verified cleanup; real resources remain visible until recovery and are not mistaken for success. | `DockerSandboxLifecycleIT.interruptionWithDockerLossCannotCompleteUntilRecoveryCleansRealResources` |
| Ownership mismatch, orphan, blocked exec, shutdown | Only owned canonical resources are removed; deadlines/reconciliation and shutdown cleanup remain bounded. | `DockerSandboxLifecycleIT`, `ExecutionWorkerIT.gracefulWorkerShutdownWaitsForActiveContainerCleanup` |
| Context shutdown while a failure-triggered consumer stop is active | Drain the existing stop before processor teardown; late connection failures cannot schedule work on a closed executor. | `ExecutionConsumerTest.shutdownWaitsForFailureStopBeforeClosingProcessorOrReturning`, `ExecutionWorkerIT` outage/restart cases |
| Java/Python errors and sandbox resource/control failures | Compilation/runtime only inside constrained containers; enforce time, memory, CPU, PIDs, output, storage and network controls; fail closed. | `DockerSandboxIT`, `DockerSandboxLifecycleIT`, `SandboxPolicyTest`, `DockerCommandClientTest` (forced termination versus unexpected output-read errors) |
| Event publish/consume failure and duplicate/stale events | Saved result survives; bounded retry/discard, no execution rerun or state regression. | Both `ExecutionEventPublisherTest` suites, `ExecutionEventConsumerTest`, `ExecutionWorkerIT.notificationFailureDoesNotLoseResultOrRepeatSource` |
| Database unavailable to API event listener | Exhausted event is discarded, persisted output survives, authorized REST recovers without another submission. | `ExecutionIT.databaseLossDiscardsNotificationAfterBoundedAttemptsButRestRecoversResult` |
| API/browser reconnect, lost HTTP response or notification | Recover authoritative history/detail; stable selection; no automatic POST retry or continuous polling. | `ExecutionIT.reconnectingApiConsumerHandlesBacklogWithoutRegressingCommittedState`, execution client tests, `executions.spec.ts` |

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

Milestone 15's approved AWS deployment uses two EC2 hosts and a CloudFront VPC
origin. The generated CloudFront hostname provides browser HTTPS/WSS; the private
CloudFront-to-Caddy hop is explicitly approved HTTP, not end-to-end TLS. Worker
database and broker connections still verify TLS certificates and hostnames.
See [DEPLOYMENT.md](DEPLOYMENT.md) for the cost envelope, network and IAM
boundaries, tester allowlist, shutdown/backup contract and outstanding acceptance.

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
