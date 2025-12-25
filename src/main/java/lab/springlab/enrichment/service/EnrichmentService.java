package lab.springlab.enrichment.service;

import lab.springlab.enrichment.client.ExternalEnrichmentClient;
import lab.springlab.enrichment.config.EnrichmentProperties;
import lab.springlab.enrichment.domain.EnrichmentJob;
import lab.springlab.enrichment.domain.EnrichmentReport;
import lab.springlab.enrichment.domain.EnrichmentStatus;
import lab.springlab.enrichment.dto.DispatchResult;
import lab.springlab.enrichment.exception.ExternalEnrichmentException;
import lab.springlab.enrichment.repository.EnrichmentJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrichmentService {

    private final EnrichmentJobRepository repository;
    private final ExternalEnrichmentClient externalClient;
    private final EnrichmentProperties properties;
    private final Semaphore semaphore;
    private final AtomicLong totalProcessed = new AtomicLong();
    private final AtomicLong totalSuccess = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong totalRetryScheduled = new AtomicLong();
    private final AtomicLong totalStuckRecovered = new AtomicLong();
    private final AtomicLong totalProcessingMs = new AtomicLong();
    private final AtomicReference<Instant> firstProcessedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastProcessedAt = new AtomicReference<>();

    public EnrichmentService(EnrichmentJobRepository repository,
                             ExternalEnrichmentClient externalClient,
                             EnrichmentProperties properties,
                             Semaphore semaphore) {
        this.repository = repository;
        this.externalClient = externalClient;
        this.properties = properties;
        this.semaphore = semaphore;
    }

    @Scheduled(fixedDelayString = "${lab.enrichment.scheduler-fixed-delay-ms:5000}")
    public void scheduledDispatch() {
        dispatchPendingJobs();
    }

    public DispatchResult dispatchPendingJobs() {
        int stuckRecovered = recoverStuckJobs();
        Instant now = Instant.now();
        List<EnrichmentJob> candidates = repository.findPending(
                EnrichmentStatus.PENDING,
                EnrichmentStatus.RETRY_SCHEDULED,
                now,
                PageRequest.of(0, properties.getBatchSize()));

        int claimed = 0;
        for (EnrichmentJob job : candidates) {
            int updated = repository.claim(job.getId(), EnrichmentStatus.PROCESSING, Instant.now(),
                    EnumSet.of(EnrichmentStatus.PENDING, EnrichmentStatus.RETRY_SCHEDULED));
            if (updated == 1) {
                claimed++;
                processJobAsync(job.getId(), job.getPayloadJson(), job.getRetryCount());
            }
        }

        return new DispatchResult(claimed, stuckRecovered);
    }

    @Async("enrichmentExecutor")
    public CompletableFuture<Void> processJobAsync(UUID jobId, String payloadJson, int retryCount) {
        Instant started = Instant.now();
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduleRetry(jobId, retryCount, "Interrupted while waiting for semaphore");
            return CompletableFuture.completedFuture(null);
        }

        try {
            attemptEnrichment(payloadJson);
            repository.markDone(jobId, EnrichmentStatus.DONE, Instant.now());
            recordSuccess(Duration.between(started, Instant.now()).toMillis());
        } catch (ExternalEnrichmentException ex) {
            if (ex.isRetryable()) {
                scheduleRetry(jobId, retryCount, ex.getMessage());
            } else {
                repository.markError(jobId, EnrichmentStatus.ERROR, Instant.now(), ex.getMessage());
                recordError(Duration.between(started, Instant.now()).toMillis());
            }
        } catch (Exception ex) {
            scheduleRetry(jobId, retryCount, "Unexpected error: " + ex.getMessage());
        } finally {
            semaphore.release();
        }

        return CompletableFuture.completedFuture(null);
    }

    private String attemptEnrichment(String payloadJson) {
        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        ExternalEnrichmentException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return externalClient.enrich(payloadJson);
            } catch (ExternalEnrichmentException ex) {
                if (!ex.isRetryable() || attempt == maxAttempts) {
                    throw ex;
                }
                lastException = ex;
                sleepBackoff(attempt);
            } catch (org.springframework.web.reactive.function.client.WebClientRequestException ex) {
                if (attempt == maxAttempts) {
                    throw new ExternalEnrichmentException(\"Timeout or connection error: \" + ex.getMessage(), null, true);
                }
                sleepBackoff(attempt);
            }
        }
        throw Optional.ofNullable(lastException)
                .orElse(new ExternalEnrichmentException("External API failed", null, true));
    }

    private void sleepBackoff(int attempt) {
        long backoff = properties.getBackoffMs() * (1L << Math.min(attempt - 1, 6));
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void scheduleRetry(UUID jobId, int retryCount, String errorMessage) {
        long delayMs = properties.getBackoffMs() * (1L << Math.min(retryCount, 6));
        Instant nextAttemptAt = Instant.now().plusMillis(delayMs);
        repository.scheduleRetry(jobId, EnrichmentStatus.RETRY_SCHEDULED, nextAttemptAt, errorMessage);
        recordRetry();
    }

    private int recoverStuckJobs() {
        Instant cutoff = Instant.now().minusMillis(properties.getStuckThresholdMs());
        int recovered = repository.recoverStuck(
                EnrichmentStatus.PROCESSING,
                EnrichmentStatus.RETRY_SCHEDULED,
                cutoff,
                Instant.now(),
                "Recovered stuck job");
        if (recovered > 0) {
            totalStuckRecovered.addAndGet(recovered);
        }
        return recovered;
    }

    private void recordSuccess(long processingMs) {
        totalProcessed.incrementAndGet();
        totalSuccess.incrementAndGet();
        totalProcessingMs.addAndGet(processingMs);
        updateProcessedWindow();
    }

    private void recordError(long processingMs) {
        totalProcessed.incrementAndGet();
        totalErrors.incrementAndGet();
        totalProcessingMs.addAndGet(processingMs);
        updateProcessedWindow();
    }

    private void recordRetry() {
        totalRetryScheduled.incrementAndGet();
    }

    private void updateProcessedWindow() {
        Instant now = Instant.now();
        firstProcessedAt.compareAndSet(null, now);
        lastProcessedAt.set(now);
    }

    @Transactional(readOnly = true)
    public EnrichmentReport report() {
        long pending = repository.countByStatus(EnrichmentStatus.PENDING);
        long processing = repository.countByStatus(EnrichmentStatus.PROCESSING);
        long done = repository.countByStatus(EnrichmentStatus.DONE);
        long error = repository.countByStatus(EnrichmentStatus.ERROR);
        long retry = repository.countByStatus(EnrichmentStatus.RETRY_SCHEDULED);

        double jobsPerMinute = calculateJobsPerMinute();
        long avgProcessingMs = totalProcessed.get() == 0 ? 0 : totalProcessingMs.get() / totalProcessed.get();

        return new EnrichmentReport(pending, processing, done, error, retry,
                totalProcessed.get(), totalSuccess.get(), totalErrors.get(), totalRetryScheduled.get(),
                totalStuckRecovered.get(), avgProcessingMs, jobsPerMinute);
    }

    private double calculateJobsPerMinute() {
        Instant start = firstProcessedAt.get();
        Instant end = lastProcessedAt.get();
        if (start == null || end == null) {
            return 0.0;
        }
        long seconds = Duration.between(start, end).getSeconds();
        if (seconds == 0) {
            return 0.0;
        }
        return (totalProcessed.get() / (seconds / 60.0));
    }

}
