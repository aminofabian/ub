package zelisline.ub.suppliers.application;

import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;

@Service
@RequiredArgsConstructor
public class SupplierProductPrimaryService {

    private final ItemRepository itemRepository;
    private final SupplierProductRepository supplierProductRepository;

    @Transactional
    public void normalizeAfterChange(String businessId, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        List<SupplierProduct> all = supplierProductRepository.findByItemIdAndDeletedAtIsNull(itemId);
        if (!item.isSellable() || !item.isStocked()) {
            for (SupplierProduct sp : all) {
                if (sp.isPrimaryLink()) {
                    sp.setPrimaryLink(false);
                }
            }
            supplierProductRepository.saveAll(all);
            return;
        }
        List<SupplierProduct> active = all.stream()
                .filter(SupplierProduct::isActive)
                .toList();
        if (active.isEmpty()) {
            return;
        }
        SupplierProduct winner = active.stream()
                .max(Comparator.comparing(SupplierProduct::getUpdatedAt).thenComparing(SupplierProduct::getId))
                .orElseThrow();
        for (SupplierProduct sp : all) {
            if (!sp.isActive()) {
                if (sp.isPrimaryLink()) {
                    sp.setPrimaryLink(false);
                }
                continue;
            }
            boolean isWinner = sp.getId().equals(winner.getId());
            if (sp.isPrimaryLink() != isWinner) {
                sp.setPrimaryLink(isWinner);
            }
        }
        supplierProductRepository.saveAll(all);
    }
}
