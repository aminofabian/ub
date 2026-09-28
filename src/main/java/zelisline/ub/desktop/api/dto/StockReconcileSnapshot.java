package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One-time reconciliation: the till sends its authoritative per-item stock and
 * the cloud sets its own {@code current_stock} to match (writing an adjustment
 * movement), so a cloud mirror that drifted before desktop stock sync existed
 * is brought back in line. The till skips the resulting movements on replay
 * because it originated them.
 */
public record StockReconcileSnapshot(
        String branchId,
        List<ItemStock> items
) {
    public record ItemStock(String itemId, BigDecimal currentStock) {}
}
