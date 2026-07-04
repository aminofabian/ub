package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    @Value("${app.inventory.daily-stock-audit.sample-size:25}")
    private int sampleSize;

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
        int limit = Math.max(1, sampleSize);
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
            String userId
    ) {
        requireValidSessionType(sessionType);
        DailyStockAudit audit = requireManifest(businessId, branchId, auditDate, userId);

        Optional<StockTakeSession> existing =
                stockTakeSessionRepository.findActiveDailyAuditSessionFetchLines(
                        businessId,
                        branchId,
                        auditDate,
                        sessionType,
                        InventoryConstants.STOCKTAKE_SOURCE_DAILY_AUDIT,
                        audit.getId()
                );
        if (existing.isPresent()) {
            return toSessionResponse(businessId, existing.get(), audit);
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
        return toSessionResponse(businessId, session, audit);
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditSessionResponse applyLineCount(
            String businessId,
            String sessionId,
            String lineId,
            BigDecimal countedQty,
            String note,
            String userId
    ) {
        loadDailyAuditSession(businessId, sessionId);
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
        return toSessionResponse(businessId, session, audit);
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditSessionResponse updateProgress(
            String businessId,
            String sessionId,
            int currentLineIndex
    ) {
        StockTakeSession session = loadDailyAuditSession(businessId, sessionId);
        stockTakeService.updateSessionProgress(
                businessId,
                sessionId,
                currentLineIndex
        );
        session = stockTakeSessionRepository
                .findByIdAndBusinessIdFetchLines(sessionId, businessId)
                .orElseThrow();
        DailyStockAudit audit = loadAudit(businessId, session.getDailyAuditId());
        return toSessionResponse(businessId, session, audit);
    }

    @Transactional(readOnly = true)
    public DailyStockAuditDtos.DailyStockAuditSessionResponse getSession(
            String businessId,
            String sessionId
    ) {
        StockTakeSession session = loadDailyAuditSession(businessId, sessionId);
        DailyStockAudit audit = loadAudit(businessId, session.getDailyAuditId());
        return toSessionResponse(businessId, session, audit);
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
                            item != null ? item.getName() : itemId,
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
        return reviewItem(
                businessId,
                auditId,
                itemId,
                InventoryConstants.DAILY_AUDIT_REVIEW_APPROVED,
                notes,
                userId
        );
    }

    @Transactional
    public DailyStockAuditDtos.DailyStockAuditReviewResponse escalateItem(
            String businessId,
            String auditId,
            String itemId,
            String notes,
            String userId
    ) {
        return reviewItem(
                businessId,
                auditId,
                itemId,
                InventoryConstants.DAILY_AUDIT_REVIEW_ESCALATED,
                notes,
                userId
        );
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
                            item != null ? item.getName() : itemId,
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

    private DailyStockAuditDtos.DailyStockAuditReviewResponse reviewItem(
            String businessId,
            String auditId,
            String itemId,
            String reviewStatus,
            String notes,
            String userId
    ) {
        DailyStockAudit audit = loadAudit(businessId, auditId);
        List<StockTakeSession> sessions =
                stockTakeSessionRepository.findByDailyAuditIdAndBusinessIdFetchLines(
                        businessId,
                        auditId
                );
        StockTakeLine target = null;
        StockTakeSession eveningSession = findSessionByType(
                sessions,
                InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING
        );
        if (eveningSession != null) {
            target = eveningSession.getLines().stream()
                    .filter(l -> l.getItemId().equals(itemId))
                    .findFirst()
                    .orElse(null);
        }
        if (target == null) {
            StockTakeSession morningSession = findSessionByType(
                    sessions,
                    InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING
            );
            if (morningSession != null) {
                target = morningSession.getLines().stream()
                        .filter(l -> l.getItemId().equals(itemId))
                        .findFirst()
                        .orElse(null);
            }
        }
        if (target == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No count line found for item in daily audit"
            );
        }
        Instant now = Instant.now();
        target.setReviewStatus(reviewStatus);
        target.setReviewNotes(notes != null && !notes.isBlank() ? notes.trim() : null);
        target.setReviewedBy(userId);
        target.setReviewedAt(now);
        stockTakeLineRepository.save(target);
        return getReview(businessId, audit.getBranchId(), audit.getAuditDate());
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
                )
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
                            item != null ? item.getName() : row.getItemId(),
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
            DailyStockAudit audit
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
                            item != null ? item.getName() : line.getItemId(),
                            item != null ? item.getSku() : null,
                            item != null ? item.getBarcode() : null,
                            categoryName(ctx, item),
                            item != null ? item.getUnitType() : null,
                            ctx.thumbs().get(line.getItemId()),
                            line.getCountedQty(),
                            line.getNote(),
                            line.getStatus(),
                            line.getSubmittedAt(),
                            line.getSortOrder()
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
        Map<String, String> thumbs = itemCatalogService.resolveThumbnailUrls(
                businessId,
                itemIds
        );
        return new ItemContext(itemsById, categoriesById, thumbs);
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
