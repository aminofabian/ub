package zelisline.ub.inventory.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.OrderPadItem;

public interface OrderPadItemRepository extends JpaRepository<OrderPadItem, String> {

    Optional<OrderPadItem> findByIdAndBusinessId(String id, String businessId);

    @Query("""
            select o from OrderPadItem o
            where o.businessId = :businessId
              and o.branchId = :branchId
              and (:ordered is null or o.ordered = :ordered)
            order by o.ordered asc, o.createdAt desc
            """)
    List<OrderPadItem> findForBranch(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("ordered") Boolean ordered
    );

    /**
     * Unticked (not yet ordered) order-pad qty per item at a branch. Only rows with an
     * {@code item_id} count — free-text lines have no item to feed the restock engine.
     */
    interface OpenPadQtyRow {
        String getItemId();

        BigDecimal getQty();
    }

    @Query("""
            select o.itemId as itemId, coalesce(sum(o.quantity), 0) as qty
              from OrderPadItem o
             where o.businessId = :businessId
               and o.branchId = :branchId
               and o.ordered = false
               and o.itemId is not null
             group by o.itemId
            """)
    List<OpenPadQtyRow> sumOpenPadQtyByItem(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId
    );
}
