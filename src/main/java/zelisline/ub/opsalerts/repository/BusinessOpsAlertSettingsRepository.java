package zelisline.ub.opsalerts.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.opsalerts.domain.BusinessOpsAlertSettings;

public interface BusinessOpsAlertSettingsRepository extends JpaRepository<BusinessOpsAlertSettings, String> {
}
