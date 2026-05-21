package zelisline.ub.notifications.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.notifications.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    List<Notification> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<Notification> findByBusinessIdAndUserIdOrderByCreatedAtDesc(String businessId, String userId);

    List<Notification> findByBusinessIdAndReadAtIsNullOrderByCreatedAtDesc(String businessId);

    long countByBusinessIdAndUserIdAndReadAtIsNull(String businessId, String userId);

    boolean existsByBusinessIdAndDedupeKey(String businessId, String dedupeKey);

    @Query("""
        select count(n) from Notification n
         where n.businessId = :businessId
           and n.userId = :userId
           and n.category in ('promo', 'engagement')
           and n.createdAt >= :since
        """)
    long countPromotionalSince(
            @Param("businessId") String businessId,
            @Param("userId") String userId,
            @Param("since") Instant since);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Notification n
           set n.readAt = :readAt
         where n.businessId = :businessId
           and n.userId = :userId
           and n.readAt is null
        """)
    int markAllReadForUser(
            @Param("businessId") String businessId,
            @Param("userId") String userId,
            @Param("readAt") Instant readAt);
}
