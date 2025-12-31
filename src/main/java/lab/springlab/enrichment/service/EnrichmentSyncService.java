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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class EnrichmentSyncService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentSyncService.class);
    private final EnrichmentJobRepository repository;
    private final EnrichmentProperties properties;
    private final EnrichmentJobProcessor processor;

    @Autowired
    private EntityManager entityManager;

    public EnrichmentSyncService(EnrichmentJobRepository repository,
                                 EnrichmentProperties properties,
                                 EnrichmentJobProcessor processor) {
        this.repository = repository;
        this.properties = properties;
        this.processor = processor;
    }

    @Transactional
    public DispatchResult dispatchPendingJobsSync() {
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
                processor.processJob(job.getId(), job.getPayloadJson(), job.getRetryCount());
            }
        }

        // FIX: Clear Hibernate session to release entities between batch cycles
        entityManager.clear();

        log.info("Dispatch sync completed claimed={} stuckRecovered={}", claimed, stuckRecovered);
        return new DispatchResult(claimed, stuckRecovered);
    }

    /**
     * Dispatches ALL pending jobs synchronously without batch-size limit.
     * Used for benchmarking comparison with async-all mode.
     */
    @Transactional
    public DispatchResult dispatchAllPendingJobsSync() {
        int stuckRecovered = processor.recoverStuckJobs();
        Instant now = Instant.now();
        List<EnrichmentJob> candidates = repository.findAllPending(
                EnrichmentStatus.PENDING,
                EnrichmentStatus.RETRY_SCHEDULED,
                now);

        log.info("Dispatch ALL sync started with {} candidates", candidates.size());

        int claimed = 0;
        for (EnrichmentJob job : candidates) {
            int updated = repository.claim(job.getId(), EnrichmentStatus.PROCESSING, Instant.now(),
                    EnumSet.of(EnrichmentStatus.PENDING, EnrichmentStatus.RETRY_SCHEDULED));
            if (updated == 1) {
                claimed++;
                processor.processJob(job.getId(), job.getPayloadJson(), job.getRetryCount());
            }
        }

        // FIX: Clear Hibernate session to release entities
        entityManager.clear();

        log.info("Dispatch ALL sync completed claimed={} stuckRecovered={}", claimed, stuckRecovered);
        return new DispatchResult(claimed, stuckRecovered);
    }
}
