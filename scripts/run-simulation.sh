#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ITEMS="${ITEMS:-500}"

POLL_INTERVAL_MS="${POLL_INTERVAL_MS:-1000}"
TIMEOUT_MS="${TIMEOUT_MS:-600000}"            # 10 min default
CURL_CONNECT_TIMEOUT="${CURL_CONNECT_TIMEOUT:-3}"
CURL_MAX_TIME="${CURL_MAX_TIME:-15}"
CURL_RETRY="${CURL_RETRY:-5}"
CURL_RETRY_DELAY="${CURL_RETRY_DELAY:-1}"

MONITOR_MEMORY="${MONITOR_MEMORY:-true}"
MONITOR_INTERVAL_MS="${MONITOR_INTERVAL_MS:-1000}"
MONITOR_METRIC_NAME="${MONITOR_METRIC_NAME:-jvm.memory.used}"
MONITOR_METRIC_TAGS="${MONITOR_METRIC_TAGS:-area=heap}"

MONITOR_PID=""

die() { echo "error: $*" >&2; exit 1; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "$1 not found"
}

is_uint() { [[ "${1:-}" =~ ^[0-9]+$ ]]; }

sleep_ms() {
  local ms="$1"
  awk "BEGIN { printf \"%.3f\", ${ms} / 1000 }" | xargs sleep
}

now_ms() {
  # GNU date supports %3N. Fallback to python if not.
  if date +%s%3N >/dev/null 2>&1; then
    date +%s%3N
  else
    "${PYTHON_BIN:-python3}" - <<'PY'
import time
print(int(time.time() * 1000))
PY
  fi
}

curl_common=(
  -sS
  --connect-timeout "$CURL_CONNECT_TIMEOUT"
  --max-time "$CURL_MAX_TIME"
  --retry "$CURL_RETRY"
  --retry-delay "$CURL_RETRY_DELAY"
  --retry-connrefused
)

curl_status() {
  # usage: curl_status METHOD URL
  local method="$1" url="$2"
  curl "${curl_common[@]}" -o /dev/null -w '%{http_code}' -X "$method" "$url"
}

curl_body_and_status() {
  # prints: body + newline + status
  curl "${curl_common[@]}" -w '\n%{http_code}' "$1"
}

start_monitor() {
  [[ "$MONITOR_MEMORY" == "true" ]] || return 0
  local label="$1"
  mkdir -p results
  local log_path="results/memory-${label}-$(now_ms).log"
  echo "memory log: $log_path"

  BASE_URL="$BASE_URL" \
    INTERVAL_MS="$MONITOR_INTERVAL_MS" \
    METRIC_NAME="$MONITOR_METRIC_NAME" \
    METRIC_TAGS="$MONITOR_METRIC_TAGS" \
    PYTHON_BIN="${PYTHON_BIN:-python3}" \
    ./scripts/monitor-memory.sh >"$log_path" 2>&1 &

  MONITOR_PID=$!
}

stop_monitor() {
  [[ -n "$MONITOR_PID" ]] || return 0
  if kill -0 "$MONITOR_PID" 2>/dev/null; then
    # tenta parar com TERM e espera um pouco
    kill -TERM "$MONITOR_PID" 2>/dev/null || true

    local start end
    start="$(now_ms)"
    while kill -0 "$MONITOR_PID" 2>/dev/null; do
      end="$(now_ms)"
      if (( end - start > 3000 )); then
        kill -KILL "$MONITOR_PID" 2>/dev/null || true
        break
      fi
      sleep 0.1
    done

    wait "$MONITOR_PID" 2>/dev/null || true
  fi
  MONITOR_PID=""
}

cleanup() {
  stop_monitor || true
}
trap cleanup EXIT INT TERM

report_json() {
  local resp body status trimmed
  resp="$(curl_body_and_status "${BASE_URL}/lab/report")"
  body="$(printf '%s' "$resp" | sed '$d')"
  status="$(printf '%s' "$resp" | tail -n 1)"

  [[ "$status" == "200" ]] || {
    echo "failed to fetch /lab/report (status=$status)" >&2
    echo "body: $body" >&2
    exit 1
  }

  trimmed="$(printf '%s' "$body" | tr -d ' \n\r\t')"
  [[ -n "$trimmed" && "${trimmed:0:1}" == "{" ]] || {
    echo "invalid response from /lab/report" >&2
    echo "body: $body" >&2
    exit 1
  }

  printf '%s' "$body"
}

report_fields() {
  # retorna "pending processing retryScheduled" numa linha
  local report="$1"
  local py_bin="${PYTHON_BIN:-python3}"
  command -v "$py_bin" >/dev/null 2>&1 || py_bin="python"
  command -v "$py_bin" >/dev/null 2>&1 || die "python not found (set PYTHON_BIN or install python3)"

  "$py_bin" - "$report" <<'PY'
import json, sys
raw = sys.argv[1]
try:
  r = json.loads(raw)
except Exception:
  sys.stderr.write("failed to parse /lab/report response\n")
  sys.stderr.write(f"raw: {raw}\n")
  sys.exit(1)

def get_int(name):
  v = r.get(name, 0)
  try: return int(v)
  except: return 0

print(get_int("pending"), get_int("processing"), get_int("retryScheduled"))
PY
}

wait_until_done() {
  local start_ms now elapsed
  start_ms="$(now_ms)"

  while true; do
    local report pending processing retry
    report="$(report_json)"
    read -r pending processing retry < <(report_fields "$report")

    if (( pending == 0 && processing == 0 && retry == 0 )); then
      break
    fi

    now="$(now_ms)"
    elapsed=$(( now - start_ms ))
    if (( elapsed > TIMEOUT_MS )); then
      echo "timeout waiting for completion after ${elapsed}ms" >&2
      echo "last report: $report" >&2
      exit 1
    fi

    sleep_ms "$POLL_INTERVAL_MS"
  done
}

seed_jobs() {
  local status
  status="$(curl_status POST "${BASE_URL}/lab/seed?items=${ITEMS}")"
  [[ "$status" == "200" ]] || die "failed to seed jobs (status=$status)"
}

reset_report() {
  local status
  status="$(curl_status POST "${BASE_URL}/lab/report/reset")"
  [[ "$status" == "204" ]] || die "failed to reset report (status=$status)"
}

run_sync() {
  while true; do
    local report pending processing retry
    report="$(report_json)"
    read -r pending processing retry < <(report_fields "$report")

    # aqui sua lógica original: enquanto existir pendente ou retry, manda dispatch sync
    if (( pending == 0 && retry == 0 )); then
      break
    fi

    local status
    status="$(curl_status POST "${BASE_URL}/lab/run-sync")"
    [[ "$status" == "200" ]] || die "failed to run sync dispatch (status=$status)"
  done

  wait_until_done
}

run_async() {
  local status
  status="$(curl_status POST "${BASE_URL}/lab/run")"
  [[ "$status" == "200" ]] || die "failed to run async dispatch (status=$status)"
  wait_until_done
}

print_report() {
  echo "report: $(report_json)"
}

main() {
  need_cmd curl
  is_uint "$ITEMS" || die "ITEMS must be an integer (got: $ITEMS)"
  is_uint "$POLL_INTERVAL_MS" || die "POLL_INTERVAL_MS must be an integer"
  is_uint "$TIMEOUT_MS" || die "TIMEOUT_MS must be an integer"

  echo "== sync =="
  reset_report
  start_monitor sync
  seed_jobs
  local start end
  start="$(now_ms)"
  run_sync
  end="$(now_ms)"
  stop_monitor
  echo "sync total_ms=$((end - start))"
  print_report

  echo "== async =="
  reset_report
  start_monitor async
  seed_jobs
  start="$(now_ms)"
  run_async
  end="$(now_ms)"
  stop_monitor
  echo "async total_ms=$((end - start))"
  print_report
}

main "$@"
