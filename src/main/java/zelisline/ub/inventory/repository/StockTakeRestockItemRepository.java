package zelisline.ub.inventory.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.StockTakeRestockItem;

public interface StockTakeRestockItemRepository extends JpaRepository<StockTakeRestockItem, String> {

    Optional<StockTakeRestockItem> findByIdAndBusinessId(String id, String businessId);

    @Query("""
            select r from StockTakeRestockItem r
             where r.businessId = :businessId
               and r.branchId = :branchId
               and r.dailyAuditId = :dailyAuditId
               and r.itemId = :itemId
               and r.supplierId = :supplierId
               and r.status = :status
             order by r.addedAt desc
            """)
    List<StockTakeRestockItem> findPendingForAuditItemAndSupplier(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("dailyAuditId") String dailyAuditId,
            @Param("itemId") String itemId,
            @Param("supplierId") String supplierId,
            @Param("status") String status
    );

    Optional<StockTakeRestockItem> findByBusinessIdAndBranchIdAndDailyAuditIdAndItemIdAndSupplierIdAndStatus(
            String businessId,
            String branchId,
            String dailyAuditId,
            String itemId,
            String supplierId,
            String status
    );

    @Query("""
            select r from StockTakeRestockItem r
             where r.businessId = :businessId
               and r.branchId = :branchId
               and r.dailyAuditId = :dailyAuditId
               and r.itemId = :itemId
               and r.status = :status
            """)
    List<StockTakeRestockItem> findPendingForAuditItem(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("dailyAuditId") String dailyAuditId,
            @Param("itemId") String itemId,
            @Param("status") String status
    );

    @Query("""
            select r from StockTakeRestockItem r
             where r.businessId = :businessId
               and r.branchId = :branchId
               and r.dailyAuditId = :dailyAuditId
               and (:status is null or r.status = :status)
               and (:supplierId is null or r.supplierId = :supplierId)
             order by r.supplierId asc, r.addedAt desc
            """)
    List<StockTakeRestockItem> findForReview(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("dailyAuditId") String dailyAuditId,
            @Param("status") String status,
            @Param("supplierId") String supplierId
    );

    @Query("""
            select r from StockTakeRestockItem r
             where r.businessId = :businessId
               and (:status is null or r.status = :status)
               and (:supplierId is null or r.supplierId = :supplierId)
               and (:from is null or r.orderDraftedAt >= :from)
               and (:to is null or r.orderDraftedAt <= :to)
               and r.orderNumber is not null
             order by r.orderDraftedAt desc, r.orderNumber desc
            """)
    List<StockTakeRestockItem> findOrderHistory(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("supplierId") String supplierId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    List<StockTakeRestockItem> findByBusinessIdAndOrderNumber(String businessId, String orderNumber);
}
