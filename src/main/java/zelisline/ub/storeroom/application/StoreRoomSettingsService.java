package zelisline.ub.storeroom.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.storeroom.api.dto.StoreRoomSettingsResponse;
import zelisline.ub.storeroom.api.dto.UpdateStoreRoomSettingsRequest;
import zelisline.ub.storeroom.domain.StoreItem;
import zelisline.ub.storeroom.domain.StoreRoomMode;
import zelisline.ub.storeroom.domain.StoreRoomSettings;
import zelisline.ub.storeroom.repository.StoreItemRepository;
import zelisline.ub.storeroom.repository.StoreRoomSettingsRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Owns the store room's optional link to the catalogue: which mode the business
 * chose, which rows point at a product, and what those products currently hold.
 *
 * <p>When a branch is selected, counts come from that branch's active inventory
 * batches — the same source Save / Take out mutate. Without a branch, counts
 * fall back to {@code items.current_stock} (business-wide).
 */
@Service
@RequiredArgsConstructor
public class StoreRoomSettingsService {

    private final StoreRoomSettingsRepository settingsRepository;
    private final StoreItemRepository storeItemRepository;
    private final ItemRepository itemRepository;
    private final PackageVariantStockResolver stockResolver;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final BranchRepository branchRepository;

    /** A linked product's live state, as the store room should display it. */
    public record LinkedStock(BigDecimal quantity, String itemName) {
    }

    /** @return the chosen mode, or {@code null} when the merchant has not chosen yet. */
    @Transactional(readOnly = true)
    public StoreRoomMode currentMode(String businessId) {
        return settingsRepository.findById(businessId)
                .map(StoreRoomSettings::getMode)
                .orElse(null);
    }

    /** The settings row, or {@code null} before the merchant has chosen anything. */
    @Transactional(readOnly = true)
    public StoreRoomSettings settingsRow(String businessId) {
        return settingsRepository.findById(businessId).orElse(null);
    }

    @Transactional(readOnly = true)
    public StoreRoomSettingsResponse settings(String businessId) {
        StoreRoomSettings row = settingsRepository.findById(businessId).orElse(null);
        return census(businessId, row, 0);
    }

    /**
     * A partial update. Choosing {@link StoreRoomMode#CONNECTED} auto-links rows to
     * products by barcode; switching back to
     * {@link StoreRoomMode#STANDALONE} leaves links in place so a re-connect is instant.
     */
    @Transactional
    public StoreRoomSettingsResponse updateSettings(
            String businessId,
            UpdateStoreRoomSettingsRequest request
    ) {
        String rawMode = request.mode();
        boolean hasMode = rawMode != null && !rawMode.isBlank();
        boolean hasThreshold = request.approvalThreshold() != null;
        boolean clearsThreshold = Boolean.TRUE.equals(request.clearApprovalThreshold());
        boolean hasSeparateApprover = request.requireSeparateApprover() != null;
        if (!hasMode && !hasThreshold && !clearsThreshold && !hasSeparateApprover) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to update");
        }

        StoreRoomSettings row = settingsRepository.findById(businessId).orElseGet(() -> {
            StoreRoomSettings fresh = new StoreRoomSettings();
            fresh.setBusinessId(businessId);
            return fresh;
        });

        int linkedNow = 0;
        if (hasMode) {
            StoreRoomMode mode = StoreRoomMode.fromWire(rawMode);
            row.setMode(mode);
            if (mode == StoreRoomMode.CONNECTED && row.getConnectedAt() == null) {
                row.setConnectedAt(Instant.now());
            }
        }
        if (clearsThreshold) {
            row.setApprovalThreshold(null);
        } else if (hasThreshold) {
            row.setApprovalThreshold(request.approvalThreshold().setScale(4, RoundingMode.HALF_UP));
        }
        if (hasSeparateApprover) {
            row.setRequireSeparateApprover(Boolean.TRUE.equals(request.requireSeparateApprover()));
        }
        settingsRepository.save(row);

        if (hasMode && row.getMode() == StoreRoomMode.CONNECTED) {
            linkedNow = autoLinkByBarcode(businessId);
        }
        return census(businessId, row, linkedNow);
    }

    /**
     * Resolves live on-hand for linked catalogue products, keyed by catalogue item id.
     * Package variants report the count held by the product they draw stock from.
     *
     * @param branchId when present, use that branch's batch on-hand (matches stock edits);
     *                 when blank, fall back to business-wide {@code current_stock}.
     */
    @Transactional(readOnly = true)
    public Map<String, LinkedStock> liveStock(String businessId, Collection<String> itemIds) {
        return liveStock(businessId, itemIds, null);
    }

    @Transactional(readOnly = true)
    public Map<String, LinkedStock> liveStock(
            String businessId,
            Collection<String> itemIds,
            String branchId
    ) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Item> productsById = loadBusinessItems(businessId, itemIds);
        if (productsById.isEmpty()) {
            return Map.of();
        }

        String stockBranch = blankToNull(branchId);
        Map<String, BigDecimal> branchStockByItemId = Map.of();
        if (stockBranch != null) {
            branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(stockBranch, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
            Set<String> poolIds = new HashSet<>();
            for (Item product : productsById.values()) {
                poolIds.addAll(stockResolver.branchStockPoolItemIds(businessId, product));
            }
            branchStockByItemId = branchStockMap(businessId, stockBranch, poolIds);
        } else {
            // Holders only needed for the current_stock fallback path.
            Set<String> holderIds = new HashSet<>();
            for (Item product : productsById.values()) {
                holderIds.add(stockResolver.stockHolderItemId(product));
            }
            Map<String, Item> holdersById = loadBusinessItems(businessId, holderIds);
            Map<String, LinkedStock> stock = new HashMap<>();
            for (Item product : productsById.values()) {
                Item holder = holdersById.getOrDefault(stockResolver.stockHolderItemId(product), product);
                BigDecimal quantity = stockResolver.displayStockQty(product, holder.getCurrentStock());
                stock.put(product.getId(), new LinkedStock(quantity, product.getName()));
            }
            return stock;
        }

        Map<String, LinkedStock> stock = new HashMap<>();
        for (Item product : productsById.values()) {
            BigDecimal holderQty = stockResolver.sumPoolStock(product, branchStockByItemId);
            BigDecimal quantity = stockResolver.displayStockQty(product, holderQty);
            stock.put(product.getId(), new LinkedStock(quantity, product.getName()));
        }
        return stock;
    }

    private Map<String, BigDecimal> branchStockMap(
            String businessId,
            String branchId,
            Collection<String> itemIds
    ) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> stockByItemId = new HashMap<>();
        for (Object[] row : inventoryBatchRepository.sumQuantityRemainingForItemsAtBranch(
                businessId,
                branchId,
                "active",
                itemIds)) {
            stockByItemId.put((String) row[0], (BigDecimal) row[1]);
        }
        return stockByItemId;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** @throws ResponseStatusException 400 when the product is not this business's. */
    @Transactional(readOnly = true)
    public Item requireLinkableItem(String businessId, String itemId) {
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Product not found: " + itemId));
    }

    private Map<String, Item> loadBusinessItems(String businessId, Collection<String> itemIds) {
        Map<String, Item> byId = new HashMap<>();
        for (Item item : itemRepository.findAllById(itemIds)) {
            if (item.getDeletedAt() != null || !businessId.equals(item.getBusinessId())) {
                continue;
            }
            byId.put(item.getId(), item);
        }
        return byId;
    }

    /** Links unlinked rows to the business's products by exact barcode match. */
    private int autoLinkByBarcode(String businessId) {
        List<StoreItem> unlinked = storeItemRepository.findByBusinessIdAndItemIdIsNullOrderByNameAsc(businessId);
        if (unlinked.isEmpty()) {
            return 0;
        }
        int linked = 0;
        for (StoreItem row : unlinked) {
            String barcode = row.getBarcode();
            if (barcode == null || barcode.isBlank()) {
                continue;
            }
            Optional<Item> match = itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, barcode);
            if (match.isEmpty()) {
                continue;
            }
            row.setItemId(match.get().getId());
            linked++;
        }
        if (linked > 0) {
            storeItemRepository.saveAll(unlinked);
        }
        return linked;
    }

    private StoreRoomSettingsResponse census(String businessId, StoreRoomSettings row, int linkedNow) {
        int total = (int) storeItemRepository.countByBusinessId(businessId);
        int linked = (int) storeItemRepository.countByBusinessIdAndItemIdIsNotNull(businessId);
        StoreRoomMode mode = row == null ? null : row.getMode();
        return new StoreRoomSettingsResponse(
                mode == null ? null : mode.wireValue(),
                row == null ? null : row.getConnectedAt(),
                row == null ? null : row.getApprovalThreshold(),
                row != null && row.isRequireSeparateApprover(),
                total,
                linked,
                Math.max(0, total - linked),
                linkedNow);
    }
}
