package zelisline.ub.purchasing.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.SupplierInvoiceLine;

public interface SupplierInvoiceLineRepository extends JpaRepository<SupplierInvoiceLine, String> {

    long countByInvoiceId(String invoiceId);

    List<SupplierInvoiceLine> findByInvoiceIdOrderBySortOrderAsc(String invoiceId);

    /**
     * Posted invoice-line totals for a set of SKUs.
     * Row: {@code [qty, spend]}.
     */
    @Query("""
            select coalesce(sum(sil.qty), 0),
                   coalesce(sum(sil.lineTotal), 0)
              from SupplierInvoiceLine sil
              join SupplierInvoice inv on inv.id = sil.invoiceId
             where inv.businessId = :businessId
               and sil.itemId in :itemIds
               and lower(inv.status) = 'posted'
            """)
    List<Object[]> aggregatePostedSpend(
            @Param("businessId") String businessId,
            @Param("itemIds") Collection<String> itemIds
    );

    @Query("""
            select inv.supplierId,
                   coalesce(sum(sil.qty), 0),
                   coalesce(sum(sil.lineTotal), 0)
              from SupplierInvoiceLine sil
              join SupplierInvoice inv on inv.id = sil.invoiceId
             where inv.businessId = :businessId
               and sil.itemId in :itemIds
               and lower(inv.status) = 'posted'
             group by inv.supplierId
             order by coalesce(sum(sil.lineTotal), 0) desc
            """)
    List<Object[]> spendBySupplier(
            @Param("businessId") String businessId,
            @Param("itemIds") Collection<String> itemIds
    );

    @Query("""
            select inv.id, inv.invoiceNumber, inv.invoiceDate, inv.supplierId, sil.itemId,
                   sil.qty, sil.unitCost, sil.lineTotal, inv.status
              from SupplierInvoiceLine sil
              join SupplierInvoice inv on inv.id = sil.invoiceId
             where inv.businessId = :businessId
               and sil.itemId in :itemIds
               and lower(inv.status) = 'posted'
             order by inv.invoiceDate desc, inv.createdAt desc
            """)
    List<Object[]> recentPostedLines(
            @Param("businessId") String businessId,
            @Param("itemIds") Collection<String> itemIds,
            Pageable pageable
    );
}
