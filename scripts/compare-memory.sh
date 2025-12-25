#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ITEMS="${ITEMS:-500}"
RUNS="${RUNS:-3}"
MONITOR_INTERVAL_MS="${MONITOR_INTERVAL_MS:-1000}"
MONITOR_METRIC_NAME="${MONITOR_METRIC_NAME:-jvm.memory.used}"
MONITOR_METRIC_TAGS="${MONITOR_METRIC_TAGS:-area=heap}"

if ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
  echo "python3 not found (set PYTHON_BIN or install python3)" >&2
  exit 1
fi

now_ms() {
  date +%s%3N
}

latest_log() {
  local pattern="$1"
  ls -t $pattern 2>/dev/null | head -n 1
}

calc_stats() {
  local file="$1"
  local py_bin="${PYTHON_BIN:-python3}"
  if ! command -v "$py_bin" >/dev/null 2>&1; then
    py_bin="python"
  fi
  "$py_bin" - "$file" <<'PY'
import sys, statistics
path = sys.argv[1]
values = []
with open(path, 'r', encoding='utf-8') as fh:
    for line in fh:
        line = line.strip()
        if not line:
            continue
        # expected format: timestamp_ms=... metric=... tags=... value=...
        parts = dict(kv.split('=', 1) for kv in line.split(' ') if '=' in kv)
        value = parts.get('value')
        if value is None:
            continue
        try:
            values.append(float(value))
        except ValueError:
            continue
if not values:
    print("count=0 min=0 max=0 avg=0 p95=0")
    sys.exit(0)
values.sort()
count = len(values)
min_v = values[0]
max_v = values[-1]
avg_v = statistics.mean(values)
p95_index = int(round(0.95 * (count - 1)))
p95_v = values[p95_index]
print(f"count={count} min={min_v} max={max_v} avg={avg_v} p95={p95_v}")
PY
}

parse_value() {
  local key="$1"
  local stats="$2"
  printf '%s\n' "$stats" | awk -v k="$key" '{for (i=1;i<=NF;i++) if ($i ~ k "=") {split($i,a,"="); print a[2]}}'
}

mkdir -p results
summary_path="results/memory-summary-$(now_ms).csv"
echo "run,mode,count,min,max,avg,p95" > "$summary_path"

for i in $(seq 1 "$RUNS"); do
  echo "== run $i/$RUNS =="
  PYTHON_BIN="${PYTHON_BIN:-python3}" \
  BASE_URL="$BASE_URL" \
  ITEMS="$ITEMS" \
  MONITOR_MEMORY=true \
  MONITOR_INTERVAL_MS="$MONITOR_INTERVAL_MS" \
  MONITOR_METRIC_NAME="$MONITOR_METRIC_NAME" \
  MONITOR_METRIC_TAGS="$MONITOR_METRIC_TAGS" \
  ./scripts/run-simulation.sh

  sync_log=$(latest_log "results/memory-sync-*.log")
  async_log=$(latest_log "results/memory-async-*.log")
  if [ -z "$sync_log" ] || [ -z "$async_log" ]; then
    echo "memory logs not found in results/" >&2
    exit 1
  fi

  sync_stats=$(calc_stats "$sync_log")
  async_stats=$(calc_stats "$async_log")

  echo "sync stats: $sync_stats"
  echo "async stats: $async_stats"

  echo "$i,sync,$(parse_value count "$sync_stats"),$(parse_value min "$sync_stats"),$(parse_value max "$sync_stats"),$(parse_value avg "$sync_stats"),$(parse_value p95 "$sync_stats")" >> "$summary_path"
  echo "$i,async,$(parse_value count "$async_stats"),$(parse_value min "$async_stats"),$(parse_value max "$async_stats"),$(parse_value avg "$async_stats"),$(parse_value p95 "$async_stats")" >> "$summary_path"

done

echo "summary: $summary_path"
