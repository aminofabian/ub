package zelisline.ub.wholesale.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.wholesale.domain.SalesOrder;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, String> {

    Optional<SalesOrder> findByIdAndBusinessId(String id, String businessId);

    Page<SalesOrder> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    Page<SalesOrder> findByBusinessIdAndStatusOrderByCreatedAtDesc(String businessId, String status, Pageable pageable);

    long countByBusinessIdAndBuyerPhoneAndStatus(String businessId, String buyerPhone, String status);

    java.util.List<SalesOrder> findTop2000ByBusinessIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            String businessId, java.time.Instant since);

    long countByBusinessIdAndBuyerPhoneKeyAndStatus(String businessId, String buyerPhoneKey, String status);

    /** What an account's orders that have not been invoiced yet add up to. Null when there are none. */
    @Query("""
            select sum(o.total) from SalesOrder o
             where o.businessId = :businessId
               and o.tradeAccountId = :accountId
               and o.status in ('placed', 'accepted', 'part_accepted')
            """)
    java.math.BigDecimal sumOpenOrdersForAccount(
            @Param("businessId") String businessId, @Param("accountId") String accountId);

    boolean existsByBusinessId(String businessId);

    /** Orders this shop answered since a moment. Orders from before answer times were kept are not here. */
    java.util.List<SalesOrder> findByBusinessIdAndAnsweredAtGreaterThanEqual(String businessId, java.time.Instant since);
}
