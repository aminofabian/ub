package zelisline.ub.purchasing.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.StockMovement;

public interface StockMovementRepository extends JpaRepository<StockMovement, String> {

    List<StockMovement> findByBusinessIdAndReferenceTypeAndReferenceId(
            String businessId,
            String referenceType,
            String referenceId
    );
}
