package zelisline.ub.inventory.repository;

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
}
