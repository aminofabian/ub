package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.BatchResponse;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.CountCycleHomeResponse;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.CountCycleResponse;
import zelisline.ub.inventory.domain.StockCountCycle;
import zelisline.ub.inventory.domain.StockCountCycleItem;
import zelisline.ub.inventory.domain.StockTakeLine;
import zelisline.ub.inventory.domain.StockTakeSession;
import zelisline.ub.inventory.repository.StockCountCycleRepository;
import zelisline.ub.inventory.repository.StockTakeSessionRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Full-count cycles: the whole stocked catalog counted in daily batches
 * (e.g. 100 products a day) until every product has a fresh count.
 */
@Service
@RequiredArgsConstructor
public class StockCountCycleService {

    private static final String FALLBACK_ZONE = "Africa/Nairobi";

    private final StockCountCycleRepository cycleRepository;
    private final StockTakeSessionRepository sessionRepository;
    private final StockTakeService stockTakeService;
    private final StockCountCycleViews views;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;

    @Transactional(readOnly = true)
    public CountCycleHomeResponse home(String businessId, String branchId) {
        requireBranch(businessId, branchId);
        CountCycleResponse cycle = cycleRepository.findLatestForBranch(businessId, branchId)
                .stream()
                .findFirst()
                .map(c -> cycleResponse(businessId, c))
                .orElse(null);
        boolean running = cycle != null && InventoryConstants.COUNT_CYCLE_ACTIVE.equals(cycle.status());
        return new CountCycleHomeResponse(
                cycle,
                running ? cycle.totalItems() : countablePool(businessId).size(),
                InventoryConstants.COUNT_CYCLE_DEFAULT_BATCH_SIZE
        );
    }

    @Transactional
    public CountCycleResponse startCycle(
            String businessId,
            String branchId,
            Integer batchSize,
            String userId
    ) {
        requireBranch(businessId, branchId);
        if (findActiveCycle(businessId, branchId) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A full count is already running at this branch."
            );
        }
        List<String> ordered = countablePool(businessId);
        if (ordered.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "There are no stocked products to count."
            );
        }
        StockCountCycle cycle = new StockCountCycle();
        cycle.setId(UUID.randomUUID().toString());
        cycle.setBusinessId(businessId);
        cycle.setBranchId(branchId);
        cycle.setStatus(InventoryConstants.COUNT_CYCLE_ACTIVE);
        cycle.setBatchSize(CountCyclePlanner.clampBatchSize(batchSize));
        cycle.setTotalItems(ordered.size());
        cycle.setStartedBy(userId);
        for (int i = 0; i < ordered.size(); i++) {
            cycle.addItem(ordered.get(i), i);
        }
        return cycleResponse(businessId, cycleRepository.save(cycle));
    }

    @Transactional
    public BatchResponse startOrResumeBatch(
            String businessId,
            String cycleId,
            String userId,
            boolean showSystemStock
    ) {
        StockCountCycle cycle = requireActiveCycle(businessId, cycleId);
        if (cycle.getActiveSessionId() != null) {
            return batchResponse(businessId, cycle.getActiveSessionId(), showSystemStock);
        }
        List<StockCountCycleItem> next =
                CountCyclePlanner.nextBatch(cycle.getItems(), cycle.getBatchSize());
        if (next.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Every product in this full count has been counted."
            );
        }
        StockTakeSession session = stockTakeService.startCountCycleBatchSession(
                businessId,
                cycle.getBranchId(),
                today(businessId),
                cycle.getId(),
                cycle.getBatchesStarted() + 1,
                next.stream().map(StockCountCycleItem::getItemId).toList(),
                userId
        );
        next.forEach(item -> item.setSessionId(session.getId()));
        cycle.setBatchesStarted(cycle.getBatchesStarted() + 1);
        cycle.setActiveSessionId(session.getId());
        cycleRepository.save(cycle);
        return views.batch(session, cycle.getBatchesStarted(), showSystemStock);
    }

    @Transactional(readOnly = true)
    public BatchResponse getBatch(String businessId, String sessionId, boolean showSystemStock) {
        return batchResponse(businessId, sessionId, showSystemStock);
    }

    @Transactional
    public BatchResponse countLine(
            String businessId,
            String sessionId,
            String lineId,
            BigDecimal countedQty,
            String userId,
            boolean showSystemStock
    ) {
        requireBatchSession(businessId, sessionId);
        stockTakeService.applySingleCountWithNote(
                businessId, sessionId, lineId, countedQty, null, null, null, userId
        );
        return batchResponse(businessId, sessionId, showSystemStock);
    }

    /**
     * Closes the batch. Counted products are done for this cycle; anything
     * left uncounted goes back to the front of the queue for the next batch.
     */
    @Transactional
    public CountCycleResponse finishBatch(String businessId, String sessionId, String userId) {
        StockTakeSession session = requireBatchSession(businessId, sessionId);
        requireInProgress(session);
        Set<String> countedItemIds = session.getLines().stream()
                .filter(StockCountCycleViews::isCounted)
                .map(StockTakeLine::getItemId)
                .collect(Collectors.toSet());
        if (countedItemIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Count at least one product before finishing the batch."
            );
        }
        StockCountCycle cycle = requireCycle(businessId, session.getCountCycleId());
        markBatchOutcome(cycle, sessionId, countedItemIds);
        closeSession(session, userId);
        cycle.setActiveSessionId(null);
        if (cycle.getCountedItems() >= cycle.getTotalItems()) {
            cycle.setStatus(InventoryConstants.COUNT_CYCLE_COMPLETED);
            cycle.setCompletedAt(Instant.now());
        }
        return cycleResponse(businessId, cycleRepository.save(cycle));
    }

    /** Writes counted quantities to stock (approver step). */
    @Transactional
    public BatchResponse applyBatch(
            String businessId,
            String sessionId,
            List<String> lineIds,
            String userId
    ) {
        StockTakeSession session = requireBatchSession(businessId, sessionId);
        if (!InventoryConstants.STOCKTAKE_SESSION_CLOSED.equals(session.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Finish the batch before applying counts to stock."
            );
        }
        Set<String> wanted = lineIds == null || lineIds.isEmpty() ? null : new HashSet<>(lineIds);
        for (StockTakeLine line : session.getLines()) {
            boolean selected = wanted == null || wanted.contains(line.getId());
            if (selected && isAwaitingApply(line)) {
                stockTakeService.applyApprovedCount(businessId, line, userId);
            }
        }
        sessionRepository.save(session);
        return batchResponse(businessId, sessionId, true);
    }

    @Transactional
    public CountCycleResponse cancelCycle(String businessId, String cycleId, String userId) {
        StockCountCycle cycle = requireActiveCycle(businessId, cycleId);
        if (cycle.getActiveSessionId() != null) {
            sessionRepository.findByIdAndBusinessIdFetchLines(cycle.getActiveSessionId(), businessId)
                    .filter(s -> InventoryConstants.STOCKTAKE_SESSION_IN_PROGRESS.equals(s.getStatus()))
                    .ifPresent(s -> closeSession(s, userId));
            cycle.setActiveSessionId(null);
        }
        cycle.setStatus(InventoryConstants.COUNT_CYCLE_CANCELLED);
        cycle.setCancelledAt(Instant.now());
        return cycleResponse(businessId, cycleRepository.save(cycle));
    }

    /** Batch number is the session's position among the cycle's batches. */
    private int batchNumber(String businessId, StockTakeSession session) {
        List<StockTakeSession> sessions =
                sessionRepository.findByCountCycleIdFetchLines(businessId, session.getCountCycleId());
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).getId().equals(session.getId())) {
                return i + 1;
            }
        }
        return sessions.size();
    }

    private static void markBatchOutcome(
            StockCountCycle cycle,
            String sessionId,
            Set<String> countedItemIds
    ) {
        Instant now = Instant.now();
        int newlyCounted = 0;
        for (StockCountCycleItem item : cycle.getItems()) {
            if (!sessionId.equals(item.getSessionId()) || item.isCounted()) {
                continue;
            }
            if (countedItemIds.contains(item.getItemId())) {
                item.setCountedAt(now);
                newlyCounted++;
            } else {
                item.returnToPool();
            }
        }
        cycle.setCountedItems(cycle.getCountedItems() + newlyCounted);
    }

    private void closeSession(StockTakeSession session, String userId) {
        session.setStatus(InventoryConstants.STOCKTAKE_SESSION_CLOSED);
        session.setClosedAt(Instant.now());
        session.setClosedBy(userId);
        sessionRepository.save(session);
    }

    private static boolean isAwaitingApply(StockTakeLine line) {
        return StockCountCycleViews.isCounted(line)
                && InventoryConstants.STOCKTAKE_LINE_SUBMITTED.equals(line.getStatus());
    }

    /** Stocked, active products — skipping family parents whose variants are counted instead. */
    private List<String> countablePool(String businessId) {
        List<String> stockedIds = itemRepository.findStockedItemIdsByBusinessId(businessId);
        if (stockedIds.isEmpty()) {
            return List.of();
        }
        Set<String> parentsWithVariants =
                new HashSet<>(itemRepository.findParentIdsHavingVariants(businessId, stockedIds));
        List<CountCyclePlanner.CatalogEntry> entries = itemRepository
                .findByIdInAndBusinessIdAndDeletedAtIsNull(stockedIds, businessId)
                .stream()
                .filter(Item::isActive)
                .filter(i -> !parentsWithVariants.contains(i.getId()))
                .map(i -> new CountCyclePlanner.CatalogEntry(
                        i.getId(), i.getCategoryId(), CountItemCatalog.displayName(i, i.getId())))
                .toList();
        Map<String, Integer> positions = categoryRepository
                .findByBusinessIdOrderByPositionAsc(businessId)
                .stream()
                .collect(Collectors.toMap(Category::getId, Category::getPosition, (a, b) -> a));
        return CountCyclePlanner.shelfWalkOrder(entries, positions);
    }

    private CountCycleResponse cycleResponse(String businessId, StockCountCycle cycle) {
        ZoneId zone = zone(businessId);
        return views.cycle(
                cycle,
                sessionRepository.findByCountCycleIdFetchLines(businessId, cycle.getId()),
                LocalDate.now(zone),
                zone
        );
    }

    private BatchResponse batchResponse(String businessId, String sessionId, boolean showSystemStock) {
        StockTakeSession session = requireBatchSession(businessId, sessionId);
        return views.batch(session, batchNumber(businessId, session), showSystemStock);
    }

    private StockCountCycle findActiveCycle(String businessId, String branchId) {
        return cycleRepository.findLatestForBranch(businessId, branchId).stream()
                .filter(c -> InventoryConstants.COUNT_CYCLE_ACTIVE.equals(c.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private StockCountCycle requireCycle(String businessId, String cycleId) {
        return cycleRepository.findByIdAndBusinessIdFetchItems(cycleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Full count not found"));
    }

    private StockCountCycle requireActiveCycle(String businessId, String cycleId) {
        StockCountCycle cycle = requireCycle(businessId, cycleId);
        if (!InventoryConstants.COUNT_CYCLE_ACTIVE.equals(cycle.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This full count is no longer running.");
        }
        return cycle;
    }

    private StockTakeSession requireBatchSession(String businessId, String sessionId) {
        StockTakeSession session = sessionRepository
                .findByIdAndBusinessIdFetchLines(sessionId, businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Batch not found"));
        if (!InventoryConstants.STOCKTAKE_SOURCE_COUNT_CYCLE.equals(session.getSource())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Not a full-count batch");
        }
        return session;
    }

    private static void requireInProgress(StockTakeSession session) {
        if (!InventoryConstants.STOCKTAKE_SESSION_IN_PROGRESS.equals(session.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This batch is already finished.");
        }
    }

    private void requireBranch(String businessId, String branchId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private LocalDate today(String businessId) {
        return LocalDate.now(zone(businessId));
    }

    private ZoneId zone(String businessId) {
        String tz = businessRepository.findById(businessId)
                .map(Business::getTimezone)
                .filter(t -> !t.isBlank())
                .orElse(FALLBACK_ZONE);
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException ex) {
            return ZoneId.of(FALLBACK_ZONE);
        }
    }
}
