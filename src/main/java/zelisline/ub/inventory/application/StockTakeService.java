package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.ApproveStockAdjustmentRequest;
import zelisline.ub.inventory.api.dto.PatchStockTakeCountsRequest;
import zelisline.ub.inventory.api.dto.PostStartStockTakeSessionRequest;
import zelisline.ub.inventory.api.dto.PostStockIncreaseRequest;
import zelisline.ub.inventory.api.dto.StockAdjustmentRequestResponse;
import zelisline.ub.inventory.api.dto.StockTakeLineResponse;
import zelisline.ub.inventory.api.dto.StockTakeSessionResponse;
import zelisline.ub.inventory.domain.StockAdjustmentRequest;
import zelisline.ub.inventory.domain.StockTakeLine;
import zelisline.ub.inventory.domain.StockTakeSession;
import zelisline.ub.inventory.repository.StockAdjustmentRequestRepository;
import zelisline.ub.inventory.repository.StockTakeSessionRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class StockTakeService {

    private static final int QTY_SCALE = 4;

    private final StockTakeSessionRepository stockTakeSessionRepository;
    private final StockAdjustmentRequestRepository stockAdjustmentRequestRepository;
    private final ItemRepository itemRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final BranchRepository branchRepository;
    private final InventoryLedgerService inventoryLedgerService;
    private final InventoryBatchPickerService inventoryBatchPickerService;

    @Transactional
    public StockTakeSessionResponse startSession(
            String businessId,
            PostStartStockTakeSessionRequest req,
            String userId
    ) {
        requireBranch(businessId, req.branchId());
        Map<String, BigDecimal> onHand = loadOnHandByItem(businessId, req.branchId());
        StockTakeSession session = new StockTakeSession();
        session.setBusinessId(businessId);
        session.setBranchId(req.branchId());
        session.setStatus(InventoryConstants.STOCKTAKE_SESSION_IN_PROGRESS);
        session.setNotes(req.notes());
        session.setStartedBy(userId);
        int order = 0;
        for (String itemId : itemRepository.findStockedItemIdsByBusinessId(businessId)) {
            BigDecimal sys = onHand.getOrDefault(itemId, BigDecimal.ZERO).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            StockTakeLine line = new StockTakeLine();
            line.setSession(session);
            line.setItemId(itemId);
            line.setSystemQtySnapshot(sys);
            line.setSortOrder(order++);
            session.getLines().add(line);
        }
        stockTakeSessionRepository.save(session);
        return buildResponse(session);
    }

    @Transactional(readOnly = true)
    public StockTakeSessionResponse getSession(String businessId, String sessionId) {
        StockTakeSession session = stockTakeSessionRepository.findByIdAndBusinessIdFetchLines(sessionId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        return buildResponse(session);
    }

    @Transactional
    public StockTakeSessionResponse applyCounts(
            String businessId,
            String sessionId,
            PatchStockTakeCountsRequest req
    ) {
        StockTakeSession session = loadSessionInProgress(businessId, sessionId);
        Map<String, StockTakeLine> byId = session.getLines().stream()
                .collect(Collectors.toMap(StockTakeLine::getId, l -> l));
        for (PatchStockTakeCountsRequest.LineCounted lc : req.lines()) {
            StockTakeLine line = byId.get(lc.lineId());
            if (line == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown line id");
            }
            line.setCountedQty(lc.countedQty().setScale(QTY_SCALE, RoundingMode.HALF_UP));
        }
        stockTakeSessionRepository.save(session);
        return buildResponse(session);
    }

    @Transactional
    public StockTakeSessionResponse closeSession(String businessId, String sessionId, String userId) {
        StockTakeSession session = loadSessionInProgress(businessId, sessionId);
        for (StockTakeLine line : session.getLines()) {
            if (line.getCountedQty() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "All lines need counted qty");
            }
            BigDecimal variance = line.getCountedQty().subtract(line.getSystemQtySnapshot());
            if (variance.signum() == 0) {
                continue;
            }
            persistAdjustmentRequest(businessId, session, line, variance, userId);
        }
        session.setStatus(InventoryConstants.STOCKTAKE_SESSION_CLOSED);
        session.setClosedAt(Instant.now());
        stockTakeSessionRepository.save(session);
        return buildResponse(session);
    }

    @Transactional
    public void approveAdjustmentRequest(
            String businessId,
            String sessionId,
            String requestId,
            ApproveStockAdjustmentRequest body,
            String userId
    ) {
        BigDecimal override = body == null ? null : body.unitCost();
        StockAdjustmentRequest request = stockAdjustmentRequestRepository
                .findByIdAndBusinessIdFetchLineAndSession(requestId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        if (!request.getStockTakeLine().getSession().getId().equals(sessionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request does not belong to session");
        }
        if (!InventoryConstants.ADJUSTMENT_REQUEST_PENDING.equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is not pending");
        }
        applyVarianceToStock(businessId, request, override, userId);
        request.setStatus(InventoryConstants.ADJUSTMENT_REQUEST_APPROVED);
        request.setDecidedBy(userId);
        request.setDecidedAt(Instant.now());
        stockAdjustmentRequestRepository.save(request);
    }

    @Transactional
    public void rejectAdjustmentRequest(
            String businessId,
            String sessionId,
            String requestId,
            String notes,
            String userId
    ) {
        StockAdjustmentRequest request = stockAdjustmentRequestRepository
                .findByIdAndBusinessIdFetchLineAndSession(requestId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        if (!request.getStockTakeLine().getSession().getId().equals(sessionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request does not belong to session");
        }
        if (!InventoryConstants.ADJUSTMENT_REQUEST_PENDING.equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is not pending");
        }
        request.setStatus(InventoryConstants.ADJUSTMENT_REQUEST_REJECTED);
        request.setNotes(notes);
        request.setDecidedBy(userId);
        request.setDecidedAt(Instant.now());
        stockAdjustmentRequestRepository.save(request);
    }

    private void applyVarianceToStock(
            String businessId,
            StockAdjustmentRequest request,
            BigDecimal unitCostOverride,
            String userId
    ) {
        BigDecimal variance = request.getVarianceQty();
        if (variance.signum() > 0) {
            BigDecimal unit = resolveInboundUnitCost(businessId, request.getBranchId(), request.getItemId(), unitCostOverride);
            inventoryLedgerService.recordStockIncrease(
                    businessId,
                    new PostStockIncreaseRequest(
                            request.getBranchId(),
                            request.getItemId(),
                            variance,
                            unit,
                            "Stock-take surplus approved"
                    ),
                    userId
            );
            return;
        }
        if (variance.signum() < 0) {
            BigDecimal pickQty = variance.negate();
            inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                    businessId,
                    request.getItemId(),
                    request.getBranchId(),
                    pickQty,
                    InventoryConstants.REF_STOCK_ADJUSTMENT_REQUEST,
                    request.getId(),
                    userId
            );
        }
    }

    private BigDecimal resolveInboundUnitCost(
            String businessId,
            String branchId,
            String itemId,
            BigDecimal override
    ) {
        if (override != null && override.signum() > 0) {
            return override.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        }
        return weightedAverageUnitCostAtBranch(businessId, itemId, branchId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "unitCost is required when there is no on-hand batch to average"
                ));
    }

    private Optional<BigDecimal> weightedAverageUnitCostAtBranch(
            String businessId,
            String itemId,
            String branchId
    ) {
        List<InventoryBatch> batches = inventoryBatchRepository
                .findByBusinessIdAndItemIdAndBranchIdAndStatusAndQuantityRemainingGreaterThanOrderByIdAsc(
                        businessId,
                        itemId,
                        branchId,
                        InventoryConstants.BATCH_STATUS_ACTIVE,
                        BigDecimal.ZERO
                );
        BigDecimal sumQty = BigDecimal.ZERO;
        BigDecimal sumVal = BigDecimal.ZERO;
        for (InventoryBatch b : batches) {
            BigDecimal q = b.getQuantityRemaining();
            sumQty = sumQty.add(q);
            sumVal = sumVal.add(q.multiply(b.getUnitCost()));
        }
        if (sumQty.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(sumVal.divide(sumQty, QTY_SCALE, RoundingMode.HALF_UP));
    }

    private void persistAdjustmentRequest(
            String businessId,
            StockTakeSession session,
            StockTakeLine line,
            BigDecimal variance,
            String userId
    ) {
        StockAdjustmentRequest r = new StockAdjustmentRequest();
        r.setBusinessId(businessId);
        r.setBranchId(session.getBranchId());
        r.setStockTakeLine(line);
        r.setItemId(line.getItemId());
        r.setAdjustmentType(InventoryConstants.ADJUSTMENT_TYPE_STOCK_TAKE);
        r.setVarianceQty(variance.setScale(QTY_SCALE, RoundingMode.HALF_UP));
        r.setSystemQtySnapshot(line.getSystemQtySnapshot());
        r.setCountedQty(line.getCountedQty());
        r.setReason(InventoryConstants.REASON_COUNTING_ERROR);
        r.setStatus(InventoryConstants.ADJUSTMENT_REQUEST_PENDING);
        r.setRequestedBy(userId);
        stockAdjustmentRequestRepository.save(r);
    }

    private StockTakeSession loadSessionInProgress(String businessId, String sessionId) {
        StockTakeSession session = stockTakeSessionRepository.findByIdAndBusinessIdFetchLines(sessionId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!InventoryConstants.STOCKTAKE_SESSION_IN_PROGRESS.equals(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is not in progress");
        }
        return session;
    }

    private StockTakeSessionResponse buildResponse(StockTakeSession session) {
        List<StockTakeLineResponse> lineDtos = session.getLines().stream()
                .map(l -> new StockTakeLineResponse(
                        l.getId(),
                        l.getItemId(),
                        l.getSystemQtySnapshot(),
                        l.getCountedQty(),
                        l.getNote()
                ))
                .toList();
        List<StockAdjustmentRequest> requests = stockAdjustmentRequestRepository
                .findByStockTakeLine_Session_IdOrderByCreatedAtAsc(session.getId());
        List<StockAdjustmentRequestResponse> reqDtos = requests.stream()
                .map(r -> new StockAdjustmentRequestResponse(
                        r.getId(),
                        r.getStockTakeLine().getId(),
                        r.getItemId(),
                        r.getVarianceQty(),
                        r.getSystemQtySnapshot(),
                        r.getCountedQty(),
                        r.getStatus()
                ))
                .toList();
        return new StockTakeSessionResponse(
                session.getId(),
                session.getBranchId(),
                session.getStatus(),
                session.getNotes(),
                session.getClosedAt(),
                lineDtos,
                reqDtos
        );
    }

    private Map<String, BigDecimal> loadOnHandByItem(String businessId, String branchId) {
        List<Object[]> rows = inventoryBatchRepository.sumQuantityRemainingByItemAtBranch(
                businessId,
                branchId,
                InventoryConstants.BATCH_STATUS_ACTIVE
        );
        Map<String, BigDecimal> out = new HashMap<>();
        for (Object[] row : rows) {
            out.put((String) row[0], toBigDecimal(row[1]));
        }
        return out;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private void requireBranch(String businessId, String branchId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }
}
