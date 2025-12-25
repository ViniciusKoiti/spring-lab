package lab.springlab.enrichment.lab;

public interface LabMetricsCollector {

    void reset();

    void registerSeed(int items, String scenario);

    void startRun(String mode, String scenario);

    void recordSuccess(long durationNs);

    void recordFail(long durationNs);

    void recordRetry(long durationNs);

    void markFinishedIfDone(boolean done);

    LabRunSnapshot runSnapshot(boolean done);

    LabMetricsSnapshot metricsSnapshot();
}
