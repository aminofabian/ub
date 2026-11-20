package zelisline.ub.pharmacy.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.StockMovement;

/** Read-only view of stock movements for the controlled-medicines register. */
public interface ControlledRegisterRepository extends Repository<StockMovement, String> {

    @Query("""
            select m from StockMovement m
             where m.businessId = :businessId
               and m.itemId = :itemId
               and m.movementType not in :excluded
               and m.createdAt >= :fromTs
               and m.createdAt < :toTs
             order by m.createdAt asc, m.id asc
            """)
    List<StockMovement> movements(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("excluded") Collection<String> excluded,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs,
            Pageable pageable);

    @Query("""
            select coalesce(sum(m.quantityDelta), 0) from StockMovement m
             where m.businessId = :businessId
               and m.itemId = :itemId
               and m.movementType not in :excluded
               and m.createdAt < :before
            """)
    BigDecimal balanceBefore(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("excluded") Collection<String> excluded,
            @Param("before") Instant before);
}
