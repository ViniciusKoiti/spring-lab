#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
INTERVAL_MS="${INTERVAL_MS:-1000}"
METRIC_NAME="${METRIC_NAME:-jvm.memory.used}"
METRIC_TAGS="${METRIC_TAGS:-area=heap}"
STOP_WHEN_IDLE="${STOP_WHEN_IDLE:-true}"
WAIT_FOR_ACTIVITY="${WAIT_FOR_ACTIVITY:-true}"

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

metric_json() {
  local url
  if [ -n "$METRIC_TAGS" ]; then
    url="${BASE_URL}/actuator/metrics/${METRIC_NAME}?${METRIC_TAGS}"
  else
    url="${BASE_URL}/actuator/metrics/${METRIC_NAME}"
  fi
  local resp body status trimmed
  resp=$(curl -sS -w '\n%{http_code}' "$url")
  body=$(printf '%s' "$resp" | sed '$d')
  status=$(printf '%s' "$resp" | tail -n 1)
  if [ "$status" != "200" ]; then
    echo "failed to fetch metric (status=$status)" >&2
    echo "url: $url" >&2
    echo "body: $body" >&2
    exit 1
  fi
  trimmed=$(printf '%s' "$body" | tr -d ' \n\r\t')
  if [ -z "$trimmed" ] || [ "${trimmed:0:1}" != "{" ]; then
    echo "invalid response from /actuator/metrics" >&2
    echo "body: $body" >&2
    exit 1
  fi
  printf '%s' "$body"
}

metric_value() {
  local metric_json="$1"
  local py_bin="${PYTHON_BIN:-python3}"
  if ! command -v "$py_bin" >/dev/null 2>&1; then
    if command -v python >/dev/null 2>&1; then
      py_bin="python"
    else
      echo "python3 not found (set PYTHON_BIN or install python3)" >&2
      exit 1
    fi
  fi
  "$py_bin" - "$metric_json" <<'PY'
import json, sys
raw = sys.argv[1]
try:
    metric = json.loads(raw)
except json.JSONDecodeError:
    sys.stderr.write("failed to parse /actuator/metrics response\n")
    sys.stderr.write(f"raw: {raw}\n")
    sys.exit(1)
measurements = metric.get("measurements", [])
value = None
for item in measurements:
    if item.get("statistic") == "VALUE":
        value = item.get("value")
        break
if value is None and measurements:
    value = measurements[0].get("value")
print(value if value is not None else 0)
PY
}

wait_interval() {
  sleep "$(awk "BEGIN { printf \"%.3f\", ${INTERVAL_MS} / 1000 }")"
}

main() {
  local active=0
  while true; do
    if [ "$STOP_WHEN_IDLE" = "true" ]; then
      local report pending processing retry
      report=$(report_json)
      pending=$(report_field "$report" pending)
      processing=$(report_field "$report" processing)
      retry=$(report_field "$report" retryScheduled)
      if [ "$pending" -eq 0 ] && [ "$processing" -eq 0 ] && [ "$retry" -eq 0 ]; then
        if [ "$WAIT_FOR_ACTIVITY" = "true" ] && [ "$active" -eq 0 ]; then
          wait_interval
          continue
        fi
        if [ "$active" -eq 1 ]; then
          break
        fi
      else
        active=1
      fi
    fi

    local metric value ts
    metric=$(metric_json)
    value=$(metric_value "$metric")
    ts=$(now_ms)
    echo "timestamp_ms=$ts metric=${METRIC_NAME} tags=${METRIC_TAGS:-none} value=$value"
    wait_interval
  done
}

main "$@"
