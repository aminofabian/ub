package zelisline.ub.pricing.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.pricing.domain.SellingPrice;

public interface SellingPriceRepository extends JpaRepository<SellingPrice, String> {

    @Query("""
            select sp from SellingPrice sp
             where sp.businessId = :businessId
               and sp.itemId = :itemId
               and ((:branchId is null and sp.branchId is null) or sp.branchId = :branchId)
               and sp.effectiveTo is null
            """)
    List<SellingPrice> findOpenEnded(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("branchId") String branchId
    );

    @Query("""
            select sp from SellingPrice sp
             where sp.businessId = :businessId
               and sp.itemId in :itemIds
               and ((:branchId is null and sp.branchId is null) or sp.branchId = :branchId)
               and sp.effectiveTo is null
            """)
    List<SellingPrice> findOpenEndedForBranchAndItemIds(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("itemIds") Collection<String> itemIds
    );

    @Query("""
            select sp from SellingPrice sp
             where sp.businessId = :businessId
               and sp.itemId in :itemIds
               and sp.branchId is null
               and sp.effectiveTo is null
            """)
    List<SellingPrice> findOpenEndedBusinessWideForItemIds(
            @Param("businessId") String businessId,
            @Param("itemIds") Collection<String> itemIds
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SellingPrice sp
               set sp.effectiveTo = :closeTo
             where sp.businessId = :businessId
               and sp.itemId = :itemId
               and ((:branchId is null and sp.branchId is null) or sp.branchId = :branchId)
               and sp.effectiveTo is null
               and sp.effectiveFrom < :newFrom
            """)
    int closeOpenRowsEffectiveBefore(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("branchId") String branchId,
            @Param("newFrom") LocalDate newFrom,
            @Param("closeTo") LocalDate closeTo
    );

    /** Close every open-ended selling price row for an item (branch-specific and business-wide). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SellingPrice sp
               set sp.effectiveTo = :closeTo
             where sp.businessId = :businessId
               and sp.itemId = :itemId
               and sp.effectiveTo is null
            """)
    int closeAllOpenRowsForItem(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("closeTo") LocalDate closeTo
    );
}
