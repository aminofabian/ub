package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.RestockDigestDtos;
import zelisline.ub.inventory.domain.RestockRun;
import zelisline.ub.inventory.domain.RestockSuggestion;
import zelisline.ub.inventory.repository.OrderPadItemRepository;
import zelisline.ub.inventory.repository.RestockRunRepository;
import zelisline.ub.inventory.repository.RestockSuggestionRepository;
import zelisline.ub.inventory.repository.StockTakeLineRepository;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.reporting.repository.MvSalesDailyRepository;
import zelisline.ub.reporting.repository.MvSalesDailyRepository.DigestVelocityRow;
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

    private final RestockRunRepository restockRunRepository;
    private final RestockSuggestionRepository restockSuggestionRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final ItemRepository itemRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final MvSalesDailyRepository mvSalesDailyRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final OrderPadItemRepository orderPadItemRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierRepository supplierRepository;
    private final StockTakeLineRepository stockTakeLineRepository;

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
            return toRunResponse(businessId, existing, loadSuggestions(existing.getId()), business, branch);
        }

        LocalDate today = LocalDate.now(businessZone(business));
        LocalDate last7From = today.minusDays(VELOCITY_7D - 1);
        LocalDate last30From = today.minusDays(VELOCITY_DAYS - 1);

        // Candidate discovery — one window query per branch (MV + OLTP gap-fill).
        Map<String, RestockDigestFormula.VelocityInput> velocityByItem = loadVelocity(
                businessId, branchId, today, last7From, last30From);
        // Stock-out proxy: items counted at zero in a stock take / daily audit in window.
        Set<String> stockOutItemIds = new HashSet<>(
                stockTakeLineRepository.findCountedZeroItemIds(businessId, branchId, last30From, today));
        Set<String> candidateIds = new LinkedHashSet<>(velocityByItem.keySet());
        candidateIds.addAll(stockOutItemIds);

        // Batch-load everything once, then compute lines.
        Map<String, Item> itemsById = loadItems(businessId, candidateIds);
        Map<String, BigDecimal> onHandByItem = resolveDisplayStockByItemId(businessId, branchId, itemsById);
        Map<String, BigDecimal> inboundByItem = loadInbound(businessId, branchId);
        Map<String, ItemLinkRow> linkByItem = loadPrimaryLinks(businessId, candidateIds);
        Set<String> snoozedItemIds = loadSnoozedItemIds(businessId, branchId, effectiveDate);

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
                    snoozed).orElse(null);
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
            return toRunResponse(businessId, winner, loadSuggestions(winner.getId()), business, branch);
        }
        return toRunResponse(businessId, run, loadSuggestions(run.getId()), business, branch);
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
        return toRunResponse(businessId, run, loadSuggestions(run.getId()), business, branch);
    }

    @Transactional(readOnly = true)
    public RestockDigestDtos.RestockRunResponse getLatestForBranch(String businessId, String branchId) {
        requireBranch(businessId, branchId);
        RestockRun run = restockRunRepository.findFirstByBranchIdOrderByRunDateDescIdDesc(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No restock run yet"));
        Business business = requireBusiness(businessId);
        Branch branch = requireBranch(businessId, run.getBranchId());
        return toRunResponse(businessId, run, loadSuggestions(run.getId()), business, branch);
    }

    // ------------------------------------------------------------------ internals

    private Map<String, RestockDigestFormula.VelocityInput> loadVelocity(
            String businessId,
            String branchId,
            LocalDate today,
            LocalDate last7From,
            LocalDate last30From
    ) {
        Map<String, RestockDigestFormula.VelocityInput> out = new LinkedHashMap<>();
        for (DigestVelocityRow row : mvSalesDailyRepository.digestVelocity(
                businessId, branchId, today, last7From, last30From)) {
            out.put(row.getItemId(), new RestockDigestFormula.VelocityInput(
                    row.getLast7Qty(), row.getLast30Qty(), row.getDaysWithSales()));
        }
        // OLTP gap-fill for tenants whose MV hasn't refreshed yet.
        for (DigestVelocityRow row : mvSalesDailyRepository.digestVelocityOltp(
                businessId, branchId, today, last7From, last30From)) {
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
        for (ItemLinkRow row : supplierProductRepository.listActiveLinksForItems(businessId, itemIds)) {
            out.putIfAbsent(row.getItemId(), row); // first row per item = primary (query orders primary first)
        }
        return out;
    }

    private Set<String> loadSnoozedItemIds(String businessId, String branchId, LocalDate runDate) {
        return restockSuggestionRepository
                .findByBusinessIdAndBranchIdAndStatusAndSnoozeUntilGreaterThanEqual(
                        businessId, branchId, InventoryConstants.DIGEST_SUGGESTION_SNOOZED, runDate)
                .stream()
                .map(RestockSuggestion::getItemId)
                .collect(Collectors.toSet());
    }

    private Map<String, Item> loadItems(String businessId, Set<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId).stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));
    }

    /** Branch display on-hand with package-variant pool resolution (mirrors Activity overlay). */
    private Map<String, BigDecimal> resolveDisplayStockByItemId(
            String businessId,
            String branchId,
            Map<String, Item> itemsById
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
            for (Object[] row : inventoryBatchRepository.sumQuantityRemainingForItemsAtBranch(
                    businessId, branchId, InventoryConstants.BATCH_STATUS_ACTIVE, poolIds)) {
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

    private List<RestockDigestDtos.RestockSuggestionResponse> loadSuggestions(String runId) {
        List<RestockSuggestion> rows = restockSuggestionRepository.findByRunIdOrderBySuggestedQtyDescIdAsc(runId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> itemIds = rows.stream().map(RestockSuggestion::getItemId).collect(Collectors.toSet());
        Map<String, Item> items = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));
        Set<String> supplierIds = rows.stream()
                .map(RestockSuggestion::getSupplierId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> supplierNames = supplierIds.isEmpty()
                ? Map.of()
                : supplierRepository.findAllById(supplierIds).stream()
                        .collect(Collectors.toMap(Supplier::getId, Supplier::getName, (a, b) -> a));
        return rows.stream()
                .map(r -> toSuggestionResponse(r, items.get(r.getItemId()), supplierNames.get(r.getSupplierId())))
                .toList();
    }

    private RestockDigestDtos.RestockSuggestionResponse toSuggestionResponse(
            RestockSuggestion r,
            Item item,
            String supplierName
    ) {
        return new RestockDigestDtos.RestockSuggestionResponse(
                r.getId(),
                r.getRunId(),
                r.getItemId(),
                item != null ? item.getName() : "",
                item != null ? item.getSku() : null,
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
