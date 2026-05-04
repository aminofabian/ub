package zelisline.ub.notifications.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.notifications.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    List<Notification> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<Notification> findByBusinessIdAndUserIdOrderByCreatedAtDesc(String businessId, String userId);

    List<Notification> findByBusinessIdAndReadAtIsNullOrderByCreatedAtDesc(String businessId);

    boolean existsByBusinessIdAndDedupeKey(String businessId, String dedupeKey);
}
