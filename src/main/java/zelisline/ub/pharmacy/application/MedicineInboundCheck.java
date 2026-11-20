package zelisline.ub.pharmacy.application;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;

/**
 * {@link MedicineReceivingGuard} for callers that only hold the received item's id: resolves the
 * item, its parent and its stock holder, then applies the lot-and-expiry rule.
 *
 * <p>Applied where a person enters stock. Internal writers (first-sale seeding, stock-take gains,
 * catalogue imports) are not people at a shelf with a pack in hand, so they are not blocked here;
 * the batches they create show up in the "missing lot or expiry" list instead.</p>
 */
@Service
@RequiredArgsConstructor
public class MedicineInboundCheck {

    private final ItemRepository itemRepository;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final MedicineReceivingGuard medicineReceivingGuard;

    @Transactional(readOnly = true)
    public void requireLotAndExpiry(String businessId, String itemId, String lotNumber, LocalDate expiryDate) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId).orElse(null);
        if (item == null) {
            return; // the write itself reports the missing item
        }
        requireLotAndExpiry(businessId, item, lotNumber, expiryDate);
    }

    @Transactional(readOnly = true)
    public void requireLotAndExpiry(String businessId, Item item, String lotNumber, LocalDate expiryDate) {
        String holderId = packageVariantStockResolver.stockHolderItemId(item);
        medicineReceivingGuard.requireLotAndExpiry(
                businessId,
                MedicineRegistry.candidateIds(item, holderId),
                lotNumber,
                expiryDate,
                item.getName());
    }
}
