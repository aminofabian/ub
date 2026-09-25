package zelisline.ub.globalcatalog.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.globalcatalog.domain.GlobalCatalogJob;
import zelisline.ub.globalcatalog.domain.GlobalCatalogJob.Status;

public interface GlobalCatalogJobRepository extends JpaRepository<GlobalCatalogJob, String> {

    Optional<GlobalCatalogJob> findFirstByStatusOrderByCreatedAtAsc(Status status);

    Optional<GlobalCatalogJob> findByIdAndBusinessId(String id, String businessId);

    Optional<GlobalCatalogJob> findByIdAndBusinessIdIsNull(String id);

    List<GlobalCatalogJob> findByStatusAndUpdatedAtBefore(Status status, Instant updatedAtBefore);

    /**
     * Atomically moves a job from {@code required} to {@code to}. Returns 1 when this caller won
     * the transition, 0 when the row no longer matched — the guard that stops two instances from
     * claiming and executing the same pending job.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update GlobalCatalogJob j
               set j.status = :to,
                   j.rowsTotal = :rowsTotal,
                   j.rowsProcessed = 0,
                   j.statusMessage = :message,
                   j.updatedAt = :now
             where j.id = :id
               and j.status = :required
            """)
    int claimPending(
            @Param("id") String id,
            @Param("required") Status required,
            @Param("to") Status to,
            @Param("rowsTotal") Integer rowsTotal,
            @Param("message") String message,
            @Param("now") Instant now);

    /** Completes a job only while it is still owned ({@code required}), never overwriting a terminal row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update GlobalCatalogJob j
               set j.status = :to,
                   j.rowsTotal = :rowsTotal,
                   j.rowsProcessed = :rowsTotal,
                   j.rowsCommitted = :rowsCommitted,
                   j.resultJson = :resultJson,
                   j.statusMessage = :message,
                   j.completedAt = :now,
                   j.updatedAt = :now
             where j.id = :id
               and j.status = :required
            """)
    int completeJob(
            @Param("id") String id,
            @Param("required") Status required,
            @Param("to") Status to,
            @Param("rowsTotal") Integer rowsTotal,
            @Param("rowsCommitted") Integer rowsCommitted,
            @Param("resultJson") String resultJson,
            @Param("message") String message,
            @Param("now") Instant now);

    /** Fails a job only from a non-terminal state; never overwrites a completed/failed row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update GlobalCatalogJob j
               set j.status = :to,
                   j.statusMessage = :message,
                   j.completedAt = :now,
                   j.updatedAt = :now
             where j.id = :id
               and j.status in :allowed
            """)
    int failJob(
            @Param("id") String id,
            @Param("allowed") Collection<Status> allowed,
            @Param("to") Status to,
            @Param("message") String message,
            @Param("now") Instant now);
}
