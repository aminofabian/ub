package zelisline.ub.pharmacy.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.PurchaseOrderAllocation;

public interface PurchaseOrderAllocationRepository extends JpaRepository<PurchaseOrderAllocation, String> {

    List<PurchaseOrderAllocation> findByBusinessIdAndPurchaseOrderId(String businessId, String purchaseOrderId);

    /** Allocations no transfer has been drafted for yet. */
    List<PurchaseOrderAllocation> findByBusinessIdAndTransferIdIsNullOrderByCreatedAtAsc(String businessId);
}
