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

    long countByBusinessIdAndStatus(String businessId, String status);

    java.util.List<SalesOrder> findTop200ByBusinessIdAndStatusOrderByCreatedAtAsc(String businessId, String status);

    /** Shops with an order in this status placed before a moment. */
    @Query("""
            select distinct o.businessId from SalesOrder o
             where o.status = :status
               and o.createdAt < :before
            """)
    java.util.List<String> findBusinessIdsWithOrdersPlacedBefore(
            @Param("status") String status, @Param("before") java.time.Instant before);

    java.util.Optional<SalesOrder> findFirstByBusinessIdAndStatusOrderByCreatedAtAsc(String businessId, String status);

    /** Trade orders this shop supplied, in full or in part. Counter orders and rejected or cancelled ones are not. */
    @Query("""
            select count(o) from SalesOrder o
             where o.businessId = :businessId
               and o.answeredAt is not null
               and o.status in ('accepted', 'part_accepted', 'dispatched')
               and o.source <> 'counter'
            """)
    long countSupplied(@Param("businessId") String businessId);

    /** Buyer shops on Kiosk that have ordered from this shop more than once. Counter orders are not counted. */
    @Query("""
            select o.buyerBusinessId from SalesOrder o
             where o.businessId = :businessId
               and o.buyerBusinessId is not null
               and o.buyerBusinessId <> :businessId
               and o.source <> 'counter'
             group by o.buyerBusinessId
            having count(o) >= 2
            """)
    java.util.List<String> findRepeatBuyerBusinessIds(@Param("businessId") String businessId);

    /** Orders this shop answered since a moment. Orders from before answer times were kept are not here. */
    java.util.List<SalesOrder> findByBusinessIdAndAnsweredAtGreaterThanEqual(String businessId, java.time.Instant since);
}
