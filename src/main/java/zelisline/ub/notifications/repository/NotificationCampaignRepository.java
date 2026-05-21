package zelisline.ub.notifications.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.notifications.domain.NotificationCampaign;

public interface NotificationCampaignRepository extends JpaRepository<NotificationCampaign, String> {

    List<NotificationCampaign> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<NotificationCampaign> findByStatusAndScheduledAtLessThanEqual(String status, Instant scheduledAt);
}
