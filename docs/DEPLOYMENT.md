# Milestone 15 deployment

This is the approved deployment contract and an incomplete implementation.
Milestone 15 remains TODO until actual AWS deployment, recovery, security,
capacity, and two-human acceptance checks pass. Local proxy fixtures are not
evidence that a CloudFront distribution has been deployed. No Milestone 16 load
testing is included.

## Approved environment and cost envelope

Use account `298984481596`, region `us-east-1`, and CLI profile `pairforge`.
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
`arn:aws:iam::298984481596:user/pairforge/pairforge-deployer`, account
`298984481596`, region `us-east-1`. `aws-deployment-context.ps1`
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

## Acceptance evidence still required

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

## Manual startup, shutdown, and recovery

Use only the `pairforge` AWS CLI profile in `us-east-1`. First verify STS returns
account `298984481596` and IAM user `pairforge-deployer`; never use the default
root profile. The two approved instances are app `i-089a4dc88b34e6c56` and
worker `i-06befca753a936715`. SSH administration uses the recorded EC2 Instance
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
- Created VPC `vpc-0ecfd20520993aace`, app subnet
  `subnet-0556e3a3dfd15f501`, worker subnet `subnet-02e9fb993e7bd205a`,
  Internet Gateway `igw-08fc55afd5ea009b9`, two route tables and three security
  groups in `us-east-1a`. The app has no internet default route or public ingress;
  only the worker subnet has an internet default route. Network IDs and completed
  operations are recorded in ignored `.tmp/m15-aws-state.json` for resumption.
- Created the exact CloudFront VPC origin service-linked role and free S3 gateway
  endpoint. Created `pairforge-298984481596-us-east-1-backups` and verified all four
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
`eice-0d1871924fd7471c4` was accepted but subsequently entered `create-failed`:
AWS requires the caller to have `ec2:CreateNetworkInterface` for the endpoint's
subnet, security group and new interface. Its network-interface list is empty.
Host launch was also denied on `ami-0045d7fc2ad003464`: DescribeImages verifies
Canonical owner ID `099720109477`, while the image-owner alias is `amazon`.
An account-ID IAM owner condition does not match that alias. No host was created.

The finalized compute policy pins that exact verified Ubuntu 24.04 AMI, permits
interface creation only in `subnet-0556e3a3dfd15f501` with administration security
group `sg-0aaaf1d9a141d0f66`, and includes the worker metadata-disabling permission.
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
was removed. Replacement `eice-0a7c34b7a38336c9a` also entered `create-failed`,
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
`eice-0174926142c2645df` entered `create-in-progress`; hosts remain gated on its
verified `create-complete` status. This checkpoint does not claim deployment
acceptance or change Milestone 15's TODO status.

The replacement endpoint subsequently reached **create-complete**, with interface
`eni-0628af0f62aecc5d3`. A live structural comparison confirms `v6` preserves every
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
account `298984481596`, profile `pairforge`, region `us-east-1`, and the dedicated
`pairforge-deployer` identity, then created exactly the approved hosts:

| Role | Instance | Private address | Initial available RAM / free root disk |
| --- | --- | --- | --- |
| App | `i-089a4dc88b34e6c56` (`t3a.medium`) | `10.42.1.137` | 3356 MiB / 26 GiB |
| Worker | `i-06befca753a936715` (`t3a.small`) | `10.42.2.6` | 1491 MiB / 17 GiB |

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
