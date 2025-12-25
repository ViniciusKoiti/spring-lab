package lab.springlab.enrichment.lab;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.ConcurrentHistogram;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class DefaultLabMetricsCollector implements LabMetricsCollector {

    private static final long HIGHEST_TRACKABLE_NS = TimeUnit.MINUTES.toNanos(5);
    private static final int SIGNIFICANT_DIGITS = 3;

    private final LabRunContext runContext = new LabRunContext();
    private final ConcurrentHistogram durationHistogram =
            new ConcurrentHistogram(HIGHEST_TRACKABLE_NS, SIGNIFICANT_DIGITS);
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failCount = new LongAdder();
    private final LongAdder retryCount = new LongAdder();

    @Override
    public void reset() {
        runContext.reset();
        successCount.reset();
        failCount.reset();
        retryCount.reset();
        synchronized (durationHistogram) {
            durationHistogram.reset();
        }
    }

    @Override
    public void registerSeed(int items, String scenario) {
        runContext.registerSeed(items, scenario);
    }

    @Override
    public void startRun(String mode, String scenario) {
        runContext.startRun(mode, scenario);
    }

    @Override
    public void recordSuccess(long durationNs) {
        recordDuration(durationNs);
        successCount.increment();
    }

    @Override
    public void recordFail(long durationNs) {
        recordDuration(durationNs);
        failCount.increment();
    }

    @Override
    public void recordRetry(long durationNs) {
        recordDuration(durationNs);
        retryCount.increment();
    }

    @Override
    public void markFinishedIfDone(boolean done) {
        runContext.markFinishedIfDone(done);
    }

    @Override
    public LabRunSnapshot runSnapshot(boolean done) {
        return runContext.snapshot(done);
    }

    @Override
    public LabMetricsSnapshot metricsSnapshot() {
        ConcurrentHistogram snapshot;
        synchronized (durationHistogram) {
            snapshot = durationHistogram.copy();
        }
        long count = snapshot.getTotalCount();
        long min = count == 0 ? 0L : snapshot.getMinValue();
        long max = count == 0 ? 0L : snapshot.getMaxValue();
        double avg = count == 0 ? 0.0 : snapshot.getMean();
        long p95 = count == 0 ? 0L : snapshot.getValueAtPercentile(95.0);
        LabLatencyStats latency = new LabLatencyStats(count, min, max, avg, p95);
        return new LabMetricsSnapshot(latency, successCount.sum(), failCount.sum(), retryCount.sum());
    }

    private void recordDuration(long durationNs) {
        if (durationNs <= 0L) {
            return;
        }
        durationHistogram.recordValue(durationNs);
    }
}
