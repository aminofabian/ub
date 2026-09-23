package zelisline.ub.catalog.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.BulkPriceApplyResponse;
import zelisline.ub.catalog.api.dto.BulkPricePreviewResponse;
import zelisline.ub.catalog.api.dto.BulkPricePreviewRow;
import zelisline.ub.catalog.api.dto.BulkPriceRequest;
import zelisline.ub.catalog.api.dto.BulkPriceSideRequest;
import zelisline.ub.catalog.api.dto.CatalogListScope;
import zelisline.ub.catalog.api.dto.ItemSummaryResponse;
import zelisline.ub.catalog.api.dto.PriceRounding;
import zelisline.ub.catalog.api.dto.PriceStatusFilter;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.application.PricingService;

@Service
@RequiredArgsConstructor
public class BulkPriceService {

    private static final int PAGE_SIZE = 200;

    private final ItemCatalogService itemCatalogService;
    private final ItemRepository itemRepository;
    private final PricingService pricingService;

    @Transactional(readOnly = true)
    public BulkPricePreviewResponse preview(
            String businessId,
            Collection<String> allowedItemTypeIds,
            BulkPriceRequest request
    ) {
        Computed computed = compute(businessId, allowedItemTypeIds, request);
        return computed.toPreview();
    }

    @Transactional
    public BulkPriceApplyResponse apply(
            String businessId,
            String actorUserId,
            Collection<String> allowedItemTypeIds,
            BulkPriceRequest request
    ) {
        Computed computed = compute(businessId, allowedItemTypeIds, request);
        if (computed.losses > 0 && (request == null || !request.acknowledgeLosses())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    computed.losses + (computed.losses == 1 ? " item would" : " items would")
                            + " have a buying price above the selling price. Confirm that loss to apply.");
        }
        if (computed.affected == 0) {
            return new BulkPriceApplyResponse(
                    0, computed.skippedExisting, computed.unchanged, computed.losses, computed.lowMargin);
        }
        Map<String, Item> items = loadItems(businessId, computed.lines.stream().map(BulkPriceMath.LineResult::id).toList());
        List<Item> sellingUpdates = new ArrayList<>();
        for (BulkPriceMath.LineResult line : computed.lines) {
            if (!line.affected()) {
                continue;
            }
            Item item = items.get(line.id());
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An item changed before prices could be saved. Refresh and try again.");
            }
            if (line.buyingChanged()) {
                item.setBuyingPrice(line.newBuying());
            }
            if (line.sellingChanged()) {
                item.setBundlePrice(line.newSelling());
                sellingUpdates.add(item);
            }
        }
        try {
            itemRepository.saveAll(items.values());
            for (Item item : sellingUpdates) {
                pricingService.syncSellingPriceFromBundle(
                        businessId, item.getId(), item.getBundlePrice(), actorUserId);
            }
        } catch (OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Prices changed while this update was running. Refresh and try again.");
        }
        return new BulkPriceApplyResponse(
                computed.affected,
                computed.skippedExisting,
                computed.unchanged,
                computed.losses,
                computed.lowMargin);
    }

    private Computed compute(String businessId, Collection<String> allowedItemTypeIds, BulkPriceRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose how prices should change.");
        }
        BulkPriceMath.SideOp buying = toOp(request.buying());
        BulkPriceMath.SideOp selling = toOp(request.selling());
        try {
            BulkPriceMath.validate(buying, selling);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        List<Target> targets = resolveTargets(businessId, allowedItemTypeIds, request);
        if (targets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "None of the selected items are in this catalog.");
        }
        Map<String, BigDecimal> sellingPrices = pricingService.getCurrentOpenSellingPricesForItems(
                businessId, null, targets.stream().map(Target::id).toList());
        PriceRounding rounding = request.rounding() == null ? PriceRounding.NONE : request.rounding();
        List<BulkPriceMath.LineResult> lines = new ArrayList<>(targets.size());
        int affected = 0;
        int skipped = 0;
        int unchanged = 0;
        int losses = 0;
        int lowMargin = 0;
        for (Target target : targets) {
            BigDecimal sellingPrice = sellingPrices.get(target.id());
            BulkPriceMath.LineResult line = BulkPriceMath.apply(
                    new BulkPriceMath.LineInput(target.id(), target.name(), target.buying(), sellingPrice),
                    buying,
                    selling,
                    rounding);
            lines.add(line);
            if (line.affected()) {
                affected++;
            } else if (line.skippedExisting()) {
                skipped++;
            } else {
                unchanged++;
            }
            if (line.loss()) {
                losses++;
            }
            if (line.lowMargin()) {
                lowMargin++;
            }
        }
        return new Computed(lines, affected, skipped, unchanged, losses, lowMargin);
    }

    private List<Target> resolveTargets(
            String businessId,
            Collection<String> allowedItemTypeIds,
            BulkPriceRequest request
    ) {
        if (request.selectAllMatching()) {
            Set<String> excluded = new HashSet<>();
            if (request.excludedItemIds() != null) {
                for (String id : request.excludedItemIds()) {
                    if (id != null && !id.isBlank()) {
                        excluded.add(id.trim());
                    }
                }
            }
            Page<ItemSummaryResponse> first = listPage(businessId, allowedItemTypeIds, request, 0);
            if (first.getTotalElements() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No items match this filter.");
            }
            if (first.getTotalElements() > BulkPriceMath.MAX_ITEMS) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "That filter matches more than " + BulkPriceMath.MAX_ITEMS
                                + " items. Narrow it before updating prices.");
            }
            List<String> ids = new ArrayList<>();
            int page = 0;
            Page<ItemSummaryResponse> current = first;
            while (true) {
                for (ItemSummaryResponse row : current.getContent()) {
                    if (!excluded.contains(row.id())) {
                        ids.add(row.id());
                    }
                }
                if (current.isLast()) {
                    break;
                }
                page++;
                if (page > BulkPriceMath.MAX_ITEMS / PAGE_SIZE) {
                    break;
                }
                current = listPage(businessId, allowedItemTypeIds, request, page);
            }
            return targetsFromIds(businessId, allowedItemTypeIds, ids);
        }
        List<String> ids = new ArrayList<>();
        if (request.itemIds() != null) {
            for (String id : request.itemIds()) {
                if (id != null && !id.isBlank() && !ids.contains(id.trim())) {
                    ids.add(id.trim());
                }
            }
        }
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one item.");
        }
        if (ids.size() > BulkPriceMath.MAX_ITEMS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Select at most " + BulkPriceMath.MAX_ITEMS + " items at a time.");
        }
        return targetsFromIds(businessId, allowedItemTypeIds, ids);
    }

    private List<Target> targetsFromIds(
            String businessId,
            Collection<String> allowedItemTypeIds,
            List<String> ids
    ) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, Item> loaded = loadItems(businessId, ids);
        Set<String> allowed = allowedItemTypeIds == null ? null : Set.copyOf(allowedItemTypeIds);
        List<Target> targets = new ArrayList<>();
        for (String id : ids) {
            Item item = loaded.get(id);
            if (item == null) {
                continue;
            }
            if (allowed != null && (item.getItemTypeId() == null || !allowed.contains(item.getItemTypeId()))) {
                continue;
            }
            targets.add(new Target(
                    item.getId(),
                    ProductDisplayName.forItem(item),
                    item.getBuyingPrice()));
        }
        targets.sort(Comparator.comparing(Target::name, String.CASE_INSENSITIVE_ORDER));
        return targets;
    }

    private Map<String, Item> loadItems(String businessId, List<String> ids) {
        Map<String, Item> loaded = new HashMap<>();
        for (int offset = 0; offset < ids.size(); offset += PAGE_SIZE) {
            List<String> slice = ids.subList(offset, Math.min(ids.size(), offset + PAGE_SIZE));
            for (Item item : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(slice, businessId)) {
                loaded.put(item.getId(), item);
            }
        }
        return loaded;
    }

    private Page<ItemSummaryResponse> listPage(
            String businessId,
            Collection<String> allowedItemTypeIds,
            BulkPriceRequest request,
            int page
    ) {
        CatalogListScope scope = request.catalogScope() == null ? CatalogListScope.ALL : request.catalogScope();
        String priceStatus = request.priceStatus() == null ? null : request.priceStatus().name();
        return itemCatalogService.listItems(
                businessId,
                request.search(),
                request.barcode(),
                request.categoryId(),
                request.includeCategoryDescendants(),
                request.noBarcode(),
                request.includeInactive(),
                scope,
                request.catalogRowTypes(),
                null,
                request.branchId(),
                request.itemTypeId(),
                allowedItemTypeIds,
                request.noPrice(),
                request.zeroStock(),
                request.lowStock(),
                false,
                request.inactiveOnly(),
                false,
                false,
                false,
                null,
                null,
                request.aisleId(),
                request.aisleUnset(),
                priceStatus,
                null,
                PageRequest.of(page, PAGE_SIZE));
    }

    private static BulkPriceMath.SideOp toOp(BulkPriceSideRequest side) {
        if (side == null || side.mode() == null) {
            return null;
        }
        return new BulkPriceMath.SideOp(side.mode(), side.value(), side.overwriteExisting());
    }

    private record Target(String id, String name, BigDecimal buying) {
    }

    private record Computed(
            List<BulkPriceMath.LineResult> lines,
            int affected,
            int skippedExisting,
            int unchanged,
            int losses,
            int lowMargin
    ) {
        BulkPricePreviewResponse toPreview() {
            List<BulkPriceMath.LineResult> ordered = lines.stream()
                    .filter(line -> line.affected() || line.skippedExisting())
                    .sorted(Comparator
                            .comparing((BulkPriceMath.LineResult line) -> !line.loss())
                            .thenComparing(line -> !line.lowMargin())
                            .thenComparing(line -> !line.affected())
                            .thenComparing(BulkPriceMath.LineResult::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            boolean truncated = ordered.size() > BulkPriceMath.PREVIEW_ROW_LIMIT;
            List<BulkPricePreviewRow> rows = ordered.stream()
                    .limit(BulkPriceMath.PREVIEW_ROW_LIMIT)
                    .map(line -> new BulkPricePreviewRow(
                            line.id(),
                            line.name(),
                            line.currentBuying(),
                            line.newBuying(),
                            line.currentSelling(),
                            line.newSelling(),
                            line.buyingChanged(),
                            line.sellingChanged(),
                            line.skippedExisting(),
                            line.loss(),
                            line.lowMargin()))
                    .toList();
            return new BulkPricePreviewResponse(
                    lines.size(),
                    affected,
                    skippedExisting,
                    unchanged,
                    losses,
                    lowMargin,
                    BulkPriceMath.LOW_MARGIN_PCT,
                    truncated,
                    losses > 0,
                    rows);
        }
    }
}
