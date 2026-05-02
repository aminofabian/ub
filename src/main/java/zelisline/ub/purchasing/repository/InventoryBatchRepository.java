package zelisline.ub.purchasing.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.InventoryBatch;

public interface InventoryBatchRepository extends JpaRepository<InventoryBatch, String> {
}
