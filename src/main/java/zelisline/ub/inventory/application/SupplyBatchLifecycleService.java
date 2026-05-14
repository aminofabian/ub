package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.domain.SupplyBatch;
import zelisline.ub.inventory.repository.SupplyBatchRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;

@Service
@RequiredArgsConstructor
public class SupplyBatchLifecycleService {

    private final SupplyBatchRepository supplyBatchRepository;
    private final InventoryBatchRepository inventoryBatchRepository;

    /**
     * After any operation that decrements {@link InventoryBatch#getQuantityRemaining()},
     * call this to auto-close the parent {@link SupplyBatch} when every line in the
     * batch is at (or below) zero. All lines are also marked as depleted.
     */
    @Transactional
    public void checkAndTransitionToSoldoutIfNeeded(String businessId, String supplyBatchId) {
        if (supplyBatchId == null) {
            return;
        }
        SupplyBatch sb = supplyBatchRepository.findByIdAndBusinessId(supplyBatchId, businessId)
                .orElse(null);
        if (sb == null) {
            return;
        }
        if (!InventoryConstants.SUPPLY_BATCH_STATUS_ACTIVE.equals(sb.getStatus())) {
            return;
        }

        List<InventoryBatch> lines = inventoryBatchRepository
                .findBySupplyBatchIdAndStatus(supplyBatchId, InventoryConstants.BATCH_STATUS_ACTIVE);

        boolean allZero = lines.stream()
                .allMatch(b -> {
                    BigDecimal q = b.getQuantityRemaining();
                    return q == null || q.signum() <= 0;
                });

        if (allZero) {
            // Auto-close the supply batch
            sb.setStatus(InventoryConstants.SUPPLY_BATCH_STATUS_CLOSED);
            sb.setClosedAt(Instant.now());
            supplyBatchRepository.save(sb);

            // Mark all lines as depleted
            for (InventoryBatch line : lines) {
                line.setStatus(InventoryConstants.BATCH_STATUS_DEPLETED);
                inventoryBatchRepository.save(line);
            }
        }
    }
}
