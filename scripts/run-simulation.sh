#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ITEMS="${ITEMS:-500}"
POLL_INTERVAL_MS="${POLL_INTERVAL_MS:-1000}"

if ! command -v curl >/dev/null 2>&1; then
  echo "curl not found" >&2
  exit 1
fi

now_ms() {
  date +%s%3N
}

report_json() {
  local resp body status trimmed
  resp=$(curl -sS -w '\n%{http_code}' "${BASE_URL}/lab/report")
  body=$(printf '%s' "$resp" | sed '$d')
  status=$(printf '%s' "$resp" | tail -n 1)
  if [ "$status" != "200" ]; then
    echo "failed to fetch /lab/report (status=$status)" >&2
    echo "body: $body" >&2
    exit 1
  fi
  trimmed=$(printf '%s' "$body" | tr -d ' \n\r\t')
  if [ -z "$trimmed" ] || [ "${trimmed:0:1}" != "{" ]; then
    echo "invalid response from /lab/report" >&2
    echo "body: $body" >&2
    exit 1
  fi
  printf '%s' "$body"
}

report_field() {
  local report_json="$1"
  local field="$2"
  local py_bin="${PYTHON_BIN:-python3}"
  if ! command -v "$py_bin" >/dev/null 2>&1; then
    if command -v python >/dev/null 2>&1; then
      py_bin="python"
    else
      echo "python3 not found (set PYTHON_BIN or install python3)" >&2
      exit 1
    fi
  fi
  "$py_bin" - "$field" "$report_json" <<'PY'
import json, sys
field = sys.argv[1]
raw = sys.argv[2]
try:
    report = json.loads(raw)
except json.JSONDecodeError:
    sys.stderr.write("failed to parse /lab/report response\n")
    sys.stderr.write(f"raw: {raw}\n")
    sys.exit(1)
print(report.get(field, 0))
PY
}

wait_until_done() {
  while true; do
    local report
    report=$(report_json)
    local pending processing retry
    pending=$(report_field "$report" pending)
    processing=$(report_field "$report" processing)
    retry=$(report_field "$report" retryScheduled)
    if [ "$pending" -eq 0 ] && [ "$processing" -eq 0 ] && [ "$retry" -eq 0 ]; then
      break
    fi
    sleep "$(awk "BEGIN { printf \"%.3f\", ${POLL_INTERVAL_MS} / 1000 }")"
  done
}

seed_jobs() {
  local status
  status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/lab/seed?items=${ITEMS}")
  if [ "$status" != "200" ]; then
    echo "failed to seed jobs (status=$status)" >&2
    exit 1
  fi
}

run_sync() {
  while true; do
    local report
    report=$(report_json)
    local pending retry
    pending=$(report_field "$report" pending)
    retry=$(report_field "$report" retryScheduled)
    if [ "$pending" -eq 0 ] && [ "$retry" -eq 0 ]; then
      break
    fi
    local status
    status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/lab/run-sync")
    if [ "$status" != "200" ]; then
      echo "failed to run sync dispatch (status=$status)" >&2
      exit 1
    fi
  done
  wait_until_done
}

run_async() {
  local status
  status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/lab/run")
  if [ "$status" != "200" ]; then
    echo "failed to run async dispatch (status=$status)" >&2
    exit 1
  fi
  wait_until_done
}

print_report() {
  local report
  report=$(report_json)
  echo "report: $report"
}

main() {
  echo "== sync =="
  seed_jobs
  local start end
  start=$(now_ms)
  run_sync
  end=$(now_ms)
  echo "sync total_ms=$((end - start))"
  print_report

  echo "== async =="
  seed_jobs
  start=$(now_ms)
  run_async
  end=$(now_ms)
  echo "async total_ms=$((end - start))"
  print_report
}

main "$@"
