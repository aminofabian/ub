package zelisline.ub.integrations.webhook.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.integrations.webhook.domain.WebhookDelivery;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, String> {

    @Query(value = """
            select d from WebhookDelivery d
            where d.status = 'pending'
            and (d.nextAttemptAt is null or d.nextAttemptAt <= :now)
            order by d.createdAt asc
            """)
    List<WebhookDelivery> findDuePending(@Param("now") Instant now, org.springframework.data.domain.Pageable pageable);

    List<WebhookDelivery> findBySubscriptionIdAndStatusIn(String subscriptionId, Collection<String> statuses);

    boolean existsBySubscriptionIdAndIdempotencyKey(String subscriptionId, String idempotencyKey);

    Optional<WebhookDelivery> findByIdAndBusinessId(String id, String businessId);
}
