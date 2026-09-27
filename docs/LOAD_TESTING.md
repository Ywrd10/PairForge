# Milestone 16 load measurements

## Scope and reproduction

This harness measures an isolated **local** API, worker and disposable PostgreSQL,
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

## Optional AWS checkpoint — approval required

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
the present local-only runner deliberately cannot target AWS.

Run the same 2/5/10-connection stages with one writer and three repetitions, then
three Java and three Python batches. Maximums remain ten connections, 30 jobs
including warm-ups, one worker, and 15 minutes of workload. Capture client
delivery/result distributions, PostgreSQL queue/duration measurements for only
the benchmark executions, queue observations, health and sandbox cleanup, and
bounded host/JVM CPU, memory and disk samples through existing authorized SSH.
Read CPU credits if existing permissions permit; do not expand IAM to obtain
them. Record Standard-mode throttling or unavailable measurements explicitly.

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
