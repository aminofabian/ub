package zelisline.ub.globalcatalog.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.globalcatalog.domain.GlobalCatalogJob;
import zelisline.ub.globalcatalog.domain.GlobalCatalogJob.Status;

public interface GlobalCatalogJobRepository extends JpaRepository<GlobalCatalogJob, String> {

    Optional<GlobalCatalogJob> findFirstByStatusOrderByCreatedAtAsc(Status status);

    Optional<GlobalCatalogJob> findByIdAndBusinessId(String id, String businessId);

    Optional<GlobalCatalogJob> findByIdAndBusinessIdIsNull(String id);

    List<GlobalCatalogJob> findByStatusAndUpdatedAtBefore(Status status, Instant updatedAtBefore);
}
