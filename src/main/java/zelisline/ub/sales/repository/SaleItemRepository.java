package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

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
               and item.deletedAt is null
               and item.active = true
               and item.sellable = true
               and (:itemTypeId is null or :itemTypeId = '' or item.itemTypeId = :itemTypeId)
               and (item.variantOfItemId is not null
                    or not exists (
                      select 1 from Item ch
                       where ch.variantOfItemId = item.id
                         and ch.businessId = item.businessId
                         and ch.deletedAt is null
                    ))
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

    /**
     * Random sample of distinct items sold on a calendar day at a branch.
     * Uses native SQL for {@code ORDER BY RAND()}.
     */
    @Query(
            value = """
                    SELECT pool.item_id FROM (
                        SELECT DISTINCT si.item_id AS item_id
                          FROM sale_items si
                          INNER JOIN sales s ON s.id = si.sale_id
                          INNER JOIN items item ON item.id = si.item_id
                         WHERE s.business_id = :businessId
                           AND s.branch_id = :branchId
                           AND s.status = 'completed'
                           AND s.voided_at IS NULL
                           AND DATE(s.sold_at) = :soldOn
                           AND item.deleted_at IS NULL
                           AND item.active = true
                           AND item.is_sellable = true
                           AND (item.variant_of_item_id IS NOT NULL
                                OR NOT EXISTS (
                                    SELECT 1 FROM items ch
                                     WHERE ch.variant_of_item_id = item.id
                                       AND ch.business_id = item.business_id
                                       AND ch.deleted_at IS NULL
                                ))
                    ) pool
                    ORDER BY RAND()
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<String> findRandomSoldItemIds(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("soldOn") LocalDate soldOn,
            @Param("limit") int limit
    );
}
