package lab.springlab.enrichment.service;

import lab.springlab.enrichment.client.ExternalEnrichmentClient;
import lab.springlab.enrichment.config.EnrichmentProperties;
import lab.springlab.enrichment.domain.EnrichmentStatus;
import lab.springlab.enrichment.exception.ExternalEnrichmentException;
import lab.springlab.enrichment.repository.EnrichmentJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EnrichmentJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentJobProcessor.class);
    private final EnrichmentJobRepository repository;
    private final ExternalEnrichmentClient externalClient;
    private final EnrichmentProperties properties;
    private final Semaphore semaphore;
    private final EnrichmentProcessingMetrics metrics;

    public EnrichmentJobProcessor(EnrichmentJobRepository repository,
                                  ExternalEnrichmentClient externalClient,
                                  EnrichmentProperties properties,
                                  Semaphore semaphore,
                                  EnrichmentProcessingMetrics metrics) {
        this.repository = repository;
        this.externalClient = externalClient;
        this.properties = properties;
        this.semaphore = semaphore;
        this.metrics = metrics;
    }

    public void processJob(UUID jobId, String payloadJson, int retryCount) {
        Instant started = Instant.now();
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduleRetry(jobId, retryCount, "Interrupted while waiting for semaphore");
            return;
        }

        try {
            attemptEnrichment(payloadJson);
            repository.markDone(jobId, EnrichmentStatus.DONE, Instant.now());
            metrics.recordSuccess(Duration.between(started, Instant.now()).toMillis());
        } catch (ExternalEnrichmentException ex) {
            if (ex.isRetryable()) {
                log.warn("Retryable error jobId={} retryCount={} message={}", jobId, retryCount, ex.getMessage());
                scheduleRetry(jobId, retryCount, ex.getMessage());
            } else {
                log.warn("Non-retryable error jobId={} message={}", jobId, ex.getMessage());
                repository.markError(jobId, EnrichmentStatus.ERROR, Instant.now(), ex.getMessage());
                metrics.recordError(Duration.between(started, Instant.now()).toMillis());
            }
        } catch (Exception ex) {
            log.warn("Unexpected error jobId={} message={}", jobId, ex.getMessage());
            scheduleRetry(jobId, retryCount, "Unexpected error: " + ex.getMessage());
        } finally {
            semaphore.release();
        }
    }

    public int recoverStuckJobs() {
        Instant cutoff = Instant.now().minusMillis(properties.getStuckThresholdMs());
        int recovered = repository.recoverStuck(
                EnrichmentStatus.PROCESSING,
                EnrichmentStatus.RETRY_SCHEDULED,
                cutoff,
                Instant.now(),
                "Recovered stuck job");
        metrics.recordStuckRecovered(recovered);
        if (recovered > 0) {
            log.warn("Recovered stuck jobs count={}", recovered);
        }
        return recovered;
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
                    throw new ExternalEnrichmentException("Timeout or connection error: " + ex.getMessage(), null, true);
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
        metrics.recordRetry();
    }
}
