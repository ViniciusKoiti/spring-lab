package lab.springlab.enrichment.config;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

@Configuration
@EnableConfigurationProperties(EnrichmentProperties.class)
public class EnrichmentConfig {

    @Bean(name = "enrichmentExecutor")
    ThreadPoolTaskExecutor enrichmentExecutor(EnrichmentProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getMaxConcurrency());
        executor.setMaxPoolSize(properties.getMaxConcurrency());
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("enrichment-");
        executor.initialize();
        return executor;
    }

    @Bean
    Semaphore enrichmentSemaphore(EnrichmentProperties properties) {
        return new Semaphore(properties.getMaxConcurrency());
    }

    @Bean
    WebClient enrichmentWebClient(EnrichmentProperties properties) {
        ConnectionProvider provider = ConnectionProvider.builder("enrichment-pool")
                .maxConnections(properties.getHttpMaxConnections())
                .pendingAcquireMaxCount(properties.getHttpMaxConnections() * 2)
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .responseTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()));
        return WebClient.builder()
                .baseUrl(properties.getExternalBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
