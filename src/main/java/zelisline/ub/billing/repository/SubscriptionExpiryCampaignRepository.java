package zelisline.ub.billing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.billing.domain.SubscriptionExpiryCampaign;
import zelisline.ub.billing.domain.SubscriptionExpiryCampaignStatus;

public interface SubscriptionExpiryCampaignRepository extends JpaRepository<SubscriptionExpiryCampaign, String> {

    List<SubscriptionExpiryCampaign> findByStatus(SubscriptionExpiryCampaignStatus status);

    List<SubscriptionExpiryCampaign> findByBusinessIdAndStatus(
            String businessId,
            SubscriptionExpiryCampaignStatus status);

    Optional<SubscriptionExpiryCampaign> findFirstByBusinessIdAndStatusOrderByCreatedAtDesc(
            String businessId,
            SubscriptionExpiryCampaignStatus status);

    long countByStatusAndCreatedAtAfter(SubscriptionExpiryCampaignStatus status, Instant createdAt);
}
