package lab.springlab.enrichment.service;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lab.springlab.enrichment.config.EnrichmentProperties;
import lab.springlab.enrichment.domain.EnrichmentJob;
import lab.springlab.enrichment.domain.EnrichmentStatus;
import lab.springlab.enrichment.dto.DispatchResult;
import lab.springlab.enrichment.repository.EnrichmentJobRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class EnrichmentAsyncService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentAsyncService.class);
    private final EnrichmentJobRepository repository;
    private final EnrichmentProperties properties;
    private final EnrichmentJobProcessor processor;

    @Autowired
    private EntityManager entityManager;

    public EnrichmentAsyncService(EnrichmentJobRepository repository,
                                  EnrichmentProperties properties,
                                  EnrichmentJobProcessor processor) {
        this.repository = repository;
        this.properties = properties;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${lab.enrichment.scheduler-fixed-delay-ms:5000}")
    public void scheduledDispatch() {
        log.info("Scheduled dispatch tick");
        dispatchPendingJobs();
    }

    @Transactional
    public DispatchResult dispatchPendingJobs() {
        int stuckRecovered = processor.recoverStuckJobs();
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

        // FIX: Clear Hibernate session to release entities between batch cycles
        // Without this, entities accumulate in memory across scheduler invocations
        entityManager.clear();

        log.info("Dispatch async completed claimed={} stuckRecovered={}", claimed, stuckRecovered);
        return new DispatchResult(claimed, stuckRecovered);
    }

    /**
     * Dispatches ALL pending jobs without batch-size limit.
     * Used for benchmarking to eliminate scheduler influence.
     */
    @Transactional
    public DispatchResult dispatchAllPendingJobs() {
        int stuckRecovered = processor.recoverStuckJobs();
        Instant now = Instant.now();
        List<EnrichmentJob> candidates = repository.findAllPending(
                EnrichmentStatus.PENDING,
                EnrichmentStatus.RETRY_SCHEDULED,
                now);

        log.info("Dispatch ALL async started with {} candidates", candidates.size());

        int claimed = 0;
        for (EnrichmentJob job : candidates) {
            int updated = repository.claim(job.getId(), EnrichmentStatus.PROCESSING, Instant.now(),
                    EnumSet.of(EnrichmentStatus.PENDING, EnrichmentStatus.RETRY_SCHEDULED));
            if (updated == 1) {
                claimed++;
                processJobAsync(job.getId(), job.getPayloadJson(), job.getRetryCount());
            }
        }

        // FIX: Clear Hibernate session to release entities
        entityManager.clear();

        log.info("Dispatch ALL async completed claimed={} stuckRecovered={}", claimed, stuckRecovered);
        return new DispatchResult(claimed, stuckRecovered);
    }

    @Async("enrichmentExecutor")
    public CompletableFuture<Void> processJobAsync(UUID jobId, String payloadJson, int retryCount) {
        processor.processJob(jobId, payloadJson, retryCount);
        return CompletableFuture.completedFuture(null);
    }
}
