package zelisline.ub.globalcatalog.application;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.globalcatalog.domain.GlobalProduct;

/**
 * Indexes tenant catalog items so global products can be matched by source id,
 * legacy import id, barcode, SKU, and several normalized name strategies.
 */
public final class TenantCatalogMatchIndex {

    private final Map<String, String> byGlobalSourceId = new HashMap<>();
    private final Map<String, String> byLegacyImportSourceId = new HashMap<>();
    private final Map<String, String> byBarcode = new HashMap<>();
    private final Map<String, String> bySku = new HashMap<>();
    private final Map<String, String> byMatchKey = new HashMap<>();

    public static TenantCatalogMatchIndex fromItems(List<Item> items) {
        TenantCatalogMatchIndex index = new TenantCatalogMatchIndex();
        for (Item item : items) {
            index.register(item);
        }
        return index;
    }

    public void register(Item item) {
        String itemId = item.getId();
        if (item.getGlobalProductSourceId() != null && !item.getGlobalProductSourceId().isBlank()) {
            byGlobalSourceId.putIfAbsent(item.getGlobalProductSourceId(), itemId);
        }
        if (item.getLegacyImportSourceId() != null && !item.getLegacyImportSourceId().isBlank()) {
            byLegacyImportSourceId.putIfAbsent(canonicalUuid(item.getLegacyImportSourceId()), itemId);
        }
        // Global catalog seed ids are the legacy export product ids.
        byLegacyImportSourceId.putIfAbsent(canonicalUuid(itemId), itemId);

        String barcode = ItemCatalogService.normalizeBarcode(item.getBarcode());
        if (barcode != null) {
            byBarcode.putIfAbsent(barcode, itemId);
        }
        if (item.getSku() != null && !item.getSku().isBlank()) {
            bySku.putIfAbsent(CatalogProductMatchNormalizer.normalizeToken(item.getSku()), itemId);
        }

        registerMatchKeys(
                itemId,
                item.getName(),
                item.getBrand(),
                item.getSize(),
                item.getVariantName()
        );
    }

    public boolean matches(GlobalProduct globalProduct) {
        return findMatchingItemId(globalProduct) != null;
    }

    public String findMatchingItemId(GlobalProduct globalProduct) {
        String bySource = byGlobalSourceId.get(globalProduct.getId());
        if (bySource != null) {
            return bySource;
        }

        String byLegacy = byLegacyImportSourceId.get(canonicalUuid(globalProduct.getId()));
        if (byLegacy != null) {
            return byLegacy;
        }

        String barcode = ItemCatalogService.normalizeBarcode(globalProduct.getBarcode());
        if (barcode != null) {
            String byBarcodeMatch = byBarcode.get(barcode);
            if (byBarcodeMatch != null) {
                return byBarcodeMatch;
            }
        }

        String skuTemplate = globalProduct.getSkuTemplate();
        if (skuTemplate != null && !skuTemplate.isBlank()) {
            String bySkuMatch = bySku.get(CatalogProductMatchNormalizer.normalizeToken(skuTemplate));
            if (bySkuMatch != null) {
                return bySkuMatch;
            }
        }

        return findByMatchKeys(
                globalProduct.getName(),
                globalProduct.getBrand(),
                globalProduct.getSize(),
                null
        );
    }

    private void registerMatchKeys(String itemId, String name, String brand, String size, String variantName) {
        for (String key : CatalogProductMatchNormalizer.matchKeys(name, brand, size, variantName)) {
            byMatchKey.putIfAbsent(key, itemId);
        }
    }

    private String findByMatchKeys(String name, String brand, String size, String variantName) {
        for (String key : CatalogProductMatchNormalizer.matchKeys(name, brand, size, variantName)) {
            String itemId = byMatchKey.get(key);
            if (itemId != null) {
                return itemId;
            }
        }
        return null;
    }

    private static String canonicalUuid(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
