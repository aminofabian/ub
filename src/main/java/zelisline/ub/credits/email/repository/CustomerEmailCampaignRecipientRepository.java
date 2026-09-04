package zelisline.ub.credits.email.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.email.domain.CustomerEmailCampaignRecipient;

public interface CustomerEmailCampaignRecipientRepository
        extends JpaRepository<CustomerEmailCampaignRecipient, String> {

    List<CustomerEmailCampaignRecipient> findByCampaignIdOrderByCustomerNameAscEmailAsc(String campaignId);

    void deleteByCampaignId(String campaignId);
}
