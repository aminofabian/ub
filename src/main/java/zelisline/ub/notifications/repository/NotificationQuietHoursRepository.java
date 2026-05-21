package zelisline.ub.notifications.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.notifications.domain.NotificationQuietHours;

public interface NotificationQuietHoursRepository extends JpaRepository<NotificationQuietHours, String> {

    Optional<NotificationQuietHours> findByUserIdAndBusinessId(String userId, String businessId);
}
