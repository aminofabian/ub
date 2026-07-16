package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.api.dto.AdjustItemCostRequest;
import zelisline.ub.inventory.api.dto.BulkAdjustItemCostRequest;
import zelisline.ub.inventory.api.dto.BulkAdjustItemCostResponse;
import zelisline.ub.inventory.api.dto.CostIssueRowResponse;
import zelisline.ub.inventory.api.dto.CostIssuesResponse;
import zelisline.ub.inventory.repository.CostAuditRepository;
import zelisline.ub.inventory.repository.CostAuditRepository.CostIssueRow;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Finds and corrects items with abnormal cost (missing/zero, at-or-above sell price, thin
 * margin, or exaggerated/high margin). Correcting an item rewrites active batch unit costs
 * (the COGS driver) and the item's reference {@code buying_price}, and can optionally set the
 * selling price — all audited.
 */
@Service
@RequiredArgsConstructor
public class CostAuditService {

    private static final int MONEY_SCALE = 2;
    private static final int COST_SCALE = 4;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DEFAULT_THIN_MARGIN_PCT = new BigDecimal("5");
    private static final BigDecimal DEFAULT_HIGH_MARGIN_PCT = new BigDecimal("50");
    private static final int MAX_ROWS = 500;

    private final CostAuditRepository costAuditRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final ItemRepository itemRepository;
    private final PricingService pricingService;
    private final BranchRepository branchRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public CostIssuesResponse listCostIssues(
            String businessId,
            String branchId,
            BigDecimal thinMarginPct,
            BigDecimal highMarginPct
    ) {
        String brId = blankToNull(branchId);
        if (brId != null) {
            requireBranch(businessId, brId);
        }
        BigDecimal thinPct = clampMarginPct(thinMarginPct, DEFAULT_THIN_MARGIN_PCT);
        BigDecimal highPct = clampMarginPct(highMarginPct, DEFAULT_HIGH_MARGIN_PCT);
        if (highPct.compareTo(thinPct) <= 0) {
            highPct = DEFAULT_HIGH_MARGIN_PCT;
            if (highPct.compareTo(thinPct) <= 0) {
                highPct = HUNDRED;
            }
        }
        BigDecimal thinFraction = thinPct.divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal highFraction = highPct.divide(HUNDRED, 6, RoundingMode.HALF_UP);

        List<CostIssueRow> rows = costAuditRepository.findCostIssues(
                businessId, brId, thinFraction, highFraction, MAX_ROWS);
        List<CostIssueRowResponse> items = new ArrayList<>(rows.size());
        int zeroCount = 0;
        int lossCount = 0;
        int thinCount = 0;
        int highCount = 0;
        for (CostIssueRow row : rows) {
            CostIssueRowResponse mapped = buildRow(
                    row.getItemId(),
                    composeDisplayName(row.getName(), row.getVariantName(), row.getSize()),
                    row.getSku(),
                    row.getUnitType(),
                    row.getCurrentStock(),
                    row.getActiveQty(),
                    row.getActiveBatchCount() == null ? 0L : row.getActiveBatchCount(),
                    row.getBatchWac(),
                    row.getBuyingPrice(),
                    row.getEffectiveCost(),
                    row.getSellPrice(),
                    thinPct,
                    highPct
            );
            switch (mapped.primaryIssue()) {
                case "zero_cost" -> zeroCount++;
                case "sells_at_loss" -> lossCount++;
                case "thin_margin" -> thinCount++;
                case "high_margin" -> highCount++;
                default -> { /* no-op */ }
            }
            items.add(mapped);
        }
        return new CostIssuesResponse(
                brId, thinPct, highPct, items.size(), zeroCount, lossCount, thinCount, highCount, items);
    }

    @Transactional
    public CostIssueRowResponse adjustCost(
            String businessId,
            String itemId,
            AdjustItemCostRequest req,
            String actorUserId
    ) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));

        BigDecimal newCost = req.unitCost();
        if (newCost == null || newCost.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unit cost must be greater than zero");
        }
        BigDecimal cost4 = newCost.setScale(COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal cost2 = newCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        String brId = blankToNull(req.branchId());
        if (brId != null) {
            requireBranch(businessId, brId);
        }

        BigDecimal oldBuyingPrice = item.getBuyingPrice();

        // 1. Rewrite the corrected cost across active on-hand batches (drives future COGS).
        List<InventoryBatch> batches =
                inventoryBatchRepository.findActiveBatchesForCostRewrite(businessId, itemId, brId);
        BigDecimal activeQty = BigDecimal.ZERO;
        int batchesUpdated = 0;
        for (InventoryBatch batch : batches) {
            activeQty = activeQty.add(nz(batch.getQuantityRemaining()));
            if (batch.getUnitCost() == null || batch.getUnitCost().compareTo(cost4) != 0) {
                batch.setUnitCost(cost4);
                inventoryBatchRepository.save(batch);
                batchesUpdated++;
            }
        }

        // 2. Item reference cost.
        item.setBuyingPrice(cost2);

        // 3. Optional selling price (keeps bundle_price + open selling price aligned).
        BigDecimal oldSellPrice = null;
        BigDecimal newSellPrice = req.sellPrice();
        boolean sellChanged = false;
        if (newSellPrice != null && newSellPrice.signum() > 0) {
            oldSellPrice = pricingService.getCurrentOpenSellingPrice(businessId, itemId, null);
            BigDecimal sell2 = newSellPrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            item.setBundlePrice(sell2);
            itemRepository.save(item);
            pricingService.syncSellingPriceFromBundle(businessId, itemId, sell2, actorUserId);
            sellChanged = true;
        } else {
            itemRepository.save(item);
        }

        publishAdjustAudit(businessId, brId, item, actorUserId, oldBuyingPrice, cost2,
                batchesUpdated, batches.size(), oldSellPrice, sellChanged ? item.getBundlePrice() : null,
                req.reason());

        // 4. Recompute the row so the client can update/remove it in place.
        BigDecimal effectiveCost = batches.isEmpty() ? cost2 : cost4;
        BigDecimal batchWac = batches.isEmpty() ? null : cost4;
        BigDecimal resolvedSell = pricingService.getCurrentOpenSellingPrice(businessId, itemId, brId);
        return buildRow(
                item.getId(),
                composeDisplayName(item.getName(), item.getVariantName(), item.getSize()),
                item.getSku(),
                item.getUnitType(),
                item.getCurrentStock(),
                activeQty,
                (long) batches.size(),
                batchWac,
                item.getBuyingPrice(),
                effectiveCost,
                resolvedSell,
                DEFAULT_THIN_MARGIN_PCT,
                DEFAULT_HIGH_MARGIN_PCT
        );
    }

    /**
     * Keep each item's sell price fixed and set unit cost so margin becomes {@code marginPct}.
     * Items without a usable sell price are skipped.
     */
    @Transactional
    public BulkAdjustItemCostResponse bulkAdjustByMargin(
            String businessId,
            BulkAdjustItemCostRequest req,
            String actorUserId
    ) {
        BigDecimal marginPct = req.marginPct();
        if (marginPct == null
                || marginPct.signum() < 0
                || marginPct.compareTo(new BigDecimal("99.99")) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Margin must be between 0 and 99.99");
        }

        Set<String> uniqueIds = new LinkedHashSet<>();
        for (String raw : req.itemIds()) {
            String id = blankToNull(raw);
            if (id != null) {
                uniqueIds.add(id);
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one item id is required");
        }
        if (uniqueIds.size() > MAX_ROWS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "At most " + MAX_ROWS + " items can be adjusted at once");
        }

        String brId = blankToNull(req.branchId());
        if (brId != null) {
            requireBranch(businessId, brId);
        }

        Map<String, BigDecimal> sellByItem =
                pricingService.getCurrentOpenSellingPricesForItems(businessId, brId, uniqueIds);

        BigDecimal keepFraction = BigDecimal.ONE.subtract(
                marginPct.divide(HUNDRED, 8, RoundingMode.HALF_UP));

        List<CostIssueRowResponse> updated = new ArrayList<>();
        List<BulkAdjustItemCostResponse.SkippedItem> skipped = new ArrayList<>();
        String reason = blankToNull(req.reason());
        if (reason == null) {
            reason = "Bulk margin adjust to " + marginPct.stripTrailingZeros().toPlainString() + "%";
        }

        for (String itemId : uniqueIds) {
            BigDecimal sell = sellByItem.get(itemId);
            if (sell == null || sell.signum() <= 0) {
                skipped.add(new BulkAdjustItemCostResponse.SkippedItem(itemId, "No sell price"));
                continue;
            }
            BigDecimal unitCost = sell.multiply(keepFraction).setScale(COST_SCALE, RoundingMode.HALF_UP);
            if (unitCost.signum() <= 0) {
                skipped.add(new BulkAdjustItemCostResponse.SkippedItem(
                        itemId, "Computed unit cost must be greater than zero"));
                continue;
            }
            try {
                CostIssueRowResponse row = adjustCost(
                        businessId,
                        itemId,
                        new AdjustItemCostRequest(unitCost, null, brId, reason),
                        actorUserId);
                updated.add(row);
            } catch (ResponseStatusException ex) {
                String msg = ex.getReason() != null ? ex.getReason() : ex.getMessage();
                skipped.add(new BulkAdjustItemCostResponse.SkippedItem(
                        itemId, msg != null ? msg : "Failed to adjust cost"));
            }
        }

        return new BulkAdjustItemCostResponse(updated, skipped);
    }

    private CostIssueRowResponse buildRow(
            String itemId,
            String name,
            String sku,
            String unitType,
            BigDecimal currentStock,
            BigDecimal activeQty,
            long activeBatchCount,
            BigDecimal batchWac,
            BigDecimal buyingPrice,
            BigDecimal effectiveCost,
            BigDecimal sellPrice,
            BigDecimal thinMarginPct,
            BigDecimal highMarginPct
    ) {
        BigDecimal cost = effectiveCost;
        BigDecimal sell = sellPrice;
        boolean hasSell = sell != null && sell.signum() > 0;
        boolean zeroCost = cost == null || cost.signum() <= 0;
        boolean sellsAtLoss = !zeroCost && hasSell && cost.compareTo(sell) >= 0;

        BigDecimal marginPct = null;
        if (hasSell && cost != null) {
            marginPct = sell.subtract(cost)
                    .multiply(HUNDRED)
                    .divide(sell, MONEY_SCALE, RoundingMode.HALF_UP);
        }
        boolean thinMargin = !zeroCost
                && !sellsAtLoss
                && hasSell
                && cost.signum() > 0
                && marginPct != null
                && marginPct.compareTo(thinMarginPct) < 0;
        boolean highMargin = !zeroCost
                && !sellsAtLoss
                && !thinMargin
                && hasSell
                && cost.signum() > 0
                && marginPct != null
                && marginPct.compareTo(highMarginPct) > 0;

        String primaryIssue;
        if (zeroCost) {
            primaryIssue = "zero_cost";
        } else if (sellsAtLoss) {
            primaryIssue = "sells_at_loss";
        } else if (thinMargin) {
            primaryIssue = "thin_margin";
        } else if (highMargin) {
            primaryIssue = "high_margin";
        } else {
            primaryIssue = "ok";
        }
        String costSource = batchWac != null
                ? "batch"
                : (buyingPrice != null && buyingPrice.signum() > 0 ? "reference" : "none");

        return new CostIssueRowResponse(
                itemId,
                name,
                sku,
                unitType,
                scaleOrNull(currentStock, COST_SCALE),
                scaleOrNull(activeQty, COST_SCALE),
                activeBatchCount,
                scaleOrNull(effectiveCost, MONEY_SCALE),
                scaleOrNull(batchWac, COST_SCALE),
                scaleOrNull(buyingPrice, MONEY_SCALE),
                scaleOrNull(sellPrice, MONEY_SCALE),
                marginPct,
                costSource,
                primaryIssue,
                zeroCost,
                sellsAtLoss,
                thinMargin,
                highMargin
        );
    }

    private void publishAdjustAudit(
            String businessId,
            String branchId,
            Item item,
            String actorUserId,
            BigDecimal oldBuyingPrice,
            BigDecimal newCost,
            int batchesUpdated,
            int batchesConsidered,
            BigDecimal oldSellPrice,
            BigDecimal newSellPrice,
            String reason
    ) {
        Map<String, Object> oldState = new LinkedHashMap<>();
        oldState.put("buyingPrice", oldBuyingPrice == null ? null : oldBuyingPrice.toPlainString());
        if (oldSellPrice != null) {
            oldState.put("sellPrice", oldSellPrice.toPlainString());
        }

        Map<String, Object> newState = new LinkedHashMap<>();
        newState.put("unitCost", newCost.toPlainString());
        newState.put("batchesUpdated", batchesUpdated);
        newState.put("activeBatches", batchesConsidered);
        if (newSellPrice != null) {
            newState.put("sellPrice", newSellPrice.toPlainString());
        }
        if (branchId != null) {
            newState.put("branchId", branchId);
        }

        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.INVENTORY, AuditEventTypes.ITEM_COST_ADJUSTED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(branchId)
                .actor(actorUserId, actorUserId != null && !actorUserId.isBlank()
                        ? AuditEventActorType.USER
                        : AuditEventActorType.SYSTEM)
                .target("item", item.getId())
                .targetLabel(item.getName())
                .oldState(oldState)
                .newState(newState)
                .reason(reason)
                .source("cost_audit")
                .build());
    }

    private void requireBranch(String businessId, String branchId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private static BigDecimal clampMarginPct(BigDecimal marginPct, BigDecimal defaultPct) {
        if (marginPct == null) {
            return defaultPct;
        }
        if (marginPct.signum() < 0) {
            return BigDecimal.ZERO;
        }
        if (marginPct.compareTo(HUNDRED) > 0) {
            return HUNDRED;
        }
        return marginPct;
    }

    /**
     * Combines a variant's parent name with its distinguishing descriptor (variant name, falling
     * back to size) so the list reads "Amara Macademia 200ml" rather than just "Amara". Skips the
     * suffix when the base name already contains it.
     */
    private static String composeDisplayName(String name, String variantName, String size) {
        String base = blankToNull(name);
        String suffix = blankToNull(variantName);
        if (suffix == null) {
            suffix = blankToNull(size);
        }
        if (base == null) {
            return suffix == null ? "" : suffix;
        }
        if (suffix == null) {
            return base;
        }
        if (base.toLowerCase(java.util.Locale.ROOT).contains(suffix.toLowerCase(java.util.Locale.ROOT))) {
            return base;
        }
        return base + " " + suffix;
    }

    private static BigDecimal scaleOrNull(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
