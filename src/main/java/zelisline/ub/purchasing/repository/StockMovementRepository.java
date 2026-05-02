package zelisline.ub.purchasing.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.StockMovement;

public interface StockMovementRepository extends JpaRepository<StockMovement, String> {
}
