package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.WastageReason;
import zelisline.ub.inventory.domain.SupplyBatch;
import zelisline.ub.inventory.repository.SupplyBatchRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;

@Service
@RequiredArgsConstructor
public class SupplyBatchClearanceService {

    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE = 4;

    @PersistenceContext
    private EntityManager entityManager;

    private final SupplyBatchRepository supplyBatchRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ItemRepository itemRepository;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;

    /**
     * Clears a supply batch, writing off any remaining stock.
     *
     * @param reason  the wastage enum reason (SPOILAGE, EXPIRED, THEFT, etc.)
     * @param notes   optional free-text notes
     */
    @Transactional
    public ClearanceResult clearBatch(
            String businessId,
            String supplyBatchId,
            WastageReason reason,
            String notes,
            String userId
    ) {
        SupplyBatch sb = supplyBatchRepository.findByIdAndBusinessId(supplyBatchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supply batch not found"));

        if (!InventoryConstants.SUPPLY_BATCH_STATUS_ACTIVE.equals(sb.getStatus())
                && !InventoryConstants.SUPPLY_BATCH_STATUS_SOLDOUT.equals(sb.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Batch is already " + sb.getStatus() + ". Only active or sold-out batches can be cleared.");
        }

        // Lock the supply batch header
        entityManager.lock(sb, LockModeType.PESSIMISTIC_WRITE);

        List<InventoryBatch> lines = inventoryBatchRepository
                .findBySupplyBatchIdAndStatusAndQuantityRemainingGreaterThan(
                        supplyBatchId, InventoryConstants.BATCH_STATUS_ACTIVE, BigDecimal.ZERO);

        boolean hadRemaining = false;
        BigDecimal totalWriteOffValue = BigDecimal.ZERO;

        for (InventoryBatch line : lines) {
            entityManager.lock(line, LockModeType.PESSIMISTIC_WRITE);
            BigDecimal remaining = line.getQuantityRemaining();
            if (remaining.signum() <= 0) {
                continue;
            }
            hadRemaining = true;

            BigDecimal writeOffValue = remaining.multiply(line.getUnitCost())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            totalWriteOffValue = totalWriteOffValue.add(writeOffValue);

            // Decrement item's current stock
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(line.getItemId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
            entityManager.lock(item, LockModeType.PESSIMISTIC_WRITE);
            BigDecimal newStock = item.getCurrentStock().subtract(remaining)
                    .setScale(QTY_SCALE, RoundingMode.HALF_UP);
            if (newStock.signum() < 0) {
                newStock = BigDecimal.ZERO;
            }
            item.setCurrentStock(newStock);
            itemRepository.save(item);

            // Record stock movement
            StockMovement sm = new StockMovement();
            sm.setBusinessId(businessId);
            sm.setBranchId(sb.getBranchId());
            sm.setItemId(line.getItemId());
            sm.setBatchId(line.getId());
            sm.setMovementType(InventoryConstants.MOVEMENT_BATCH_CLEARANCE);
            sm.setReferenceType("supply_batch");
            sm.setReferenceId(supplyBatchId);
            sm.setQuantityDelta(remaining.negate());
            sm.setUnitCost(line.getUnitCost());
            sm.setWastageReason(reason.name());
            String reasonText = reason.name() + (notes != null && !notes.isBlank() ? " — " + notes : "");
            sm.setReason(InventoryConstants.MOVEMENT_BATCH_CLEARANCE + " — " + reasonText);
            sm.setNotes(notes);
            sm.setCreatedBy(userId);
            stockMovementRepository.save(sm);

            // Decrement the batch line
            line.setQuantityRemaining(BigDecimal.ZERO);
            line.setStatus(InventoryConstants.BATCH_STATUS_DEPLETED);
            inventoryBatchRepository.save(line);
        }

        // Post journal entry if anything was written off
        String journalEntryId = null;
        if (hadRemaining && totalWriteOffValue.signum() > 0) {
            JournalEntry entry = new JournalEntry();
            entry.setBusinessId(businessId);
            entry.setEntryDate(LocalDate.now(ZoneOffset.UTC));
            entry.setSourceType(InventoryConstants.JOURNAL_BATCH_CLEARANCE);
            entry.setSourceId(supplyBatchId);
            entry.setMemo("Batch clearance: " + sb.getBatchNumber() + " — " + reason.name()
                    + (notes != null && !notes.isBlank() ? " — " + notes : ""));

            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY_SHRINKAGE),
                    totalWriteOffValue);
            entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY),
                    totalWriteOffValue);

            journalEntryId = ledgerPostingPort.post(entry);
        }

        // Close the supply batch
        sb.setStatus(InventoryConstants.SUPPLY_BATCH_STATUS_CLOSED);
        sb.setClosedAt(Instant.now());
        sb.setClosedBy(userId);
        supplyBatchRepository.save(sb);

        return new ClearanceResult(sb.getId(), sb.getBatchNumber(), hadRemaining, totalWriteOffValue, journalEntryId);
    }

    public record ClearanceResult(
            String supplyBatchId,
            String batchNumber,
            boolean hadRemainingStock,
            BigDecimal totalWriteOffValue,
            String journalEntryId
    ) {
    }
}
