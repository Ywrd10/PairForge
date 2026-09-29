# Milestone 16 load measurements

## Scope and reproduction

The local runner measures an isolated **local** API, worker and disposable PostgreSQL,
Redis and RabbitMQ containers. It does not restart AWS or accept a remote URL.
It uses the existing API, invitation admission, STOMP protocol and real Docker
Java/Python runner. There is no product API/schema change, extra service, or
worker-concurrency increase. These are small local measurements, not cloud or
production-scale capacity claims.

Requirements: Java 21, Node 24, PowerShell 7.4+, a working Linux Docker engine
with the existing sandbox controls, and free loopback ports 18080–18083.
Use the repository's pinned versions and committed npm lockfile. From the root:

```powershell
./scripts/run-load-tests.ps1 -Profile smoke
./scripts/run-load-tests.ps1 -Profile local
```

The launcher checks prerequisites, prepares pinned sandbox images, packages the
unchanged JVM applications (packaging is explicitly not a Java-test run), installs
locked frontend dependencies, runs the harness unit tests, and invokes the chosen
profile. With these prerequisites already prepared, use `npm run load:smoke` or
`npm run load:local` from `frontend`. Required Docker failures fail the run.

`frontend/tools/load/local.json` supplies explicit caps: ten live connections,
30 submitted jobs including warm-ups, 900 seconds overall, ten-second HTTP/socket/
document deadlines, and 120 seconds from submission for terminal observation.
Settings are range-checked before starting resources. Lower bounds in `smoke.json`
exercise two connections and four real jobs. Both profiles are manual; normal CI
runs only `npm run test:load` and validates every required harness test report,
including rejection of missing or skipped suites.

Generated credentials/tokens remain in memory. Exactly two random synthetic
accounts are registered in the disposable database. The guest's nonmember room
read must fail before it joins using the invitation. Production users, execution
allowlists, AWS credentials and persistent development volumes are not used.

Reports are saved under ignored `.tmp/load/<timestamp>-<random>/report.json`.
They include configuration, environment, base Git commit, dirty-tree indicator,
harness source SHA-256, immutable sandbox image IDs, timing samples, aggregate
statistics, resource observations, failure counts and cleanup status. They omit
passwords, tokens, invitations, source/output and account/execution UUIDs.
Do not publish raw application logs as benchmark artifacts.

## Methodology

The API and worker run as separate host JVMs. PostgreSQL 17.11, Redis 7.4.11 and
RabbitMQ 4.1.8 use random loopback ports and unique container names. A random
sandbox namespace and temporary workspace isolate each run. The shared browser
fixture is reused for lifecycle management; load mode retains default bcrypt
cost 12, authentication/rate limits, worker concurrency/prefetch one and sandbox
limits, rather than the browser test fixture's faster authentication settings.
Observability listens only on loopback ports 18082/18083.

Collaboration and execution are measured sequentially:

| Setting | Collaboration | Execution |
| --- | --- | --- |
| Repetitions | Three at each of 2, 5 and 10 connections | Three per language |
| Warm-up | Five seconds per stage, excluded | One job before each language batch, excluded |
| Measured workload | 20 seconds per stage | Four jobs per language batch |
| Payload | 1,024-byte full document with unique update ID | Fixed single-file Java/Python program printing `PairForge load OK` |
| Arrival pattern | One writer per room, nominal two updates/sec; wait for all receivers before the next send | Four sequential HTTP submissions followed by drain; one worker runs them serially |
| Cooldown | 500 ms between stages | Batch starts at least 60 seconds apart |

The collaboration client sends STOMP directly, using the configured Origin and
bearer authentication. Monaco rendering and the browser's 300 ms debounce are
not part of the measurement. A delivery is counted once for each connection and
update ID, including the writer; duplicate acknowledgement/broadcasts do not
inflate counts. Generation/content mismatches fail the scenario. Slow delivery
can reduce the achieved update rate: this is a bounded, closed-loop workload,
not a saturation or maximum-throughput test. Each stage deliberately reconnects
one observer and verifies the exact generation, version and document snapshot.
Reported unexpected disconnects exclude that intentional reconnect.

Execution acceptance latency is measured from POST start through receipt parsing.
Terminal-result latency is measured from POST start to the first terminal STOMP
event on **each** observer, using one load-generator monotonic clock. REST then
verifies the final status, stdout and exit code. Events arriving before the HTTP
receipt are retained; duplicate/stale events cannot replace the first terminal
observation. Notification failure is reported separately from an execution timeout.
Uncertain POST outcomes stop the run without automatic resubmission.

Queue delay is PostgreSQL `started_at - created_at`, including dispatch. Runner
duration comes from `duration_ms` and includes preparation, compilation, runtime
and inspection, excluding queue wait and final cleanup. The harness reads these
columns from its own disposable database; it does not change the REST contract.
Throughput is measured jobs divided by the batch's submission-to-verification
drain interval, excluding warm-up and inter-batch cooldown. It is not sustained
throughput. Percentiles use nearest rank; small execution samples make p95/p99
close to the maximum and do not establish tail-latency guarantees.

Every roughly two seconds the collector samples existing private JVM/PairForge
metrics, host available RAM, load-generator RSS and available filesystem bytes.
It bounds storage to 600 samples and records scrape failures. Sampling can miss
short peaks and includes its own overhead. Dependency queues are inspected at
batch boundaries; both execution queues must drain at the end. Readiness, zero
owned sandbox containers and zero job directories are checked before teardown.
Sockets, owned processes, containers/volumes and temporary workspaces are cleaned
in `finally`, including on failure or a handled SIGINT/SIGTERM. Startup/cleanup
operations have their own bounded deadlines; the overall workload timer stops
new work but cannot make an in-progress Docker CLI call instantaneous. Forced
OS termination cannot guarantee in-process cleanup; inspect only that run's
uniquely named fixture containers and sandbox labels if recovery is required.

## Verification and results

The CI permission prerequisite passed on commit `54bee80` in
[Actions run 36352308012](https://github.com/Ywrd10/PairForge/actions/runs/36352308012):
365 Java, 70 frontend unit and 12 browser tests, zero failures/errors/skips.
That run predates the load harness and is not evidence for its new code.

Candidate `149b5798490075ebd7f33090d81dc25f71f6884b` subsequently passed
[Actions run 36355070812](https://github.com/Ywrd10/PairForge/actions/runs/36355070812).
Downloaded sanitized reports confirm 365 Java, 70 frontend unit, 22 harness and
12 browser tests passed with zero failures, errors or skips. Browser retries are
disabled. Required integration-report gates and frontend lint/typecheck/build
also passed. No additional CI fix was needed for this candidate.

The initial local harness verification included 22 passing focused tests and 74
CI gate checks, plus frontend typecheck/lint, 70 unit tests and production build.
All twelve existing real-browser regression tests pass with retries disabled
after the shared fixture changes. The final launcher smoke run also passes after
the failure-path hardening; it performs four real jobs and verifies cleanup.
The successful smoke run exercised real collaboration, reconnect, nonmember
denial and four successful Java/Python jobs with complete cleanup. Two earlier
smoke attempts failed on harness-only assumptions (management readiness ports
and the worker's empty namespace directory); both were corrected. Failed runs
are excluded from baseline results. The existing Monaco bundle warning remains.

The full local baseline passed on 2026-09-27, 22:07:08–22:16:43 UTC. The
[sanitized measurement artifact](load-results/m16-local-2026-09-27.json) contains
each repetition, raw delivery samples, individual job timings and aggregate
statistics. The original report with all sampled metric lines remains under
ignored `.tmp/load/2026-09-27T22-07-08-301Z-b2d1f1/report.json`.

Environment: Windows build 10.0.26200, AMD Ryzen 5 5600X (six cores/twelve logical
CPUs), approximately 16 GiB host RAM; Docker Desktop Linux Engine 29.4.3 with WSL2
kernel 6.6.114.1 and twelve vCPUs/8,286,998,528 bytes of VM memory. The host JVMs
used Oracle Java 21.0.9+7 and the generator used Node 24.19.0. The API/worker ran
on the Windows host while dependencies and execution sandboxes ran in Docker's
Linux VM. This differs from the deployed two-host Linux topology. The desktop
was not an otherwise idle dedicated benchmark machine; other host activity can
affect measurements. Builds and other heavy test suites were not run during the
baseline.

The baseline ran from a working tree based on `54bee800d0d51d618cdf4da0ffb15842c84b351e`;
the artifact records `workingTreeDirty: true`, the measured harness SHA-256 and
both sandbox image IDs. Later failure-path hardening contains subscription
exceptions, distinguishes transport errors from observation timeouts, bounds
fixture startup operations by the run deadline, and avoids asserting verified
cleanup when startup did not return a fixture. These changes do not change the
successful measurement path; focused tests and the follow-up smoke cover them.

| Connections | Repetitions | Delivered / expected | p50 ms | p95 ms | p99 ms | Unexpected disconnects / errors |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 3 | 240 / 240 | 9.68 | 13.80 | 15.18 | 0 / 0 |
| 5 | 3 | 600 / 600 | 9.73 | 11.82 | 12.38 | 0 / 0 |
| 10 | 3 | 1,200 / 1,200 | 9.56 | 12.15 | 14.69 | 0 / 0 |

All nine measured stages sent 40 updates each. All nine reconnect checks restored
the exact shared document, version and generation. Reconnect observations were
282–310 ms, including the deliberate 250 ms pause to release the old server slot;
they are not pure network reconnection latency. No payload errors or missing
deliveries occurred. These stages establish the tested workload only; the
non-monotonic p95 values do not imply more connections improve performance.

| Language | Accepted / submitted (including warm-up) | Measured jobs | Queue p50 / p95 ms | Runner p50 / p95 ms | Terminal p50 / p95 ms | Batch jobs/sec |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Java | 15 / 15 | 12 | 3,720.66 / 10,796.25 | 2,870 / 3,061 | 7,290.62 / 14,396.57 | 0.279 |
| Python | 15 / 15 | 12 | 2,407.34 / 7,161.08 | 1,692 / 1,797 | 4,786.95 / 9,619.51 | 0.418 |

Each language includes three excluded warm-up jobs. Terminal distributions have
24 observations per language (both clients for each of twelve measured jobs).
There were **zero rejected/uncertain submissions, execution failures, execution
timeouts, or notification timeouts**. All expected stdout/exit codes were
verified. Queue delay grows within each four-job burst because the single worker
processes jobs serially; no claim of parallel execution is made. The longer
terminal latency includes queueing and result persistence/notification, rather
than only the runner timer.

The collector retained 270 samples with zero collection errors. Sampled host CPU
peaked at 36.62%; API/worker JVM CPU at 27.31%/0.77% (normalized by the runtime,
not per-core saturation). Peak sampled API/worker **heap** was 111.67/108.15 MiB;
this excludes native memory and is not process RSS. Generator RSS peaked at
101.10 MiB. Minimum host available RAM was 4.97 GiB and disk availability was
550.54 GiB. Worker JVM CPU excludes submitted-code containers, and host CPU
includes other desktop activity. Container memory/CPU limits were enforced by
the existing worker sandbox preflight; this run does not report per-container
peak usage. No inference about AWS instance adequacy follows from these local
samples.

Both execution queues finished with zero ready/unacknowledged messages. API and
worker readiness passed, no owned sandbox containers or job workspaces remained,
and final fixture teardown succeeded. The separately approved AWS measurements
are recorded below; these local measurements were preserved. Milestone 17 is
outside this work.

Resume verification: recomputing the published artifact from the original report
produced an exact match, including per-run samples and resource aggregates.
Independent percentile checks and comparison with the rounded documentation
tables passed. The completed baseline was preserved without rerunning it.
The final launcher smoke report also records successful cleanup. Current local
inspection found no benchmark JVMs, loopback listeners or temporary fixture
directories; remaining Node processes belonged to Codex. Docker Desktop is
stopped, so container inventory could not be freshly enumerated. The successful
run-time zero-container/workspace checks remain the container-cleanup evidence.
No AWS resources were started or queried during this resume verification.

## Original AWS proposal (subsequently approved)

No AWS benchmark is authorized or started by the local runner. After reviewing
the local results, the proposed separate window is **45 minutes total**: up to
15 minutes for identity/startup/readiness and privately supplied authentication,
15 minutes for the bounded measurements, and 15 minutes for drain, backup and
verified shutdown. Stop adding work if a health/deadline/capacity check fails.

Use only the existing `us-east-1` app `t3a.medium` and worker `t3a.small`, existing
CloudFront HTTPS/WSS distribution, and the two already approved account UUIDs.
No new accounts, allowlist expansion, instance resizing, public management port,
additional AWS service or load-generator instance is proposed. A remote adapter
would need an explicit HTTPS target and privately supplied short-lived tokens;
the local runner deliberately cannot target AWS. The separate remote adapter
described below implements the approved target.

Run the same 2/5/10-connection stages with one writer and three repetitions, then
three Java and three Python batches. Maximums remain ten connections, 30 jobs
including warm-ups, one worker, and 15 minutes of workload. Capture client
delivery/result distributions, PostgreSQL queue/duration measurements for only
the benchmark executions, queue observations, health and sandbox cleanup, and
bounded host/JVM CPU, memory and disk samples through existing authorized SSH.
Read CPU credits if existing permissions permit; do not expand IAM to obtain
them. Record Standard-mode throttling or unavailable measurements explicitly.

Keep the same exact workload: 1,024-byte documents, nominal two updates per
second, five seconds of excluded warm-up and twenty seconds of measurement per
collaboration stage, with one explicit reconnect/restore check per stage. Each
execution batch contains one excluded warm-up and four measured fixed-output
jobs; batch starts remain at least sixty seconds apart. Both authenticated
observers receive terminal events. Ten-second request/document deadlines and
120-second terminal deadlines remain explicit; uncertain submissions are never
retried. Close unrelated sessions for the two approved users before measuring.

Proposed operational guards, not production performance guarantees: stop new
work on readiness loss, OOM, sandbox cleanup failure, uncertain submission or
an unexpected execution/notification failure. Pause progression if either host
exceeds 85% sampled CPU for sixty seconds, available memory falls below 256 MiB,
or free disk falls below the greater of 2 GiB and 10%. Record peaks, sustained
pressure, queue depth/drain time and any throttling; do not silently raise limits
or resize hosts. Sampling can miss short peaks. Report latency distributions
without inventing an AWS latency SLO from the local baseline.

Prepare and test the small remote adapter before the paid window. On approval,
verify STS account `<approved-aws-account-id>` and IAM user `pairforge-deployer` using only
profile `pairforge`, then verify the two recorded instance IDs and Standard
CPU-credit mode. Follow `DEPLOYMENT.md`: start the existing hosts, check EC2
status and disabled worker metadata, start PostgreSQL/Redis/RabbitMQ then the
API/Caddy, and start the worker with the one-use helper. Verify private dependency
TLS, sandbox preflight, HTTPS/WSS and the exact two-account allowlist before
opening execution admission. Authenticate privately and use a fresh benchmark
room; never put tokens in reports. No product API/schema change is proposed.

Estimated incremental cost before credits/tax for 0.75 hours:

| Item | Estimate |
| --- | ---: |
| App, $0.0376/hour | $0.02820 |
| Worker, $0.0188/hour | $0.01410 |
| Worker public IPv4, $0.005/hour | $0.00375 |
| Traffic/requests and fresh encrypted backup allowance | $0.20 |
| Total planning allowance, rounded up | **$0.25** |

Rates checked on 2026-09-27 against [AWS T3a pricing](https://aws.amazon.com/ec2/instance-types/t3/)
and [AWS public IPv4 pricing](https://aws.amazon.com/vpc/pricing/). The $0.20 line
is a conservative allowance, not a quoted usage bill. Existing encrypted EBS and
retained S3 storage continue their previously approved idle charges; no extra
volumes are proposed. Standard CPU-credit mode must be rechecked before startup;
this estimate does not authorize Unlimited mode or extra resources. Recheck the
dedicated `pairforge` identity/account and current deployment state before action.

At the end, close execution admission, drain durable work and queues, stop the
worker, create/verify the fresh encrypted seven-day-retention backup, stop app
services, stop both EC2 instances, and independently verify both are stopped.
Preserve the documented private networking, TLS, sandbox restrictions and
startup/shutdown procedure in `DEPLOYMENT.md`. The window is an operator plan,
not an automatic billing cap. Approval is required before restarting either host.

## Approved AWS run procedure

The owner subsequently approved exactly the checkpoint above. Documentation
checkpoint `6c1e817` was committed with a clean working tree before preparing the
remote adapter. Adapter checkpoint `476ad70` passed 33 focused tests, the required
load-report gate, 70 frontend unit tests, lint, typecheck, production build and
PowerShell/Bash syntax checks before either host was started. The local baseline
was not rerun. Application code, schema, instance sizes and architecture were
unchanged.

The separate `frontend/tools/load/remote-run.ts` entry point permits only the
recorded CloudFront HTTPS origin and exact approved configuration. It reuses the
local collaboration/execution scenarios. `remote-policy.ts` enforces workload
bounds, both approved identities, fresh capacity samples and the operational
guards. `remote-io.ts` reads bounded samples over existing private SSH; the
read-only `scripts/observe-load-host.sh` collects Linux host health/capacity and
queue observations. Missing/malformed/stale samples abort new work, as do guard
violations. An abort never retries an uncertain POST.

For any separately approved future run, both authenticated account IDs must match
the exact two-account set in the ignored operator configuration; missing/invalid
configuration aborts before authentication/network work. See
[OPERATOR_CONFIGURATION.md](OPERATOR_CONFIGURATION.md). This configuration does
not modify the deployed allowlist or authorize a new AWS window. Resource and
backup-object aliases in public evidence replace identifiers only; measurements,
hashes and verification results are unchanged.

Before a run, transfer that observer as `/home/ubuntu/m16-observe.sh` to each
existing host using `send-deployment-file.ps1`; complete the deployment startup
checks and open admission only for the two approved UUIDs. Close unrelated room
tabs to avoid consuming their five-session-per-user limit. In a private local
PowerShell 7 terminal, invoke:

```powershell
./scripts/run-aws-load.ps1 -NodePath <absolute-node-24-path> -ReportDirectory <new-ignored-report-directory>
```

Enter both existing account credentials only at its private prompts. Passwords
and tokens remain in process memory; tokens pass to Node through standard input,
never command arguments, environment files or reports. The adapter confirms both
identities through `/auth/me`, creates one new benchmark room and joins the other
account using its invitation. No account creation or allowlist changes occur.
HTTP redirects are rejected and WebSockets use verified WSS. Sessions must have
at least 700 seconds remaining; workload also aborts before the earliest expiry.

The report is incrementally written as `report.json`; `room-id.txt` is a private
correlation aid for inspecting only the new room's durable timing rows. Existing
reports cannot be overwritten by the launcher. A fresh authentication attempt
after rejected login is safe only before workload creation. Do not restart a
failed/uncertain benchmark without inspecting its report and durable records.

Observers sample CPU over a one-second `vmstat` interval, available RAM and root
disk space, host OOM counters, readiness, cleanup-failure counters and aggregate
ready/unacknowledged depth of the two execution queues. The loop sleeps four
seconds between samples; SSH/broker/scrape overhead increases that interval.
The client aborts on a twenty-second observation gap. CPU-credit reads use only
existing IAM permissions. Private inspection and queue polling add overhead;
sampled peaks are not continuous maxima. Operator verification of job directories,
backup success and stopped EC2 hosts remains necessary after the runner exits.

## Partial AWS measurements — 2026-09-28

**Historical checkpoint, completed by the continuation below.** The original
partial artifact remains unchanged so the interruption is visible. Its evidence is
[`load-results/m16-aws-partial-2026-09-28.json`](load-results/m16-aws-partial-2026-09-28.json).
Its overall `passed` flag remains false; it retains the actual inspection failure,
per-stage samples, recovered durable timings and resource observations. It contains
no credentials, invitation tokens, submitted source or private room/account IDs.

### Environment and actual scope

The load generator was the same Windows 11 Ryzen 5 5600X desktop, Node 24.19.0,
using the public CloudFront HTTPS/WSS URL over its normal internet connection.
The API and worker were the existing `us-east-1a` t3a.medium and t3a.small hosts,
each with two AMD EPYC 7571 vCPUs, approximately 3.75/1.87 GiB guest RAM and
30/20 GiB encrypted gp3 volumes. Both used Standard CPU-credit mode, Ubuntu
24.04.5 LTS, kernel 7.0.0-1013-aws, OpenJDK 21.0.12.1 and Docker 29.1.3.
API/worker maximum JVM heaps were 768/256 MiB. PostgreSQL 17.11, Redis 7.4.11
and RabbitMQ 4.1.8 ran on the app host. Worker concurrency/prefetch remained one;
sandbox images, limits and deployed JAR hashes are recorded in the artifact.
No application binary, schema, allowlist, instance size or architecture changed.

Identity was verified as profile `pairforge`, account `<approved-aws-account-id>`, IAM user
`pairforge/pairforge-deployer`. Both EC2 status checks, dependency/API/worker
readiness and sandbox preflight passed. Worker database and broker connections
used the private app address with TLSv1.3; configured database `verify-full` and
broker certificate/hostname validation remained enabled. CloudFront HTTPS
returned 200, unauthenticated WSS closed with 1008, and unauthenticated execution
POST returned 401. Execution admission retained exactly the two approved UUIDs.

Workload timestamps on the generator were 03:10:07–03:14:52 UTC. All nine
collaboration stages completed with the approved 1,024-byte document, one writer,
nominal 2 updates/sec, five-second warm-up and twenty-second measured window.
Only the first Java batch ran: one excluded warm-up followed by four measured
fixed-output jobs. No Python batch or remaining Java batch ran. The full 30-job
plan had **25 submissions remaining** at this checkpoint, including five warm-ups.

### Collaboration results

Each row aggregates three repetitions; latency includes the external network,
CloudFront and API delivery. Percentiles use the same nearest-rank reporting as
the local baseline. They are not browser rendering or typing latency.

| Connections | Delivered / expected | p50 ms | p95 ms | p99 ms | Maximum ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2 | 240 / 240 | 49.76 | 68.22 | 78.03 | 89.92 |
| 5 | 600 / 600 | 47.45 | 62.76 | 71.59 | 88.46 |
| 10 | 1,200 / 1,200 | 47.84 | 72.09 | 93.83 | 152.64 |

All 2,040 expected deliveries arrived, with zero errors or unexpected disconnects.
All nine reconnect checks restored the source, version and generation. Reconnect
elapsed times were 450.84–575.27 ms, including the intentional 250 ms disconnect
pause. Duplicate observations are retained separately in the artifact and do
not inflate successful-delivery counts or latency samples; clients subscribe to
both room broadcasts and private acknowledgements.

At ten connections, the local loopback baseline was p50 9.56/p95 12.15 ms versus
AWS p50 47.84/p95 72.09 ms. Different hardware, placement and network paths prevent
attributing the difference to a single component. Neither run establishes a
maximum connection count or a production latency guarantee.

### Execution results (one Java batch only)

Five submissions were accepted and succeeded, with zero rejection, uncertain
submission, failure, timeout or missing terminal notification. Both authenticated
observers received every terminal result. No uncertain request was retried.

| Metric | Four measured Java jobs (warm-up excluded) |
| --- | ---: |
| Batch elapsed time | 13,369.08 ms |
| Small-batch throughput | 0.2992 jobs/sec |
| Queue delay p50 / p95 | 3,377.40 / 9,651.92 ms |
| Runner duration p50 / p95 | 3,019 / 3,187 ms |
| Terminal-result latency p50 / p95 | 6,691.37 / 13,024.52 ms |

Queue and runner statistics have four job samples; terminal latency has eight
observations (two per job). In both cases p95/p99 select the maximum; these tail
estimates are not stable capacity measurements. The local three-batch Java result was 0.2787
jobs/sec and Python 0.4178 jobs/sec. One cloud Java batch is insufficient for a
like-for-like repeated-batch comparison, and no cloud Python figure exists yet.
Small-batch throughput must not be described as sustained system capacity.

### Capacity observations and limitations

There were 97 samples with zero collection errors: 43 app and 54 worker samples.
All observed readiness checks passed, with zero OOM or cleanup-failure counters.

| Host | Maximum sampled CPU | Minimum available RAM | Minimum available root disk |
| --- | ---: | ---: | ---: |
| App | 83% | 2,670.38 MiB | 23.18 GiB |
| Worker | 64% | 1,225.20 MiB | 14.15 GiB |

The app observer's aggregate execution-queue samples peaked at two ready and
one unacknowledged message. Worker queue fields are placeholders; queue depths
come from the app observer. No configured capacity guard fired. These samples
support the completed short workload only; they do not verify the remaining
25 jobs, sustained saturation, concurrent editing plus execution, or future scale.
No capacity evidence justified resizing either host.

The host UTC timestamps were approximately fifty seconds ahead of the generator.
Use client `observedAt` and workload phase for approximate correlation, not direct
cross-host timestamp subtraction. Delivery/result latencies use one client
monotonic clock; queue durations use one PostgreSQL clock, so those calculations
do not depend on cross-host synchronization. Sampling can miss transient peaks;
there are no continuous capacity measurements during the authentication pauses.
CPU-credit balance reads were denied by existing IAM permissions; Standard mode
was verified but credit balance/throttling cannot be quantified. IAM was not widened.

### Adapter failure, continuation and CI

The first batch completed before the adapter's timing inspection failed. The
reader incorrectly parsed only the first line of multiline PostgreSQL JSON after
the SSH helper's identity preamble. Commit `4b04ff0` parses the complete JSON and
adds regression coverage. A read-only query recovered the five terminal rows in
the exclusive benchmark room. Their creation order matched the five sequential
accepted submissions; no execution was rerun. The artifact describes this recovery.

The owner approved an at-most-seven-minute continuation within the original
45-minute operating window. Commit `29d3988` restricts recovery to this exact
nine-stage/five-job checkpoint, caps remaining work at 25 submissions, and retains
a five-minute shutdown reserve. Fresh authentication was not completed: the owner
reported that the private terminal did not open. No continuation runner or jobs
started before the time fence. The hosts were stopped, briefly restarted for the
approved continuation, then cleanly stopped again within the original window.
A new window requires separate approval; never rerun completed stages by starting
the ordinary full-run launcher. Private terminal visibility must be verified
before starting another paid window.

Adapter checkpoint `476ad70` passed Actions run `36373018952`. Run `36374218145`
for `29d3988` passed the required Java job but failed one existing frontend page
test at its immediate language-heading assertion. Load/build/browser steps were
then skipped and their report gates failed explicitly. No missing suite was
treated as passing. Commit `bdf5c54` waits for editor readiness and awaits user
interactions while retaining every assertion. Local verification passed all
70 frontend unit tests, 35 harness tests, lint, typecheck and build. The existing
Monaco chunk-size warning remains. The affected test also passed three targeted
diagnostic runs; those selected only that test and do not replace the full-suite
evidence. Replacement [Actions run 36375165081](https://github.com/Ywrd10/PairForge/actions/runs/36375165081)
for `bdf5c54` passed. Downloaded sanitized summaries confirm 365 Java, 70 frontend
unit, 35 harness and 12 browser tests with zero failures, errors or skips. Required
container suites and fixture cleanup ran; no test retry or weakened permission
was introduced. The subsequent results checkpoint changes documentation/artifacts
only; its own workflow status must be checked separately.

### Final shutdown evidence

The original host window began at 03:02:41 UTC. Final independent checks confirmed
both exact instances stopped with no public IP by 03:46:26 UTC, inside 45 minutes.
Admission was closed first; PostgreSQL had zero QUEUED/RUNNING executions and both
RabbitMQ queues had zero ready/unacknowledged messages. The worker stopped with
zero owned sandbox containers or job directories; observer processes ended.
After the backup, API/Caddy, the backup timer and dependency containers stopped.

The final backup service returned success/exit 0. Its CMS-encrypted object was
stored in the existing backup bucket with S3 AES256 encryption and the verified
seven-day lifecycle. Downloaded ciphertext matched both the stored SHA-256
metadata and S3 checksum:

- Key: `postgres/2026-09-28/<backup-object-3>.dump.cms`
- Size: 16,811 bytes
- SHA-256: `c50405754e6ffa411bdca66c72cf336baa0ec5e3fd57672299f485585ba1e797`

This verifies the new backup's upload/integrity; the previously recorded
Milestone 15 restore drill remains the restore evidence. This backup was not
separately restored during the benchmark window.

Before/after permitted inventories match: the same two instances, the same
encrypted 30/20 GiB gp3 volumes, and zero Elastic IPs. No infrastructure creation
or resizing operation was performed. NAT-gateway enumeration was denied, so this
is not a claim of an exhaustive account-wide billing audit. The app route table
retained its local/S3 routes with no public default route.

The approved $0.25 incremental allowance remains an estimate, not a measured
AWS bill. The elapsed window was less than 45 minutes, including a stopped
interval; no new recurring resource was added. Existing EBS and retained S3
storage continue billing while hosts are stopped (previous planning estimate
approximately $4.25/month, usage dependent). At this historical checkpoint
Milestone 16 remained IN PROGRESS; the completed continuation is recorded below.

## Approved twenty-minute continuation — terminal preparation

The owner approved one new twenty-minute host window for only the remaining
25 submissions: two Java and three Python batches, each with one warm-up and
four measured jobs. The completed collaboration stages and first Java batch
remain authoritative and must not be repeated. Checkpoint `c4fa299` passed
[Actions run 36375766436](https://github.com/Ywrd10/PairForge/actions/runs/36375766436).

Use `scripts/run-aws-load.ps1 -ApprovedTwentyMinuteContinuation` with the original
private report as `-ResumeReport`, a new ignored report directory and Node 24.
Run it manually in a visible PowerShell 7 terminal. Its first prompt pauses before
any authentication/network call; leave it there and confirm visibility to the
operator. No host may start before that confirmation. After startup/preflight,
the operator writes the actual UTC start into the non-secret, ignored
`.tmp/m16-aws-continuation-start-time.txt` and tells the owner to press Enter.

Both passwords are entered through `Read-Host -AsSecureString`. The launcher
verifies both account UUIDs through `/auth/me` while execution admission remains
closed, then pauses again. Its temporary `authenticated.json` contains only a
boolean and timestamp, never credentials. Open admission only after both approved
accounts authenticate, then instruct the owner to type `RUN`. Tokens pass to the
load process only through stdin and remain excluded from reports/files/logs.
The authentication marker is removed in `finally`.

The continuation keeps the existing workload and cooldowns, caps workload time
at seven minutes, and reserves five minutes within the twenty-minute host window
for verified shutdown. It refuses to start with less than six minutes of workload
budget remaining. Operator startup/authentication deadlines and host shutdown
still require active supervision; the harness cannot stop EC2 on its own.

Preparation checks passed: all 35 harness tests including twenty-minute deadline
boundaries, typecheck, lint, PowerShell syntax, and mocked launcher checks for
both pauses and marker cleanup. These checks made no AWS/network requests or
execution submissions. The repository and saved benchmark evidence were verified;
no completed benchmark was rerun. The owner subsequently confirmed terminal
visibility and authenticated both accounts; the actual continuation is recorded
below. No terminal was launched on the owner's behalf.

## Complete AWS measurements — 2026-09-28

The [complete sanitized artifact](load-results/m16-aws-2026-09-28.json) preserves
the nine original collaboration stages and first five Java job records exactly.
Their equality with the partial artifact was checked before publishing. The
original local baseline was also preserved. **No completed benchmark work was
repeated.** PostgreSQL inspection verified exactly thirty distinct successful
executions in the benchmark room, matched every saved queue/runner timing, and
confirmed the original five rows unchanged.

### Environment, timing and method

The environment is the same two-host deployment recorded above: app
`<app-instance-id>` (`t3a.medium`, two vCPUs, approximately 3.75 GiB guest RAM,
30 GiB encrypted gp3) and worker `<worker-instance-id>` (`t3a.small`, two vCPUs,
approximately 1.87 GiB guest RAM, 20 GiB encrypted gp3), in `us-east-1a`.
Ubuntu/kernel, JVM/Docker/dependency versions, sandbox image IDs and app/worker
JAR hashes remained unchanged and are recorded in the artifact. The Windows
Ryzen 5 5600X/Node 24.19.0 generator used its normal internet path through
CloudFront, rather than a cloud load-generator host. Worker concurrency and
prefetch remained one; both instances retained Standard CPU-credit mode.

Preflight verified the dedicated `pairforge` identity, exact hosts, private app
routes, API/worker/dependency health, sandbox readiness, HTTPS/WSS, and worker
PostgreSQL/RabbitMQ TLSv1.3 with certificate/hostname validation. Worker IMDS
remained disabled. The execution allowlist retained only the two previously
approved UUIDs. Anonymous execution was rejected with 401; anonymous WSS closed
with 1008. Admission stayed closed until both approved accounts authenticated
privately. Neither architecture, application binaries, schema, host sizes nor
execution authorization changed.

The original measurements ran 03:10:07–03:14:52 UTC. The new approved host window
started at **13:17:06.370 UTC** after visible-terminal confirmation. The continuation
ran **13:25:02.223–13:29:40.319 UTC**, approximately 278.10 seconds. It submitted only
the remaining two Java batches and three Python batches: **25 new jobs**, five
excluded warm-ups plus twenty measured jobs. The resumed report retains the
original start time; `resumedAt` identifies the second measurement interval.
The gap between windows is excluded from latency/throughput calculations.

The workload, fixed stdout, deadlines and cooldowns remained as specified above:
one warm-up and four measured jobs per batch; batch starts at least sixty seconds
apart, ten-second request/document deadlines, 120-second terminal deadlines, and
no uncertain-submission retries. Remaining work was capped at seven minutes with
a five-minute shutdown reserve inside the twenty-minute host window. The complete
report aggregates both measurement windows, not a continuous twenty-minute load.

The first window used adapter checkpoint `476ad70`. The continuation used
`c4fa299` plus the visible-terminal/twenty-minute deadline changes; the earlier
timing-parser fix was already present. A subsequent session-freshness adjustment
requires eight minutes of token validity for a seven-minute continuation instead
of the full-run eleven-minute-forty-second guard. The running process had already
loaded the earlier guard and passed it; this adjustment did not alter any
measurement or workload. Its boundary cases are tested. Neither the parser fix
nor these admission/deadline changes invalidate completed successful samples.

### Collaboration: preserved completed results

All **2,040 / 2,040 deliveries**, all nine stages and all nine reconnect-restoration
checks passed. There were zero errors or unexpected disconnects. Each row combines
three repetitions using one writer, a 1,024-byte document, nominal two updates/sec,
five-second warm-up and twenty-second measurement. These are the original samples.

| Connections | Delivered / expected | p50 ms | p95 ms | p99 ms | Maximum ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2 | 240 / 240 | 49.76 | 68.22 | 78.03 | 89.92 |
| 5 | 600 / 600 | 47.45 | 62.76 | 71.59 | 88.46 |
| 10 | 1,200 / 1,200 | 47.84 | 72.09 | 93.83 | 152.64 |

Reconnect observations remained 450.84–575.27 ms, including the intentional
250 ms pause. Duplicate acknowledgement/broadcast observations were counted
separately and did not inflate successful deliveries or latency samples.

### Execution: complete repeated batches

All **30 / 30 submissions** were accepted and succeeded: fifteen per language,
including three excluded warm-ups per language. Both authenticated observers
automatically received every terminal result; REST verified expected stdout and
exit zero. There were **zero rejections, uncertain submissions, execution failures,
execution timeouts or notification timeouts**. These workloads deliberately succeed;
they do not replace the failure/security tests from previous milestones.

| Language / metric | Samples | p50 ms | p95 ms | p99 ms | Maximum ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Java queue delay | 12 jobs | 3,377.40 | 9,651.92 | 9,651.92 | 9,651.92 |
| Java runner duration | 12 jobs | 2,502 | 3,187 | 3,187 | 3,187 |
| Java terminal-result latency | 24 observer deliveries | 6,691.37 | 13,024.36 | 13,024.52 | 13,024.52 |
| Python queue delay | 12 jobs | 809.58 | 2,317.83 | 2,317.83 | 2,317.83 |
| Python runner duration | 12 jobs | 626 | 661 | 661 | 661 |
| Python terminal-result latency | 24 observer deliveries | 1,707.89 | 3,254.11 | 3,255.97 | 3,255.97 |

Percentiles use nearest rank. Twelve measured jobs per language are insufficient
for reliable tail estimates: queue/runner p95 and p99 select the maximum.
Twenty-four terminal observations represent two observers of the same twelve
jobs, not twenty-four independent executions. Terminal latency includes queueing,
result persistence and delivery, whereas runner duration excludes queue wait and
final cleanup. Queue delay grows within a burst because the worker is serial.

| Batch | Measured jobs | Measured drain interval ms | Small-batch jobs/sec |
| --- | ---: | ---: | ---: |
| Java 1 (preserved original) | 4 | 13,369.08 | 0.2992 |
| Java 2 | 4 | 11,073.84 | 0.3612 |
| Java 3 | 4 | 10,930.94 | 0.3659 |
| Python 1 | 4 | 3,692.79 | 1.0832 |
| Python 2 | 4 | 3,504.87 | 1.1413 |
| Python 3 | 4 | 3,484.77 | 1.1479 |

Combined small-batch throughput is **0.3392 Java jobs/sec** and **1.1233 Python
jobs/sec**, calculated as twelve measured jobs divided by the sum of that
language's three measured drain intervals. Warm-ups, cooldowns and the gap between
windows are excluded. These figures are **not sustained system capacity**.

### Resource observations and comparison with local

The combined artifact contains **194 samples** with zero collection errors:
86 app and 108 worker. The continuation contributes 97 of them (43 app/54 worker).
Every sampled readiness check passed; OOM and cleanup-failure counters remained
zero. App queue observations peaked at three ready and one unacknowledged message
across `execution.jobs` and `execution.events`. Worker queue fields are placeholders;
only app samples measure queue depth.

| Host | Maximum sampled CPU, combined / continuation | Minimum available RAM | Minimum available root disk |
| --- | ---: | ---: | ---: |
| App | 83% / 66% | 2,665.40 MiB | 23.17 GiB |
| Worker | 65% / 65% | 1,212.35 MiB | 14.14 GiB |

No health, OOM, cleanup, uncertain-submission, notification or capacity guard fired
during the continuation. RAM stayed above 256 MiB; disk stayed above the greater
of 2 GiB or 10%; CPU never reached the sustained-above-85%-for-sixty-seconds guard.
The approved instance sizes were adequate for this bounded demo workload. No
additional resources or resizing were required.

Observers use a one-second `vmstat` interval followed by four seconds' sleep plus
SSH/broker overhead, yielding roughly five-to-seven-second samples. Sampled
maxima can miss short peaks; there is no continuous observation during the
operator authentication pauses or gap between windows. Host UTC clocks were
approximately fifty seconds ahead of the generator; correlate approximately
using client `observedAt` and phase. Client-monotonic latency and single-database
queue timing do not depend on cross-host clock subtraction. Existing permissions
again denied `cloudwatch:GetMetricStatistics`; CPU-credit balances and possible
credit throttling remain **unmeasured**, although Standard mode was verified.
No monitoring service or IAM expansion was added.

| Comparable bounded metric | Local baseline | AWS completed benchmark |
| --- | ---: | ---: |
| Ten-connection delivery p50 / p95 ms | 9.56 / 12.15 | 47.84 / 72.09 |
| Java queue p50 / p95 ms | 3,720.66 / 10,796.25 | 3,377.40 / 9,651.92 |
| Python queue p50 / p95 ms | 2,407.34 / 7,161.08 | 809.58 / 2,317.83 |
| Java runner p50 / p95 ms | 2,870 / 3,061 | 2,502 / 3,187 |
| Python runner p50 / p95 ms | 1,692 / 1,797 | 626 / 661 |
| Java terminal p50 / p95 ms | 7,290.62 / 14,396.57 | 6,691.37 / 13,024.36 |
| Python terminal p50 / p95 ms | 4,786.95 / 9,619.51 | 1,707.89 / 3,254.11 |
| Java small-batch jobs/sec | 0.2787 | 0.3392 |
| Python small-batch jobs/sec | 0.4178 | 1.1233 |

The identical workload permits descriptive comparison, not attribution to one
component. Local loopback/Windows-host JVMs/Docker Desktop WSL2 differ from native
Linux hosts and an external CloudFront path. The desktop is not a dedicated idle
generator; ordinary activity and inspection add overhead. Collaboration excludes
Monaco rendering/debounce. Editing and execution were measured separately;
neither environment establishes saturation, simultaneous mixed-load capacity,
large-scale production performance, or a latency guarantee.

### Final cleanup, backup and shutdown

After the last batch, admission was closed. PostgreSQL had zero QUEUED/RUNNING
executions globally; both execution queues had zero ready/unacknowledged messages.
The worker stopped with zero owned sandbox containers and zero job workspaces.
Remote observers ended, and no local Node benchmark/JVM/tunnel process remained.
The visible PowerShell shell may remain open for the owner; its launcher exited
and removed the non-secret authentication marker.

The backup service returned success/exit zero. The existing bucket's new
CMS-encrypted ciphertext also uses S3 AES256 encryption. Downloaded bytes matched
both SHA-256 metadata and the S3 checksum, and the seven-day lifecycle was verified:

- Bucket: `<private-backup-bucket>`
- Key: `postgres/2026-09-28/<backup-object-4>.dump.cms`
- Size: 18,252 bytes
- SHA-256: `52f1a80ed9d04a8834d1363759c6408b758f2c0e00be1e4df0805c43bd070eba`

This verifies fresh backup upload/integrity; the Milestone 15 restore drill remains
the restore evidence. This new backup was not separately restored. API/Caddy,
backup timer and dependency containers then stopped. Both exact EC2 hosts were
independently verified **stopped with no public IP** by **13:34:32.683 UTC**:
**17 minutes 26.313 seconds**, within the approved twenty-minute operating window.
The final artifact records the shutdown/inventory/backup checks.

Before/after permitted inventories match: two original instances, encrypted
30/20 GiB gp3 volumes, zero Elastic IPs, and app local/S3 routes without a public
default route. No infrastructure creation or resizing command was issued. As with
the first window, denied optional inventory/metric permissions prevent an
exhaustive account-wide billing/CPU-credit claim.

Using the previously approved planning rates ($0.0376/hour app, $0.0188/hour
worker), the full 17m26s window estimates approximately **$0.0164 compute**,
before requests/storage/traffic; this is an estimate, not the actual AWS bill.
Existing EBS and retained S3 storage continue billing while stopped (previous
planning estimate approximately $4.25/month plus usage). No new recurring
resource was introduced. Restart remains manual; Milestone 17 is untouched.

### Final verification

The documentation checkpoint `c4fa299` passed
[Actions run 36375766436](https://github.com/Ywrd10/PairForge/actions/runs/36375766436)
before this continuation. Relevant final checks pass all 35 focused harness tests,
frontend lint/typecheck, PowerShell syntax, and mocked visible-terminal/admission
gates with authentication-marker cleanup. Artifact assertions verify complete
counts, unchanged completed work, durable timings, aggregate statistics and
absence of credentials/private correlation IDs. Long benchmarks and unrelated
local suites were not rerun. The final pushed candidate is also subject to the
existing required Java, frontend, harness and browser workflow/report gates;
its exact Actions result is reported with the final commit.
