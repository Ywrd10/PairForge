# Testing and verification

The final Milestone 16 commit `fcd04c7b0cee28858883afe7162b01ef1b564e94` passed
[Actions run 36431277187](https://github.com/Ywrd10/PairForge/actions/runs/36431277187):
365 Java, 70 frontend unit, 35 load-harness and 12 browser tests, with zero
failures, errors or skips. This is pinned evidence for that commit, not a claim
that tests were rerun during documentation-only portfolio work.

## What the tests establish

| Layer | Representative checks |
| --- | --- |
| Java unit/integration | Real PostgreSQL/Redis/RabbitMQ behavior; migration rollback and constraints; HTTP/WebSocket authorization; TTL/reset/version ordering; admission and confirmed dispatch; duplicate jobs/events; worker process kills; bounded dependency/result-persistence failures |
| Real Docker sandbox | Java/Python success and errors; time, memory, PID, output, network and writable-storage limits; required controls; orphan/workspace cleanup |
| Frontend unit | Auth and room forms; request validation; stale responses; editor lifecycle; debounce/snapshot ordering; manual recovery; uncertain execution submission handling |
| Chromium acceptance | Independently authenticated sessions, invitations, real shared edits, reconnect/restoration, real Java/Python results and failure recovery |
| Harness unit | Timing/percentiles, event correlation/duplicates, observation deadlines, cleanup, scoped continuation and uncertain-submission handling |

The [failure acceptance matrix](ARCHITECTURE.md#milestone-12-failure-acceptance-matrix)
maps reliability requirements to named tests. The load measurements in
[LOAD_TESTING.md](LOAD_TESTING.md) are separate manual experiments, not CI capacity
tests or replacements for the failure cases above.

## Run relevant checks locally

Use the prerequisites in [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md). Run commands
from the repository root in PowerShell, and run the Java and browser container
suites sequentially on a local Windows machine. Tests use disposable infrastructure;
they do not require the normal development Compose services to be running.

### API and worker

```powershell
.\scripts\prepare-sandbox.ps1
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
pwsh -NoProfile -File scripts/check-ci-reports.ps1 -Kind Java
```

Keep image preparation and Maven in the same terminal so the immutable image IDs
are inherited. Maven builds both runnable JARs and runs JUnit/Spring Boot/
Testcontainers suites. Docker, Linux cgroup v2 and seccomp are required; sandbox
tests fail rather than skip when prerequisites are missing. On Linux, use
`bash ./mvnw --batch-mode --no-transfer-progress clean verify` with the same
prepared images. The [CI workflow](../.github/workflows/ci.yml) shows the complete
Linux setup and helper checks.

### Frontend and focused harness tests

```powershell
Push-Location frontend
npm.cmd ci
npm.cmd run lint
npm.cmd run typecheck
npm.cmd test
npm.cmd run test:load
npm.cmd run build
Pop-Location
pwsh -NoProfile -File scripts/check-ci-reports.ps1 -Kind Unit
pwsh -NoProfile -File scripts/check-ci-reports.ps1 -Kind Load
```

`test:load` runs 35 focused harness tests; it does not run a benchmark or start
AWS. Collect these reports before Playwright, which clears its output directory.

### Browser acceptance

After building both JARs and preparing sandbox images in the Java step:

```powershell
Push-Location frontend
npx.cmd playwright install chromium
npm.cmd run test:e2e
Pop-Location
pwsh -NoProfile -File scripts/check-ci-reports.ps1 -Kind Browser
```

The fixture uses fresh container credentials, temporary accounts/rooms, API and
worker ports 18080/18081, and Vite preview port 15173. The configured web server
builds the frontend with its test API URL. Both JVMs, the test namespace's sandbox
containers/workspaces and dependency containers/volumes are cleaned at teardown.
Do not reuse a running development API on those test ports. Forced OS termination
may require inspection of that run's owned resources; never use a global prune
or erase unrelated volumes as cleanup.

Playwright uses one worker and zero retries. Traces, videos and screenshots are
disabled because they can contain credentials or invitation tokens. Browser
fixtures intentionally lower BCrypt cost and raise test registration limits;
normal runtime defaults are unchanged. See [SCREENSHOTS.md](SCREENSHOTS.md) for
safe manual presentation captures.

## CI guarantees and diagnostics

Two GitHub Actions jobs verify backend/worker and frontend independently on
Ubuntu. Actions use immutable commit pins; Maven's checksum, exact npm versions
and sandbox image digests are committed. The hosted OS/kernel itself can change.
CI requires working Docker security controls, runs helper permission/cleanup
checks, and uses no production credentials. It does not deploy or start AWS.

The frontend job packages JARs with `-DskipTests` only because the independent
Java job runs the full Java suite. Both jobs must pass. Report gates discover
required source suites and reject missing, empty, malformed, inconsistent,
failing or skipped results. Raw XML remains in module `target/` and frontend
`test-results/`; only sanitized suite paths/status/counts are uploaded for seven
days. Historical workflow links outlive that short artifact-retention period.

Run `pwsh -NoProfile -File scripts/test-ci-checks.ps1` when changing these gates.
Their controlled fixtures cover invalid prerequisites and reports without
stopping a real Docker daemon. Reproduce the failing suite rather than blindly
rerunning unrelated tests, and preserve failure assertions.

## Documentation-only checks

There is no committed Markdown linter or link-checker command. For a documentation
change, check relative targets and heading anchors, fenced-block syntax, Mermaid
syntax, documented commands against script parameters/configuration, ignored
artifact patterns, and `git diff --check`. Verify quoted results against the
saved sanitized artifact and the recorded workflow; do not repeat completed
benchmarks to validate copied numbers. Avoid checking the live demo as a link
health probe while its scheduled deployment is stopped.

The earlier chronological verification notes remain in
[history/VERIFICATION_NOTES.md](history/VERIFICATION_NOTES.md). They describe the
state at each historical milestone, not current gaps or current test counts.
