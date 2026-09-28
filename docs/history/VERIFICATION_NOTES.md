# Historical verification notes

These dated records were preserved from the pre-polish README at commit
`fcd04c7`. They describe the state at each earlier milestone; statements about
uncommitted work, unverified CI or milestones not yet begun are historical.
They are not the current project status or setup instructions.

Use the [current README](../../README.md), [testing guide](../TESTING.md),
[local development guide](../LOCAL_DEVELOPMENT.md) and
[roadmap](../ROADMAP.md) for the current project. Machine-specific Docker recovery
below is an incident record, not a routine installation procedure.

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
