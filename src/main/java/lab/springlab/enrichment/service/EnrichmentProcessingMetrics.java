package lab.springlab.enrichment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class EnrichmentProcessingMetrics {

    private final AtomicLong totalProcessed = new AtomicLong();
    private final AtomicLong totalSuccess = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong totalRetryScheduled = new AtomicLong();
    private final AtomicLong totalStuckRecovered = new AtomicLong();
    private final AtomicLong totalProcessingMs = new AtomicLong();
    private final AtomicReference<Instant> firstProcessedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastProcessedAt = new AtomicReference<>();

    public void recordSuccess(long processingMs) {
        totalProcessed.incrementAndGet();
        totalSuccess.incrementAndGet();
        totalProcessingMs.addAndGet(processingMs);
        updateProcessedWindow();
    }

    public void recordError(long processingMs) {
        totalProcessed.incrementAndGet();
        totalErrors.incrementAndGet();
        totalProcessingMs.addAndGet(processingMs);
        updateProcessedWindow();
    }

    public void recordRetry() {
        totalRetryScheduled.incrementAndGet();
    }

    public void recordStuckRecovered(int recovered) {
        if (recovered > 0) {
            totalStuckRecovered.addAndGet(recovered);
        }
    }

    public long getTotalProcessed() {
        return totalProcessed.get();
    }

    public long getTotalSuccess() {
        return totalSuccess.get();
    }

    public long getTotalErrors() {
        return totalErrors.get();
    }

    public long getTotalRetryScheduled() {
        return totalRetryScheduled.get();
    }

    public long getTotalStuckRecovered() {
        return totalStuckRecovered.get();
    }

    public long getTotalProcessingMs() {
        return totalProcessingMs.get();
    }

    public double getJobsPerMinute() {
        Instant start = firstProcessedAt.get();
        Instant end = lastProcessedAt.get();
        if (start == null || end == null) {
            return 0.0;
        }
        long seconds = Duration.between(start, end).getSeconds();
        if (seconds == 0) {
            return 0.0;
        }
        return totalProcessed.get() / (seconds / 60.0);
    }

    public long getTotalElapsedMs() {
        Instant start = firstProcessedAt.get();
        Instant end = lastProcessedAt.get();
        if (start == null || end == null) {
            return 0L;
        }
        return Duration.between(start, end).toMillis();
    }

    private void updateProcessedWindow() {
        Instant now = Instant.now();
        firstProcessedAt.compareAndSet(null, now);
        lastProcessedAt.set(now);
    }
}
