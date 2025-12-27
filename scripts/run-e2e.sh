#!/usr/bin/env bash
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8070}
ITEMS=${ITEMS:-200}
LIMIT=${LIMIT:-20}
SLEEP_SECS=${SLEEP_SECS:-1}
MAX_WAIT_SECS=${MAX_WAIT_SECS:-120}
MODE=${MODE:-async} # async | sync

log() {
  printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"
}

if ! command -v curl >/dev/null 2>&1; then
  echo "curl not found" >&2
  exit 1
fi

log "Seeding jobs: items=${ITEMS}"
curl -sS -X POST "${BASE_URL}/lab/seed?items=${ITEMS}" >/dev/null

if [[ "${MODE}" == "sync" ]]; then
  log "Dispatching sync"
  curl -sS -X POST "${BASE_URL}/lab/run-sync" >/dev/null
else
  log "Dispatching async"
  curl -sS -X POST "${BASE_URL}/lab/run" >/dev/null
fi

log "Waiting for drain (max ${MAX_WAIT_SECS}s)"
end_time=$((SECONDS + MAX_WAIT_SECS))
while (( SECONDS < end_time )); do
  report=$(curl -sS "${BASE_URL}/lab/report")
  pending=$(printf '%s' "$report" | sed -n 's/.*"pending":\([0-9]*\).*/\1/p')
  processing=$(printf '%s' "$report" | sed -n 's/.*"processing":\([0-9]*\).*/\1/p')
  retry=$(printf '%s' "$report" | sed -n 's/.*"retryScheduled":\([0-9]*\).*/\1/p')
  if [[ -n "$pending" && -n "$processing" && -n "$retry" ]]; then
    log "pending=${pending} processing=${processing} retryScheduled=${retry}"
    if [[ "$pending" == "0" && "$processing" == "0" && "$retry" == "0" ]]; then
      log "Run drained"
      break
    fi
  else
    log "Could not parse report, raw: ${report}"
  fi
  sleep "$SLEEP_SECS"
done

log "Final report"
curl -sS "${BASE_URL}/lab/report" | sed 's/^/  /'

log "Sample jobs (limit=${LIMIT})"
curl -sS "${BASE_URL}/lab/jobs?limit=${LIMIT}" | sed 's/^/  /'
