package zelisline.ub.marketplace.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.SupplierPerformanceEvent;

public interface SupplierPerformanceEventRepository extends JpaRepository<SupplierPerformanceEvent, String> {
}
