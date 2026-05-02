package zelisline.ub.catalog.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.ItemTypeRepository;

/**
 * Seeds default catalog rows for a new tenant (PHASE_1_PLAN.md §4.4).
 */
@Service
@RequiredArgsConstructor
public class CatalogBootstrapService {

    private final ItemTypeRepository itemTypeRepository;

    @Transactional
    public void seedDefaultItemTypesIfMissing(String businessId) {
        seedIfMissing(businessId, "goods", "Goods", 0);
        seedIfMissing(businessId, "service", "Service", 1);
        seedIfMissing(businessId, "kit", "Kit", 2);
    }

    private void seedIfMissing(String businessId, String key, String label, int sortOrder) {
        if (itemTypeRepository.existsByBusinessIdAndTypeKey(businessId, key)) {
            return;
        }
        ItemType row = new ItemType();
        row.setBusinessId(businessId);
        row.setTypeKey(key);
        row.setLabel(label);
        row.setSortOrder(sortOrder);
        row.setActive(true);
        itemTypeRepository.save(row);
    }
}
