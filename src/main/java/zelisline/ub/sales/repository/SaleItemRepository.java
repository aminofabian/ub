package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.sales.domain.SaleItem;

public interface SaleItemRepository extends JpaRepository<SaleItem, String> {

    List<SaleItem> findBySaleIdOrderByLineIndexAsc(String saleId);

    java.util.Optional<SaleItem> findByIdAndSaleId(String id, String saleId);

    List<SaleItem> findByBatchId(String batchId);

    @Query("""
            SELECT COUNT(si) FROM SaleItem si
            INNER JOIN Sale s ON si.saleId = s.id
            WHERE s.businessId = :businessId AND si.itemId = :itemId
            """)
    long countByBusinessIdAndItemId(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId
    );
}
