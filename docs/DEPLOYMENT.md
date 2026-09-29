# Milestone 15 deployment

Milestone 15 is complete as of 2026-09-27. The approved deployment was verified
with two authenticated people, restricted execution, recovery, backups and clean
shutdown. Both hosts are stopped; start them using the procedure below for a
supervised demo. The dated checkpoints later in this file are historical records,
not the current status. No Milestone 16 load testing is included.

## Final deployed acceptance — 2026-09-27

Live demo URL while hosts are operating:
[PairForge](https://d3pq3na8h2es74.cloudfront.net).
The owner confirmed their teammate independently authenticated and joined using
the invitation, both people edited in both directions and Java/Python results
arrived without Refresh Status. Both accounts were registered, and reload
restoration was verified by the owner. The backend allowlist remained exactly
the two individually approved test accounts; the owner verified rejection of the
second account before approval. No third account or public execution was enabled.

Java/Python success, Java compilation failure, Python runtime failure, timeout
and immediate recovery are confirmed by persisted results and reported browser
observations. The final pair of Python requests were created at 21:06:02.861743
and 21:06:04.876143 UTC. The second queued while the first was active, then ran
after it completed. Both succeeded (2645 / 2634 ms, exit 0). This verifies two
outstanding requests and the single worker's serial processing, not two
simultaneous sandboxes. Exact IDs and evidence are in
[MILESTONE_15_ACCEPTANCE.md](MILESTONE_15_ACCEPTANCE.md).

Capacity observations on the approved t3a.medium / t3a.small:

| Observation | App | Worker |
| --- | --- | --- |
| Minimum sampled available RAM, 20:50–21:05 UTC | 2580.1 MiB | 1211.4 MiB |
| Minimum sampled CPU idle in that window | 25% | 82% |
| Free root disk | 24 GiB | 15 GiB |
| Service restarts in the window | 0 | 0 |

Earlier execution samples measured Java at 36.18 MiB / 512 MiB, 14 / 128 PIDs,
and a non-terminating Python program at 3.828 MiB / 128 MiB, 2 / 32 PIDs,
each using approximately one CPU under a one-CPU limit. A successful Python
sample used 3.812 MiB / 128 MiB. Required inspected sandbox controls were present.
All post-case readiness checks passed, queues drained and no sandbox leaked.
No capacity problem required an upgrade for this demo. These are samples, not
peaks: the bounded 20:50–21:05 sampler ended before the final 21:06 request pair.
That pair has durable timing and post-case health evidence, not continuous CPU
or per-container samples. CloudWatch CPUCreditBalance reads were denied to the
deployer; permissions were not broadened. Sustained capacity remains untested.

Final pre-shutdown backup:
`postgres/2026-09-27/<backup-object-2>.dump.cms`,
16,310 bytes, S3 AES256, SHA256
`a16c5838eb63dd94bb5e060bbb254a62232f5e6b5af38073ccd01bf835c3f03d`.
Downloaded ciphertext matched metadata/checksum; offline decryption kept the
private key local. An isolated database restore passed with 1 migration,
2 users, 2 rooms, 3 memberships and 18 executions. Application-role table and
owner-room relationship reads passed. This is database/application-role recovery,
not a separately authenticated restored-API HTTP login. Temporary database and
both plaintext dumps were removed and cleanup verified. The encrypted local
recovery copy is retained in the owner-protected ignored deployment directory,
including for downtime beyond S3's seven-day retention.

Clean shutdown passed: admission closed, zero QUEUED/RUNNING rows, both queues
empty, worker stopped, fresh backup verified, app/dependency services stopped,
then both exact hosts reached stopped state. Independent EC2 reads confirmed
no public IPs, no PairForge Elastic IP allocations and no app internet default
route. Encrypted EBS volumes, backup objects and CloudFront configuration remain.
Retained storage is estimated at $4.25/month plus requests/traffic; this is not
an actual bill. The approved operating estimate is $21.70/month for 250 host
hours each. Budget alerts are configured, not a spending cap. Use the documented
manual schedule and startup/shutdown; no 24/7 availability is claimed.

Changed frontend checks previously passed: lint, typecheck, 70 unit tests,
production build and deployed origin/invitation/autofill checks. Earlier Java
verification passed 365 tests. The attempted broader local browser rerun was
blocked by Docker Desktop availability and is not represented as a pass; changed
browser flows were subsequently checked against the deployed assets and real
two-person workflow. Acceptance recording introduced no new application code,
so unrelated heavy suites were not repeated. The Monaco chunk-size warning and
documented Docker/dual-write/editor-state limitations remain. No new AWS resources
or resize was introduced during final acceptance.

## Approved environment and cost envelope

Keep the exact approved account and resource identities in ignored operator
files, as described in [OPERATOR_CONFIGURATION.md](OPERATOR_CONFIGURATION.md).
Public aliases in these records are not executable configuration. Use the
configured approved account, region `us-east-1`, and CLI profile `pairforge`.
Every provisioning/operation entry point must verify STS account and identity;
reject the root identity, a different account, or missing credentials. Root is
used only to bootstrap the dedicated IAM identity and its fixed permissions.
Never fall back to `default` or another profile.

- One x86 Linux `t3a.medium` app host, 4 GiB RAM, 30 GB encrypted gp3.
- One x86 Linux `t3a.small` worker host, 2 GiB RAM, 20 GB encrypted gp3.
- Standard CPU-credit mode, one availability zone, no instance autoscaling.
- One CloudFront distribution with its generated HTTPS hostname and VPC origin.
- One private S3 backup bucket and a free S3 gateway endpoint.
- One EC2 Instance Connect Endpoint for authorized SSH administration.
- No NAT gateway, load balancer, managed database/broker, extra instance,
  paid monitoring subscription, custom domain, or Route 53 zone.

The approved estimate uses 250 running hours per host/month: app $9.40,
worker $4.70, disks $4.00, worker IPv4 $1.25, temporary bootstrap IPv4 $0.10,
S3 allowance $0.25, and traffic allowance $2.00: **$21.70 before tax**.
An always-running 730-hour month is approximately **$51.17 before tax** under
those allowances and is not the approved operating mode. These are estimates,
not a guaranteed spending ceiling; data, requests and backup size vary.
Prices were checked against AWS pricing APIs on 2026-09-24.

Create the account-wide `PairForge-Monthly` $50 COST budget with actual-cost
email alerts at 50%, 80%, and 100%. The approved recipient is supplied at
provisioning time; do not put contact information in public source control.
Budgets are delayed notifications, not a hard cap. No budget actions or paid
budget reports are included. Do not use Cost Explorer API polling for scheduling.

Stopping both hosts removes compute and automatically assigned IPv4 usage,
but retained disks and backups continue billing (estimated $4.25/month plus
traffic). Release any temporary Elastic IP immediately after maintenance.
Complete teardown must remove the distribution/VPC origin, instances, disks,
backup objects/bucket, endpoints and network resources after the owner decides
which data must be preserved. Never delete durable data implicitly.

## Network and trust boundaries

Browsers use HTTPS/WSS to the CloudFront generated hostname. Frontend, `/api/*`
and `/ws` share that origin. CloudFront terminates TLS; its private HTTP
connection to Caddy port 8084 is **not end-to-end TLS**. This is an explicitly
approved exception for a restricted portfolio demo. Use unique demonstration
credentials rather than passwords reused elsewhere.

The app host has no public address or internet default route during operation.
Caddy binds to the app's private IPv4. Its security group accepts 8084 only
from the intended CloudFront VPC origin service security group. A separate,
random `X-PairForge-Origin` custom header authenticates this distribution to
Caddy. The token is a secret; exclude distribution configuration containing it
from logs, commits and public reports. Caddy rejects a missing token or viewer
address. CloudFront must generate `CloudFront-Viewer-Address` through the chosen
origin request policy. Caddy derives the client IP from it, overwrites forwarded
headers and removes the origin token before forwarding to the loopback API.
Tomcat trusts only the loopback proxy. Test IPv4, IPv6 and spoofed headers.

CloudFront must forward authentication, Origin and WebSocket handshake headers.
Disable caching on API and WebSocket behaviors, preserve all required methods,
and do not retry execution POSTs in application code. Management endpoints,
secrets and source files must never be reachable through the public origin.

PostgreSQL 5432 and RabbitMQ 5671 accept only the worker security group on the
app private interface (and loopback for local API traffic). Both clients verify
the private CA and certificate hostname. Redis and Actuator remain loopback-only.
The app provisions separate migrator, runtime API and worker database roles;
the worker cannot read users/password hashes or modify unrelated data. Broker
users are distinct, without management access. The shared direct exchange means
RabbitMQ resource permissions do not restrict the worker to a single routing
key; the worker host is trusted application infrastructure, unlike submissions.

The worker has no public service listener. Its automatic public IPv4 is used
for outbound package/image retrieval, not for incoming application requests.
Administration enters through the IAM-authorized EC2 Instance Connect Endpoint
and SSH keys. The worker never has an instance role. Its initial Ubuntu bootstrap
uses IMDSv2 (hop limit 1) to receive the SSH key and non-secret cloud-init config;
disabling IMDS at launch would prevent that initialization. Before installing or
starting the execution service, disable the worker metadata endpoint through EC2
and verify `HttpEndpoint=disabled`. The worker start helper also refuses startup
if metadata responds. IAM permits this disabling operation only on the tagged
worker, not re-enabling metadata. Never use the worker as the app's bastion, NAT
or package proxy.

During a controlled installation/patch window only, the app may temporarily use
an internet route and Elastic IP to fetch verified packages/images. Inbound app
access remains blocked. Remove that route and release the address before opening
the demo. Backups use the same-region S3 gateway endpoint without a NAT gateway.

## Identity and permissions

`scripts/new-deployment-policies.ps1` generates four complete JSON documents.
Supply the account, output directory, verified `ApprovedAmiId`, and the existing
`AdministrationSubnetId`/`AdministrationSecurityGroupId`. The final permissions
pin these concrete resources rather than relying on the AMI owner alias:

| Document | Permission boundary |
| --- | --- |
| `network.json` | Explicit regional EC2 reads; tagged network creation; changes/deletion of PairForge-tagged network resources; creation-time tagging only; S3 gateway endpoint only. |
| `compute.json` | Approved sizes, IMDSv2-required launches, bounded encrypted gp3, one verified Canonical AMI and tagged network; private-administration interface only in the exact app subnet/security group; disable metadata only on the tagged worker; operate owned instances; pass only the fixed backup role; private SSH tunnel; two required service-linked role types. |
| `edge-storage-budget.json` | Tagged CloudFront distribution/VPC origin, managed-policy reads, the exact backup bucket and the exact budget. |
| `app-backup.json` | Upload/abort uploads only to the bucket's `postgres/` prefix over TLS; no read, list, delete or IAM permissions. |

The deployment user has no IAM policy editing permission, arbitrary PassRole,
instance resizing, NAT gateway or managed-service permissions. These policies
do not enforce a resource-count or dollar cap. Provisioning must check existing
owned resources, reuse them by recorded identity, and refuse a third instance.
CloudFront creation-time tagging requires tag permission before IDs exist;
restrict edge mutation/tagging to the actual recorded ARNs after provisioning.
Do not silently broaden permissions to work around a denied operation.

Validate generated policies with AWS Access Analyzer and run
`scripts/test-deployment-policies.ps1`. The live simulator requires an authorized
IAM administrator; that permission is intentionally absent from the deployer.
IAM policies contain no secrets, but generated credentials do. Never copy AWS
CLI credentials to either host or a submission container.

The identity bootstrap created `pairforge-deployer`, three scoped managed
deployment policies, and the fixed `pairforge-app-backup` role/instance profile.
No access keys were created. The owner completed console password/MFA setup.
On 2026-09-24, STS verified profile `pairforge` as
`arn:aws:iam::<approved-aws-account-id>:user/pairforge/pairforge-deployer`, account
`<approved-aws-account-id>`, region `us-east-1`. `aws-deployment-context.ps1`
rejects root, a different account/user or the wrong configured region. The
create-only `new-aws-budget.ps1 -Email <actual-address>` helper checks its budget
amount, thresholds and configured recipient after creation. It successfully
created and verified `PairForge-Monthly` on 2026-09-24. This verifies configuration,
not actual email delivery or an enforced spending cap.

## Admission, operation and recovery requirements

Production execution defaults to denied for every account. After each tester
registers independently, confirm their actual account UUID out of band and add
only those UUIDs to `EXECUTION_APPROVED_USERS` in the private API environment.
Restart the API to apply changes; removing a UUID follows the same process.
Approval does not grant room membership. The backend checks membership and the
execution allowlist before persistence or RabbitMQ publication. Registration,
email text or a room invitation does not automatically approve execution.

Startup must keep admission closed until database/broker readiness, sandbox
preflight and worker readiness pass. The worker must never restart automatically:
an operator first confirms the predecessor is stopped, then uses the one-use
approval in `scripts/start-production-worker.ps1`. Existing cleanup/reconciliation
and interrupted-execution rules remain authoritative; started/terminal work is
never rerun on redelivery. PostgreSQL/RabbitMQ dual-write crash windows remain
documented MVP limitations; no outbox is introduced.

The production API checks the root-owned regular file
`/run/pairforge-operations/execution-enabled` before persisting or publishing any
execution. Missing files (including after reboot), directories and symlinks
fail closed with HTTP 503 for approved users; unapproved accounts still receive
403. Install `infra/deploy/pairforge-operations.conf` in `/etc/tmpfiles.d/`.
After privately verifying worker readiness and sandbox preflight, root may run
`set-production-admission.ps1 -Mode Open -WorkerReadinessVerified` on the app.
`-Mode Close` removes admission and restarts the API to settle requests already
past the gate before draining. A failed restart leaves admission closed.
This is an operator-controlled gate, not a continuous worker-health monitor.

Shutdown must close admission, drain accepted work, stop the worker, take and
verify an off-host database backup, then stop the app/dependencies and both EC2
instances. If draining or backup fails, report failure rather than claiming a
clean shutdown. API admission must remain closed while the worker is stopped.
The live deployment uses the manual operating procedure below. Target a
weekdays-only 12:00–20:00 America/New_York demonstration window (about 176
running hours in a 22-weekday month), leaving the remainder of the approved
250-hour monthly host allowance for testing and maintenance. This is an
operator schedule, not an automatic stop or guaranteed cost limit. Both hosts
must be stopped when no supervised demo window is planned.

Backups must be encrypted, stored off-host in the private S3 bucket, and retained
for seven days. Take a daily backup while operating and before shutdown. During
extended downtime no database changes occur and no new dump is produced; a strict
seven-day lifecycle will eventually expire the final off-host copy. Before a
shutdown longer than seven days, export a verified encrypted recovery copy to
owner-controlled storage or explicitly decide to keep only the encrypted EBS
volume. Do not imply an expired backup is restorable. Restore into an isolated
database, verify counts and application reads, then reapply restricted grants.
Never automatically restore over the active database or run Flyway clean.

The daily systemd timer is installed for 23:00 UTC with a catch-up run after
startup. It does not start a stopped EC2 host. `backup-production.ps1`
requires PowerShell 7.4+ to preserve the binary
dump, encrypts it with AES-256-GCM/CMS and the offline recipient's public
certificate, and uploads with an S3 checksum and expected bucket-owner check.
Only the public recipient certificate belongs on the app; keep the recipient
private key and private CA signing key offline. A successful upload is not
proof of a successful restore.

Local `test-deployment-storage.ps1` creates an isolated PostgreSQL fixture,
verifies TLS and hostname rejection, denies API DDL, encrypts an actual custom
format pg_dump, decrypts it and restores into a new database. It verifies the
restored row and removes its disposable resources. This proves the local dump
and cryptographic path, not S3 availability or deployed application restoration.

## Acceptance verification checklist

- Actual resource IDs, generated HTTPS URL, budget and alert configuration.
- Non-root deployment identity and effective permissions.
- Public ingress/private management checks, HTTPS/WSS and verified dependency TLS.
- Default-denied execution and two actual approved account UUIDs.
- Measured host/JVM/container memory, CPU credits, disk and sandbox bounds under
  the expected two-user workflow; report capacity problems before upgrades.
- Successful encrypted backup and isolated restore; safe shutdown/startup.
- Both humans independently register/login, create/join a room, edit together,
  execute Java/Python and receive real-time results/history.
- Complete local tests/builds and deployed checks with no unresolved failures.

Do not invent the second tester, account IDs, observed capacity or passing tests.

## Post-completion review — 2026-09-27

The milestone's deployed acceptance is retained from the real two-person test,
durable execution evidence and successful backup/restore/shutdown record above.
This review did not restart the hosts or repeat human browser acceptance.
Read-only AWS checks reconfirmed the exact two hosts stopped with no public IPs,
worker metadata disabled/no instance role, CloudFront deployed/HTTPS-only with
caching disabled and the intended VPC origin, expected security-group-only
ingress, no app internet default route or remaining Elastic IP, private S3
public-access protections, seven-day retention, encrypted final backup metadata,
and the $50 monthly budget with 50/80/100% actual-cost notifications.

One reproducibility defect was fixed: `provision-worker-production.sh` referenced
an absent `infra/deploy/provision-worker.sql`. It now reads the existing shipped
`scripts/provision-worker.sql`. The configuration check verifies both production
provisioning helpers reference SQL included in the repository release. That check
and Bash syntax validation pass. This changes a one-time setup helper, not live
roles or application behavior; it has not been reapplied to stopped hosts.

Checks rerun successfully:

- frontend lint/typecheck, all 70 unit tests and production build;
- nine focused Java tests for execution access and API/worker production
  configuration, zero failures/errors/skips;
- Maven packaging of both runnable applications (tests deliberately excluded
  from the packaging-only command, separate from the nine executed tests);
- configuration/credential isolation and provisioning-asset checks, three
  identity rejection checks, six administration readiness checks, all PowerShell
  syntax checks and corrected helper Bash syntax;
- Git whitespace validation and read-only AWS checks listed above.

Current rerun blockers: Docker Desktop's Linux engine pipe is absent.
`test-production-admission.ps1`, `test-production-worker-start.ps1` and
`test-deployment-storage.ps1` were attempted and exited nonzero before executing
their fixtures. DeploymentIT, the broader Testcontainers suite and Playwright
with real local dependencies were not rerun for the same prerequisite failure.
These are environment-blocked checks, not passing or silently skipped tests.
Earlier 365-test Java verification and actual deployed acceptance remain separate
historical evidence. No flaky test was observed in the tests executed this review.
The existing Monaco bundle warning remains. CPU credits, budget email delivery,
full restored-API HTTP login and sustained/peak load are not newly verified.

No architectural drift or future-milestone functionality was found. The modular
monolith/one worker boundary remains, code execution is confined to the dedicated
worker's disposable containers, authorization precedes persistence/publication,
and secrets are kept out of submissions and source control. The manual admission
gate/schedule, approved private HTTP origin hop, non-perfect Docker isolation,
ephemeral editor state and dual-write gaps remain explicit operating limitations.
Milestone 15 remains DONE based on deployed acceptance; this review does not
claim a fresh passing full local integration suite. Milestone 16 remains TODO.

## Manual startup, shutdown, and recovery

Use only the `pairforge` AWS CLI profile in `us-east-1`. First verify STS returns
account `<approved-aws-account-id>` and IAM user `pairforge-deployer`; never use the default
root profile. The two approved instances are app `<app-instance-id>` and
worker `<worker-instance-id>`. SSH administration uses the recorded EC2 Instance
Connect Endpoint through `scripts/invoke-deployment-ssh.ps1` and its pinned local
key/known-hosts files. Do not accept an unexpected SSH host-key change without
verifying the fingerprint through authenticated EC2 console output.
After draining and backup, `scripts/set-aws-demo-hosts.ps1 -Mode Stop
-CleanShutdownVerified` operates
only on these recorded hosts and waits for `stopped`; `-Mode Start` waits for
`running`. This helper does **not** close admission, back up data, start
services, or replace the EC2 status checks and steps below.

For startup, start only those two instances and wait for EC2 running and status
checks. Verify worker `HttpEndpoint=disabled` in EC2 before any worker start. On
the app, run `docker compose --env-file /etc/pairforge/infra.env -f
/opt/pairforge/current/infra/deploy/compose.yaml up -d`, wait for all three
healthy services, then start `pairforge-api`, `caddy`, and `backup.timer`. Verify
the API's loopback readiness endpoint and HTTPS root through CloudFront. On the
worker, confirm no predecessor is running and use root's one-use
`scripts/start-production-worker.ps1 -ConfirmedPreviousWorkerStopped`; its own
loopback readiness must be `UP`, including Docker sandbox preflight. The
root-owned `/run/pairforge-operations/execution-enabled` marker is absent after
boot. Open it with `scripts/set-production-admission.ps1 -Mode Open
-WorkerReadinessVerified` on the app only after the worker and both explicitly
approved account UUIDs are verified. With an empty allowlist, leave admission
closed. Do not enable automatic worker restart.

For shutdown, first run `set-production-admission.ps1 -Mode Close` on the app;
it removes the marker and restarts the API to settle in-flight admission checks.
Check that no `QUEUED` or `RUNNING` execution remains in PostgreSQL and that
RabbitMQ's `execution.jobs` queue has no ready or unacknowledged messages. If
either remains, continue draining or investigate; do not stop the worker and
claim a clean shutdown. Stop `pairforge-worker` and verify no PairForge sandbox
containers remain. Run `systemctl start backup.service` on the app and require
`systemctl show backup.service -p Result,ExecMainStatus` to report success; use
the deployer profile to verify the new S3 object, checksum, and AES256
server-side encryption. Then stop Caddy, API, backup timer and Compose services,
stop the two exact EC2 instances, and confirm stopped status. Verify no app
maintenance EIP/default internet route remains. If draining or backup fails,
keep admission closed and investigate before declaring a clean shutdown.

After more than seven days of downtime, the seven-day S3 lifecycle may remove
every backup. Before such a shutdown, keep a verified encrypted recovery copy
in separate owner-controlled storage or explicitly accept relying only on the
encrypted EBS volume. To restore, retrieve an S3 `.dump.cms` object to an
owner-only workspace, verify S3 checksum/encryption, decrypt with the offline
`backup.key`/`backup.crt`, restore with `pg_restore --exit-on-error` into an
isolated PostgreSQL database, check migration history and application table
reads, then reapply runtime and worker grants before reconnecting an API. Do
not place the private keys on AWS, overwrite the live database, or run Flyway
clean. Remove the plaintext dump after the check.

## Approved allowlist and restore checkpoint (2026-09-26)

Browser sign-in renewed profile `pairforge` and STS again verified the dedicated
non-root deployer in account `<approved-aws-account-id>`, region `us-east-1`. The allowlist
was changed from the owner alone to exactly both explicitly approved UUIDs.
The API restarted and readiness returned UP; worker readiness was UP. The
root-owned 0644 admission marker was opened through the documented helper.
Only those two accounts may execute; public execution remains denied.

The owner confirmed the previously unapproved second account was rejected.
They are currently operating both accounts themselves; a second person has
not performed the final acceptance. Their later checklist report retained
PASS/FAIL/output placeholders, and the actual database still had zero executions
with empty job/event queues at the correlation check. Execution and delivery
cases must remain pending until actual submissions and browser outcomes exist.

Bounded fifteen-minute resource sampling started on both existing hosts without
new infrastructure or resizing. Current samples have no execution workload:
approximately 2.6 GiB available app RAM / 1.2 GiB worker RAM, zero service
restarts, and 24/15 GiB free root disk. These are baseline observations, not proof
of two-user execution capacity. Sampling output contains only resource/health
data and selected sandbox controls, never environment secrets or submitted source.

The encrypted backup at `postgres/2026-09-26/164547-*` is 14,602 bytes. S3 reports
AES256 encryption and SHA256 `8ac24ac69f46e48d23ade1fc358478d62446b919760bbc08b479e3f5685dd129`,
matching the downloaded ciphertext. Decryption used the offline key, which was
not transferred to AWS. Local Docker was unavailable; instead, restoration used
an isolated temporary database on the existing app host. `pg_restore` succeeded,
yielding one migration, two users, two rooms and three memberships. Restored
API-role table and owner-room relationship reads passed. This is application-role
data access, not a full restored-API HTTP login test. The temporary database and
remote/local plaintext dumps were removed and independently checked; production
readiness remained UP. Retained ciphertext is owner-protected. A final fresh
backup before shutdown is still required.

Follow-up at 2026-09-27 01:01 UTC: real Java execution
`a0cab0e0-4fd5-4efd-b3b4-35aea9122b21` succeeded with exit 0, stdout
`PairForge Java OK`, empty stderr and duration 4234 ms. The owner reports the
result appeared in both accounts; the database independently confirms it.
A running-container sample recorded 36.18 MiB against the 512 MiB Java limit,
14 processes against 128 and approximately one CPU in use. Required sampled
sandbox controls were present, worker service restarts remained zero, readiness
was UP after completion, queues drained and the sandbox was removed. This is
sampled evidence for one successful Java case, not peak or sustained capacity.
See [MILESTONE_15_ACCEPTANCE.md](MILESTONE_15_ACCEPTANCE.md) for remaining cases.

Milestone 15 remains TODO. Python execution/error/timeout/recovery results,
browser delivery/reload observations, a second human, workload capacity and final
clean shutdown/stopped-host verification remain outstanding. No final acceptance
commit has been made and no Milestone 16 work was introduced.

## Two-account collaboration and owner approval (2026-09-25)

Follow-up: the owner confirmed the second account's non-allowlisted execution
rejection and explicitly approved its UUID. The attempt to apply that approval
stopped at the expired AWS CLI session's STS identity check, before any remote
mutation. Browser reauthentication is pending. The intended final allowlist is
exactly the two approved accounts; the deployed configuration still contains
only the first account and admission remains closed at this checkpoint.

The owner reports that both accounts independently registered/authenticated,
joined one room and synchronized code edits in both directions. A read-only
database check confirms exactly two registered accounts and one room with both
members. This is user-reported collaboration evidence plus database membership;
the second account's operator still needs confirmation for the two-person test.

After explicit approval, only the owner's UUID was added to the private API
allowlist. The API was restarted and readiness returned UP. The second account
was identified from the recent registration and its UUID reported for separate
approval; it has not been allowlisted. The execution admission marker remains
absent, executions count is zero, and both RabbitMQ execution queues report zero
ready and unacknowledged messages. No other accounts or public execution were
authorized. Account emails/UUIDs and credentials are not recorded here.

The remaining supervised cases and sample programs are in
[MILESTONE_15_ACCEPTANCE.md](MILESTONE_15_ACCEPTANCE.md). They cover reconnect,
Java/Python success, compilation/runtime errors, timeout/recovery, both browsers'
live results, backend denial, and measurements during the expected workload.
Use the existing second account for the denial check before approving it, if
practical. Remaining execution, capacity, restored-data and final shutdown checks
are pending. Milestone 15 stays TODO; no infrastructure was added or resized.

## Owner registration and invitation readiness (2026-09-25)

The owner reports successful deployed registration/login, dashboard access,
room creation and opening Monaco. A read-only lookup by the supplied email
identified their real account UUID; no password hashes were queried. Execution
allowlist and admission were verified empty/closed and remain unchanged pending
explicit approval. Personal account identifiers are not recorded in this runbook.

The join form and both admission inputs now disable autocomplete and use named
text fields, with capitalization/spellcheck disabled. Removing the password
input avoids presenting a username/password pair to browser credential managers.
The HTML configuration and initially empty fields are testable; individual
third-party password-manager overrides are not a universal browser guarantee.

After creating a new room, the owner can select **Copy invitation** on the
dashboard or the room page to copy its room ID and token. This newly issued
token stays only in the authenticated tab's memory; reload, logout or expiry
clears it. Share the copied fields privately with the teammate, who independently
registers/logs in and uses **Have an invitation?** on the dashboard. No token is
placed in a URL, browser history or durable browser storage. Previously created
rooms whose token was not saved show recovery guidance: use the saved token or
create a new room and save its invitation. No token rotation/retrieval API was
added; the server continues to store only a hash.

The static update is deployed. Frontend lint, typecheck, all **70 unit tests**,
production build and whitespace checks pass. Tests cover copying after room
navigation, clipboard-failure fallback, join-body submission, and invitation
clearing on logout/expiry/new sessions. An initial new test had a mock-response
ordering issue; waiting for the initial room list corrected that test setup.
A browser check of deployed assets (all auth/room APIs and WebSockets intercepted
with fixture responses) verifies join-field semantics, copying in the room UI,
and logout clearing; it does not represent a real second tester. The clipboard
check waits for completion and normalizes Windows line endings. The real
registration form also reaches the live HTTPS API and rejects invalid input
with HTTP 400 without creating a synthetic account. No unrelated heavy suites
were rerun for this frontend-only update; the previously documented local Docker
blocker remains separate. The existing Monaco bundle-size warning remains.

The second tester can register at
`https://d3pq3na8h2es74.cloudfront.net/register`. Both testers must receive
explicit UUID approval before execution. Milestone 15 remains TODO pending
the two-real-account execution/collaboration workflow, capacity measurements,
restored-data check and final clean shutdown. No Milestone 16 work was added.

## Frontend registration repair (2026-09-25)

The owner's first registration attempt exposed a release defect: the frontend
bundle defaulted to `http://127.0.0.1:8080`, sending requests to the visitor's
computer. The initial page-render/direct-API smoke test did not exercise the
form's configured API URL and therefore missed it. That attempt could not reach
the deployed API; it is not a successful registration or acceptance result.

Production builds now default to the page origin for API requests and derive
WSS from that HTTPS origin. Local development keeps its separate loopback API;
an explicit `VITE_API_BASE_URL` remains available for local browser fixtures.
The AWS release must be built without that fixture override. After browser
tests, rebuild with the production environment before packaging `frontend/dist`:
the browser-test build deliberately targets its local fixture API.

The repaired production bundle was transferred over private authenticated SSH,
its SHA256 verified, assets installed, and `index.html` replaced atomically.
Static files/directories were also restricted to root ownership and 644/755
permissions. Existing hashed assets were retained for already-open pages.
Users with the earlier page open must reload before registration.

Frontend lint, typecheck, all 67 unit tests (including two origin regressions)
and production build passed. The new deployed smoke check submits an invalid
email through the actual registration form: it reaches the CloudFront HTTPS
`/api/auth/register` endpoint and receives HTTP 400 without creating an account.
WSS again opens and rejects anonymous STOMP with code 1008. The broader
browser-suite retry was blocked because the local Docker daemon was stopped;
starting Docker Desktop reproduced its inaccessible `dockerInference` runtime
socket startup error. No factory reset or Docker data deletion was performed.
This does not constitute a passing or skipped suite. Real successful registration,
authenticated WSS and the two-person acceptance workflow are still pending.

## Registration restart checkpoint (2026-09-25, 13:53–13:56 UTC)

The deployment/documentation checkpoint was committed as `42884bf` before
restarting either host. After renewing the session, STS verified account
`<approved-aws-account-id>`, profile `pairforge`, region `us-east-1`, and IAM user
`arn:aws:iam::<approved-aws-account-id>:user/pairforge/pairforge-deployer`.

- The same two approved hosts passed EC2 status checks. The app has no public
  address or internet default route; worker IMDS remains disabled with no IAM
  role. The existing security-group restrictions and deployed CloudFront
  distribution were rechecked; no additional infrastructure was created.
- PostgreSQL, Redis and RabbitMQ report healthy. API and worker readiness are
  `UP`; live API/worker PostgreSQL and RabbitMQ connections use TLS 1.3. The
  worker start helper passed its metadata guard and sandbox readiness checks.
- The HTTPS registration page renders without JavaScript errors. An empty
  registration request returns 400; no test account was created. Anonymous
  rooms access returns 401, public management access returns 404, and the
  private origin without its secret returns 403. WSS opens through CloudFront
  and closes unauthenticated STOMP with policy code 1008.
- The execution allowlist is empty and the admission marker is absent. Both
  execution queues have zero ready/unacknowledged messages. Each real tester
  must independently register and receive explicit owner approval for their
  UUID before execution is enabled. Authenticated collaboration/execution is
  still pending, not implied by the transport checks above.
- Idle baseline: app 2747 MiB available RAM and 24 GiB free root disk; worker
  1312 MiB available RAM and 15 GiB free root disk. A one-second CPU sample was
  100% idle on the app and 99% idle on the worker; these short idle samples are
  not workload-capacity evidence. Worker service memory was 382,533,632 bytes
  with zero service restarts at the subsequent sample.

Both hosts are running for the requested supervised registration/acceptance
window at this checkpoint. The owner was given
`https://d3pq3na8h2es74.cloudfront.net/register`; no account UUID has been
approved. Keep Milestone 15 TODO until the real two-person workflow, workload
measurements, restored-data read, and documented clean shutdown pass. The
final acceptance commit remains pending. No Milestone 16 work was performed.

## Live deployment checkpoint (2026-09-25)

- The dedicated `pairforge` IAM user was verified before provisioning. The
  approved two hosts, private VPC origin `vo_4h8eX1IJFG5G3HJL0uxaSK`, and
  CloudFront distribution `E3LQ3DV4SPTWHU` are deployed. The HTTPS site is
  `https://d3pq3na8h2es74.cloudfront.net`. CloudFront-to-Caddy remains the
  approved **private HTTP** exception; browser HTTPS is not end-to-end TLS.
- The app has no public IP or default internet route. Its temporary download
  EIP was released after pulling images. Only the CloudFront origin security
  group reaches Caddy; only the worker group reaches PostgreSQL 5432 and
  RabbitMQ 5671; SSH is limited to the administration endpoint group. No
  database, Redis, broker-management, Actuator, or Docker listener is public.
- PostgreSQL 17.11, Redis 7.4.11 and RabbitMQ 4.1.8 are healthy on the app.
  The API is healthy with Flyway v1 and restricted runtime grants. The isolated
  worker is healthy after Java/Python digest-pinned sandbox preflight, verified
  TLS to PostgreSQL/RabbitMQ, and worker-only database grants. Worker IMDS is
  **disabled** in applied EC2 settings; its token endpoint returns an `EC2ws`
  403. The startup helper now accepts that exact denial and still rejects
  reachable metadata, unrelated 403 responses and uncertain errors.
- The public root returned 200, unauthenticated `/api/rooms` returned 401,
  public `/actuator/health` returned 404, and direct origin requests lacking
  the secret returned 403. This does not yet prove authenticated WSS or the
  full two-person workflow. Execution allowlist is empty and admission is closed.
- Idle app resources: 2673 MiB available RAM, 24 GiB free disk, two vCPUs;
  idle worker: 1248 MiB available RAM, 15 GiB free disk, two vCPUs. The expected
  two-user and sandbox workload still needs measurement.
- The first real database backup reached the private S3 bucket with SHA256
  checksum and AES256 server-side encryption. Its CMS ciphertext decrypted
  with the offline key and restored without error into a network-isolated local
  PostgreSQL 17.11 container. The restored Flyway history count was 1 and
  `users` count was 0; the local plaintext dump/container were removed. The
  daily 23:00 UTC timer is enabled. An application-level restored-data read
  after actual user data exists is still pending.
- The $50 budget and seven-day `postgres/` lifecycle are live. Billing's
  reported actual cost was $0 at this checkpoint, which may lag usage. The
  $25/$40/$50 actual-cost alert thresholds were rechecked.
- The manual shutdown/restart rehearsal passed: admission closed, both broker
  queues had zero ready/unacknowledged messages, the worker stopped with no
  sandbox containers, a fresh pre-shutdown encrypted S3 object and checksum
  were verified, and both hosts reached `stopped`. After restart, both EC2
  status checks passed; the worker SSH host key persisted, IMDS remained
  disabled, the worker did not auto-start, both services returned to readiness
  `UP`, the public site returned 200, and admission remained closed. The app
  still had no public IP, default internet route or maintenance EIP.
- The current candidate passed Maven `clean verify` with 365 tests, zero
  failures/errors/skips; frontend lint, typecheck, 65 unit tests, production
  build and 12 real-browser tests. The deployment credential, IAM identity,
  operator admission, worker metadata, and encrypted-storage fixtures also
  passed; all 41 PowerShell scripts and the new Bash helpers parse. The live
  WSS handshake opened through CloudFront and unauthenticated STOMP was closed
  with policy code 1008.
- The two-person deployed workflow, restored-data application read after real
  user data, and execution limits/capacity under the expected two-user workload
  remain pending. No real account has registered yet and the owner has not
  designated the second tester. The site was made available and recovered after
  the startup rehearsal, then both hosts were **stopped** after a second clean
  drain and verified encrypted S3 backup at `postgres/2026-09-25/050823-*`.
  The assigned CloudFront URL returns an origin error during this approved
  downtime. Start the same hosts and verify health before inviting registration;
  do not invent tester identities or UUIDs. **Milestone 15 remains TODO.**

## Local verification checkpoint (2026-09-24)

- Maven `clean verify`: 364 tests, zero failures/errors/skips. A subsequent
  focused run of all five `DeploymentIT` tests also passes after adding IPv6
  and forged viewer-address assertions.
- Frontend lint, typecheck, 65 unit tests, production build and 12 real-browser
  regression tests pass. These are local fixtures, not the two-human AWS test.
- Four generated IAM policy documents have zero Access Analyzer findings;
  18 positive/negative IAM simulations pass. No billable resources were created.
- Credential isolation/default-deny checks, non-root identity rejection checks,
  59 existing CI helper checks, PowerShell syntax, Actionlint and Compose syntax
  pass. The pinned Linux storage fixture passes verified PostgreSQL TLS,
  hostname mismatch rejection, API DDL denial, encrypted dump and isolated restore.
- The origin authorization test initially caught Caddy directive ordering;
  explicit `route` ordering corrected it. Certificate fixture runs caught and
  corrected PowerShell executable-name resolution and absolute-path issues.
- Console password/MFA and a verified non-root `pairforge` session remain
  pending. Budget creation, AWS resources, production RabbitMQ TLS/role checks,
  actual capacity, S3 backup/restore, operating schedule enforcement and human
  acceptance remain unverified. The new S3 upload helper has not run against AWS.
- Changes are uncommitted; GitHub Actions has not run this candidate.

These results do not satisfy the deployment milestone's acceptance criteria.

## Resumed deployment checkpoint (2026-09-24)

- Verified the non-root `pairforge-deployer` CLI identity and configured region.
- Created and verified the approved monthly budget, all three thresholds and
  their configured recipient; no contact address is stored in this repository.
- Created VPC `<vpc-id>`, app subnet
  `<app-subnet-id>`, worker subnet `<worker-subnet-id>`,
  Internet Gateway `<internet-gateway-id>`, two route tables and three security
  groups in `us-east-1a`. The app has no internet default route or public ingress;
  only the worker subnet has an internet default route. Network IDs and completed
  operations are recorded in ignored `.tmp/m15-aws-state.json` for resumption.
- Created the exact CloudFront VPC origin service-linked role and free S3 gateway
  endpoint. Created `<private-backup-bucket>` and verified all four
  public-access blocks, bucket-owner-enforced ownership, AES256 server-side
  encryption, TLS-only access, seven-day `postgres/` retention, and private-endpoint
  enforcement for uploads by the app backup role. The bucket is empty; actual
  encrypted database backup and restore remain unverified.
- No CloudFront distribution, EC2 instances, EBS volumes or allocated public IPs
  have been created. There are no PairForge compute/EBS/public-IPv4 running charges
  at this checkpoint. S3 requests can incur small charges and stored backups will
  incur storage charges; unrelated account spending has not been measured.
- Private administration endpoint creation failed because AWS requires the
  case-sensitive role name `AWSServiceRoleForEc2InstanceConnect`; the original
  IAM policy used `AWSServiceRoleForEC2InstanceConnect`. The generator is fixed,
  and the IAM administrator must apply that exact ARN correction to
  `PairForge-compute`. The deployer cannot edit its own policy. Do not switch the
  deployment profile back to root to bypass this boundary.
- `new-aws-network.ps1` resumes recorded network operations. The prepared
  `new-aws-hosts.ps1` requires that network, launches only the two approved types,
  uses deterministic client tokens, standard CPU credits, encrypted bounded gp3,
  retained durable disks, required IMDSv2 and disabled worker metadata. It refuses
  a third host. It has not yet been executed against AWS.
- Added a production admission marker and operator helper; tests prove rejection
  before execution persistence, immediate closing without application changes,
  readiness/explicit-operator requirements, and closed admission after a failed
  restart. The helper's Linux test injects systemd/health failures; actual host
  operation remains unverified.
- Fresh Maven `clean verify`: **365 tests, zero failures/errors/skips**. Fresh
  frontend lint, typecheck, **65 unit tests**, production build, report validation,
  PowerShell parsing and Actionlint pass. The existing Monaco chunk-size warning
  remains informational. All **12 browser regression tests** and browser report
  validation pass without retries.
- Two new IAM simulation cases cover the exact service-role ARN and its incorrect
  capitalization. They require an IAM administrator and have not been run; the
  earlier 18 simulations did not cover this service-linked-role case.

Milestone 15 remains TODO. Deployed HTTPS/WSS, dependency TLS and role checks,
capacity, S3 backup/restore, scheduled operation and two-human acceptance remain
outstanding. No Milestone 16 work is included.

## IAM correction follow-up (2026-09-24)

The owner applied the service-linked-role capitalization correction. Endpoint
`<eice-resource-13>` was accepted but subsequently entered `create-failed`:
AWS requires the caller to have `ec2:CreateNetworkInterface` for the endpoint's
subnet, security group and new interface. Its network-interface list is empty.
Host launch was also denied on `<ami-id>`: DescribeImages verifies
Canonical owner ID `099720109477`, while the image-owner alias is `amazon`.
An account-ID IAM owner condition does not match that alias. No host was created.

The finalized compute policy pins that exact verified Ubuntu 24.04 AMI, permits
interface creation only in `<app-subnet-id>` with administration security
group `<admin-security-group-id>`, and includes the worker metadata-disabling permission.
The administrator must apply the full corrected compute policy. These actions
do not give the deployer IAM editing, arbitrary instance types or other VPCs.
The generated policy awaits application and live verification; do not describe
it as verified by the earlier simulator runs.

Provisioning now validates asynchronous endpoint readiness and exact subnet/
security-group identity before any host launch. Six local failure/success checks
pass for this guard; the three existing identity rejection checks still pass.
The failed empty endpoint must be removed and recreated after the correction.
The encrypted S3 bucket, budget and existing network remain in place.

AWS references for these corrections: [Instance Connect Endpoint permissions](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/permissions-for-ec2-instance-connect-endpoint.html),
[AMI owner-condition examples](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ExamplePolicies_EC2.html),
and [IMDS launch implications for SSH keys and user data](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-IMDS-new-instances.html).

After the full compute-policy replacement, the original failed empty endpoint
was removed. Replacement `<eice-resource-16>` also entered `create-failed`,
this time on authorization for the new `network-interface/*` resource. The
subnet condition on that not-yet-created resource was removed from the generator;
authorization remains bounded by the separate required exact subnet/security-group
resources. A structural comparison confirms that only this statement changes.
The one-time `repair-deployment-interface-policy.ps1` helper can apply exactly
that condition removal, preserving other active policy content. It requires
explicit approval to use the `default` administrator profile and refuses unexpected
account, policy content, concurrent edits or a full policy-version history. It
does not provision infrastructure or change the `pairforge` profile. Approval or
manual application of this final interface correction is pending. No EC2 hosts
have been launched.

The owner subsequently approved the one-time administrator condition repair.
It was applied as compute policy version `v5` and verified, after which all
provisioning resumed through `pairforge`. A further endpoint attempt failed on
`ec2:CreateTags` for its generated interface. Decoding AWS's authorization failure
showed the new-interface preflight context has literal `ec2:NetworkInterfaceID=*`.
The proposed additional statement uses `StringEquals` for that literal value;
read-only IAM simulations allow this context and deny an existing `eni-*` ID.
This is a preflight permission, not permission to retag existing interfaces.

Automatic approval review rejected applying this new statement and removing an
obsolete non-default policy version to free a version slot: it treated the prior
approval as covering only the condition repair. Neither rejected mutation ran.
`repair-deployment-interface-tags.ps1` is prepared to preserve the old version
locally, retain the active version and every existing permission, and add only
the scoped statement. Explicit approval for those two actions is pending.
Actual endpoint success, host launch, and deployed acceptance remain unverified.

The owner then explicitly approved both scoped IAM changes. The helper archived
the oldest non-default version locally and applied the creation-only tag statement
as compute policy `v6`. Subsequent provisioning verified and used only
`pairforge-deployer`. The failed empty endpoint was deleted, and replacement
`<admin-endpoint-id>` entered `create-in-progress`; hosts remain gated on its
verified `create-complete` status. This checkpoint does not claim deployment
acceptance or change Milestone 15's TODO status.

The replacement endpoint subsequently reached **create-complete**, with interface
`<eni-resource-18>`. A live structural comparison confirms `v6` preserves every
`v5` statement and adds only the approved tag statement. All three local identity
rejection checks and six endpoint readiness checks pass.

The next host launch, through the verified deployment identity, was rejected by
EC2 with `InvalidParameterCombination`: the specified instance type is not
eligible for Free Tier. This indicates an account-plan restriction; the owner
must confirm the plan in Billing before proceeding. No automatic billing-plan
upgrade or substitution of instance types was attempted. A subsequent inventory
verified zero PairForge instances, EBS volumes and Elastic IP allocations.

To retain the approved `t3a.medium` / `t3a.small` topology, the owner can review
AWS Console **Upgrade plan → Upgrade account** if the account uses the Free plan.
The Paid plan allows pay-as-you-go charges beyond eligible credits; budget alerts
do not cap spending. This account-wide change needs the owner's decision. See
[AWS account-plan guidance](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html).
If the account already shows Paid plan, investigate the restriction rather than
changing the deployment sizes or broadening IAM permissions. Milestone 15 remains
TODO; the actual hosts, application, backups/restore and human acceptance are
still outstanding.

## Host bootstrap checkpoint (2026-09-24)

The owner confirmed upgrading the account plan. Provisioning again verified
account `<approved-aws-account-id>`, profile `pairforge`, region `us-east-1`, and the dedicated
`pairforge-deployer` identity, then created exactly the approved hosts:

| Role | Instance | Private address | Initial available RAM / free root disk |
| --- | --- | --- | --- |
| App | `<app-instance-id>` (`t3a.medium`) | `<app-private-ip>` | 3356 MiB / 26 GiB |
| Worker | `<worker-instance-id>` (`t3a.small`) | `<worker-private-ip>` | 1491 MiB / 17 GiB |

These are idle measurements before application installation, not acceptance
under a two-user workload. AWS verified encrypted 30/20 GB gp3 disks, baseline
3000 IOPS / 125 MiB/s, standard CPU credits, required IMDSv2 with hop limit one,
the app-only backup instance profile and no worker IAM role. Cloud-init completed
on both hosts. SSH uses the private endpoint; both Ed25519 host fingerprints
matched authenticated EC2 console output before transferring any credentials.

The administration key had been created under the local sandbox Windows account,
which prevented the normal owner account from using it. Its ownership and ACL
were corrected to the owner alone without replacing or printing the key. Run
key/configuration generation and deployment administration under the same actual
Windows user, outside a separate sandbox identity.

Both hosts now have OpenJDK 21.0.12.1, Docker 29.1.3 with cgroup v2 and retained
seccomp/AppArmor, and PowerShell 7.6.6. App bootstrap also installed Caddy 2.11.4
from its pinned image and AWS CLI 2.37.1. Ubuntu 24.04 lacked an `awscli` apt
candidate; the helper now verifies AWS's official installer against the published
signing fingerprint and renewed public key. A fresh host verification passed
the signature check without an expired-key status. A keyserver initially supplied
an older expired key; bootstrap now embeds the current public signing key and
explicitly rejects expired/revoked/invalid signature status.

`set-aws-app-maintenance.ps1` records and safely reuses a temporary tagged Elastic
IP, permits only the expected app internet default route, and never opens inbound
security groups. Its live Open/Close cycle passed: installation finished, the
app's internet default route was removed, and its temporary Elastic IP released.
The helper refuses unexpected route targets or addresses assigned to another
host. Do not open this maintenance window while accepting demo traffic.

Worker metadata disabling is blocked by a mistake in the prepared IAM policy:
AWS's decoded request context reports `ec2:MetadataHttpEndpoint=enabled` as the
current state and `ec2:Attribute/HttpEndpoint=disabled` as the requested change.
The policy incorrectly tested the former. The prepared
`repair-deployment-metadata-policy.ps1` changes only this statement, pins it to
the exact worker instance, and permits only the `HttpEndpoint` attribute with
requested value `disabled`. Four live read-only IAM simulations pass: disabling
this worker is allowed; enabling metadata, additional attributes and another
instance are denied. The generator uses the corrected request condition too.
Applying the live repair requires the owner's separate administrator approval;
the earlier interface repair approval does not cover it. No live metadata-policy
change has been applied, and execution must remain disabled until the real
metadata change and worker preflight are verified.

Three identity rejection and six administration readiness checks still pass;
all PowerShell scripts parse. Application code did not change during this
bootstrap continuation, so the earlier Java/frontend results were not rerun or
represented as deployed acceptance. No production database, user data, execution
service, CloudFront distribution or public application URL exists yet. Both
hosts are verified **stopped**, with no public addresses, while approval is
pending; encrypted disks are retained (approximately $4/month). AWS also verified
zero remaining PairForge Elastic IP allocations and no app internet default route.
Database backup before shutdown is not yet applicable because no
database has been initialized. Milestone 15 remains TODO.
