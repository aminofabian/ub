package zelisline.ub.credits.sms.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.sms.domain.BusinessSmsHubSettings;

public interface BusinessSmsHubSettingsRepository extends JpaRepository<BusinessSmsHubSettings, String> {

    List<BusinessSmsHubSettings> findByWinbackEnabledTrue();
}
