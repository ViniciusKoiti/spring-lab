package lab.springlab.enrichment.client;

import lab.springlab.enrichment.exception.ExternalEnrichmentException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class ExternalEnrichmentClient {

    private final WebClient webClient;
    private final Timer successTimer;
    private final Timer errorTimer;

    public ExternalEnrichmentClient(WebClient webClient, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.webClient = webClient;
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            successTimer = null;
            errorTimer = null;
        } else {
            successTimer = Timer.builder("lab.external.enrich")
                    .tag("result", "success")
                    .register(registry);
            errorTimer = Timer.builder("lab.external.enrich")
                    .tag("result", "error")
                    .register(registry);
        }
    }

    public String enrich(String payloadJson) {
        long startedNs = System.nanoTime();
        try {
            String response = webClient.post()
                    .uri("/enrich")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payloadJson)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, httpResponse -> httpResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(toException(httpResponse.statusCode(), body))))
                    .bodyToMono(String.class)
                    .block();
            recordTimer(successTimer, startedNs);
            return response;
        } catch (RuntimeException ex) {
            recordTimer(errorTimer, startedNs);
            throw ex;
        }
    }

    private ExternalEnrichmentException toException(HttpStatusCode status, String body) {
        int statusCode = status.value();
        boolean retryable = status.is5xxServerError() || statusCode == 429;
        String message = "External API error " + statusCode + " " + body;
        return new ExternalEnrichmentException(message, statusCode, retryable);
    }

    private void recordTimer(Timer timer, long startedNs) {
        if (timer == null) {
            return;
        }
        long duration = System.nanoTime() - startedNs;
        if (duration > 0L) {
            timer.record(duration, TimeUnit.NANOSECONDS);
        }
    }
}
