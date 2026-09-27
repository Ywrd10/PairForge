#!/usr/bin/env bash
# Run once on the application host after Flyway and API runtime grants.
# The temporary worker credential file is transferred through private SSH and
# removed by this helper even when provisioning fails.
set -euo pipefail
test "$(id -u)" = 0
credential=/home/ubuntu/worker-provision.env
test -f "$credential"
test "$(stat -c %a "$credential")" = 600
cleanup() { rm -f -- "$credential"; }
trap cleanup EXIT
if LC_ALL=C grep -q $'\r' "$credential"; then
    echo 'Worker credential file must use Unix LF line endings' >&2
    exit 1
fi
set -a
. "$credential"
set +a
compose=(docker compose --env-file /etc/pairforge/infra.env -f /opt/pairforge/current/infra/deploy/compose.yaml)
"${compose[@]}" exec -T -e WORKER_DB_USER -e WORKER_DB_PASSWORD postgres \
    psql -v ON_ERROR_STOP=1 -U pairforge_bootstrap -d pairforge \
    < /opt/pairforge/current/scripts/provision-worker.sql
unset WORKER_DB_USER WORKER_DB_PASSWORD
