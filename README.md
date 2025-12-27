# SpringLab: Async Data Enrichment Case Study

## Problem Statement
This lab models a system that stores incomplete records and enriches them by calling an external HTTP API. The external API has variable latency, returns errors (4xx/5xx), enforces rate limits, and cannot handle high concurrent load. The solution must be asynchronous, resilient, and avoid external brokers (Kafka/RabbitMQ).

## Architecture Overview
- **Persistence**: `enrichment_job` table stores job state (`PENDING`, `PROCESSING`, `DONE`, `ERROR`, `RETRY_SCHEDULED`).
- **Scheduler**: `@Scheduled` poller claims jobs atomically and dispatches async processing.
- **Async Processing**: `@Async` + `ThreadPoolTaskExecutor` with a `Semaphore` for concurrency control.
- **Retries**: in-process retries with exponential backoff; failures become `RETRY_SCHEDULED`.
- **Stuck Recovery**: jobs in `PROCESSING` beyond a threshold are rescheduled.
- **External API**: local mock or real upstream; endpoint called via WebClient with pool + timeout.

## Why No Broker?
This lab demonstrates a durable, DB-backed async pipeline suitable for low/medium throughput without operational overhead. It highlights trade-offs: simpler infrastructure vs. limited horizontal scalability and reliance on polling.

## When Not to Use
Avoid this approach for high-volume, multi-region, or strict ordering requirements. Use a dedicated broker when you need guaranteed throughput, replay, or advanced backpressure.

## Running Locally
```bash
./gradlew bootRun
```
Swagger UI: `http://localhost:8080/swagger-ui/index.html`

## Profiles and Lab Mode
- `lab.enabled=true` enables lab-only endpoints and in-app benchmarks.
- Keep `lab.enabled=false` in non-lab environments.

## Observability (Actuator)
- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/metrics/jvm.memory.used`
- `GET /actuator/metrics/system.cpu.usage`
- `GET /actuator/prometheus`

## Lab Endpoints
- `POST /lab/seed?items=500` - create sample jobs.
- `POST /lab/run` - trigger a dispatch cycle.
- `POST /lab/run-sync` - process a dispatch cycle synchronously (for comparison).
- `GET /lab/jobs?status=PENDING&limit=50` - list jobs by status.
- `GET /lab/report` - view metrics and throughput (includes total elapsed ms).
- `POST /lab/report/reset` - reset report counters and run metadata.

### Lab Report Payload (GET /lab/report)
Fields include queue state, throughput, and run metadata:
- `runId`, `scenario`, `mode`, `items`
- `startedAtMs`, `finishedAtMs`, `drainTimeMs`, `runDone`
- `latencyNs` (count, minNs, maxNs, avgNs, p95Ns)
- `successCount`, `failCount`, `retryCount`

## Fake External API
- `POST /fake/enrich?latencyMs=200&mode=success`
- `POST /fake/enrich?mode=error500`
- `POST /fake/enrich?mode=rateLimit`
- `POST /fake/enrich?mode=timeout`
- `POST /fake/enrich?mode=random&failureRate=0.3`

## Tuning Scenarios
Adjust `lab.enrichment.max-concurrency`, `lab.enrichment.http-timeout-ms`, and `lab.enrichment.max-retries` in `src/main/resources/application.properties` to compare throughput and retry behavior.

## Flow: Manual Lab Runs (sync vs async)
1) `POST /lab/report/reset`
2) `POST /lab/seed?items=500`
3) `POST /lab/run-sync` and poll `GET /lab/report` until `runDone=true`
4) `POST /lab/report/reset`
5) `POST /lab/seed?items=500`
6) `POST /lab/run` and poll `GET /lab/report` until `runDone=true`

## Scripted Runs (CSV + charts)
Run the simulation and capture CSVs:
```bash
BASE_URL=http://localhost:8070 ITEMS=500 ./scripts/run-simulation.sh
```

This generates:
- `results/report-sync-<timestamp>.csv`
- `results/report-async-<timestamp>.csv`

Plot the CSV:
```bash
python3 scripts/plot-report.py results/report-sync-<timestamp>.csv --out results/report-sync.png
python3 scripts/plot-report.py results/report-async-<timestamp>.csv --out results/report-async.png
```

## End-to-End Benchmark Endpoint
Endpoint:
```
POST /enrich/benchmark
```

Body:
```json
{
  "items": 500,
  "mode": "async_semaphore",
  "permits": 8,
  "payloadSize": 64
}
```

Response:
```json
{
  "durationMs": 1234,
  "itemsProcessed": 500,
  "errors": 0,
  "mode": "async_semaphore",
  "permits": 8
}
```

## External Mock (WireMock)
Run a local mock upstream:
```bash
./gradlew runExternalMock -PwiremockArgs="--port=9091,--delay-ms=50,--error-rate=0.01,--payload-size=64"
```

Point the app to it:
```bash
LAB_ENRICHMENT_EXTERNAL_BASE_URL=http://localhost:9091 ./gradlew bootRun
```

## Load Testing (k6)
Run a 60s scenario with 10s ramp-up:
```bash
k6 run \
  -e BASE_URL=http://localhost:8070 \
  -e MODE=async_semaphore \
  -e PERMITS=8 \
  -e ITEMS=500 \
  -e PAYLOAD_SIZE=64 \
  -e VUS=5 \
  -e RAMP=10s \
  -e DURATION=60s \
  scripts/k6-enrich-benchmark.js \
  --out json=results/k6-async-semaphore.json
```

## JMH Benchmarks
Run:
```bash
./gradlew jmh
```

Benchmarks live in `src/jmh/java/lab/springlab/enrichment/bench/`.
