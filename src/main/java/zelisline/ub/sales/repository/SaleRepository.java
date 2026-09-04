package zelisline.ub.sales.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.sales.domain.Sale;

public interface SaleRepository extends JpaRepository<Sale, String> {

    Optional<Sale> findByBusinessIdAndIdempotencyKey(String businessId, String idempotencyKey);

    Optional<Sale> findByIdAndBusinessId(String id, String businessId);

    List<Sale> findByBusinessIdAndCustomerIdOrderBySoldAtDesc(String businessId, String customerId);

    List<Sale> findByBusinessIdAndCustomerIdOrderBySoldAtDesc(
            String businessId, String customerId, org.springframework.data.domain.Pageable pageable);

    List<Sale> findByBusinessIdAndSoldByOrderBySoldAtDesc(String businessId, String soldBy);

    List<Sale> findByShiftIdAndStatus(String shiftId, String status);

    /** Sales the desktop till has not yet uploaded to the cloud (realtime sync). */
    List<Sale> findByBusinessIdAndCloudSyncedAtIsNullOrderBySoldAtAsc(String businessId);

    /** Unsynced sales still left in a shift (used to decide when to stamp the shift). */
    long countByShiftIdAndCloudSyncedAtIsNull(String shiftId);

    /** Cloud-side incremental pull for the desktop: sales at/after the cursor. */
    List<Sale> findByBusinessIdAndSoldAtGreaterThanEqualOrderBySoldAtAsc(
            String businessId,
            java.time.Instant soldAt,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Till and cloud allocate receipt numbers independently (MAX+1), so a
     * mirrored cloud sale can collide with a local one — checked before insert
     * to avoid tripping {@code uq_sales_business_receipt_no}.
     */
    boolean existsByBusinessIdAndReceiptNo(String businessId, Long receiptNo);

    @Query(
            value = """
                    SELECT COUNT(*),
                           COALESCE(SUM(s.grand_total), 0)
                      FROM sales s
                     WHERE s.business_id = :businessId
                       AND s.status = 'completed'
                       AND s.voided_at IS NULL
                       AND s.sold_at >= :since
                       AND s.sold_at < :until
                    """,
            nativeQuery = true
    )
    List<Object[]> aggregateSalesBetween(
            @Param("businessId") String businessId,
            @Param("since") java.time.Instant since,
            @Param("until") java.time.Instant until
    );

    @Query(
            value = """
                    SELECT COUNT(*),
                           COALESCE(SUM(s.grand_total), 0)
                      FROM sales s
                     WHERE s.business_id = :businessId
                       AND s.status = 'completed'
                       AND s.voided_at IS NULL
                    """,
            nativeQuery = true
    )
    List<Object[]> aggregateSalesAllTime(@Param("businessId") String businessId);

    @Query(
            value = """
                    SELECT COALESCE(SUM(si.quantity), 0)
                      FROM sale_items si
                      INNER JOIN sales s ON s.id = si.sale_id
                     WHERE s.business_id = :businessId
                       AND s.status = 'completed'
                       AND s.voided_at IS NULL
                       AND si.line_kind = 'ITEM'
                       AND si.item_id IS NOT NULL
                       AND s.sold_at >= :since
                       AND s.sold_at < :until
                    """,
            nativeQuery = true
    )
    java.math.BigDecimal unitsSoldBetween(
            @Param("businessId") String businessId,
            @Param("since") java.time.Instant since,
            @Param("until") java.time.Instant until
    );

    @Query(
            value = """
                    SELECT COALESCE(SUM(si.quantity), 0)
                      FROM sale_items si
                      INNER JOIN sales s ON s.id = si.sale_id
                     WHERE s.business_id = :businessId
                       AND s.status = 'completed'
                       AND s.voided_at IS NULL
                       AND si.line_kind = 'ITEM'
                       AND si.item_id IS NOT NULL
                    """,
            nativeQuery = true
    )
    java.math.BigDecimal unitsSoldAllTime(@Param("businessId") String businessId);

    @Query(
            value = """
                    SELECT MAX(s.sold_at)
                      FROM sales s
                     WHERE s.business_id = :businessId
                       AND s.status = 'completed'
                       AND s.voided_at IS NULL
                    """,
            nativeQuery = true
    )
    java.time.Instant findLastSaleAt(@Param("businessId") String businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sale s where s.id = :id and s.businessId = :businessId")
    Optional<Sale> findByIdAndBusinessIdForUpdate(@Param("id") String id, @Param("businessId") String businessId);

    /**
     * Current highest receipt number for the business (empty when none yet).
     *
     * <p>Locks the max row (and the trailing gap on the
     * {@code (business_id, receipt_no)} index) so concurrent sales for the same
     * business serialize instead of colliding; the unique index is the backstop.
     * Callers add 1 for the next number. A plain (non-aggregate) {@code FOR UPDATE}
     * select is used because MySQL/MariaDB (and H2's MySQL mode) reject
     * {@code FOR UPDATE} on grouped/aggregate queries, and Hibernate 7 forbids
     * {@code @Lock} on native queries.
     */
    @Query(
            value = "SELECT receipt_no FROM sales WHERE business_id = :businessId"
                    + " ORDER BY receipt_no DESC LIMIT 1 FOR UPDATE",
            nativeQuery = true
    )
    Optional<Long> nextReceiptNo(@Param("businessId") String businessId);

    /** Read-only latest receipt number (no row lock). Prefer {@link #nextReceiptNo} when allocating. */
    @Query(
            value = "SELECT receipt_no FROM sales WHERE business_id = :businessId"
                    + " ORDER BY receipt_no DESC LIMIT 1",
            nativeQuery = true
    )
    Optional<Long> findLatestReceiptNo(@Param("businessId") String businessId);

    boolean existsByBusinessId(String businessId);

    boolean existsByBusinessIdAndStatusAndVoidedAtIsNull(String businessId, String status);

    @Query("""
            select s.customerId,
                   count(s),
                   coalesce(sum(s.grandTotal), 0),
                   min(s.soldAt),
                   max(s.soldAt)
              from Sale s
             where s.businessId = :businessId
               and s.customerId is not null
               and s.status = 'completed'
               and s.voidedAt is null
             group by s.customerId
            """)
    List<Object[]> aggregatePurchaseStatsByCustomer(@Param("businessId") String businessId);
}
