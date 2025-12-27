#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8070}"
ITEMS="${ITEMS:-500}"
RUNS="${RUNS:-3}"
POLL_INTERVAL_MS="${POLL_INTERVAL_MS:-1000}"
TIMEOUT_MS="${TIMEOUT_MS:-600000}"

if ! command -v curl >/dev/null 2>&1; then
  echo "curl not found" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
  echo "python3 not found (set PYTHON_BIN or install python3)" >&2
  exit 1
fi

now_ms() {
  if date +%s%3N >/dev/null 2>&1; then
    date +%s%3N
  else
    "${PYTHON_BIN:-python3}" - <<'PY'
import time
print(int(time.time() * 1000))
PY
  fi
}

sleep_ms() {
  local ms="$1"
  awk "BEGIN { printf \"%.3f\", ${ms} / 1000 }" | xargs sleep
}

curl_status() {
  local method="$1" url="$2"
  curl -sS -o /dev/null -w '%{http_code}' -X "$method" "$url"
}

curl_body_and_status() {
  curl -sS -w '\n%{http_code}' "$1"
}

report_json() {
  local resp body status trimmed
  resp="$(curl_body_and_status "${BASE_URL}/lab/report")"
  body="$(printf '%s' "$resp" | sed '$d')"
  status="$(printf '%s' "$resp" | tail -n 1)"

  if [[ "$status" != "200" ]]; then
    echo "failed to fetch /lab/report (status=$status)" >&2
    echo "body: $body" >&2
    exit 1
  fi

  trimmed="$(printf '%s' "$body" | tr -d ' \n\r\t')"
  if [[ -z "$trimmed" || "${trimmed:0:1}" != "{" ]]; then
    echo "invalid response from /lab/report" >&2
    echo "body: $body" >&2
    exit 1
  fi

  printf '%s' "$body"
}

report_fields() {
  local report="$1"
  local py_bin="${PYTHON_BIN:-python3}"
  command -v "$py_bin" >/dev/null 2>&1 || py_bin="python"
  command -v "$py_bin" >/dev/null 2>&1 || {
    echo "python not found" >&2
    exit 1
  }

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
  [[ "$status" == "200" ]] || { echo "failed to seed jobs (status=$status)" >&2; exit 1; }
}

reset_report() {
  local status
  status="$(curl_status POST "${BASE_URL}/lab/report/reset")"
  [[ "$status" == "204" ]] || { echo "failed to reset report (status=$status)" >&2; exit 1; }
}

run_sync() {
  while true; do
    local report pending processing retry
    report="$(report_json)"
    read -r pending processing retry < <(report_fields "$report")
    if (( pending == 0 && retry == 0 )); then
      break
    fi
    local status
    status="$(curl_status POST "${BASE_URL}/lab/run-sync")"
    [[ "$status" == "200" ]] || { echo "failed to run sync dispatch (status=$status)" >&2; exit 1; }
  done
  wait_until_done
}

run_async() {
  local status
  status="$(curl_status POST "${BASE_URL}/lab/run")"
  [[ "$status" == "200" ]] || { echo "failed to run async dispatch (status=$status)" >&2; exit 1; }
  wait_until_done
}

report_to_csv_line() {
  local run_id="$1" label="$2" report="$3"
  local py_bin="${PYTHON_BIN:-python3}"
  command -v "$py_bin" >/dev/null 2>&1 || py_bin="python"
  "$py_bin" - "$run_id" "$label" "$report" <<'PY'
import csv, io, json, sys
run_id = sys.argv[1]
label = sys.argv[2]
raw = sys.argv[3]
fields = [
  "pending",
  "processing",
  "done",
  "error",
  "retryScheduled",
  "totalProcessed",
  "totalSuccess",
  "totalErrors",
  "totalRetryScheduled",
  "totalStuckRecovered",
  "avgProcessingMs",
  "totalElapsedMs",
  "jobsPerMinute",
  "runId",
  "scenario",
  "mode",
  "items",
  "startedAtMs",
  "finishedAtMs",
  "drainTimeMs",
  "successCount",
  "failCount",
  "retryCount",
  "runDone",
]
try:
  r = json.loads(raw)
except Exception as exc:
  sys.stderr.write(f"failed to parse report json: {exc}\n")
  sys.stderr.write(f"raw: {raw}\n")
  sys.exit(1)

row = [run_id, label] + [r.get(f, "") for f in fields]
output = io.StringIO()
writer = csv.writer(output)
writer.writerow(row)
print(output.getvalue().strip())
PY
}

mkdir -p results
summary_path="results/report-summary-$(now_ms).csv"
{
  echo "run,label,pending,processing,done,error,retryScheduled,totalProcessed,totalSuccess,totalErrors,totalRetryScheduled,totalStuckRecovered,avgProcessingMs,totalElapsedMs,jobsPerMinute,runId,scenario,mode,items,startedAtMs,finishedAtMs,drainTimeMs,successCount,failCount,retryCount,runDone"
  for i in $(seq 1 "$RUNS"); do
    echo "== run $i/$RUNS (sync) =="
    reset_report
    seed_jobs
    run_sync
    report_sync="$(report_json)"
    report_to_csv_line "$i" "sync" "$report_sync"

    echo "== run $i/$RUNS (async) =="
    reset_report
    seed_jobs
    run_async
    report_async="$(report_json)"
    report_to_csv_line "$i" "async" "$report_async"
  done
} > "$summary_path"

echo "summary: $summary_path"
