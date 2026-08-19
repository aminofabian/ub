package zelisline.ub.platform.email.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.email.domain.PlatformEmailCampaign;

public interface PlatformEmailCampaignRepository extends JpaRepository<PlatformEmailCampaign, String> {

    Page<PlatformEmailCampaign> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
