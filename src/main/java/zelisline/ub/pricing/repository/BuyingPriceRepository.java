package zelisline.ub.pricing.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.pricing.domain.BuyingPrice;

public interface BuyingPriceRepository extends JpaRepository<BuyingPrice, String> {

    @Query("""
            select bp from BuyingPrice bp
             where bp.businessId = :businessId
               and bp.itemId = :itemId
               and (:supplierId is null or bp.supplierId = :supplierId)
             order by bp.effectiveFrom desc, bp.id desc
            """)
    List<BuyingPrice> findLatestRows(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("supplierId") String supplierId,
            Pageable pageable
    );

    @Query("""
            select bp from BuyingPrice bp
             where bp.businessId = :businessId
               and bp.itemId = :itemId
               and bp.supplierId = :supplierId
               and bp.effectiveTo is null
            """)
    List<BuyingPrice> findOpenEnded(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("supplierId") String supplierId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BuyingPrice bp
               set bp.effectiveTo = :closeTo
             where bp.businessId = :businessId
               and bp.itemId = :itemId
               and bp.supplierId = :supplierId
               and bp.effectiveTo is null
               and bp.effectiveFrom < :newFrom
            """)
    int closeOpenRowsEffectiveBefore(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("supplierId") String supplierId,
            @Param("newFrom") LocalDate newFrom,
            @Param("closeTo") LocalDate closeTo
    );
}
