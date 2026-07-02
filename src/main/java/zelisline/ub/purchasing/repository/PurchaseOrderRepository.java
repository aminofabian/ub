package zelisline.ub.purchasing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.PurchaseOrder;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, String> {

    Optional<PurchaseOrder> findByIdAndBusinessId(String id, String businessId);

    List<PurchaseOrder> findByBusinessIdAndStatusOrderByCreatedAtDesc(String businessId, String status);

    List<PurchaseOrder> findByBusinessIdAndSupplierIdAndStatusOrderByCreatedAtDesc(
            String businessId, String supplierId, String status);
}
