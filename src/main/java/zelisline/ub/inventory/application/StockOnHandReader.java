package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;

/**
 * What a branch can actually use of each product today: the stock in its active batches that has
 * not expired. Other modules read stock through this (the servings forecast, ADR 0026) rather than
 * through the inventory repositories. Read-only; it never moves stock.
 */
@Service
@RequiredArgsConstructor
public class StockOnHandReader {

    private final InventoryBatchRepository inventoryBatchRepository;

    /**
     * @param usable        on hand in batches that have not expired on the day asked about
     * @param expiringToday the part of {@code usable} whose batch expires that same day
     */
    public record Usable(BigDecimal usable, BigDecimal expiringToday) {
        public static final Usable NONE = new Usable(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Usable stock per item at one branch on a day. An item with no batch is absent from the map;
     * treat it as {@link Usable#NONE}. Scoped by business and branch.
     */
    @Transactional(readOnly = true)
    public Map<String, Usable> usableByItemAtBranch(
            String businessId, String branchId, Collection<String> itemIds, LocalDate day) {
        Map<String, Usable> out = new HashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return out;
        }
        Map<String, BigDecimal> today = sum(businessId, branchId, itemIds, day);
        Map<String, BigDecimal> afterToday = sum(businessId, branchId, itemIds, day.plusDays(1));
        for (Map.Entry<String, BigDecimal> entry : today.entrySet()) {
            BigDecimal later = afterToday.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            out.put(entry.getKey(), new Usable(entry.getValue(), entry.getValue().subtract(later).max(BigDecimal.ZERO)));
        }
        return out;
    }

    private Map<String, BigDecimal> sum(String businessId, String branchId, Collection<String> itemIds, LocalDate from) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Object[] row : inventoryBatchRepository.sumUsableQuantityRemainingForItemsAtBranch(
                businessId, branchId, InventoryConstants.BATCH_STATUS_ACTIVE, itemIds, from)) {
            Object value = row[1];
            out.put((String) row[0], value instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(value)));
        }
        return out;
    }
}
