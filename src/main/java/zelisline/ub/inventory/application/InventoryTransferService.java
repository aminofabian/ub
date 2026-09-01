package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.application.BatchNumberGenerator;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.CostMethod;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.inventory.api.dto.PostStockTransferRequest;
import zelisline.ub.inventory.api.dto.StockTransferCreatedResponse;
import zelisline.ub.inventory.api.dto.StockTransferSummaryResponse;
import zelisline.ub.inventory.domain.StockTransfer;
import zelisline.ub.inventory.domain.StockTransferLine;
import zelisline.ub.inventory.repository.StockTransferRepository;
import zelisline.ub.inventory.domain.SupplyBatch;
import zelisline.ub.inventory.repository.SupplyBatchRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class InventoryTransferService {

    private static final int QTY_SCALE = 4;

    @PersistenceContext
    private EntityManager entityManager;

    private final BatchNumberGenerator batchNumberGenerator;

    private final StockTransferRepository stockTransferRepository;
    private final BranchRepository branchRepository;
    private final ItemRepository itemRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final BusinessRepository businessRepository;
    private final BusinessInventorySettingsReader businessInventorySettingsReader;
    private final SupplyBatchRepository supplyBatchRepository;
    private final SupplyBatchLifecycleService supplyBatchLifecycleService;
    private final ApplicationEventPublisher eventPublisher;
    private final PackageVariantStockResolver packageVariantStockResolver;

    /**
     * Transfers list for the Inventory → Transfers view — newest first, with
     * item names resolved so the receiving shop sees what's arriving. The
     * confirm-receipt workflow lives on these rows: an in-transit transfer
     * stays pending until the receiving side calls {@code /receive}.
     */
    @Transactional(readOnly = true)
    public List<StockTransferSummaryResponse> listTransfers(
            String businessId,
            String status,
            String branchId) {
        String effectiveStatus = (status != null && !status.isBlank()) ? status : null;
        String effectiveBranch = (branchId != null && !branchId.isBlank()) ? branchId : null;
        List<StockTransfer> transfers = stockTransferRepository.findByBusinessIdFiltered(
                businessId, effectiveStatus, effectiveBranch);

        // One batched name lookup for every line across the page.
        List<String> itemIds = transfers.stream()
                .flatMap(t -> t.getLines().stream())
                .map(StockTransferLine::getItemId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, String> namesById = itemIds.isEmpty()
                ? Map.of()
                : itemRepository.findAllById(itemIds).stream()
                        .filter(i -> itemIds.contains(i.getId()))
                        .collect(java.util.stream.Collectors.toMap(Item::getId, Item::getName, (a, b) -> a));

        return transfers.stream()
                .map(t -> new StockTransferSummaryResponse(
                        t.getId(),
                        t.getStatus(),
                        t.getFromBranchId(),
                        t.getToBranchId(),
                        t.getNotes(),
                        t.getCreatedAt(),
                        t.getCreatedBy(),
                        t.getLines().stream()
                                .map(l -> new StockTransferSummaryResponse.Line(
                                        l.getItemId(),
                                        namesById.getOrDefault(l.getItemId(), "Unknown item"),
                                        l.getQuantity()))
                                .toList()))
                .toList();
    }

    @Transactional
    public StockTransferCreatedResponse createDraft(
            String businessId,
            PostStockTransferRequest req,
            String userId
    ) {
        validateBranchesDifferentTenant(businessId, req.fromBranchId(), req.toBranchId());
        StockTransfer t = new StockTransfer();
        t.setBusinessId(businessId);
        t.setFromBranchId(req.fromBranchId());
        t.setToBranchId(req.toBranchId());
        t.setStatus(InventoryConstants.TRANSFER_STATUS_DRAFT);
        t.setNotes(req.notes());
        t.setCreatedBy(userId);
        int order = 0;
        for (PostStockTransferRequest.Line lr : req.lines()) {
            packageVariantStockResolver.requireInventoryHolder(businessId, lr.itemId());
            BigDecimal qty = lr.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            StockTransferLine line = new StockTransferLine();
            line.setTransfer(t);
            line.setItemId(lr.itemId());
            line.setQuantity(qty);
            line.setSortOrder(order++);
            t.getLines().add(line);
        }
        stockTransferRepository.save(t);

        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.TransferInitiatedEvent(
                businessId, req.fromBranchId(), req.toBranchId(), t.getId(), req.lines().size()));

        return new StockTransferCreatedResponse(t.getId(), t.getStatus());
    }

    @Transactional
    public void completeTransfer(String businessId, String transferId, String userId) {
        StockTransfer t = stockTransferRepository.findByIdAndBusinessIdFetchLines(transferId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        if (!InventoryConstants.TRANSFER_STATUS_DRAFT.equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer is not draft");
        }
        validateBranchesDifferentTenant(businessId, t.getFromBranchId(), t.getToBranchId());
        lockDistinctItemsInOrder(businessId, t.getLines());
        for (StockTransferLine line : t.getLines()) {
            moveLine(t, line, userId, InventoryConstants.BATCH_STATUS_ACTIVE);
        }
        t.setStatus(InventoryConstants.TRANSFER_STATUS_COMPLETED);
        stockTransferRepository.save(t);

        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.TransferReceivedEvent(
                businessId, t.getFromBranchId(), t.getToBranchId(), transferId));
    }

    /**
     * Phase 9: Send a draft transfer — goods leave the source branch and are
     * marked in-transit. Destination batches are created with status
     * {@code in_transit} so they are NOT sellable until received.
     */
    @Transactional
    public void sendTransfer(String businessId, String transferId, String userId) {
        StockTransfer t = stockTransferRepository.findByIdAndBusinessIdFetchLines(transferId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        if (!InventoryConstants.TRANSFER_STATUS_DRAFT.equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer must be in draft status to send");
        }
        validateBranchesDifferentTenant(businessId, t.getFromBranchId(), t.getToBranchId());
        lockDistinctItemsInOrder(businessId, t.getLines());
        for (StockTransferLine line : t.getLines()) {
            moveLine(t, line, userId, InventoryConstants.BATCH_STATUS_IN_TRANSIT);
        }
        t.setStatus(InventoryConstants.TRANSFER_STATUS_IN_TRANSIT);
        stockTransferRepository.save(t);

        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.TransferSentEvent(
                businessId, t.getFromBranchId(), t.getToBranchId(), transferId, t.getLines().size()));
    }

    /**
     * Phase 9: Receive an in-transit transfer — destination batches flip from
     * {@code in_transit} to {@code active}, making goods sellable at the
     * receiving branch. Transfer-in stock movements are recorded.
     *
     * <p>Branch-scoped staff may only confirm receipt for transfers addressed
     * to their own branch; branch-unscoped operators (owners/admins) may
     * confirm for any branch.
     */
    @Transactional
    public void receiveTransfer(
            String businessId,
            String transferId,
            String userId,
            String callerBranchId) {
        StockTransfer t = stockTransferRepository.findByIdAndBusinessIdFetchLines(transferId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        if (!InventoryConstants.TRANSFER_STATUS_IN_TRANSIT.equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer must be in-transit to receive");
        }
        requireDestinationScope(t, callerBranchId);
        validateBranchesDifferentTenant(businessId, t.getFromBranchId(), t.getToBranchId());

        // Flip all destination batches created by this transfer from in_transit → active
        // and record the transfer-in stock movements.
        for (StockTransferLine line : t.getLines()) {
            receiveLine(t, line, userId);
        }
        t.setStatus(InventoryConstants.TRANSFER_STATUS_COMPLETED);
        stockTransferRepository.save(t);

        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.TransferReceivedEvent(
                businessId, t.getFromBranchId(), t.getToBranchId(), transferId));
    }

    /**
     * Phase 9: Cancel an in-transit transfer — reverses stock movements at source,
     * discards destination batches, and marks the transfer cancelled.
     *
     * <p>Same destination scoping as {@link #receiveTransfer}: the receiving
     * branch owns the decision to refuse a delivery.
     */
    @Transactional
    public void cancelTransfer(
            String businessId,
            String transferId,
            String userId,
            String callerBranchId) {
        StockTransfer t = stockTransferRepository.findByIdAndBusinessIdFetchLines(transferId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        if (!InventoryConstants.TRANSFER_STATUS_IN_TRANSIT.equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only in-transit transfers can be cancelled");
        }
        requireDestinationScope(t, callerBranchId);
        validateBranchesDifferentTenant(businessId, t.getFromBranchId(), t.getToBranchId());

        for (StockTransferLine line : t.getLines()) {
            cancelLine(t, line, userId);
        }
        t.setStatus(InventoryConstants.TRANSFER_STATUS_CANCELLED);
        stockTransferRepository.save(t);

        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.TransferCancelledEvent(
                businessId, t.getFromBranchId(), t.getToBranchId(), transferId));
    }

    /**
     * Confirm-receipt guard: a branch-scoped caller must belong to the
     * transfer's destination branch. Unscoped callers (owner/admin tokens carry
     * no branch) pass — they already operate across branches.
     */
    private static void requireDestinationScope(StockTransfer t, String callerBranchId) {
        String scope = callerBranchId == null ? "" : callerBranchId.trim();
        if (!scope.isEmpty() && !scope.equals(t.getToBranchId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only staff at the receiving branch can confirm this transfer");
        }
    }

    private void cancelLine(StockTransfer t, StockTransferLine line, String userId) {
        String businessId = t.getBusinessId();
        // Find destination batches in in_transit status and delete them
        List<InventoryBatch> inTransitBatches = inventoryBatchRepository
                .findBySourceTypeAndSourceIdAndStatus(
                        InventoryConstants.BATCH_SOURCE_STOCK_TRANSFER,
                        line.getId(),
                        InventoryConstants.BATCH_STATUS_IN_TRANSIT);
        for (InventoryBatch dest : inTransitBatches) {
            // Return stock to source by reversing the transfer_out as a stock adjustment
            StockMovement reverseMv = new StockMovement();
            reverseMv.setBusinessId(businessId);
            reverseMv.setBranchId(t.getFromBranchId());
            reverseMv.setItemId(dest.getItemId());
            reverseMv.setBatchId(dest.getId());
            reverseMv.setMovementType(InventoryConstants.MOVEMENT_ADJUSTMENT);
            reverseMv.setReferenceType(InventoryConstants.REF_STOCK_TRANSFER_LINE);
            reverseMv.setReferenceId(line.getId());
            reverseMv.setQuantityDelta(dest.getQuantityRemaining());
            reverseMv.setUnitCost(dest.getUnitCost());
            reverseMv.setNotes("Transfer cancelled — stock returned");
            reverseMv.setCreatedBy(userId);
            stockMovementRepository.save(reverseMv);

            // Delete the in_transit destination batch
            dest.setStatus(InventoryConstants.BATCH_STATUS_DEPLETED);
            dest.setQuantityRemaining(BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP));
            inventoryBatchRepository.save(dest);
        }
    }

    private void receiveLine(StockTransfer t, StockTransferLine line, String userId) {
        String businessId = t.getBusinessId();
        List<InventoryBatch> inTransitBatches = inventoryBatchRepository
                .findBySourceTypeAndSourceIdAndStatus(
                        InventoryConstants.BATCH_SOURCE_STOCK_TRANSFER,
                        line.getId(),
                        InventoryConstants.BATCH_STATUS_IN_TRANSIT);
        for (InventoryBatch dest : inTransitBatches) {
            dest.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
            inventoryBatchRepository.save(dest);

            StockMovement inMv = new StockMovement();
            inMv.setBusinessId(businessId);
            inMv.setBranchId(t.getToBranchId());
            inMv.setItemId(dest.getItemId());
            inMv.setBatchId(dest.getId());
            inMv.setMovementType(InventoryConstants.MOVEMENT_TRANSFER_IN);
            inMv.setReferenceType(InventoryConstants.REF_STOCK_TRANSFER_LINE);
            inMv.setReferenceId(line.getId());
            inMv.setQuantityDelta(dest.getQuantityRemaining());
            inMv.setUnitCost(dest.getUnitCost());
            inMv.setNotes("Stock transfer received");
            inMv.setCreatedBy(userId);
            stockMovementRepository.save(inMv);
        }
    }

    private void moveLine(StockTransfer t, StockTransferLine line, String userId, String destBatchStatus) {
        String businessId = t.getBusinessId();
        PackageVariantStockResolver.StockPickResolution stock =
                packageVariantStockResolver.resolveInbound(businessId, line.getItemId(), line.getQuantity());
        String stockHolderId = stock.stockItemId();
        Item item = packageVariantStockResolver.requireInventoryHolder(businessId, stockHolderId);
        List<InventoryBatch> locked = inventoryBatchRepository.lockActiveBatchesForPick(
                businessId,
                stockHolderId,
                t.getFromBranchId(),
                InventoryConstants.BATCH_STATUS_ACTIVE,
                BigDecimal.ZERO
        );
        List<InventoryBatch> working = new ArrayList<>(locked);
        BatchAllocationPlanner.sortBatchesForPick(working, item, costMethodForTenant(businessId));
        List<BatchAllocationLine> picks = BatchAllocationPlanner.allocateInOrder(working, stock.stockQuantity());
        Map<String, InventoryBatch> byId = new HashMap<>();
        for (InventoryBatch b : locked) {
            byId.put(b.getId(), b);
        }
        for (BatchAllocationLine slice : picks) {
            applyPickSlice(t, line, userId, stockHolderId, byId.get(slice.batchId()), slice, destBatchStatus);
        }
    }

    private void applyPickSlice(
            StockTransfer t,
            StockTransferLine line,
            String userId,
            String stockHolderId,
            InventoryBatch src,
            BatchAllocationLine slice,
            String destBatchStatus
    ) {
        if (src == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch disappeared");
        }
        String businessId = t.getBusinessId();
        subtractFromSourceBatch(src, slice);
        persistTransferOut(t, line, userId, stockHolderId, src, slice, businessId);
        InventoryBatch dest = newBatchAtDestination(t, line, stockHolderId, src, slice, destBatchStatus);
        inventoryBatchRepository.save(dest);
        // Phase 9: transfer_in movement is deferred until receive for in_transit batches.
        // For the legacy completeTransfer path (active), record it immediately.
        if (InventoryConstants.BATCH_STATUS_ACTIVE.equals(destBatchStatus)) {
            persistTransferIn(t, line, userId, stockHolderId, dest, slice, businessId);
        }
    }

    private static void subtractFromSourceBatch(InventoryBatch src, BatchAllocationLine pick) {
        BigDecimal next = src.getQuantityRemaining().subtract(pick.quantity()).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (next.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch quantity changed during transfer");
        }
        src.setQuantityRemaining(next);
    }

    private void persistTransferOut(
            StockTransfer t,
            StockTransferLine line,
            String userId,
            String stockHolderId,
            InventoryBatch src,
            BatchAllocationLine slice,
            String businessId
    ) {
        inventoryBatchRepository.save(src);
        supplyBatchLifecycleService.checkAndTransitionToSoldoutIfNeeded(businessId, src.getSupplyBatchId());
        StockMovement outMv = new StockMovement();
        outMv.setBusinessId(businessId);
        outMv.setBranchId(t.getFromBranchId());
        outMv.setItemId(stockHolderId);
        outMv.setBatchId(src.getId());
        outMv.setMovementType(InventoryConstants.MOVEMENT_TRANSFER_OUT);
        outMv.setReferenceType(InventoryConstants.REF_STOCK_TRANSFER_LINE);
        outMv.setReferenceId(line.getId());
        outMv.setQuantityDelta(slice.quantity().negate());
        outMv.setUnitCost(slice.unitCost());
        outMv.setNotes("Stock transfer out");
        outMv.setCreatedBy(userId);
        stockMovementRepository.save(outMv);
    }

    private void persistTransferIn(
            StockTransfer t,
            StockTransferLine line,
            String userId,
            String stockHolderId,
            InventoryBatch dest,
            BatchAllocationLine slice,
            String businessId
    ) {
        StockMovement inMv = new StockMovement();
        inMv.setBusinessId(businessId);
        inMv.setBranchId(t.getToBranchId());
        inMv.setItemId(stockHolderId);
        inMv.setBatchId(dest.getId());
        inMv.setMovementType(InventoryConstants.MOVEMENT_TRANSFER_IN);
        inMv.setReferenceType(InventoryConstants.REF_STOCK_TRANSFER_LINE);
        inMv.setReferenceId(line.getId());
        inMv.setQuantityDelta(slice.quantity());
        inMv.setUnitCost(slice.unitCost());
        inMv.setNotes("Stock transfer in");
        inMv.setCreatedBy(userId);
        stockMovementRepository.save(inMv);
    }

    private InventoryBatch newBatchAtDestination(
            StockTransfer t,
            StockTransferLine line,
            String stockHolderId,
            InventoryBatch src,
            BatchAllocationLine slice,
            String destBatchStatus
    ) {
        SupplyBatch sb = new SupplyBatch();
        sb.setBusinessId(t.getBusinessId());
        sb.setBranchId(t.getToBranchId());
        sb.setSupplierId(src.getSupplierId());
        sb.setBatchNumber(batchNumberGenerator.next(null, null, Instant.now(), t.getBusinessId()));
        sb.setBatchName(null);
        sb.setSourceType(InventoryConstants.BATCH_SOURCE_STOCK_TRANSFER);
        sb.setSourceId(line.getId());
        sb.setItemCount(1);
        sb.setTotalInitialQuantity(slice.quantity());
        sb.setTotalRemainingQuantity(slice.quantity());
        sb.setReceivedAt(Instant.now());
        sb.setStatus(destBatchStatus);
        supplyBatchRepository.save(sb);

        InventoryBatch dest = new InventoryBatch();
        dest.setBusinessId(t.getBusinessId());
        dest.setBranchId(t.getToBranchId());
        dest.setItemId(stockHolderId);
        dest.setSupplyBatchId(sb.getId());
        dest.setSupplierId(src.getSupplierId());
        dest.setBatchNumber(batchNumberForSlice(line, slice));
        dest.setSourceType(InventoryConstants.BATCH_SOURCE_STOCK_TRANSFER);
        dest.setSourceId(line.getId());
        dest.setInitialQuantity(slice.quantity());
        dest.setQuantityRemaining(slice.quantity());
        dest.setUnitCost(slice.unitCost().setScale(QTY_SCALE, RoundingMode.HALF_UP));
        dest.setReceivedAt(Instant.now());
        dest.setExpiryDate(src.getExpiryDate());
        dest.setStatus(destBatchStatus);
        return dest;
    }

    private static String batchNumberForSlice(StockTransferLine line, BatchAllocationLine pick) {
        String line8 = line.getId().replace("-", "").substring(0, 8).toUpperCase();
        String batch8 = pick.batchId().replace("-", "").substring(0, 8).toUpperCase();
        return "XFR-" + line8 + "-" + batch8;
    }

    private void lockDistinctItemsInOrder(String businessId, List<StockTransferLine> lines) {
        TreeSet<String> itemIds = new TreeSet<>();
        for (StockTransferLine line : lines) {
            itemIds.add(line.getItemId());
        }
        for (String itemId : itemIds) {
            Item item = requireStockedItem(businessId, itemId);
            entityManager.lock(item, LockModeType.PESSIMISTIC_WRITE);
        }
    }

    private void validateBranchesDifferentTenant(String businessId, String fromBranchId, String toBranchId) {
        if (fromBranchId.equals(toBranchId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "From and to branch must differ");
        }
        requireBranch(businessId, fromBranchId);
        requireBranch(businessId, toBranchId);
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

    private CostMethod costMethodForTenant(String businessId) {
        String json = businessRepository.findById(businessId).map(Business::getSettings).orElse("{}");
        return businessInventorySettingsReader.costMethodFromSettingsJson(json);
    }
}
