#!/usr/bin/env python3
"""Compare async vs sync flows across increasing job sizes.

Environment variables:
  BASE_URL        (default: http://localhost:8070)
  ITEMS_LIST      (default: 100,250,500)
  ITEMS_START     (optional, integer)
  ITEMS_END       (optional, integer)
  ITEMS_STEP      (optional, integer, default: 100 when using range)
  RUNS            (default: 3)
  POLL_INTERVAL_MS (default: 1000)
  TIMEOUT_MS      (default: 600000)
  OUTPUT_DIR      (default: results)
"""

from __future__ import annotations

import csv
import json
import os
import sys
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
    output_dir: str


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
    output_dir = os.getenv("OUTPUT_DIR", "results")
    if runs <= 0:
        raise ValueError("RUNS must be > 0")
    return Config(base_url, items_list, runs, poll_interval_ms, timeout_ms, output_dir)


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


def run_flow(config: Config, mode: str, items: int) -> dict[str, Any]:
    request_no_body("POST", f"{config.base_url}/lab/report/reset")
    request_no_body("POST", f"{config.base_url}/lab/seed?items={items}")
    if mode == "async":
        request_no_body("POST", f"{config.base_url}/lab/run")
    elif mode == "sync":
        request_no_body("POST", f"{config.base_url}/lab/run-sync")
    else:
        raise ValueError(f"unsupported mode: {mode}")
    return wait_until_done(config)


def ensure_output_dir(path: str) -> None:
    os.makedirs(path, exist_ok=True)


def csv_paths(output_dir: str) -> tuple[str, str]:
    timestamp = str(int(time.time() * 1000))
    detail = os.path.join(output_dir, f"flow-compare-{timestamp}.csv")
    summary = os.path.join(output_dir, f"flow-compare-summary-{timestamp}.csv")
    return detail, summary


def write_summary(detail_rows: list[dict[str, Any]], summary_path: str) -> None:
    aggregate: dict[tuple[int, str], dict[str, Any]] = {}
    for row in detail_rows:
        key = (row["items"], row["mode"])
        entry = aggregate.setdefault(key, {
            "items": row["items"],
            "mode": row["mode"],
            "runs": 0,
            "avg_drain_ms": 0.0,
            "avg_processing_ms": 0.0,
            "avg_jobs_per_min": 0.0,
            "errors": 0,
            "retries": 0,
        })
        entry["runs"] += 1
        entry["avg_drain_ms"] += float(row.get("drainTimeMs") or 0)
        entry["avg_processing_ms"] += float(row.get("avgProcessingMs") or 0)
        entry["avg_jobs_per_min"] += float(row.get("jobsPerMinute") or 0)
        entry["errors"] += int(row.get("totalErrors") or 0)
        entry["retries"] += int(row.get("totalRetryScheduled") or 0)

    for entry in aggregate.values():
        runs = entry["runs"]
        if runs > 0:
            entry["avg_drain_ms"] = round(entry["avg_drain_ms"] / runs, 2)
            entry["avg_processing_ms"] = round(entry["avg_processing_ms"] / runs, 2)
            entry["avg_jobs_per_min"] = round(entry["avg_jobs_per_min"] / runs, 2)

    with open(summary_path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=[
            "items",
            "mode",
            "runs",
            "avg_drain_ms",
            "avg_processing_ms",
            "avg_jobs_per_min",
            "errors",
            "retries",
        ])
        writer.writeheader()
        for key in sorted(aggregate.keys()):
            writer.writerow(aggregate[key])


def main() -> int:
    try:
        config = load_config()
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    ensure_output_dir(config.output_dir)
    detail_path, summary_path = csv_paths(config.output_dir)

    detail_rows: list[dict[str, Any]] = []

    with open(detail_path, "w", newline="", encoding="utf-8") as handle:
        fieldnames = [
            "run",
            "mode",
            "items",
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
            "startedAtMs",
            "finishedAtMs",
            "drainTimeMs",
            "successCount",
            "failCount",
            "retryCount",
            "runDone",
        ]
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()

        for items in config.items_list:
            for mode in ("async", "sync"):
                for run_idx in range(1, config.runs + 1):
                    print(f"== items={items} mode={mode} run={run_idx}/{config.runs} ==")
                    report = run_flow(config, mode, items)
                    row = {"run": run_idx, "mode": mode, "items": items}
                    for name in fieldnames:
                        if name in row:
                            continue
                        row[name] = report.get(name)
                    writer.writerow(row)
                    detail_rows.append(row)

    write_summary(detail_rows, summary_path)
    print(f"detail: {detail_path}")
    print(f"summary: {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
