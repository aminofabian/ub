package zelisline.ub.integrations.privacy.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.integrations.privacy.domain.PrivacyExportJob;

public interface PrivacyExportJobRepository extends JpaRepository<PrivacyExportJob, String> {

    Optional<PrivacyExportJob> findByIdAndBusinessId(String id, String businessId);
}
