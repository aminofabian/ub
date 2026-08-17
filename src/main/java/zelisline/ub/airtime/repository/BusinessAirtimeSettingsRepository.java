package zelisline.ub.airtime.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.airtime.domain.BusinessAirtimeSettings;

public interface BusinessAirtimeSettingsRepository extends JpaRepository<BusinessAirtimeSettings, String> {

    Optional<BusinessAirtimeSettings> findByBusinessId(String businessId);
}
