package zelisline.ub.marketplace.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.ItemCatalogService;

/**
 * Isolates item creates so a single catalogue import failure does not poison the attach TX.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceItemImportHelper {

    private final ItemCatalogService itemCatalogService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String createItemOrNull(String businessId, CreateItemRequest request) {
        try {
            var result = itemCatalogService.createItem(businessId, request, null);
            if (result.body() == null || result.body().id() == null) {
                return null;
            }
            return result.body().id();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
