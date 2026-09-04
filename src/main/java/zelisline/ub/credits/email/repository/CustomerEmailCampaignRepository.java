package zelisline.ub.credits.email.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.email.domain.CustomerEmailCampaign;

public interface CustomerEmailCampaignRepository extends JpaRepository<CustomerEmailCampaign, String> {

    Page<CustomerEmailCampaign> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    java.util.Optional<CustomerEmailCampaign> findByIdAndBusinessId(String id, String businessId);
}
