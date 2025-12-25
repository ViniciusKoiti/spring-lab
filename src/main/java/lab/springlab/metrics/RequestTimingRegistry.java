package lab.springlab.metrics;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

@Component
public class RequestTimingRegistry {

    private final Map<String, RequestTimingStats> stats = new ConcurrentHashMap<>();

    public void record(String path, long durationMs) {
        stats.computeIfAbsent(path, key -> new RequestTimingStats())
                .record(durationMs);
    }

    public Map<String, RequestTimingStats> snapshot() {
        return Collections.unmodifiableMap(stats);
    }

    public static final class RequestTimingStats {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalMs = new LongAdder();
        private final AtomicLong maxMs = new AtomicLong();
        private final AtomicLong minMs = new AtomicLong(Long.MAX_VALUE);

        void record(long durationMs) {
            count.increment();
            totalMs.add(durationMs);
            maxMs.updateAndGet(current -> Math.max(current, durationMs));
            minMs.updateAndGet(current -> Math.min(current, durationMs));
        }

        public long getCount() {
            return count.sum();
        }

        public long getTotalMs() {
            return totalMs.sum();
        }

        public long getMaxMs() {
            return maxMs.get();
        }

        public long getMinMs() {
            long current = minMs.get();
            return current == Long.MAX_VALUE ? 0L : current;
        }

        public long getAvgMs() {
            long countValue = count.sum();
            if (countValue == 0) {
                return 0L;
            }
            return totalMs.sum() / countValue;
        }
    }
}
