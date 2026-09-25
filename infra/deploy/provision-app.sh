#!/usr/bin/env bash
# One-time production role setup on the application host. Credentials stay in
# root-owned environment files and are passed as environment, never argv.
set -euo pipefail
test "$(id -u)" = 0
set -a
. /etc/pairforge/api.env
set +a
export API_DB_PASSWORD="$DATABASE_PASSWORD"
compose=(docker compose --env-file /etc/pairforge/infra.env -f /opt/pairforge/current/infra/deploy/compose.yaml)
"${compose[@]}" exec -T -e API_DB_PASSWORD -e MIGRATION_PASSWORD postgres \
    psql -v ON_ERROR_STOP=1 -U pairforge_bootstrap -d pairforge \
    < /opt/pairforge/current/infra/deploy/provision-app.sql
unset API_DB_PASSWORD DATABASE_PASSWORD MIGRATION_PASSWORD
