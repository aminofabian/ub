package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.application.PackageVariantStockResolver.StockPickResolution;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.integrations.webhook.WebhookEventTypes;
import zelisline.ub.integrations.webhook.application.WebhookEnqueueService;
import zelisline.ub.notifications.application.NotificationOutboxService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.CostMethod;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Batch ordering (FEFO / FIFO / LIFO) and pessimistic-lock picks. Phase 4 sale can call
 * {@link #pickAndApplyPhysicalDecrement} inside the same transaction as revenue/COGS posting.
 */
@Service
@RequiredArgsConstructor
public class InventoryBatchPickerService {

    private static final int QTY_SCALE = 4;

    @PersistenceContext
    private EntityManager entityManager;

    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final BusinessInventorySettingsReader businessInventorySettingsReader;
    private final WebhookEnqueueService webhookEnqueueService;
    private final NotificationOutboxService notificationOutboxService;
    private final ApplicationEventPublisher eventPublisher;
    private final SupplyBatchLifecycleService supplyBatchLifecycleService;
    private final PackageVariantStockResolver packageVariantStockResolver;

    @Transactional(readOnly = true)
    public List<BatchAllocationLine> previewAllocation(
            String businessId,
            String itemId,
            String branchId,
            BigDecimal quantity
    ) {
        requireBranch(businessId, branchId);
        Item catalogItem = packageVariantStockResolver.requireSellableItem(businessId, itemId);
        StockPickResolution pick = packageVariantStockResolver.resolvePick(businessId, itemId, quantity);
        Item item = requireStockedItem(businessId, pick.stockItemId());
        List<InventoryBatch> batches = loadActiveBatchesReadOnly(businessId, catalogItem, branchId);
        List<InventoryBatch> working = new ArrayList<>(batches);
        // ── Exclude expired batches BEFORE sorting ────────────────────────
        working = new ArrayList<>(BatchAllocationPlanner.excludeExpired(working));
        if (working.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No non-expired stock available for this item"
            );
        }
        BatchAllocationPlanner.sortBatchesForPick(
                working,
                item,
                costMethodForTenant(businessId)
        );
        return BatchAllocationPlanner.allocateInOrder(working, pick.stockQuantity());
    }

    /**
     * Locks all on-hand batches for the item at the branch (ordered by id for deadlock avoidance),
     * allocates by policy, decrements {@link InventoryBatch#getQuantityRemaining}, decreases
     * {@link Item#getCurrentStock}, and appends {@link StockMovement} rows. Does not post GL
     * (Phase 4 sale journals sit beside this in one transaction).
     */
    @Transactional
    public List<BatchAllocationLine> pickAndApplyPhysicalDecrement(
            String businessId,
            String itemId,
            String branchId,
            BigDecimal quantity,
            String referenceType,
            String referenceId,
            String userId
    ) {
        return pickAndApplyPhysicalDecrement(
                businessId,
                itemId,
                branchId,
                quantity,
                referenceType,
                referenceId,
                InventoryConstants.MOVEMENT_ADJUSTMENT,
                userId
        );
    }

    @Transactional
    public List<BatchAllocationLine> pickAndApplyPhysicalDecrement(
            String businessId,
            String itemId,
            String branchId,
            BigDecimal quantity,
            String referenceType,
            String referenceId,
            String movementType,
            String userId
    ) {
        requireBranch(businessId, branchId);
        Item catalogItem = packageVariantStockResolver.requireSellableItem(businessId, itemId);
        StockPickResolution pick = packageVariantStockResolver.resolvePick(businessId, itemId, quantity);
        Item item = requireStockedItem(businessId, pick.stockItemId());
        entityManager.lock(item, LockModeType.PESSIMISTIC_WRITE);
        List<InventoryBatch> locked = lockActiveBatchesForPool(
                businessId, branchId, catalogItem);
        List<InventoryBatch> working = new ArrayList<>(locked);
        // ── Exclude expired batches BEFORE sorting ────────────────────────
        working = new ArrayList<>(BatchAllocationPlanner.excludeExpired(working));
        if (working.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No non-expired stock available for this item"
            );
        }
        BatchAllocationPlanner.sortBatchesForPick(
                working,
                item,
                costMethodForTenant(businessId)
        );
        List<BatchAllocationLine> lines = BatchAllocationPlanner.allocateInOrder(working, pick.stockQuantity());

        Map<String, InventoryBatch> byId = new HashMap<>();
        for (InventoryBatch b : locked) {
            byId.put(b.getId(), b);
        }

        for (BatchAllocationLine line : lines) {
            InventoryBatch batch = byId.get(line.batchId());
            if (batch == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch disappeared");
            }
            BigDecimal next = batch.getQuantityRemaining().subtract(line.quantity()).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            if (next.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch quantity changed during pick");
            }
            batch.setQuantityRemaining(next);
            inventoryBatchRepository.save(batch);

            // Publish real-time stock.depleted event when batch hits zero
            if (next.signum() == 0) {
                eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.StockDepletedEvent(
                        businessId, branchId, pick.stockItemId(), item.getName(), line.batchId()));
            }

            // Auto-detect sold-out on parent supply batch
            supplyBatchLifecycleService.checkAndTransitionToSoldoutIfNeeded(
                    businessId, batch.getSupplyBatchId());

            StockMovement sm = new StockMovement();
            sm.setBusinessId(businessId);
            sm.setBranchId(branchId);
            sm.setItemId(pick.stockItemId());
            sm.setBatchId(line.batchId());
            sm.setMovementType(movementType);
            sm.setReferenceType(referenceType);
            sm.setReferenceId(referenceId);
            sm.setQuantityDelta(line.quantity().negate());
            sm.setUnitCost(line.unitCost());
            sm.setNotes("Batch pick allocation");
            sm.setCreatedBy(userId);
            stockMovementRepository.save(sm);
        }

        BigDecimal stockBefore = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
        applyStockDelta(item, pick.stockQuantity().negate());
        maybeEnqueueLowStockWebhook(businessId, branchId, pick.stockItemId(), item, stockBefore);
        return lines;
    }

    public String newPickReferenceId() {
        return UUID.randomUUID().toString();
    }

    private CostMethod costMethodForTenant(String businessId) {
        String json = businessRepository.findById(businessId).map(Business::getSettings).orElse("{}");
        return businessInventorySettingsReader.costMethodFromSettingsJson(json);
    }

    private List<InventoryBatch> loadActiveBatchesReadOnly(
            String businessId,
            Item catalogItem,
            String branchId
    ) {
        Set<String> pool = packageVariantStockResolver.branchStockPoolItemIds(businessId, catalogItem);
        if (pool.size() <= 1) {
            String only = pool.iterator().next();
            return inventoryBatchRepository.findActiveBatchesForPreview(
                    businessId,
                    only,
                    branchId,
                    InventoryConstants.BATCH_STATUS_ACTIVE,
                    BigDecimal.ZERO
            );
        }
        return inventoryBatchRepository.findActiveBatchesForItems(
                businessId,
                branchId,
                InventoryConstants.BATCH_STATUS_ACTIVE,
                List.copyOf(pool),
                BigDecimal.ZERO
        );
    }

    private List<InventoryBatch> lockActiveBatchesForPool(
            String businessId,
            String branchId,
            Item catalogItem
    ) {
        Set<String> pool = packageVariantStockResolver.branchStockPoolItemIds(businessId, catalogItem);
        if (pool.size() <= 1) {
            return inventoryBatchRepository.lockActiveBatchesForPick(
                    businessId,
                    pool.iterator().next(),
                    branchId,
                    InventoryConstants.BATCH_STATUS_ACTIVE,
                    BigDecimal.ZERO
            );
        }
        return inventoryBatchRepository.lockActiveBatchesForPickForItems(
                businessId,
                branchId,
                InventoryConstants.BATCH_STATUS_ACTIVE,
                List.copyOf(pool),
                BigDecimal.ZERO
        );
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
        BigDecimal base = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
        BigDecimal next = base.add(delta).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (next.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock cannot go negative");
        }
        item.setCurrentStock(next);
        itemRepository.save(item);
    }

    /** Fire once when on-hand quantity crosses from above reorder level to at-or-below (same txn as pick). */
    private void maybeEnqueueLowStockWebhook(
            String businessId,
            String branchId,
            String itemId,
            Item item,
            BigDecimal stockBeforePick
    ) {
        BigDecimal reorder = item.getReorderLevel();
        if (reorder == null) {
            return;
        }
        BigDecimal after = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
        if (stockBeforePick.compareTo(reorder) > 0 && after.compareTo(reorder) <= 0) {
            var data = new LinkedHashMap<String, Object>();
            data.put("itemId", itemId);
            data.put("branchId", branchId);
            data.put("currentStock", after.toPlainString());
            data.put("reorderLevel", reorder.toPlainString());
            webhookEnqueueService.enqueue(businessId, WebhookEventTypes.STOCK_LOW_STOCK, data, null);

            eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.StockLowEvent(
                    businessId, branchId, itemId, item.getName(), after, reorder));

            try {
                notificationOutboxService.enqueueStockLow(
                        businessId,
                        branchId,
                        itemId,
                        item.getName(),
                        after.toPlainString(),
                        reorder.toPlainString());
            } catch (Exception ex) {
                // must not roll back inventory pick
            }
        }
    }
}
