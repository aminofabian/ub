package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
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

    /**
     * Aggregate best-selling items at a branch from completed, non-voided sales.
     *
     * <p>Returns rows of {@code [itemId, saleCount, totalQty, lastSoldAt]},
     * ordered by sum-of-quantity (units sold), then sale count, then recency.
     * Caller bounds the result with a {@link Pageable}.</p>
     */
    @Query("""
            select si.itemId,
                   count(distinct s.id),
                   coalesce(sum(si.quantity), 0),
                   max(s.soldAt)
              from SaleItem si
              join Sale s on s.id = si.saleId
              join Item item on item.id = si.itemId
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.status = 'completed'
               and s.voidedAt is null
               and (:itemTypeId is null or :itemTypeId = '' or item.itemTypeId = :itemTypeId)
             group by si.itemId
             order by coalesce(sum(si.quantity), 0) desc,
                      count(distinct s.id) desc,
                      max(s.soldAt) desc
            """)
    List<Object[]> topItemsByUnitsSold(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("itemTypeId") String itemTypeId,
            Pageable pageable
    );
}
