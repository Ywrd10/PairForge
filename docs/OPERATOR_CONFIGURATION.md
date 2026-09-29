# Private deployment and benchmark configuration

Public source uses role aliases for the original deployment and execution-approved
accounts. Actual identifiers belong in ignored operator files. This does not
change backend authorization, AWS resources, benchmark measurements or Git history.

## Operator metadata

Copy [operator-config.example.json](../infra/deploy/operator-config.example.json)
to `.tmp/operator-config.json` and fill it privately from the owner's previously
approved deployment records. The empty template deliberately fails validation.
Never populate it from the currently authenticated AWS caller or benchmark users.

| Field | Required value |
| --- | --- |
| `AccountId` | The exact approved 12-digit AWS account ID. |
| `DeploymentArn` | The exact dedicated IAM user ARN, `arn:aws:iam::<AccountId>:user/pairforge/pairforge-deployer`. Root and other identities are rejected. |
| `BackupBucket` | The exact previously approved private backup bucket name. |
| `ApprovedBenchmarkUsers` | Exactly two distinct, explicitly approved account UUIDs, using canonical lowercase UUID format. No automatically approved registrations. |

The default location is ignored by `.gitignore`. To supply a different private
file, set `PAIRFORGE_OPERATOR_CONFIG` to its absolute path in the operator's
terminal. Keep that file outside tracked content, with owner-only access (0600
on Linux or a restricted owner ACL on Windows). Pass only the path through the
environment; never put passwords, sessions or tokens in this metadata file.
The PowerShell launcher passes the same environment to its Node child. The Node
entry point also reads the same default file when invoked directly.

Missing files, invalid JSON, blank values, wildcard account IDs, mismatched/root
ARNs, and missing/duplicate/expanded approval sets fail closed. The benchmark
compares both authenticated `/auth/me` IDs against the configured exact pair;
neither an arbitrary authenticated account nor the same account twice qualifies.
Configuration values are not copied to benchmark reports.

This file **does not grant execution access**. Production continues to enforce
`EXECUTION_APPROVED_USERS` in its separate private API environment before
persisting/publishing execution requests. Changing that environment still
requires explicit per-account approval and an API restart. Room membership and
the independent execution-admission marker remain required.

## Recorded resource identities

Retain the approved `.tmp/m15-aws-state.json` privately. Deployment helpers continue
to compare the returned EC2/origin/administration records to that exact state,
including role, instance type, subnet, VPC and private address where applicable.
Do not rediscover a replacement host by tags or accept a different returned ID
when a state check fails. Reconcile with the owner before operating.

Account-bound IAM ARNs use the explicitly configured account with the existing
fixed policy/role names. The one-time IAM repair scripts obtain their exact
subnet, administration security group and worker ID from the approved state;
they still require the explicit administrator-repair switch and exact
administrator identity. This is not permission to run those repairs or provision
anything. Normal operations still require profile `pairforge`, `us-east-1`, and
the exact non-root deployer; there is no default-profile fallback.

## Public evidence and history

Deployment notes and AWS result artifacts replace unnecessary identity/resource
locators with consistent aliases. Individual timings, resource samples, image/JAR
hashes, workload bounds, backup sizes/checksums, outcomes and verification evidence
are retained. Backup-object aliases remain distinct and consistent across records.
These edits do not repeat any benchmark or independently reverify a stopped host.

Earlier commits still contain the original identifiers and Git email metadata.
Current-tree sanitization is not history erasure. History rewriting and repository
visibility changes require a separate owner decision.

Offline checks: `scripts/test-operator-config.ps1`,
`scripts/test-aws-deployment-context.ps1`, and `npm run test:load` in `frontend`.
No AWS authentication or running host is needed for these checks.
