package zelisline.ub.inventory.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;

/**
 * Read queries behind the stock-safety screens (quarantine, expiry, missing lot, recall).
 * Every query is scoped by {@code businessId}. Rows that pair a batch with its item are
 * {@code [InventoryBatch, Item]}.
 */
public interface BatchSafetyRepository extends Repository<InventoryBatch, String> {

    @Query("""
            select b, i
              from InventoryBatch b
              join zelisline.ub.catalog.domain.Item i on i.id = b.itemId
             where b.businessId = :businessId
               and i.businessId = :businessId
               and b.status = :status
               and (:branchId is null or b.branchId = :branchId)
             order by b.quarantinedAt desc, b.id asc
            """)
    List<Object[]> findByStatus(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("branchId") String branchId,
            Pageable pageable
    );

    @Query("""
            select count(b)
              from InventoryBatch b
             where b.businessId = :businessId
               and b.status = :status
               and (:branchId is null or b.branchId = :branchId)
            """)
    long countByStatus(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("branchId") String branchId
    );

    /** On-sale batches with stock whose expiry falls in {@code [from, until]} (both bounds required). */
    @Query("""
            select b, i
              from InventoryBatch b
              join zelisline.ub.catalog.domain.Item i on i.id = b.itemId
             where b.businessId = :businessId
               and i.businessId = :businessId
               and b.status = 'active'
               and b.quantityRemaining > :zero
               and b.expiryDate is not null
               and b.expiryDate >= :fromDay
               and b.expiryDate <= :untilDay
               and (:branchId is null or b.branchId = :branchId)
             order by b.expiryDate asc, b.id asc
            """)
    List<Object[]> findOnSaleByExpiryWindow(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("fromDay") LocalDate fromDay,
            @Param("untilDay") LocalDate untilDay,
            @Param("zero") BigDecimal zero,
            Pageable pageable
    );

    @Query("""
            select count(b)
              from InventoryBatch b
             where b.businessId = :businessId
               and b.status = 'active'
               and b.quantityRemaining > :zero
               and b.expiryDate is not null
               and b.expiryDate >= :fromDay
               and b.expiryDate <= :untilDay
               and (:branchId is null or b.branchId = :branchId)
            """)
    long countOnSaleByExpiryWindow(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("fromDay") LocalDate fromDay,
            @Param("untilDay") LocalDate untilDay,
            @Param("zero") BigDecimal zero
    );

    /** On-sale medicine batches with stock that lack a lot number or an expiry date. */
    @Query("""
            select b, i
              from InventoryBatch b
              join zelisline.ub.catalog.domain.Item i on i.id = b.itemId
             where b.businessId = :businessId
               and i.businessId = :businessId
               and b.status = 'active'
               and b.quantityRemaining > :zero
               and (b.lotNumber is null or b.expiryDate is null)
               and (:branchId is null or b.branchId = :branchId)
               and (b.itemId in (select d.itemId from zelisline.ub.pharmacy.domain.ItemMedicineDetails d
                                  where d.businessId = :businessId)
                    or b.itemId in (select v.variantOfItemId from zelisline.ub.catalog.domain.Item v
                                     where v.businessId = :businessId
                                       and v.variantOfItemId is not null
                                       and v.id in (select d2.itemId
                                                      from zelisline.ub.pharmacy.domain.ItemMedicineDetails d2
                                                     where d2.businessId = :businessId)))
             order by b.receivedAt desc, b.id asc
            """)
    List<Object[]> findMedicineBatchesMissingDetails(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("zero") BigDecimal zero,
            Pageable pageable
    );

    @Query("""
            select count(b)
              from InventoryBatch b
             where b.businessId = :businessId
               and b.status = 'active'
               and b.quantityRemaining > :zero
               and (b.lotNumber is null or b.expiryDate is null)
               and (:branchId is null or b.branchId = :branchId)
               and (b.itemId in (select d.itemId from zelisline.ub.pharmacy.domain.ItemMedicineDetails d
                                  where d.businessId = :businessId)
                    or b.itemId in (select v.variantOfItemId from zelisline.ub.catalog.domain.Item v
                                     where v.businessId = :businessId
                                       and v.variantOfItemId is not null
                                       and v.id in (select d2.itemId
                                                      from zelisline.ub.pharmacy.domain.ItemMedicineDetails d2
                                                     where d2.businessId = :businessId)))
            """)
    long countMedicineBatchesMissingDetails(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("zero") BigDecimal zero
    );

    /** Every batch of a lot (any status), optionally narrowed to one stock item. */
    @Query("""
            select b, i
              from InventoryBatch b
              join zelisline.ub.catalog.domain.Item i on i.id = b.itemId
             where b.businessId = :businessId
               and i.businessId = :businessId
               and lower(b.lotNumber) = :lotLower
               and (:itemId is null or b.itemId = :itemId)
             order by b.receivedAt asc, b.id asc
            """)
    List<Object[]> findByLot(
            @Param("businessId") String businessId,
            @Param("lotLower") String lotLower,
            @Param("itemId") String itemId,
            Pageable pageable
    );

    /** Rows are {@code [SaleItem, Sale]} for till sales that drew on any of the batches. */
    @Query("""
            select si, s
              from zelisline.ub.sales.domain.SaleItem si
              join zelisline.ub.sales.domain.Sale s on s.id = si.saleId
             where s.businessId = :businessId
               and si.batchId in :batchIds
             order by s.soldAt desc, si.id asc
            """)
    List<Object[]> findSaleLinesForBatches(
            @Param("businessId") String businessId,
            @Param("batchIds") Collection<String> batchIds,
            Pageable pageable
    );

    /** Stock movements of one reference type (for example online orders) that drew on the batches. */
    @Query("""
            select m
              from StockMovement m
             where m.businessId = :businessId
               and m.batchId in :batchIds
               and m.referenceType = :referenceType
             order by m.createdAt desc, m.id asc
            """)
    List<StockMovement> findMovementsForBatches(
            @Param("businessId") String businessId,
            @Param("batchIds") Collection<String> batchIds,
            @Param("referenceType") String referenceType,
            Pageable pageable
    );
}
