package zelisline.ub.integrations.csvimport.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.integrations.csvimport.domain.ImportJob;
import zelisline.ub.integrations.csvimport.domain.ImportJob.Status;

public interface ImportJobRepository extends JpaRepository<ImportJob, String> {

    Optional<ImportJob> findFirstByStatusOrderByCreatedAtAsc(Status status);

    Optional<ImportJob> findByIdAndBusinessId(String id, String businessId);
}
