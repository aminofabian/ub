package zelisline.ub.inventory.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.StockAdjustmentRequest;

public interface StockAdjustmentRequestRepository extends JpaRepository<StockAdjustmentRequest, String> {

    Optional<StockAdjustmentRequest> findByIdAndBusinessId(String id, String businessId);

    List<StockAdjustmentRequest> findByStockTakeLine_Session_IdOrderByCreatedAtAsc(String sessionId);

    @Query("""
            select r from StockAdjustmentRequest r
             join fetch r.stockTakeLine l
             join fetch l.session s
             where r.id = :id and r.businessId = :businessId
            """)
    Optional<StockAdjustmentRequest> findByIdAndBusinessIdFetchLineAndSession(
            @Param("id") String id,
            @Param("businessId") String businessId
    );
}
