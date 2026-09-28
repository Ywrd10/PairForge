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

Local harness verification currently includes 22 passing focused tests and 74
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
and final fixture teardown succeeded. AWS remains stopped; its optional run
requires the separate approval below. Milestone 17 is outside this work.

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
verify STS account `298984481596` and IAM user `pairforge-deployer` using only
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

**This is an incomplete cloud benchmark, not Milestone 16 completion.** Preserve
the existing local baseline and completed cloud stages. The sanitized evidence is
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

Identity was verified as profile `pairforge`, account `298984481596`, IAM user
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
plan has **25 submissions remaining**, including five warm-ups.

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

- Key: `postgres/2026-09-28/034041-ff0de7058ee04cbb9a91765aa409e7d1.dump.cms`
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
approximately $4.25/month, usage dependent). Milestone 16 remains IN PROGRESS;
Milestone 17 is untouched.
