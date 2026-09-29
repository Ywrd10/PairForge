# Milestone 15 deployed acceptance

Use the existing [PairForge deployment](https://d3pq3na8h2es74.cloudfront.net).
This is a supervised two-account demo, not Milestone 16 load testing. Never
share passwords or bearer tokens with the operator or commit them to this record.

## Preconditions and evidence

- The owner reported that both accounts registered/authenticated independently,
  joined the same room, and synchronized edits in both directions. The database
  confirms one room with both registered accounts as members. These are reported
  browser results plus membership evidence, not an independently observed test of
  two people. Confirm who operates the second account before final sign-off.
- On 2026-09-26 the renewed `pairforge` session verified the dedicated deployment
  identity. The private API allowlist was updated to exactly the two explicitly
  approved UUIDs, the API restarted healthy, and worker readiness passed. The
  documented operator gate opened restricted execution. No additional accounts
  or public execution were authorized.
- The owner confirmed that the second account was rejected from execution before
  allowlisting. This supplies the reported backend-denial case without creating
  a third user. Recheck execution rows/queues after CLI access is restored;
  the subsequent database check still shows zero executions and empty queues.
- Once both UUIDs are approved, the operator verifies API/worker/dependency
  readiness, worker sandbox preflight and private network boundaries, starts
  bounded resource observation, and opens admission only for those two UUIDs.
- Reload the updated frontend and log in again before testing. Save the room ID
  and token from **Copy invitation**; the invitation survives navigation within
  this authenticated tab, but not reload/logout/expiry.

## Test sequence

Record each execution ID and whether both browsers update without pressing
**Refresh Status**. A very fast job may skip a visibly rendered intermediate
state; the slower successful examples below should expose RUNNING. Regressions,
disconnects, stuck states or unexpected retries are failures to investigate.

1. Authenticate the two accounts separately. Create a room with account A and
   join it with account B using the copied ID/token. The earlier reported result
   may be retained if both users are still in that same room.
2. A adds a source comment, waits for synchronization, then B changes it. Confirm
   each change on the other browser. Avoid typing over each other during this
   check; the MVP uses server-authoritative versions, not conflict-free merging.
3. After the latest edit is acknowledged, reload B, log in again and reopen the
   room. Expect the same shared document. Repeat with A. Redis must still hold
   the document; expiration/loss instead produces the documented explicit reset.
4. A selects Java, pastes the successful Java example below, waits for B to see
   it, and clicks Run once. Both browsers should reach SUCCEEDED, exit code 0,
   with the exact stdout shown. Neither browser should require a refresh.
5. B selects Python, replaces the source with the successful Python example and
   clicks Run once. Verify the same result behavior in both browsers.
6. A runs the Java compilation-error example. Expect FAILED with
   `COMPILATION_ERROR`, compiler diagnostics in stderr and no application stdout.
   Compiler wording can differ; no API stack trace or infrastructure secret
   should appear. Both browsers remain usable.
7. B runs the Python runtime-error example. Expect FAILED with `RUNTIME_ERROR`,
   nonzero exit code, and `ZeroDivisionError` in stderr. Both browsers update.
8. A runs the non-terminating Python example once. Expect TIMED_OUT after the
   configured five-second runtime budget plus bounded orchestration/cleanup
   overhead. Observe both browsers and verify the disposable container is removed.
9. Run the successful Python example again after the timeout to verify recovery.
   With both browsers connected, each tester submits one additional successful
   run, allowing at most the two expected users' requests together. Record queue
   drainage and health; do not generate a synthetic stress test.

### Successful Java — stdout `PairForge Java OK`

```java
public class Main {
    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(1500);
        System.out.println("PairForge Java OK");
    }
}
```

### Successful Python — stdout `PairForge Python OK`

```python
import time
time.sleep(1.5)
print("PairForge Python OK")
```

### Java compilation error

```java
public class Main {
    public static void main(String[] args) {
        System.out.println("This must not run")
    }
}
```

### Python runtime error

```python
print(1 / 0)
```

### Python timeout

```python
while True:
    pass
```

## Operator observations and completion gate

Capture UTC timestamps and host CPU idle/usage, available RAM, service/container
memory, free disk, job/event queue ready/unacknowledged counts, execution status,
duration, exit code, failure reason and output bounds. Sample during execution,
not only before/after. Compare with the approved app t3a.medium / worker t3a.small;
verify no OOM/restarts, persistent queue backlog, leaked containers or dependency
health failures. Confirm configured sandbox CPU/memory/PID/network controls from
running containers without printing their source or environment. Report burstable
CPU-credit data if available; do not claim sustained-load capacity from short
demo measurements. Never resize or add resources without approval.

For final evidence distinguish browser observations, user-reported results,
database facts and resource samples. Do not infer browser delivery solely from
terminal database status. Current remaining cases above are **pending**.

### Actual evidence checkpoint — 2026-09-26

- The owner confirmed they are operating both authenticated accounts themselves;
  the required second-human acceptance is still outstanding.
- Their checklist reply retained PASS/FAIL and output/ID placeholders. No case
  can be recorded as passed from that reply. A database read at approximately
  16:49 UTC found zero execution records, with both execution queues empty.
  At that checkpoint Java/Python success, failure, timeout, recovery, browser
  state delivery and reload behavior were unverified; see subsequent evidence below.
- Bounded host sampling is active for fifteen minutes. Samples so far reflect
  idle/application/backup work, not execution capacity: approximately 2.6 GiB
  available on the app and 1.2 GiB on the worker, with zero service restarts.
  Do not claim adequate workload capacity until actual submissions are observed.
- A real encrypted S3 backup was downloaded, its SHA256 matched S3 metadata and
  checksum, and offline decryption succeeded. Its isolated database restore
  yielded one Flyway migration, two users, two rooms and three memberships.
  API-role table/owner-room relationship reads succeeded. This verifies restored
  application-role data access, not an independently authenticated HTTP login
  to a restored API. The temporary database and both plaintext dumps were removed;
  production API readiness remained UP. A cleanup-verification query initially
  failed due to shell dollar quoting; the corrected query returned zero matching
  temporary databases and verified no remaining remote dump.
- Final clean shutdown, stopped-host verification, DONE status and final commit
  remain pending. Do not replace them with a passing claim based on placeholders.

### Java evidence — 2026-09-27 01:01 UTC

- The owner supplied screenshots and reported that both accounts displayed the
  same successful Java result. Execution `a0cab0e0-4fd5-4efd-b3b4-35aea9122b21`
  returned `PairForge Java OK` on stdout, empty stderr, exit code 0 and duration
  4234 ms. A PostgreSQL read independently confirmed JAVA/SUCCEEDED, revision 2,
  creation at 01:01:11.117114 UTC and completion at 01:01:15.591921 UTC.
- The running Java sandbox sample showed 36.18 MiB memory against 512 MiB,
  14 processes against 128, and approximately one CPU in use during compilation.
  Inspection confirmed one CPU limit, network disabled, read-only root, non-root
  user, dropped capabilities and no-new-privileges. The worker had approximately
  1.19 GiB available RAM and zero service restarts at the sampled interval.
  These are samples, not measured peak usage or sustained-load capacity.
- Following completion, API/worker readiness was UP, both execution queues had
  zero ready/unacknowledged messages and no submission container remained.
- **Java success and result visibility in both accounts: PASS.** Automatic
  delivery without Refresh Status, intermediate browser transitions and document
  restoration after reload still need explicit browser confirmation. At this checkpoint Python,
  error, timeout/recovery and overlapping two-account workload cases remain pending.

### Python evidence — 2026-09-27 01:07 UTC

- The owner reported successful Python results in both accounts. PostgreSQL
  independently confirms two PYTHON/SUCCEEDED executions, both exit 0, stdout
  `PairForge Python OK` and empty stderr:
  `952ed667-e40f-4475-b804-d9a380b2436f` (2173 ms, created 01:07:15.960120 UTC)
  and `a5c5916c-c4fc-441e-9b80-9a104ae1dd1f` (2135 ms, created
  01:07:31.076307 UTC).
- API and worker readiness remained UP after these submissions. Both execution
  queues had zero ready/unacknowledged messages, and no sandbox container remained.
  The current sampling log did not contain these Python execution IDs; do not
  infer Python memory/CPU measurements from the earlier Java sample.
- **Python success and result visibility in both accounts: PASS.** Whether either
  account used Refresh Status remains unconfirmed. Error, timeout/recovery,
  reload, overlapping workload and second-human checks remain pending.

### Java compilation-error evidence — 2026-09-27 01:11 UTC

- The owner reported `/source/Main.java:3: error: ';' expected` and `1 error`
  in both accounts. PostgreSQL independently confirms execution
  `239fc5d0-6222-4cf4-91fd-74e0b46cd180` as JAVA/FAILED with
  `COMPILATION_ERROR`, exit 1, duration 1774 ms, empty stdout and matching
  compiler stderr. Creation time was 01:11:22.943636 UTC.
- **Compilation-error handling and visibility in both accounts: PASS.** API
  readiness remained UP and both execution queues drained to zero. Automatic
  browser delivery without Refresh Status remains awaiting explicit confirmation.

### Python runtime-error evidence — 2026-09-27 01:12 UTC

- The owner supplied the expected traceback ending in `ZeroDivisionError:
  division by zero`. PostgreSQL independently confirms execution
  `84111219-34f8-45b3-a8fb-890043d19bcd` as PYTHON/FAILED with
  `RUNTIME_ERROR`, exit 1, duration 954 ms, empty stdout and matching stderr.
  Creation time was 01:12:26.730873 UTC. **Runtime-error handling: PASS.**
- API readiness remained UP and both execution queues drained to zero. This
  reply did not explicitly confirm the error in both browsers or automatic
  delivery; those observations remain pending. A fresh bounded observation
  window was started on both existing hosts for timeout/recovery testing.

### Timeout evidence — 2026-09-27 01:22 UTC

- The owner reported the timeout in both accounts. PostgreSQL independently
  confirms Python execution `03720185-3009-4ca8-b68c-60cd5d371135` as
  TIMED_OUT, duration 5416 ms, created 01:22:58.020775 UTC and completed
  01:23:03.698684 UTC. **Timeout handling and visibility in both accounts: PASS.**
- Its running-container sample recorded 3.828 MiB against 128 MiB, two processes
  against 32, and approximately one CPU used under the one-CPU limit. Inspection
  confirmed disabled networking, read-only root, non-root user, dropped
  capabilities, no-new-privileges and no OOM. Host samples during the interval
  showed approximately 1.18 GiB available RAM and 48–49% CPU idle on the
  two-vCPU worker. These samples are not measured peak usage.
- Following timeout, API/worker readiness was UP, worker service restarts were
  zero, both queues had zero ready/unacknowledged messages and no sandbox
  container remained, including stopped containers. Successful post-timeout
  execution and automatic browser delivery remain to be confirmed.

### Post-timeout recovery evidence — 2026-09-27 01:24 UTC

- The owner reported success in both accounts. PostgreSQL independently confirms
  the next execution after the verified timeout,
  `a6f29681-a858-4cc6-8ff8-29ced67d698f`, as PYTHON/SUCCEEDED, exit 0,
  duration 2167 ms, stdout `PairForge recovery OK` and empty stderr. Creation
  time was 01:24:47.220562 UTC. **Post-timeout recovery and result visibility
  in both accounts: PASS.**
- API/worker readiness remained UP, worker restarts were zero, both execution
  queues drained and no sandbox container remained. The reply does not explicitly
  establish whether Refresh Status was used. Automatic delivery, reload
  restoration, overlapping workload and second-human acceptance remain pending.

### Resumed acceptance window — 2026-09-27 20:46 UTC

- Renewed `pairforge` credentials verified the dedicated non-root deployer in
  the approved account and region. Both original hosts were already running;
  no start, resize, replacement or additional resource was needed.
- API/worker readiness was UP; PostgreSQL, Redis and RabbitMQ containers were
  healthy. The allowlist remained exactly the two approved UUIDs and admission
  was open. Worker metadata remained disabled and no sandbox container remained.
- CloudFront HTTPS root returned 200 and unauthenticated `/api/auth/me` returned
  401. The HTTPS WebSocket upgrade returned 101; the bounded curl probe then
  timed out intentionally while waiting on the upgraded connection. This verifies
  the transport handshake, not authenticated STOMP or browser event delivery.
- Available RAM was approximately 2659 MiB app / 1198 MiB worker, free root disk
  24 GiB / 15 GiB, and worker restarts zero. Fresh fifteen-minute host samplers
  started at 20:46:24 / 20:46:37 UTC. Reload restoration, explicit no-refresh
  browser observations, overlapping workload and second-human acceptance remain
  pending, as do final backup/shutdown/stopped-host verification and final commit.

### Browser restoration and automatic delivery — 2026-09-27

- Following the explicit reload/no-refresh instructions, the owner confirmed
  completing and verifying all steps: the acknowledged shared source restored
  after separately reloading, authenticating and reopening the room in both
  accounts, and earlier execution results appeared without Refresh Status.
  **Reload restoration and automatic result delivery: PASS (owner-reported).**
  This is browser evidence supplied by the owner, not inferred from database
  terminal states. It does not establish every intermediate state was visibly
  rendered, nor does it establish participation by a second person.
- A fresh bounded host measurement window was prepared for overlapping requests
  by the two approved accounts. Workload results, second-human confirmation and
  final backup/shutdown/stopped-host checks remain pending.

### Workload correlation — 2026-09-27 20:52 UTC

- The owner confirmed the requested two-account run. The database shows only
  one new execution: `f792a44a-e896-40bd-9daf-afc9ce297286`, submitted by the
  approved second account, PYTHON/SUCCEEDED, exit 0, duration 2665 ms, stdout
  `PairForge two-account OK`, created 20:51:58.213134 UTC. The previous record
  remains the earlier recovery execution; no matching first-account submission
  exists in this check. Shared result visibility is reported successful, but
  overlapping submissions are **not verified** by this evidence.
- Both services remained ready, queues drained, service restarts were zero,
  no sandbox remained and free root disk remained 24 GiB app / 15 GiB worker.
  Shutdown is deferred while the missing workload observation is clarified.

### Independent account submissions — 2026-09-27 21:03 UTC

- Screenshots show two distinct successful results. PostgreSQL confirms
  `94ffd16c-7be7-4124-977c-b7a9412a174f` was submitted by the approved second
  account and `ce10fc73-6e60-43c1-b3f0-78d13af5ff89` by the owner account.
  Both returned `PairForge two-account OK`, SUCCEEDED and exit 0, with durations
  2638 / 2643 ms respectively. **Submission by each approved account: PASS.**
- The first ran from 21:02:56.660324 to 21:02:59.502605 UTC; the second ran
  from 21:03:11.825069 to 21:03:14.668248 UTC. These were sequential, not
  overlapping, executions. Do not describe them as simultaneous workload.
- API/worker readiness remained UP, queues drained, worker restarts remained
  zero and no sandbox container remained. The sampler was running but did not
  capture these short-lived containers; no per-container memory/CPU result is
  inferred for these IDs. Earlier Java/timeout container measurements remain
  valid. Coordinated overlapping requests and second-person participation,
  followed by final backup/shutdown, remain pending.

### Final acceptance and shutdown — 2026-09-27

- The owner confirmed a teammate was operating the second account and explicitly
  confirmed that teammate independently logged in, joined using the invitation,
  edited in both directions and observed Java/Python results without Refresh
  Status. **Two-person deployed workflow: PASS (owner-reported).** The browser
  observations are not represented as automated or independently witnessed.
- The supplied screenshots and PostgreSQL match final requests
  `4b59ede1-2ea0-45f5-85e9-ccaf20151cc8` (second account) and
  `3db76247-cf3f-4b37-9a0a-c1004ac23a67` (owner), both in the shared room.
  Both succeeded, exit 0, stdout `PairForge two-account OK`, durations
  2645 / 2634 ms. The first was created 21:06:02.861743 UTC; the second at
  21:06:04.876143 UTC, before the first completed at 21:06:05.747481 UTC.
  The second started 21:06:05.758185 UTC and completed 21:06:08.599429 UTC.
  **Overlapping accepted requests with serial worker processing: PASS.**
- All post-case readiness checks passed, queues drained and no sandbox remained.
  The 20:50–21:05 sampling window recorded minimum available RAM 2580.1 MiB
  app / 1211.4 MiB worker, minimum sampled CPU idle 25% / 82%, no service
  restarts, and free root disk 24 / 15 GiB. It captured earlier Python work,
  but ended before the final pair; do not claim continuous sampling of that pair.
  Earlier Java and timeout sandbox resource/control samples remain valid.
  CPU-credit reads were denied by IAM. **Demo capacity: PASS with these sampling
  limitations**, no sustained-load or peak-capacity claim and no upgrade required.
- Final backup service returned success/exit 0. The 16,310-byte encrypted S3
  object `postgres/2026-09-27/<backup-object-2>.dump.cms`
  had AES256 encryption and SHA256
  `a16c5838eb63dd94bb5e060bbb254a62232f5e6b5af38073ccd01bf835c3f03d`.
  Download integrity and offline decryption passed. Isolated restore yielded
  1 migration, 2 users, 2 rooms, 3 memberships and 18 executions; application-role
  table/relationship reads passed. Temporary database and remote/local plaintext
  dumps were removed and verified absent. **Fresh backup and restore: PASS.**
- Admission closed before drain, zero durable queued/running rows and empty
  job/event queues were verified, worker and app/dependency services stopped,
  then both EC2 hosts were independently verified stopped with no public IPs.
  No PairForge Elastic IP or app internet default route remained.
  **Clean shutdown and stopped-host verification: PASS.** Durable disks were kept.
- Milestone 15 is DONE. Earlier pending statements in dated checkpoints describe
  the state at those times. No Milestone 16 implementation or load testing began.

For subsequent demo windows, perform the documented encrypted backup and isolated restore with
an application-level read of restored data. Close execution admission, drain
queues/durable work, stop the worker, verify a fresh off-host backup, stop services
and both hosts, and verify stopped state using `docs/DEPLOYMENT.md`. Preserve
durable volumes. Mark Milestone 15 DONE and commit final evidence only after all
required checks pass. Do not begin Milestone 16.
