package lab.springlab.enrichment.dto;

import lab.springlab.enrichment.domain.EnrichmentJob;
import lab.springlab.enrichment.domain.EnrichmentStatus;
import java.time.Instant;
import java.util.UUID;

public record EnrichmentJobView(UUID id,
                                EnrichmentStatus status,
                                int retryCount,
                                Instant createdAt,
                                Instant startedAt,
                                Instant completedAt,
                                Instant nextAttemptAt,
                                String lastError) {
    public static EnrichmentJobView from(EnrichmentJob job) {
        return new EnrichmentJobView(job.getId(), job.getStatus(), job.getRetryCount(),
                job.getCreatedAt(), job.getStartedAt(), job.getCompletedAt(),
                job.getNextAttemptAt(), job.getLastError());
    }
}
