package zelisline.ub.crm.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmAutomationRun;

public interface CrmAutomationRunRepository extends JpaRepository<CrmAutomationRun, String> {

    Page<CrmAutomationRun> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    List<CrmAutomationRun> findByBusinessIdAndAutomationIdOrderByCreatedAtDesc(String businessId, String automationId);
}
