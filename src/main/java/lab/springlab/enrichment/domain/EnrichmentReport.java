package lab.springlab.enrichment.domain;

import lab.springlab.enrichment.lab.LabLatencyStats;

public record EnrichmentReport(
        long pending,
        long processing,
        long done,
        long error,
        long retryScheduled,
        long totalProcessed,
        long totalSuccess,
        long totalErrors,
        long totalRetryScheduled,
        long totalStuckRecovered,
        long avgProcessingMs,
        long totalElapsedMs,
        double jobsPerMinute,
        String runId,
        String scenario,
        String mode,
        int items,
        Long startedAtMs,
        Long finishedAtMs,
        Long drainTimeMs,
        LabLatencyStats latencyNs,
        long successCount,
        long failCount,
        long retryCount,
        boolean runDone) {
}
