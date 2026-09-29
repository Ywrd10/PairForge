# Milestone 18 — Beta testing

**Status: DONE — manual beta acceptance confirmed; cleanup, backup and shutdown verified.**

This beta validates the existing workflow with people outside the developer's
own accounts. It is a supervised usability session, not another load benchmark.
The owner personally coordinated and observed two external testers using their
own accounts and explicitly accepted those observations as the human beta
acceptance evidence. Retained backend records provide separate, incomplete
corroboration; earlier acceptance tests and benchmarks are separate evidence.

## For testers: about 10–15 minutes

Use [PairForge](https://d3pq3na8h2es74.cloudfront.net) only during the operator's
announced window. Work with another tester using your own independently
registered account. Use a unique demonstration password and harmless example
code. Never send passwords, JWTs, invitation tokens, or private source in feedback.

1. **Register, then log in.** Registration does not sign you in automatically.
   Tell the owner your registered email privately so they can identify your
   account. Execution remains unavailable until the owner explicitly approves
   your UUID and the operator confirms access. Keep testing the other steps while
   waiting; registration or a room invitation alone does not authorize execution.
2. **Create and join.** One tester creates a room and uses **Copy invitation** to
   share its room ID and token privately with the other. The other uses **Room ID
   to join** and **Invitation token to join** on the dashboard. Each tester should
   also create a room to check creation; use one shared room for the remaining
   steps. Save the invitation before reloading: it cannot be recovered afterward.
3. **Edit together.** Wait for **Connected — synchronized in Redis.** Take turns
   adding a harmless comment and confirm it appears in the other browser in both
   directions. Simultaneous edits may overwrite each other; this MVP does not
   merge competing changes.
4. **Switch languages.** Select Java, then Python, and confirm the language
   changes in both browsers. Switching preserves the text; replace it with the
   matching example below rather than trying to run Java as Python.
5. **Run working code.** After execution approval, each tester runs the Java and
   Python examples once. Wait for the partner to see the source, then click
   **Run** once. Both browsers should show **SUCCEEDED**, exit code zero and the
   expected stdout automatically, without **Refresh Status**. A fast job may not
   visibly render every intermediate state. Check **Recent executions** too.
6. **Try an expected error.** One tester removes the Java statement's semicolon
   and runs once; expect **FAILED** with compiler diagnostics in stderr. The
   other runs `print(1 / 0)` as Python; expect **FAILED** with `ZeroDivisionError`
   in stderr. These deliberate errors are successful checks when handled cleanly.
7. **Reload and reconnect.** Wait for the latest edit to synchronize, reload,
   log in again and reopen the room from the dashboard. Expect the current shared
   source/language while Redis retains it. Repeat with the partner. If the
   connection drops, restore the connection and choose **Reconnect**; verify the
   shared state before editing again. A brief operator-guided network disconnect
   can exercise this action if no natural drop occurs. Reconnect is manual.
   Copy any unsynchronized draft before reload; it is not durably saved.
8. **Send feedback.** Describe anything broken, confusing, slow or unexpected,
   including anything that prevented completion. Report a delayed execution
   before retrying: **Refresh Status** or history may recover it. Never submit
   another job merely because the first outcome is uncertain.

### Java — expected stdout: `PairForge beta Java OK`

```java
public class Main {
    public static void main(String[] args) {
        System.out.println("PairForge beta Java OK");
    }
}
```

### Python — expected stdout: `PairForge beta Python OK`

```python
print("PairForge beta Python OK")
```

One file only: `Main.java` or `main.py`, standard libraries, closed stdin, no
package installation or external network. Run one request at a time per tester;
the worker processes jobs serially. Ask the operator before any additional run.

## Feedback template

Send this privately to the owner; use an alias in repository records. Leave
unknown answers unknown. Include only redacted screenshots if helpful. Do not
send credentials, tokens, real email addresses, private code or a raw HAR/network
export. The operator can keep necessary request/execution IDs privately.

```text
Tester alias:
Device / OS / browser and version (if known):
What I attempted:
Did it work? (yes / partly / no / not tried):
Bugs encountered, including steps to repeat:
Confusing UI or wording:
Collaboration or reconnect issues:
Execution, output or error-display issues:
Anything slow or unexpected (rough duration if observed):
Anything that prevented finishing:
Steps I did not complete and why:
```

## Approved operator window

The owner approved **one 45-minute AWS operating window**, measured from starting
the first host, with at most **two external volunteer testers in one pair**,
and subsequently confirmed both external testers were available. Attendance and
completed workflow results still require actual evidence. Do not count the developer's own accounts,
automation, or prior milestone sessions as new beta testers. If only one external
person is available, document the actual pairing and count rather than inventing
a second tester. Participation and execution authorization are separate approvals.

| Time reserved | Work |
| --- | --- |
| 0–10 minutes | Start existing hosts and perform private/public preflight |
| 10–20 minutes | Independent registration/login, private UUID lookup, owner approval and restricted access confirmation |
| 20–35 minutes | Paired 10–15 minute workflow and factual feedback collection |
| 35–45 minutes | Close admission, drain, verify cleanup/backup and stop both hosts |

Bounds: at most **two active tester room tabs**, one shared collaboration room
at a time, worker concurrency **one**, and **12 total execution submissions**
across the entire window. The workflow plans six submissions (four successful
language runs and two deliberate errors); the remaining six are a ceiling for
explicitly agreed diagnostics, not a target. Count warm-ups/operator checks if
any occur; no synthetic load, automatic retries or benchmark runs. Keep the
existing rate limits and sandbox limits. Stop new testing at minute 35; end
earlier if readiness or authentication/approval is not established by minute 20.

Using the historical approved planning rates in [DEPLOYMENT.md](DEPLOYMENT.md#approved-environment-and-cost-envelope),
45 minutes estimates **$0.0423 compute** for both hosts together
(`0.75 × ($0.0376 + $0.0188)`), before existing worker IPv4, requests, storage
and traffic. Reserve **$0.10 incremental cost** for this small window as a
planning estimate, not a current price quote, AWS bill or hard cap. Retained EBS
and S3 costs continue during downtime. Existing $50 budget alerts remain in place.
No new instance, resize, NAT gateway, load balancer, paid monitoring or other
resource is proposed. Any extension or billable change requires separate approval.

### Before starting either host

- Obtain approval of the window and arrange tester availability privately. Have
  the existing administration key, pinned known-hosts file, recorded deployment
  state and backup recovery materials ready locally. Never launch a credential
  terminal on behalf of the owner; credentials belong in their own visible UI.
- Verify `pairforge` in `us-east-1` with the existing identity helper; require
  the exact approved non-root deployer account/identity from
  [DEPLOYMENT.md](DEPLOYMENT.md#manual-startup-shutdown-and-recovery).
  Resolve expired authentication before starting the paid window.
- Read the recorded instance IDs from the private state and compare them with the
  deployment runbook. Confirm the app is `t3a.medium`, worker `t3a.small`, both
  stopped, and Standard CPU-credit mode remains configured. Do not create or
  substitute hosts. Existing pinned application binaries are sufficient unless
  a subsequently reproduced beta defect requires a tested change.

### Startup and preflight

Follow [the existing startup procedure](DEPLOYMENT.md#manual-startup-shutdown-and-recovery)
through the approved private SSH path. These commands name existing helpers;
host-side commands must run on the appropriate host, not the local Windows shell.

1. Start the two recorded hosts with `scripts/set-aws-demo-hosts.ps1 -Mode Start`;
   wait for running state and EC2 status checks. Record the actual UTC window
   start. Confirm worker IMDS is disabled and app public ingress remains blocked.
2. On the app, start the existing production Compose dependencies, wait for
   PostgreSQL/Redis/RabbitMQ health, then start API, Caddy and `backup.timer` as
   documented. Keep the execution-admission marker absent. Verify API loopback
   readiness (8082), CloudFront HTTPS frontend/register/login, and frontend assets.
3. On the worker, confirm the predecessor is stopped and use
   `scripts/start-production-worker.ps1 -ConfirmedPreviousWorkerStopped` as root.
   Verify readiness on loopback 8083, trusted images and required sandbox controls,
   restricted private PostgreSQL/RabbitMQ connectivity with certificate/hostname
   verification, concurrency one and no public worker/management/Docker listener.
4. Verify authenticated WSS with the testers' actual collaboration workflow;
   registration/login, invitation and membership checks remain authoritative.
   Use safe private health/queue/resource observations; keep tokens, submitted
   code, outputs and raw payload logs out of collected diagnostics.

### Tester execution approval

1. Preserve the existing approved accounts. Before editing the private environment,
   verify its allowlist matches the previously approved set in the runbook.
   Any unexpected account is a preflight failure, not permission to keep it.
2. Each tester registers independently and provides their email privately. Look up
   the registered user through trusted administration; report the actual UUID to
   the owner and **wait for explicit approval of that UUID**. No automatic grant
   based on email, registration, invitation or tester participation.
3. Add only individually approved UUIDs to `EXECUTION_APPROVED_USERS` in the
   private API environment, retaining restrictive file permissions. Keep admission
   closed, restart the API and require readiness. Privately verify the exact full
   set against original approvals plus new explicit approvals. Never commit the
   environment, account emails or UUID list into beta evidence.
4. If practical, observe one unapproved tester's backend execution denial before
   approving them; it must create no execution/job. Mark this check not performed
   if it is not observed. Do not create an extra account just for it.
5. Open admission only after readiness and approval checks using
   `scripts/set-production-admission.ps1 -Mode Open -WorkerReadinessVerified` on
   the app. Confirm approved testers' actual requests work; keep unrestricted
   public execution disabled. If approved users cannot be verified promptly,
   close admission and shut down.
6. Request the owner's decision on temporary beta grants before the window:
   the proposed policy is to remove only newly granted beta UUIDs afterward,
   preserving the original approvals. Window approval alone does not approve
   unknown UUIDs; grant/removal decisions must be recorded privately.

### Observation, stop conditions and shutdown

Record UTC times, actual participant/session evidence and readiness, CPU,
available RAM, disk, queue ready/unacknowledged counts, OOM/cleanup failures and
service restarts through existing private administration. Capture start, during
execution and end samples; label them samples, not continuous capacity evidence.
Existing metrics/observers may be used read-only with bounded lifetimes and
cleanup. CPU-credit metrics remain unavailable unless existing permissions allow
them; never fabricate readings or expand permissions for this session.

Stop new work and close admission on health loss, OOM, unexpected execution or
notification failure, uncertain submission, stuck queues or sandbox cleanup
failure. Deliberate compiler/runtime errors are expected cases. Pause testing
if available RAM falls below 256 MiB, free disk falls below the greater of 2 GiB
or 10%, or CPU stays above 85% for 60 seconds. Do not resize or increase workload.

1. On the app run `set-production-admission.ps1 -Mode Close`, require the API
   restart to settle in-flight requests, and drain all accepted work. Check zero
   `QUEUED`/`RUNNING` rows and zero ready/unacknowledged messages in **both**
   `execution.jobs` and `execution.events`; diagnose rather than discarding work.
2. Stop the worker; verify zero owned sandbox containers and job workspaces.
   End observation processes and close tester room tabs. Apply only approved
   temporary-grant removals while admission is closed; verify the resulting set.
3. Start `backup.service` on the app; require success/exit zero. Verify the fresh
   encrypted S3 object, AES256 encryption, checksum/integrity and seven-day
   lifecycle through the existing backup procedure. Keep a verified encrypted
   recovery copy in owner-controlled storage if downtime may exceed seven days.
   Do not claim a fresh restore test merely from an upload; the recorded restore
   drill remains historical evidence.
4. Stop Caddy/API, backup timer and dependency services. Use
   `scripts/set-aws-demo-hosts.ps1 -Mode Stop -CleanShutdownVerified` only after
   those conditions pass, then independently verify both exact hosts are stopped.
   Verify no maintenance EIP/default app internet route or unexpected resource
   change remains. Report retained EBS/S3 costs and denied inventory permissions.

Ten minutes is a shutdown reserve, not proof shutdown will finish. If drain,
backup or stopping fails, keep admission closed, stop adding work and report the
actual state immediately. Never claim a clean shutdown or blindly stop active
work to meet a time estimate; further operation requires owner coordination.

## Issue records and fix policy

Preserve genuine feedback using aliases and factual observations. Use one record
per legitimate issue; leave reproduction uncertain until checked. Keep large
feature suggestions as feedback, outside the implementation scope.

```text
Issue ID / short title:
Tester alias / session alias / UTC date:
Affected component:
Device / browser (if known):
Reproduction steps:
Expected behavior:
Actual behavior:
Severity (blocker / major / minor):
Reproducible? (yes / no / not yet checked; attempts observed):
Evidence (redacted; private correlation reference if needed):
Disposition (investigating / fixed / documented limitation / feature suggestion):
Fix / regression test / relevant check result, if any:
```

A blocker prevents a core workflow or violates authorization/isolation; a major
issue materially disrupts the workflow; a minor issue causes confusion without
preventing completion. Reproduce where practical, make the smallest correction
to auth, rooms, collaboration/reconnect, execution/authorization or output/error
handling, and add an appropriate regression test. Use local reproduction and
targeted checks first. Do not spend the live window on open-ended development;
shut down and seek a separate retest window if needed. Architecture redesign,
unrelated features and large UI work are outside this milestone.

## Actual results — 2026-09-28

### Manual beta acceptance evidence

The owner confirmed that they **personally coordinated and observed two distinct
external testers using their own accounts**, and that all required tester-facing
checks passed. This direct manual observation, together with the testers'
reports, is the accepted evidence for the human beta-testing portion of
Milestone 18. The participant count is two, not inferred from database account
count alone. Both reported **Chrome on a PC**; operating systems and browser
versions were not supplied. Session counts and individual Run-click counts were
not independently tracked. Earlier acceptance sessions and automation are not
additional beta users.

The initial feedback relayed by the owner was:

> Both devices: google chrome, pc, registration/login:pass, create/join room:
> pass, invitation:pass, edit both directions: pass Java/Python stdout/err: pass.
> Everything passed, no errors seen.

The owner's final explicit manual acceptance confirmation records:

| Manually confirmed check | Tester A | Tester B |
| --- | --- | --- |
| Independent registration/login | PASS | PASS |
| Room admission and invitation workflow | Created a room: PASS | Joined the shared room: PASS |
| Collaborative edits in both directions | PASS | PASS |
| Working Java execution | PASS | PASS |
| Working Python execution | PASS | PASS |
| Expected stdout appeared | PASS | PASS |
| Reload restored source/language | PASS | PASS |
| Both browsers received results without Refresh Status | PASS | PASS |

The owner also confirmed that one tester ran `print(1 / 0)` and that the runtime
error was surfaced correctly to the user. The actions were confirmed on the
deployed CloudFront URL. These passes are attributed to direct owner observation
and tester reports; the agent did not independently observe every browser action
or corroborate every execution through retained backend records. No material
product bugs were reported by the two external testers. No product fixes were
required or made. This small beta does not establish broad adoption, a measured
defect rate or production capacity.

### Retained backend evidence and its limitation

Startup PostgreSQL contained **48** pre-existing terminal executions. After
admission closed and accepted work drained, the inspected database contained
**50**, including **two retained beta execution rows**, both belonging to Tester
B. This retained-row count is not a complete count of manually observed actions:

| Language | Durable outcome | Exit code | Runner duration | Observation |
| --- | --- | --- | --- | --- |
| Java | FAILED / COMPILATION_ERROR | 1 | 1,755 ms | Compiler error handled as a terminal result |
| Java | SUCCEEDED | 0 | 2,524 ms | Successful completion; stdout differed from the guide's exact example string |

Both beta users existed and had membership in the shared room. The inspected
room records showed a created room only for Tester B. Safe API counters recorded
two successful registrations, two successful logins, one successful join, one
room creation and two accepted execution submissions. Two authenticated
WebSocket connections were observed.
Counters are process-local; the database snapshot corroborates only the retained
records described here.

**No Python execution, Tester A execution, or Tester A room creation was present
in the inspected final database.** Repeated metadata lookups, including the final
post-admission-close snapshot, showed the same two Java rows. Missing rows were
not recovered or independently corroborated. The cause of the incomplete
retention/correlation was not established; no deletion, data-loss explanation or
complete backend audit trail is claimed. A different successful example is not
inherently a defect; its exact stdout was not retained in this evidence record.
Live editing, reload/restoration and browser notification acceptance come from
the manual observation above, not inferred membership or connection counts.

`BETA-EVIDENCE-01` is closed as an **explicitly accepted evidence limitation**,
not a demonstrated product bug or failed human acceptance case. The owner
instructed that their direct observation and tester-reported results satisfy the
human beta acceptance gate, and that the retained backend evidence is not a
complete audit trail of every manual beta interaction. This acceptance decision
does not change the application's persistence contract in
[ARCHITECTURE.md](ARCHITECTURE.md#4-durable-data-model), fabricate missing rows or
claim independent backend verification of them. No AWS restart, beta rerun or
product change is required for this documentation closeout. No additional account
was created to test denial; this window's optional unapproved-account rejection
check was **not performed**.

### Deployment and operating window

The owner explicitly authorized startup and confirmed availability before any
host started. The operator-clock window began at **18:19:24.906 UTC**. Admission
closed and the final drain finished at approximately **18:51:43 UTC**, before the
18:54:24.906 stop-testing deadline. The host-stop waiter completed at
**18:55:59.570 UTC**; independent EC2 verification at **18:56:55.297 UTC** confirmed
both hosts stopped, before the 19:04:24.906 maximum-window deadline. The complete
operation including final verification took **37.51 minutes**, below 45 minutes.

Only the existing `t3a.medium` app and `t3a.small` worker ran; both retained
Standard CPU-credit mode. The dedicated non-root deployment identity was verified
before operations. Preflight passed EC2 status checks, PostgreSQL/Redis/RabbitMQ
health, API/worker readiness, frontend/register/login HTTPS, Docker sandbox
readiness, verified private database/broker TLS 1.3 connections, and one job
consumer with prefetch one. The HTTPS/WSS probe retained certificate validation;
anonymous STOMP authentication was rejected with close code 1008. The two actual
browser sessions supplied authenticated connection evidence.

Admission began closed with exactly the original two approved accounts. Each
beta UUID received separate explicit owner approval before being added. Updates
preserved root-only mode 0600 and all unrelated environment lines. The exact
four-account set was verified before restricted admission opened. No public
execution was enabled. During shutdown, **both temporary grants were removed**,
the exact original approvals restored, environment permissions retained, and
API readiness verified with admission closed. Emails, UUIDs, tokens, submitted
source and raw outputs are excluded from this repository evidence.

### Resource observations and caveats

Existing read-only observers sampled during the session; no load benchmark ran.

| Host | Samples | Highest sampled CPU | Minimum sampled available RAM | Minimum sampled free root disk |
| --- | --- | --- | --- | --- |
| App | 231 | 76% | 2,650.1 MiB | 23.17 GiB |
| Worker | 356 | 61% | 1,253.7 MiB | 14.13 GiB |

All emitted samples reported readiness UP, zero OOM kills and zero cleanup
failures. App queue samples reached zero ready and at most one unacknowledged
message across the two execution queues. Worker observer queue fields are
placeholders, not broker measurements. Final direct queue checks, rather than
sampling, established the drain. No sampled guard breach required resizing.
Startup RAM samples (2,760/1,252 MiB) are separate from these interval minima.

CPU values are short `vmstat` intervals separated by approximately 5–7 seconds,
not continuous peaks or sustained capacity measurements. App observers ended
on deliberate API restarts during grant updates and admission close, leaving
sampling gaps; these curl exit-7 observations are planned readiness interruptions,
not silently suppressed failures. One worker sampling interval overlapped the
preceding read-only interval; the sample count is not independent workload
repetitions. Host timestamps were roughly fifty seconds ahead of the operator
clock. CPU-credit balances were not collected. These observations accompany only
the two durably recorded Java runs and must not be presented as evidence of the
missing Python runs or production capacity.

### Cleanup, backup and shutdown

- Admission was closed with the existing helper; no QUEUED/RUNNING rows remained.
  Both `execution.jobs` and `execution.events` had zero ready/unacknowledged
  messages, checked again before dependency shutdown.
- The worker process stopped with MainPID zero and no automatic restart. Zero
  labelled sandbox containers and zero job directories remained; its stable
  workspace namespace directory was retained. All bounded observers ended.
- Systemd recorded the requested worker stop as `failed/exit-code` because the
  JVM returned **143** after termination. Systemd timestamps show the requested
  stop immediately preceded that exit, with zero restarts. The initial strict
  `inactive` check therefore failed; subsequent process-state and cleanup checks
  verified it was stopped. This operational status limitation is documented,
  not hidden with `reset-failed`, treated as workload health loss, or claimed as
  a product fix.
- `backup.service` returned success/exit zero. The fresh CMS-encrypted PostgreSQL
  backup at `postgres/2026-09-28/<backup-object-1>.dump.cms`
  contains **18,842 bytes** of ciphertext, with S3 AES256 encryption. A download
  matched both S3's SHA256 checksum and SHA256 metadata. Seven-day lifecycle
  retention was verified. A ciphertext recovery copy was retained in existing
  owner-only local storage for downtime exceeding retention. No new restore
  drill is claimed; the earlier restore evidence remains historical.
- Caddy, API, backup timer and all three dependency containers stopped before
  the two exact hosts. Independent inventory confirmed exactly two existing
  stopped PairForge hosts, no public host IP, no tagged maintenance EIP and no
  app internet default route. No AWS resource was created or resized; this is
  scoped inventory evidence, not an exhaustive account billing audit.

The historical approved rates imply under **$0.036 compute** for the bounded
window, before traffic, requests and existing worker IPv4 usage; this is planning
arithmetic, not an actual bill. The $0.10 allowance was not expanded. Retained
EBS and S3 continue billing (the runbook estimates approximately $4.25/month
plus variable storage/requests); the existing CloudFront/network configuration
remains. Daily backups do not run while hosts are stopped, and S3 objects may
expire after seven days; the verified owner-controlled encrypted copy is separate.

### Final checks and closeout

Only this guide and the roadmap changed; no product, infrastructure, dependencies
or workflow code changed. Documentation checks cover relative links/anchors,
Markdown fences, whitespace, private-data patterns, scoped file changes and
unchanged Milestone 16/17 evidence. No new code regression test is appropriate
for these documentation edits, and completed benchmarks and beta tests were not
rerun. Evidence checkpoint `f61df0f` passed
[CI run 36469255477](https://github.com/Ywrd10/PairForge/actions/runs/36469255477):
365 Java, 70 frontend unit, 35 harness and 12 browser tests, with zero failures,
errors or skips. These are that checkpoint's verified results, not a claim that
the suites were manually rerun for the final documentation edits. The existing
workflow starts automatically on the final closeout push; its actual status is
reported separately. Automated CI does not replace the manual beta evidence.

Milestone 18 is **DONE** based on the owner's direct manual acceptance of the
two external testers' workflow, no reported material product bugs, passing CI
and documentation checks, and previously verified cleanup/backup/shutdown.
Incomplete retained evidence remains documented; no missing backend rows are
claimed verified. AWS stays stopped and no deployment operation was performed
during this documentation-only closeout. Optional final screenshots remain the
three real views in [SCREENSHOTS.md](SCREENSHOTS.md): synchronized Monaco views,
a successful execution with matching source/output, and dashboard/create/join
with empty invitation fields. No image was supplied or fabricated. Capture from
the final real UI and redact personal data as the screenshot guide requires.

## Completion gate

The completion gate is satisfied: the owner directly observed two external users
complete the core workflow and explicitly accepted the manual evidence; genuine
feedback is recorded; no material product fixes were required; the normal
[CI gates](TESTING.md) passed; and drain, cleanup, backup and stopped hosts were
verified. Documentation and repository consistency checks cover this final edit.
The retained-evidence limitation and optional check not performed remain explicit
in the record. [ROADMAP.md](ROADMAP.md) marks Milestone 18 DONE. No new milestone
is proposed.
