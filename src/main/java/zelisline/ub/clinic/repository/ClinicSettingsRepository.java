package zelisline.ub.clinic.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.clinic.domain.ClinicSettings;

public interface ClinicSettingsRepository extends JpaRepository<ClinicSettings, String> {

    boolean existsByBusinessIdAndEnabledTrue(String businessId);
}
