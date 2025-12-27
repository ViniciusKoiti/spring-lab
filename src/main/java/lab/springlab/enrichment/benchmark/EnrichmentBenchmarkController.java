package lab.springlab.enrichment.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/enrich")
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class EnrichmentBenchmarkController {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentBenchmarkController.class);
    private final EnrichmentBenchmarkService benchmarkService;

    public EnrichmentBenchmarkController(EnrichmentBenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    @PostMapping("/benchmark")
    public ResponseEntity<BenchmarkResponse> benchmark(@RequestBody BenchmarkRequest request) {
        log.info("Benchmark request mode={} items={} permits={} payloadSize={}",
                request.mode(), request.items(), request.permits(), request.payloadSize());
        BenchmarkResponse response = benchmarkService.run(request);
        return ResponseEntity.ok(response);
    }
}
