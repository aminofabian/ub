package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.DailyStockAuditDtos;
import zelisline.ub.inventory.api.dto.StockTakeSessionResponse;
import zelisline.ub.inventory.domain.DailyStockAudit;
import zelisline.ub.inventory.domain.StockTakeLine;
import zelisline.ub.inventory.domain.StockTakeSession;
import zelisline.ub.inventory.repository.DailyStockAuditRepository;
import zelisline.ub.inventory.repository.StockTakeLineRepository;
import zelisline.ub.inventory.repository.StockTakeSessionRepository;
import zelisline.ub.sales.application.SalesIntelligenceService;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.tenancy.application.BusinessInventorySettingsService;
import zelisline.ub.tenancy.api.dto.StocktakeSettingsResponse;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.*;

@Service
@RequiredArgsConstructor
public class DailyStockAuditService {

    private static final int QTY_SCALE = 4;

    private final DailyStockAuditRepository dailyStockAuditRepository;
    private final StockTakeSessionRepository stockTakeSessionRepository;
    private final StockTakeLineRepository stockTakeLineRepository;
    private final StockTakeService stockTakeService;
    private final SaleItemRepository saleItemRepository;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final ItemCatalogService itemCatalogService;
    private final SalesIntelligenceService salesIntelligenceService;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final InventoryRoleAccessService inventoryRoleAccessService;
    private final BusinessInventorySettingsService businessInventorySettingsService;

    @Value("${app.inventory.daily-stock-audit.sample-size:25}")
    private int platformSampleSize;

    @Value("${app.inventory.daily-stock-audit.zone:Africa/Nairobi}")
    private String auditZoneId;

    public LocalDate resolveAuditDate(LocalDate auditDate) {
        return auditDate != null ? auditDate : LocalDate.now(ZoneId.of(auditZoneId));
    }

    @Transactional
    public Optional<DailyStockAudit> generateForBranchIfAbsent(
            String businessId,
            String branchId,
            LocalDate auditDate,
            String generatedBy
    ) {
        requireBranch(businessId, branchId);
        if (
            dailyStockAuditRepository.existsByBusinessIdAndBranchIdAndAuditDate(
                    businessId,
                    branchId,
                    auditDate
            )
        ) {
            return dailyStockAuditRepository.findByBusinessBranchAndDateFetchItems(
                    businessId,
                    branchId,
                    auditDate
            );
        }

        LocalDate soldOn = auditDate.minusDays(1);
        int limit = resolveSampleSize(businessId);
        List<String> itemIds = saleItemRepository.findRandomSoldItemIds(
                businessId,
                branchId,
                soldOn,
                limit
        );
        if (itemIds.isEmpty()) {
            return Optional.empty();
        }

        DailyStockAudit audit = new DailyStockAudit();
        audit.setId(UUID.randomUUID().toString());
        audit.setBusinessId(businessId);
        audit.setBranchId(branchId);
        audit.setAuditDate(auditDate);
        audit.setItemCount(itemIds.size());
        audit.setGeneratedAt(Instant.now());
        audit.setGeneratedBy(generatedBy);

        int order = 0;
        for (String itemId : itemIds) {
            audit.addItem(itemId, order++);
        }

        try {
            dailyStockAuditRepository.save(audit);
            return Optional.of(audit);
        } catch (DataIntegrityViolationException ex) {
            return dailyStockAuditRepository.findByBusinessBranchAndDateFetchItems(
                    businessId,
                    branchId,
                    auditDate
            );
        }
    }

    @Transactional
    public void generateForAllBusinesses(LocalDate auditDate) {
        var page = businessRepository.findByDeletedAtIsNull(PageRequest.of(0, 500));
        for (var business : page.getContent()) {
            if (
                business.getTenantStatus() == zelisline.ub.tenancy.domain.TenantStatus.SUSPENDED
                        || business.getTenantStatus() == zelisline.ub.tenancy.domain.TenantStatus.INACTIVE
            ) {
                continue;
            }
            List<String> branchIds = branchRepository
                    .findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(business.getId())
                    .stream()
                    .map(b -> b.getId())
                    .toList();
            for (String branchId : branchIds) {
                try {
                    generateForBranchIfAbsent(
                            business.getId(),
                            branchId,
                            auditDate,
                            "system"
                    );
                } catch (RuntimeException ex) {
                    // logged by scheduler
                }
            }
        }
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditTodayResponse getToday(
            String businessId,
            String branchId,
            LocalDate auditDate
    ) {
        DailyStockAudit audit = requireManifest(businessId, branchId, auditDate, "system");
        return buildTodayResponse(businessId, audit);
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditSessionResponse startOrResumeSession(
            String businessId,
            String branchId,
            String sessionType,
            LocalDate auditDate,
            String userId,
            String roleId,
            boolean hasStocktakeApprove
    ) {
        requireValidSessionType(sessionType);
        requireCountingWindowOpen(businessId, sessionType);
        boolean showSystemStock = inventoryRoleAccessService.canSeeSystemStockDuringCount(
                businessId,
                roleId,
                hasStocktakeApprove
        );
        DailyStockAudit audit = requireManifest(businessId, branchId, auditDate, userId);

        List<StockTakeSession> existingOfType =
                stockTakeSessionRepository.findDailyAuditSessionsFetchLines(
                        businessId,
                        branchId,
                        auditDate,
                        sessionType,
                        InventoryConstants.STOCKTAKE_SOURCE_DAILY_AUDIT,
                        audit.getId()
                );
        Optional<StockTakeSession> active = existingOfType.stream()
                .filter(s -> InventoryConstants.STOCKTAKE_SESSION_IN_PROGRESS.equals(s.getStatus()))
                .findFirst();
        if (active.isPresent()) {
            return toSessionResponse(businessId, active.get(), audit, showSystemStock);
        }
        Optional<StockTakeSession> closed = existingOfType.stream()
                .filter(s -> InventoryConstants.STOCKTAKE_SESSION_CLOSED.equals(s.getStatus()))
                .findFirst();
        if (closed.isPresent()) {
            String label = InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING.equals(sessionType)
                    ? "Morning"
                    : "Evening";
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    label + " count is already done for today."
            );
        }

        List<String> itemIds = audit.getItems().stream()
                .map(i -> i.getItemId())
                .toList();
        StockTakeSessionResponse created = stockTakeService.startDailyAuditSession(
                businessId,
                branchId,
                sessionType,
                auditDate,
                audit.getId(),
                itemIds,
                userId
        );
        StockTakeSession session = stockTakeSessionRepository
                .findByIdAndBusinessIdFetchLines(created.id(), businessId)
                .orElseThrow();
        return toSessionResponse(businessId, session, audit, showSystemStock);
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditSessionResponse completeSession(
            String businessId,
            String sessionId,
            String userId,
            String roleId,
            boolean hasStocktakeApprove
    ) {
        StockTakeSession session = loadDailyAuditSession(businessId, sessionId);
        requireCountingWindowOpen(businessId, session.getSessionType());

        if (InventoryConstants.STOCKTAKE_SESSION_CLOSED.equals(session.getStatus())) {
            DailyStockAudit audit = loadAudit(businessId, session.getDailyAuditId());
            boolean showSystemStock = inventoryRoleAccessService.canSeeSystemStockDuringCount(
                    businessId,
                    roleId,
                    hasStocktakeApprove
            );
            return toSessionResponse(businessId, session, audit, showSystemStock);
        }
        if (!InventoryConstants.STOCKTAKE_SESSION_IN_PROGRESS.equals(session.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Session cannot be completed from status " + session.getStatus()
            );
        }

        long missing = session.getLines().stream()
                .filter(l -> l.getCountedQty() == null)
                .count();
        if (missing > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    missing + " item(s) still need a physical count before finishing."
            );
        }

        session.setStatus(InventoryConstants.STOCKTAKE_SESSION_CLOSED);
        session.setClosedAt(Instant.now());
        session.setClosedBy(userId);
        stockTakeSessionRepository.save(session);

        DailyStockAudit audit = loadAudit(businessId, session.getDailyAuditId());
        boolean showSystemStock = inventoryRoleAccessService.canSeeSystemStockDuringCount(
                businessId,
                roleId,
                hasStocktakeApprove
        );
        return toSessionResponse(businessId, session, audit, showSystemStock);
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditSessionResponse applyLineCount(
            String businessId,
            String sessionId,
            String lineId,
            BigDecimal countedQty,
            String note,
            String userId,
            String roleId,
            boolean hasStocktakeApprove
    ) {
        StockTakeSession existing = loadDailyAuditSession(businessId, sessionId);
        requireSessionEditable(existing);
        requireCountingWindowOpen(businessId, existing.getSessionType());
        stockTakeService.applySingleCountWithNote(
                businessId,
                sessionId,
                lineId,
                countedQty,
                null,
                note,
                null,
                userId
        );
        StockTakeSession session = stockTakeSessionRepository
                .findByIdAndBusinessIdFetchLines(sessionId, businessId)
                .orElseThrow();
        DailyStockAudit audit = loadAudit(businessId, session.getDailyAuditId());
        boolean showSystemStock = inventoryRoleAccessService.canSeeSystemStockDuringCount(
                businessId,
                roleId,
                hasStocktakeApprove
        );
        return toSessionResponse(businessId, session, audit, showSystemStock);
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditSessionResponse updateProgress(
            String businessId,
            String sessionId,
            int currentLineIndex,
            String roleId,
            boolean hasStocktakeApprove
    ) {
        StockTakeSession session = loadDailyAuditSession(businessId, sessionId);
        requireSessionEditable(session);
        requireCountingWindowOpen(businessId, session.getSessionType());
        stockTakeService.updateSessionProgress(
                businessId,
                sessionId,
                currentLineIndex
        );
        session = stockTakeSessionRepository
                .findByIdAndBusinessIdFetchLines(sessionId, businessId)
                .orElseThrow();
        DailyStockAudit audit = loadAudit(businessId, session.getDailyAuditId());
        boolean showSystemStock = inventoryRoleAccessService.canSeeSystemStockDuringCount(
                businessId,
                roleId,
                hasStocktakeApprove
        );
        return toSessionResponse(businessId, session, audit, showSystemStock);
    }

    @Transactional(readOnly = true)
    public DailyStockAuditDtos.DailyStockAuditSessionResponse getSession(
            String businessId,
            String sessionId,
            String roleId,
            boolean hasStocktakeApprove
    ) {
        StockTakeSession session = loadDailyAuditSession(businessId, sessionId);
        DailyStockAudit audit = loadAudit(businessId, session.getDailyAuditId());
        boolean showSystemStock = inventoryRoleAccessService.canSeeSystemStockDuringCount(
                businessId,
                roleId,
                hasStocktakeApprove
        );
        return toSessionResponse(businessId, session, audit, showSystemStock);
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditReviewResponse getReview(
            String businessId,
            String branchId,
            LocalDate auditDate
    ) {
        DailyStockAudit audit = requireManifest(businessId, branchId, auditDate, "system");

        List<StockTakeSession> sessions =
                stockTakeSessionRepository.findByDailyAuditIdAndBusinessIdFetchLines(
                        businessId,
                        audit.getId()
                );
        StockTakeSession morning = findSessionByType(
                sessions,
                InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING
        );
        StockTakeSession evening = findSessionByType(
                sessions,
                InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING
        );

        Map<String, StockTakeLine> morningByItem = linesByItemId(morning);
        Map<String, StockTakeLine> eveningByItem = linesByItemId(evening);
        Map<String, BigDecimal> soldByItem = salesIntelligenceService.quantitySoldByItem(
                businessId,
                auditDate,
                auditDate
        );

        List<String> itemIds = audit.getItems().stream()
                .map(i -> i.getItemId())
                .toList();
        ItemContext ctx = loadItemContext(businessId, itemIds);

        List<DailyStockAuditDtos.DailyStockAuditReviewLineResponse> lines = new ArrayList<>();
        for (var auditItem : audit.getItems()) {
            String itemId = auditItem.getItemId();
            Item item = ctx.itemsById().get(itemId);
            StockTakeLine morningLine = morningByItem.get(itemId);
            StockTakeLine eveningLine = eveningByItem.get(itemId);
            BigDecimal morningCount = morningLine != null ? morningLine.getCountedQty() : null;
            BigDecimal eveningCount = eveningLine != null ? eveningLine.getCountedQty() : null;
            BigDecimal counted = eveningCount != null ? eveningCount : morningCount;
            BigDecimal systemStock = eveningLine != null
                    ? eveningLine.getSystemQtySnapshot()
                    : morningLine != null ? morningLine.getSystemQtySnapshot() : BigDecimal.ZERO;
            BigDecimal opening = morningCount != null ? morningCount : systemStock;
            BigDecimal sold = soldByItem.getOrDefault(itemId, BigDecimal.ZERO);
            BigDecimal expected = opening.subtract(sold).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            BigDecimal variance = counted != null
                    ? counted.subtract(systemStock).setScale(QTY_SCALE, RoundingMode.HALF_UP)
                    : null;
            boolean matches = variance != null && variance.compareTo(BigDecimal.ZERO) == 0;

            StockTakeLine reviewLine = eveningLine != null ? eveningLine : morningLine;
            String reviewStatus = reviewLine != null
                    ? reviewLine.getReviewStatus()
                    : InventoryConstants.DAILY_AUDIT_REVIEW_PENDING;

            lines.add(
                    new DailyStockAuditDtos.DailyStockAuditReviewLineResponse(
                            itemId,
                            itemDisplayName(item, itemId),
                            item != null ? item.getSku() : null,
                            item != null ? item.getBarcode() : null,
                            categoryName(ctx, item),
                            item != null ? item.getUnitType() : null,
                            ctx.thumbs().get(itemId),
                            morningCount,
                            eveningCount,
                            systemStock,
                            expected,
                            variance,
                            matches,
                            reviewStatus,
                            reviewLine != null ? reviewLine.getReviewNotes() : null,
                            reviewLine != null ? reviewLine.getReviewedBy() : null,
                            reviewLine != null ? reviewLine.getReviewedAt() : null,
                            auditItem.getSortOrder()
                    )
            );
        }

        return new DailyStockAuditDtos.DailyStockAuditReviewResponse(
                audit.getId(),
                audit.getAuditDate(),
                audit.getBranchId(),
                audit.getItemCount(),
                lines
        );
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditReviewResponse approveItem(
            String businessId,
            String auditId,
            String itemId,
            String notes,
            String userId
    ) {
        DailyStockAudit audit = loadAudit(businessId, auditId);
        applyReviewDecision(
                businessId,
                auditId,
                itemId,
                InventoryConstants.DAILY_AUDIT_REVIEW_APPROVED,
                notes,
                userId
        );
        return getReview(businessId, audit.getBranchId(), audit.getAuditDate());
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditReviewResponse approveItems(
            String businessId,
            String auditId,
            List<String> itemIds,
            String notes,
            String userId
    ) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "itemIds is required"
            );
        }
        DailyStockAudit audit = loadAudit(businessId, auditId);
        LinkedHashMap<String, Boolean> unique = new LinkedHashMap<>();
        for (String itemId : itemIds) {
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            unique.putIfAbsent(itemId.trim(), Boolean.TRUE);
        }
        if (unique.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "itemIds is required"
            );
        }
        for (String itemId : unique.keySet()) {
            applyReviewDecision(
                    businessId,
                    auditId,
                    itemId,
                    InventoryConstants.DAILY_AUDIT_REVIEW_APPROVED,
                    notes,
                    userId
            );
        }
        return getReview(businessId, audit.getBranchId(), audit.getAuditDate());
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditReviewResponse escalateItem(
            String businessId,
            String auditId,
            String itemId,
            String notes,
            String userId
    ) {
        DailyStockAudit audit = loadAudit(businessId, auditId);
        applyReviewDecision(
                businessId,
                auditId,
                itemId,
                InventoryConstants.DAILY_AUDIT_REVIEW_ESCALATED,
                notes,
                userId
        );
        return getReview(businessId, audit.getBranchId(), audit.getAuditDate());
    }

    @Transactional(readOnly = true)
    public List<DailyStockAuditDtos.DailyStockAuditInvestigationResponse> listInvestigations(
            String businessId,
            String branchId,
            LocalDate from,
            LocalDate to
    ) {
        List<StockTakeLine> lines = stockTakeLineRepository.findEscalatedDailyAuditLines(
                businessId,
                branchId,
                from,
                to
        );
        if (lines.isEmpty()) {
            return List.of();
        }

        Set<String> auditIds = lines.stream()
                .map(l -> l.getSession().getDailyAuditId())
                .collect(Collectors.toSet());
        Map<String, DailyStockAudit> auditsById = new HashMap<>();
        for (String auditId : auditIds) {
            dailyStockAuditRepository
                    .findByIdAndBusinessIdFetchItems(auditId, businessId)
                    .ifPresent(a -> auditsById.put(auditId, a));
        }

        List<StockTakeSession> allSessions = new ArrayList<>();
        for (String auditId : auditIds) {
            allSessions.addAll(
                    stockTakeSessionRepository.findByDailyAuditIdAndBusinessIdFetchLines(
                            businessId,
                            auditId
                    )
            );
        }
        Map<String, Map<String, StockTakeLine>> linesByAuditAndItem = new HashMap<>();
        for (StockTakeSession session : allSessions) {
            Map<String, StockTakeLine> byItem = linesByAuditAndItem.computeIfAbsent(
                    session.getDailyAuditId(),
                    k -> new HashMap<>()
            );
            for (StockTakeLine line : session.getLines()) {
                byItem.put(line.getItemId() + ":" + session.getSessionType(), line);
            }
        }

        List<String> itemIds = lines.stream()
                .map(StockTakeLine::getItemId)
                .distinct()
                .toList();
        ItemContext ctx = loadItemContext(businessId, itemIds);

        List<DailyStockAuditDtos.DailyStockAuditInvestigationResponse> out = new ArrayList<>();
        for (StockTakeLine escalatedLine : lines) {
            StockTakeSession session = escalatedLine.getSession();
            DailyStockAudit audit = auditsById.get(session.getDailyAuditId());
            if (audit == null) {
                continue;
            }
            String itemId = escalatedLine.getItemId();
            Map<String, StockTakeLine> auditLines = linesByAuditAndItem.getOrDefault(
                    audit.getId(),
                    Map.of()
            );
            StockTakeLine morningLine = auditLines.get(
                    itemId + ":" + InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING
            );
            StockTakeLine eveningLine = auditLines.get(
                    itemId + ":" + InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING
            );
            BigDecimal morningCount = morningLine != null ? morningLine.getCountedQty() : null;
            BigDecimal eveningCount = eveningLine != null ? eveningLine.getCountedQty() : null;
            BigDecimal systemStock = escalatedLine.getSystemQtySnapshot();
            BigDecimal counted = eveningCount != null ? eveningCount : morningCount;
            BigDecimal variance = counted != null
                    ? counted.subtract(systemStock).setScale(QTY_SCALE, RoundingMode.HALF_UP)
                    : null;
            BigDecimal opening = morningCount != null ? morningCount : systemStock;
            BigDecimal sold = salesIntelligenceService
                    .quantitySoldByItem(
                            businessId,
                            audit.getAuditDate(),
                            audit.getAuditDate()
                    )
                    .getOrDefault(itemId, BigDecimal.ZERO);
            BigDecimal expected = opening.subtract(sold).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            Item item = ctx.itemsById().get(itemId);

            out.add(
                    new DailyStockAuditDtos.DailyStockAuditInvestigationResponse(
                            audit.getId(),
                            audit.getAuditDate(),
                            audit.getBranchId(),
                            itemId,
                            itemDisplayName(item, itemId),
                            item != null ? item.getSku() : null,
                            morningCount,
                            eveningCount,
                            systemStock,
                            expected,
                            variance,
                            escalatedLine.getReviewNotes(),
                            escalatedLine.getReviewedBy(),
                            escalatedLine.getReviewedAt()
                    )
            );
        }
        return out;
    }

    private void applyReviewDecision(
            String businessId,
            String auditId,
            String itemId,
            String reviewStatus,
            String notes,
            String userId
    ) {
        List<StockTakeSession> sessions =
                stockTakeSessionRepository.findByDailyAuditIdAndBusinessIdFetchLines(
                        businessId,
                        auditId
                );
        StockTakeLine eveningLine = null;
        StockTakeLine morningLine = null;
        StockTakeSession eveningSession = findSessionByType(
                sessions,
                InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING
        );
        if (eveningSession != null) {
            eveningLine = eveningSession.getLines().stream()
                    .filter(l -> l.getItemId().equals(itemId))
                    .findFirst()
                    .orElse(null);
        }
        StockTakeSession morningSession = findSessionByType(
                sessions,
                InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING
        );
        if (morningSession != null) {
            morningLine = morningSession.getLines().stream()
                    .filter(l -> l.getItemId().equals(itemId))
                    .findFirst()
                    .orElse(null);
        }

        // Prefer evening count for review + stock update; fall back to morning.
        StockTakeLine target = null;
        if (eveningLine != null && eveningLine.getCountedQty() != null) {
            target = eveningLine;
        } else if (morningLine != null && morningLine.getCountedQty() != null) {
            target = morningLine;
        } else {
            target = eveningLine != null ? eveningLine : morningLine;
        }
        if (target == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No count line found for item in daily audit"
            );
        }

        // Idempotent approve: already approved (+ stock applied) — skip.
        if (
            InventoryConstants.DAILY_AUDIT_REVIEW_APPROVED.equals(reviewStatus)
                    && InventoryConstants.DAILY_AUDIT_REVIEW_APPROVED.equals(
                            target.getReviewStatus()
                    )
                    && InventoryConstants.STOCKTAKE_LINE_CONFIRMED.equals(target.getStatus())
        ) {
            return;
        }

        if (
            InventoryConstants.DAILY_AUDIT_REVIEW_APPROVED.equals(reviewStatus)
                    && target.getCountedQty() == null
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cannot approve without an evening (or morning) count"
            );
        }

        Instant now = Instant.now();
        target.setReviewStatus(reviewStatus);
        target.setReviewNotes(notes != null && !notes.isBlank() ? notes.trim() : null);
        target.setReviewedBy(userId);
        target.setReviewedAt(now);
        stockTakeLineRepository.save(target);

        if (InventoryConstants.DAILY_AUDIT_REVIEW_APPROVED.equals(reviewStatus)) {
            stockTakeService.applyDailyAuditApprovedCount(businessId, target, userId);
            // Keep morning review status in sync when evening is the approval target.
            if (eveningLine != null && morningLine != null && morningLine != target) {
                morningLine.setReviewStatus(reviewStatus);
                morningLine.setReviewNotes(target.getReviewNotes());
                morningLine.setReviewedBy(userId);
                morningLine.setReviewedAt(now);
                stockTakeLineRepository.save(morningLine);
            }
        }
    }

    private DailyStockAuditDtos.DailyStockAuditTodayResponse buildTodayResponse(
            String businessId,
            DailyStockAudit audit
    ) {
        List<StockTakeSession> sessions =
                stockTakeSessionRepository.findByDailyAuditIdAndBusinessIdFetchLines(
                        businessId,
                        audit.getId()
                );
        CountingSchedulePhase phase = resolveCountingSchedulePhase(
                businessId,
                audit.getAuditDate()
        );
        return new DailyStockAuditDtos.DailyStockAuditTodayResponse(
                audit.getId(),
                audit.getAuditDate(),
                audit.getBranchId(),
                audit.getItemCount(),
                audit.getGeneratedAt(),
                buildItemSummaries(businessId, audit),
                summarizeSession(
                        findSessionByType(
                                sessions,
                                InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING
                        )
                ),
                summarizeSession(
                        findSessionByType(
                                sessions,
                                InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING
                        )
                ),
                phase.morningStartsAt(),
                phase.eveningStartsAt(),
                phase.countingEndsAt(),
                phase.timezone(),
                phase.activeSessionType(),
                phase.phaseEndsAt(),
                phase.nextOpensAt()
        );
    }

    private List<DailyStockAuditDtos.DailyStockAuditItemSummary> buildItemSummaries(
            String businessId,
            DailyStockAudit audit
    ) {
        List<String> itemIds = audit.getItems().stream()
                .map(i -> i.getItemId())
                .toList();
        ItemContext ctx = loadItemContext(businessId, itemIds);
        List<DailyStockAuditDtos.DailyStockAuditItemSummary> out = new ArrayList<>();
        for (var row : audit.getItems()) {
            Item item = ctx.itemsById().get(row.getItemId());
            out.add(
                    new DailyStockAuditDtos.DailyStockAuditItemSummary(
                            row.getItemId(),
                            itemDisplayName(item, row.getItemId()),
                            item != null ? item.getSku() : null,
                            item != null ? item.getBarcode() : null,
                            categoryName(ctx, item),
                            item != null ? item.getUnitType() : null,
                            ctx.thumbs().get(row.getItemId()),
                            row.getSortOrder()
                    )
            );
        }
        return out;
    }

    private DailyStockAuditDtos.DailyStockAuditSessionResponse toSessionResponse(
            String businessId,
            StockTakeSession session,
            DailyStockAudit audit,
            boolean showSystemStock
    ) {
        List<String> itemIds = session.getLines().stream()
                .map(StockTakeLine::getItemId)
                .toList();
        ItemContext ctx = loadItemContext(businessId, itemIds);
        int submitted = 0;
        List<DailyStockAuditDtos.DailyStockAuditLineResponse> lines = new ArrayList<>();
        for (StockTakeLine line : session.getLines()) {
            if (
                InventoryConstants.STOCKTAKE_LINE_SUBMITTED.equals(line.getStatus())
                        || InventoryConstants.STOCKTAKE_LINE_CONFIRMED.equals(
                                line.getStatus()
                        )
            ) {
                submitted++;
            }
            Item item = ctx.itemsById().get(line.getItemId());
            lines.add(
                    new DailyStockAuditDtos.DailyStockAuditLineResponse(
                            line.getId(),
                            line.getItemId(),
                            itemDisplayName(item, line.getItemId()),
                            item != null ? item.getSku() : null,
                            item != null ? item.getBarcode() : null,
                            categoryName(ctx, item),
                            item != null ? item.getUnitType() : null,
                            ctx.thumbs().get(line.getItemId()),
                            line.getCountedQty(),
                            line.getNote(),
                            line.getStatus(),
                            line.getSubmittedAt(),
                            line.getSortOrder(),
                            showSystemStock ? line.getSystemQtySnapshot() : null
                    )
            );
        }
        return new DailyStockAuditDtos.DailyStockAuditSessionResponse(
                session.getId(),
                audit.getId(),
                audit.getAuditDate(),
                session.getBranchId(),
                session.getSessionType(),
                session.getStatus(),
                session.getCurrentLineIndex() != null ? session.getCurrentLineIndex() : 0,
                lines.size(),
                submitted,
                lines
        );
    }

    private DailyStockAuditDtos.DailyStockAuditSessionSummary summarizeSession(StockTakeSession session) {
        if (session == null) {
            return null;
        }
        int submitted = (int) session.getLines().stream()
                .filter(l ->
                        InventoryConstants.STOCKTAKE_LINE_SUBMITTED.equals(l.getStatus())
                                || InventoryConstants.STOCKTAKE_LINE_CONFIRMED.equals(
                                        l.getStatus()
                                )
                )
                .count();
        return new DailyStockAuditDtos.DailyStockAuditSessionSummary(
                session.getId(),
                session.getSessionType(),
                session.getStatus(),
                session.getCurrentLineIndex() != null ? session.getCurrentLineIndex() : 0,
                submitted,
                session.getLines().size()
        );
    }

    private StockTakeSession findSessionByType(
            List<StockTakeSession> sessions,
            String sessionType
    ) {
        return sessions.stream()
                .filter(s -> sessionType.equals(s.getSessionType()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, StockTakeLine> linesByItemId(StockTakeSession session) {
        if (session == null || session.getLines() == null) {
            return Map.of();
        }
        Map<String, StockTakeLine> map = new HashMap<>();
        for (StockTakeLine line : session.getLines()) {
            map.put(line.getItemId(), line);
        }
        return map;
    }

    private StockTakeSession loadDailyAuditSession(String businessId, String sessionId) {
        StockTakeSession session = stockTakeSessionRepository
                .findByIdAndBusinessIdFetchLines(sessionId, businessId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Session not found"
                        )
                );
        if (
            !InventoryConstants.STOCKTAKE_SOURCE_DAILY_AUDIT.equals(session.getSource())
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Not a daily audit session"
            );
        }
        return session;
    }

    private DailyStockAudit loadAudit(String businessId, String auditId) {
        return dailyStockAuditRepository
                .findByIdAndBusinessIdFetchItems(auditId, businessId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Daily audit not found"
                        )
                );
    }

    private DailyStockAudit requireManifest(
            String businessId,
            String branchId,
            LocalDate auditDate,
            String generatedBy
    ) {
        requireBranch(businessId, branchId);
        return dailyStockAuditRepository
                .findByBusinessBranchAndDateFetchItems(businessId, branchId, auditDate)
                .or(() ->
                        generateForBranchIfAbsent(
                                businessId,
                                branchId,
                                auditDate,
                                generatedBy
                        )
                )
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "No products were sold yesterday at this branch, so there is no daily audit for this date."
                        )
                );
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
            !InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING.equals(sessionType)
                    && !InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING.equals(
                            sessionType
                    )
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sessionType must be 'morning' or 'evening'"
            );
        }
    }

    private void requireSessionEditable(StockTakeSession session) {
        if (!InventoryConstants.STOCKTAKE_SESSION_IN_PROGRESS.equals(session.getStatus())) {
            String label = InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING.equals(
                            session.getSessionType()
                    )
                    ? "Morning"
                    : "Evening";
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    label + " count is already done. Counts are locked."
            );
        }
    }

    private void requireCountingWindowOpen(String businessId, String sessionType) {
        CountingSchedulePhase phase = resolveCountingSchedulePhase(
                businessId,
                LocalDate.now(resolveBusinessZone(businessId))
        );
        String active = phase.activeSessionType();
        if (active != null && active.equals(sessionType)) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                countingWindowClosedMessage(sessionType, phase)
        );
    }

    private static String countingWindowClosedMessage(
            String sessionType,
            CountingSchedulePhase phase
    ) {
        if (phase.activeSessionType() == null) {
            if (phase.nextOpensAt() != null) {
                return "Morning counting opens at " + phase.morningStartsAt()
                        + " (" + phase.timezone() + ")";
            }
            return "Counting has ended for today (closed at "
                    + phase.countingEndsAt()
                    + " "
                    + phase.timezone()
                    + ")";
        }
        if (InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING.equals(sessionType)) {
            return "Morning counting is closed. Evening counting is open until "
                    + phase.countingEndsAt();
        }
        return "Evening counting opens at " + phase.eveningStartsAt()
                + " (" + phase.timezone() + ")";
    }

    private CountingSchedulePhase resolveCountingSchedulePhase(
            String businessId,
            LocalDate auditDate
    ) {
        Business business = businessRepository.findById(businessId).orElse(null);
        ZoneId zone = resolveBusinessZone(business);
        StocktakeSettingsResponse stocktake = business == null
                ? StocktakeSettingsResponse.defaults()
                : businessInventorySettingsService
                        .readFromSettingsJson(business.getSettings())
                        .stocktake();

        LocalTime morning = StocktakeSettingsResponse.parseTime(stocktake.morningStartsAt());
        LocalTime evening = StocktakeSettingsResponse.parseTime(stocktake.eveningStartsAt());
        LocalTime ends = StocktakeSettingsResponse.parseTime(stocktake.countingEndsAt());

        Instant morningStart = atZone(auditDate, morning, zone);
        Instant eveningStart = atZone(auditDate, evening, zone);
        Instant countingEnd = atZone(auditDate, ends, zone);
        Instant now = Instant.now();

        String active;
        Instant phaseEndsAt;
        Instant nextOpensAt;
        if (now.isBefore(morningStart)) {
            active = null;
            phaseEndsAt = null;
            nextOpensAt = morningStart;
        } else if (now.isBefore(eveningStart)) {
            active = InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING;
            phaseEndsAt = eveningStart;
            nextOpensAt = eveningStart;
        } else if (now.isBefore(countingEnd)) {
            active = InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING;
            phaseEndsAt = countingEnd;
            nextOpensAt = null;
        } else {
            active = null;
            phaseEndsAt = null;
            nextOpensAt = null;
        }

        return new CountingSchedulePhase(
                stocktake.morningStartsAt(),
                stocktake.eveningStartsAt(),
                stocktake.countingEndsAt(),
                zone.getId(),
                active,
                phaseEndsAt,
                nextOpensAt
        );
    }

    private ZoneId resolveBusinessZone(String businessId) {
        return resolveBusinessZone(
                businessRepository.findById(businessId).orElse(null)
        );
    }

    private ZoneId resolveBusinessZone(Business business) {
        String tz = business != null ? blankToNull(business.getTimezone()) : null;
        if (tz == null) {
            tz = blankToNull(auditZoneId);
        }
        if (tz == null) {
            tz = "Africa/Nairobi";
        }
        try {
            return ZoneId.of(tz);
        } catch (Exception ex) {
            return ZoneId.of("Africa/Nairobi");
        }
    }

    private static Instant atZone(LocalDate date, LocalTime time, ZoneId zone) {
        return LocalDateTime.of(date, time).atZone(zone).toInstant();
    }

    private record CountingSchedulePhase(
            String morningStartsAt,
            String eveningStartsAt,
            String countingEndsAt,
            String timezone,
            String activeSessionType,
            Instant phaseEndsAt,
            Instant nextOpensAt
    ) {}

    private ItemContext loadItemContext(String businessId, List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return new ItemContext(Map.of(), Map.of(), Map.of());
        }
        Map<String, Item> itemsById = itemRepository
                .findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)
                .stream()
                .collect(Collectors.toMap(Item::getId, i -> i));
        Set<String> categoryIds = itemsById.values().stream()
                .map(Item::getCategoryId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, Category> categoriesById = categoryIds.isEmpty()
                ? Map.of()
                : categoryRepository.findByBusinessIdOrderByPositionAsc(businessId)
                        .stream()
                        .filter(c -> categoryIds.contains(c.getId()))
                        .collect(Collectors.toMap(Category::getId, c -> c));
        Map<String, String> thumbs = new LinkedHashMap<>(
                itemCatalogService.resolveThumbnailUrls(businessId, itemIds)
        );
        // Variants without their own photo borrow the parent product image.
        Set<String> parentIds = itemsById.values().stream()
                .filter(i -> !thumbs.containsKey(i.getId()))
                .map(Item::getVariantOfItemId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (!parentIds.isEmpty()) {
            Map<String, String> parentThumbs = itemCatalogService.resolveThumbnailUrls(
                    businessId,
                    parentIds
            );
            for (Item item : itemsById.values()) {
                if (thumbs.containsKey(item.getId())) {
                    continue;
                }
                String parentId = item.getVariantOfItemId();
                if (parentId == null || parentId.isBlank()) {
                    continue;
                }
                String parentThumb = parentThumbs.get(parentId);
                if (parentThumb != null && !parentThumb.isBlank()) {
                    thumbs.put(item.getId(), parentThumb);
                }
            }
        }
        return new ItemContext(itemsById, categoriesById, thumbs);
    }

    /**
     * Shelf label for audits: parent/base name plus pack size or variant option
     * (e.g. "Kensalt 500g"), matching cost-audit / supplier-catalog display.
     */
    private static String itemDisplayName(Item item, String fallbackId) {
        if (item == null) {
            return fallbackId != null ? fallbackId : "";
        }
        String base = blankToNull(item.getName());
        if (base == null) {
            base = "Item";
        }
        String suffix = blankToNull(item.getVariantName());
        if (suffix != null && isGenericVariantLabel(suffix)) {
            suffix = null;
        }
        if (suffix == null) {
            suffix = blankToNull(item.getSize());
        }
        if (suffix == null) {
            suffix = blankToNull(item.getPackagingUnitName());
        }
        if (suffix == null) {
            return base;
        }
        if (base.toLowerCase(Locale.ROOT).contains(suffix.toLowerCase(Locale.ROOT))) {
            return base;
        }
        return base + " " + suffix;
    }

    private static boolean isGenericVariantLabel(String variantName) {
        String t = variantName.trim().toLowerCase(Locale.ROOT);
        return t.equals("variant")
                || t.equals("option")
                || t.equals("variation")
                || t.equals("default");
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int resolveSampleSize(String businessId) {
        int configured = businessRepository
                .findById(businessId)
                .map(Business::getSettings)
                .map(businessInventorySettingsService::readFromSettingsJson)
                .map(inv -> inv.stocktake().dailyAuditSampleSize())
                .orElse(platformSampleSize);
        if (configured <= 0) {
            configured = platformSampleSize > 0
                    ? platformSampleSize
                    : StocktakeSettingsResponse.DEFAULT_DAILY_AUDIT_SAMPLE_SIZE;
        }
        return StocktakeSettingsResponse.clampSampleSize(configured);
    }

    private String categoryName(ItemContext ctx, Item item) {
        if (item == null || item.getCategoryId() == null) {
            return null;
        }
        Category category = ctx.categoriesById().get(item.getCategoryId());
        return category != null ? category.getName() : null;
    }

    private record ItemContext(
            Map<String, Item> itemsById,
            Map<String, Category> categoriesById,
            Map<String, String> thumbs
    ) {}
}
