package zelisline.ub.domainoffer.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.domainoffer.domain.DomainOfferCampaign;
import zelisline.ub.domainoffer.domain.DomainOfferCampaignStatus;

public interface DomainOfferCampaignRepository extends JpaRepository<DomainOfferCampaign, String> {

    Optional<DomainOfferCampaign> findFirstByStatusOrderByCreatedAtDesc(DomainOfferCampaignStatus status);

    Optional<DomainOfferCampaign> findFirstByOrderByCreatedAtDesc();

    List<DomainOfferCampaign> findAllByOrderByCreatedAtDesc();
}
