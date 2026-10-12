package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.StockDriftLine;
import zelisline.ub.inventory.api.dto.StockDriftResponse;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Read-only preview of the oversell-deficit drift (see {@link OversellDeficitBackfillService}).
 * For each item/branch it reports the branch on-hand the POS shows today, the historical
 * batch-less sale outflows not yet carried, and the on-hand after the backfill repairs it — so an
 * operator can review before running the repair and verify afterwards.
 */
@Service
@RequiredArgsConstructor
public class InventoryDriftService {

    private static final int QTY_SCALE = 4;
    private static final int MAX_LIMIT = 500;

    private final StockMovementRepository stockMovementRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;

    /**
     * @param branchId optional single-branch scope; {@code null} for every branch in the tenant.
     * @param limit    max lines returned (1..{@value #MAX_LIMIT}).
     */
    @Transactional(readOnly = true)
    public StockDriftResponse drift(String businessId, String branchId, int limit) {
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Object[]> rows = stockMovementRepository.findBatchlessOutflowsByItemBranch(
                businessId, InventoryConstants.MOVEMENT_SALE);

        List<StockDriftLine> lines = new ArrayList<>();
        int candidates = 0;
        int repairable = 0;
        int alreadyRepaired = 0;
        int skippedNoBatches = 0;
        BigDecimal totalOutstanding = BigDecimal.ZERO;

        for (Object[] row : rows) {
            String bizId = (String) row[0];
            String rowBranchId = (String) row[1];
            String itemId = (String) row[2];
            BigDecimal outflow = (BigDecimal) row[3];
            if (outflow == null || outflow.signum() >= 0) {
                continue;
            }
            if (branchId != null && !branchId.isBlank() && !branchId.equals(rowBranchId)) {
                continue;
            }
            if (!inventoryBatchRepository.existsByBusinessIdAndItemIdAndBranchId(bizId, itemId, rowBranchId)) {
                // Till-only item: the cloud ledger never held its stock, so there is nothing to net.
                skippedNoBatches++;
                continue;
            }
            candidates++;
            boolean repaired = inventoryBatchRepository
                    .existsByBusinessIdAndItemIdAndBranchIdAndSourceTypeAndSourceId(
                            bizId, itemId, rowBranchId,
                            InventoryConstants.BATCH_SOURCE_OVERSELL,
                            InventoryConstants.OVERSELL_BACKFILL_SOURCE_ID);
            if (repaired) {
                alreadyRepaired++;
            } else {
                repairable++;
                totalOutstanding = totalOutstanding.add(outflow);
            }
            if (lines.size() < cap) {
                lines.add(toLine(bizId, rowBranchId, itemId, outflow, repaired));
            }
        }

        return new StockDriftResponse(
                candidates,
                repairable,
                alreadyRepaired,
                skippedNoBatches,
                totalOutstanding.setScale(QTY_SCALE, RoundingMode.HALF_UP),
                candidates > lines.size(),
                lines);
    }

    private StockDriftLine toLine(
            String businessId,
            String branchId,
            String itemId,
            BigDecimal outflow,
            boolean alreadyRepaired
    ) {
        BigDecimal batchOnHand = onHand(businessId, branchId, itemId);
        BigDecimal outstanding = alreadyRepaired
                ? BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP)
                : outflow.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId).orElse(null);
        String branchName = branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .map(b -> b.getName())
                .orElse(branchId);
        return new StockDriftLine(
                itemId,
                item != null ? item.getName() : itemId,
                item != null ? item.getSku() : null,
                branchId,
                branchName,
                batchOnHand,
                outflow.setScale(QTY_SCALE, RoundingMode.HALF_UP),
                batchOnHand.add(outstanding).setScale(QTY_SCALE, RoundingMode.HALF_UP),
                alreadyRepaired);
    }

    private BigDecimal onHand(String businessId, String branchId, String itemId) {
        List<Object[]> rows = inventoryBatchRepository.sumQuantityRemainingForItemsAtBranch(
                businessId, branchId, InventoryConstants.BATCH_STATUS_ACTIVE, List.of(itemId));
        if (rows.isEmpty() || !(rows.getFirst()[1] instanceof BigDecimal sum)) {
            return BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        }
        return sum.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }
}
