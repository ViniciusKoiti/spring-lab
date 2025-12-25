package lab.springlab.enrichment.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(LabMetricsCollector.class)
public class NoopLabMetricsCollector implements LabMetricsCollector {

    private static final LabLatencyStats EMPTY_LATENCY = new LabLatencyStats(0L, 0L, 0L, 0.0, 0L);
    private static final LabMetricsSnapshot EMPTY_METRICS = new LabMetricsSnapshot(EMPTY_LATENCY, 0L, 0L, 0L);
    private static final LabRunSnapshot EMPTY_RUN = new LabRunSnapshot(null, null, null, 0, null, null, null, false);

    @Override
    public void reset() {
    }

    @Override
    public void registerSeed(int items, String scenario) {
    }

    @Override
    public void startRun(String mode, String scenario) {
    }

    @Override
    public void recordSuccess(long durationNs) {
    }

    @Override
    public void recordFail(long durationNs) {
    }

    @Override
    public void recordRetry(long durationNs) {
    }

    @Override
    public void markFinishedIfDone(boolean done) {
    }

    @Override
    public LabRunSnapshot runSnapshot(boolean done) {
        return EMPTY_RUN;
    }

    @Override
    public LabMetricsSnapshot metricsSnapshot() {
        return EMPTY_METRICS;
    }
}
