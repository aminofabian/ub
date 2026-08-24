package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.RestockDigestDtos;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.AcceptRestockRunRequest;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.AcceptRestockRunResponse;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.CreatedPurchaseOrderRef;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.RestockDigestPdfFile;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.SkippedAcceptLine;
import zelisline.ub.inventory.restock.RestockDigestPdfLine;
import zelisline.ub.inventory.restock.RestockDigestPdfRenderer;
import zelisline.ub.inventory.restock.RestockDigestPdfSnapshot;
import zelisline.ub.inventory.domain.OrderPadItem;
import zelisline.ub.inventory.domain.RestockRun;
import zelisline.ub.inventory.domain.RestockSuggestion;
import zelisline.ub.inventory.repository.OrderPadItemRepository;
import zelisline.ub.inventory.repository.RestockRunRepository;
import zelisline.ub.inventory.repository.RestockSuggestionRepository;
import zelisline.ub.inventory.repository.StockTakeLineRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.AddPathAPurchaseOrderLineRequest;
import zelisline.ub.purchasing.api.dto.CreatePathAPurchaseOrderRequest;
import zelisline.ub.purchasing.api.dto.PathAPurchaseOrderDetailResponse;
import zelisline.ub.purchasing.application.PathAPurchaseService;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.reporting.repository.MvSalesDailyRepository;
import zelisline.ub.reporting.repository.MvSalesDailyRepository.DigestVelocityRow;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository.ItemLinkRow;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Nightly restock digest engine. Generates one {@link RestockRun} per branch per
 * business-local day, with explainable {@link RestockSuggestion} lines, without
 * touching {@code stock_take_restock_items} (separate artifact / cadence).
 */
@Service
@RequiredArgsConstructor
public class RestockDigestService {

    private static final int QTY_SCALE = 4;
    private static final int VELOCITY_DAYS = 30;
    private static final int VELOCITY_7D = 7;
    /** Minimum accepted lines before par learning engages for an item. */
    private static final int MIN_ACCEPT_HISTORY = 3;
    private static final DateTimeFormatter PDF_DATE =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final String UNCATEGORISED = "Uncategorised";
    /** Query value for items with no department. Also accept the old {@code __none__} sentinel. */
    static final String UNCATEGORISED_KEY = "uncategorised";
    private static final String LEGACY_UNCATEGORISED_KEY = "__none__";

    private final RestockRunRepository restockRunRepository;
    private final RestockSuggestionRepository restockSuggestionRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final ItemRepository itemRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final MvSalesDailyRepository mvSalesDailyRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final OrderPadItemRepository orderPadItemRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierRepository supplierRepository;
    private final StockTakeLineRepository stockTakeLineRepository;
    private final PathAPurchaseService pathAPurchaseService;
    private final PurchaseOrderRepository purchaseOrderRepository;

    // ------------------------------------------------------------------ generate

    /**
     * Generate (or return an existing) digest run for a branch on a business-local day.
     * Idempotent: the unique {@code (branch_id, run_date)} constraint guarantees one run.
     */
    @Transactional
    public RestockDigestDtos.RestockRunResponse generateForBranch(
            String businessId,
            String branchId,
            LocalDate runDate,
            String trigger
    ) {
        Branch branch = requireBranch(businessId, branchId);
        Business business = requireBusiness(businessId);
        LocalDate effectiveDate = runDate != null ? runDate : LocalDate.now(businessZone(business));

        // Idempotent — a redeploy / double tick cannot double-generate.
        RestockRun existing = restockRunRepository.findByBranchIdAndRunDate(branchId, effectiveDate)
                .orElse(null);
        if (existing != null) {
            return toRunResponse(businessId, existing, loadSuggestions(businessId, existing.getId()), business, branch);
        }

        // Windows are anchored on the run date (not "now") so a manual run for an
        // explicit date reads the same history it would have read that night. The
        // upper bound is exclusive, so minusDays(N) spans exactly N whole days.
        LocalDate windowEnd = effectiveDate;
        LocalDate last7From = windowEnd.minusDays(VELOCITY_7D);
        LocalDate last30From = windowEnd.minusDays(VELOCITY_DAYS);

        // Candidate discovery — one window query per branch (MV + OLTP gap-fill).
        Map<String, RestockDigestFormula.VelocityInput> velocityByItem = loadVelocity(
                businessId, branchId, windowEnd, last7From, last30From);
        // Stock-out proxy: items counted at zero in a stock take / daily audit in window.
        Set<String> stockOutItemIds = new HashSet<>(
                stockTakeLineRepository.findCountedZeroItemIds(
                        businessId, branchId, last30From, windowEnd));
        Set<String> candidateIds = new LinkedHashSet<>(velocityByItem.keySet());
        candidateIds.addAll(stockOutItemIds);

        // Batch-load everything once, then compute lines.
        Map<String, Item> itemsById = loadItems(businessId, candidateIds);
        LocalDate usableFrom = effectiveDate.plusDays(branch.getRestockCoverDays());
        Map<String, BigDecimal> onHandByItem =
                resolveDisplayStockByItemId(businessId, branchId, itemsById, usableFrom);
        Map<String, BigDecimal> inboundByItem = loadInbound(businessId, branchId);
        Map<String, ItemLinkRow> linkByItem = loadPrimaryLinks(businessId, candidateIds);
        Set<String> snoozedItemIds = loadSnoozedItemIds(businessId, branchId, effectiveDate);
        // Phase-4 learning: bias par toward what the reviewer actually accepted.
        Map<String, BigDecimal> parBiases = loadParBiases(businessId, branchId);

        RestockRun run = new RestockRun();
        run.setId(UUID.randomUUID().toString()); // pre-assign so suggestion rows can reference it
        run.setBusinessId(businessId);
        run.setBranchId(branchId);
        run.setRunDate(effectiveDate);
        run.setGeneratedAt(Instant.now());
        run.setStatus(InventoryConstants.DIGEST_RUN_GENERATED);
        run.setCurrency(normalizeCurrency(business));
        run.setTrigger(normalizeTrigger(trigger));

        List<RestockSuggestion> suggestions = new ArrayList<>();
        for (String itemId : candidateIds) {
            Item item = itemsById.get(itemId);
            if (item == null || !isCandidateEligible(item)) {
                continue;
            }
            BigDecimal reorderLevel = item.getReorderLevel() != null
                    ? item.getReorderLevel()
                    : item.getMinStockLevel();
            BigDecimal onHand = qtyOrZero(onHandByItem.get(itemId));
            BigDecimal inbound = qtyOrZero(inboundByItem.get(itemId));
            ItemLinkRow link = linkByItem.get(itemId);
            boolean stockOut = stockOutItemIds.contains(itemId);
            boolean snoozed = snoozedItemIds.contains(itemId);

            RestockDigestFormula.Computed computed = RestockDigestFormula.compute(
                    onHand,
                    inbound,
                    reorderLevel,
                    item.getReorderQty(),
                    velocityByItem.get(itemId),
                    link != null
                            ? new RestockDigestFormula.LinkInput(
                                    link.getPackSize(), link.getMinOrderQty(), link.getLeadTimeDays())
                            : null,
                    branch.getRestockCoverDays(),
                    stockOut,
                    snoozed,
                    parBiases.get(itemId)).orElse(null);
            if (computed == null) {
                continue;
            }
            suggestions.add(buildSuggestion(
                    run,
                    item,
                    link,
                    onHand,
                    inbound,
                    reorderLevel,
                    computed));
        }

        applyCounts(run, suggestions);
        try {
            restockRunRepository.save(run);
            if (!suggestions.isEmpty()) {
                restockSuggestionRepository.saveAll(suggestions);
            }
        } catch (DataIntegrityViolationException ex) {
            // Concurrent generation — the unique constraint wins; return the existing run.
            RestockRun winner = restockRunRepository.findByBranchIdAndRunDate(branchId, effectiveDate)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "Restock run already exists for this branch and date"));
            return toRunResponse(businessId, winner, loadSuggestions(businessId, winner.getId()), business, branch);
        }
        // A fresh run supersedes the prior one: expire it if it still has pending lines.
        expirePriorRuns(businessId, branchId, effectiveDate);
        return toRunResponse(businessId, run, loadSuggestions(businessId, run.getId()), business, branch);
    }

    private void expirePriorRuns(String businessId, String branchId, LocalDate runDate) {
        List<RestockRun> prior = restockRunRepository.findByBranchIdAndRunDateBeforeAndStatusIn(
                branchId,
                runDate,
                List.of(
                        InventoryConstants.DIGEST_RUN_GENERATED,
                        InventoryConstants.DIGEST_RUN_NOTIFIED,
                        InventoryConstants.DIGEST_RUN_PARTIALLY_ACCEPTED));
        for (RestockRun p : prior) {
            if (restockSuggestionRepository.existsByRunIdAndStatus(
                    p.getId(), InventoryConstants.DIGEST_SUGGESTION_PENDING)) {
                p.setStatus(InventoryConstants.DIGEST_RUN_EXPIRED);
                restockRunRepository.save(p);
            }
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<RestockDigestDtos.RestockRunListRow> listRuns(
            String businessId,
            String branchId,
            LocalDate from,
            LocalDate to
    ) {
        Map<String, String> branchNames = loadBranchNames(businessId);
        return restockRunRepository.findForList(businessId, branchId, from, to).stream()
                .map(r -> new RestockDigestDtos.RestockRunListRow(
                        r.getId(),
                        r.getBranchId(),
                        branchNames.getOrDefault(r.getBranchId(), ""),
                        r.getRunDate(),
                        r.getGeneratedAt(),
                        r.getStatus(),
                        r.getLineCount(),
                        r.getEstTotal(),
                        r.getCurrency(),
                        r.getTrigger()))
                .toList();
    }

    @Transactional(readOnly = true)
    public RestockDigestDtos.RestockRunResponse getRun(String businessId, String runId) {
        RestockRun run = restockRunRepository.findByIdAndBusinessId(runId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        Business business = requireBusiness(businessId);
        Branch branch = requireBranch(businessId, run.getBranchId());
        return toRunResponse(businessId, run, loadSuggestions(businessId, run.getId()), business, branch);
    }

    /** Latest run for the branch — empty summary when none exists or it's no longer actionable. */
    @Transactional(readOnly = true)
    public RestockDigestDtos.RestockActiveRunSummary activeRunSummary(String businessId, String branchId) {
        requireBranch(businessId, branchId);
        RestockRun run = restockRunRepository.findFirstByBranchIdOrderByRunDateDescIdDesc(branchId)
                .orElse(null);
        boolean actionable = run != null
                && (InventoryConstants.DIGEST_RUN_GENERATED.equals(run.getStatus())
                        || InventoryConstants.DIGEST_RUN_NOTIFIED.equals(run.getStatus())
                        || InventoryConstants.DIGEST_RUN_PARTIALLY_ACCEPTED.equals(run.getStatus()));
        return actionable
                ? new RestockDigestDtos.RestockActiveRunSummary(
                        run.getId(), run.getRunDate(), run.getStatus(), run.getLineCount())
                : new RestockDigestDtos.RestockActiveRunSummary(null, null, null, 0);
    }

    /** Clerk-facing prep view — redacted (no cost / supplier / order links). */
    @Transactional(readOnly = true)
    public RestockDigestDtos.RestockPrepResponse prepRun(String businessId, String runId) {
        RestockRun run = requireRun(businessId, runId);
        Branch branch = requireBranch(businessId, run.getBranchId());
        List<RestockSuggestion> rows =
                restockSuggestionRepository.findByRunIdOrderBySuggestedQtyDescIdAsc(runId);
        Set<String> itemIds = rows.stream()
                .map(RestockSuggestion::getItemId)
                .collect(Collectors.toSet());
        Map<String, Item> items = loadItems(businessId, itemIds);
        Map<String, ItemType> types = loadItemTypes(businessId, items);
        List<RestockDigestDtos.RestockPrepItem> itemsOut = rows.stream()
                .map(s -> {
                    Item item = items.get(s.getItemId());
                    ItemType type = item == null ? null : types.get(item.getItemTypeId());
                    return new RestockDigestDtos.RestockPrepItem(
                            s.getItemId(),
                            item != null && item.getName() != null ? item.getName() : "",
                            item != null ? item.getSku() : null,
                            item != null ? item.getItemTypeId() : null,
                            type != null ? type.getLabel() : UNCATEGORISED,
                            s.getTarget(),
                            s.getOnHand(),
                            s.getPar(),
                            s.getSuggestedQty(),
                            s.getReasonCode(),
                            s.getEvidence(),
                            s.getConfidence());
                })
                .toList();
        return new RestockDigestDtos.RestockPrepResponse(
                run.getId(),
                branch.getName(),
                run.getRunDate(),
                run.getStatus(),
                run.getLineCount(),
                run.getEstTotal(),
                run.getCurrency(),
                itemsOut);
    }

    /** Latest run for the branch (404 when none exists). */
    @Transactional(readOnly = true)
    public RestockDigestDtos.RestockRunResponse getLatestForBranch(String businessId, String branchId) {
        requireBranch(businessId, branchId);
        RestockRun run = restockRunRepository.findFirstByBranchIdOrderByRunDateDescIdDesc(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No restock run yet"));
        Business business = requireBusiness(businessId);
        Branch branch = requireBranch(businessId, run.getBranchId());
        return toRunResponse(businessId, run, loadSuggestions(businessId, run.getId()), business, branch);
    }

    /**
     * Closing-sheet PDF for one group on a run. Filter by department, supplier,
     * and/or pad-only. Dismissed and snoozed lines are omitted so the sheet is
     * the orderable list.
     */
    @Transactional(readOnly = true)
    public RestockDigestPdfFile renderGroupPdf(
            String businessId,
            String runId,
            String departmentId,
            String supplierId,
            boolean padOnly
    ) {
        RestockRun run = requireRun(businessId, runId);
        Business business = requireBusiness(businessId);
        Branch branch = requireBranch(businessId, run.getBranchId());
        List<RestockDigestDtos.RestockSuggestionResponse> suggestions =
                loadSuggestions(businessId, run.getId());

        String deptFilter = blankToNull(departmentId);
        String supplierFilter = blankToNull(supplierId);
        List<RestockDigestDtos.RestockSuggestionResponse> lines = suggestions.stream()
                .filter(s -> !"dismissed".equals(s.status()) && !"snoozed".equals(s.status()))
                .filter(s -> matchesDepartment(deptFilter, s.itemTypeId()))
                .filter(s -> supplierFilter == null || supplierFilter.equals(s.supplierId()))
                .filter(s -> !padOnly || InventoryConstants.DIGEST_TARGET_PAD.equals(s.target()))
                .toList();
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No lines in this group");
        }

        String groupTitle = groupTitle(lines, deptFilter, supplierFilter, padOnly);
        String groupHint = lines.size() + (lines.size() == 1 ? " item" : " items");
        BigDecimal subtotal = BigDecimal.ZERO;
        List<RestockDigestPdfLine> pdfLines = new ArrayList<>();
        for (RestockDigestDtos.RestockSuggestionResponse s : lines) {
            BigDecimal qty = s.acceptedQty() != null ? s.acceptedQty() : s.suggestedQty();
            BigDecimal lineTotal = qty != null && s.unitCost() != null
                    ? qty.multiply(s.unitCost())
                    : null;
            if (lineTotal != null) {
                subtotal = subtotal.add(lineTotal);
            }
            pdfLines.add(new RestockDigestPdfLine(
                    s.itemName(),
                    s.itemSku(),
                    s.itemTypeName(),
                    s.supplierName(),
                    s.onHand(),
                    s.par(),
                    qty,
                    s.unitCost(),
                    lineTotal,
                    s.evidence()));
        }

        String currency = run.getCurrency() != null ? run.getCurrency() : "KES";
        RestockDigestPdfSnapshot snapshot = new RestockDigestPdfSnapshot(
                business.getName(),
                branch.getName(),
                run.getRunDate().format(PDF_DATE),
                groupTitle,
                groupHint,
                currency,
                pdfLines,
                subtotal.setScale(2, RoundingMode.HALF_UP));

        String filename = "restock-"
                + run.getRunDate()
                + "-"
                + slug(groupTitle)
                + ".pdf";
        try {
            return new RestockDigestPdfFile(filename, RestockDigestPdfRenderer.render(snapshot));
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Could not build this PDF", ex);
        }
    }

    private static boolean matchesDepartment(String deptFilter, String itemTypeId) {
        if (deptFilter == null) {
            return true;
        }
        if (isUncategorisedKey(deptFilter)) {
            return itemTypeId == null || itemTypeId.isBlank();
        }
        return deptFilter.equals(itemTypeId);
    }

    private static boolean isUncategorisedKey(String key) {
        return UNCATEGORISED_KEY.equalsIgnoreCase(key) || LEGACY_UNCATEGORISED_KEY.equals(key);
    }

    private static String groupTitle(
            List<RestockDigestDtos.RestockSuggestionResponse> lines,
            String departmentId,
            String supplierId,
            boolean padOnly
    ) {
        String dept = lines.get(0).itemTypeName();
        if (dept == null || dept.isBlank()) {
            dept = UNCATEGORISED;
        }
        if (padOnly) {
            return departmentId != null ? dept + " - Needs a supplier" : "Needs a supplier";
        }
        if (supplierId != null) {
            String supplier = lines.get(0).supplierName();
            if (supplier == null || supplier.isBlank()) {
                supplier = "Supplier";
            }
            return departmentId != null ? dept + " - " + supplier : supplier;
        }
        if (departmentId != null) {
            return dept;
        }
        return "Tonight's list";
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "group";
        }
        String s = value.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-)$", "");
        return s.isBlank() ? "group" : s;
    }

    // ---------------------------------------------------------------- accept flow

    /**
     * Accept pending suggestions into draft POs (supplier groups) + order pad lines.
     * Idempotent: re-accepting an already-accepted line is a no-op and reuses the
     * existing PO / pad link. PO-target lines require {@code canWritePo}; pad-target
     * lines require {@code canWritePad} — lines the caller may not touch are reported
     * in {@code skippedLines} instead of failing the whole accept.
     */
    @Transactional
    public AcceptRestockRunResponse acceptRun(
            String businessId,
            String runId,
            String userId,
            boolean canWritePo,
            boolean canWritePad,
            AcceptRestockRunRequest req
    ) {
        RestockRun run = requireRun(businessId, runId);
        Business business = requireBusiness(businessId);
        Branch branch = requireBranch(businessId, run.getBranchId());
        List<RestockSuggestion> all = restockSuggestionRepository.findByRunIdOrderBySuggestedQtyDescIdAsc(runId);

        if (InventoryConstants.DIGEST_RUN_EXPIRED.equals(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This list has expired — generate a new run");
        }

        Set<String> selectedIds = req.lineIds() == null || req.lineIds().isEmpty()
                ? all.stream().map(RestockSuggestion::getId).collect(Collectors.toSet())
                : new HashSet<>(req.lineIds());
        String mode = normalizeMode(req.mode());
        List<RestockSuggestion> pending = all.stream()
                .filter(s -> selectedIds.contains(s.getId()))
                .filter(s -> InventoryConstants.DIGEST_SUGGESTION_PENDING.equals(s.getStatus()))
                .filter(s -> modeMatches(mode, s.getTarget()))
                .toList();

        Map<String, String> itemNames = loadItemNames(businessId, pending);
        List<CreatedPurchaseOrderRef> createdPos = new ArrayList<>();
        List<SkippedAcceptLine> skipped = new ArrayList<>();
        List<RestockSuggestion> modified = new ArrayList<>();

        List<RestockSuggestion> poLines = pending.stream()
                .filter(s -> InventoryConstants.DIGEST_TARGET_PO.equals(s.getTarget()))
                .toList();
        if (!poLines.isEmpty()) {
            acceptPoGroups(
                    businessId, run, branch, userId, canWritePo, req,
                    poLines, itemNames, createdPos, skipped, modified);
        }

        List<RestockSuggestion> padLines = pending.stream()
                .filter(s -> InventoryConstants.DIGEST_TARGET_PAD.equals(s.getTarget()))
                .toList();
        int padCreated = 0;
        if (!padLines.isEmpty()) {
            padCreated = acceptPadLines(businessId, run, userId, canWritePad, req, padLines, itemNames, skipped, modified);
        }

        // Only move the run forward when a line actually landed — an accept where every
        // line was skipped (no permission, no cost) leaves the list untouched.
        if (!modified.isEmpty()) {
            restockSuggestionRepository.saveAll(modified);
            long pendingLeft = all.stream()
                    .filter(s -> InventoryConstants.DIGEST_SUGGESTION_PENDING.equals(s.getStatus()))
                    .count();
            run.setStatus(pendingLeft == 0
                    ? InventoryConstants.DIGEST_RUN_ACCEPTED
                    : InventoryConstants.DIGEST_RUN_PARTIALLY_ACCEPTED);
            restockRunRepository.save(run);
        }

        return new AcceptRestockRunResponse(
                toRunResponse(businessId, run, loadSuggestions(businessId, runId), business, branch),
                createdPos,
                padCreated,
                skipped);
    }

    private void acceptPoGroups(
            String businessId,
            RestockRun run,
            Branch branch,
            String userId,
            boolean canWritePo,
            AcceptRestockRunRequest req,
            List<RestockSuggestion> poLines,
            Map<String, String> itemNames,
            List<CreatedPurchaseOrderRef> createdPos,
            List<SkippedAcceptLine> skipped,
            List<RestockSuggestion> modified
    ) {
        // A po-target line without a supplier snapshot can't be grouped into a PO; report
        // it instead of letting groupingBy trip over the null key.
        Map<String, List<RestockSuggestion>> bySupplier = poLines.stream()
                .filter(s -> {
                    if (s.getSupplierId() != null && !s.getSupplierId().isBlank()) {
                        return true;
                    }
                    skipped.add(new SkippedAcceptLine(
                            s.getId(),
                            s.getItemId(),
                            itemNames.getOrDefault(s.getItemId(), ""),
                            "no supplier on this line — link a supplier or add it to the order pad"));
                    return false;
                })
                .collect(Collectors.groupingBy(
                        RestockSuggestion::getSupplierId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<RestockSuggestion>> entry : bySupplier.entrySet()) {
            String supplierId = entry.getKey();
            List<RestockSuggestion> group = entry.getValue();
            if (!canWritePo) {
                skipAll(group, itemNames, skipped, "requires purchasing permission");
                continue;
            }
            // Resolve orderable lines first so we never create an empty draft PO for a
            // group where every line is missing a cost or has a bad qty override.
            Map<RestockSuggestion, BigDecimal> orderable = new LinkedHashMap<>();
            for (RestockSuggestion s : group) {
                String itemName = itemNames.getOrDefault(s.getItemId(), "");
                BigDecimal qty = resolveAcceptQty(req, s);
                if (qty == null) {
                    skipped.add(new SkippedAcceptLine(
                            s.getId(), s.getItemId(), itemName, "quantity must be greater than zero"));
                    continue;
                }
                BigDecimal cost = s.getUnitCost();
                if (cost == null || cost.signum() <= 0) {
                    skipped.add(new SkippedAcceptLine(
                            s.getId(), s.getItemId(), itemName,
                            "missing unit cost — set a buying price or supplier cost"));
                    continue;
                }
                orderable.put(s, qty);
            }
            if (orderable.isEmpty()) {
                continue;
            }

            String poId = group.stream()
                    .map(RestockSuggestion::getPurchaseOrderId)
                    .filter(id -> id != null && !id.isBlank())
                    .findFirst()
                    .orElse(null);
            String poNumber;
            if (poId == null) {
                String notes = "Restock digest " + run.getRunDate() + " - " + branch.getName();
                PathAPurchaseOrderDetailResponse po = pathAPurchaseService.createPurchaseOrder(
                        businessId,
                        new CreatePathAPurchaseOrderRequest(supplierId, run.getBranchId(), null, null, notes));
                poId = po.id();
                poNumber = po.poNumber();
                markPoRestockSource(businessId, poId);
            } else {
                poNumber = loadPoNumber(businessId, poId);
            }
            int acceptedInGroup = 0;
            for (Map.Entry<RestockSuggestion, BigDecimal> line : orderable.entrySet()) {
                RestockSuggestion s = line.getKey();
                BigDecimal qty = line.getValue();
                pathAPurchaseService.addPurchaseOrderLine(
                        businessId,
                        poId,
                        new AddPathAPurchaseOrderLineRequest(s.getItemId(), qty, s.getUnitCost()));
                s.setStatus(InventoryConstants.DIGEST_SUGGESTION_ACCEPTED);
                s.setAcceptedQty(qty);
                s.setPurchaseOrderId(poId);
                modified.add(s);
                acceptedInGroup++;
            }
            if (acceptedInGroup > 0) {
                createdPos.add(new CreatedPurchaseOrderRef(
                        poId, poNumber, supplierId, loadSupplierName(businessId, supplierId), acceptedInGroup));
            }
        }
    }

    private int acceptPadLines(
            String businessId,
            RestockRun run,
            String userId,
            boolean canWritePad,
            AcceptRestockRunRequest req,
            List<RestockSuggestion> padLines,
            Map<String, String> itemNames,
            List<SkippedAcceptLine> skipped,
            List<RestockSuggestion> modified
    ) {
        if (!canWritePad) {
            skipAll(padLines, itemNames, skipped, "requires order pad permission");
            return 0;
        }
        // Keep suggestion → pad row aligned by building one ordered list of accepted
        // lines; lines with a bad qty override are skipped rather than shifting indexes.
        List<RestockSuggestion> accepted = new ArrayList<>();
        List<OrderPadItem> rows = new ArrayList<>();
        for (RestockSuggestion s : padLines) {
            BigDecimal qty = resolveAcceptQty(req, s);
            if (qty == null) {
                skipped.add(new SkippedAcceptLine(
                        s.getId(),
                        s.getItemId(),
                        itemNames.getOrDefault(s.getItemId(), ""),
                        "quantity must be greater than zero"));
                continue;
            }
            OrderPadItem row = new OrderPadItem();
            row.setBusinessId(businessId);
            row.setBranchId(run.getBranchId());
            row.setItemId(s.getItemId());
            row.setItemName(itemNames.getOrDefault(s.getItemId(), s.getItemId()));
            row.setQuantity(qty);
            row.setNote("Restock digest " + run.getRunDate());
            row.setOrdered(false);
            row.setCreatedBy(userId);
            accepted.add(s);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return 0;
        }
        List<OrderPadItem> saved = orderPadItemRepository.saveAll(rows);
        for (int i = 0; i < accepted.size(); i++) {
            RestockSuggestion s = accepted.get(i);
            s.setStatus(InventoryConstants.DIGEST_SUGGESTION_ACCEPTED);
            s.setAcceptedQty(saved.get(i).getQuantity());
            s.setOrderPadItemId(saved.get(i).getId());
            modified.add(s);
        }
        return saved.size();
    }

    private static void skipAll(
            List<RestockSuggestion> lines,
            Map<String, String> itemNames,
            List<SkippedAcceptLine> skipped,
            String reason
    ) {
        for (RestockSuggestion s : lines) {
            skipped.add(new SkippedAcceptLine(
                    s.getId(), s.getItemId(), itemNames.getOrDefault(s.getItemId(), ""), reason));
        }
    }

    /**
     * Accept quantity for a line, or {@code null} when it is not orderable. Returning
     * null (rather than throwing) keeps one bad override from rolling back the POs and
     * pad rows already created for the rest of the run — the line is reported in
     * {@code skippedLines} instead.
     */
    private BigDecimal resolveAcceptQty(AcceptRestockRunRequest req, RestockSuggestion s) {
        BigDecimal override = req.qtyOverrides() != null ? req.qtyOverrides().get(s.getId()) : null;
        BigDecimal qty = override != null ? override : s.getSuggestedQty();
        if (qty == null || qty.signum() <= 0) {
            return null;
        }
        return qty.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    private Map<String, String> loadItemNames(String businessId, List<RestockSuggestion> lines) {
        Set<String> itemIds = lines.stream()
                .map(RestockSuggestion::getItemId)
                .collect(Collectors.toSet());
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return loadItems(businessId, itemIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    String name = e.getValue().getName();
                    return name != null ? name : e.getKey();
                }));
    }

    private String loadSupplierName(String businessId, String supplierId) {
        return supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .map(Supplier::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("");
    }

    private String loadPoNumber(String businessId, String poId) {
        return purchaseOrderRepository.findByIdAndBusinessId(poId, businessId)
                .map(po -> {
                    String n = po.getPoNumber();
                    return n != null ? n : poId;
                })
                .orElse(poId);
    }

    private void markPoRestockSource(String businessId, String purchaseOrderId) {
        purchaseOrderRepository.findByIdAndBusinessId(purchaseOrderId, businessId)
                .ifPresent(entity -> {
                    entity.setSource(PurchasingConstants.PO_SOURCE_RESTOCK);
                    purchaseOrderRepository.save(entity);
                });
    }

    /** Dismiss a pending suggestion. Returns the refreshed run. */
    @Transactional
    public RestockDigestDtos.RestockRunResponse dismissSuggestion(String businessId, String suggestionId) {
        RestockSuggestion s = requireSuggestion(businessId, suggestionId);
        requirePending(s);
        requireActiveRun(businessId, s.getRunId());
        s.setStatus(InventoryConstants.DIGEST_SUGGESTION_DISMISSED);
        restockSuggestionRepository.save(s);
        return refreshRunResponse(businessId, s.getRunId());
    }

    /** Snooze a pending suggestion until run_date + days (default 1, max 30). */
    @Transactional
    public RestockDigestDtos.RestockRunResponse snoozeSuggestion(
            String businessId,
            String suggestionId,
            int days
    ) {
        RestockSuggestion s = requireSuggestion(businessId, suggestionId);
        requirePending(s);
        requireActiveRun(businessId, s.getRunId());
        int d = days <= 0 ? 1 : Math.min(days, 30);
        RestockRun run = requireRun(businessId, s.getRunId());
        s.setStatus(InventoryConstants.DIGEST_SUGGESTION_SNOOZED);
        s.setSnoozeUntil(run.getRunDate().plusDays(d));
        restockSuggestionRepository.save(s);
        return refreshRunResponse(businessId, s.getRunId());
    }

    private void requireActiveRun(String businessId, String runId) {
        RestockRun run = requireRun(businessId, runId);
        if (InventoryConstants.DIGEST_RUN_EXPIRED.equals(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This list has expired — generate a new run");
        }
    }

    private static void requirePending(RestockSuggestion s) {
        if (!InventoryConstants.DIGEST_SUGGESTION_PENDING.equals(s.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending suggestions can be changed");
        }
    }

    private RestockSuggestion requireSuggestion(String businessId, String suggestionId) {
        return restockSuggestionRepository.findById(suggestionId)
                .filter(s -> businessId.equals(s.getBusinessId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suggestion not found"));
    }

    private RestockRun requireRun(String businessId, String runId) {
        return restockRunRepository.findByIdAndBusinessId(runId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    private RestockDigestDtos.RestockRunResponse refreshRunResponse(String businessId, String runId) {
        RestockRun run = requireRun(businessId, runId);
        Business business = requireBusiness(businessId);
        Branch branch = requireBranch(businessId, run.getBranchId());
        return toRunResponse(businessId, run, loadSuggestions(businessId, runId), business, branch);
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "all";
        }
        String m = mode.trim().toLowerCase();
        return "po".equals(m) || "pad".equals(m) ? m : "all";
    }

    private static boolean modeMatches(String mode, String target) {
        return switch (mode) {
            case "po" -> InventoryConstants.DIGEST_TARGET_PO.equals(target);
            case "pad" -> InventoryConstants.DIGEST_TARGET_PAD.equals(target);
            default -> true;
        };
    }

    // ------------------------------------------------------------------ internals

    private Map<String, RestockDigestFormula.VelocityInput> loadVelocity(
            String businessId,
            String branchId,
            LocalDate windowEnd,
            LocalDate last7From,
            LocalDate last30From
    ) {
        Map<String, RestockDigestFormula.VelocityInput> out = new LinkedHashMap<>();
        for (DigestVelocityRow row : mvSalesDailyRepository.digestVelocity(
                businessId, branchId, windowEnd, last7From, last30From)) {
            out.put(row.getItemId(), new RestockDigestFormula.VelocityInput(
                    row.getLast7Qty(), row.getLast30Qty(), row.getDaysWithSales()));
        }
        // OLTP gap-fill for tenants whose MV hasn't refreshed yet.
        for (DigestVelocityRow row : mvSalesDailyRepository.digestVelocityOltp(
                businessId, branchId, windowEnd, last7From, last30From)) {
            out.putIfAbsent(row.getItemId(), new RestockDigestFormula.VelocityInput(
                    row.getLast7Qty(), row.getLast30Qty(), row.getDaysWithSales()));
        }
        return out;
    }

    private Map<String, BigDecimal> loadInbound(String businessId, String branchId) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (PurchaseOrderLineRepository.OpenInboundRow row :
                purchaseOrderLineRepository.sumOpenInboundByItem(businessId, branchId)) {
            out.merge(row.getItemId(), qtyOrZero(row.getQty()), BigDecimal::add);
        }
        for (OrderPadItemRepository.OpenPadQtyRow row :
                orderPadItemRepository.sumOpenPadQtyByItem(businessId, branchId)) {
            out.merge(row.getItemId(), qtyOrZero(row.getQty()), BigDecimal::add);
        }
        return out;
    }

    private Map<String, ItemLinkRow> loadPrimaryLinks(String businessId, Set<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ItemLinkRow> out = new HashMap<>();
        // Skip the system "Unassigned (migrate)" supplier: items only linked there have
        // no real supplier yet and belong in the order-pad (target=pad) section.
        for (ItemLinkRow row : supplierProductRepository.listActiveLinksForItems(
                businessId, itemIds, SupplierCodes.SYSTEM_UNASSIGNED)) {
            out.putIfAbsent(row.getItemId(), row); // first row per item = primary (query orders primary first)
        }
        return out;
    }

    private Set<String> loadSnoozedItemIds(String businessId, String branchId, LocalDate runDate) {
        return restockSuggestionRepository
                .findByBusinessIdAndBranchIdAndStatusAndSnoozeUntilGreaterThanEqual(
                        businessId,
                        branchId,
                        InventoryConstants.DIGEST_SUGGESTION_SNOOZED,
                        runDate,
                        InventoryConstants.DIGEST_RUN_EXPIRED)
                .stream()
                .map(RestockSuggestion::getItemId)
                .collect(Collectors.toSet());
    }

    /**
     * Learned par multiplier per item from accepted-qty history. Only items with at
     * least {@link #MIN_ACCEPT_HISTORY} accepted lines get a bias; the mean ratio is
     * clamped to [0.6, 1.4] so one-off over/under-accepting can't swing par wildly.
     */
    private Map<String, BigDecimal> loadParBiases(String businessId, String branchId) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (RestockSuggestionRepository.AcceptedRatioRow row :
                restockSuggestionRepository.acceptedRatioByItem(
                        businessId, branchId, InventoryConstants.DIGEST_SUGGESTION_ACCEPTED)) {
            if (row.getCount() < MIN_ACCEPT_HISTORY || row.getRatio() == null || row.getRatio() <= 0) {
                continue;
            }
            double clamped = Math.min(Math.max(row.getRatio(), 0.6), 1.4);
            if (Math.abs(clamped - 1.0) < 0.01) {
                continue; // nothing meaningful to learn
            }
            out.put(row.getItemId(), BigDecimal.valueOf(clamped));
        }
        return out;
    }

    private Map<String, Item> loadItems(String businessId, Set<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId).stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));
    }

    /**
     * Branch display on-hand with package-variant pool resolution (mirrors Activity overlay).
     * Phase-4 expiry awareness: batches expiring before {@code usableFrom} are excluded,
     * so stock that will spoil inside the cover window doesn't mask a reorder need.
     */
    private Map<String, BigDecimal> resolveDisplayStockByItemId(
            String businessId,
            String branchId,
            Map<String, Item> itemsById,
            LocalDate usableFrom
    ) {
        if (itemsById.isEmpty()) {
            return Map.of();
        }
        Set<String> poolIds = new HashSet<>();
        for (Item item : itemsById.values()) {
            poolIds.addAll(packageVariantStockResolver.branchStockPoolItemIds(businessId, item));
        }
        Map<String, BigDecimal> rawByItemId = new HashMap<>();
        if (!poolIds.isEmpty()) {
            for (Object[] row : inventoryBatchRepository.sumUsableQuantityRemainingForItemsAtBranch(
                    businessId, branchId, InventoryConstants.BATCH_STATUS_ACTIVE, poolIds, usableFrom)) {
                rawByItemId.put((String) row[0], (BigDecimal) row[1]);
            }
        }
        Map<String, BigDecimal> display = new HashMap<>();
        for (Item item : itemsById.values()) {
            BigDecimal holder = packageVariantStockResolver.sumPoolStock(item, rawByItemId);
            display.put(item.getId(), qtyOrZero(packageVariantStockResolver.displayStockQty(item, holder)));
        }
        return display;
    }

    private boolean isCandidateEligible(Item item) {
        if (!item.isActive() || item.getDeletedAt() != null) {
            return false;
        }
        // Stocked rows hold batch on-hand; package / shared-stock variants sell from a parent pool.
        return item.isStocked() || packageVariantStockResolver.sharesParentStock(item);
    }

    private RestockSuggestion buildSuggestion(
            RestockRun run,
            Item item,
            ItemLinkRow link,
            BigDecimal onHand,
            BigDecimal inbound,
            BigDecimal reorderLevel,
            RestockDigestFormula.Computed computed
    ) {
        RestockSuggestion row = new RestockSuggestion();
        row.setRunId(run.getId());
        row.setBusinessId(run.getBusinessId());
        row.setBranchId(run.getBranchId());
        row.setItemId(item.getId());
        row.setTarget(link != null
                ? InventoryConstants.DIGEST_TARGET_PO
                : InventoryConstants.DIGEST_TARGET_PAD);
        row.setOnHand(onHand);
        row.setInbound(inbound);
        row.setReorderLevel(reorderLevel);
        row.setPar(computed.par());
        row.setSuggestedQty(computed.suggestedQty());
        row.setReasonCode(computed.reasonCode());
        row.setEvidence(computed.evidence());
        row.setConfidence(computed.confidence());
        row.setStatus(InventoryConstants.DIGEST_SUGGESTION_PENDING);
        if (link != null) {
            row.setSupplierId(link.getSupplierId());
            row.setUnitCost(resolveUnitCost(link, item));
            row.setPackSize(link.getPackSize());
            row.setLeadTimeDays(link.getLeadTimeDays());
        } else {
            row.setUnitCost(item.getBuyingPrice());
        }
        return row;
    }

    private static BigDecimal resolveUnitCost(ItemLinkRow link, Item item) {
        if (link.getDefaultCost() != null && link.getDefaultCost().signum() > 0) {
            return link.getDefaultCost();
        }
        return item.getBuyingPrice();
    }

    private void applyCounts(RestockRun run, List<RestockSuggestion> suggestions) {
        int po = 0;
        int pad = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (RestockSuggestion s : suggestions) {
            if (InventoryConstants.DIGEST_TARGET_PO.equals(s.getTarget())) {
                po++;
            } else {
                pad++;
            }
            if (s.getSuggestedQty() != null && s.getUnitCost() != null) {
                total = total.add(s.getSuggestedQty().multiply(s.getUnitCost()));
            }
        }
        run.setLineCount(suggestions.size());
        run.setPoLineCount(po);
        run.setPadLineCount(pad);
        run.setEstTotal(total.setScale(4, RoundingMode.HALF_UP));
    }

    private List<RestockDigestDtos.RestockSuggestionResponse> loadSuggestions(String businessId, String runId) {
        List<RestockSuggestion> rows = restockSuggestionRepository.findByRunIdOrderBySuggestedQtyDescIdAsc(runId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> itemIds = rows.stream().map(RestockSuggestion::getItemId).collect(Collectors.toSet());
        Map<String, Item> items = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));
        Map<String, ItemType> types = loadItemTypes(businessId, items);
        Set<String> supplierIds = rows.stream()
                .map(RestockSuggestion::getSupplierId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> supplierNames = supplierIds.isEmpty()
                ? Map.of()
                : supplierRepository.findAllById(supplierIds).stream()
                        .collect(Collectors.toMap(Supplier::getId, Supplier::getName, (a, b) -> a));
        return rows.stream()
                .map(r -> {
                    Item item = items.get(r.getItemId());
                    ItemType type = item == null || item.getItemTypeId() == null
                            ? null
                            : types.get(item.getItemTypeId());
                    return toSuggestionResponse(
                            r, item, type, supplierName(supplierNames, r.getSupplierId()));
                })
                .toList();
    }

    private Map<String, ItemType> loadItemTypes(String businessId, Map<String, Item> items) {
        Set<String> typeIds = items.values().stream()
                .map(Item::getItemTypeId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (typeIds.isEmpty()) {
            return Map.of();
        }
        return itemTypeRepository.findAllById(typeIds).stream()
                .filter(t -> businessId.equals(t.getBusinessId()))
                .collect(Collectors.toMap(ItemType::getId, t -> t, (a, b) -> a));
    }

    private static String supplierName(Map<String, String> names, String supplierId) {
        if (supplierId == null || supplierId.isBlank()) {
            return null;
        }
        return names.get(supplierId);
    }

    private RestockDigestDtos.RestockSuggestionResponse toSuggestionResponse(
            RestockSuggestion r,
            Item item,
            ItemType itemType,
            String supplierName
    ) {
        return new RestockDigestDtos.RestockSuggestionResponse(
                r.getId(),
                r.getRunId(),
                r.getItemId(),
                item != null ? item.getName() : "",
                item != null ? item.getSku() : null,
                item != null ? item.getItemTypeId() : null,
                itemType != null && itemType.getLabel() != null && !itemType.getLabel().isBlank()
                        ? itemType.getLabel()
                        : UNCATEGORISED,
                r.getSupplierId(),
                supplierName,
                r.getTarget(),
                r.getOnHand(),
                r.getInbound(),
                r.getReorderLevel(),
                r.getPar(),
                r.getSuggestedQty(),
                r.getAcceptedQty(),
                r.getUnitCost(),
                r.getPackSize(),
                r.getLeadTimeDays(),
                r.getReasonCode(),
                r.getEvidence(),
                r.getConfidence(),
                r.getStatus(),
                r.getSnoozeUntil(),
                r.getPurchaseOrderId(),
                r.getOrderPadItemId(),
                r.getCreatedAt());
    }

    private RestockDigestDtos.RestockRunResponse toRunResponse(
            String businessId,
            RestockRun run,
            List<RestockDigestDtos.RestockSuggestionResponse> suggestions,
            Business business,
            Branch branch
    ) {
        return new RestockDigestDtos.RestockRunResponse(
                run.getId(),
                businessId,
                run.getBranchId(),
                branch.getName(),
                run.getRunDate(),
                run.getGeneratedAt(),
                run.getStatus(),
                run.getLineCount(),
                run.getPoLineCount(),
                run.getPadLineCount(),
                run.getEstTotal(),
                run.getCurrency(),
                run.getTrigger(),
                run.getErrorNote(),
                suggestions);
    }

    private Map<String, String> loadBranchNames(String businessId) {
        return branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId).stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName, (a, b) -> a));
    }

    private Branch requireBranch(String businessId, String branchId) {
        return branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private Business requireBusiness(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
    }

    private static ZoneId businessZone(Business business) {
        String tz = business.getTimezone();
        if (tz == null || tz.isBlank()) {
            return ZoneId.of("Africa/Nairobi");
        }
        try {
            return ZoneId.of(tz);
        } catch (Exception ex) {
            return ZoneId.of("Africa/Nairobi");
        }
    }

    private static String normalizeCurrency(Business business) {
        String c = business.getCurrency();
        return c != null && !c.isBlank() ? c.trim() : "KES";
    }

    private static String normalizeTrigger(String trigger) {
        if (trigger == null || trigger.isBlank()) {
            return InventoryConstants.DIGEST_TRIGGER_MANUAL;
        }
        String t = trigger.trim().toLowerCase();
        if (InventoryConstants.DIGEST_TRIGGER_SCHEDULED.equals(t)) {
            return t;
        }
        return InventoryConstants.DIGEST_TRIGGER_MANUAL;
    }

    private static BigDecimal qtyOrZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP)
                : v.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    /** Convenience for the Phase-2 scheduler: resolve the business-local run date. */
    public LocalDate resolveRunDate(String businessId) {
        return LocalDate.now(businessZone(requireBusiness(businessId)));
    }
}
