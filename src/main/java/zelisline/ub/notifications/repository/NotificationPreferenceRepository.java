package zelisline.ub.notifications.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.notifications.domain.NotificationPreference;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, String> {

    List<NotificationPreference> findByBusinessIdAndUserId(String businessId, String userId);

    Optional<NotificationPreference> findByBusinessIdAndUserIdAndCategoryAndChannel(
            String businessId,
            String userId,
            String category,
            String channel);
}
