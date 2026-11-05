package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;

/**
 * Carries the unit cost of a received supply onto the product, so the price a shop last paid is the price it is
 * offered next time.
 *
 * <p>Receiving stock used to stamp only {@code supplier_products.last_cost_price}. The supplier link's
 * {@code default_cost_price} and the catalog {@code items.buying_price} are filled in once, when the product is
 * created, and nothing moved them afterwards. Restock suggestions, the product cost and margin views, and quick edit
 * read those two, so they stayed on the first price ever typed.
 *
 * <p>Last write wins, the same as the existing last cost: re-pricing an older supply bill re-points the defaults too.
 */
@Service
@RequiredArgsConstructor
public class SupplyCostDefaultsService {

    /** {@code items.buying_price} is {@code decimal(14,2)}. */
    private static final int CATALOG_PRICE_SCALE = 2;

    private final SupplierProductRepository supplierProductRepository;
    private final ItemRepository itemRepository;

    /**
     * @param unitCost what one catalog unit of {@code itemId} cost on this supply (a tray, not an egg). A zero or
     *                 negative cost (free goods) is recorded as the last cost but never overwrites the defaults.
     */
    @Transactional
    public void recordSupplyCost(String businessId, String supplierId, String itemId, BigDecimal unitCost) {
        boolean paid = unitCost != null && unitCost.signum() > 0;

        supplierProductRepository.findBySupplierIdAndItemId(supplierId, itemId)
                .filter(sp -> sp.getDeletedAt() == null && sp.isActive())
                .ifPresent(sp -> {
                    sp.setLastCostPrice(unitCost);
                    sp.setLastPurchaseAt(Instant.now());
                    if (paid) {
                        sp.setDefaultCostPrice(unitCost);
                    }
                    supplierProductRepository.save(sp);
                });

        if (!paid) {
            return;
        }
        BigDecimal catalogPrice = unitCost.setScale(CATALOG_PRICE_SCALE, RoundingMode.HALF_UP);
        if (catalogPrice.signum() <= 0) {
            return;
        }
        itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId).ifPresent(item -> {
            item.setBuyingPrice(catalogPrice);
            itemRepository.save(item);
        });
    }
}
