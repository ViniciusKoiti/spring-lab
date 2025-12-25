package lab.springlab.enrichment.service;

import lab.springlab.enrichment.domain.EnrichmentReport;
import lab.springlab.enrichment.domain.EnrichmentStatus;
import lab.springlab.enrichment.repository.EnrichmentJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrichmentReportService {

    private final EnrichmentJobRepository repository;
    private final EnrichmentProcessingMetrics metrics;

    public EnrichmentReportService(EnrichmentJobRepository repository, EnrichmentProcessingMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public EnrichmentReport report() {
        long pending = repository.countByStatus(EnrichmentStatus.PENDING);
        long processing = repository.countByStatus(EnrichmentStatus.PROCESSING);
        long done = repository.countByStatus(EnrichmentStatus.DONE);
        long error = repository.countByStatus(EnrichmentStatus.ERROR);
        long retry = repository.countByStatus(EnrichmentStatus.RETRY_SCHEDULED);

        long totalProcessed = metrics.getTotalProcessed();
        long avgProcessingMs = totalProcessed == 0 ? 0 : metrics.getTotalProcessingMs() / totalProcessed;

        return new EnrichmentReport(pending, processing, done, error, retry,
                totalProcessed,
                metrics.getTotalSuccess(),
                metrics.getTotalErrors(),
                metrics.getTotalRetryScheduled(),
                metrics.getTotalStuckRecovered(),
                avgProcessingMs,
                metrics.getTotalElapsedMs(),
                metrics.getJobsPerMinute());
    }
}
