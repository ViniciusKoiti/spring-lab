package lab.springlab.enrichment.lab;

public record LabMetricsSnapshot(
        LabLatencyStats latencyNs,
        long successCount,
        long failCount,
        long retryCount) {
}
