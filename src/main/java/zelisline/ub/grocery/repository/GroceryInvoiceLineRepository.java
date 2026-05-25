package zelisline.ub.grocery.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.grocery.domain.GroceryInvoiceLine;

public interface GroceryInvoiceLineRepository extends JpaRepository<GroceryInvoiceLine, String> {

    List<GroceryInvoiceLine> findByInvoiceIdOrderByLineIndex(String invoiceId);

    /**
     * Aggregate the items that appear most on a clerk's own (non-cancelled)
     * invoices for the given branch.
     *
     * <p>Returns rows of {@code [itemId, itemName, invoiceCount, totalQty,
     * lastInvoicedAt]}, ordered by invoice count then sum-of-quantity, then
     * recency. Caller bounds the result with a {@link Pageable} (e.g.
     * {@code PageRequest.of(0, 20)}).</p>
     */
    @Query("""
            select line.itemId,
                   max(line.itemName),
                   count(distinct inv.id),
                   coalesce(sum(line.quantity), 0),
                   max(inv.createdAt)
              from GroceryInvoiceLine line
              join GroceryInvoice inv on inv.id = line.invoiceId
             where inv.businessId = :businessId
               and inv.branchId   = :branchId
               and inv.createdBy  = :createdBy
               and inv.status     <> 'cancelled'
             group by line.itemId
             order by count(distinct inv.id) desc,
                      coalesce(sum(line.quantity), 0) desc,
                      max(inv.createdAt) desc
            """)
    List<Object[]> topItemsForUser(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("createdBy") String createdBy,
            Pageable pageable
    );
}
