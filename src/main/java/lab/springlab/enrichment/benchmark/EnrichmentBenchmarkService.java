package lab.springlab.enrichment.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import lab.springlab.enrichment.client.ExternalEnrichmentClient;
import lab.springlab.enrichment.config.EnrichmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class EnrichmentBenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentBenchmarkService.class);
    private static final int DEFAULT_ITEMS = 1000;
    private static final int DEFAULT_PAYLOAD_SIZE = 64;

    private final ExternalEnrichmentClient externalClient;
    private final EnrichmentProperties properties;

    public EnrichmentBenchmarkService(ExternalEnrichmentClient externalClient, EnrichmentProperties properties) {
        this.externalClient = externalClient;
        this.properties = properties;
    }

    public BenchmarkResponse run(BenchmarkRequest request) {
        int items = request.items() > 0 ? request.items() : DEFAULT_ITEMS;
        int payloadSize = request.payloadSize() != null && request.payloadSize() > 0
                ? request.payloadSize()
                : DEFAULT_PAYLOAD_SIZE;
        BenchmarkMode mode = BenchmarkMode.from(request.mode());
        int permits = resolvePermits(request.permits());
        String payload = buildPayload(payloadSize);

        AtomicInteger errors = new AtomicInteger();
        long startedNs = System.nanoTime();

        try {
            switch (mode) {
                case SYNC -> runSync(items, payload, errors);
                case ASYNC_UNBOUNDED -> runAsyncUnbounded(items, payload, errors);
                case ASYNC_SEMAPHORE -> runAsyncSemaphore(items, payload, permits, errors);
                default -> throw new IllegalStateException("Unsupported mode: " + mode);
            }
        } finally {
            log.info("Benchmark completed mode={} items={} errors={}", mode, items, errors.get());
        }

        long durationMs = (System.nanoTime() - startedNs) / 1_000_000L;
        int processed = Math.max(0, items - errors.get());
        return new BenchmarkResponse(durationMs, processed, errors.get(), mode.name().toLowerCase(Locale.ROOT), permits);
    }

    private void runSync(int items, String payload, AtomicInteger errors) {
        for (int i = 0; i < items; i++) {
            invokeExternal(payload, errors);
        }
    }

    private void runAsyncUnbounded(int items, String payload, AtomicInteger errors) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>(items);
            for (int i = 0; i < items; i++) {
                futures.add(CompletableFuture.runAsync(() -> invokeExternal(payload, errors), executor));
            }
            waitAll(futures);
        }
    }

    private void runAsyncSemaphore(int items, String payload, int permits, AtomicInteger errors) {
        Semaphore semaphore = new Semaphore(permits);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>(items);
            for (int i = 0; i < items; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    boolean acquired = false;
                    try {
                        semaphore.acquire();
                        acquired = true;
                        invokeExternal(payload, errors);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        errors.incrementAndGet();
                    } finally {
                        if (acquired) {
                            semaphore.release();
                        }
                    }
                }, executor));
            }
            waitAll(futures);
        }
    }

    private void invokeExternal(String payload, AtomicInteger errors) {
        try {
            externalClient.enrich(payload);
        } catch (RuntimeException ex) {
            errors.incrementAndGet();
        }
    }

    private void waitAll(List<CompletableFuture<Void>> futures) {
        for (CompletableFuture<Void> future : futures) {
            try {
                future.join();
            } catch (Exception ex) {
                // errors are counted at call site
            }
        }
    }

    private int resolvePermits(Integer permits) {
        if (permits != null && permits > 0) {
            return permits;
        }
        return Math.max(1, properties.getMaxConcurrency());
    }

    private String buildPayload(int payloadSize) {
        StringBuilder builder = new StringBuilder(payloadSize + 20);
        builder.append("{\"data\":\"");
        for (int i = 0; i < payloadSize; i++) {
            builder.append('a');
        }
        builder.append("\"}");
        return builder.toString();
    }
}
