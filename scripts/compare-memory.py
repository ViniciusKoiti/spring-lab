#!/usr/bin/env python3
"""Compare async vs sync flows with memory usage tracking.

Environment variables:
  BASE_URL        (default: http://localhost:8070)
  ITEMS_LIST      (default: 100,250,500)
  ITEMS_START     (optional, integer)
  ITEMS_END       (optional, integer)
  ITEMS_STEP      (optional, integer, default: 100 when using range)
  RUNS            (default: 3)
  POLL_INTERVAL_MS (default: 1000)
  TIMEOUT_MS      (default: 600000)
  MEMORY_POLL_MS  (default: 500)
  OUTPUT_DIR      (default: results)
"""

from __future__ import annotations

import csv
import json
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


@dataclass
class Config:
    base_url: str
    items_list: list[int]
    runs: int
    poll_interval_ms: int
    timeout_ms: int
    memory_poll_ms: int
    output_dir: str


class MemoryMonitor(threading.Thread):
    """Monitors heap memory usage via Spring Boot Actuator."""

    def __init__(self, base_url: str, interval_ms: int = 500):
        super().__init__()
        self.base_url = base_url
        self.interval_ms = interval_ms
        self.stop_event = threading.Event()
        self.samples_used: list[float] = []
        self.samples_max: list[float] = []
        self.samples_committed: list[float] = []
        self.daemon = True

    def run(self) -> None:
        while not self.stop_event.is_set():
            try:
                used_data = fetch_metric(
                    f"{self.base_url}/actuator/metrics/jvm.memory.used",
                    {"area": "heap"}
                )
                max_data = fetch_metric(
                    f"{self.base_url}/actuator/metrics/jvm.memory.max",
                    {"area": "heap"}
                )
                committed_data = fetch_metric(
                    f"{self.base_url}/actuator/metrics/jvm.memory.committed",
                    {"area": "heap"}
                )

                if used_data and "measurements" in used_data and len(used_data["measurements"]) > 0:
                    self.samples_used.append(used_data["measurements"][0]["value"])
                if max_data and "measurements" in max_data and len(max_data["measurements"]) > 0:
                    self.samples_max.append(max_data["measurements"][0]["value"])
                if committed_data and "measurements" in committed_data and len(committed_data["measurements"]) > 0:
                    self.samples_committed.append(committed_data["measurements"][0]["value"])

            except Exception:
                # Ignore polling errors (e.g., server not responding)
                pass

            time.sleep(self.interval_ms / 1000.0)

    def stop(self) -> None:
        self.stop_event.set()

    def get_stats(self) -> dict[str, float]:
        if not self.samples_used:
            return {}

        # Convert bytes to MB
        used_mb = [s / 1024 / 1024 for s in self.samples_used]
        committed_mb = [s / 1024 / 1024 for s in self.samples_committed]

        stats: dict[str, float] = {
            "heap_used_min_mb": round(min(used_mb), 2),
            "heap_used_max_mb": round(max(used_mb), 2),
            "heap_used_avg_mb": round(statistics.mean(used_mb), 2),
        }

        # Calculate percentiles if we have enough samples
        if len(used_mb) >= 2:
            sorted_used = sorted(used_mb)
            p95_idx = int(len(sorted_used) * 0.95)
            p99_idx = int(len(sorted_used) * 0.99)
            stats["heap_used_p95_mb"] = round(sorted_used[p95_idx], 2)
            stats["heap_used_p99_mb"] = round(sorted_used[p99_idx], 2)
        else:
            stats["heap_used_p95_mb"] = stats["heap_used_max_mb"]
            stats["heap_used_p99_mb"] = stats["heap_used_max_mb"]

        if self.samples_max:
            stats["heap_max_mb"] = round(max(self.samples_max) / 1024 / 1024, 2)
        else:
            stats["heap_max_mb"] = 0.0

        if committed_mb:
            stats["heap_committed_min_mb"] = round(min(committed_mb), 2)
            stats["heap_committed_max_mb"] = round(max(committed_mb), 2)
            stats["heap_committed_avg_mb"] = round(statistics.mean(committed_mb), 2)
        else:
            stats["heap_committed_min_mb"] = 0.0
            stats["heap_committed_max_mb"] = 0.0
            stats["heap_committed_avg_mb"] = 0.0

        return stats


def fetch_metric(url: str, tags: dict[str, str] | None = None) -> dict[str, Any] | None:
    """Fetch metric from Actuator endpoint with optional tags."""
    if tags:
        tag_params = "&".join([f"tag={k}:{v}" for k, v in tags.items()])
        url = f"{url}?{tag_params}"

    try:
        with urllib.request.urlopen(url, timeout=3) as response:
            return json.loads(response.read().decode())
    except Exception:
        return None


def getenv_int(name: str, default: int) -> int:
    value = os.getenv(name, str(default)).strip()
    try:
        return int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer (got {value})") from exc


def parse_items_list(value: str) -> list[int]:
    items = []
    for part in value.split(','):
        part = part.strip()
        if not part:
            continue
        try:
            items.append(int(part))
        except ValueError as exc:
            raise ValueError(f"ITEMS_LIST must be comma-separated integers (got {part})") from exc
    if not items:
        raise ValueError("ITEMS_LIST must contain at least one value")
    return items


def parse_items_range() -> list[int] | None:
    start = os.getenv("ITEMS_START")
    end = os.getenv("ITEMS_END")
    if start is None or end is None:
        return None
    try:
        start_value = int(start)
        end_value = int(end)
        step_value = int(os.getenv("ITEMS_STEP", "100"))
    except ValueError as exc:
        raise ValueError("ITEMS_START/ITEMS_END/ITEMS_STEP must be integers") from exc
    if step_value <= 0:
        raise ValueError("ITEMS_STEP must be > 0")
    if end_value < start_value:
        raise ValueError("ITEMS_END must be >= ITEMS_START")
    return list(range(start_value, end_value + 1, step_value))


def load_config() -> Config:
    base_url = os.getenv("BASE_URL", "http://localhost:8070").rstrip('/')
    items_range = parse_items_range()
    if items_range is None:
        items_list = parse_items_list(os.getenv("ITEMS_LIST", "100,250,500"))
    else:
        items_list = items_range
    runs = getenv_int("RUNS", 3)
    poll_interval_ms = getenv_int("POLL_INTERVAL_MS", 1000)
    timeout_ms = getenv_int("TIMEOUT_MS", 600000)
    memory_poll_ms = getenv_int("MEMORY_POLL_MS", 500)
    output_dir = os.getenv("OUTPUT_DIR", "results")
    if runs <= 0:
        raise ValueError("RUNS must be > 0")
    return Config(base_url, items_list, runs, poll_interval_ms, timeout_ms, memory_poll_ms, output_dir)


def request(method: str, url: str, body: dict[str, Any] | None = None) -> tuple[int, str]:
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            status = resp.status
            payload = resp.read().decode("utf-8")
            return status, payload
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8")


def request_no_body(method: str, url: str) -> int:
    status, body = request(method, url, None)
    if status >= 400:
        raise RuntimeError(f"request failed {method} {url} status={status} body={body}")
    return status


def fetch_report(base_url: str) -> dict[str, Any]:
    status, body = request("GET", f"{base_url}/lab/report")
    if status != 200:
        raise RuntimeError(f"/lab/report failed status={status} body={body}")
    try:
        return json.loads(body)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"invalid JSON from /lab/report: {body}") from exc


def wait_until_done(config: Config) -> dict[str, Any]:
    start = time.time()
    while True:
        report = fetch_report(config.base_url)
        pending = int(report.get("pending", 0))
        processing = int(report.get("processing", 0))
        retry = int(report.get("retryScheduled", 0))
        if pending == 0 and processing == 0 and retry == 0:
            return report
        elapsed_ms = int((time.time() - start) * 1000)
        if elapsed_ms > config.timeout_ms:
            raise RuntimeError(f"timeout waiting for completion after {elapsed_ms}ms")
        time.sleep(config.poll_interval_ms / 1000.0)


def run_flow_with_memory(config: Config, mode: str, items: int) -> dict[str, Any]:
    """Run flow test with memory monitoring."""
    # 1. Reset metrics
    request_no_body("POST", f"{config.base_url}/lab/report/reset")

    # 2. Start memory monitor BEFORE seeding
    memory_monitor = MemoryMonitor(config.base_url, config.memory_poll_ms)
    memory_monitor.start()

    # 3. Seed jobs
    request_no_body("POST", f"{config.base_url}/lab/seed?items={items}")

    # 4. Dispatch based on mode
    if mode == "async":
        request_no_body("POST", f"{config.base_url}/lab/run")
    elif mode == "async-all":
        request_no_body("POST", f"{config.base_url}/lab/run-all")
    elif mode == "sync":
        request_no_body("POST", f"{config.base_url}/lab/run-sync")
    elif mode == "sync-all":
        request_no_body("POST", f"{config.base_url}/lab/run-sync-all")
    else:
        raise ValueError(f"unsupported mode: {mode}")

    # 5. Wait for completion (polling /lab/report)
    report = wait_until_done(config)

    # 6. Stop memory monitor
    memory_monitor.stop()
    memory_monitor.join(timeout=2.0)

    # 7. Get memory stats
    memory_stats = memory_monitor.get_stats()

    # 8. Merge report + memory stats
    return {
        **report,
        **memory_stats
    }


def ensure_output_dir(path: str) -> None:
    os.makedirs(path, exist_ok=True)


def main() -> int:
    try:
        config = load_config()
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    ensure_output_dir(config.output_dir)
    timestamp = int(time.time() * 1000)
    output_csv = os.path.join(config.output_dir, f"memory-compare-{timestamp}.csv")

    fieldnames = [
        "run", "mode", "items", "drain_ms",
        "heap_used_min_mb", "heap_used_max_mb", "heap_used_avg_mb",
        "heap_used_p95_mb", "heap_used_p99_mb",
        "heap_max_mb", "heap_committed_min_mb", "heap_committed_max_mb", "heap_committed_avg_mb",
        "success_count", "error_count", "retry_count"
    ]

    with open(output_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()

        for items in config.items_list:
            for mode in ["async", "async-all", "sync", "sync-all"]:
                for run in range(1, config.runs + 1):
                    print(f"Run {run}/{config.runs}: mode={mode}, items={items}")
                    result = run_flow_with_memory(config, mode, items)

                    row = {
                        "run": run,
                        "mode": mode,
                        "items": items,
                        "drain_ms": result.get("drainTimeMs", 0),
                        "success_count": result.get("totalSuccess", 0),
                        "error_count": result.get("totalErrors", 0),
                        "retry_count": result.get("totalRetryScheduled", 0),
                    }

                    # Add memory stats
                    for key in [
                        "heap_used_min_mb", "heap_used_max_mb", "heap_used_avg_mb",
                        "heap_used_p95_mb", "heap_used_p99_mb", "heap_max_mb",
                        "heap_committed_min_mb", "heap_committed_max_mb", "heap_committed_avg_mb"
                    ]:
                        row[key] = result.get(key, 0.0)

                    writer.writerow(row)

    print(f"\nResults saved to: {output_csv}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
