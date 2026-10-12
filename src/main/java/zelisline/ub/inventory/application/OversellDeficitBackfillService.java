package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;

/**
 * One-off repair for the historical oversell drift.
 *
 * <p>Before the picker carried oversold units on a deficit batch, a sale that exceeded its batches
 * wrote a batch-less {@code sale} movement and decremented <em>no</em> batch, so the branch on-hand
 * ({@code SUM(active batch remaining)}) stayed permanently inflated by every unit sold beyond the
 * batches. This service rebuilds that missing deficit: for each (item, branch) it creates a single
 * {@code oversell_deficit} batch whose negative {@code quantity_remaining} equals the item/branch's
 * batch-less sale outflows, so the on-hand nets and later receipts cancel it out.
 *
 * <p>Safe to re-run: each repaired (item, branch) is tagged {@code source_id = "backfill"} and
 * skipped on subsequent runs, so nothing is double-counted. Item/branches with no batches at all
 * (till-only items, whose stock never lived in the cloud ledger) are skipped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OversellDeficitBackfillService {

    private static final int QTY_SCALE = 4;
    private static final String BACKFILL_SOURCE_ID = InventoryConstants.OVERSELL_BACKFILL_SOURCE_ID;

    private final StockMovementRepository stockMovementRepository;
    private final InventoryBatchRepository inventoryBatchRepository;

    /**
     * @param businessId repair a single tenant, or {@code null} to repair every tenant.
     */
    @Transactional
    public BackfillSummary run(String businessId) {
        List<Object[]> rows = stockMovementRepository.findBatchlessOutflowsByItemBranch(
                businessId, InventoryConstants.MOVEMENT_SALE);

        int created = 0;
        int skippedExisting = 0;
        int skippedNoBatches = 0;
        BigDecimal totalDeficit = BigDecimal.ZERO;

        for (Object[] row : rows) {
            String bizId = (String) row[0];
            String branchId = (String) row[1];
            String itemId = (String) row[2];
            BigDecimal deficit = (BigDecimal) row[3];
            BigDecimal unitCost = (BigDecimal) row[4];
            if (deficit == null || deficit.signum() >= 0) {
                continue;
            }
            if (!inventoryBatchRepository.existsByBusinessIdAndItemIdAndBranchId(bizId, itemId, branchId)) {
                // Till-only item: the cloud ledger never held its stock, so there is nothing to net.
                skippedNoBatches++;
                continue;
            }
            if (inventoryBatchRepository.existsByBusinessIdAndItemIdAndBranchIdAndSourceTypeAndSourceId(
                    bizId, itemId, branchId, InventoryConstants.BATCH_SOURCE_OVERSELL, BACKFILL_SOURCE_ID)) {
                skippedExisting++;
                continue;
            }
            inventoryBatchRepository.save(buildDeficitBatch(bizId, branchId, itemId, deficit, unitCost));
            created++;
            totalDeficit = totalDeficit.add(deficit);
        }

        BackfillSummary summary = new BackfillSummary(
                rows.size(), created, skippedExisting, skippedNoBatches,
                totalDeficit.setScale(QTY_SCALE, RoundingMode.HALF_UP));
        log.info("Oversell deficit backfill (businessId={}): {}", businessId, summary);
        return summary;
    }

    private InventoryBatch buildDeficitBatch(
            String businessId,
            String branchId,
            String itemId,
            BigDecimal deficit,
            BigDecimal unitCost
    ) {
        InventoryBatch b = new InventoryBatch();
        b.setBusinessId(businessId);
        b.setBranchId(branchId);
        b.setItemId(itemId);
        b.setSupplyBatchId(null);
        b.setSupplierId(null);
        b.setBatchNumber("OD-" + itemId.substring(0, Math.min(12, itemId.length()))
                + "-" + branchId.substring(0, Math.min(8, branchId.length())));
        b.setSourceType(InventoryConstants.BATCH_SOURCE_OVERSELL);
        b.setSourceId(BACKFILL_SOURCE_ID);
        b.setInitialQuantity(BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP));
        b.setQuantityRemaining(deficit.setScale(QTY_SCALE, RoundingMode.HALF_UP));
        b.setUnitCost(unitCost == null
                ? BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP)
                : unitCost);
        b.setReceivedAt(Instant.now());
        b.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        return b;
    }

    public record BackfillSummary(
            int candidates,
            int created,
            int skippedExisting,
            int skippedNoBatches,
            BigDecimal totalDeficit
    ) {
    }
}
