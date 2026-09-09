package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.application.BatchAllocationPlanner;
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
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
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
    /** Matches {@code inventory_batches.unit_cost} {@code DECIMAL(14,4)}. */
    private static final BigDecimal MAX_UNIT_COST_14_4 = new BigDecimal("9999999999.9999");

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
        String jeId = null;
        if (value.signum() > 0) {
            jeId = saveJournal(
                    businessId,
                    InventoryConstants.JOURNAL_OPENING,
                    opId,
                    "Opening balance",
                    value,
                    true
            );
        }
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
        // Zero-cost batches (stock gains / count corrections) still move qty;
        // skip GL — journals cannot be posted at $0.
        String jeId = null;
        if (value.signum() > 0) {
            jeId = saveJournal(
                    businessId,
                    InventoryConstants.JOURNAL_ADJUSTMENT_DOWN,
                    opId,
                    "Inventory adjustment (decrease)",
                    value,
                    false
            );
        }
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
        // Resolve holder from the catalog SKU (package variants write off parent stock).
        Item item = packageVariantStockResolver.requireInventoryHolder(businessId, req.itemId());
        if (!item.getId().equals(outbound.stockItemId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Stock holder mismatch for wastage");
        }
        String opId = UUID.randomUUID().toString();

        // ── Resolve enum reason ──────────────────────────────────────
        WastageReason cat = WastageReason.fromString(req.wastageReason());
        String movementReason;
        if (req.reason() != null && !req.reason().isBlank()) {
            movementReason = cat.name() + " — " + req.reason();
        } else {
            movementReason = cat.name();
        }

        BigDecimal qty = outbound.stockQuantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);

        // ── Deplete batches FEFO/FIFO — across as many as needed ─────
        // Grocery on-hand is the sum of active inventory lines (including lots
        // whose supply header was closed). Sale picks exclude closed supply
        // batches; wastage must still write off physical shelf stock — same
        // pool as stock-take write-downs — and lock rows to avoid optimistic
        // lock 500s when clerks spoil while sales are depleting the same lots.
        List<BatchAllocationLine> slices;
        Map<String, InventoryBatch> lockedById = new LinkedHashMap<>();
        InventoryBatch primaryBatch;
        if (req.batchId() != null && !req.batchId().isBlank()) {
            primaryBatch = lockWastageBatch(businessId, req, item);
            lockedById.put(primaryBatch.getId(), primaryBatch);
            if (primaryBatch.getQuantityRemaining().compareTo(qty) < 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Wastage quantity (" + qty + ") exceeds batch remaining ("
                                + primaryBatch.getQuantityRemaining() + ")"
                );
            }
            slices = List.of(new BatchAllocationLine(
                    primaryBatch.getId(), qty, primaryBatch.getUnitCost()));
        } else {
            List<InventoryBatch> candidates = inventoryBatchRepository
                    .lockActiveBatchesForPhysicalAdjustment(
                            businessId,
                            item.getId(),
                            req.branchId(),
                            InventoryConstants.BATCH_STATUS_ACTIVE,
                            BigDecimal.ZERO
                    );
            if (candidates.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "No on-hand stock to write off for this item at this branch. "
                                + "Receive stock or refresh — the shelf count may be out of date."
                );
            }
            for (InventoryBatch b : candidates) {
                lockedById.put(b.getId(), b);
            }
            List<InventoryBatch> working = new ArrayList<>(candidates);
            BatchAllocationPlanner.sortBatchesForPick(
                    working,
                    item,
                    CostMethod.FIFO   // wastage: oldest first (FEFO if expiry exists)
            );
            BigDecimal available = working.stream()
                    .map(InventoryBatch::getQuantityRemaining)
                    .filter(q -> q != null && q.signum() > 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(QTY_SCALE, RoundingMode.HALF_UP);
            if (available.compareTo(qty) < 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Not enough on-hand stock to write off ("
                                + qty + " needed, " + available + " available)."
                );
            }
            slices = BatchAllocationPlanner.allocateInOrder(working, qty);
            primaryBatch = lockedById.get(slices.getFirst().batchId());
            if (primaryBatch == null) {
                primaryBatch = inventoryBatchRepository.findById(slices.getFirst().batchId())
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Allocated batch not found"));
            }
        }

        // ── Decrement each batch and record its movement ─────────────
        StockMovement firstMv = null;
        BigDecimal totalValue = BigDecimal.ZERO;
        String firstBatchNumber = null;
        Set<String> supplyBatchIds = new LinkedHashSet<>();
        for (BatchAllocationLine slice : slices) {
            InventoryBatch batch = lockedById.get(slice.batchId());
            if (batch == null) {
                batch = inventoryBatchRepository.findById(slice.batchId())
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Allocated batch not found"));
            }
            batch.setQuantityRemaining(batch.getQuantityRemaining().subtract(slice.quantity()));
            inventoryBatchRepository.save(batch);
            if (batch.getSupplyBatchId() != null && !batch.getSupplyBatchId().isBlank()) {
                supplyBatchIds.add(batch.getSupplyBatchId());
            }

            StockMovement mv = persistMovement(
                    businessId,
                    req.branchId(),
                    item.getId(),
                    batch.getId(),
                    PurchasingConstants.MOVEMENT_WASTAGE,
                    opId,
                    slice.quantity().negate(),
                    batch.getUnitCost(),
                    movementReason,
                    userId
            );
            mv.setWastageReason(cat.name());
            stockMovementRepository.save(mv);
            if (firstMv == null) {
                firstMv = mv;
                firstBatchNumber = batch.getBatchNumber();
            }
            totalValue = totalValue.add(extensionMoney(slice.quantity(), batch.getUnitCost()));
        }
        // Close supply headers only after every line decrement — calling this
        // mid-loop can mark sibling lots depleted before they are written off.
        for (String supplyBatchId : supplyBatchIds) {
            supplyBatchLifecycleService.checkAndTransitionToSoldoutIfNeeded(
                    businessId, supplyBatchId);
        }
        applyStockDelta(item, qty.negate(), true);

        String jeId = null;
        if (totalValue.signum() > 0) {
            jeId = saveJournal(
                    businessId,
                    InventoryConstants.JOURNAL_STANDALONE_WASTAGE,
                    opId,
                    "Inventory wastage — batch " + firstBatchNumber,
                    totalValue,
                    false
            );
        }
        String itemName = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(req.itemId(), businessId)
                .map(zelisline.ub.catalog.domain.Item::getName).orElse(req.itemId());
        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.StockAdjustedEvent(
                businessId, req.branchId(), req.itemId(), itemName,
                "wastage", qty.negate()));
        return new InventoryMutationResponse(jeId, firstMv.getId(), primaryBatch.getId());
    }

    /**
     * Locks and validates a caller-picked batch for wastage.
     */
    private InventoryBatch lockWastageBatch(
            String businessId,
            PostStandaloneWastageRequest req,
            Item item
    ) {
        InventoryBatch b = inventoryBatchRepository
                .findByIdAndBusinessIdForUpdate(req.batchId(), businessId)
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
        BigDecimal cost = sanitizeUnitCost14_4(unitCost);
        b.setUnitCost(cost);
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
        sm.setUnitCost(sanitizeUnitCost14_4(unitCost));
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
        if (qty == null || unitCost == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return qty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
    }

    /** Fits {@code inventory_batches.unit_cost} / movement unit cost {@code DECIMAL(14,4)}. */
    private static BigDecimal sanitizeUnitCost14_4(BigDecimal unitCost) {
        BigDecimal x = unitCost == null ? BigDecimal.ZERO : unitCost;
        if (x.signum() < 0) {
            x = BigDecimal.ZERO;
        }
        if (x.compareTo(MAX_UNIT_COST_14_4) > 0) {
            x = MAX_UNIT_COST_14_4;
        }
        return x.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }
}
