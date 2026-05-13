package zelisline.ub.sync.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sync.domain.SyncConflict;

public interface SyncConflictRepository extends JpaRepository<SyncConflict, String> {

    Page<SyncConflict> findByBusinessIdAndResolutionOrderByCreatedAtDesc(
            String businessId, String resolution, Pageable pageable);

    long countByBusinessIdAndResolution(String businessId, String resolution);

    Optional<SyncConflict> findByBusinessIdAndEntityTypeAndEntityIdAndResolution(
            String businessId, String entityType, String entityId, String resolution);
}
