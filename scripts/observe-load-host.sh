#!/usr/bin/env bash
# Read-only bounded host observer. Run through the existing private SSH path.
set -euo pipefail
mode=${1:?observe or timings or cleanup}
role=${2:?App or Worker}
case "$role" in App) port=8082;; Worker) port=8083;; *) exit 2;; esac
if [[ "$mode" == timings ]]; then
  [[ "$role" == App && ${3:-} =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || exit 2
  docker exec pairforge-production-postgres-1 psql -X -qAt -U pairforge_bootstrap -d pairforge -v ON_ERROR_STOP=1 -c \
    "SELECT coalesce(json_agg(t),'[]'::json) FROM (SELECT id,status,extract(epoch FROM (started_at-created_at))*1000 AS queue_ms,duration_ms FROM executions WHERE room_id='$3'::uuid ORDER BY created_at LIMIT 31) t;"
  exit
fi
if [[ "$mode" == cleanup ]]; then
  [[ "$role" == Worker ]] || exit 2
  [[ -z $(docker ps -aq --filter label=io.pairforge.sandbox) ]] || exit 3
  # Worker job roots are verified separately against its configured workspace.
  printf '{"sandboxContainers":0}\n'
  exit
fi
[[ "$mode" == observe ]] || exit 2
end=$((SECONDS+900))
while (( SECONDS < end )); do
  cpu=$(vmstat -n 1 2 | tail -1 | awk '{printf "%.2f",100-$15}')
  available=$(awk '/^MemAvailable:/{printf "%.0f",$2*1024}' /proc/meminfo)
  read -r disk_total disk_available < <(df -B1 --output=size,avail / | tail -1)
  oom=$(awk '/^oom_kill /{print $2}' /proc/vmstat)
  health=$(curl -fsS --max-time 3 "http://127.0.0.1:$port/actuator/health/readiness")
  healthy=false; if [[ "$health" == *'"status":"UP"'* ]]; then healthy=true; fi
  metrics=$(curl -fsS --max-time 3 "http://127.0.0.1:$port/actuator/prometheus")
  cleanup=$(printf '%s\n' "$metrics" | awk '/^pairforge_execution_cleanup_failures_total/{n+=$NF} END{print n+0}')
  ready=0; unacked=0
  if [[ "$role" == App ]]; then
    queues=$(docker exec pairforge-production-rabbitmq-1 rabbitmqctl -q list_queues -p pairforge name messages_ready messages_unacknowledged)
    read -r ready unacked < <(printf '%s\n' "$queues" | awk '$1=="execution.jobs" || $1=="execution.events" {n++; r+=$2; u+=$3} END{if(n!=2)exit 2;print r+0,u+0}')
  fi
  printf '{"role":"%s","time":"%s","cpuPercent":%s,"availableBytes":%s,"diskTotalBytes":%s,"diskAvailableBytes":%s,"oomKills":%s,"cleanupFailures":%s,"healthy":%s,"ready":%s,"unacked":%s}\n' \
    "$role" "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$cpu" "$available" "$disk_total" "$disk_available" "$oom" "$cleanup" "$healthy" "$ready" "$unacked"
  sleep 4
done
