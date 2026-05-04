package zelisline.ub.integrations.webhook.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.integrations.webhook.domain.WebhookSubscription;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, String> {

    List<WebhookSubscription> findByBusinessIdAndActiveIsTrueOrderByCreatedAtAsc(String businessId);

    List<WebhookSubscription> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    Optional<WebhookSubscription> findByIdAndBusinessId(String id, String businessId);
}
