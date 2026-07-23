package zelisline.ub.purchasing.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.StockMovement;

public interface StockMovementRepository extends JpaRepository<StockMovement, String> {

    List<StockMovement> findByBusinessIdAndReferenceTypeAndReferenceId(
            String businessId,
            String referenceType,
            String referenceId
    );

    List<StockMovement> findByBatchIdAndMovementType(String batchId, String movementType);

    List<StockMovement> findByBatchId(String batchId);

    List<StockMovement> findByBusinessIdAndItemIdOrderByCreatedAtDesc(
            String businessId,
            String itemId,
            Pageable pageable
    );

    @Query("""
            SELECT m FROM StockMovement m
             WHERE m.businessId = :businessId
               AND m.itemId = :itemId
               AND m.movementType IN :types
               AND (:branchId IS NULL OR m.branchId = :branchId)
             ORDER BY m.createdAt DESC
            """)
    List<StockMovement> findInboundForItem(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("branchId") String branchId,
            @Param("types") Collection<String> types,
            Pageable pageable
    );
}
