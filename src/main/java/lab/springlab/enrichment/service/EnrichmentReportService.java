package lab.springlab.enrichment.service;

import lab.springlab.enrichment.domain.EnrichmentReport;
import lab.springlab.enrichment.domain.EnrichmentStatus;
import lab.springlab.enrichment.lab.LabMetricsCollector;
import lab.springlab.enrichment.lab.LabMetricsSnapshot;
import lab.springlab.enrichment.lab.LabRunSnapshot;
import lab.springlab.enrichment.repository.EnrichmentJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrichmentReportService {

    private static final String DEFAULT_SCENARIO = "default";
    private final EnrichmentJobRepository repository;
    private final EnrichmentProcessingMetrics metrics;
    private final LabMetricsCollector labMetrics;

    public EnrichmentReportService(EnrichmentJobRepository repository,
                                   EnrichmentProcessingMetrics metrics,
                                   LabMetricsCollector labMetrics) {
        this.repository = repository;
        this.metrics = metrics;
        this.labMetrics = labMetrics;
    }

    public void reset() {
        metrics.reset();
        labMetrics.reset();
    }

    public void registerSeed(int items) {
        labMetrics.registerSeed(items, DEFAULT_SCENARIO);
    }

    public void startRun(String mode) {
        labMetrics.startRun(mode, DEFAULT_SCENARIO);
    }

    @Transactional(readOnly = true)
    public EnrichmentReport report() {
        long pending = repository.countByStatus(EnrichmentStatus.PENDING);
        long processing = repository.countByStatus(EnrichmentStatus.PROCESSING);
        long done = repository.countByStatus(EnrichmentStatus.DONE);
        long error = repository.countByStatus(EnrichmentStatus.ERROR);
        long retry = repository.countByStatus(EnrichmentStatus.RETRY_SCHEDULED);
        boolean runDone = pending == 0 && processing == 0 && retry == 0;

        long totalProcessed = metrics.getTotalProcessed();
        long avgProcessingMs = totalProcessed == 0 ? 0 : metrics.getTotalProcessingMs() / totalProcessed;

        labMetrics.markFinishedIfDone(runDone);
        LabRunSnapshot runSnapshot = labMetrics.runSnapshot(runDone);
        LabMetricsSnapshot labSnapshot = labMetrics.metricsSnapshot();

        return new EnrichmentReport(pending, processing, done, error, retry,
                totalProcessed,
                metrics.getTotalSuccess(),
                metrics.getTotalErrors(),
                metrics.getTotalRetryScheduled(),
                metrics.getTotalStuckRecovered(),
                avgProcessingMs,
                metrics.getTotalElapsedMs(),
                metrics.getJobsPerMinute(),
                runSnapshot.runId(),
                runSnapshot.scenario(),
                runSnapshot.mode(),
                runSnapshot.items(),
                runSnapshot.startedAtMs(),
                runSnapshot.finishedAtMs(),
                runSnapshot.drainTimeMs(),
                labSnapshot.latencyNs(),
                labSnapshot.successCount(),
                labSnapshot.failCount(),
                labSnapshot.retryCount(),
                runSnapshot.done());
    }
}
