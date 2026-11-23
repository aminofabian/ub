package zelisline.ub.wholesale.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.wholesale.domain.SalesInvoice;

public interface SalesInvoiceRepository extends JpaRepository<SalesInvoice, String> {

    Optional<SalesInvoice> findByIdAndBusinessId(String id, String businessId);

    @org.springframework.data.jpa.repository.Query("""
            select i from SalesInvoice i
             where i.businessId = :businessId and i.updatedAt >= :since
             order by i.updatedAt asc
            """)
    java.util.List<SalesInvoice> findForDesktopSyncPull(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("since") java.time.Instant since,
            org.springframework.data.domain.Pageable pageable);

    Optional<SalesInvoice> findBySalesOrderIdAndBusinessId(String salesOrderId, String businessId);

    List<SalesInvoice> findTop2000ByBusinessIdAndIssuedOnGreaterThanEqual(String businessId, java.time.LocalDate since);

    List<SalesInvoice> findTop500ByBusinessIdOrderByIssuedOnDescCreatedAtDesc(String businessId);

    List<SalesInvoice> findTop500ByBusinessIdAndStatusInOrderByDueOnAsc(String businessId, Collection<String> statuses);

    /** What is still owed on an account's invoices. Null when there are none. */
    @Query("""
            select sum(i.total - i.amountPaid - i.amountCredited)
              from SalesInvoice i, SalesOrder o
             where o.id = i.salesOrderId
               and i.businessId = :businessId
               and o.tradeAccountId = :accountId
               and i.status in ('open', 'part_paid')
            """)
    java.math.BigDecimal sumOwedForAccount(
            @Param("businessId") String businessId, @Param("accountId") String accountId);
}
