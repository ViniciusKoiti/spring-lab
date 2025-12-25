package lab.springlab.enrichment.client;

import lab.springlab.enrichment.exception.ExternalEnrichmentException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class ExternalEnrichmentClient {

    private final WebClient webClient;

    public ExternalEnrichmentClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public String enrich(String payloadJson) {
        return webClient.post()
                .uri("/enrich")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payloadJson)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(toException(response.statusCode(), body))))
                .bodyToMono(String.class)
                .block();
    }

    private ExternalEnrichmentException toException(HttpStatusCode status, String body) {
        int statusCode = status.value();
        boolean retryable = status.is5xxServerError() || statusCode == 429;
        String message = "External API error " + statusCode + " " + body;
        return new ExternalEnrichmentException(message, statusCode, retryable);
    }
}
