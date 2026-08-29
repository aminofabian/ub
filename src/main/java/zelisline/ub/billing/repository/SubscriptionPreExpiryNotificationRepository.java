package zelisline.ub.billing.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.billing.domain.SubscriptionPreExpiryNotification;
import zelisline.ub.billing.domain.SubscriptionPreExpiryNotificationStatus;

public interface SubscriptionPreExpiryNotificationRepository
        extends JpaRepository<SubscriptionPreExpiryNotification, String> {

    boolean existsByBusinessIdAndPeriodEndAt(String businessId, java.time.Instant periodEndAt);

    Optional<SubscriptionPreExpiryNotification> findByBusinessIdAndPeriodEndAt(
            String businessId,
            java.time.Instant periodEndAt);

    @Query("""
        select count(n) from SubscriptionPreExpiryNotification n
         where n.status = :status and n.sentAt >= :since
        """)
    long countByStatusAndSentAtAfter(
            @Param("status") SubscriptionPreExpiryNotificationStatus status,
            @Param("since") java.time.Instant since);
}
