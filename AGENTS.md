# PairForge Agent Instructions

## Project

PairForge is a portfolio-quality real-time collaborative coding and
code-execution platform.

The goal is to demonstrate production-style backend engineering,
not to maximize feature count.

## Required Project Documentation

Before planning or implementing any non-trivial change, read the relevant
sections of:

- `docs/PROJECT_SPEC.md` — product requirements and scope
- `docs/ARCHITECTURE.md` — system design and architectural constraints
- `docs/ROADMAP.md` — implementation order and current milestone

These documents are the source of truth.

If code and documentation disagree, do not silently choose one.
Identify the discrepancy before making a major architectural change.

## Core Stack

Backend:

- Java
- Spring Boot
- PostgreSQL
- Redis
- RabbitMQ
- WebSockets

Frontend:

- React
- TypeScript
- Vite
- Monaco Editor

Infrastructure:

- Docker
- GitHub Actions

Testing:

- JUnit 5
- Spring Boot Test
- Testcontainers

Observability:

- Spring Boot Actuator
- Micrometer / Prometheus

## Architecture Rules

PairForge uses:

- one Spring Boot modular-monolith API;
- one separate execution-worker process;
- PostgreSQL for durable application state;
- Redis for ephemeral/distributed state;
- RabbitMQ for asynchronous execution jobs and committed execution events;
- WebSockets for real-time events;
- disposable Docker containers for submitted code.

Do not introduce:

- microservices;
- Kubernetes;
- Kafka;
- GraphQL;
- CRDTs;
- Terraform;
- implementation languages beyond Java and TypeScript;
- execution languages beyond Java and Python;

unless explicitly requested.

Use `execution.jobs` and `execution.events` with at-least-once delivery and
idempotent processing. Terminal executions must never be rerun on duplicate
delivery. Persist in PostgreSQL before publishing with publisher confirms and
explicit failure handling. The initial MVP does not use a transactional outbox;
document the remaining dual-write crash windows and retain outbox as future work.

Room admission requires an invitation token; knowing a room ID is insufficient.
Do not impose a two-member architectural limit. Redis collaborative documents
have a 24-hour inactivity TTL; expiration or loss causes an explicit reset.

## Backend Engineering Rules

- Prefer constructor injection.
- Keep controllers thin.
- Put business logic in services.
- Put persistence behind repositories.
- Use DTOs at API boundaries.
- Do not expose JPA entities directly through REST APIs.
- Validate external input.
- Return consistent error responses.
- Do not swallow exceptions.
- Use Flyway for database schema changes.
- Do not rely on Hibernate auto-DDL for production schemas.
- Avoid unnecessary abstractions and premature optimization.

Organize backend code by domain rather than one global controller/service/
repository hierarchy.

Expected domains include:

- auth
- user
- room
- collaboration
- execution
- infrastructure
- common

## Frontend Engineering Rules

- Use TypeScript.
- Avoid `any` unless justified.
- Keep API communication separate from presentation components.
- Keep WebSocket connection/state logic centralized where practical.
- Keep the UI functional and clean.
- Do not add large UI frameworks solely for visual polish.

## Security Rules

Submitted source, including compilation, MUST run only inside disposable
execution containers orchestrated by the worker. Never execute submissions in
the API or worker host process; host-process fallbacks are prohibited.

Execution must enforce configurable:

- timeout;
- memory limits;
- CPU limits;
- output limits;
- process limits;
- source-size and writable-storage limits.

External network access is disabled. Required sandbox controls must fail closed.
Use trusted images, fixed commands, non-root users, dropped capabilities,
no-new-privileges, and retained seccomp protection. Never expose Docker sockets,
application secrets, or unrelated host files to submission containers.

MVP execution supports only one `Main.java` or `main.py`, standard libraries,
closed stdin, and no package installation. Clean up containers and workspaces on
failure and reconcile resources left by worker crashes. See `docs/ARCHITECTURE.md`
for the detailed execution contract and worker-host trust boundary.

Authenticate WebSocket connections and authorize both subscriptions and sends.
Never trust client-supplied identity or room membership.

Never commit:

- passwords;
- API keys;
- JWT secrets;
- cloud credentials;
- database credentials.

Use environment variables for secrets.

Do not describe Docker containers as perfectly secure isolation for hostile
production workloads.

## Testing Rules

Every meaningful backend feature must include appropriate tests.

Use:

- unit tests for isolated business logic;
- Spring integration tests for application behavior;
- Testcontainers when PostgreSQL, Redis, or RabbitMQ behavior matters.

Before declaring work complete:

1. run relevant tests;
2. fix failures caused by the change;
3. run the broader test suite when practical;
4. report exactly what was tested.

Do not delete or weaken tests simply to make the suite pass.

Test authorization failures, dependency loss, duplicate delivery, dispatch
failure, worker crashes, and sandbox limits in the milestone that introduces
the behavior. Missing Docker must not silently skip required integration tests.
Basic CI starts in Milestone 0.

## Scope Discipline

Do not implement future roadmap milestones while working on the current
milestone unless required for a clean interface.

Do not add technologies just because they may look impressive on a resume.

Reliability required for a feature belongs in that feature's milestone. Do not
defer acknowledgements, authorization, resource limits, or crash handling solely
because a later reliability milestone exists. The documented MVP dual-write
limitation is an approved exception; do not add an outbox without approval.

Prefer a smaller system that is:

- working;
- tested;
- deployed;
- measurable;
- explainable;

over a larger half-finished system.

## Task Workflow

Before implementing a significant task:

1. read the relevant documentation;
2. inspect existing code;
3. identify the current roadmap milestone;
4. propose a short implementation plan;
5. identify important architectural or security implications.

Then implement the task.

After implementation:

1. run tests/checks;
2. summarize what changed;
3. list important files changed;
4. explain significant design decisions;
5. identify known limitations or follow-up work;
6. update `docs/ROADMAP.md` if milestone status changed.

Do not automatically start the next major milestone unless the user's task
explicitly asks for it.

## Git Discipline

Keep changes focused on the requested task.

Do not rewrite unrelated working code.

Do not modify architecture solely for stylistic reasons.

Prefer small, understandable commits and changes.

## Definition of Done

A feature is complete when:

- required functionality works;
- relevant tests pass;
- failure cases are handled;
- security constraints are respected;
- no obvious dead code remains;
- documentation is updated where necessary;
- the implementation remains within project scope.
