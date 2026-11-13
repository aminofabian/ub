package zelisline.ub.credits.sms.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.sms.domain.CustomerSmsCampaignRecipient;

public interface CustomerSmsCampaignRecipientRepository
        extends JpaRepository<CustomerSmsCampaignRecipient, String> {

    List<CustomerSmsCampaignRecipient> findByCampaignIdOrderByCustomerNameAscPhoneAsc(
            String campaignId, Pageable pageable);

    List<CustomerSmsCampaignRecipient> findByCampaignIdAndStatusOrderByCustomerNameAscPhoneAsc(
            String campaignId, String status);

    List<CustomerSmsCampaignRecipient> findByCampaignIdAndStatus(String campaignId, String status);

    long countByCampaignIdAndStatus(String campaignId, String status);

    void deleteByCampaignId(String campaignId);
}
