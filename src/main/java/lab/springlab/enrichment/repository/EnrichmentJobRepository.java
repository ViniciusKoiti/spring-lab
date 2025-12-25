package lab.springlab.enrichment.repository;

import lab.springlab.enrichment.domain.EnrichmentJob;
import lab.springlab.enrichment.domain.EnrichmentStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EnrichmentJobRepository extends JpaRepository<EnrichmentJob, UUID> {

    @Query("select j from EnrichmentJob j where (j.status = :pending) or (j.status = :retry and j.nextAttemptAt <= :now) order by j.createdAt asc")
    List<EnrichmentJob> findPending(@Param("pending") EnrichmentStatus pending,
                                    @Param("retry") EnrichmentStatus retry,
                                    @Param("now") Instant now,
                                    Pageable pageable);

    List<EnrichmentJob> findByStatus(EnrichmentStatus status, Pageable pageable);

    long countByStatus(EnrichmentStatus status);

    @Transactional
    @Modifying
    @Query("update EnrichmentJob j set j.status = :processing, j.startedAt = :now, j.completedAt = null, j.nextAttemptAt = null, j.lastError = null where j.id = :id and j.status in :claimable")
    int claim(@Param("id") UUID id,
              @Param("processing") EnrichmentStatus processing,
              @Param("now") Instant now,
              @Param("claimable") Collection<EnrichmentStatus> claimable);

    @Transactional
    @Modifying
    @Query("update EnrichmentJob j set j.status = :done, j.completedAt = :now, j.lastError = null where j.id = :id")
    int markDone(@Param("id") UUID id, @Param("done") EnrichmentStatus done, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("update EnrichmentJob j set j.status = :error, j.completedAt = :now, j.lastError = :lastError where j.id = :id")
    int markError(@Param("id") UUID id,
                  @Param("error") EnrichmentStatus error,
                  @Param("now") Instant now,
                  @Param("lastError") String lastError);

    @Transactional
    @Modifying
    @Query("update EnrichmentJob j set j.status = :retryStatus, j.retryCount = j.retryCount + 1, j.startedAt = null, j.completedAt = null, j.nextAttemptAt = :nextAttemptAt, j.lastError = :lastError where j.id = :id")
    int scheduleRetry(@Param("id") UUID id,
                      @Param("retryStatus") EnrichmentStatus retryStatus,
                      @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("lastError") String lastError);

    @Transactional
    @Modifying
    @Query("update EnrichmentJob j set j.status = :retryStatus, j.retryCount = j.retryCount + 1, j.startedAt = null, j.completedAt = null, j.nextAttemptAt = :now, j.lastError = :lastError where j.status = :processing and j.startedAt <= :cutoff")
    int recoverStuck(@Param("processing") EnrichmentStatus processing,
                     @Param("retryStatus") EnrichmentStatus retryStatus,
                     @Param("cutoff") Instant cutoff,
                     @Param("now") Instant now,
                     @Param("lastError") String lastError);
}
