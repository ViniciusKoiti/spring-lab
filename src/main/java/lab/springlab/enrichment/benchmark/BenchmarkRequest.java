package lab.springlab.enrichment.benchmark;

public record BenchmarkRequest(
        int items,
        String mode,
        Integer permits,
        Integer payloadSize) {
}
