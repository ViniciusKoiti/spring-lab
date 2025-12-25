package lab.springlab.enrichment.domain;

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
        double jobsPerMinute) {
}
