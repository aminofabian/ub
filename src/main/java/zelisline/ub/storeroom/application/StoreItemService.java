package zelisline.ub.storeroom.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.storeroom.api.dto.CreateStoreItemRequest;
import zelisline.ub.storeroom.api.dto.PatchStoreItemRequest;
import zelisline.ub.storeroom.api.dto.StoreItemResponse;
import zelisline.ub.storeroom.application.StoreRoomSettingsService.LinkedStock;
import zelisline.ub.storeroom.domain.StoreItem;
import zelisline.ub.storeroom.domain.StoreRoomMode;
import zelisline.ub.storeroom.repository.StoreItemRepository;

@Service
@RequiredArgsConstructor
public class StoreItemService {

    private final StoreItemRepository storeItemRepository;
    private final StoreRoomSettingsService storeRoomSettingsService;

    @Transactional(readOnly = true)
    public List<StoreItemResponse> list(String businessId, String branchId) {
        List<StoreItem> rows = storeItemRepository.findByBusinessIdOrderByNameAsc(businessId);
        StoreRoomMode mode = storeRoomSettingsService.currentMode(businessId);
        Map<String, LinkedStock> stock = liveStockFor(businessId, mode, rows, branchId);
        return rows.stream().map(row -> toResponse(row, stock)).toList();
    }

    @Transactional
    public StoreItemResponse create(String businessId, CreateStoreItemRequest request, String branchId) {
        String barcode = normalizeBarcode(request.barcode());
        assertBarcodeAvailable(businessId, barcode, null);

        StoreRoomMode mode = storeRoomSettingsService.currentMode(businessId);
        Item linked = request.itemId() == null || request.itemId().isBlank()
                ? null
                : storeRoomSettingsService.requireLinkableItem(businessId, request.itemId());

        StoreItem row = new StoreItem();
        row.setBusinessId(businessId);
        row.setName(request.name().trim());
        row.setBarcode(
                barcode != null
                        ? barcode
                        : borrowedBarcode(businessId, linked, null));
        row.setItemId(linked == null ? null : linked.getId());
        row.setQuantity(request.quantity());
        row.setExpiryDate(request.expiryDate());
        row.setBuyingPrice(normalizeMoney(request.buyingPrice()));
        storeItemRepository.save(row);

        return toResponse(row, liveStockFor(businessId, mode, List.of(row), branchId));
    }

    @Transactional
    public StoreItemResponse update(
            String businessId,
            String id,
            PatchStoreItemRequest request,
            String branchId
    ) {
        StoreItem row = storeItemRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store item not found"));

        StoreRoomMode mode = storeRoomSettingsService.currentMode(businessId);

        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
            }
            row.setName(name);
        }
        if (request.barcode() != null) {
            String barcode = normalizeBarcode(request.barcode());
            assertBarcodeAvailable(businessId, barcode, id);
            row.setBarcode(barcode);
        }
        if (request.quantity() != null) {
            assertCountIsManual(mode, row);
            row.setQuantity(request.quantity());
        }
        if (Boolean.TRUE.equals(request.clearExpiryDate())) {
            row.setExpiryDate(null);
        } else if (request.expiryDate() != null) {
            row.setExpiryDate(request.expiryDate());
        }
        if (Boolean.TRUE.equals(request.clearBuyingPrice())) {
            row.setBuyingPrice(null);
        } else if (request.buyingPrice() != null) {
            row.setBuyingPrice(normalizeMoney(request.buyingPrice()));
        }
        if (Boolean.TRUE.equals(request.clearItemId())) {
            row.setItemId(null);
        } else if (request.itemId() != null && !request.itemId().isBlank()) {
            Item linked = storeRoomSettingsService.requireLinkableItem(businessId, request.itemId());
            row.setItemId(linked.getId());
            if (row.getBarcode() == null) {
                row.setBarcode(borrowedBarcode(businessId, linked, id));
            }
        }

        storeItemRepository.save(row);
        return toResponse(row, liveStockFor(businessId, mode, List.of(row), branchId));
    }

    @Transactional
    public void delete(String businessId, String id) {
        StoreItem row = storeItemRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store item not found"));
        storeItemRepository.delete(row);
    }

    /**
     * Reads live on-hand for the rows that mirror a product. Only worth doing while the
     * business is connected — a standalone register has no catalogue to read from.
     * Prefer branch batch stock when {@code branchId} is set so the count matches Save.
     */
    private Map<String, LinkedStock> liveStockFor(
            String businessId,
            StoreRoomMode mode,
            List<StoreItem> rows,
            String branchId
    ) {
        if (mode != StoreRoomMode.CONNECTED) {
            return Map.of();
        }
        Set<String> itemIds = new LinkedHashSet<>();
        for (StoreItem row : rows) {
            if (row.getItemId() != null && !row.getItemId().isBlank()) {
                itemIds.add(row.getItemId());
            }
        }
        return storeRoomSettingsService.liveStock(businessId, itemIds, branchId);
    }

    /**
     * A linked product's barcode, but only when no other store-room row already
     * claims it — {@code uq_store_items_business_barcode} would reject the insert.
     */
    private String borrowedBarcode(String businessId, Item linked, String excludeId) {
        if (linked == null) {
            return null;
        }
        String productBarcode = normalizeBarcode(linked.getBarcode());
        if (productBarcode == null) {
            return null;
        }
        boolean taken = excludeId == null
                ? storeItemRepository.existsByBusinessIdAndBarcode(businessId, productBarcode)
                : storeItemRepository.existsByBusinessIdAndBarcodeAndIdNot(businessId, productBarcode, excludeId);
        return taken ? null : productBarcode;
    }

    /** A linked count is owned by inventory; hand-counting it would only be overwritten. */
    private void assertCountIsManual(StoreRoomMode mode, StoreItem row) {
        if (mode == StoreRoomMode.CONNECTED && row.getItemId() != null && !row.getItemId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This store room follows inventory, so its count updates on its own. "
                            + "Unlink the product to count by hand.");
        }
    }

    private void assertBarcodeAvailable(String businessId, String barcode, String excludeId) {
        if (barcode == null) {
            return;
        }
        boolean taken = excludeId == null
                ? storeItemRepository.existsByBusinessIdAndBarcode(businessId, barcode)
                : storeItemRepository.existsByBusinessIdAndBarcodeAndIdNot(businessId, barcode, excludeId);
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Barcode already in use");
        }
    }

    private static String normalizeBarcode(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal normalizeMoney(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private StoreItemResponse toResponse(StoreItem row, Map<String, LinkedStock> stock) {
        LinkedStock linked = row.getItemId() == null ? null : stock.get(row.getItemId());
        return new StoreItemResponse(
                row.getId(),
                row.getName(),
                row.getBarcode(),
                row.getItemId(),
                row.getQuantity(),
                row.getExpiryDate(),
                row.getBuyingPrice(),
                linked == null ? null : linked.quantity(),
                linked == null ? null : linked.itemName(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
