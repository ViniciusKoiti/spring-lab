package lab.springlab.enrichment.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class ProcessingModeBenchmark {

    public enum ExecMode { SYNC, ASYNC_UNBOUNDED, ASYNC_SEMAPHORE }

    @Param({"SYNC", "ASYNC_UNBOUNDED", "ASYNC_SEMAPHORE"})
    public ExecMode mode;

    @Param({"1000"})
    public int items;

    @Param({"8"})
    public int permits;

    private ExecutorService executor;

    @Setup(Level.Trial)
    public void setup() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "bench-pool");
            t.setDaemon(true);
            return t;
        });
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        executor.shutdownNow();
    }

    @Benchmark
    public void process(Blackhole bh) throws Exception {
        AtomicLong accumulator = new AtomicLong(0);

        switch (mode) {
            case SYNC -> {
                for (int i = 0; i < items; i++) {
                    accumulator.addAndGet(work(i));
                }
                bh.consume(accumulator.get());
            }
            case ASYNC_UNBOUNDED -> {
                List<CompletableFuture<Long>> futures = new ArrayList<>(items);
                for (int i = 0; i < items; i++) {
                    final int idx = i;
                    futures.add(CompletableFuture.supplyAsync(() -> work(idx), executor));
                }

                long sum = 0;
                for (CompletableFuture<Long> future : futures) {
                    sum += future.get();
                }
                bh.consume(sum);
            }
            case ASYNC_SEMAPHORE -> {
                Semaphore semaphore = new Semaphore(permits);
                List<CompletableFuture<Long>> futures = new ArrayList<>(items);

                for (int i = 0; i < items; i++) {
                    final int idx = i;
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        boolean acquired = false;
                        try {
                            semaphore.acquire();
                            acquired = true;
                            return work(idx);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } finally {
                            if (acquired) {
                                semaphore.release();
                            }
                        }
                    }, executor));
                }

                long sum = 0;
                for (CompletableFuture<Long> future : futures) {
                    sum += future.get();
                }
                bh.consume(sum);
            }
            default -> throw new IllegalStateException("Unsupported mode: " + mode);
        }
    }

    private static long work(int seed) {
        long x = seed * 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 2_000; i++) {
            x ^= (x << 13);
            x ^= (x >>> 7);
            x ^= (x << 17);
        }
        return x;
    }
}
