package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;

/**
 * Storefront on-hand quantities: package / shared-stock SKUs sell in whole packages
 * but deduct from the parent product's batches (see {@link PackageVariantStockResolver}).
 */
@Service
@RequiredArgsConstructor
public class StorefrontCatalogStockService {

    private final PackageVariantStockResolver packageVariantStockResolver;
    private final InventoryBatchRepository inventoryBatchRepository;

    public Map<String, BigDecimal> displayQtyForItems(
            String businessId,
            String branchId,
            List<Item> catalogItems
    ) {
        if (catalogItems.isEmpty()) {
            return Map.of();
        }
        Set<String> poolIds = new LinkedHashSet<>();
        for (Item item : catalogItems) {
            poolIds.addAll(packageVariantStockResolver.branchStockPoolItemIds(businessId, item));
        }
        Map<String, BigDecimal> raw = loadRawBatchQty(businessId, branchId, poolIds);
        Map<String, BigDecimal> out = new HashMap<>();
        for (Item item : catalogItems) {
            BigDecimal holder = packageVariantStockResolver.sumPoolStock(item, raw);
            out.put(item.getId(), packageVariantStockResolver.displayStockQty(item, holder));
        }
        return out;
    }

    public BigDecimal displayQtyForItem(String businessId, String branchId, Item item) {
        return displayQtyForItems(businessId, branchId, List.of(item))
                .getOrDefault(item.getId(), BigDecimal.ZERO);
    }

    private Map<String, BigDecimal> loadRawBatchQty(
            String businessId,
            String branchId,
            Collection<String> itemIds
    ) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> out = new HashMap<>();
        List<Object[]> rows = inventoryBatchRepository.sumQuantityRemainingForItemsAtBranch(
                businessId,
                branchId,
                InventoryConstants.BATCH_STATUS_ACTIVE,
                new ArrayList<>(itemIds));
        for (Object[] row : rows) {
            String id = (String) row[0];
            Object q = row[1];
            BigDecimal qty = q instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
            out.put(id, qty);
        }
        return out;
    }
}
