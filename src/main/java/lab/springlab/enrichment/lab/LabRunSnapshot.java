package lab.springlab.enrichment.lab;

public record LabRunSnapshot(
        String runId,
        String scenario,
        String mode,
        int items,
        Long startedAtMs,
        Long finishedAtMs,
        Long drainTimeMs,
        boolean done) {
}
