package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.domain.StockTransfer;
import zelisline.ub.inventory.domain.StockTransferLine;
import zelisline.ub.inventory.domain.SupplyBatch;
import zelisline.ub.inventory.repository.StockTransferRepository;
import zelisline.ub.inventory.repository.SupplyBatchRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * One-off repair for transfers cancelled <em>before</em> {@link InventoryTransferService#cancelLine}
 * restored the source batch. Sending a transfer decrements the source batch and writes a
 * {@code transfer_out} movement; the old cancel only returned a movement but never added the stock
 * back, so the source branch's on-hand stayed permanently low.
 *
 * <p>For every line of a cancelled transfer this re-adds to each source batch the {@code
 * transfer_out} quantity that has no matching positive movement on that same batch, and records a
 * compensating movement so the repair is idempotent (a second run finds nothing to do). Sending may
 * have depleted the source line and closed its supply batch; both are reopened so the returned stock
 * counts and is sellable again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransferCancelBackfillService {

    private static final int QTY_SCALE = 4;

    private final StockTransferRepository stockTransferRepository;
    private final StockMovementRepository stockMovementRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final SupplyBatchRepository supplyBatchRepository;
    private final BusinessRepository businessRepository;

    /**
     * @param businessId repair a single tenant, or {@code null} to repair every tenant.
     * @param branchId   optional source-branch scope (matches from OR to); {@code null} for all.
     */
    @Transactional
    public BackfillSummary run(String businessId, String branchId) {
        List<String> businessIds = (businessId == null || businessId.isBlank())
                ? businessRepository.findAll().stream().map(Business::getId).toList()
                : List.of(businessId);
        List<StockTransfer> cancelled = new ArrayList<>();
        for (String biz : businessIds) {
            cancelled.addAll(stockTransferRepository.findByBusinessIdFiltered(
                    biz, InventoryConstants.TRANSFER_STATUS_CANCELLED, branchId));
        }

        int linesScanned = 0;
        int batchesRestored = 0;
        int alreadyRestored = 0;
        BigDecimal totalRestored = BigDecimal.ZERO;
        Set<String> supplyBatchesToReopen = new LinkedHashSet<>();

        for (StockTransfer t : cancelled) {
            for (StockTransferLine line : t.getLines()) {
                linesScanned++;
                Map<String, BigDecimal> outByBatch = new LinkedHashMap<>();
                Map<String, BigDecimal> backByBatch = new LinkedHashMap<>();
                Map<String, BigDecimal> costByBatch = new LinkedHashMap<>();
                for (StockMovement m : stockMovementRepository
                        .findByBusinessIdAndReferenceTypeAndReferenceId(
                                t.getBusinessId(), InventoryConstants.REF_STOCK_TRANSFER_LINE, line.getId())) {
                    if (m.getBatchId() == null || m.getQuantityDelta() == null) {
                        continue;
                    }
                    if (InventoryConstants.MOVEMENT_TRANSFER_OUT.equals(m.getMovementType())) {
                        outByBatch.merge(m.getBatchId(), m.getQuantityDelta().negate(), BigDecimal::add);
                        costByBatch.putIfAbsent(m.getBatchId(), m.getUnitCost());
                    } else if (m.getQuantityDelta().signum() > 0) {
                        backByBatch.merge(m.getBatchId(), m.getQuantityDelta(), BigDecimal::add);
                    }
                }
                for (Map.Entry<String, BigDecimal> out : outByBatch.entrySet()) {
                    String sourceBatchId = out.getKey();
                    BigDecimal outQty = out.getValue().setScale(QTY_SCALE, RoundingMode.HALF_UP);
                    if (outQty.signum() <= 0) {
                        continue;
                    }
                    BigDecimal restored = backByBatch.getOrDefault(sourceBatchId, BigDecimal.ZERO);
                    BigDecimal missing = outQty.subtract(restored).setScale(QTY_SCALE, RoundingMode.HALF_UP);
                    if (missing.signum() <= 0) {
                        alreadyRestored++;
                        continue;
                    }
                    InventoryBatch src = inventoryBatchRepository
                            .findByIdAndBusinessIdForUpdate(sourceBatchId, t.getBusinessId())
                            .orElse(null);
                    if (src == null) {
                        continue;
                    }
                    boolean wasDepleted = !InventoryConstants.BATCH_STATUS_ACTIVE.equals(src.getStatus());
                    src.setQuantityRemaining(src.getQuantityRemaining().add(missing)
                            .setScale(QTY_SCALE, RoundingMode.HALF_UP));
                    src.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
                    inventoryBatchRepository.save(src);
                    if (wasDepleted && src.getSupplyBatchId() != null && !src.getSupplyBatchId().isBlank()) {
                        supplyBatchesToReopen.add(src.getSupplyBatchId());
                    }

                    StockMovement repair = new StockMovement();
                    repair.setBusinessId(t.getBusinessId());
                    repair.setBranchId(t.getFromBranchId());
                    repair.setItemId(src.getItemId());
                    repair.setBatchId(sourceBatchId);
                    repair.setMovementType(InventoryConstants.MOVEMENT_ADJUSTMENT);
                    repair.setReferenceType(InventoryConstants.REF_STOCK_TRANSFER_LINE);
                    repair.setReferenceId(line.getId());
                    repair.setQuantityDelta(missing);
                    repair.setUnitCost(costByBatch.get(sourceBatchId));
                    repair.setNotes("Transfer cancel backfill — source stock restored");
                    stockMovementRepository.save(repair);

                    batchesRestored++;
                    totalRestored = totalRestored.add(missing);
                }
            }
        }
        reopenClosedSupplyBatches(supplyBatchesToReopen);

        BackfillSummary summary = new BackfillSummary(
                cancelled.size(), linesScanned, batchesRestored, alreadyRestored,
                totalRestored.setScale(QTY_SCALE, RoundingMode.HALF_UP));
        log.info("Transfer-cancel backfill (businessId={}, branchId={}): {}", businessId, branchId, summary);
        return summary;
    }

    private void reopenClosedSupplyBatches(Set<String> supplyBatchIds) {
        for (String supplyBatchId : supplyBatchIds) {
            SupplyBatch sb = supplyBatchRepository.findById(supplyBatchId).orElse(null);
            if (sb != null && InventoryConstants.SUPPLY_BATCH_STATUS_CLOSED.equals(sb.getStatus())) {
                sb.setStatus(InventoryConstants.SUPPLY_BATCH_STATUS_ACTIVE);
                sb.setClosedAt(null);
                supplyBatchRepository.save(sb);
            }
        }
    }

    public record BackfillSummary(
            int cancelledTransfers,
            int linesScanned,
            int sourceBatchesRestored,
            int alreadyRestored,
            BigDecimal totalRestored
    ) {
    }
}
