package zelisline.ub.crm.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmAutomation;

public interface CrmAutomationRepository extends JpaRepository<CrmAutomation, String> {

    List<CrmAutomation> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    Optional<CrmAutomation> findByIdAndBusinessId(String id, String businessId);

    /** The engine's hot path: active rules of one trigger type for a shop. */
    List<CrmAutomation> findByBusinessIdAndTriggerTypeAndActiveTrue(String businessId, String triggerType);
}
