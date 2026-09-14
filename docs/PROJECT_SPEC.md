# PairForge Product Specification

## 1. Product Summary

PairForge is a real-time collaborative coding platform.

Authenticated users can create coding rooms, invite other users, edit code
together in real time, execute Java or Python asynchronously, and receive
execution results without refreshing the page.

The project is intended to demonstrate production-style backend engineering
using Java and Spring Boot.

---

## 2. Primary User Flow

A user should be able to:

1. create an account;
2. log in;
3. create a coding room;
4. share the room ID and an invitation token with another user;
5. have the second user join the room;
6. edit source code collaboratively;
7. choose Java or Python;
8. submit code for execution;
9. see execution progress;
10. receive stdout, stderr, and execution status;
11. view recent executions from the room.

---

## 3. Required MVP Features

### Authentication

Users can:

- register with email and password;
- log in;
- access protected resources.

Passwords must never be stored in plaintext.

---

### Rooms

Authenticated users can:

- create rooms;
- retrieve rooms they belong to;
- join an existing room;
- open a room;
- access rooms only when authorized.

Joining requires a valid invitation token, not just knowledge of a room ID.

The owner is automatically a member. Members may edit, change the active
document language, execute code, and read room history. There is no hard-coded
two-member architectural limit; any later operational capacity limit must be
configurable and documented. Two-user acceptance scenarios are minimum tests,
not maximum membership requirements.

Each room should contain:

- ID;
- name;
- owner;
- programming language;
- members;
- timestamps.

---

### Collaborative Editing

Users in the same room should see code updates from each other in near real
time.

MVP collaboration uses:

- WebSockets;
- client-side debouncing;
- server-side document versions;
- last-write-wins conflict resolution.

Updates replace the full document in server acceptance order. Versions order
accepted updates; they do not merge simultaneous edits.

Redis stores the collaborative document with a 24-hour inactivity TTL. Accepted
updates and authenticated room join/snapshot activity refresh the TTL. Transport
heartbeats alone do not count as activity. Reconnection restores the current
document while available. Redis expiration, eviction, or data loss explicitly
resets the document with a new generation and a visible reset notification.

Durable editor checkpoints are outside the initial MVP.

Connections must be authenticated, and both subscriptions and updates require
room membership. Offline changes must not be blindly replayed after reconnect.

Perfect simultaneous-edit conflict resolution is NOT required.

---

### Code Editor

The frontend should use Monaco Editor.

Supported languages for MVP:

- Java
- Python

The room interface should contain:

- editor;
- language selector;
- Run button;
- execution state;
- stdout/stderr display;
- connection status.

---

### Asynchronous Code Execution

Submitting code must not block the HTTP request while execution completes.

Expected lifecycle:

    QUEUED → RUNNING → SUCCEEDED / FAILED / TIMED_OUT

Pre-execution dispatch failures may transition directly from QUEUED to FAILED.

Every terminal execution is immutable with respect to execution processing:
duplicate RabbitMQ delivery must never rerun it.

Run captures an immutable snapshot of the initiating client's visible source
and selected language, independent of pending editor debounce updates.

The MVP supports one `Main.java` (no package declaration, entry point `Main`) or
one `main.py`, standard libraries only, closed stdin, no package installation,
and no external network access. Multiple files and interactive input are
unsupported. Standard-library APIs are still subject to sandbox restrictions.

Persist the execution in PostgreSQL, then publish to RabbitMQ `execution.jobs`
with publisher confirms and explicit dispatch-failure handling. Use at-least-once
message delivery and idempotent execution processing, not exactly-once claims.

No transactional outbox is included in the initial MVP. A crash between commit
and publish can strand a QUEUED execution; confirmation loss can make dispatch
uncertain. The API exposes the execution ID and authoritative status so the UI
does not blindly resubmit an uncertain request. See Architecture §9 for handling.

The client should receive execution-state changes in real time.

Committed changes travel through RabbitMQ `execution.events` to the API and then
to authorized WebSocket subscribers. Events may be duplicated, delayed, or lost
in the commit-to-publish crash window. Reconnect and explicit Refresh Status
retrieve authoritative PostgreSQL state through REST. Continuous polling is
not required; event delivery is not a durable browser notification guarantee.

---

### Execution Isolation

All submitted code, including compilation, must run inside disposable Docker
containers orchestrated by the worker. Host-process fallbacks are prohibited.

Required configurable restrictions include:

- execution timeout;
- memory limit;
- CPU limit;
- output-size limit;
- process limit;
- source-size limit;
- writable-storage limit.

External network access is always disabled. Use non-root execution, dropped
capabilities, no-new-privileges, retained seccomp protection, trusted images and
fixed commands, a read-only root filesystem, and bounded writable workspaces.

Submission containers receive no application secrets, Docker sockets, or
unrelated host files. Required controls must fail closed.

Initial limits and measurement requirements:

- 5-second runtime target, with a separate bounded compilation budget;
- language-specific memory budgets validated with real Java/Python smoke tests;
- 64 KiB UTF-8 source limit;
- 64 KiB combined stdout/stderr retention limit; overflow terminates execution
  with an output-limit failure reason and marks retained output as truncated.

Do not assume 128 MiB is sufficient for Java compilation and JVM overhead.

Container startup, compilation, and runtime must all have bounded deadlines.

Record final language-specific settings and measured behavior in Milestone 10.

Bound Docker logs, drain both output streams concurrently, and clean resources
on failure and worker restart. Docker is useful isolation for a portfolio demo,
not a claim of perfect security for arbitrary hostile workloads.

---

### Execution History

Users should be able to view recent executions for a room.

Each execution should record at least:

- ID;
- room;
- submitting user;
- language;
- submitted source;
- status;
- stdout;
- stderr;
- exit code;
- duration;
- timestamps.

---

### Rate Limiting

Users should not be able to submit unlimited execution jobs.

Execution submission enforces Redis-backed per-user rate limits and a global
outstanding-work limit before real execution is enabled for other users.

Worker concurrency is bounded. Dependency failure must not bypass admission
controls. Login and WebSocket input also require abuse limits.

---

### Observability

The system should expose meaningful operational metrics including:

- HTTP request count/latency;
- active WebSocket connections;
- executions submitted;
- executions succeeded;
- executions failed;
- executions timed out;
- queue waiting time;
- execution duration.

---

### Continuous Integration

GitHub Actions should automatically build and test the project on relevant
pushes and pull requests. Basic backend/worker tests and frontend checks begin
in Milestone 0; later CI work extends this foundation.

---

### Deployment

Functional MVP completion requires the tested local workflow. Portfolio-ready
completion additionally requires a deployed demo and deployment explanation.

Initial deployed execution is restricted to invited testers; public registration
alone does not grant execution access.

Deployment should include:

- frontend;
- Spring Boot backend;
- execution worker;
- PostgreSQL;
- Redis;
- RabbitMQ.

AWS is the preferred cloud platform where reasonable.

Use a dedicated worker host/VM for the deployed sandbox trust boundary. This is
the existing worker deployment, not an additional application service. Managed
PostgreSQL/Redis/RabbitMQ services are optional based on cost and practicality.

---

## 4. Non-Goals

The MVP will NOT attempt to implement:

- Google Docs-quality editing;
- CRDTs;
- Operational Transformation;
- video calls;
- voice calls;
- GitHub repository integration;
- collaborative debugging;
- arbitrary package installation;
- more than Java and Python;
- Kubernetes;
- microservices;
- multi-region deployment;
- perfect hostile-code isolation.

Transactional outbox, durable editor checkpoints, and exactly-once execution
guarantees are outside the initial MVP. Record the resulting limitations honestly.

These may be considered future improvements only after the core project is
complete.

---

## 5. Quality Requirements

The project should demonstrate:

- clean backend architecture;
- input validation;
- authorization;
- error handling;
- integration testing;
- failure handling;
- asynchronous processing;
- database persistence;
- real-time communication;
- observability;
- containerization;
- CI.

A feature should not be considered complete only because the happy path works.

Failure tests must cover unauthorized subscriptions, invalid invitations,
Redis expiration/loss, dispatch failure and confirmation uncertainty, duplicate
jobs/events, worker termination, result persistence failure, and sandbox resource
limits. The acknowledged dual-write crash windows remain MVP limitations.

---

## 6. Portfolio Requirements

Before PairForge is considered portfolio-ready, the repository should include:

- clear README;
- architecture diagram;
- local development instructions;
- screenshots;
- testing instructions;
- deployment explanation;
- benchmark/load-test methodology;
- measured results;
- known limitations.

Performance numbers and user counts must be measured honestly and must never
be fabricated.

---

## 7. Success Criteria

The MVP is successful when at least two independent users can:

    register → log in → join the same room using an invitation
        → edit collaboratively → submit Java or Python
        → execute asynchronously → receive the result in real time

while the backend demonstrates appropriate persistence, testing, isolation,
queueing, and observability.
