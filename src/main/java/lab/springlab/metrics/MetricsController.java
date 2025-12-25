package lab.springlab.metrics;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lab/metrics")
public class MetricsController {

    private final RequestTimingRegistry registry;

    public MetricsController(RequestTimingRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/requests")
    public ResponseEntity<Map<String, RequestTimingView>> requestTimings() {
        Map<String, RequestTimingView> response = new TreeMap<>();
        registry.snapshot().forEach((path, stats) ->
                response.put(path, RequestTimingView.from(stats)));
        return ResponseEntity.ok(response);
    }

    public record RequestTimingView(long count,
                                    long totalMs,
                                    long avgMs,
                                    long minMs,
                                    long maxMs) {
        static RequestTimingView from(RequestTimingRegistry.RequestTimingStats stats) {
            return new RequestTimingView(stats.getCount(),
                    stats.getTotalMs(),
                    stats.getAvgMs(),
                    stats.getMinMs(),
                    stats.getMaxMs());
        }
    }
}
