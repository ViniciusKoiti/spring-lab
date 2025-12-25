package lab.springlab.enrichment.lab;

public record LabLatencyStats(
        long count,
        long minNs,
        long maxNs,
        double avgNs,
        long p95Ns) {
}
