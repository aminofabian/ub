package zelisline.ub.suppliers.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;

@Service
@RequiredArgsConstructor
public class SupplierLinkProvisioner {

    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierProductPrimaryService primaryService;

    @Transactional
    public void afterItemChanged(String businessId, Item item) {
        if (item == null || item.getDeletedAt() != null) {
            return;
        }
        if (requiresAtLeastOneActiveLink(item)) {
            ensureForItem(businessId, item);
            return;
        }
        primaryService.normalizeAfterChange(businessId, item.getId());
    }

    @Transactional
    public void ensureForItem(String businessId, Item item) {
        if (!requiresAtLeastOneActiveLink(item)) {
            return;
        }
        if (supplierProductRepository.existsActiveByItemId(item.getId())) {
            primaryService.normalizeAfterChange(businessId, item.getId());
            return;
        }
        Supplier sys = getOrCreateSystemSupplier(businessId);
        SupplierProduct sp = new SupplierProduct();
        sp.setSupplierId(sys.getId());
        sp.setItemId(item.getId());
        sp.setPrimaryLink(true);
        sp.setActive(true);
        supplierProductRepository.save(sp);
        primaryService.normalizeAfterChange(businessId, item.getId());
    }

    private boolean requiresAtLeastOneActiveLink(Item item) {
        return item.isSellable() && item.isStocked();
    }

    private Supplier getOrCreateSystemSupplier(String businessId) {
        Optional<Supplier> existing = supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(
                businessId, SupplierCodes.SYSTEM_UNASSIGNED);
        if (existing.isPresent()) {
            return existing.get();
        }
        Supplier s = new Supplier();
        s.setBusinessId(businessId);
        s.setName("Unassigned (migrate)");
        s.setCode(SupplierCodes.SYSTEM_UNASSIGNED);
        s.setSupplierType("distributor");
        s.setStatus("active");
        s.setNotes("Synthetic supplier for items without an assigned vendor; replace with real suppliers.");
        return supplierRepository.save(s);
    }
}
