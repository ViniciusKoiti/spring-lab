package lab.springlab.enrichment.api;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fake")
public class FakeExternalEnrichmentController {

    @PostMapping("/enrich")
    public ResponseEntity<Map<String, Object>> enrich(@RequestBody String payload,
                                                      @RequestParam(defaultValue = "0") long latencyMs,
                                                      @RequestParam(defaultValue = "success") String mode,
                                                      @RequestParam(defaultValue = "0.0") double failureRate) {
        applyLatency(mode, latencyMs);

        String resolvedMode = resolveMode(mode, failureRate);
        if ("error500".equalsIgnoreCase(resolvedMode)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Simulated 500",
                    "timestamp", Instant.now().toString()));
        }
        if ("rateLimit".equalsIgnoreCase(resolvedMode)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "error", "Simulated 429",
                    "retryAfter", "1"));
        }
        if ("timeout".equalsIgnoreCase(resolvedMode)) {
            sleep(3000);
        }

        return ResponseEntity.ok(Map.of(
                "status", "ENRICHED",
                "enrichedAt", Instant.now().toString(),
                "payloadSize", payload.length()));
    }

    private void applyLatency(String mode, long latencyMs) {
        if (latencyMs > 0) {
            sleep(latencyMs);
            return;
        }
        if ("timeout".equalsIgnoreCase(mode)) {
            sleep(3000);
        }
    }

    private String resolveMode(String mode, double failureRate) {
        if (!"random".equalsIgnoreCase(mode)) {
            return mode;
        }
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < failureRate) {
            return ThreadLocalRandom.current().nextBoolean() ? "error500" : "rateLimit";
        }
        return "success";
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
