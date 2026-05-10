package zelisline.ub.catalog.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.repository.ItemTypeRepository;

/**
 * Seeds default catalog rows for a new tenant (PHASE_1_PLAN.md §4.4).
 * Item types are now fully user-managed — no defaults are seeded.
 */
@Service
@RequiredArgsConstructor
public class CatalogBootstrapService {

    private final ItemTypeRepository itemTypeRepository;

    @Transactional
    public void seedDefaultItemTypesIfMissing(String businessId) {
        // Item types are fully user-managed. No hardcoded defaults.
    }
}
