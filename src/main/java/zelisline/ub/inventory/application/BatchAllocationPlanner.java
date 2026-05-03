package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.inventory.CostMethod;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.purchasing.domain.InventoryBatch;

public final class BatchAllocationPlanner {

    private static final int QTY_SCALE = 4;

    private BatchAllocationPlanner() {
    }

    public static void sortBatchesForPick(List<InventoryBatch> batches, Item item, CostMethod costMethod) {
        Comparator<InventoryBatch> cmp;
        if (useFefo(item, batches)) {
            cmp = fefoComparator();
        } else if (costMethod == CostMethod.LIFO) {
            cmp = Comparator.comparing(InventoryBatch::getReceivedAt).reversed();
        } else {
            cmp = Comparator.comparing(InventoryBatch::getReceivedAt);
        }
        batches.sort(cmp);
    }

    private static boolean useFefo(Item item, List<InventoryBatch> batches) {
        if (!item.isHasExpiry()) {
            return false;
        }
        for (InventoryBatch b : batches) {
            if (b.getExpiryDate() != null) {
                return true;
            }
        }
        return false;
    }

    private static Comparator<InventoryBatch> fefoComparator() {
        return Comparator
                .comparing((InventoryBatch b) -> b.getExpiryDate() != null ? b.getExpiryDate() : LocalDate.MAX)
                .thenComparing(InventoryBatch::getReceivedAt);
    }

    public static List<BatchAllocationLine> allocateInOrder(List<InventoryBatch> sortedBatches, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick quantity must be positive");
        }
        List<BatchAllocationLine> out = new ArrayList<>();
        BigDecimal remaining = quantity.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        for (InventoryBatch b : sortedBatches) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal onHand = b.getQuantityRemaining().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            if (onHand.signum() <= 0) {
                continue;
            }
            BigDecimal take = onHand.min(remaining);
            out.add(new BatchAllocationLine(b.getId(), take, b.getUnitCost()));
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient stock for pick");
        }
        return out;
    }
}
