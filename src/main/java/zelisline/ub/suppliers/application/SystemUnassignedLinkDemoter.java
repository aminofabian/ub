package zelisline.ub.suppliers.application;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Retires the synthetic "Unassigned (migrate)" supplier link(s) for an item so the item
 * leaves the merchant-facing "Suppliers Not Linked" bucket once a real supplier is linked.
 * Shared by the manual link flow and the global-catalog adopt linker so the demote behavior
 * can never drift between the two.
 */
@Component
@RequiredArgsConstructor
public class SystemUnassignedLinkDemoter {

    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;

    /**
     * Soft-delete the item's unassigned link(s), if any, and return the unassigned supplier id
     * (resolved by code fallback when no link row exists) so callers can migrate dependent rows
     * such as open-ended buying prices.
     */
    @Transactional
    public String demote(String businessId, String itemId) {
        String unassignedSupplierId = null;
        for (SupplierProduct link : supplierProductRepository.listForItem(businessId, itemId)) {
            Supplier supplier = supplierRepository.findById(link.getSupplierId()).orElse(null);
            if (supplier == null || !SupplierCodes.SYSTEM_UNASSIGNED.equals(supplier.getCode())) {
                continue;
            }
            unassignedSupplierId = supplier.getId();
            link.setPrimaryLink(false);
            link.setActive(false);
            link.setDeletedAt(Instant.now());
            supplierProductRepository.save(link);
        }
        if (unassignedSupplierId == null) {
            unassignedSupplierId = supplierRepository
                    .findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, SupplierCodes.SYSTEM_UNASSIGNED)
                    .map(Supplier::getId)
                    .orElse(null);
        }
        return unassignedSupplierId;
    }
}
