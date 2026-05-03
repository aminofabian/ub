package zelisline.ub.credits.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.BusinessCreditSettings;

public interface BusinessCreditSettingsRepository extends JpaRepository<BusinessCreditSettings, String> {
}
