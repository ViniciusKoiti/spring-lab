package lab.springlab.enrichment.benchmark;

public record BenchmarkResponse(
        long durationMs,
        int itemsProcessed,
        int errors,
        String mode,
        int permits) {
}
