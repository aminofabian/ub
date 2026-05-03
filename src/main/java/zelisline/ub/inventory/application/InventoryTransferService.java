package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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
import zelisline.ub.inventory.CostMethod;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.inventory.api.dto.PostStockTransferRequest;
import zelisline.ub.inventory.api.dto.StockTransferCreatedResponse;
import zelisline.ub.inventory.domain.StockTransfer;
import zelisline.ub.inventory.domain.StockTransferLine;
import zelisline.ub.inventory.repository.StockTransferRepository;
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

    private final StockTransferRepository stockTransferRepository;
    private final BranchRepository branchRepository;
    private final ItemRepository itemRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final BusinessRepository businessRepository;
    private final BusinessInventorySettingsReader businessInventorySettingsReader;

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
            requireStockedItem(businessId, lr.itemId());
            BigDecimal qty = lr.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            StockTransferLine line = new StockTransferLine();
            line.setTransfer(t);
            line.setItemId(lr.itemId());
            line.setQuantity(qty);
            line.setSortOrder(order++);
            t.getLines().add(line);
        }
        stockTransferRepository.save(t);
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
            moveLine(t, line, userId);
        }
        t.setStatus(InventoryConstants.TRANSFER_STATUS_COMPLETED);
        stockTransferRepository.save(t);
    }

    private void moveLine(StockTransfer t, StockTransferLine line, String userId) {
        String businessId = t.getBusinessId();
        Item item = requireStockedItem(businessId, line.getItemId());
        List<InventoryBatch> locked = inventoryBatchRepository.lockActiveBatchesForPick(
                businessId,
                line.getItemId(),
                t.getFromBranchId(),
                InventoryConstants.BATCH_STATUS_ACTIVE,
                BigDecimal.ZERO
        );
        List<InventoryBatch> working = new ArrayList<>(locked);
        BatchAllocationPlanner.sortBatchesForPick(working, item, costMethodForTenant(businessId));
        List<BatchAllocationLine> picks = BatchAllocationPlanner.allocateInOrder(working, line.getQuantity());
        Map<String, InventoryBatch> byId = new HashMap<>();
        for (InventoryBatch b : locked) {
            byId.put(b.getId(), b);
        }
        for (BatchAllocationLine pick : picks) {
            applyPickSlice(t, line, userId, byId.get(pick.batchId()), pick);
        }
    }

    private void applyPickSlice(
            StockTransfer t,
            StockTransferLine line,
            String userId,
            InventoryBatch src,
            BatchAllocationLine pick
    ) {
        if (src == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch disappeared");
        }
        String businessId = t.getBusinessId();
        subtractFromSourceBatch(src, pick);
        persistTransferOut(t, line, userId, src, pick, businessId);
        InventoryBatch dest = newBatchAtDestination(t, line, src, pick);
        inventoryBatchRepository.save(dest);
        persistTransferIn(t, line, userId, dest, pick, businessId);
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
            InventoryBatch src,
            BatchAllocationLine pick,
            String businessId
    ) {
        inventoryBatchRepository.save(src);
        StockMovement outMv = new StockMovement();
        outMv.setBusinessId(businessId);
        outMv.setBranchId(t.getFromBranchId());
        outMv.setItemId(line.getItemId());
        outMv.setBatchId(src.getId());
        outMv.setMovementType(InventoryConstants.MOVEMENT_TRANSFER_OUT);
        outMv.setReferenceType(InventoryConstants.REF_STOCK_TRANSFER_LINE);
        outMv.setReferenceId(line.getId());
        outMv.setQuantityDelta(pick.quantity().negate());
        outMv.setUnitCost(pick.unitCost());
        outMv.setNotes("Stock transfer out");
        outMv.setCreatedBy(userId);
        stockMovementRepository.save(outMv);
    }

    private void persistTransferIn(
            StockTransfer t,
            StockTransferLine line,
            String userId,
            InventoryBatch dest,
            BatchAllocationLine pick,
            String businessId
    ) {
        StockMovement inMv = new StockMovement();
        inMv.setBusinessId(businessId);
        inMv.setBranchId(t.getToBranchId());
        inMv.setItemId(line.getItemId());
        inMv.setBatchId(dest.getId());
        inMv.setMovementType(InventoryConstants.MOVEMENT_TRANSFER_IN);
        inMv.setReferenceType(InventoryConstants.REF_STOCK_TRANSFER_LINE);
        inMv.setReferenceId(line.getId());
        inMv.setQuantityDelta(pick.quantity());
        inMv.setUnitCost(pick.unitCost());
        inMv.setNotes("Stock transfer in");
        inMv.setCreatedBy(userId);
        stockMovementRepository.save(inMv);
    }

    private InventoryBatch newBatchAtDestination(
            StockTransfer t,
            StockTransferLine line,
            InventoryBatch src,
            BatchAllocationLine pick
    ) {
        InventoryBatch dest = new InventoryBatch();
        dest.setBusinessId(t.getBusinessId());
        dest.setBranchId(t.getToBranchId());
        dest.setItemId(line.getItemId());
        dest.setSupplierId(src.getSupplierId());
        dest.setBatchNumber(batchNumberForSlice(line, pick));
        dest.setSourceType(InventoryConstants.BATCH_SOURCE_STOCK_TRANSFER);
        dest.setSourceId(line.getId());
        dest.setInitialQuantity(pick.quantity());
        dest.setQuantityRemaining(pick.quantity());
        dest.setUnitCost(pick.unitCost().setScale(QTY_SCALE, RoundingMode.HALF_UP));
        dest.setReceivedAt(Instant.now());
        dest.setExpiryDate(src.getExpiryDate());
        dest.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
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
