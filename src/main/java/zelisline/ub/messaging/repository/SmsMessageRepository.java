package zelisline.ub.messaging.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.messaging.domain.SmsMessage;

public interface SmsMessageRepository extends JpaRepository<SmsMessage, String> {

    Page<SmsMessage> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    Page<SmsMessage> findByBusinessIdAndCustomerIdOrderByCreatedAtDesc(
            String businessId, String customerId, Pageable pageable);

    Page<SmsMessage> findByBusinessIdAndCampaignIdOrderByCreatedAtDesc(
            String businessId, String campaignId, Pageable pageable);

    boolean existsByBusinessIdAndReferenceIdAndStatusIn(
            String businessId, String referenceId, Collection<String> statuses);

    List<SmsMessage> findByProviderMessageId(String providerMessageId);

    @Query("""
            select count(m)
              from SmsMessage m
             where m.businessId = :businessId
               and m.phone = :phone
               and m.messageClass = 'PROMOTIONAL'
               and m.status in ('SENT', 'DELIVERED')
               and m.createdAt >= :since
            """)
    long countPromotionalSince(
            @Param("businessId") String businessId,
            @Param("phone") String phone,
            @Param("since") Instant since);

    @Query("""
            select distinct m.businessId
              from SmsMessage m
             where m.phone = :phone
               and m.status in ('SENT', 'DELIVERED')
               and m.createdAt >= :since
               and (:promotionalOnly = false or m.messageClass = 'PROMOTIONAL')
            """)
    List<String> businessIdsThatTexted(
            @Param("phone") String phone,
            @Param("since") Instant since,
            @Param("promotionalOnly") boolean promotionalOnly);

    @Query("""
            select count(m), coalesce(sum(m.credits), 0)
              from SmsMessage m
             where m.businessId = :businessId
               and m.status in ('SENT', 'DELIVERED')
               and m.createdAt >= :since
            """)
    List<Object[]> sentTotalsSince(
            @Param("businessId") String businessId,
            @Param("since") Instant since);

    /** Rows of {@code [createdAt, status, credits]} for one shop since {@code since}. */
    @Query("""
            select m.createdAt, m.status, m.credits
              from SmsMessage m
             where m.businessId = :businessId
               and m.createdAt >= :since
            """)
    List<Object[]> activitySince(
            @Param("businessId") String businessId,
            @Param("since") Instant since);

    @Query("""
            select m.reason, count(m), coalesce(sum(m.credits), 0)
              from SmsMessage m
             where m.businessId = :businessId
               and m.status in ('SENT', 'DELIVERED')
               and m.createdAt >= :since
             group by m.reason
            """)
    List<Object[]> sentTotalsByReasonSince(
            @Param("businessId") String businessId,
            @Param("since") Instant since);
}
