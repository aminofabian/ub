package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.WastageReason;
import zelisline.ub.inventory.api.dto.DisposeBatchRequest;
import zelisline.ub.inventory.api.dto.EditBatchDetailsRequest;
import zelisline.ub.inventory.api.dto.InventoryMutationResponse;
import zelisline.ub.inventory.api.dto.PostStandaloneWastageRequest;
import zelisline.ub.pharmacy.application.MedicineReceivingGuard;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;

/**
 * Takes a batch off sale without taking it off the books (ADR 0033).
 *
 * <p>Sellable on-hand everywhere is "active batches", and {@code items.current_stock} mirrors that
 * sum. So quarantining writes a negative {@code quarantine} movement and lowers current stock by
 * what the batch holds; releasing reverses both. The batch keeps its quantity and cost, so the
 * stock is still accounted for until it is released or written off.</p>
 *
 * <p>Disposal reuses the existing wastage write-off (movements, shrinkage journal, supply-batch
 * close-out): inside one transaction the batch is released, the quantity is written off against
 * it, and whatever is left goes back into quarantine. The row stays locked throughout, so a sale
 * cannot pick the batch in between.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchQuarantineService {

    private static final int QTY_SCALE = 4;

    @PersistenceContext
    private EntityManager entityManager;

    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ItemRepository itemRepository;
    private final zelisline.ub.inventory.repository.SupplyBatchRepository supplyBatchRepository;
    private final InventoryLedgerService inventoryLedgerService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional
    public InventoryBatch quarantine(String businessId, String batchId, String reason, String userId) {
        InventoryBatch batch = lock(businessId, batchId);
        if (InventoryConstants.BATCH_STATUS_QUARANTINED.equals(batch.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This batch is already quarantined");
        }
        if (!InventoryConstants.BATCH_STATUS_ACTIVE.equals(batch.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a batch that is on sale can be quarantined");
        }
        if (InventoryConstants.BATCH_SOURCE_OVERSELL.equals(batch.getSourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is a system batch and cannot be quarantined");
        }
        String why = requireReason(reason);
        applyQuarantine(batch, why, userId);
        audit(businessId, userId, AuditEventTypes.INVENTORY_BATCH_QUARANTINED, batch, Map.of("reason", why));
        return batch;
    }

    @Transactional
    public InventoryBatch release(String businessId, String batchId, String reason, String userId) {
        InventoryBatch batch = lock(businessId, batchId);
        requireQuarantined(batch);
        String why = requireReason(reason);
        applyRelease(batch, "Released from quarantine: " + why, userId);
        audit(businessId, userId, AuditEventTypes.INVENTORY_BATCH_RELEASED, batch, Map.of("reason", why));
        return batch;
    }

    @Transactional
    public InventoryMutationResponse dispose(
            String businessId,
            String batchId,
            DisposeBatchRequest req,
            String userId
    ) {
        InventoryBatch batch = lock(businessId, batchId);
        requireQuarantined(batch);
        BigDecimal remaining = scale(batch.getQuantityRemaining());
        if (remaining.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing is left in this batch to dispose of");
        }
        if (req != null && req.expectedRemaining() != null
                && scale(req.expectedRemaining()).compareTo(remaining) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This batch changed since you opened it. Reload the page and check what is left.");
        }
        BigDecimal qty = req == null || req.quantity() == null ? remaining : scale(req.quantity());
        if (qty.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be positive");
        }
        if (qty.compareTo(remaining) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only " + remaining.stripTrailingZeros().toPlainString() + " is left in this batch");
        }
        String originalReason = batch.getQuarantineReason();
        WastageReason category = req == null || req.wastageReason() == null || req.wastageReason().isBlank()
                ? WastageReason.QUARANTINE_DISPOSAL
                : WastageReason.fromString(req.wastageReason());
        String note = req == null || req.note() == null || req.note().isBlank()
                ? "Quarantined stock disposed" + (originalReason == null ? "" : " (" + originalReason + ")")
                : req.note().strip();
        if (note.length() > 255) {
            note = note.substring(0, 255);
        }

        applyRelease(batch, "Handed to disposal", userId);
        entityManager.flush();
        InventoryMutationResponse out = inventoryLedgerService.recordStandaloneWastage(
                businessId,
                new PostStandaloneWastageRequest(
                        batch.getBranchId(),
                        batch.getItemId(),
                        qty,
                        batch.getUnitCost() == null ? BigDecimal.ZERO : batch.getUnitCost(),
                        note,
                        batch.getId(),
                        category.name()),
                userId);
        entityManager.flush();
        entityManager.refresh(batch);
        if (scale(batch.getQuantityRemaining()).signum() > 0
                && InventoryConstants.BATCH_STATUS_ACTIVE.equals(batch.getStatus())) {
            applyQuarantine(batch, originalReason == null ? "Quarantined" : originalReason, userId);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("quantity", qty);
        state.put("wastageReason", category.name());
        audit(businessId, userId, AuditEventTypes.INVENTORY_BATCH_DISPOSED, batch, state);
        return out;
    }

    /** Corrects the lot number and/or expiry recorded on a batch (for stock loaded before the rule existed). */
    @Transactional
    public InventoryBatch editDetails(
            String businessId,
            String batchId,
            EditBatchDetailsRequest req,
            String userId
    ) {
        InventoryBatch batch = lock(businessId, batchId);
        if (InventoryConstants.BATCH_SOURCE_OVERSELL.equals(batch.getSourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is a system batch and cannot be edited");
        }
        String newLot = MedicineReceivingGuard.normalizeLot(req.lotNumber());
        LocalDate newExpiry = req.expiryDate();
        if (newLot == null && newExpiry == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a lot number or an expiry date");
        }
        Map<String, Object> state = new LinkedHashMap<>();
        if (newLot != null && !newLot.equals(batch.getLotNumber())) {
            state.put("lotNumberFrom", batch.getLotNumber());
            state.put("lotNumberTo", newLot);
            batch.setLotNumber(newLot);
        }
        if (newExpiry != null && !newExpiry.equals(batch.getExpiryDate())) {
            state.put("expiryDateFrom", batch.getExpiryDate() == null ? null : batch.getExpiryDate().toString());
            state.put("expiryDateTo", newExpiry.toString());
            batch.setExpiryDate(newExpiry);
        }
        if (state.isEmpty()) {
            return batch;
        }
        inventoryBatchRepository.save(batch);
        audit(businessId, userId, AuditEventTypes.INVENTORY_BATCH_DETAILS_EDITED, batch, state);
        return batch;
    }

    private void applyQuarantine(InventoryBatch batch, String reason, String userId) {
        BigDecimal qty = scale(batch.getQuantityRemaining());
        batch.setStatus(InventoryConstants.BATCH_STATUS_QUARANTINED);
        batch.setQuarantineReason(reason);
        batch.setQuarantinedAt(Instant.now());
        batch.setQuarantinedBy(userId);
        inventoryBatchRepository.save(batch);
        if (qty.signum() > 0) {
            movement(batch, InventoryConstants.MOVEMENT_QUARANTINE, qty.negate(), reason, userId);
            applyItemStock(batch, qty.negate());
        }
    }

    private void applyRelease(InventoryBatch batch, String note, String userId) {
        BigDecimal qty = scale(batch.getQuantityRemaining());
        batch.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        batch.setQuarantineReason(null);
        batch.setQuarantinedAt(null);
        batch.setQuarantinedBy(null);
        inventoryBatchRepository.save(batch);
        if (qty.signum() > 0) {
            reopenSupplyBatchIfClosed(batch);
            movement(batch, InventoryConstants.MOVEMENT_QUARANTINE_RELEASE, qty, note, userId);
            applyItemStock(batch, qty);
        }
    }

    private void movement(InventoryBatch batch, String type, BigDecimal delta, String note, String userId) {
        StockMovement sm = new StockMovement();
        sm.setBusinessId(batch.getBusinessId());
        sm.setBranchId(batch.getBranchId());
        sm.setItemId(batch.getItemId());
        sm.setBatchId(batch.getId());
        sm.setMovementType(type);
        sm.setReferenceType(InventoryConstants.STOCK_REF_BATCH_QUARANTINE);
        sm.setReferenceId(batch.getId());
        sm.setQuantityDelta(delta);
        sm.setUnitCost(batch.getUnitCost());
        sm.setNotes(note);
        sm.setCreatedBy(userId);
        stockMovementRepository.save(sm);
    }

    private void applyItemStock(InventoryBatch batch, BigDecimal delta) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(batch.getItemId(), batch.getBusinessId())
                .orElse(null);
        if (item == null) {
            return; // a deleted product keeps its batches; there is no shelf count to adjust
        }
        entityManager.lock(item, LockModeType.PESSIMISTIC_WRITE);
        BigDecimal base = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
        item.setCurrentStock(base.add(delta).setScale(QTY_SCALE, RoundingMode.HALF_UP));
        itemRepository.save(item);
    }

    /**
     * Locks the item row, then the batch row. Sales, voids and refunds take their locks in that
     * order (item first), so taking the batch first here could deadlock a recall against a sale.
     */
    /**
     * For a role tied to one branch: 404 unless the batch sits in that branch. A null
     * {@code branchId} means the caller is not tied to a branch.
     */
    @Transactional(readOnly = true)
    public void assertBatchInBranch(String businessId, String batchId, String branchId) {
        if (branchId == null) {
            return;
        }
        InventoryBatch b = inventoryBatchRepository.findByIdAndBusinessId(batchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found"));
        if (!branchId.equals(b.getBranchId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found");
        }
    }

    private InventoryBatch lock(String businessId, String batchId) {
        InventoryBatch peek = inventoryBatchRepository.findByIdAndBusinessId(batchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found"));
        itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(peek.getItemId(), businessId)
                .ifPresent(item -> entityManager.lock(item, LockModeType.PESSIMISTIC_WRITE));
        InventoryBatch locked = inventoryBatchRepository.findByIdAndBusinessIdForUpdate(batchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found"));
        entityManager.refresh(locked);
        return locked;
    }

    /**
     * A delivery's header closes when its other lots sell out while this one sits in quarantine,
     * and sale picks skip lots under a closed header. Reopen it so released stock can be sold.
     */
    private void reopenSupplyBatchIfClosed(InventoryBatch batch) {
        String supplyBatchId = batch.getSupplyBatchId();
        if (supplyBatchId == null || supplyBatchId.isBlank()) {
            return;
        }
        supplyBatchRepository.findByIdAndBusinessId(supplyBatchId, batch.getBusinessId()).ifPresent(sb -> {
            if (InventoryConstants.SUPPLY_BATCH_STATUS_CLOSED.equals(sb.getStatus())) {
                sb.setStatus(InventoryConstants.SUPPLY_BATCH_STATUS_ACTIVE);
                sb.setClosedAt(null);
                supplyBatchRepository.save(sb);
            }
        });
    }

    private static void requireQuarantined(InventoryBatch batch) {
        if (!InventoryConstants.BATCH_STATUS_QUARANTINED.equals(batch.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This batch is not quarantined");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a reason");
        }
        String r = reason.strip();
        return r.length() > 255 ? r.substring(0, 255) : r;
    }

    private static BigDecimal scale(BigDecimal q) {
        return (q == null ? BigDecimal.ZERO : q).setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    private void audit(String businessId, String userId, String eventType, InventoryBatch batch, Map<String, ?> state) {
        try {
            Map<String, Object> newState = new LinkedHashMap<>(state);
            newState.put("itemId", batch.getItemId());
            newState.put("batchNumber", batch.getBatchNumber());
            newState.put("lotNumber", batch.getLotNumber());
            newState.put("status", batch.getStatus());
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.INVENTORY, eventType, AuditEventSeverity.WARN)
                    .businessId(businessId)
                    .branchId(batch.getBranchId())
                    .actor(userId, AuditEventActorType.USER)
                    .target("inventory_batch", batch.getId())
                    .source("inventory")
                    .newState(newState)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit {} business={} batch={}: {}", eventType, businessId, batch.getId(), ex.toString());
        }
    }
}
