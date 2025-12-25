package lab.springlab.enrichment.lab;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public class LabRunContext {

    private static final String DEFAULT_SCENARIO = "default";
    private final AtomicLong runSequence = new AtomicLong();

    private String runId;
    private String scenario = DEFAULT_SCENARIO;
    private String mode;
    private int items;
    private Long startedAtMs;
    private Long finishedAtMs;

    public synchronized void reset() {
        runId = null;
        scenario = DEFAULT_SCENARIO;
        mode = null;
        items = 0;
        startedAtMs = null;
        finishedAtMs = null;
    }

    public synchronized void registerSeed(int items, String scenario) {
        ensureRunInitialized(scenario);
        this.items = items;
    }

    public synchronized void startRun(String mode, String scenario) {
        if (startedAtMs != null && finishedAtMs == null) {
            reset();
        }
        ensureRunInitialized(scenario);
        this.mode = mode;
        if (startedAtMs == null) {
            startedAtMs = Instant.now().toEpochMilli();
        }
    }

    public synchronized void markFinishedIfDone(boolean done) {
        if (done && finishedAtMs == null && startedAtMs != null) {
            finishedAtMs = Instant.now().toEpochMilli();
        }
    }

    public synchronized LabRunSnapshot snapshot(boolean done) {
        Long drainTimeMs = null;
        if (startedAtMs != null && finishedAtMs != null) {
            drainTimeMs = finishedAtMs - startedAtMs;
        }
        return new LabRunSnapshot(runId, scenario, mode, items, startedAtMs, finishedAtMs, drainTimeMs, done);
    }

    private void ensureRunInitialized(String scenario) {
        if (runId == null || finishedAtMs != null) {
            runId = "run-" + runSequence.incrementAndGet();
            this.scenario = scenario == null ? DEFAULT_SCENARIO : scenario;
            mode = null;
            items = 0;
            startedAtMs = null;
            finishedAtMs = null;
        } else if (scenario != null) {
            this.scenario = scenario;
        }
    }
}
