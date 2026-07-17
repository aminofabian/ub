package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.application.BatchNumberGenerator;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.inventory.CostMethod;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.WastageReason;
import zelisline.ub.inventory.domain.SupplyBatch;
import zelisline.ub.inventory.repository.SupplyBatchRepository;
import zelisline.ub.inventory.api.dto.InventoryMutationResponse;
import zelisline.ub.inventory.api.dto.PostBatchDecreaseRequest;
import zelisline.ub.inventory.api.dto.PostBatchIncreaseRequest;
import zelisline.ub.inventory.api.dto.PostOpeningBalanceRequest;
import zelisline.ub.inventory.api.dto.PostStandaloneWastageRequest;
import zelisline.ub.inventory.api.dto.PostStockIncreaseRequest;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class InventoryLedgerService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int QTY_SCALE = 4;

    private final BatchNumberGenerator batchNumberGenerator;

    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final SupplyBatchRepository supplyBatchRepository;
    private final SupplyBatchLifecycleService supplyBatchLifecycleService;
    private final ApplicationEventPublisher eventPublisher;
    private final PackageVariantStockResolver packageVariantStockResolver;

    @Transactional
    public InventoryMutationResponse recordOpeningBalance(
            String businessId,
            PostOpeningBalanceRequest req,
            String userId
    ) {
        requireBranch(businessId, req.branchId());
        PackageVariantStockResolver.StockPickResolution inbound =
                packageVariantStockResolver.resolveInbound(businessId, req.itemId(), req.quantity());
        Item item = packageVariantStockResolver.requireInventoryHolder(businessId, inbound.stockItemId());
        BigDecimal unitCost = PackageVariantStockResolver.toStockUnitCost(
                req.quantity(), req.unitCost(), inbound);
        BigDecimal value = PackageVariantStockResolver.catalogExtensionMoney(req.quantity(), req.unitCost());
        String opId = UUID.randomUUID().toString();
        InventoryBatch batch = saveInboundBatch(
                businessId,
                req.branchId(),
                item.getId(),
                InventoryConstants.BATCH_SOURCE_OPENING,
                opId,
                inbound.stockQuantity(),
                unitCost,
                opId
        );
        SupplyBatch sb = createSupplyBatchForSoloBatch(batch, opId, "Opening balance");
        batch.setSupplyBatchId(sb.getId());
        inventoryBatchRepository.save(batch);
        StockMovement mv = persistMovement(
                businessId,
                req.branchId(),
                item.getId(),
                batch.getId(),
                InventoryConstants.MOVEMENT_OPENING,
                opId,
                inbound.stockQuantity(),
                unitCost,
                req.notes(),
                userId
        );
        applyStockDelta(item, inbound.stockQuantity());
        String jeId = saveJournal(
                businessId,
                InventoryConstants.JOURNAL_OPENING,
                opId,
                "Opening balance",
                value,
                true
        );
        return new InventoryMutationResponse(jeId, mv.getId(), batch.getId());
    }

    @Transactional
    public InventoryMutationResponse recordStockIncrease(
            String businessId,
            PostStockIncreaseRequest req,
            String userId
    ) {
        requireBranch(businessId, req.branchId());
        PackageVariantStockResolver.StockPickResolution inbound =
                packageVariantStockResolver.resolveInbound(businessId, req.itemId(), req.quantity());
        Item item = packageVariantStockResolver.requireInventoryHolder(businessId, inbound.stockItemId());
        BigDecimal unitCost = PackageVariantStockResolver.toStockUnitCost(
                req.quantity(), req.unitCost(), inbound);
        BigDecimal value = PackageVariantStockResolver.catalogExtensionMoney(req.quantity(), req.unitCost());
        String opId = UUID.randomUUID().toString();
        InventoryBatch batch = saveInboundBatch(
                businessId,
                req.branchId(),
                item.getId(),
                InventoryConstants.BATCH_SOURCE_STOCK_GAIN,
                opId,
                inbound.stockQuantity(),
                unitCost,
                opId
        );
        SupplyBatch sb = createSupplyBatchForSoloBatch(batch, opId, "Stock gain");
        batch.setSupplyBatchId(sb.getId());
        inventoryBatchRepository.save(batch);
        StockMovement mv = persistMovement(
                businessId,
                req.branchId(),
                item.getId(),
                batch.getId(),
                InventoryConstants.MOVEMENT_ADJUSTMENT,
                opId,
                inbound.stockQuantity(),
                unitCost,
                req.notes(),
                userId
        );
        applyStockDelta(item, inbound.stockQuantity());
        // Stock-take surplus and other callers may use $0 unit cost when there is no
        // on-hand batch to average; GL lines must not be zero-amount.
        String jeId = null;
        if (value.signum() > 0) {
            jeId = saveJournal(
                    businessId,
                    InventoryConstants.JOURNAL_COUNT_GAIN,
                    opId,
                    "Stock count / gain",
                    value,
                    true
            );
        }
        if (inbound.stockQuantity().signum() != 0) {
            String itemName = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(item.getId(), businessId)
                    .map(zelisline.ub.catalog.domain.Item::getName).orElse(item.getId());
            eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.StockAdjustedEvent(
                    businessId, req.branchId(), item.getId(), itemName,
                    "stock_increase", inbound.stockQuantity()));
        }
        return new InventoryMutationResponse(jeId, mv.getId(), batch.getId());
    }

    @Transactional
    public InventoryMutationResponse recordBatchDecrease(
            String businessId,
            PostBatchDecreaseRequest req,
            String userId
    ) {
        InventoryBatch batch = inventoryBatchRepository.findByIdAndBusinessId(req.batchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found"));
        if (!InventoryConstants.BATCH_STATUS_ACTIVE.equals(batch.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch is not active");
        }
        if (batch.getQuantityRemaining().compareTo(req.quantity()) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity exceeds batch remaining");
        }
        Item item = requireStockedItem(businessId, batch.getItemId());
        String opId = UUID.randomUUID().toString();
        batch.setQuantityRemaining(batch.getQuantityRemaining().subtract(req.quantity()));
        inventoryBatchRepository.save(batch);
        supplyBatchLifecycleService.checkAndTransitionToSoldoutIfNeeded(businessId, batch.getSupplyBatchId());

        StockMovement mv = persistMovement(
                businessId,
                batch.getBranchId(),
                item.getId(),
                batch.getId(),
                InventoryConstants.MOVEMENT_ADJUSTMENT,
                opId,
                req.quantity().negate(),
                batch.getUnitCost(),
                req.reason(),
                userId
        );
        // Batch remaining is the physical guard; current_stock may already be
        // below branch on-hand (oversell / multi-branch drift).
        applyStockDelta(item, req.quantity().negate(), true);
        BigDecimal value = extensionMoney(req.quantity(), batch.getUnitCost());
        String jeId = saveJournal(
                businessId,
                InventoryConstants.JOURNAL_ADJUSTMENT_DOWN,
                opId,
                "Inventory adjustment (decrease)",
                value,
                false
        );
        return new InventoryMutationResponse(jeId, mv.getId(), batch.getId());
    }

    @Transactional
    public InventoryMutationResponse recordBatchIncrease(
            String businessId,
            PostBatchIncreaseRequest req,
            String userId
    ) {
        InventoryBatch batch = inventoryBatchRepository.findByIdAndBusinessId(req.batchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found"));
        if (!InventoryConstants.BATCH_STATUS_ACTIVE.equals(batch.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch is not active");
        }
        Item item = requireStockedItem(businessId, batch.getItemId());
        String opId = UUID.randomUUID().toString();
        batch.setQuantityRemaining(batch.getQuantityRemaining().add(req.quantity()));
        inventoryBatchRepository.save(batch);

        StockMovement mv = persistMovement(
                businessId,
                batch.getBranchId(),
                item.getId(),
                batch.getId(),
                InventoryConstants.MOVEMENT_ADJUSTMENT,
                opId,
                req.quantity(),
                batch.getUnitCost(),
                req.reason(),
                userId
        );
        applyStockDelta(item, req.quantity());
        BigDecimal value = extensionMoney(req.quantity(), batch.getUnitCost());
        String jeId = null;
        if (value.signum() > 0) {
            jeId = saveJournal(
                    businessId,
                    InventoryConstants.JOURNAL_COUNT_GAIN,
                    opId,
                    "Inventory adjustment (increase)",
                    value,
                    true
            );
        }
        return new InventoryMutationResponse(jeId, mv.getId(), batch.getId());
    }

    @Transactional
    public InventoryMutationResponse recordStandaloneWastage(
            String businessId,
            PostStandaloneWastageRequest req,
            String userId
    ) {
        requireBranch(businessId, req.branchId());
        PackageVariantStockResolver.StockPickResolution outbound =
                packageVariantStockResolver.resolveInbound(businessId, req.itemId(), req.quantity());
        Item item = packageVariantStockResolver.requireInventoryHolder(businessId, outbound.stockItemId());
        String opId = UUID.randomUUID().toString();

        // ── Resolve the target batch ──────────────────────────────────
        InventoryBatch batch = resolveWastageBatch(businessId, req, item);

        // ── Decrement the batch ───────────────────────────────────────
        BigDecimal qty = outbound.stockQuantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (batch.getQuantityRemaining().compareTo(qty) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Wastage quantity (" + qty + ") exceeds batch remaining ("
                            + batch.getQuantityRemaining() + ")"
            );
        }
        batch.setQuantityRemaining(batch.getQuantityRemaining().subtract(qty));
        inventoryBatchRepository.save(batch);
        supplyBatchLifecycleService.checkAndTransitionToSoldoutIfNeeded(businessId, batch.getSupplyBatchId());

        // ── Resolve enum reason ──────────────────────────────────────────
        WastageReason cat = WastageReason.fromString(req.wastageReason());
        String movementReason;
        if (req.reason() != null && !req.reason().isBlank()) {
            movementReason = cat.name() + " — " + req.reason();
        } else {
            movementReason = cat.name();
        }

        // ── Record the movement (now WITH batch_id) ───────────────────
        StockMovement mv = persistMovement(
                businessId,
                req.branchId(),
                item.getId(),
                batch.getId(),
                PurchasingConstants.MOVEMENT_WASTAGE,
                opId,
                qty.negate(),
                batch.getUnitCost(),
                movementReason,
                userId
        );
        mv.setWastageReason(cat.name());
        stockMovementRepository.save(mv);
        applyStockDelta(item, qty.negate(), true);

        BigDecimal value = extensionMoney(qty, batch.getUnitCost());
        String jeId = saveJournal(
                businessId,
                InventoryConstants.JOURNAL_STANDALONE_WASTAGE,
                opId,
                "Inventory wastage — batch " + batch.getBatchNumber(),
                value,
                false
        );
        String itemName = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(req.itemId(), businessId)
                .map(zelisline.ub.catalog.domain.Item::getName).orElse(req.itemId());
        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.StockAdjustedEvent(
                businessId, req.branchId(), req.itemId(), itemName,
                "wastage", qty.negate()));
        return new InventoryMutationResponse(jeId, mv.getId(), batch.getId());
    }

    /**
     * Resolves which batch to deplete for wastage.
     * If the caller specifies a batchId, use that (validate it).
     * Otherwise, auto-pick the most eligible batch using FEFO → FIFO.
     */
    private InventoryBatch resolveWastageBatch(
            String businessId,
            PostStandaloneWastageRequest req,
            Item item
    ) {
        if (req.batchId() != null && !req.batchId().isBlank()) {
            // ── Caller picked a specific batch ────────────────────────
            InventoryBatch b = inventoryBatchRepository
                    .findByIdAndBusinessId(req.batchId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Batch not found"));
            if (!InventoryConstants.BATCH_STATUS_ACTIVE.equals(b.getStatus())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Batch is not active");
            }
            if (!b.getBranchId().equals(req.branchId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Batch does not belong to this branch");
            }
            if (!b.getItemId().equals(item.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Batch item does not match");
            }
            return b;
        }

        // ── Auto-pick: load active batches, sort FEFO → FIFO, take first ─
        List<InventoryBatch> candidates = inventoryBatchRepository
                .findActiveBatchesForPreview(
                        businessId,
                        item.getId(),
                        req.branchId(),
                        InventoryConstants.BATCH_STATUS_ACTIVE,
                        BigDecimal.ZERO
                );
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "No active batches with remaining quantity");
        }

        // Reuse the existing sort logic from BatchAllocationPlanner
        List<InventoryBatch> working = new ArrayList<>(candidates);
        BatchAllocationPlanner.sortBatchesForPick(
                working,
                item,
                CostMethod.FIFO   // wastage: oldest first (FEFO if expiry exists)
        );
        return working.getFirst();
    }

    private InventoryBatch saveInboundBatch(
            String businessId,
            String branchId,
            String itemId,
            String sourceType,
            String sourceId,
            BigDecimal quantity,
            BigDecimal unitCost,
            String operationId
    ) {
        InventoryBatch b = new InventoryBatch();
        b.setBusinessId(businessId);
        b.setBranchId(branchId);
        b.setItemId(itemId);
        b.setSupplierId(null);
        b.setBatchNumber("P3-" + operationId.substring(0, 8).toUpperCase());
        b.setSourceType(sourceType);
        b.setSourceId(sourceId);
        b.setInitialQuantity(quantity);
        b.setQuantityRemaining(quantity);
        BigDecimal cost = unitCost != null ? unitCost : BigDecimal.ZERO;
        b.setUnitCost(cost.setScale(QTY_SCALE, RoundingMode.HALF_UP));
        b.setReceivedAt(Instant.now());
        inventoryBatchRepository.save(b);
        return b;
    }

    private StockMovement persistMovement(
            String businessId,
            String branchId,
            String itemId,
            String batchId,
            String movementType,
            String operationId,
            BigDecimal quantityDelta,
            BigDecimal unitCost,
            String notes,
            String userId
    ) {
        StockMovement sm = new StockMovement();
        sm.setBusinessId(businessId);
        sm.setBranchId(branchId);
        sm.setItemId(itemId);
        sm.setBatchId(batchId);
        sm.setMovementType(movementType);
        sm.setReferenceType(InventoryConstants.REF_OPERATION);
        sm.setReferenceId(operationId);
        sm.setQuantityDelta(quantityDelta);
        sm.setUnitCost(unitCost);
        sm.setNotes(notes);
        sm.setCreatedBy(userId);
        stockMovementRepository.save(sm);
        return sm;
    }

    /** When increase: Dr inventory, Cr equity. When decrease class: Dr shrinkage, Cr inventory. */
    private String saveJournal(
            String businessId,
            String sourceType,
            String sourceId,
            String memo,
            BigDecimal extensionValue,
            boolean isInboundInventory
    ) {
        if (extensionValue.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Journal amount must be positive");
        }
        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC));
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setMemo(memo);
        BigDecimal v = extensionValue.setScale(2, RoundingMode.HALF_UP);
        if (isInboundInventory) {
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY), v);
            entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.OPENING_BALANCE_EQUITY), v);
        } else {
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY_SHRINKAGE), v);
            entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY), v);
        }
        return ledgerPostingPort.post(entry);
    }

    private void requireBranch(String businessId, String branchId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private Item requireStockedItem(String businessId, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
        if (!item.isStocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not stocked");
        }
        return item;
    }

    private void applyStockDelta(Item item, BigDecimal delta) {
        // Inbound always allowed so oversold current_stock can be repaired.
        applyStockDelta(item, delta, delta.signum() > 0);
    }

    /**
     * @param allowNegativeResult when true (batch-backed decrease / wastage), skip the
     *        denormalized current_stock floor. Branch UIs use batch on-hand; current_stock
     *        can already sit below that after allowNegativeStock sales or cross-branch drift.
     */
    private void applyStockDelta(Item item, BigDecimal delta, boolean allowNegativeResult) {
        BigDecimal base = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
        BigDecimal next = base.add(delta).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (next.signum() < 0 && !allowNegativeResult) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock cannot go negative");
        }
        if (base.signum() <= 0 && next.signum() > 0) {
            eventPublisher.publishEvent(new zelisline.ub.notifications.application.CatalogNotificationListener.ItemRestockedEvent(
                    item.getBusinessId(),
                    item.getId(),
                    item.getName()));
        }
        item.setCurrentStock(next);
        itemRepository.save(item);
    }

    private SupplyBatch createSupplyBatchForSoloBatch(InventoryBatch batch, String sourceId, String batchName) {
        SupplyBatch sb = new SupplyBatch();
        sb.setBusinessId(batch.getBusinessId());
        sb.setBranchId(batch.getBranchId());
        sb.setSupplierId(batch.getSupplierId());
        sb.setBatchNumber(batchNumberGenerator.next(null, null, batch.getReceivedAt(), batch.getBusinessId()));
        sb.setBatchName(batchName);
        sb.setSourceType(batch.getSourceType());
        sb.setSourceId(sourceId);
        sb.setItemCount(1);
        sb.setTotalInitialQuantity(batch.getInitialQuantity());
        sb.setTotalRemainingQuantity(batch.getQuantityRemaining());
        sb.setReceivedAt(batch.getReceivedAt());
        sb.setStatus("active");
        supplyBatchRepository.save(sb);
        return sb;
    }

    private static BigDecimal extensionMoney(BigDecimal qty, BigDecimal unitCost) {
        return qty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
    }
}
