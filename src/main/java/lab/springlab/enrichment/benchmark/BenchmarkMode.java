package lab.springlab.enrichment.benchmark;

public enum BenchmarkMode {
    SYNC,
    ASYNC_UNBOUNDED,
    ASYNC_SEMAPHORE;

    public static BenchmarkMode from(String value) {
        if (value == null) {
            return SYNC;
        }
        return BenchmarkMode.valueOf(value.trim().toUpperCase());
    }
}
