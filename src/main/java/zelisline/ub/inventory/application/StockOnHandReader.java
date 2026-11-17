package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * What a branch can actually use of each product today: the stock in its active batches that has
 * not expired. Other modules read stock through this (the servings forecast, ADR 0026) rather than
 * through the inventory repositories. Read-only; it never moves stock.
 */
@Service
@RequiredArgsConstructor
public class StockOnHandReader {

    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final SupplierRepository supplierRepository;

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

    /** Stock whose batch expires in a window: how much, and the first day any of it expires. */
    public record Expiring(BigDecimal quantity, LocalDate firstDay) {
    }

    /** Per item, the stock at a branch that expires from one day to another, both included. */
    @Transactional(readOnly = true)
    public Map<String, Expiring> expiringByItemAtBranch(
            String businessId, String branchId, Collection<String> itemIds, LocalDate fromDay, LocalDate untilDay) {
        Map<String, Expiring> out = new HashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return out;
        }
        for (Object[] row : inventoryBatchRepository.sumExpiringForItemsAtBranch(
                businessId, branchId, itemIds, fromDay, untilDay)) {
            out.put((String) row[0], new Expiring(decimal(row[1]), (LocalDate) row[2]));
        }
        return out;
    }

    /** The newest delivery of a product from a supplier: when, from whom, at what unit cost. */
    public record LastPurchase(Instant receivedAt, String supplierName, BigDecimal unitCost) {
    }

    /** Per item, the newest batch bought from a supplier at a branch in the last year. */
    @Transactional(readOnly = true)
    public Map<String, LastPurchase> lastPurchaseByItemAtBranch(
            String businessId, String branchId, Collection<String> itemIds) {
        Map<String, LastPurchase> out = new HashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return out;
        }
        Map<String, String> supplierNames = new HashMap<>();
        Instant since = Instant.now().minus(java.time.Duration.ofDays(365));
        for (InventoryBatch batch : inventoryBatchRepository.findNewestPurchaseForItemsAtBranchSince(
                businessId, branchId, itemIds, since)) {
            if (out.containsKey(batch.getItemId())) {
                continue;
            }
            String name = supplierNames.computeIfAbsent(batch.getSupplierId(), id -> supplierRepository
                    .findByIdAndBusinessId(id, businessId).map(Supplier::getName).orElse(""));
            out.put(batch.getItemId(),
                    new LastPurchase(batch.getReceivedAt(), name.isBlank() ? null : name, batch.getUnitCost()));
        }
        return out;
    }

    /** Stock written off as waste: of what, why, how much and what it had cost. Both are positive. */
    public record Loss(String itemId, String reason, BigDecimal quantity, BigDecimal cost) {
    }

    /**
     * What a branch wrote off as waste over {@code [from, to)}, one row per item and reason. Waste
     * recorded with no reason (a dish cooked and then cancelled) counts as {@code OTHER}.
     */
    @Transactional(readOnly = true)
    public List<Loss> lossesAtBranch(String businessId, String branchId, Instant from, Instant to) {
        Map<String, Loss> merged = new java.util.LinkedHashMap<>();
        for (Object[] row : stockMovementRepository.sumWastageByItemAndReason(businessId, branchId, from, to)) {
            String itemId = (String) row[0];
            String reason = row[1] == null || ((String) row[1]).isBlank() ? "OTHER" : (String) row[1];
            BigDecimal quantity = decimal(row[2]).negate();
            BigDecimal cost = decimal(row[3]).negate();
            merged.merge(itemId + "|" + reason, new Loss(itemId, reason, quantity, cost),
                    (a, b) -> new Loss(itemId, reason, a.quantity().add(b.quantity()), a.cost().add(b.cost())));
        }
        List<Loss> out = new ArrayList<>();
        for (Loss loss : merged.values()) {
            if (loss.quantity().signum() > 0) {
                out.add(loss);
            }
        }
        return out;
    }

    /** Everything on the shelf at a branch per item, expired batches included: what can be written off. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> onShelfByItemAtBranch(String businessId, String branchId, Collection<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return new HashMap<>();
        }
        return sum(businessId, branchId, itemIds, LocalDate.of(1970, 1, 1));
    }

    /** A batch on the shelf that is past its date, and how much is left in it. */
    public record ExpiredBatch(String batchId, BigDecimal quantity) {
    }

    /** An item's batches at a branch that expired before a day and still hold stock, oldest date first. */
    @Transactional(readOnly = true)
    public List<ExpiredBatch> expiredBatchesAtBranch(String businessId, String branchId, String itemId, LocalDate day) {
        List<ExpiredBatch> out = new ArrayList<>();
        for (InventoryBatch batch : inventoryBatchRepository.findExpiringOnOrBefore(
                businessId, branchId, null, day.minusDays(1))) {
            if (batch.getItemId().equals(itemId) && batch.getQuantityRemaining().signum() > 0) {
                out.add(new ExpiredBatch(batch.getId(), batch.getQuantityRemaining()));
            }
        }
        return out;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(value));
    }

    private Map<String, BigDecimal> sum(String businessId, String branchId, Collection<String> itemIds, LocalDate from) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Object[] row : inventoryBatchRepository.sumUsableQuantityRemainingForItemsAtBranch(
                businessId, branchId, InventoryConstants.BATCH_STATUS_ACTIVE, itemIds, from)) {
            out.put((String) row[0], decimal(row[1]));
        }
        return out;
    }
}
