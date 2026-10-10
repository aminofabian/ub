package zelisline.ub.crm.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmAutomationStep;

public interface CrmAutomationStepRepository extends JpaRepository<CrmAutomationStep, String> {

    List<CrmAutomationStep> findByAutomationIdOrderByPositionAsc(String automationId);

    void deleteByAutomationId(String automationId);
}
