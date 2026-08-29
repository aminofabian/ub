package zelisline.ub.billing.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.billing.domain.SubscriptionExpiryCampaignDelivery;
import zelisline.ub.billing.domain.SubscriptionExpiryDeliveryChannel;

public interface SubscriptionExpiryCampaignDeliveryRepository
        extends JpaRepository<SubscriptionExpiryCampaignDelivery, String> {

    boolean existsByCampaignIdAndStepDayAndChannel(
            String campaignId,
            int stepDay,
            SubscriptionExpiryDeliveryChannel channel);

    Optional<SubscriptionExpiryCampaignDelivery> findByCampaignIdAndStepDayAndChannel(
            String campaignId,
            int stepDay,
            SubscriptionExpiryDeliveryChannel channel);
}
