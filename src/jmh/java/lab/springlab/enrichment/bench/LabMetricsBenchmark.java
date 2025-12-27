package lab.springlab.enrichment.bench;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lab.springlab.enrichment.lab.DefaultLabMetricsCollector;
import lab.springlab.enrichment.lab.LabMetricsSnapshot;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class LabMetricsBenchmark {

    private static final int SAMPLE_COUNT = 2048;

    private DefaultLabMetricsCollector collector;
    private long[] samples;
    private int index;

    @Setup(Level.Trial)
    public void setup() {
        collector = new DefaultLabMetricsCollector();
        samples = new long[SAMPLE_COUNT];
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            samples[i] = ThreadLocalRandom.current().nextLong(100_000, 5_000_000);
        }
        index = 0;
    }

    @Setup(Level.Iteration)
    public void resetCollector() {
        collector.reset();
        index = 0;
    }

    @Benchmark
    public void recordSuccess() {
        long duration = samples[index++ & (SAMPLE_COUNT - 1)];
        collector.recordSuccess(duration);
    }

    @Benchmark
    public LabMetricsSnapshot snapshot() {
        return collector.metricsSnapshot();
    }
}
