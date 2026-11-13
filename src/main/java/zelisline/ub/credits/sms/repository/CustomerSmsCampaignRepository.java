package zelisline.ub.credits.sms.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import zelisline.ub.credits.sms.domain.CustomerSmsCampaign;

public interface CustomerSmsCampaignRepository extends JpaRepository<CustomerSmsCampaign, String> {

    Page<CustomerSmsCampaign> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    Optional<CustomerSmsCampaign> findByIdAndBusinessId(String id, String businessId);

    /** Campaigns ready to send: queued now, or scheduled and due. Not tenant-scoped: the sender runs for all shops. */
    @Query("""
            select c
              from CustomerSmsCampaign c
             where c.status = 'QUEUED'
                or (c.status = 'SCHEDULED' and c.scheduledAt <= :now)
             order by c.scheduledAt asc, c.createdAt asc
            """)
    List<CustomerSmsCampaign> findDue(@Param("now") Instant now, Pageable pageable);

    /** RUNNING campaigns whose sender stopped reporting. */
    @Query("""
            select c
              from CustomerSmsCampaign c
             where c.status = 'RUNNING'
               and (c.heartbeatAt is null or c.heartbeatAt < :staleBefore)
            """)
    List<CustomerSmsCampaign> findStalled(@Param("staleBefore") Instant staleBefore);

    /**
     * Atomically takes a due campaign for sending. Returns 1 for the one sender that
     * wins; 0 means another instance already has it or it was cancelled.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CustomerSmsCampaign c
               set c.status = 'RUNNING',
                   c.heartbeatAt = :now,
                   c.updatedAt = :now
             where c.id = :id
               and c.status in ('QUEUED', 'SCHEDULED')
            """)
    int claim(@Param("id") String id, @Param("now") Instant now);

    // The sender only ever changes a campaign through the guarded updates below, each
    // conditional on status = RUNNING, so it can never overwrite a cancel made by staff.

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CustomerSmsCampaign c
               set c.startedAt = :now
             where c.id = :id
               and c.startedAt is null
            """)
    int markStarted(@Param("id") String id, @Param("now") Instant now);

    /** Heartbeat plus counters. Returns 0 when the campaign is no longer RUNNING. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CustomerSmsCampaign c
               set c.heartbeatAt = :now,
                   c.updatedAt = :now,
                   c.recipientsSent = :sent,
                   c.recipientsFailed = :failed,
                   c.recipientsSkipped = :skipped,
                   c.creditsUsed = :credits
             where c.id = :id
               and c.status = 'RUNNING'
            """)
    int progress(
            @Param("id") String id,
            @Param("now") Instant now,
            @Param("sent") int sent,
            @Param("failed") int failed,
            @Param("skipped") int skipped,
            @Param("credits") int credits);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CustomerSmsCampaign c
               set c.status = :status,
                   c.completedAt = :now,
                   c.updatedAt = :now
             where c.id = :id
               and c.status = 'RUNNING'
            """)
    int finishRunning(@Param("id") String id, @Param("status") String status, @Param("now") Instant now);

    /** Sending window closed, or a stalled sender: hand the campaign back to the scheduler. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CustomerSmsCampaign c
               set c.status = :status,
                   c.scheduledAt = :scheduledAt,
                   c.updatedAt = :now
             where c.id = :id
               and c.status = 'RUNNING'
            """)
    int releaseRunning(
            @Param("id") String id,
            @Param("status") String status,
            @Param("scheduledAt") Instant scheduledAt,
            @Param("now") Instant now);
}
