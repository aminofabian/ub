package zelisline.ub.notifications.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.notifications.domain.NotificationSubscription;

public interface NotificationSubscriptionRepository extends JpaRepository<NotificationSubscription, String> {

    List<NotificationSubscription> findByBusinessIdAndItemIdAndKindAndActiveTrue(
            String businessId,
            String itemId,
            String kind);

    Optional<NotificationSubscription> findByBusinessIdAndUserIdAndItemIdAndKind(
            String businessId,
            String userId,
            String itemId,
            String kind);

    List<NotificationSubscription> findByBusinessIdAndUserIdAndActiveTrue(String businessId, String userId);
}
