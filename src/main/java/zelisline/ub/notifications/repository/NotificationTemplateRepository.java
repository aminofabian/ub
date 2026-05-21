package zelisline.ub.notifications.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.notifications.domain.NotificationTemplate;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {

    Optional<NotificationTemplate> findFirstByBusinessIdAndTypeAndLocaleAndActiveTrueOrderByVersionDesc(
            String businessId,
            String type,
            String locale);

    Optional<NotificationTemplate> findFirstByBusinessIdIsNullAndTypeAndLocaleAndActiveTrueOrderByVersionDesc(
            String type,
            String locale);
}
