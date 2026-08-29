package zelisline.ub.payroll.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payroll.domain.PayrollAutomationSettings;

public interface PayrollAutomationSettingsRepository extends JpaRepository<PayrollAutomationSettings, String> {

    List<PayrollAutomationSettings> findByEnabledTrue();
}
