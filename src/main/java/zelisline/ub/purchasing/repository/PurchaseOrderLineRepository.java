package zelisline.ub.purchasing.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.PurchaseOrderLine;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, String> {

    List<PurchaseOrderLine> findByPurchaseOrderIdOrderBySortOrderAscIdAsc(String purchaseOrderId);

    @Query("select coalesce(max(l.sortOrder), -1) from PurchaseOrderLine l where l.purchaseOrderId = :poId")
    int maxSortOrder(@Param("poId") String purchaseOrderId);
}
