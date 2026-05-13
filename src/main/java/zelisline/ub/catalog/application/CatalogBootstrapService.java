package zelisline.ub.catalog.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.repository.ItemTypeRepository;

/**
 * Seeds default catalog rows for a new tenant (PHASE_1_PLAN.md §4.4).
 * Seeds a single "Goods" type as the default if the business has no types yet.
 * Item types remain fully user-managed — the default can be renamed, deleted,
 * or supplemented with additional types.
 */
@Service
@RequiredArgsConstructor
public class CatalogBootstrapService {

    private final ItemTypeRepository itemTypeRepository;

    @Transactional
    public void seedDefaultItemTypesIfMissing(String businessId) {
        if (itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(businessId).isEmpty()) {
            zelisline.ub.catalog.domain.ItemType goods = new zelisline.ub.catalog.domain.ItemType();
            goods.setBusinessId(businessId);
            goods.setTypeKey("goods");
            goods.setLabel("Goods");
            goods.setIcon("package");
            goods.setSortOrder(0);
            goods.setActive(true);
            goods.setDefault(true);
            itemTypeRepository.save(goods);
        }
    }
}
