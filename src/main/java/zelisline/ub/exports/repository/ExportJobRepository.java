package zelisline.ub.exports.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.exports.domain.ExportJob;

public interface ExportJobRepository extends JpaRepository<ExportJob, String> {

    Optional<ExportJob> findByIdAndBusinessId(String id, String businessId);

    Optional<ExportJob> findByBusinessIdAndIdempotencyKeyHash(String businessId, String hash);
}
