package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Aisle;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.AisleRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.ApproveStockAdjustmentRequest;
import zelisline.ub.inventory.api.dto.CreateItemWithStocktakeLineRequest;
import zelisline.ub.inventory.api.dto.PatchStockTakeCountsRequest;
import zelisline.ub.inventory.api.dto.PostStartStockTakeSessionRequest;
import zelisline.ub.inventory.api.dto.PostBatchDecreaseRequest;
import zelisline.ub.inventory.api.dto.PostBatchIncreaseRequest;
import zelisline.ub.inventory.api.dto.PostStockIncreaseRequest;
import zelisline.ub.inventory.api.dto.ReconciliationLine;
import zelisline.ub.inventory.api.dto.ReconciliationResponse;
import zelisline.ub.inventory.api.dto.StockAdjustmentRequestResponse;
import zelisline.ub.inventory.api.dto.StockTakeLineBatchResponse;
import zelisline.ub.inventory.api.dto.StockTakeLineResponse;
import zelisline.ub.inventory.api.dto.StockTakeSessionResponse;
import zelisline.ub.inventory.domain.StockAdjustmentRequest;
import zelisline.ub.inventory.domain.StockTakeLine;
import zelisline.ub.inventory.domain.StockTakeLineBatch;
import zelisline.ub.inventory.domain.StockTakeSession;
import zelisline.ub.inventory.repository.StockAdjustmentRequestRepository;
import zelisline.ub.inventory.repository.StockTakeChecklistItemRepository;
import zelisline.ub.inventory.repository.StockTakeSessionRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.sales.application.SalesIntelligenceService;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class StockTakeService {

    private static final int QTY_SCALE = 4;
    private static final DateTimeFormatter NAME_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("d MMM yyyy");

    private final StockTakeSessionRepository stockTakeSessionRepository;
    private final StockAdjustmentRequestRepository stockAdjustmentRequestRepository;
    private final StockTakeChecklistItemRepository checklistItemRepository;
    private final ItemRepository itemRepository;
    private final AisleRepository aisleRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final BranchRepository branchRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final InventoryLedgerService inventoryLedgerService;
    private final InventoryBatchPickerService inventoryBatchPickerService;
    private final ItemCatalogService itemCatalogService;
    private final SalesIntelligenceService salesIntelligenceService;

    // ── Session lifecycle ──────────────────────────────────────────────

    @Transactional
    public StockTakeSessionResponse startSession(
        String businessId,
        PostStartStockTakeSessionRequest req,
        String userId
    ) {
        requireBranch(businessId, req.branchId());
        requireValidSessionType(req.sessionType());

        LocalDate sessionDate =
            req.sessionDate() != null ? req.sessionDate() : LocalDate.now();

        Map<String, BigDecimal> onHand = loadOnHandByItem(
            businessId,
            req.branchId()
        );

        // Determine item IDs for this session.
        // - If caller supplied IDs explicitly, use those (after validation earlier).
        // - If evening session: auto-load the item IDs that were confirmed in the morning
        //   session for the same branch & date. This ensures reconciliation compares
        //   the same items end-to-end.
        // - Otherwise: fall back to the checklist or all stocked items.
        List<String> itemIds = req.itemIds();
        if (itemIds == null || itemIds.isEmpty()) {
            if (
                InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING.equals(
                    req.sessionType()
                )
            ) {
                // Auto-load from confirmed lines in ALL morning sessions for this branch/date
                itemIds = stockTakeSessionRepository
                    .findAllByTypeAndBranchAndDateFetchLines(
                        businessId,
                        req.branchId(),
                        InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING,
                        sessionDate
                    )
                    .stream()
                    .flatMap(m -> m.getLines().stream())
                    .filter(l ->
                        InventoryConstants.STOCKTAKE_LINE_CONFIRMED.equals(
                            l.getStatus()
                        )
                    )
                    .map(StockTakeLine::getItemId)
                    .distinct()
                    .toList();
            }
            if (itemIds == null || itemIds.isEmpty()) {
                itemIds =
                    checklistItemRepository.findItemIdsByBusinessIdAndSessionType(
                        businessId,
                        req.sessionType()
                    );
                if (itemIds.isEmpty()) {
                    itemIds = itemRepository.findStockedItemIdsByBusinessId(
                        businessId
                    );
                }
            }
        }
        itemIds = filterActiveItemIds(businessId, itemIds);

        int sessionNumber = stockTakeSessionRepository.nextSessionNumber(
            businessId
        );

        // Load active batches for all items in one query (batch-level stock-take support)
        Map<String, List<InventoryBatch>> batchesByItem =
            inventoryBatchRepository
                .findActiveBatchesForItems(
                    businessId,
                    req.branchId(),
                    InventoryConstants.BATCH_STATUS_ACTIVE,
                    itemIds,
                    BigDecimal.ZERO
                )
                .stream()
                .collect(Collectors.groupingBy(InventoryBatch::getItemId));

        StockTakeSession session = new StockTakeSession();
        session.setBusinessId(businessId);
        session.setBranchId(req.branchId());
        session.setSessionType(req.sessionType());
        session.setSessionDate(sessionDate);
        session.setStatus(InventoryConstants.STOCKTAKE_SESSION_IN_PROGRESS);
        session.setNotes(req.notes());
        session.setStartedBy(userId);
        session.setSessionNumber(sessionNumber);

        int order = 0;
        for (String itemId : itemIds) {
            BigDecimal sys = onHand
                .getOrDefault(itemId, BigDecimal.ZERO)
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);
            StockTakeLine line = new StockTakeLine();
            line.setSession(session);
            line.setItemId(itemId);
            line.setSystemQtySnapshot(sys);
            line.setStatus(InventoryConstants.STOCKTAKE_LINE_PENDING);
            line.setSortOrder(order++);
            // Pre-fill aisle from product data
            resolveAisleName(businessId, itemId).ifPresent(line::setAisle);

            // Populate batch-level snapshots
            List<InventoryBatch> itemBatches = batchesByItem.getOrDefault(
                itemId,
                List.of()
            );
            int batchOrder = 0;
            for (InventoryBatch ib : itemBatches) {
                StockTakeLineBatch slb = new StockTakeLineBatch();
                slb.setLine(line);
                slb.setBatchId(ib.getId());
                slb.setBatchNumber(ib.getBatchNumber());
                slb.setExpiryDate(ib.getExpiryDate());
                slb.setSystemQtySnapshot(
                    ib.getQuantityRemaining()
                        .setScale(QTY_SCALE, RoundingMode.HALF_UP)
                );
                slb.setSortOrder(batchOrder++);
                line.getBatches().add(slb);
            }

            session.getLines().add(line);
        }
        stockTakeSessionRepository.save(session);
        return buildResponse(session);
    }

    @Transactional
    public StockTakeSessionResponse getSession(
        String businessId,
        String sessionId
    ) {
        StockTakeSession session = stockTakeSessionRepository
            .findByIdAndBusinessIdFetchLines(sessionId, businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Session not found"
                )
            );
        return sessionResponse(session);
    }

    // ── Active / stale session lookup ──────────────────────────────────

    @Transactional
    public Optional<StockTakeSessionResponse> getActiveSession(
        String businessId,
        String branchId,
        LocalDate date,
        String sessionType
    ) {
        if (sessionType != null && !sessionType.isBlank()) {
            return stockTakeSessionRepository
                .findActiveByBusinessIdAndBranchIdAndDateAndTypeFetchLines(
                    businessId,
                    branchId,
                    date,
                    sessionType
                )
                .map(this::sessionResponse);
        }
        return stockTakeSessionRepository
            .findActiveListByBusinessIdAndBranchIdAndDateFetchLines(
                businessId,
                branchId,
                date
            )
            .stream()
            .findFirst()
            .map(this::sessionResponse);
    }

    @Transactional
    public Optional<StockTakeSessionResponse> getStaleSession(
        String businessId,
        String branchId,
        LocalDate today
    ) {
        // findStaleListByBusinessIdAndBranchIdFetchLines returns a List (ordered by date desc)
        // to avoid NonUniqueResultException; we take the most recent one.
        return stockTakeSessionRepository
            .findStaleListByBusinessIdAndBranchIdFetchLines(
                businessId,
                branchId,
                today
            )
            .stream()
            .findFirst()
            .map(this::sessionResponse);
    }

    @Transactional(readOnly = true)
    public List<StockTakeSessionResponse> listSessions(
        String businessId,
        String branchId,
        String status,
        LocalDate from,
        LocalDate to
    ) {
        String statusFilter = (status != null && !status.isBlank())
            ? status
            : null;
        String branchFilter = (branchId != null && !branchId.isBlank())
            ? branchId
            : null;
        return stockTakeSessionRepository
            .findFiltered(businessId, branchFilter, statusFilter, from, to)
            .stream()
            .map(this::buildResponse)
            .toList();
    }

    // ── Counting ───────────────────────────────────────────────────────

    @Transactional
    public StockTakeSessionResponse applyCounts(
        String businessId,
        String sessionId,
        PatchStockTakeCountsRequest req,
        String userId
    ) {
        StockTakeSession session = loadSessionInProgress(businessId, sessionId);
        Instant now = Instant.now();
        Map<String, StockTakeLine> byId = session
            .getLines()
            .stream()
            .collect(Collectors.toMap(StockTakeLine::getId, l -> l));
        for (PatchStockTakeCountsRequest.LineCounted lc : req.lines()) {
            StockTakeLine line = byId.get(lc.lineId());
            if (line == null) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown line id"
                );
            }
            if (
                InventoryConstants.STOCKTAKE_LINE_CONFIRMED.equals(
                    line.getStatus()
                )
            ) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Line has already been confirmed by admin and cannot be modified."
                );
            }
            line.setCountedQty(
                lc.countedQty().setScale(QTY_SCALE, RoundingMode.HALF_UP)
            );
            if (lc.aisle() != null) {
                line.setAisle(lc.aisle().trim());
            }
            line.setStatus(InventoryConstants.STOCKTAKE_LINE_SUBMITTED);
            line.setSubmittedBy(userId);
            line.setSubmittedAt(now);

            if (lc.batches() != null && !lc.batches().isEmpty()) {
                applyBatchCounts(line, lc.batches());
            }
        }
        stockTakeSessionRepository.save(session);
        return buildResponse(session);
    }

    // ── Single-line update (for per-item modal submit) ─────────────────

    @Transactional
    public StockTakeSessionResponse applySingleCount(
        String businessId,
        String sessionId,
        String lineId,
        BigDecimal countedQty,
        String aisle,
        List<PatchStockTakeCountsRequest.BatchCounted> batches,
        String userId
    ) {
        StockTakeSession session = loadSessionInProgress(businessId, sessionId);
        StockTakeLine line = session
            .getLines()
            .stream()
            .filter(l -> l.getId().equals(lineId))
            .findFirst()
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown line id"
                )
            );
        if (
            InventoryConstants.STOCKTAKE_LINE_CONFIRMED.equals(line.getStatus())
        ) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Line has already been confirmed by admin and cannot be modified."
            );
        }
        Instant now = Instant.now();
        line.setCountedQty(
            countedQty.setScale(QTY_SCALE, RoundingMode.HALF_UP)
        );
        if (aisle != null && !aisle.isBlank()) {
            line.setAisle(aisle.trim());
        }
        line.setStatus(InventoryConstants.STOCKTAKE_LINE_SUBMITTED);
        line.setSubmittedBy(userId);
        line.setSubmittedAt(now);

        if (batches != null && !batches.isEmpty()) {
            applyBatchCounts(line, batches);
        }

        stockTakeSessionRepository.save(session);
        return buildResponse(session);
    }

    private void applyBatchCounts(
        StockTakeLine line,
        List<PatchStockTakeCountsRequest.BatchCounted> batchCounts
    ) {
        Map<String, StockTakeLineBatch> byBatchId = line
            .getBatches()
            .stream()
            .collect(Collectors.toMap(StockTakeLineBatch::getBatchId, b -> b));
        BigDecimal batchSum = BigDecimal.ZERO;
        for (PatchStockTakeCountsRequest.BatchCounted bc : batchCounts) {
            StockTakeLineBatch slb = byBatchId.get(bc.batchId());
            if (slb == null) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Batch " + bc.batchId() + " does not belong to line " + line.getId()
                );
            }
            slb.setCountedQty(
                bc.countedQty().setScale(QTY_SCALE, RoundingMode.HALF_UP)
            );
            batchSum = batchSum.add(slb.getCountedQty());
        }
        if (batchSum.compareTo(line.getCountedQty()) != 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch counts sum to " +
                    batchSum +
                    " but line countedQty is " +
                    line.getCountedQty()
            );
        }
    }

    // ── Ad-hoc line (product not on checklist) ─────────────────────────

    @Transactional
    public StockTakeSessionResponse addAdHocLine(
        String businessId,
        String sessionId,
        String itemId,
        String aisle,
        String userId
    ) {
        StockTakeSession session = loadSessionInProgress(businessId, sessionId);
        // Don't add duplicates
        boolean exists = session
            .getLines()
            .stream()
            .anyMatch(l -> l.getItemId().equals(itemId));
        if (exists) {
            return buildResponse(session);
        }
        Map<String, BigDecimal> onHand = loadOnHandByItem(
            businessId,
            session.getBranchId()
        );
        BigDecimal sys = onHand
            .getOrDefault(itemId, BigDecimal.ZERO)
            .setScale(QTY_SCALE, RoundingMode.HALF_UP);
        int maxOrder = session
            .getLines()
            .stream()
            .mapToInt(StockTakeLine::getSortOrder)
            .max()
            .orElse(0);
        StockTakeLine line = new StockTakeLine();
        line.setSession(session);
        line.setItemId(itemId);
        line.setSystemQtySnapshot(sys);
        line.setStatus(InventoryConstants.STOCKTAKE_LINE_PENDING);
        line.setSortOrder(maxOrder + 1);
        if (aisle != null && !aisle.isBlank()) {
            line.setAisle(aisle.trim());
        } else {
            resolveAisleName(businessId, itemId).ifPresent(line::setAisle);
        }

        // Populate batch-level snapshots for ad-hoc line
        populateLineBatches(businessId, session.getBranchId(), itemId, line);

        session.getLines().add(line);
        stockTakeSessionRepository.save(session);
        return buildResponse(session);
    }

    // ── Create product + line atomically ───────────────────────────────

    @Transactional
    public StockTakeSessionResponse createItemAndAddLine(
        String businessId,
        String sessionId,
        CreateItemWithStocktakeLineRequest req,
        String userId
    ) {
        StockTakeSession session = loadSessionInProgress(businessId, sessionId);

        CreateItemRequest createReq = new CreateItemRequest(
            null,
            req.barcode(),
            req.name(),
            null,
            req.itemTypeId(),
            req.categoryId(),
            null,
            req.unitType(),
            false,
            req.isSellable() != null ? req.isSellable() : true,
            req.isStocked() != null ? req.isStocked() : true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            req.brand(),
            req.size()
        );

        var createResult = itemCatalogService.createItem(
            businessId,
            createReq,
            null
        );
        String itemId = createResult.body().id();

        boolean exists = session
            .getLines()
            .stream()
            .anyMatch(l -> l.getItemId().equals(itemId));
        if (!exists) {
            Map<String, BigDecimal> onHand = loadOnHandByItem(
                businessId,
                session.getBranchId()
            );
            BigDecimal sys = onHand
                .getOrDefault(itemId, BigDecimal.ZERO)
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);
            int maxOrder = session
                .getLines()
                .stream()
                .mapToInt(StockTakeLine::getSortOrder)
                .max()
                .orElse(0);
            StockTakeLine line = new StockTakeLine();
            line.setSession(session);
            line.setItemId(itemId);
            line.setSystemQtySnapshot(sys);
            line.setCountedQty(
                req.countedQty().setScale(QTY_SCALE, RoundingMode.HALF_UP)
            );
            line.setStatus(InventoryConstants.STOCKTAKE_LINE_SUBMITTED);
            line.setSortOrder(maxOrder + 1);
            if (req.aisle() != null && !req.aisle().isBlank()) {
                line.setAisle(req.aisle().trim());
            }
            line.setSubmittedBy(userId);
            line.setSubmittedAt(Instant.now());
            populateLineBatches(businessId, session.getBranchId(), itemId, line);
            session.getLines().add(line);
        }

        stockTakeSessionRepository.save(session);
        return buildResponse(session);
    }

    // ── Session resume ─────────────────────────────────────────────────

    @Transactional
    public StockTakeSessionResponse resumeSession(
        String businessId,
        String sessionId
    ) {
        StockTakeSession session = stockTakeSessionRepository
            .findByIdAndBusinessIdFetchLines(sessionId, businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Session not found"
                )
            );
        if (
            !InventoryConstants.STOCKTAKE_SESSION_IN_PROGRESS.equals(
                session.getStatus()
            )
        ) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Session is not in progress"
            );
        }
        return sessionResponse(session);
    }

    // ── Session close ──────────────────────────────────────────────────

    @Transactional
    public StockTakeSessionResponse closeSession(
        String businessId,
        String sessionId,
        String userId,
        boolean force
    ) {
        StockTakeSession session = loadSessionInProgress(businessId, sessionId);

        // Only require confirmation for items that have had counts submitted
        // (i.e., have countedQty or adminQuantity set, not just pending items)
        long unconfirmedWithCounts = session
            .getLines()
            .stream()
            .filter(l -> {
                // Item has a count submitted if it has countedQty or adminQuantity
                boolean hasCount = (l.getCountedQty() != null ||
                    l.getAdminQuantity() != null);
                boolean isConfirmed =
                    InventoryConstants.STOCKTAKE_LINE_CONFIRMED.equals(
                        l.getStatus()
                    );
                return hasCount && !isConfirmed;
            })
            .count();
        if (unconfirmedWithCounts > 0 && !force) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                unconfirmedWithCounts +
                    " items with counts entered are still unconfirmed. Pass force=true to close anyway."
            );
        }

        session.setStatus(InventoryConstants.STOCKTAKE_SESSION_CLOSED);
        session.setClosedAt(Instant.now());
        session.setClosedBy(userId);
        stockTakeSessionRepository.save(session);
        return buildResponse(session);
    }

    // ── Delete session (admin only) ────────────────────────────────────

    @Transactional
    public void deleteSession(
        String businessId,
        String sessionId,
        String userId
    ) {
        StockTakeSession session = stockTakeSessionRepository
            .findByIdAndBusinessIdFetchLines(sessionId, businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Session not found"
                )
            );
        stockTakeSessionRepository.delete(session);
    }

    // ── Line confirmation (admin confirms a single line) ───────────────

    @Transactional
    public StockTakeSessionResponse confirmLine(
        String businessId,
        String sessionId,
        String lineId,
        BigDecimal adminQuantity,
        String userId
    ) {
        StockTakeSession session = loadSessionInProgress(businessId, sessionId);
        StockTakeLine line = session
            .getLines()
            .stream()
            .filter(l -> l.getId().equals(lineId))
            .findFirst()
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown line id"
                )
            );
        if (
            InventoryConstants.STOCKTAKE_LINE_CONFIRMED.equals(line.getStatus())
        ) {
            return buildResponse(session);
        }
        if (line.getCountedQty() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Line has no counted quantity"
            );
        }
        Instant now = Instant.now();
        line.setAdminQuantity(
            adminQuantity != null
                ? adminQuantity.setScale(QTY_SCALE, RoundingMode.HALF_UP)
                : line.getCountedQty()
        );
        line.setStatus(InventoryConstants.STOCKTAKE_LINE_CONFIRMED);
        line.setConfirmedBy(userId);
        line.setConfirmedAt(now);

        // Immediately apply inventory update if there's a variance
        BigDecimal confirmedQty = line.getAdminQuantity();
        BigDecimal systemSnap =
            line.getSystemQtySnapshot() != null
                ? line.getSystemQtySnapshot()
                : BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        BigDecimal variance = confirmedQty.subtract(systemSnap);
        if (variance.signum() != 0) {
            StockAdjustmentRequest r = persistAdjustmentRequest(
                businessId,
                session,
                line,
                variance,
                userId
            );
            // Auto-approve the adjustment since admin confirmed it
            r.setStatus(InventoryConstants.ADJUSTMENT_REQUEST_APPROVED);
            r.setDecidedBy(userId);
            r.setDecidedAt(now);

            boolean hasBatchCounts = line
                .getBatches()
                .stream()
                .anyMatch(b -> b.getCountedQty() != null);
            if (hasBatchCounts) {
                applyBatchVarianceToStock(
                    businessId,
                    session.getBranchId(),
                    line,
                    userId
                );
            } else {
                applyVarianceToStock(businessId, r, null, userId);
            }
            stockAdjustmentRequestRepository.save(r);
        }

        stockTakeSessionRepository.save(session);
        return buildResponse(session);
    }

    // ── Reconciliation ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReconciliationResponse getReconciliation(
        String businessId,
        String morningSessionId,
        String eveningSessionId
    ) {
        StockTakeSession morning = stockTakeSessionRepository
            .findByIdAndBusinessIdFetchLines(morningSessionId, businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Morning session not found"
                )
            );
        StockTakeSession evening = stockTakeSessionRepository
            .findByIdAndBusinessIdFetchLines(eveningSessionId, businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Evening session not found"
                )
            );

        // Use the morning session date for sales lookup (both sessions are same day)
        Map<String, BigDecimal> soldByItem =
            salesIntelligenceService.quantitySoldByItem(
                businessId,
                morning.getSessionDate(),
                morning.getSessionDate()
            );

        return buildReconciliationResponse(
            businessId,
            morningSessionId,
            eveningSessionId,
            morning,
            evening,
            soldByItem
        );
    }

    /**
     * Auto-detects morning and evening sessions for a branch + date and returns
     * the reconciliation report, so users don't have to manually enter session IDs.
     */
    @Transactional(readOnly = true)
    public ReconciliationResponse getReconciliationByBranchAndDate(
        String businessId,
        String branchId,
        LocalDate date
    ) {
        requireBranch(businessId, branchId);
        List<StockTakeSession> morningSessions =
            stockTakeSessionRepository.findAllByTypeAndBranchAndDateFetchLines(
                businessId,
                branchId,
                InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING,
                date
            );
        if (morningSessions.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No morning session found for branch on " + date
            );
        }
        List<StockTakeSession> eveningSessions =
            stockTakeSessionRepository.findAllByTypeAndBranchAndDateFetchLines(
                businessId,
                branchId,
                InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING,
                date
            );
        if (eveningSessions.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No evening session found for branch on " + date
            );
        }
        // Use the most recent sessions for reconciliation
        StockTakeSession morning = morningSessions.get(0);
        StockTakeSession evening = eveningSessions.get(0);

        Map<String, BigDecimal> soldByItem =
            salesIntelligenceService.quantitySoldByItem(businessId, date, date);

        return buildReconciliationResponse(
            businessId,
            morning.getId(),
            evening.getId(),
            morning,
            evening,
            soldByItem
        );
    }

    private ReconciliationResponse buildReconciliationResponse(
        String businessId,
        String morningSessionId,
        String eveningSessionId,
        StockTakeSession morning,
        StockTakeSession evening,
        Map<String, BigDecimal> soldByItem
    ) {
        Map<String, BigDecimal> morningQtys = new HashMap<>();
        for (StockTakeLine line : morning.getLines()) {
            if (
                InventoryConstants.STOCKTAKE_LINE_CONFIRMED.equals(
                    line.getStatus()
                )
            ) {
                BigDecimal qty =
                    line.getAdminQuantity() != null
                        ? line.getAdminQuantity()
                        : line.getCountedQty();
                if (qty != null) {
                    morningQtys.put(line.getItemId(), qty);
                }
            }
        }

        Map<String, BigDecimal> eveningQtys = new HashMap<>();
        for (StockTakeLine line : evening.getLines()) {
            if (
                InventoryConstants.STOCKTAKE_LINE_CONFIRMED.equals(
                    line.getStatus()
                )
            ) {
                BigDecimal qty =
                    line.getAdminQuantity() != null
                        ? line.getAdminQuantity()
                        : line.getCountedQty();
                if (qty != null) {
                    eveningQtys.put(line.getItemId(), qty);
                }
            }
        }

        int morningConfirmedCount = morningQtys.size();
        int eveningConfirmedCount = eveningQtys.size();

        Set<String> allIds = new HashSet<>(morningQtys.keySet());
        allIds.addAll(eveningQtys.keySet());

        List<ReconciliationLine> lines = new ArrayList<>();
        int zeroVariance = 0;
        int withVariance = 0;
        int missingInMorningCount = 0;
        int missingInEveningCount = 0;

        for (String itemId : allIds) {
            BigDecimal opening = morningQtys.get(itemId);
            BigDecimal actual = eveningQtys.get(itemId);
            String missingIn = null;

            if (opening == null) {
                missingIn = "morning";
                missingInMorningCount++;
                opening = BigDecimal.ZERO;
            } else if (actual == null) {
                missingIn = "evening";
                missingInEveningCount++;
                actual = BigDecimal.ZERO;
            }

            BigDecimal sold = soldByItem.getOrDefault(itemId, BigDecimal.ZERO);
            BigDecimal expected = opening.subtract(sold);
            BigDecimal variance = actual.subtract(expected);

            if (missingIn == null) {
                if (variance.signum() == 0) {
                    zeroVariance++;
                } else {
                    withVariance++;
                }
            }

            Item item = itemRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElse(null);
            lines.add(
                new ReconciliationLine(
                    itemId,
                    item != null ? item.getName() : itemId,
                    item != null ? item.getSku() : "",
                    opening,
                    sold,
                    expected,
                    actual,
                    variance,
                    missingIn
                )
            );
        }

        String morningName = computeSessionName(
            morning.getSessionType(),
            morning.getSessionDate()
        );
        String eveningName = computeSessionName(
            evening.getSessionType(),
            evening.getSessionDate()
        );

        return new ReconciliationResponse(
            morningSessionId,
            eveningSessionId,
            morningName,
            eveningName,
            lines.size(),
            zeroVariance,
            withVariance,
            morningConfirmedCount,
            eveningConfirmedCount,
            missingInMorningCount,
            missingInEveningCount,
            lines
        );
    }

    // ── Adjustment request approval / rejection ────────────────────────

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
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Request not found"
                )
            );
        if (
            !request.getStockTakeLine().getSession().getId().equals(sessionId)
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Request does not belong to session"
            );
        }
        if (
            !InventoryConstants.ADJUSTMENT_REQUEST_PENDING.equals(
                request.getStatus()
            )
        ) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Request is not pending"
            );
        }
        applyVarianceToStock(businessId, request, override, userId);
        request.setStatus(InventoryConstants.ADJUSTMENT_REQUEST_APPROVED);
        request.setDecidedBy(userId);
        request.setDecidedAt(Instant.now());
        stockAdjustmentRequestRepository.save(request);
        eventPublisher.publishEvent(
            new zelisline.ub.platform.realtime.RealtimeBridge.ApprovalResolvedEvent(
                businessId,
                request.getBranchId(),
                requestId,
                "approved",
                userId,
                request.getRequestedBy()
            )
        );
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
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Request not found"
                )
            );
        if (
            !request.getStockTakeLine().getSession().getId().equals(sessionId)
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Request does not belong to session"
            );
        }
        if (
            !InventoryConstants.ADJUSTMENT_REQUEST_PENDING.equals(
                request.getStatus()
            )
        ) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Request is not pending"
            );
        }
        request.setStatus(InventoryConstants.ADJUSTMENT_REQUEST_REJECTED);
        request.setNotes(notes);
        request.setDecidedBy(userId);
        request.setDecidedAt(Instant.now());
        stockAdjustmentRequestRepository.save(request);
        eventPublisher.publishEvent(
            new zelisline.ub.platform.realtime.RealtimeBridge.ApprovalResolvedEvent(
                businessId,
                request.getBranchId(),
                requestId,
                "rejected",
                userId,
                request.getRequestedBy()
            )
        );
    }

    // ── Private helpers ────────────────────────────────────────────────

    private StockAdjustmentRequest persistAdjustmentRequest(
        String businessId,
        StockTakeSession session,
        StockTakeLine line,
        BigDecimal variance,
        String userId
    ) {
        BigDecimal countedQty =
            line.getAdminQuantity() != null
                ? line.getAdminQuantity()
                : line.getCountedQty();
        StockAdjustmentRequest r = new StockAdjustmentRequest();
        r.setBusinessId(businessId);
        r.setBranchId(session.getBranchId());
        r.setStockTakeLine(line);
        r.setItemId(line.getItemId());
        r.setAdjustmentType(InventoryConstants.ADJUSTMENT_TYPE_STOCK_TAKE);
        r.setVarianceQty(variance.setScale(QTY_SCALE, RoundingMode.HALF_UP));
        BigDecimal systemSnap =
            line.getSystemQtySnapshot() != null
                ? line.getSystemQtySnapshot()
                : BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        r.setSystemQtySnapshot(systemSnap);
        r.setCountedQty(countedQty);
        r.setReason(InventoryConstants.REASON_COUNTING_ERROR);
        r.setStatus(InventoryConstants.ADJUSTMENT_REQUEST_PENDING);
        r.setRequestedBy(userId);
        stockAdjustmentRequestRepository.save(r);
        eventPublisher.publishEvent(
            new zelisline.ub.platform.realtime.RealtimeBridge.ApprovalRequestedEvent(
                businessId,
                r.getBranchId(),
                r.getId(),
                "stock_adjustment",
                r.getRequestedBy(),
                r.getItemId(),
                itemName(businessId, r.getItemId()),
                r.getVarianceQty().abs(),
                r.getReason()
            )
        );
        return r;
    }

    private void applyVarianceToStock(
        String businessId,
        StockAdjustmentRequest request,
        BigDecimal unitCostOverride,
        String userId
    ) {
        BigDecimal variance = request.getVarianceQty();
        if (variance.signum() > 0) {
            BigDecimal unit = resolveInboundUnitCostSafe(
                businessId,
                request.getBranchId(),
                request.getItemId(),
                unitCostOverride
            );
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

    private void applyBatchVarianceToStock(
        String businessId,
        String branchId,
        StockTakeLine line,
        String userId
    ) {
        BigDecimal adminQty = line.getAdminQuantity();
        BigDecimal countedQty = line.getCountedQty();
        BigDecimal scale = BigDecimal.ONE;
        if (adminQty != null && countedQty != null && countedQty.signum() > 0) {
            scale = adminQty.divide(countedQty, QTY_SCALE, RoundingMode.HALF_UP);
        }

        for (StockTakeLineBatch slb : line.getBatches()) {
            if (slb.getCountedQty() == null) {
                continue;
            }
            BigDecimal effectiveCounted = slb
                .getCountedQty()
                .multiply(scale)
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);
            BigDecimal batchVariance = effectiveCounted.subtract(slb.getSystemQtySnapshot());
            if (batchVariance.signum() == 0) {
                continue;
            }
            if (batchVariance.signum() > 0) {
                inventoryLedgerService.recordBatchIncrease(
                    businessId,
                    new PostBatchIncreaseRequest(
                        slb.getBatchId(),
                        batchVariance,
                        "Stock-take surplus approved"
                    ),
                    userId
                );
            } else {
                inventoryLedgerService.recordBatchDecrease(
                    businessId,
                    new PostBatchDecreaseRequest(
                        slb.getBatchId(),
                        batchVariance.negate(),
                        "Stock-take deficit approved"
                    ),
                    userId
                );
            }
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
        return weightedAverageUnitCostAtBranch(
            businessId,
            itemId,
            branchId
        ).orElseThrow(() ->
            new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "unitCost is required when there is no on-hand batch to average"
            )
        );
    }

    /**
     * Safe variant — falls back to zero cost when no on-hand batches exist.
     * Used by confirmLine where admin confirms without specifying unit cost.
     */
    private BigDecimal resolveInboundUnitCostSafe(
        String businessId,
        String branchId,
        String itemId,
        BigDecimal override
    ) {
        if (override != null && override.signum() > 0) {
            return override.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        }
        return weightedAverageUnitCostAtBranch(
            businessId,
            itemId,
            branchId
        ).orElse(BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP));
    }

    private Optional<BigDecimal> weightedAverageUnitCostAtBranch(
        String businessId,
        String itemId,
        String branchId
    ) {
        List<InventoryBatch> batches =
            inventoryBatchRepository.findActiveBatchesForPreview(
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
        return Optional.of(
            sumVal.divide(sumQty, QTY_SCALE, RoundingMode.HALF_UP)
        );
    }

    private StockTakeSession loadSessionInProgress(
        String businessId,
        String sessionId
    ) {
        StockTakeSession session = stockTakeSessionRepository
            .findByIdAndBusinessIdFetchLines(sessionId, businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Session not found"
                )
            );
        if (
            !InventoryConstants.STOCKTAKE_SESSION_IN_PROGRESS.equals(
                session.getStatus()
            )
        ) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Session is not in progress"
            );
        }
        return session;
    }

    /**
     * Drops stock-take lines whose catalog item was deleted after the session started.
     */
    private void pruneOrphanLines(StockTakeSession session) {
        if (session.getLines() == null || session.getLines().isEmpty()) {
            return;
        }
        String businessId = session.getBusinessId();
        List<String> lineItemIds = session
            .getLines()
            .stream()
            .map(StockTakeLine::getItemId)
            .distinct()
            .toList();
        Set<String> activeItemIds = itemRepository
            .findByIdInAndBusinessIdAndDeletedAtIsNull(lineItemIds, businessId)
            .stream()
            .map(Item::getId)
            .collect(Collectors.toCollection(HashSet::new));
        boolean removed = session
            .getLines()
            .removeIf(l -> !activeItemIds.contains(l.getItemId()));
        if (removed) {
            stockTakeSessionRepository.save(session);
        }
    }

    private StockTakeSessionResponse sessionResponse(StockTakeSession session) {
        pruneOrphanLines(session);
        return buildResponse(session);
    }

    private List<String> filterActiveItemIds(String businessId, List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        return itemRepository
            .findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)
            .stream()
            .map(Item::getId)
            .toList();
    }

    private StockTakeSessionResponse buildResponse(StockTakeSession session) {
        String name = computeSessionName(
            session.getSessionType(),
            session.getSessionDate()
        );

        // Batch-load item names and SKUs in a single query so the frontend
        // never has to do a secondary lookup or page-guess.
        List<String> lineItemIds = session
            .getLines()
            .stream()
            .map(StockTakeLine::getItemId)
            .distinct()
            .toList();
        Map<String, Item> itemsById = lineItemIds.isEmpty()
            ? Map.of()
            : itemRepository
                  .findByIdInAndBusinessIdAndDeletedAtIsNull(
                      lineItemIds,
                      session.getBusinessId()
                  )
                  .stream()
                  .collect(Collectors.toMap(Item::getId, i -> i));

        List<StockTakeLineResponse> lineDtos = session
            .getLines()
            .stream()
            .map(l -> {
                Item item = itemsById.get(l.getItemId());
                List<StockTakeLineBatchResponse> batchDtos = l
                    .getBatches()
                    .stream()
                    .map(b ->
                        new StockTakeLineBatchResponse(
                            b.getId(),
                            b.getBatchId(),
                            b.getBatchNumber(),
                            b.getExpiryDate(),
                            b.getSystemQtySnapshot(),
                            b.getCountedQty(),
                            b.getSortOrder()
                        )
                    )
                    .toList();
                return new StockTakeLineResponse(
                    l.getId(),
                    l.getItemId(),
                    item != null ? item.getName() : l.getItemId(),
                    item != null ? item.getSku() : null,
                    l.getSystemQtySnapshot(),
                    l.getCountedQty(),
                    l.getAdminQuantity(),
                    l.getNote(),
                    l.getAisle(),
                    l.getStatus(),
                    l.getSubmittedBy(),
                    l.getSubmittedAt(),
                    l.getConfirmedBy(),
                    l.getConfirmedAt(),
                    l.getUpdatedAt(),
                    batchDtos
                );
            })
            .toList();

        List<StockAdjustmentRequest> requests =
            stockAdjustmentRequestRepository.findByStockTakeLine_Session_IdOrderByCreatedAtAsc(
                session.getId()
            );
        List<StockAdjustmentRequestResponse> reqDtos = requests
            .stream()
            .map(r ->
                new StockAdjustmentRequestResponse(
                    r.getId(),
                    r.getStockTakeLine().getId(),
                    r.getItemId(),
                    r.getVarianceQty(),
                    r.getSystemQtySnapshot(),
                    r.getCountedQty(),
                    r.getStatus()
                )
            )
            .toList();

        int totalCount = lineDtos.size();
        int pendingCount = 0;
        int submittedCount = 0;
        int confirmedCount = 0;
        for (StockTakeLineResponse l : lineDtos) {
            switch (l.status()) {
                case InventoryConstants.STOCKTAKE_LINE_PENDING -> pendingCount++;
                case InventoryConstants.STOCKTAKE_LINE_SUBMITTED -> submittedCount++;
                case InventoryConstants.STOCKTAKE_LINE_CONFIRMED -> confirmedCount++;
            }
        }
        int remainingCount = totalCount - confirmedCount;

        return new StockTakeSessionResponse(
            session.getId(),
            session.getSessionNumber(),
            session.getBranchId(),
            session.getStatus(),
            session.getSessionType(),
            session.getSessionDate(),
            name,
            session.getNotes(),
            session.getStartedBy(),
            session.getClosedAt(),
            session.getClosedBy(),
            lineDtos,
            reqDtos,
            new StockTakeSessionResponse.Summary(
                totalCount,
                pendingCount,
                submittedCount,
                confirmedCount,
                remainingCount
            )
        );
    }

    private void populateLineBatches(
        String businessId,
        String branchId,
        String itemId,
        StockTakeLine line
    ) {
        List<InventoryBatch> batches = inventoryBatchRepository
            .findActiveBatchesForItems(
                businessId,
                branchId,
                InventoryConstants.BATCH_STATUS_ACTIVE,
                List.of(itemId),
                BigDecimal.ZERO
            );
        int batchOrder = 0;
        for (InventoryBatch ib : batches) {
            StockTakeLineBatch slb = new StockTakeLineBatch();
            slb.setLine(line);
            slb.setBatchId(ib.getId());
            slb.setBatchNumber(ib.getBatchNumber());
            slb.setExpiryDate(ib.getExpiryDate());
            slb.setSystemQtySnapshot(
                ib.getQuantityRemaining()
                    .setScale(QTY_SCALE, RoundingMode.HALF_UP)
            );
            slb.setSortOrder(batchOrder++);
            line.getBatches().add(slb);
        }
    }

    private Map<String, BigDecimal> loadOnHandByItem(
        String businessId,
        String branchId
    ) {
        List<Object[]> rows =
            inventoryBatchRepository.sumQuantityRemainingByItemAtBranch(
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

    private Optional<String> resolveAisleName(
        String businessId,
        String itemId
    ) {
        return itemRepository
            .findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
            .map(Item::getAisleId)
            .filter(id -> id != null && !id.isBlank())
            .flatMap(aisleId ->
                aisleRepository.findByIdAndBusinessId(aisleId, businessId)
            )
            .map(Aisle::getName);
    }

    private static String computeSessionName(
        String sessionType,
        LocalDate sessionDate
    ) {
        if (sessionType == null || sessionDate == null) {
            return "";
        }
        String capitalized =
            sessionType.substring(0, 1).toUpperCase() +
            sessionType.substring(1);
        return capitalized + " — " + sessionDate.format(NAME_DATE_FORMATTER);
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
        branchRepository
            .findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Branch not found"
                )
            );
    }

    private void requireValidSessionType(String sessionType) {
        if (
            !InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING.equals(
                sessionType
            ) &&
            !InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING.equals(
                sessionType
            )
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "sessionType must be 'morning' or 'evening'"
            );
        }
    }

    private String itemName(String businessId, String itemId) {
        return itemRepository
            .findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
            .map(Item::getName)
            .orElse(itemId);
    }

    /**
     * Returns a copy of the response with {@code systemQtySnapshot} removed from
     * every line. Used for stock counters who should not see expected quantities
     * while performing the physical count.
     */
    public StockTakeSessionResponse maskSystemQty(
        StockTakeSessionResponse response
    ) {
        if (response == null || response.lines() == null) {
            return response;
        }
        List<StockTakeLineResponse> masked = response
            .lines()
            .stream()
            .map(l ->
                new StockTakeLineResponse(
                    l.id(),
                    l.itemId(),
                    l.itemName(),
                    l.itemSku(),
                    null,
                    l.countedQty(),
                    l.adminQuantity(),
                    l.note(),
                    l.aisle(),
                    l.status(),
                    l.submittedBy(),
                    l.submittedAt(),
                    l.confirmedBy(),
                    l.confirmedAt(),
                    l.updatedAt(),
                    l.batches()
                )
            )
            .toList();
        return new StockTakeSessionResponse(
            response.id(),
            response.sessionNumber(),
            response.branchId(),
            response.status(),
            response.sessionType(),
            response.sessionDate(),
            response.name(),
            response.notes(),
            response.startedBy(),
            response.closedAt(),
            response.closedBy(),
            masked,
            response.adjustmentRequests(),
            response.summary()
        );
    }
}
