package zelisline.ub.platform.email.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.email.domain.PlatformEmailCampaignRecipient;

public interface PlatformEmailCampaignRecipientRepository
        extends JpaRepository<PlatformEmailCampaignRecipient, String> {

    List<PlatformEmailCampaignRecipient> findByCampaignIdOrderByEmailAsc(String campaignId);

    Page<PlatformEmailCampaignRecipient> findByCampaignIdOrderByEmailAsc(
            String campaignId, Pageable pageable);
}
